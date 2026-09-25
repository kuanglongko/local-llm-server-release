#!/bin/sh
# 自测 `tools/run_ui_io_guard.sh`：对着**打桩源码树**跑，
# 断言"该绿的绿、该红的红，且失败时点得出是哪一条"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是三个自欺模式（本仓库各栽过一次，所以三个都钉）
# ═══════════════════════════════════════════════════════════════════════════
#   1. **恒真**：pattern 写成到处都有的文本（"有 synchronized"），
#      把功能删掉照样 PASS。I-4 的本体不是"有锁"，是"**这四处字段的每一次
#      读写**都在锁区段内" —— 桩③/④ 就是钉这个差别。
#   2. **恒假被静音**：pattern 与源码差一个字符 -> 守卫永远红 -> 有人把
#      `exit 1` 改成 `|| true`，那时它彻底没用了。自测逐条验"变红且点名"。
#   3. **判据锚错位置**：钉的是"文件里出现过 ModelStore.rows()"，而不是
#      "取数在 ioExecutor 上、渲染是另一个函数"。桩② 就是钉这个。
# 做法：就地改真源码（每个桩只改一处锚点）-> 断言守卫**变红并点名** -> 还原。
# 桩全部来自**本轮真正的旧写法**，不是编出来的反例。
#
# 纯 sh + python3 标准库，不依赖工具链。运行：sh tools/run_ui_io_guard_tests.sh
set -e
cd "$(dirname "$0")/.."

PY=${PYTHON:-python3}
GUARD=tools/run_ui_io_guard.sh
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
MS=app/src/main/java/com/xiaowan/localinference/ModelStore.kt
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt
EXT=app/src/main/java/com/xiaowan/localinference/ExternalModel.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
for f in "$ACT" "$MS" "$LFS" "$EXT"; do
    cp "$f" "$TMP/$(basename "$f").bak"
done
restore() {
    for f in "$ACT" "$MS" "$LFS" "$EXT"; do cp "$TMP/$(basename "$f").bak" "$f"; done
    rm -rf "$TMP"
}
trap restore EXIT
reset_src() { for f in "$ACT" "$MS" "$LFS" "$EXT"; do cp "$TMP/$(basename "$f").bak" "$f"; done; }

# must_red "<桩名>" "<期望被点名的那条断言>"
must_red() {
    set +e
    sh "$GUARD" > "$TMP/guard.out" 2>&1
    rc=$?
    set -e
    if [ "$rc" = "0" ]; then
        echo "FAIL  $1：守卫没变红（该断言失效了）"; bad=$((bad+1)); return
    fi
    if grep -qF "$2" "$TMP/guard.out"; then
        echo "PASS  $1：守卫变红，且指到了「$2」"; ok=$((ok+1))
    else
        echo "FAIL  $1：守卫红了，但没指到「$2」"; bad=$((bad+1))
    fi
}

# ── 0) 基线：未打桩时必须全绿 ─────────────────────────────────────────────
set +e
sh "$GUARD" > "$TMP/base.out" 2>&1
base=$?
set -e
if [ "$base" = "0" ]; then echo "PASS  基线（未打桩）全绿"; ok=$((ok+1));
else echo "FAIL  基线（未打桩）就红了："; cat "$TMP/base.out"; bad=$((bad+1)); fi

# ── 桩①：I-2 本体 —— 把逐行问 prefs 的渲染循环放回主线程 ──────────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        for (r in rows) {
            val f = r.file
            val isSel = f.name == sel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tv = TextView(this).apply {
                text = (if (isSel) "● " else "○ ") + (if (r.external) "🔗 " else "") + r.alias +
                    (if (r.alias != f.name.removeSuffix(".gguf")) " · ${f.name}" else "") +
                    if (r.missing) "  ⚠ 原文件已丢失" else "  (${r.bytes / 1048576} MB)\""""
new = """        for (r in rows) {
            val f = r.file
            val isSel = f.name == sel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val ext = ModelStore.isExternal(this, f.name)
            val al = ModelStore.aliasOf(this, f.name)
            val tv = TextView(this).apply {
                text = (if (isSel) "● " else "○ ") + (if (ext) "🔗 " else "") + al +
                    (if (al != f.name.removeSuffix(".gguf")) " · ${f.name}" else "") +
                    if (r.missing) "  ⚠ 原文件已丢失" else "  (${f.length() / 1048576} MB)\""""
assert s.count(old) == 1, "stub1: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩①（渲染循环里逐行问 prefs）" "I-2: renderModelList 函数体里不含任何 prefs"
reset_src

# ── 桩②：I-2 —— 取数挪回主线程（渲染/取数不再分离）───────────────────────
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        ioExecutor.execute {
            val rows = runCatching { ModelStore.rows(this) }.getOrNull()
            ui.post { if (myGen == modelListGen) renderModelList(rows.orEmpty(), sel) }
        }
    }"""
new = """        val rows = runCatching { ModelStore.list(this) }.getOrNull()
        renderModelList(rows.orEmpty().map { ModelStore.Row(it, it.name, false, false, it.length()) }, sel)
    }"""
assert s.count(old) == 1, "stub2: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩②（取数挪回主线程）" "I-2: 取数发生在 ioExecutor 上（不是主线程）"
reset_src

# ── 桩③：I-3 本体 —— 退回 File(getExternalFilesDir(null), ...) ────────────
"$PY" - $MS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "models").apply { mkdirs() }"""
new = """        return File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }"""
assert s.count(old) == 1, "stub3: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩③（modelsDir 退回 getExternalFilesDir 直传）" "I-3: 全仓不得把 getExternalFilesDir"
reset_src

# ── 桩④：I-4 本体 —— 折叠读改写放回锁外 ──────────────────────────────────
"$PY" - $LFS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    fun append(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            // 折叠判据与写出必须在同一个临界区内：读-改-写三步分开做时，
            // 两个线程会各自认为自己是"第一个"，`fileRep` 的增长被打断 → 少报。
            val out = if (line.startsWith("##")) {"""
new = """    fun append(line: String) {
        val w = writer ?: return
        val out = if (line.startsWith("##")) {"""
assert s.count(old) == 1, "stub4a: anchor not found"
s = s.replace(old, new, 1)
old2 = """                line
            }
            runCatching {
                w.write(out)
                w.write("\\n")
                w.flush()
            }
        }
    }"""
new2 = """                line
            }
        synchronized(lock) {
            runCatching {
                w.write(out)
                w.write("\\n")
                w.flush()
            }
        }
    }"""
assert s.count(old2) == 1, "stub4b: anchor not found"
s = s.replace(old2, new2, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩④（折叠读改写回到锁外）" "I-4: append() 不得在锁外先算 out"
reset_src

# ── 桩⑤：I-4 —— noteNoise 的判与设分开 ───────────────────────────────────
"$PY" - $LFS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    fun noteNoise(line: String) {
        synchronized(lock) {
            if (noiseRun == 0) noiseSample = line
            noiseRun++
        }
    }"""
new = """    fun noteNoise(line: String) {
        if (noiseRun == 0) noiseSample = line
        synchronized(lock) { noiseRun++ }
    }"""
assert s.count(old) == 1, "stub5: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑤（noteNoise 判与设分离）" "I-4: noteNoise 不得在锁外先判"
reset_src

# ── 桩⑥：I-4 —— init 退回 @Synchronized（与 append 不是同一把锁）─────────
"$PY" - $LFS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """    fun init(context: android.content.Context) {
        synchronized(lock) {
            if (writer != null) return"""
new = """    @Synchronized
    fun init(context: android.content.Context) {
        run {
            if (writer != null) return"""
assert s.count(old) == 1, "stub6: anchor not found"
s = s.replace(old, new, 1)
old2 = """                writer = OutputStreamWriter(java.io.FileOutputStream(cur, true), Charsets.UTF_8)
            }
        }
    }"""
new2 = """                writer = OutputStreamWriter(java.io.FileOutputStream(cur, true), Charsets.UTF_8)
            }
        }
    }
// stub6"""
assert s.count(old2) == 1, "stub6b: anchor not found"
s = s.replace(old2, new2, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑥（init 退回 @Synchronized）" "I-4: init() 用 lock 而不是 this 做监视器"
reset_src

# ── 桩⑦：I-5 本体 —— onDestroy 不再 shutdown ──────────────────────────────
# 锚点必须落在 `onDestroy` **自己的函数体**里：它前面还有一行
# 「退出即释放模型」（`if (isFinishing) unloadOnExit()`），也会改动同一个函数。
# 所以桩按函数体匹配，不锚"文件里存在 shutdownNow + super 这两行" ——
# 后者会因为别处多一行而静默失配（桩没打上却报 PASS）。
"$PY" - $ACT <<'PYEOF'
import sys, re
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
m = re.search(r"(override fun onDestroy\(\) \{.*?\n    \})", s, re.S)
assert m, "stub7: onDestroy 函数体没找到"
body = m.group(1)
assert "ioExecutor.shutdownNow()" in body, "stub7: 函数体里没有 shutdownNow（锚点形状变了）"
s = s.replace(body, body.replace("        ioExecutor.shutdownNow()\n", "", 1), 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑦（onDestroy 不关 ioExecutor）" "I-5: onDestroy 里 shutdown ioExecutor"
reset_src

# ── 桩⑧：I-3 —— ExternalModel.add 退回自己拼 getExternalFilesDir ──────────
"$PY" - $EXT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        if (File(ModelStore.modelsDir(ctx), name).isFile) return false"""
new = """        if (File(ctx.getExternalFilesDir(null), "models/$name").isFile) return false"""
assert s.count(old) == 1, "stub8: anchor not found"
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑧（ExternalModel.add 自己拼 getExternalFilesDir）" "I-3: ExternalModel.add 的去重判据改用 modelsDir"
reset_src

# ── 桩⑨：I-2 —— ModelStore.rows 退回逐键 getString ────────────────────────
# 这条钉的是"一次 getAll"这个**手段**：逐键 getString 在语义上等价，
# 但代价重新变回"每个模型若干次 prefs 往返"，也就把 I-2 修了个寂寞。
"$PY" - $MS <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """        val all = prefs.all"""
new = """        val all = HashMap<String, Any>()
        for (rr in files0) all[KEY_ALIAS_PREFIX + rr] = prefs.getString(KEY_ALIAS_PREFIX + rr, "")"""
assert s.count(old) == 1, "stub9: anchor not found"
s = s.replace(old, new, 1)
old2 = """        val files = list(ctx)"""
new2 = """        val files = list(ctx); val files0 = files.map { it.name }"""
assert s.count(old2) == 1, "stub9b: anchor not found"
s = s.replace(old2, new2, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑨（rows 退回逐键 getString）" "I-2: ModelStore.rows() 一次 getAll"
reset_src

# ── 桩⑩：I-6 本体 —— 把每轮开跑的清零整段删掉（#154 的原样）────────────
# 这是本轮唯一"桩就是真事发现场"的一条：Issue #154 报的现象正是这个字段
# 从进程启动起**只被置位、从未被复位**。删掉这一行之后，断言必须变红，
# 且要点名到 I-6（不是随便红一条）。
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                    stopRequested = false
                    // 思考开关与 HTTP 入口同一套实现（ThinkingControl），此处不再自己拼字符串。"""
assert s.count(old) == 1, "stub10: anchor not found"
new = """                    // stub10：把每轮开跑的清零删掉（#154 的原样）
                    // 思考开关与 HTTP 入口同一套实现（ThinkingControl），此处不再自己拼字符串。"""
s = s.replace(old, new, 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑩（每轮开跑不清零 stopRequested，Issue #154 本体）" "I-6: 每轮开跑时把上一轮的停止意图清零"
reset_src

# ── 桩⑪：I-6 —— 清零挪到 genLock **之外**（与按钮回调交错）────────────────
# 这一条钉的是"位置"而不是"有没有"：只断言存在性的话，把它挪到锁外照样全绿，
# 而锁外清零会让"刚点完停止"的那一瞬间被抹掉。
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """            try {
                synchronized(LlmEngine.genLock) {
                    // 上一轮「停止」的意图在本轮开跑前作废（Issue #154）。"""
assert s.count(old) == 1, "stub11: anchor not found"
new = """            try {
                stopRequested = false
                synchronized(LlmEngine.genLock) {
                    // 上一轮「停止」的意图在本轮开跑前作废（Issue #154）。"""
s = s.replace(old, new, 1)
s = s.replace("""                    stopRequested = false
                    // 思考开关与 HTTP 入口同一套实现""",
              """                    // stub11：清零挪到锁外了
                    // 思考开关与 HTTP 入口同一套实现""", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑪（清零挪到 genLock 之外）" "I-6: 清零落在 synchronized(genLock) 之内"
reset_src

# ── 桩⑫：I-6 —— 清零挪到 startCompletion **之后** ────────────────────────
# 这一条钉的是"先有标志再动生成"这个**顺序**：反过来写，那段"标志还是旧的 true、
# 而生成已经开跑"的窗口恰好等于一整段 prefill。
"$PY" - $ACT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = """                    stopRequested = false
                    // 思考开关与 HTTP 入口同一套实现（ThinkingControl），此处不再自己拼字符串。"""
assert s.count(old) == 1, "stub12: anchor not found"
new = """                    // stub12：清零挪到 startCompletion 之后
                    // 思考开关与 HTTP 入口同一套实现（ThinkingControl），此处不再自己拼字符串。"""
s = s.replace(old, new, 1)
old2 = """                    cancel = LlmEngine.beginCancelable()"""
assert s.count(old2) == 1, "stub12b: anchor not found"
s = s.replace(old2, """                    stopRequested = false
                    cancel = LlmEngine.beginCancelable()""", 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
must_red "桩⑫（清零挪到 startCompletion 之后）" "I-6: 清零落在 startCompletion **之前**"
reset_src

# ── 收尾：还原后再跑一次基线，确认自测没污染源码 ─────────────────────────
set +e
sh "$GUARD" > "$TMP/final.out" 2>&1; fin=$?
set -e
DIRTY=0
for f in "$ACT" "$MS" "$LFS" "$EXT"; do
    cmp -s "$TMP/$(basename "$f").bak" "$f" || DIRTY=1
done
if [ "$fin" != "0" ]; then
    echo "FAIL  收尾：源码还原后守卫仍红"; cat "$TMP/final.out"; bad=$((bad+1))
elif [ "$DIRTY" != "0" ]; then
    echo "FAIL  自测污染了源码（未还原干净）"; bad=$((bad+1))
else
    echo "PASS  收尾（源码已还原，守卫仍全绿）"; ok=$((ok+1))
fi

echo ""
if [ "$bad" = "0" ]; then echo "=== UI/IO 守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== UI/IO 守卫自测：PASS $ok / FAIL $bad ==="; exit 1
