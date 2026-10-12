#!/bin/sh
# 自测 `tools/run_rag_archive_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 守卫判据全是 grep/awk 的存在性与结构性断言，它有三个自欺模式：
#
#   1. **恒真**：pattern 写成到处都有的文本，真 bug 改回去照样 PASS。
#      本族最容易恒真的两处 —— "信封不含向量字段"（解释为什么不带向量的
#      注释里就有 `vector`）与"导出不引用模型"（注释里必然出现"嵌入模型"）——
#      都靠 `nocomment` 剥注释才不是恒假。
#   2. **锚错位置**：钉"文件里出现过某函数"而不是"**在这条路径里**按这个顺序
#      做了这件事" —— 前者在定义处就恒真。"格式名判据在读 entries 之前"、
#      "删索引在写正文之前"、"上限判据在逐条收之前"就是为此存在的。
#   3. **恒假后被静音**：pattern 与源码差一个字符 → 守卫永远 FAIL。
#
# 桩全部来自"真正会写错的写法"，是本特性**唯一不可归因**的那几种失败方向：
#   · 信封连向量一起带走（跨机器导入 -> 用旧向量命中新正文，接口 200、排序像随机）
#   · 格式名对不上也尽力解析（选错文件被读成"笔记是空的"）
#   · 格式名判据挪到读 entries 之后（顺序反了等于没拒绝）
#   · 坏行不计数（"少了几条"看起来像 bug，无法归因）
#   · 超限静默截断（用户以为文件里就这么多）
#   · 导入覆盖现有库（原有笔记静默消失，过几天才发现）
#   · 导入后留着旧索引（列表说"能用"，而正文已经比它多了）
#   · 先写正文再删索引（中间被杀 -> 新正文 + 老索引）
#   · 导出要求嵌入模型在线（换机场景下压根用不了）
#   · 请求码与选模型共用（选完文件什么都没发生）
#   · 导入不弹确认（不可撤销的落盘）
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 不需要工具链，只要 sh + python3。运行：sh tools/run_rag_archive_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_archive_guard.sh
SRC=app/src/main/java/com/xiaowan/localinference
RA=$SRC/RagArchive.kt
RS=$SRC/RagIndexStore.kt
LE=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
# ⚠ 切分原语（软标点表 / 切点选择 / 字符边界）在 TextBoundary —— 它**与建库那一路
#   共用**。打桩"切段"这件事已经不可能只改 RagArchive，所以这份源码也要进备份/还原，
#   否则桩改在它身上就收不回来（下一次跑守卫的基线直接是红的）。
TB=$SRC/TextBoundary.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RA" "$TMP/ra.bak"; cp "$RS" "$TMP/rs.bak"
cp "$LE" "$TMP/le.bak"; cp "$ACT" "$TMP/act.bak"; cp "$TB" "$TMP/tb.bak"
restore() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/rs.bak" "$RS";
            cp "$TMP/le.bak" "$LE"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tb.bak" "$TB"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

reset_src() { cp "$TMP/ra.bak" "$RA"; cp "$TMP/rs.bak" "$RS";
              cp "$TMP/le.bak" "$LE"; cp "$TMP/act.bak" "$ACT"; cp "$TMP/tb.bak" "$TB"; }

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

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

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,60p' "$TMP/guard.out"; fi

# ── 1. 桩①：信封**连向量一起带走**（本特性唯一不可归因的错法）────────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            arr.put(JSONObject().put("t", e.text).put("s", e.source))"""
new = """            arr.put(JSONObject().put("t", e.text).put("s", e.source).put("vector", e.vector))"""
assert s.count(old) == 1, "桩①：没找到 encode 里写条目的那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩①（信封带上向量）" "信封**不含**任何向量"
reset_src

# ── 2. 桩②：格式名对不上也"尽力解析"（选错文件读成"笔记是空的"）──────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (fmt.isNotEmpty() && fmt != FORMAT) {"""
new = """        if (false) {"""
assert s.count(old) == 1, "桩②：没找到格式名判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩②（格式名对不上也尽力解析）" "格式名对不上就返回 error"
reset_src

# ── 3. 桩③：格式名判据挪到读 entries **之后**（顺序反了等于没拒绝）───────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val arr = root.optJSONArray("entries")
            ?: return fail("文件里没有 entries 列表")"""
new = """        val arr = root.optJSONArray("entries")
            ?: return fail("文件里没有 entries 列表")
        root.optJSONArray("entries")"""
assert s.count(old) == 1, "桩③：没找到 entries 读取那一行"
# 把格式名判据整段挪到 entries 读取之后
s = s.replace(old, """        val arr0 = root.optJSONArray("entries")
            ?: return fail("文件里没有 entries 列表")
        val arr = arr0""", 1)
open(p, 'w', encoding='utf-8').write(s)
EOF
# 说明：这一桩**不**能让"格式名判据在读 entries 之前"变红（判据行序没动）——
# 真正会让它变红的是把 if 挪到 optJSONArray 之后。下面那一段就是这么改的。
"$PY" - "$RA" <<'EOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val fmt = root.optString("format", "")
        if (fmt.isNotEmpty() && fmt != FORMAT) {
            return fail("这不是本应用的知识库文件（format = 「$fmt」，应为「$FORMAT」）")
        }"""
new = """        val fmt = root.optString("format", "")
        root.optJSONArray("entries")
        if (fmt.isNotEmpty() && fmt != FORMAT) {
            return fail("这不是本应用的知识库文件（format = 「$fmt」，应为「$FORMAT」）")
        }"""
assert s.count(old) == 1, "桩③b：没找到格式名判据块"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩③（格式名判据挪到读 entries 之后）" "格式名判据在**读 entries 之前**"
reset_src

# ── 4. 桩④：坏行**不计数**（"少了几条"看起来像 bug）─────────────────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (e == null) { skipped++; continue }"""
new = """            if (e == null) { continue }"""
assert s.count(old) >= 1, "桩④：没找到坏行计数那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
EOF
must_red "桩④（坏行静默丢弃、不计数）" "坏行**计数**"
reset_src

# ── 5. 桩⑤：超限判据消失（静默截断，用户以为文件里就这么多）─────────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (arr.length() > MAX_IMPORT_ENTRIES) {"""
new = """        if (false) {"""
assert s.count(old) == 2, "桩⑤a：上限判据应在两处（数组 / 信封）"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
EOF
must_red "桩⑤a（超限判据被短路）" "上限判据在逐条收**之前**"
reset_src

# ── 5b. 桩⑤b：上限常量不再进判据（阈值写死成 999999 = 形同没有）──────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (arr.length() > MAX_IMPORT_ENTRIES) {"""
new = """        if (arr.length() > 999999) {"""
assert s.count(old) == 2, "桩⑤b：上限判据应在两处"
open(p, 'w', encoding='utf-8').write(s.replace(old, new))
EOF
must_red "桩⑤b（阈值写死）" "上限判据在逐条收**之前**"
reset_src

# ── 6. 桩⑥：导入**覆盖**现有库（原有笔记静默消失）──────────────────────
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        val after = before + encodeTexts(incoming)"""
new = """        val after = encodeTexts(incoming)"""
assert s.count(old) == 1, "桩⑥：没找到追加那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑥（导入整份覆盖）" "追加在**末尾**"
reset_src

# ── 7. 桩⑦：导入后**留着旧索引**（列表说"能用"，正文已经比它多）──────────
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        indexFile(ctx, name)?.let { runCatching { it.delete() } }"""
new = """        // 索引留着不管"""
assert s.count(old) == 1, "桩⑦：没找到删索引那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑦（导入后不删旧索引）" "合并后**删掉旧索引**"
reset_src

# ── 8. 桩⑧：**先写正文、再删索引**（中间被杀 -> 新正文 + 老索引）─────────
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        indexFile(ctx, name)?.let { runCatching { it.delete() } }
        if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return -1"""
new = """        if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return -1
        indexFile(ctx, name)?.let { runCatching { it.delete() } }"""
assert s.count(old) == 1, "桩⑧：没找到删索引/写正文那一对"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑧（先写正文再删索引）" "删索引在**写正文之前**"
reset_src

# ── 9. 桩⑨：导入**逐条 encode**（换机场景下模型没装，用不了）─────────────
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (incoming.isEmpty()) return -1"""
new = """        if (incoming.isEmpty()) return -1
        appendEntries(ctx, name, incoming)"""
assert s.count(old) == 1, "桩⑨：没找到 importEntries 开头"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑨（导入改走 appendEntries，要模型在线）" "不经过 appendEntries"
reset_src

# ── 10. 桩⑩：导出要求嵌入模型在线（换机场景下压根用不了）────────────────
"$PY" - "$LE" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (RagIndexStore.dirOf(ctx, name) == null) return null
        val rows = RagIndexStore.exportEntries(ctx, name)"""
new = """        if (RagIndexStore.dirOf(ctx, name) == null) return null
        if (!hasEmbedModel) return null
        embedPath ?: return null
        val rows = RagIndexStore.exportEntries(ctx, name)"""
assert s.count(old) == 1, "桩⑩：没找到 exportRagArchive 开头"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑩（导出要求嵌入模型在线）" "导出不碰 native"
reset_src

# ── 11. 桩⑪：导入复用"选模型"的请求码（选完文件什么都没发生）─────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        private const val REQ_IMPORT_RAG = 44"""
new = """        private const val REQ_IMPORT_RAG = 42"""
assert s.count(old) == 1, "桩⑪：没找到知识库导入请求码"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑪（知识库导入与选模型共用请求码）" "导入用**独立**请求码"
reset_src

# ── 12. 桩⑫：导入**不弹确认**（不可撤销的落盘）─────────────────────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 锚点本轮跟着签名改了（ISSUE #259）：`.txt / .md` 那条路改成直接落盘之后，
#   它与「导入库」共用同一个确认框，于是签名多了 `fromTextFile`。
#   桩若还锚老签名，`assert` 直接失败 -> 自测把"桩没打上"报成 FAIL；
#   桩漂了最坏的结果是它悄悄不再挡住任何东西（假绿），所以必须跟着改。
old = """    private fun confirmRagImport(parsed: RagArchive.Parsed, fromTextFile: Boolean = false) {"""
new = """    private fun confirmRagImportDisabled(parsed: RagArchive.Parsed, fromTextFile: Boolean = false) {"""
assert s.count(old) == 1, "桩⑫：没找到确认函数"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑫（导入不弹确认）" "落盘前先确认"
reset_src

# ── 13. 桩⑬：导入后**不切到目标库**（用户看不到结果，以为没导入）─────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                    loadRagDoc(target)"""
new = """                    // 不切库"""
assert s.count(old) == 1, "桩⑬：没找到 doImportRagEntries 里的 loadRagDoc"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑬（导入后不切到目标库）" "导入成功后切到目标库"
reset_src

# ── 14. 桩⑭：导出 MIME 退回 text/plain（.json 被当纯文本，零报错）─────────
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                    val where = LogExport.saveText(this, fileName, json, mime = "application/json")"""
new = """                    val where = LogExport.saveText(this, fileName, json)"""
assert s.count(old) == 1, "桩⑭：没找到知识库导出落盘那一行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑭（导出 MIME 退回 text/plain）" "导出落盘声明 application/json"
reset_src

# ── 15. 桩⑮：导入路径回填失败仍报"已导入"（用户以为成功了）────────────────
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return -1"""
new = """        writeTexts(d, after.toByteArray(Charsets.UTF_8))"""
assert s.count(old) == 1, "桩⑮：没找到写正文的失败判据"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑮（正文写失败却不报失败）" "正文写失败**必须**报失败"
reset_src

# ── 16. 桩⑯：多行 JSONL 被 JSONObject 悄然吃成一条 ────────────────────────
#
# 这是本轮抓到的那条**产品侧**缺陷的原始形状：`JSONObject` 从第一个 `{` 吃到
# 配平的 `}` 就收工、后面全忽略，于是 3 行 JSONL 能 parse 成一个**合法的单条
# 对象**。按旧逻辑（只看 `has("t") && has("s")`）就认了它 ——
# 用户选进 3 条，导入后只剩 1 条，而 `skipped == 0`、`error == null`：
# 确认框说"文件里有 1 条"、Toast 说"已导入 1 条"，另两条无声消失。
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            val nonBlank = text.lines().count { it.isNotBlank() }
            if (nonBlank <= 1 && root.has("t") && root.has("s")) {"""
new = """            if (root.has("t") && root.has("s")) {"""
assert s.count(old) == 1, "桩⑯：没找到「数非空行 + 单行判定」这两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
# 打掉"行数前提"之后，真正变红的是**行数前提**那一条（而"多行按行解析"那条
# 判的是 `if (nonBlank > 1)` 这个**分支**还在不在 —— 分支还在，只是永远不成立）。
# 这里如实指向真实变红的那一条，不指向"看起来更像"的那一条。
must_red "桩⑯（多行 JSONL 被当成单条：静默丢 N-1 条）" "单行 JSONL 收下、且判据里带"
reset_src

# ── 17. 桩⑰：单行判定丢掉"行数"前提，退回只看 has t/s ─────────────────────
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (nonBlank <= 1 && root.has("t") && root.has("s")) {"""
new = """            if (root.has("t") && root.has("s")) {"""
assert s.count(old) == 1, "桩⑰：没找到单行条目那一格"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩⑰（单行判定丢掉行数前提 -> 多行又被吃成一条）" "单行 JSONL 收下、且判据里带"
reset_src

# ── 18. 桩⑱：两个上限**分家**（导入放行 16384、建库只切 4096）────────────
# 症状：导入通过了、建库却被截断 —— 用户以为两万条全进来了，
# 而"哪些没进来"没有任何痕迹（正是本轮给 chunk 加 truncated 出参要修的那件事）。
# 判据锚"两个数**相等**"而不是"等于 16384"：写死数字的话，两边一起改歪也能全绿。
"$PY" - "$RS" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "val maxChunks: Int = 16384,"
assert s.count(old) == 1, "桩⑱：没找到 maxChunks 默认值"
open(p, 'w', encoding='utf-8').write(s.replace(old, "val maxChunks: Int = 4096,", 1))
EOF
must_red "桩⑱（导入上限与建库上限分家）" "两个上限不相等"
reset_src

# ── 19. 桩⑲：切段自己又实现了一套软标点（不再走共享原语）────────────────
#
# 这是本轮把重复收掉的**那个**形态：导入这一路抄了第二份表 + 第二份控制流，
# 而那份没有任何判据绑着（改一处另两处不会红）。桩打回那个形态，守卫必须变红
# —— 否则"共用一份"这件事只是注释里的愿望。
"$PY" - "$RA" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            var cut = TextBoundary.pickCut(line, pos, end, sepMaxWidth)\n"
assert s.count(old) == 1, "桩⑲：没找到调共享切点函数的那一行"
# ⚠ 这里必须把**所有**提到它的地方都拿掉：SPLIT 里还有一行注释写着
#   `[TextBoundary.pickCut]`，只换那一行调用的话判据（grep 函数体）照样绿 ——
#   桩就白打了。所以按**出现次数**改，并断言"改完一次都不剩"。
s = s.replace(old, "            var cut = -1   // 本地又抄了一份表 + 一份控制流\n", 1)
s = s.replace("TextBoundary.pickCut", "本地私有切点")
assert s.count("TextBoundary.pickCut") == 0, "桩⑲：还有残留（判据会假绿）"
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑲（切段又实现了一套软标点）" "两级降级：软标点 -> 硬切"
reset_src

# ── 20b. 桩⑳b：空白级又插回切段里（"整块丢字"的触发源）────────────────────
#
# ⚠ 判据锚"本函数里**没有** isWhitespace()"。只查"有硬切"是不够的：
#   一份"空白级 + 硬切"的实现照样有硬切，判据全绿 —— 而它正是这一轮删掉的那个
#   形态，实测丢字（"word " × 400 丢 4 字）。桩就把它原样插回去。
"$PY" - "$RA" <<'STUBEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            if (cut <= pos) {\n                // \u2461 硬切"
new = "            if (cut <= pos) {\n                cut = -1\n                for (i in end - 1 downTo pos + 1) {\n                    if (line[i].isWhitespace()) { cut = i; break }\n                }\n            }\n            if (cut <= pos) {\n                // \u2461 硬切"
assert s.count(old) == 1, "桩⑳b：没找到硬切之前那一格"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
STUBEOF
must_red "桩⑳b（空白级又插回切段里）" "切段里**没有空白级**"
reset_src

# ── 20c. 桩⑳c：收尾又在循环里 trim（段尾 trimEnd + 下轮 trimStart 吃两次）──
#
# ⚠ 这一桩锚的是**收尾那一刀**，与空白级是独立的两件事：只删空白级、却保留
#   循环里的 trim()，照样丢字。若判据只查"没有空白级"，这一形态会漏过去。
"$PY" - "$RA" <<'STUBEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "            val seg = line.substring(pos, end0)\n            if (seg.isNotEmpty()) out.add(seg)\n            pos = maxOf(end0, pos + 1)"
new = "            val seg = line.substring(pos, end0).trim()\n            if (seg.isNotEmpty()) out.add(seg)\n            pos = maxOf(cut, pos + 1)"
assert s.count(old) == 1, "桩⑳c：没找到切段的收尾那一刀"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
STUBEOF
must_red "桩⑳c（收尾又在循环里 trim）" "切段不做内部 trim"
reset_src

# ── 20. 桩⑳：硬切不再按字符边界（共享原语里直接往前收一格）───────────────
#
# ⚠ 判据锚的 `boundaryBefore` 现在**调用在 RagArchive、定义在 TextBoundary**。
#   桩打的是**定义**那一处，并且必须让"定义"这件事真的消失 —— 只把函数名改掉
#   是不够的：`boundaryBefore(text, cutRaw)` 那个**调用点**里还留着这个名字，
#   而判据（`printf SPLIT | grep boundaryBefore`）锚的正是嵌入函数体里的这一行
#   —— 于是桩打了、判据照样绿（**假绿**，本仓库踩过的那一族）。
#   所以桩做的是"把整个函数体从源码里删掉"：调用点、定义、名字，一起没有。
"$PY" - "$TB" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ① 切点选择里的那一跳退回裸下标（= 切在代理对中间的原始错法）
call = "                val cut = boundaryBefore(text, cutRaw)\n"
assert s.count(call) == 1, "桩⑳：没找到切点选择里那一跳"
s = s.replace(call, "                val cut = cutRaw   // 不再对齐字符边界\n", 1)
# ② 定义整个删掉
old = "    fun boundaryBefore(s: String, i: Int): Int {\n"
assert s.count(old) == 1, "桩⑳：没找到边界函数的定义（锚点漂了，别静默放行）"
i = s.index(old)
j = s.index("\n    }\n", i) + len("\n    }\n")
s = s[:i] + s[j:]
assert s.count("boundaryBefore") == 0, "桩⑳：还有残留（判据会假绿）"
open(p, 'w', encoding='utf-8').write(s)
EOF
must_red "桩⑳（硬切不再按字符边界）" "硬切按**字符边界**"

# ── 21. 桩㉑：进度闸又写回**编译不过的 `return@logSink`** ──────────────────
#
# 这一桩来自 PR #261 的一轮真事故：守卫当时锚的是 `grep -q 'return@logSink'`，
# 也就是把"进度那一支用 `return@logSink` 提前返回"当成了**正确写法**去正面钉住。
# 而 `LlmEngine.logSink` 是 `((String) -> Unit)?` —— 普通函数类型、非 inline，
# 标签不生成内联点，`return@logSink` 在真编译器下是
# `error: 'return' is prohibited here.`（EngineActivity 2015 / 2018 两处）。
# 结果：**守卫全绿、CI 的 `scripts/kt_check.sh` 红**，而守卫的序号（⑫）在
# kt_check 之前 —— 一个把编译错误钉住的守卫比没有守卫更坏：它让"跑过了"变成假证据。
# 桩就地改回旧写法，断言该红、且指得出是哪一条。
"$PY" - "$ACT" <<'EOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """                if (now - lastProgressAt >= PROGRESS_MS) {
                    lastProgressAt = now
                    ui.post { updateProgressLine(line) }
                }"""
new = """                if (now - lastProgressAt < PROGRESS_MS) return@logSink
                lastProgressAt = now
                ui.post { updateProgressLine(line) }
                return@logSink"""
assert s.count(old) == 1, "桩㉑：没找到进度节流那一段"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
EOF
must_red "桩㉑（进度闸退回编译不过的 return@logSink）" "非法 return"
reset_src

echo ""
if [ "$bad" = "0" ]; then echo "=== 知识库导入/导出守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 知识库导入/导出守卫自测：PASS $ok / FAIL $bad ==="; exit 1
