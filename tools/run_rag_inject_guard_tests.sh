#!/bin/sh
# 自测 `tools/run_rag_inject_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，各有各的自欺模式：
#
#   A. **存在性断言**（`... | grep -q '某段源码'`）：pattern 写成到处都有的
#      文本就恒真。桩③④⑤⑥ 就是在测这一类。
#   B. **交叉校验**（cross_inject / check_wiring / check_texts）：最容易的失效是
#      "提取不到就静默通过" —— 函数改名后两边都取不到，字段集合变成两个空集，
#      `==` 判定为真。桩①②⑦ 专测这个。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。
#
# ⚠ 写这一份时真踩到的两个坑（写下来免得下一个人再踩）：
#   1. `_fnbody.kotlin_fn_body` 返回 **(body, line) 元组**，直接拿它做 `in`
#      判断**恒假** —— 守卫首版就是因此报了一批假红。
#   2. `_fnbody` 会抹掉字符串字面量，而"注入限定在 system 段"这条判据锚的正是
#      `role == "system"`。必须读**原文**区间（raw_fn）。桩⑧ 是这两条的回归。
#   3. 桩用 `assert s.count('某段源码') == 1` 定位。源码一改名，这条 `assert` 就抛
#      异常 —— 而**抛异常不是红**：`set -e` 下脚本直接终止，那条判据静默消失，
#      CI 只报"自测挂了"、看不到"哪条判据不再有效"。所以每个 `assert` 都带上
#      失败原因；`buildIndex` 那次锚点真的断过（0.9.2xx 给调用加了
#      `embedBatchSize = ...` 实参时），桩⑩ 就这么消失了一轮。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_rag_inject_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_inject_guard.sh
HDR=app/src/main/cpp/rag_index.h
INJ=app/src/main/java/com/xiaowan/localinference/RagInject.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ⚠ 存基线**之前**先断言磁盘上是干净的真源码。
# 否则一次异常退出会把上一轮的桩当成本轮基线存下来，之后每一轮都在错的基础上跑 ——
# 表现为"明明还原了却一直红"（本轮真踩到：一条 python 断言异常让 `（来源: ` 的桩
# 变成了基线）。这里用"基线守卫必须全绿"当判据：不对就明确报错退出，不静默继续。
if ! sh "$GUARD" > /dev/null 2>&1; then
    echo "FAIL  跑自测前工作树不干净：基线守卫已经是红的。"
    echo "      先手动还原（git checkout）再跑本自测，否则桩会被当基线存下来。"
    exit 2
fi

TMP=$(mktemp -d)
for f in "$HDR" "$INJ" "$EA" "$ENGINE" "$STORE"; do
    cp "$f" "$TMP/$(basename "$f").bak"
done
restore() {
    for f in "$HDR" "$INJ" "$EA" "$ENGINE" "$STORE"; do
        cp "$TMP/$(basename "$f").bak" "$f"
    done
    rm -rf "$TMP"
}
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    return $rc
}
reset_src() {
    for f in "$HDR" "$INJ" "$EA" "$ENGINE" "$STORE"; do
        [ -f "$TMP/$(basename "$f").bak" ] && cp "$TMP/$(basename "$f").bak" "$f"
    done
}

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() {
    name=$1
    if run_guard; then ck "$name：守卫全绿" 1
    else ck "$name：守卫全绿（实际红了）" 0; sed -n '1,80p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 桩①：Kotlin 行首标记被改（与 C++ 不一致）────────────────────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('MARK = "[知识库]"') == 1
s = s.replace('MARK = "[知识库]"', 'MARK = "[KB]"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩①（Kotlin 行首标记与 C++ 不一致）" "行首标记不一致"
reset_src

# ── 桩②：来源修饰被重新加回（契约要求两边**都没有**它）──────────────────
# 新契约：每条只有正文，行尾不许再有 `（来源: …）`（它会被模型抄进回答）。
# 这里往 C++ 侧塞回来源修饰 —— 判据必须变红（"还有来源修饰"）。
# 旧桩测的是"两边**不相等**"，方向已翻成"两边**都不出现**"，所以桩也跟着翻。
"$PY" - "$HDR" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        s += rows[(size_t) p.index];\n        s += "\\n";'
assert s.count(old) == 1, "anchor miss"
new = '        s += rows[(size_t) p.index];\n        s += "（来源: ";\n        s += "x";\n        s += "）\\n";'
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩②（来源修饰被加回）" "还有来源修饰"
reset_src

# ── 桩③：表头提前写（无条目时会留光表头 -> "非空即已注入"恒真）──────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把 `if (sb.isEmpty()) sb.append(MARK).append('\n')` 提到跳过之前
old = "            if (p.index < 0 || p.index >= rows.size) continue\n            val row = rows[p.index]\n            if (row.isEmpty()) continue\n            if (sb.isEmpty()) sb.append(MARK).append('\\n')"
new = "            if (sb.isEmpty()) sb.append(MARK).append('\\n')\n            if (p.index < 0 || p.index >= rows.size) continue\n            val row = rows[p.index]\n            if (row.isEmpty()) continue"
assert s.count(old) == 1, "anchor miss"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩③（表头提前写 = 无条目也留表头）" "Kotlin 的表头写入早于空正文跳过"
reset_src

# ── 桩④：preparedMessages 不再注入 ──────────────────────────────────────
"$PY" - "$EA" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('val rag = ragBlockFor(msgs) ?: return msgs') == 1
s = s.replace('val rag = ragBlockFor(msgs) ?: return msgs',
              'val rag: LlmEngine.RagBlock? = null; if (rag == null) return msgs', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩④（preparedMessages 不检索）" "没有调用 ragBlockFor"
reset_src

# ── 桩⑤：注入退回 system 段（分叉点被拉回 pos 0，KV 复用归零）────────────
# 这是第 4 步要修的那个现象本身：块进了 system 段，于是每轮 prompt 从 pos 0 就分叉，
# 公共前缀只剩 system 开头几个 token。旧判据（钉"注入限定在 system 段"）方向正好相反 ——
# 它把本步要消灭的写法当成了正确写法。这条桩就是钉"方向不再反"。
"$PY" - "$EA" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return RagInject.attachBeforeLastUser(msgs, rag.block)"
assert s.count(old) == 1, "anchor miss"
new = ('        val out = msgs.map { (role, content) ->\n'
       '            if (role == "system") role to RagInject.attachToSystem(content, block) else role to content\n'
       '        }\n'
       '        return out')
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑤（注入退回 system 段 = 分叉点在 pos 0）" "仍往 system 段注入"
reset_src

# ── 桩⑤b：注入在第二个函数里又拼了一份（调用点不止一处）─────────────────
# 只判"preparedMessages 里调了没有"抓不到这种漂移：两处都调，位置判据就失效。
"$PY" - "$EA" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun ragBlockFor(history: List<Pair<String, String>>): LlmEngine.RagBlock? {"
assert s.count(old) == 1, "anchor miss"
new = ("    private fun injectElsewhere(msgs: List<Pair<String, String>>, b: String): List<Pair<String, String>> {\n"
       "        return RagInject.attachBeforeLastUser(msgs, b)\n"
       "    }\n\n"
       "    private fun ragBlockFor(history: List<Pair<String, String>>): String? {")
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
# 桩⑤b：注入在**第三个**函数里再拼一份 —— 放开成"恰好两个入口"之后，
# 判据仍然必须抓到多出来的那一份（否则"放开"就变成了"放空"）。
must_red "桩⑤b（注入在第三个函数里又拼了一份）" "的调用点是"
reset_src

# ── 桩⑥：ragContext 绕过 shouldSearch（开关判据写了两处）─────────────────
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('if (!RagInject.shouldSearch(enabled, kbName, query)) null') == 1
s = s.replace('if (!RagInject.shouldSearch(enabled, kbName, query)) null',
              'if (!(enabled && kbName != null)) null', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑥（ragContext 另写了一套开关判据）" "没有走 RagInject.shouldSearch"
reset_src

# ── 桩⑦：buildIndex 不再把正文交给 save（只剩向量）──────────────────────
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('RagIndexStore.save(ctx, name, fp, dim, flat, meta, rows)') == 1
s = s.replace('RagIndexStore.save(ctx, name, fp, dim, flat, meta, rows)',
              'RagIndexStore.save(ctx, name, fp, dim, flat, meta)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑦（索引只落向量、不落正文）" "缺少承载正文的那一个"
reset_src

# ── 桩⑧：RagInject.enabled 默认改成 false（配好了也永远不用）─────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('getBoolean(KEY_ENABLED, true)') == 1
s = s.replace('getBoolean(KEY_ENABLED, true)', 'getBoolean(KEY_ENABLED, false)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑧（App 内开关默认改成关）" "默认值是 true"
reset_src

# ── 桩⑨：开关键与当前库键撞名（互相覆盖）───────────────────────────────
"$PY" - "$INJ" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('const val KEY_CURRENT = "rag_current_kb"') == 1
s = s.replace('const val KEY_CURRENT = "rag_current_kb"',
              'const val KEY_CURRENT = "rag_enabled"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑨（开关与当前库共用同一个键）" "共用同一个键"
reset_src

# ── 桩⑩：UI 建库入口不再调用引擎（按钮点了没反应）───────────────────────
"$PY" - "$EA" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('LlmEngine.buildIndex(this, name, docs') == 1, \
    "桩⑩ 的锚点失效：`startBuildIndex` 那次 `LlmEngine.buildIndex(` 调用被改名/换形参了"
s = s.replace('LlmEngine.buildIndex(this, name, docs', 'null', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
# ⚠ 判据按**两个函数各司其职**拆成了两条（0.9.190 起"问"与"做"分开），
#   所以这里点名的是**开工点那一条** —— 它才是"按钮点了没反应/建库没真跑"的判据。
must_red "桩⑩（建库按钮不再调引擎）" "开工点 startBuildIndex 真的调 LlmEngine.buildIndex"
reset_src

# ── 桩⑪：状态行读本页变量（不是引擎真值）──────────────────────────────
"$PY" - "$EA" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        val embedOk = LlmEngine.hasEmbedModel"
assert s.count(old) == 1
s = s.replace(old, "        val embedOk = false", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑪（状态行的嵌入读数不取引擎真值）" "嵌入是否驻留取引擎真值"
reset_src

# ── 桩⑬：注入侧退回"按命中顺序压紧"（用户报的「选中一条内容为空」）──────
# 这是本轮的主修对象：`rows` 的容量取自 picks.size、且按 picks 迭代填。
# 旧守卫（只审 RagInject.kt 纯函数）**完全抓不到** —— 单测喂的都是行号对齐的数组。
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ("                            val all = RagIndexStore.readEntriesAligned(ctx, kbName)\n"
       "                            val rows = ArrayList<String>(all.size)\n"
       "                            for (e in all) rows.add(e?.text.orEmpty())")
new = ("                            val rows = ArrayList<String>(picks.size)\n"
       "                            for (p in picks) {\n"
       "                                rows.add(RagIndexStore.rowText(ctx, kbName, p.index).orEmpty())\n"
       "                            }")
assert s.count(old) == 1, "anchor miss"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑬（注入侧按命中顺序压紧 = 行号错位）" "按行号对齐"
reset_src

# ── 桩⑭：对齐读取被改回"跳过空行"（返回的 list 被压紧）──────────────────
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ("        val out = ArrayList<Entry?>(lines.size)\n"
       "        for (i in lines.indices) out.add(decodeTextRow(raw, i))\n"
       "        return out")
new = ("        val out = ArrayList<Entry?>(lines.size)\n"
       "        for (i in lines.indices) {\n"
       "            if (lines[i].isEmpty()) continue\n"
       "            out.add(decodeTextRow(raw, i))\n"
       "        }\n"
       "        return out")
assert s.count(old) == 1, "anchor miss"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑭（对齐读取跳过空行 = 下标不再是行号）" "返回的 list 被压紧"
reset_src

# ── 桩⑫：drop **又去删正文**（行为退回"连正文一起删"）────────────────────
# 本轮判据反向了（ISSUE #259 第 ② 条）：旧桩测的是"drop 不删正文"—— 那本来是
# 对的行为，却在旧设计里被禁止。现在桩要**反过来打**：往 `drop` 体内塞回删正文
# 那一行，守卫必须当场变红。否则"行为退回去"这件事没有任何东西挡得住。
"$PY" - "$STORE" <<'STUBPY'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        return runCatching { f.delete() }.getOrDefault(false)\n    }"
assert s.count(old) == 1, "anchor miss：drop 的返回那一行"
new = ("        runCatching { textsFile(ctx, name)?.delete() }\n"
       "        return runCatching { f.delete() }.getOrDefault(false)\n    }")
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
STUBPY
must_red "桩⑫（drop 又去删正文）" "drop **只删索引**，不碰正文"
reset_src


# ── 收尾：还原后守卫必须重回全绿 ───────────────────────────────────────
must_green "收尾：全部桩已还原"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
