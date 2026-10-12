#!/bin/sh
# 自测 `tools/run_rag_index_guard.sh`：把它对着几份**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫的判据分两类，各自都有自欺模式：
#
#   A. **存在性断言**（`... | grep -q '某段源码'`）：pattern 写成到处都有的
#      文本就恒真。桩②（buildIndex 不检查模型）与桩④（decode 不校验 magic）
#      就是在测这一类。
#   B. **交叉校验**（cross_layout / check_fp_fields）：最容易的失效是
#      "提取不到就静默通过" —— 比如函数改名后 `fingerprint_of` 里什么都取不到，
#      字段集合变成两个空集，`==` 判定结果为真。桩⑤⑥ 专测这个：
#      改了字段而另一边没跟，守卫必须红。
#
# 在 CI 里"跑一遍看它绿"发现不了（它本来就绿）。唯一有效的做法是对着
# **已知该红**的桩跑。
#
# 桩全部是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移，
# 那时测的就是一份没人看的旧代码）。
#
# ⚠ 本轮真踩到的坑（写进这里，免得下一个人再踩）：
#   `_fnbody.py` 会**抹掉字符串字面量**（那是它的本职：判据锚实现不锚字面量）。
#   于是首版守卫里 grep `'FILE\.tmp'` 与 `'c == "-"'` 的断言**恒假**（桩都不需要，
#   守卫自己就红了）。改成判结构（"有几份文件名"、"字符比较出现几次"）才对。
#   桩⑧ 就是这一条的回归：它断言"改回字面量写法时守卫仍能正确判定"。
#
# 不需要工具链，只要 sh + python3。CI 里可直接跑。
#
# 运行：sh tools/run_rag_index_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_rag_index_guard.sh
HDR=app/src/main/cpp/rag_index.h
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ARCHIVE=app/src/main/java/com/xiaowan/localinference/RagArchive.kt
TB=app/src/main/java/com/xiaowan/localinference/TextBoundary.kt
# rank 的「全挡」阈值判据锚在**测试文件**上（它比的是用例数据与阈值的数值关系，
# 不是源码里某个字面量）—— 所以桩要能改这份测试，得先把它纳入备份/还原。
RIST=tools/rag_index/RagIndexStoreTest.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$HDR" "$TMP/hdr.bak"; cp "$STORE" "$TMP/store.bak"; cp "$ENGINE" "$TMP/engine.bak"
cp .cnb.yml "$TMP/cnb.bak"; cp scripts/ci-apk-stage.sh "$TMP/ci-stage.bak"; cp "$RIST" "$TMP/rist.bak"; cp "$ARCHIVE" "$TMP/archive.bak"; cp "$TB" "$TMP/tb.bak"
restore() {
    cp "$TMP/hdr.bak" "$HDR"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/engine.bak" "$ENGINE"
    cp "$TMP/cnb.bak" .cnb.yml; cp "$TMP/ci-stage.bak" scripts/ci-apk-stage.sh; cp "$TMP/rist.bak" "$RIST"; cp "$TMP/archive.bak" "$ARCHIVE"; cp "$TMP/tb.bak" "$TB"
    rm -rf "$TMP"
}
# 只还原 .cnb.yml（桩㉟ 用）：其它桩改的是三个源码文件，恢复它们由 reset_src 负责。
gold_restore_cnb() { cp "$TMP/cnb.bak" .cnb.yml; cp "$TMP/ci-stage.bak" scripts/ci-apk-stage.sh; }
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
    cp "$TMP/hdr.bak" "$HDR"; cp "$TMP/store.bak" "$STORE"; cp "$TMP/engine.bak" "$ENGINE"
    cp "$TMP/cnb.bak" .cnb.yml; cp "$TMP/ci-stage.bak" scripts/ci-apk-stage.sh; cp "$TMP/rist.bak" "$RIST"; cp "$TMP/archive.bak" "$ARCHIVE"; cp "$TMP/tb.bak" "$TB"
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

# ── 桩①：cross_layout 的 magic 两边不一致 ───────────────────────────────
# 改 Kotlin 的 MAGIC，C++ 不动。症状会是"把指纹当向量读"（维度恰好对得上）。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('MAGIC = "RAG1"') == 1
s = s.replace('MAGIC = "RAG1"', 'MAGIC = "RAG9"', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩①（Kotlin MAGIC 与 C++ 不一致）" "magic 两边一致"
reset_src

# ── 桩②：cross_layout 的头字段偏移被改 ──────────────────────────────────
# 把 OFF_DIM 从 8 改成 12（与 C++ 的排布错开）。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('OFF_DIM = 8') == 1
s = s.replace('OFF_DIM = 8', 'OFF_DIM = 12', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩②（OFF_DIM 偏移被改）" "OFF_DIM == 8"
reset_src

# ── 桩③：指纹字段被打散（Kotlin 少算 mtime）─────────────────────────────
# 这是"更宽松"方向的失效：就地替换权重后旧索引被继续使用。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('sb.append("m").append(fp.modelMtime)') == 1
s = s.replace('sb.append("m").append(fp.modelMtime)', 'sb.append("z").append(fp.modelMtime)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩③（Kotlin 指纹字段 m 被改掉）" "指纹字段集合两边一致"
reset_src

# ── 桩④：C++ 侧指纹字段被打散（C++ 少算 size）───────────────────────────
"$PY" - "$HDR" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('s += "b";') == 1
s = s.replace('s += "b";', 's += "z";', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩④（C++ 指纹字段 b 被改掉）" "指纹字段集合两边一致"
reset_src

# ── 桩⑤：buildIndex 不再检查嵌入模型是否加载 ────────────────────────────
# 症状：整库向量全空而"建库成功"照样返回，检索永远为空。
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (!hasEmbedModel) return "尚未加载嵌入模型（索引必须用它算向量）"\n'
assert s.count(old) == 1, "桩⑤：没找到 hasEmbedModel 判定"
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑤（buildIndex 不检查嵌入模型）" "buildIndex 检查了嵌入模型是否已加载"
reset_src

# ── 桩⑥：search 不再做指纹比对（换模型后继续用旧索引）──────────────────
# ⚠ 指纹构造在 buildIndex 与 search 里**各有一处**（两份都该有，那是刻意的），
# 所以不能全局 replace —— 必须**只在 search 函数体内**改。
# 走"按行号定位"而不是按函数体文本：`_fnbody` 返回的是**去注释去字符串后的**
# 文本，与原文并不逐字节相同，拿它回原文替换必然对不上（本轮真踩到）。
"$PY" tools/rag_index/stub_search_fp.py "$ENGINE"
must_red "桩⑥（search 不做指纹比对）" "search 按当前模型构造指纹"
reset_src

# ── 桩⑦：save 改成直接写正式文件（非原子落盘）──────────────────────────
# 症状：写到一半被杀留下半截文件（Android 杀后台很常见）。
#
# ⚠ 桩**不能**只改临时文件名的字符串 —— `_fnbody` 会抹掉字符串字面量
# （那是它的本职：判据锚实现不锚字面量），`File(d, "$FILE.tmp")` 与
# `File(d, FILE)` 在它眼里完全一样，于是那样的桩**照样绿**（本轮真踩到）。
# 真实的退化形态是"不再有独立的临时句柄"：把 `val tmp = ...` 那一行整个去掉，
# 后续写盘直接落到正式文件。桩按这个形态造。
"$PY" tools/rag_index/stub_drop_line.py "$STORE" save 'val tmp = File(d,'
must_red "桩⑦（save 不再走 tmp 中转）" "save 用了独立的临时句柄"
reset_src

# ── 桩8：decode 不再校验 magic（把任意文件当索引读）
# ⚠ 按行定位而不是按字面量：那一行里有字符串（会被 `_fnbody` 抹掉），
# 用字面量匹配会找不到（本轮真踩到）。
"$PY" tools/rag_index/stub_drop_line.py "$STORE" decode 'Charsets.US_ASCII) != MAGIC'
must_red "桩⑧（decode 不校验 magic）" "decode 校验 magic"
reset_src

# ── 桩⑨：dirOf 绕过 sanitizeName（目录穿越）─────────────────────────────
# ⚠⚠ 锚点跟着源码改过：`dirOf` 现在有**两处** `sanitizeName` —— 第一处净化用户
#   输入的名字，第二处净化**表里查出来的目录名**（表文件在私有目录里、用户可以
#   手工改，所以那一处不能省）。旧桩只打掉第一处，于是第二处照样让
#   `grep -q 'sanitizeName'` 命中，判据**不变红** —— 桩没打上（假绿）。
#   桩本来的意图是"dirOf 完全绕过净化"，所以两处一起打掉。
"$PY" - "$STORE" <<'STUB9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old1 = '        val safe = sanitizeName(name) ?: return null'
assert s.count(old1) == 1, "桩⑨：没找到 dirOf 的第一处净化"
s = s.replace(old1, '        val safe = name', 1)
old2 = '        return File(rootDir(ctx), sanitizeName(mapped) ?: safe)'
assert s.count(old2) == 1, "桩⑨：没找到 dirOf 的第二处净化（表里那个目录名）"
s = s.replace(old2, '        return File(rootDir(ctx), mapped)', 1)
open(p, 'w', encoding='utf-8').write(s)
STUB9
must_red "桩⑨（dirOf 绕过 sanitizeName）" "dirOf 走了 sanitizeName"
reset_src


# ── 桩⑩：函数被改名（提取失效必须红，不能静默变绿）─────────────────────
# 这是 B 类自欺的**核心**：提取不到 -> 空集 -> "两个空集相等" -> 假绿。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('fun fingerprintOf(') == 1
s = s.replace('fun fingerprintOf(', 'fun fingerprintOfRenamed(', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑩（Kotlin fingerprintOf 改名 = 提取失效）" "Kotlin 侧提取不到 fingerprintOf"
reset_src

# ── 桩⑪：C++ 侧 kMagic 改名（提取失效）─────────────────────────────────
"$PY" - "$HDR" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
assert s.count('kMagic') >= 1
s = s.replace('kMagic', 'kMagicX')
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑪（C++ kMagic 改名 = 提取失效）" "C++ 侧能提取到 kMagic"
reset_src

# ── 桩⑫：search 把三态合成一个 null（空库每次触发重建）─────────────────
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            is RagIndexStore.Load.empty -> return Search.Hits(RagIndexStore.Ranked(emptyList(), Float.NaN, 0))'
assert s.count(old) == 1, "桩⑫：没找到 empty 分支"
# 三态退化成两态：空库被当成"没有可用索引"（每次检索都触发重建）。
s = s.replace(old, '            is RagIndexStore.Load.empty -> return Search.NoIndex("空", "x", false)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑫（search 把 empty 当 none）" "search 的 empty 分支落到「有索引但 0 条」"
reset_src

# ── 桩⑬：正文 hash 被从指纹里摘掉 ────────────────────────────────────────
# 这是**最贵**的一种"更宽松"：用户改了正文，索引照旧被当成可用 ->
# 用旧语义的向量命中、注入新写进去的正文（排序像随机，接口 200、日志干净）。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        sb.append("x").append(textsHash).append'
assert s.count(old) == 1, "桩⑬：没找到正文 hash 那一行"
s = s.replace(old, '        // sb.append("x").append(textsHash).append', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑬（指纹不再包含正文 hash）" "指纹字段集合两边一致"
reset_src

# ── 桩⑭：textsHash 形参加了默认值（少传就能静默漏算）───────────────────
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'fun fingerprintOf(fp: FpInput, textsHash: String, rows: Int): String {'
assert s.count(old) == 1, "桩⑭：没找到 fingerprintOf 签名"
s = s.replace(old, 'fun fingerprintOf(fp: FpInput, textsHash: String = "", rows: Int): String {', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑭（textsHash 有了默认值 = 漏算可以静默发生）" "有默认值"
reset_src

# ── 桩⑭b：rows 形参加了默认值（"每次检索都判索引不可用"的那个真根因）──────
# 这不是理论风险：`rows: Int = -1` 在真机上就是每一次检索都报
#   [知识库] 索引不可用（"tes2"）：正文行数 变了：索引 正文行数=2 ≠ 当前 -1
# 因为 `r` 是 compareFp 的**必比字段**，索引里写的永远是真实行数 ——
# 省略 rows 不是"退化成不比较"，而是拿 -1 去比 2。所以形参不得有默认值。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'fun fingerprintOf(fp: FpInput, textsHash: String, rows: Int): String {'
assert s.count(old) == 1, "桩⑭b：没找到 fingerprintOf 签名"
s = s.replace(old, 'fun fingerprintOf(fp: FpInput, textsHash: String, rows: Int = -1): String {', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑭b（rows 有了默认值 = 拿哨兵值比真实行数）" "rows 有默认值"
reset_src

# ── 桩⑭c：search 省略 rows（期望指纹的 r 退化成哨兵值）──────────────────
# 判据只判"有没有调 fingerprintOf"时这一桩**绿**：它确实调了，
# 只是没传第三个实参。而后果是每一次检索都被判"索引不可用"。
#
# ⚠ 锚点必须**唯一**：`0.9.236` 起 `indexUsableForRewrite` 里也有同样一行
#   （它同样要现算 rows —— 与检索同源），所以这里按**前一行**（那个函数独有的
#   特征行）定位，而不是按那一行本身（`count(old) == 1` 会当场断言失败）。
#   两个调用点**都**必须带 `rowsOf`，所以下面还有桩⑭d 钉第二个。
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('        val fp = RagIndexStore.fingerprintOf(\n'
       '            fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))\n'
       '        val loaded = RagIndexStore.load(ctx, name, fp)')
assert s.count(old) == 1, "桩⑭c：没找到 search 里的指纹构造"
new = old.replace('RagIndexStore.rowsOf(textsRaw))',
                  'RagIndexStore.rowsOf(textsRaw))').replace(
    'fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))',
    'fpIn, RagIndexStore.textsHashOf(textsRaw))')
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩⑭c（search 省略 rows = 期望指纹 r 退化）" "rowsOf"
reset_src

# ── 桩⑭d：改字重算那条路的可用性判据省略 rows（同样恒判"不可用"）─────────
# `indexUsableForRewrite` 是"能不能按行改字"的唯一闸，它的指纹也必须现算 `r`
# —— 省了就是"每一次改字都判索引不可用"（退回只写正文 + 待重建，而用户看到的
# 是"免重建没生效"，没有任何线索指向那一处）。
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('        val fp = RagIndexStore.fingerprintOf(\n'
       '            fpIn, RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))\n'
       '        // ⚠ 只认 [RagIndexStore.Load.valid]')
assert s.count(old) == 1, "桩⑭d：没找到可用性判据里的指纹构造"
new = old.replace('RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))',
                  'RagIndexStore.textsHashOf(textsRaw))')
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF
must_red "桩⑭d（可用性判据省略 rows）" "indexUsableForRewrite 调 fingerprintOf 时没有把 rowsOf"
reset_src

# ── 桩⑮：save 不再自己现算正文 hash（写下的与判定的不同源）─────────────
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        val full = fingerprintOf(fp, textsHashOf(textsBytes), rowsCount)'
assert s.count(old) == 1, "桩⑮：没找到 save 里的指纹构造"
s = s.replace(old, '        val full = fingerprintOf(fp, fp.embedPath)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑮（save 不再现算正文 hash）" "save 体内没有现算正文 hash"
reset_src

# ── 桩⑯：另开一个"写正文文件"的入口（绕过唯一的写路径）─────────────────
# 多一处写正文就多一条忘记同步 hash 的路：写进去的正文与指纹里的那一半不再同源，
# 而两者的表现完全一样（都是"正文文件更新了"）。
"$PY" - "$STORE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    /** 索引文件在不在（不解析内容）。'
assert s.count(old) == 1, "桩⑯：没找到插入点"
stray = '    /** 打桩用：另开一个写正文的路径（守卫必须因此变红）。 */\n'
stray += '    fun strayTextWriter(d: File, bytes: ByteArray): Boolean {\n'
stray += '        val tmp = File(d, "$TEXTS.tmp")\n'
stray += '        return tmp.writeBytes(bytes) >= 0\n'
stray += '    }\n\n'
s = s.replace(old, stray + old, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑯（另开一个写正文的入口）" "写正文临时文件的函数是"
reset_src

# ── 桩⑰：不再用当前正文的 hash（改过正文也不作废）──────────────────────
# ⚠ `0.9.236` 起这个"正文 hash + 行数"的三参形态有**两个**调用点
#   （`search` 与 `indexUsableForRewrite`），所以这里**替换全部** ——
#   只换一处的话另一处仍是对的，守卫会保持绿（桩白打），而两个点都该被钉。
"$PY" - "$ENGINE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "RagIndexStore.textsHashOf(textsRaw), RagIndexStore.rowsOf(textsRaw))"
assert s.count(old) >= 1, "桩⑰：没找到正文 hash 那一处"
s = s.replace(old, '"stub-hash", RagIndexStore.rowsOf(textsRaw))')
open(p, 'w', encoding='utf-8').write(s)
PYEOF
must_red "桩⑰（search 不用当前正文的 hash）" "没有 用当前正文的 hash 构造指纹"
reset_src

# ── 收尾：还原后必须恢复全绿 ────────────────────────────────────────────
# ── 桩⑯：建库侧自己算了一份指纹（绕过唯一的取值处）───────────────
# 这正是用户拍板要修的那个现象：**建库成功，检索说"模型或参数变了"** ——
# 两次算出来的不是同一个字符串，而两侧单看都对。
"$PY" - "$ENGINE" <<'PYEOF2'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val fp = embedFpInput(cp, batch)'
assert s.count(old) == 1, "stub18: anchor miss"
s = s.replace(old, 'val fp = RagIndexStore.FpInput(embedPath!!, 0L, 0L, 1, cp)', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF2
must_red "桩⑱（建库侧自己算指纹）" "buildIndex 走唯一的指纹取值处"
reset_src

# ── 桩⑰：池化方式没进指纹实参（取到了不传）──────────────────
# 这是"升级了取向量的方式而旧索引完全不知情"的成因：同一个模型、同一个维度，
# 按 CLS 取与按 last 取得到的向量完全不同，而指纹上看不出差别。
"$PY" - "$ENGINE" <<'PYEOF3'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'pooling = embedPooling,'
assert s.count(old) == 1, "stub19: anchor miss"
s = s.replace(old, 'pooling = null,', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF3
must_red "桩⑲（池化方式没进指纹）" "池化方式"
reset_src

# ── 桩⑱：不可用时只给一句笼统的话（退回“模型或参数变了”）───
"$PY" - "$STORE" <<'PYEOF4'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'lastDiff = compareFp(fpIn, expectFp)'
assert s.count(old) == 1, "stub20: anchor miss"
s = s.replace(old, 'lastDiff = FpDiff(false, false, "v", "\u6a21\u578b\u6216\u53c2\u6570\u53d8\u4e86")', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF4
must_red "桩⑳（不可用时只给一句笼统的话）" "没有把逐字段比对的结果写进 lastDiff"
reset_src

# ── 桩⑲：compareFp 又把"已过时"说成"待重建"─────────────────
"$PY" - "$STORE" <<'PYEOF5'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'return FpDiff(false, false, "v",'
assert s.count(old) == 1, "stub21: anchor miss"
s = s.replace(old, 'return FpDiff(false, true, "v",', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF5
must_red "桩㉑（把已过时说成待重建）" "逐字段比对的返回值里不许出现 stale=true"
reset_src

# ── 桩⑳：Kotlin 漏算池化字段（两边字段集合不再相等）───────────
"$PY" - "$STORE" <<'PYEOF6'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'sb.append("q").append(pq).append'
assert s.count(old) == 1, "stub22: anchor miss"
s = s.replace(old, 'sb.append("Q99").append(pq).append', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF6
must_red "桩㉒（Kotlin 漏算池化字段）" "指纹字段集合两边一致"
reset_src

# ── 桩⑴：C++ 漏算池化字段（两边字段集合不再相等）─────────────
"$PY" - "$HDR" <<'PYEOF7'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 's += "q";      s += fp.pooling;'
assert s.count(old) == 1, "stub23: anchor miss"
s = s.replace(old, 's += "Q99";      s += fp.pooling;', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF7
must_red "桩㉓（C++ 漏算池化字段）" "指纹字段集合两边一致"
reset_src

# ── 桩㉔：查询编码失败与"没命中"合流（第 2 步要防的正是这个）─────────────
# 把单独那一支改回旧写法：`embed(query) ?: return emptyList()` 的等价形态 ——
# 返回 `Search.Hits(空)`，于是"本轮查询没算出来"与"库里确实没有"共用一句话。
"$PY" - "$ENGINE" <<'PYEOF8'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val q = embed(query) ?: return Search.QueryFailed(lastEmbedFailure()?.describe() ?: "查询向量编码失败")'
assert s.count(old) == 1, "桩㉔：没找到 QueryFailed 那一支"
new = 'val q = embed(query) ?: return Search.Hits(RagIndexStore.Ranked(emptyList(), Float.NaN, 0))'
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYEOF8
must_red "桩㉔（查询编码失败与没命中合流）" "查询编码失败**必须**从 QueryFailed 那一支出口"
reset_src

# ── 桩㉕：没命中时不报最高分（"差一点"与"完全不像"又分不开了）───────────
"$PY" - "$ENGINE" <<'PYEOF9'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '（最高分 ${fmtScore(r.rank.best)} / 阈值 $minScore）'
assert s.count(old) == 1, "桩㉕：没找到最高分的日志文案"
open(p, 'w', encoding='utf-8').write(s.replace(old, '（阈值 $minScore）', 1))
PYEOF9
must_red "桩㉕（没命中时不报最高分）" "「没命中」时把**最高分**打出来"
reset_src

# ── 桩㉖：存储层不再给出过滤前的最高分（Ranked.best 没了判据）───────────
"$PY" - "$STORE" <<'PYEOF10'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'var best = Float.NaN'
assert s.count(old) == 1, "桩㉖：没找到 best 的初值"
open(p, 'w', encoding='utf-8').write(s.replace(old, 'var best = 0f', 1))
PYEOF10
must_red "桩㉖（Ranked.best 用 0f 代替 NaN）" "存储层给出「过滤前的最高分」"
reset_src

# ── 桩㉗：Kotlin 分隔符表漏一条（两边边界分家，静默）─────────────────────
# ⚠ 表在 TextBoundary（共享，见那里的注释），不在 LlmEngine —— 桩跟着搬。
#   桩打在**表所在的那份源码**上，否则"改一个没人读的副本"会变成恒绿。
"$PY" - "$TB" <<'PYEOF11'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        "、" to false,     // 顿号\n'
assert s.count(old) == 1, "stub27: anchor miss"
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF11
must_red "桩㉗（Kotlin 分隔符表漏「、」，两边分家）" "分块规则两边逐条一致"
reset_src

# ── 桩㉘：把空格加进分隔符表（硬伤②的触发源）────────────────────────────
"$PY" - "$HDR" <<'PYEOF12'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '    { "\\n",   false },   // 换行\n'
assert s.count(old) == 1, "stub28: anchor miss"
s = s.replace(old, old + '    { " ",    false },   // 单个空格（不该在表内）\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF12
must_red "桩㉘（C++ 表里加进单个空格）" "分块规则两边逐条一致"
reset_src

# ── 桩㉙：Kotlin 侧不再把规则版本写进指纹那一格 ─────────────────────────
"$PY" - "$STORE" <<'PYEOF13'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        sb.append("k").append(fp.cp.chunkRuleVersion).append(\'\\u001f\')\n'
assert s.count(old) == 1, "stub29: anchor miss"
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF13
must_red "桩㉙（规则版本没进指纹）" "分块规则两边逐条一致"
reset_src

# ── 桩㉚：去掉「对齐后 <= pos 即视为没找到」那道闸（丢字路径会回来）──────
"$PY" - "$HDR" <<'PYEOF14'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            if (cut <= pos) continue;                         // 规则 2\n'
assert s.count(old) == 1, "stub30: anchor miss"
s = s.replace(old, '            // 闸被拿掉了（老路径复活）\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF14
must_red "桩㉚（C++ 拿走「对齐后 <= pos」那道闸）" "C++ 切点选择有「对齐后 <= pos 即视为没找到」这道闸"
reset_src

# ── 桩㉚b：**共享**切点函数里拿走那道闸（两边同时失去铁律）────────────────
# 与桩㉚的区别：桩㉚ 打在 C++ 那份实现上（只有 C++ 变红），这一支打在 Kotlin
# 与导入切段**共用**的那一份上 —— 它一处红了、三处都受影响。
# 这一支存在的理由：判据若只查"转发那一行"，转发到一个没有闸的实现上会恒绿。
"$PY" - "$TB" <<'PYEOF14b'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                if (cut <= pos) continue                  // 对齐后仍不算\n'
assert s.count(old) == 1, "stub30b: anchor miss"
s = s.replace(old, '                // 闸被拿掉了（老路径复活）\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF14b
must_red "桩㉚b（共享切点函数拿走那道闸）" "Kotlin 切点选择有同一道闸"
reset_src

# ── 桩㉚c：建库那一路不再走共享原语（又长出一套本地实现）──────────────────
"$PY" - "$ENGINE" <<'PYEOF14c'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        return TextBoundary.pickCut(text, pos, end, kSepMaxWidth)\n'
assert s.count(old) == 1, "stub30c: anchor miss"
s = s.replace(old, '        return -1   // 本地又实现了一套（表/控制流会分家）\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF14c
must_red "桩㉚c（建库那一路不再走共享原语）" "Kotlin 切点选择有同一道闸"
reset_src

# ── 桩㉛：Kotlin 硬截不再按码点对齐（切出非法 UTF-8）────────────────────
"$PY" - "$ENGINE" <<'PYEOF15'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '                    var hard = alignUtf8(text, end)\n'
assert s.count(old) == 1, "stub31: anchor miss"
s = s.replace(old, '                    var hard = end\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF15
must_red "桩㉛（Kotlin 硬截不对齐码点）" "Kotlin 硬截那一行走 alignUtf8(text, end)"
reset_src

# ── 桩㉜：buildIndex 只算硬截数、不打日志（用户要的"记进日志"消失）──────
"$PY" - "$ENGINE" <<'PYEOF16'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (hardCuts > 0) {\n'
assert s.count(old) == 1, "stub32: anchor miss"
s = s.replace(old, '        if (false) {\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF16
must_red "桩㉜（硬截只计数、不打日志）" "buildIndex 有「有硬截才打日志」这道条件"
reset_src

# ── 桩㉝：Kotlin 对齐退回按字节的 0xC0 判据（对 UTF-16 是误判）────────────
"$PY" - "$ENGINE" <<'PYEOF17'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        while (j > 0 && s[j].isLowSurrogate()) j--\n'
assert s.count(old) == 1, "stub33: anchor miss"
s = s.replace(old, '        while (j > 0 && (s[j].code and 0xC0) == 0x80) j--\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF17
must_red "桩㉝（Kotlin 对齐退回按字节的 0xC0 判据）" "Kotlin alignUtf8 用 isLowSurrogate 判「半个字符」"
reset_src

# ── 桩㉞：Kotlin 对齐下标上界用 length（s[j] 越界抛异常）────────────────
"$PY" - "$ENGINE" <<'PYEOF18'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 把"到末尾/越界就原样返回 length"这条分流拿掉，退回一个会读 s[length] 的写法。
old = '        if (i >= s.length) return s.length\n'
assert s.count(old) == 1, "stub34: anchor miss"
s = s.replace(old, '', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF18
must_red "桩㉞（Kotlin 对齐上界用 length）" "Kotlin alignUtf8 对 i >= length 显式返回 length"
reset_src

# ── 桩㉟：CI 里去掉分块规则差分这一步 ───────────────────────────────────
# ⚠ 打桩对象是 **CI 的调用清单**（`.cnb.yml` 与它引用的 `scripts/ci-apk-stage.sh`），
#   不是 `.cnb.yml` 这一个文件：stage 正文在 2026-10-10 搬进了后者
#   （内联 170 KB 会让 CNB runner 的 fork/exec 报 `argument list too long`）。
#   桩跟着走，否则它会去一个已经没有这行字的文件里找锚点、**当场断言失败** ——
#   "打桩对象搬了、桩没搬"与"判据锚了错的文件"是同一族的失效。
"$PY" - "$(pwd)/scripts/ci-apk-stage.sh" <<'PYEOF19'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'sh tools/rag_index/chunk_rule/run_diff.sh\n'
assert s.count(old) == 1, "stub35: anchor miss"
s = s.replace(old, 'true\n', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF19
must_red "桩㉟（CI 去掉分块规则差分）" "CI 里有分块规则差分这一步"
# ⚠ 还原必须**排在这一根桩之后**：桩㉟ 改的是 stage 脚本，而上面那次 `reset_src`
#   发生在它之前。顺序反了的话它永远不被还原 —— 本次就真踩到了：stage 里那行
#   被换成 `true` 留在工作区，紧接着 `count_suite_totals.py` 量到的条数与原文件
#   对不上，报成"条数漂移"，而真正的原因是一个没还原的桩。
gold_restore_cnb
reset_src

# ── 桩㊱：rank 的「全挡」阈值退回 0.9999f（那一格从没进过全挡分支）────────
#
# 原始形状：`0.9999f` 的精确值是 0.99989998…（**严格小于** 1.0），
# `1.0f >= 0.9999f` 为真，row0 照样命中。于是那一格：
#   · "全挡 -> 空" 实测 hits=1（红）        ← 断言方向对，但守卫没执行到
#   · "全挡时 above = 0" 实测 above=1（红）
#   · "best 仍是 1.0" 恰好为真（恒真）     ← 把上面两红盖成了"看起来在测"
# 桩打回这个阈值，守卫必须变红并点名。
"$PY" - "$RIST" <<'PYEOF20'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "floatArrayOf(1f, 0f), 3, 1.00001f)"
assert s.count(old) == 1, "stub36: anchor miss"
s = s.replace(old, "floatArrayOf(1f, 0f), 3, 0.9999f)", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF20
must_red "桩㊱（rank「全挡」阈值退回 0.9999f）" "全挡」阈值**严格大于**矩阵最高分"
reset_src

# ── 桩㊲：rank 的「全挡」那一格期望 hits 写成 1（自欺成"有命中"）─────────
"$PY" - "$RIST" <<'PYEOF21'
import sys, re
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'eqi(r.hits.size.toLong(), 0L, "rank：阈值 1.00001 全挡 -> 空")'
assert s.count(old) == 1, "stub37: anchor miss"
s = s.replace(old, 'eqi(r.hits.size.toLong(), 1L, "rank：阈值 1.00001 全挡 -> 空")', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF21
must_red "桩㊲（「全挡」格期望 hits 写成 1）" "全挡」那一格期望 hits"
reset_src

# ── 桩㊳：rank 的 NaN 闸被挪到阈值比较**之后**（= 旧实现的形态）────────────
# 这是本轮修的本质缺陷：`minScore > 0f` 为假时那段被短路，NaN 进 passed，
# 且 `compareByDescending` 把 NaN 排最前、占掉一个 top-k 名额。
"$PY" - "$STORE" <<'PYEOF22'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (!score.isFinite()) continue
            if (minScore > 0f && !(score >= minScore)) continue
            passed.add(SearchHit(r, score))"""
assert s.count(old) == 1, "stub38: anchor miss"
new = """            if (minScore > 0f && !(score >= minScore)) continue
            if (!score.isFinite()) continue
            passed.add(SearchHit(r, score))"""
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF22
must_red "桩㊳（NaN 闸挪到阈值比较之后）" "NaN 闸排在阈值比较"
reset_src

# ── 桩㊴：rank 干脆不要 NaN 闸（只留阈值比较）────────────────────────────
"$PY" - "$STORE" <<'PYEOF23'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """            if (!score.isFinite()) continue
"""
assert s.count(old) == 1, "stub39: anchor miss"
s = s.replace(old, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF23
must_red "桩㊴（rank 去掉 NaN 闸）" "rank 丢弃非有限分数"
reset_src

# ── 桩㊵：RagArchive 退回裸 substring（会切在代理对中间）────────────────
"$PY" - "$ARCHIVE" <<'PYEOF24'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "TextBoundary.clipAtCharBoundary(t, MAX_TEXT_CHARS)"
assert s.count(old) == 1, "stub40: anchor miss"
s = s.replace(old, "t.substring(0, MAX_TEXT_CHARS)", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF24
must_red "桩㊵（RagArchive 退回裸 substring 截断）" "RagArchive 的 MAX_TEXT_CHARS 截断确实调用 clipAtCharBoundary"
reset_src

# ── 桩㊶：LlmEngine.addEntry 退回裸 substring ───────────────────────────
"$PY" - "$ENGINE" <<'PYEOF25'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "TextBoundary.clipAtCharBoundary(trimmed, cp.targetChars)"
assert s.count(old) == 1, "stub41: anchor miss"
s = s.replace(old, "trimmed.substring(0, cp.targetChars)", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF25
must_red "桩㊶（addEntry 退回裸 substring 截断）" "LlmEngine.addEntry 的超长截断走 clipAtCharBoundary"
reset_src

# ── 桩㊷：clipAtCharBoundary 退回按字节的 0xC0 判据（对正常汉字误判）────
"$PY" - "$TB" <<'PYEOF26'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "        while (n > 0 && s[n].isLowSurrogate()) n--"
assert s.count(old) == 1, "stub42: anchor miss"
s = s.replace(old, "        while (n > 0 && (s[n].code and 0xC0) == 0x80) n--", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF26
must_red "桩㊷（clipAtCharBoundary 退回按字节 0xC0 判据）" "clipAtCharBoundary 不按字节的 0xC0 判据"
reset_src

# ── 桩㊸：指纹字段前缀被**复制**成两份（集合判据看不出来）──────────────
# ⚠ 这才是本轮顺带发现的那个判据缺口：`check_fp_fields.py` 原先只比**集合**，
#    而"某个前缀写了两遍"在集合里完全看不出来 —— 真发生时 `fpFields()` 会
#    静默丢掉后一个（那一类变化不再作废索引）。
"$PY" - "$STORE" <<'PYEOF27'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = r"""        sb.append("k").append(fp.cp.chunkRuleVersion).append('\u001f')
"""
assert s.count(old) == 1, "stub43: anchor miss"
s = s.replace(old, old + old, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF27
must_red "桩㊸（指纹字段前缀 k 被写了两遍）" "字段前缀 'k' 出现了两次"
reset_src

# ── 桩㊹：entriesCountOf 退回"数非空行"（与索引侧口径分家）────────────
# 自产文件上两者恰好相等，所以这个缺陷只在"文件被外部改过 / 由别处导入"时现形 ——
# 而那时导入确认框报的"已有 N 条"是假数。桩必须打到**真分家**的形态。
"$PY" - "$STORE" <<'PYEOF28'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "readTexts(ctx, name)?.let { rowsToCount(rowsOf(it)) } ?: 0"
assert s.count(old) == 1, "stub44: anchor miss"
new = ('readTexts(ctx, name)?.toString(Charsets.UTF_8)'
       '?.split(chr(10))?.count { it.isNotEmpty() } ?: 0')
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF28
must_red "桩㊹（entriesCountOf 退回数非空行）" "与索引侧同口径"
reset_src

# ── 桩㊺：C++ 那边截断出参消失（撞上限后静默收工）──────────────────────
# 这是本轮修的那件事本身：`chunk_text` 撞 maxChunks 就 return，一点都不说。
# 判据必须红并点名"truncated 出参"。
"$PY" - "$HDR" <<'PYEOF29'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    if (truncated) *truncated = !brokeByEnd;\n"
assert s.count(old) == 1, "stub45: anchor miss"
s = s.replace(old, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF29
must_red "桩㊺（C++ 截断出参没了：撞上限静默收工）" "C++ chunk_text 有 truncated 出参"
reset_src

# ── 桩㊻：Kotlin 那边把截断判据写成"块数正好等于上限" ──────────────────
# 这一档在"正好切满且到文末"时是**完整**的，报成截断会让用户白建一遍 ——
# 而它会**同时**让"截断日志"在正常库上天天出现（用户从此无视它）。
"$PY" - "$ENGINE" <<'PYEOF30'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "truncated[0] = !brokeByEnd"
assert s.count(old) == 1, "stub46: anchor miss"
s = s.replace(old, "truncated[0] = out.size >= cp.maxChunks", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF30
must_red "桩㊻（Kotlin 截断判据写成『块数等于上限』）" "Kotlin chunk 有同一判断"
reset_src

# ── 桩㊻b：判据写成 `pos < len`（每份正常文档都被报成"截断"）──────────────
# ⚠ 这不是假想：本轮**第一版就是这么写的**，C++ 单测的四条正例当场抓到。
#   最后一块走到文末时 `pos` 指向那一块的**开头**，它当然 `< len` ——
#   于是那条"有 N 条被截断"的日志会在每个库上天天出现，用户从此无视它
#   （而它本该只在**真丢内容**时出现）。
"$PY" - "$HDR" <<'PYEOF35'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (truncated) *truncated = !brokeByEnd;"
assert s.count(old) == 1, "stub46b: anchor miss"
s = s.replace(old, "if (truncated) *truncated = (pos < len);", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF35
must_red "桩㊻b（截断判据写成 pos < len：正常文档全被报成截断）" "C++ chunk_text 有 truncated 出参"
reset_src

# ── 桩㊼：截断算出来了、但**没写进日志**（纯逻辑全绿、界面上什么都没有）──
# 这正是这一族缺陷的形状：单测只看 RagCost / chunk 的返回值，全绿；
# 而用户看到的仍然是"有些内容搜不到"。判据必须锚**函数体里那一支**。
"$PY" - "$ENGINE" <<'PYEOF31'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "if (truncatedDocs > 0) {"
assert s.count(old) == 1, "stub47: anchor miss"
s = s.replace(old, "if (false) {", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF31
must_red "桩㊼（截断算出来了但不报）" "buildIndex 函数体里有『被截断才报』这一支"
reset_src

# ── 桩㊽：maxChunks 没进指纹（4096 -> 16384 时旧索引被当新的用）────────
# 症状最坏：按旧上限**截断**出来的半份索引被当成"仍然可用"，
# 后半段的正文一个向量都没有，而它与"文档里没有那一段"逐字相同。
"$PY" - "$STORE" <<'PYEOF32'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = r"""        sb.append("c").append(fp.cp.maxChunks).append('\u001f')
"""
assert s.count(old) == 1, "stub48: anchor miss"
s = s.replace(old, "", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF32
must_red "桩㊽（maxChunks 没进指纹）" "Kotlin 指纹里把 maxChunks 写进"
reset_src

# ── 桩㊾：从指纹回填时 maxChunks 吃默认值（追加一次就把索引弄失效）──────
# 症状：用户点一次「追加」，指纹按**默认**上限重算 -> 与索引里那份不相等
# -> 判"参数变了"，而日志只会说这四个字，用户无从知道是哪一项。
"$PY" - "$STORE" <<'PYEOF33'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'maxChunks = f("c")?.toIntOrNull() ?: ChunkParams().maxChunks,'
assert s.count(old) == 1, "stub49: anchor miss"
s = s.replace(old, "maxChunks = ChunkParams().maxChunks,", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF33
must_red "桩㊾（回填时 maxChunks 吃默认值）" "从指纹回填时 maxChunks 一起搬"
reset_src

# ── 桩㊿：截断日志还在，但文案说成"慢一点"（把一件事讲成了另一件）──────
# "被截断"与"慢"后果差一个量级：前者是**内容没了**，后者只是等。
# 文案里必须出现"未进索引"这四个字 —— 用户据此才会去拆文档，而不是继续等。
"$PY" - "$ENGINE" <<'PYEOF34'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "超出的部分**未进索引**——"
assert s.count(old) == 1, "stub50: anchor miss"
s = s.replace(old, "超出的部分会慢一些——", 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF34
must_red "桩㊿（截断日志说成『慢一点』）" "截断日志明说『超出的部分未进索引』"
reset_src

# ── 桩51：硬截轮又回退 overlap（那段正文被切两遍、硬截计数灌水）──────────
# 这是本轮修的那处。判据是"`next` 按 cutByRule 分家"——把 C++ 侧退回"无条件重叠"
# 之后，守卫必须红，而且要点出是这一条（不是别的判据顺带红了）。
"$PY" - "$HDR" <<'PYEOF35'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = """        size_t next;
        if (cutByRule) {
            next = end > (size_t) overlap ? end - (size_t) overlap : 0;
            next = align_utf8_back(text, next);
        } else {
            next = end;
        }"""
new = """        size_t next = end > (size_t) overlap ? end - (size_t) overlap : 0;
        next = align_utf8_back(text, next);"""
assert s.count(old) == 1, "stub51: anchor miss"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF35
must_red "桩51（C++ 硬截轮又重叠）" "C++ 「下一块起点」按 cutByRule 分家"
reset_src

# ── 桩52：Kotlin 侧同一处被改回去（两边控制流分家）────────────────────
"$PY" - "$ENGINE" <<'PYEOF36'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "var next = if (cutByRule) alignUtf8(text, (end - overlap).coerceAtLeast(0)) else end"
new = "var next = alignUtf8(text, (end - overlap).coerceAtLeast(0))"
assert s.count(old) == 1, "stub52: anchor miss"
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF36
must_red "桩52（Kotlin 硬截轮又重叠）" "Kotlin 「下一块起点」按 cutByRule 分家"
reset_src

# ── 桩53：importEntries 退回"裸拼"（末尾无换行的外部文件下两条粘成一行）─────
# 这是本轮修的 C1：`before` 从磁盘原样读回，自产文件有尾换行没问题，但外部
# 编辑过的文件末尾可能无换行 —— 那时旧末条与本次首条粘成同一行，两条都
# 读不出来，而回执数字（数段数）照样对得上。桩把它改回裸拼形态。
"$PY" - "$STORE" <<'PYEOF37'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = 'val before = if (before0.isEmpty() || before0.endsWith("\\n")) before0 else before0 + "\\n"'
assert s.count(old) == 1, "stub53: anchor miss"
s = s.replace(old, 'val before = before0', 1)
open(p, 'w', encoding='utf-8').write(s)
PYEOF37
must_red "桩53（importEntries 退回裸拼，末尾无换行就粘行）" "importEntries 拼接前把 before 对齐到"
reset_src

must_green "收尾：全部桩已还原"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
