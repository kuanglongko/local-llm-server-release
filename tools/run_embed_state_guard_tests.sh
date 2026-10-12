#!/bin/sh
# 自测 `tools/run_embed_state_guard.sh`：对着**已知该红**的桩源码跑，
# 断言"该红的红、点得出是哪一条"，还原后必须恢复全绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一份防的是"守卫自己失效"，而嵌入侧的失败模式与对话侧同一个（见
# `run_model_state_guard_tests.sh` 开头的推导），只是桩要换成嵌入那一组：
# ═══════════════════════════════════════════════════════════════════════════
# 本守卫里有一批**负向**断言（"旧写法应绝迹"）。负向断言天生恒绿：pattern
# 一旦写错（写成源码里根本不存在的措辞），"不出现"就永远成立，这条判据从此
# 零信息量，而它在 CI 里照样打印 PASS。所以桩①②必须**真的把旧写法写回去**。
#
# 桩来源都是**本仓库嵌入侧真正的旧写法**，不是编出来的反例：
#   · 状态行只报选中、还把 n_embd 挂上去（`已选 X · n_embd=…`）——修复前就是那行；
#   · 行标只由选中态决定（`if (isSel) "● " else "○ "`）——修复前就是那行；
#   · 驻留判据改问 embedFile（"我以为选了就算装上了"）——最诱人的错法；
#   · 驻留判据改问 hasEmbedModel（答得出"有没有"、答不出"哪一份"）；
#   · 轮询不比较嵌入驻留值 / 卸载后不重画 —— 两条都会让标记停在错的格子上。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 运行：sh tools/run_embed_state_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/run_embed_state_guard.sh
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$ACT" "$TMP/act.bak"
restore() { cp "$TMP/act.bak" "$ACT"; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() { set +e; sh "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }
restore_src() { cp "$TMP/act.bak" "$ACT"; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -qF "$key" "$TMP/guard.out"; then
        line=$(grep -F "$key" "$TMP/guard.out" | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *) ck "$name：指到了「$key」（该行不是 FAIL：$line）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() {
    if run_guard; then ck "$1：守卫保持全绿" 1
    else ck "$1：守卫保持全绿（实际红了）" 0; sed -n '1,40p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
if run_guard; then ck "基线：未改动源码树 -> 守卫全绿" 1
else ck "基线：未改动源码树 -> 守卫全绿（实际红了，守卫本身有问题）" 0
     sed -n '1,40p' "$TMP/guard.out"; fi

# ── 1. 桩①：状态行退回"只报选中 + 把 n_embd 挂上去"（修复前的那一行）──────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '            else -> "$loadedText · $selText"\n'
new = ('            else -> "$loadedText · $selText"\n'
       '            // 桩：旧写法 —— 只报选中，并把 n_embd 挂在这一句上\n'
       '            else -> "已选 ${sel!!.name} · n_embd=${LlmEngine.embedDim}"\n')
assert s.count(old) == 1, "桩①：没找到状态行的 else 分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（状态行只报选中 + n_embd 挂在选中上）" "n_embd 挂在选中态上"
restore_src

# ── 2. 桩②：行标只由选中态决定（修复前的那一行）──────────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('            val mark = when {\n'
       '                isSel && isLoaded -> "▶● "\n'
       '                isLoaded -> "▶  "\n'
       '                isSel -> "●  "\n'
       '                else -> "○  "\n'
       '            }\n'
       '            val tailTxt = when {\n'
       '                r.missing -> "  ⚠ 原文件已丢失"\n'
       '                isSel && isLoaded -> "  (${r.bytes / 1048576} MB) · 已加载，检索正在用它"\n'
       '                isLoaded -> "  (${r.bytes / 1048576} MB) · 在内存里，但当前选中的是别的（未切换）"\n'
       '                isSel -> "  (${r.bytes / 1048576} MB) · 已选中，尚未加载"\n'
       '                else -> "  (${r.bytes / 1048576} MB)"\n'
       '            }\n')
new = ('            val mark = (if (isSel) "● " else "○ ")\n'
       '            val tailTxt = if (r.missing) "  ⚠ 原文件已丢失" else "  (${r.bytes / 1048576} MB)"\n')
assert s.count(old) == 1, "桩②：没找到嵌入列表的三态行标"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩②（行标只由选中态决定）" "行标不再只由选中态决定"
restore_src

# ── 3. 桩③：驻留判据改问 embedFile（"我以为选了就算装上了"）──────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun loadedEmbedFile(): File? = LlmEngine.embedPath?.let { File(it) }\n"
new = "    private fun loadedEmbedFile(): File? = embedFile\n"
assert s.count(old) == 1, "桩③：没找到 loadedEmbedFile 实现"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩③（驻留判据改问选中态）" "嵌入驻留判据不得改问本页选中态"
restore_src

# ── 4. 桩④：驻留判据改问 hasEmbedModel（答不出"哪一份"）──────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun loadedEmbedFile(): File? = LlmEngine.embedPath?.let { File(it) }\n"
new = "    private fun loadedEmbedFile(): File? = if (LlmEngine.hasEmbedModel) embedFile else null\n"
assert s.count(old) == 1, "桩④：没找到 loadedEmbedFile 实现"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩④（驻留判据改问 hasEmbedModel）" "嵌入驻留判据不得只问 hasEmbedModel"
restore_src

# ── 5. 桩⑤：轮询不比较嵌入驻留值 ─────────────────────────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('                val embedNow = LlmEngine.embedPath.orEmpty()\n'
       '                if (embedNow != shownEmbedPath) {\n'
       '                    shownEmbedPath = embedNow\n'
       '                    renderEmbedStatus()\n'
       '                    refreshEmbedList()\n'
       '                }\n')
assert s.count(old) == 1, "桩⑤：没找到轮询的嵌入快照比较"
open(p, 'w', encoding='utf-8').write(s.replace(old, '', 1))
PYSTUB
must_red "桩⑤（轮询不比较嵌入驻留值）" "轮询里比较嵌入驻留值快照"
restore_src

# ── 6. 桩⑥：卸载嵌入后不重画列表（▶ 会挂在已经空掉的引擎上）──────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('        renderEmbedStatus()\n'
       '        // 列表同理：那个 `▶` 会继续挂在已经空掉的引擎上 —— 与本 issue 对话侧\n'
       '        // 卸载后不重画是同一个病。\n'
       '        refreshEmbedList()\n')
new = '        renderEmbedStatus()\n'
assert s.count(old) == 1, "桩⑥：没找到卸载嵌入路径的重画两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑥（卸载嵌入后不重画列表）" "卸载后重画列表"
restore_src

# ── 7. 桩⑦：两张表行标各写一套（跨表一致性）──────────────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# 锚**嵌入表**那一段：它在对话表之前（源码顺序：嵌入 ~2260、对话 ~3060），
# 用 `loadedEmbedFile` 的**调用点**定位（那是嵌入表独有的）；不锚注释措辞 ——
# 注释会随文案改而漂，桩就会在"找不到锚"上报错，看起来像守卫坏了。
i = s.index('loadedEmbedFile()?.name')
j = s.index('isSel && isLoaded -> "▶● "', i)
s2 = s[:j] + 'isSel && isLoaded -> "★  "' + s[j + len('isSel && isLoaded -> "▶● "'):]
assert s2 != s, "桩⑦：没找到嵌入表的 ▶● 行标"
open(p, 'w', encoding='utf-8').write(s2)
PYSTUB
must_red "桩⑦（两张表行标各行一套）" "对话表与嵌入表都出现三态行标"
restore_src

# ── 8. 桩⑧：取数时不快照驻留值（渲染回调里现取）──────────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('        val loaded = loadedEmbedFile()?.name\n'
       '        ioExecutor.execute {\n'
       '            val rows = runCatching { ModelStore.rows(this) }.getOrNull()\n'
       '            ui.post { if (myGen == embedListGen) renderEmbedList(rows.orEmpty(), sel, loaded) }\n')
new = ('        ioExecutor.execute {\n'
       '            val rows = runCatching { ModelStore.rows(this) }.getOrNull()\n'
       '            ui.post { if (myGen == embedListGen) renderEmbedList(rows.orEmpty(), sel, null) }\n')
assert s.count(old) == 1, "桩⑧：没找到 refreshEmbedList 的快照行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑧（取数时不快照驻留值）" "取数时先快照驻留值"
restore_src

# ── 9. 对照：把旧写法只写进注释，必须**保持全绿** ─────────────────────────
# 判据锚的是**代码行**。注释里出现旧写法是正常说明，算进代码行会让守卫在
# 自己的说明文字上假红 —— 本仓库在这一族上栽过一次。
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun selectEmbedModel(f: File) {\n"
new = ('    // 校准注释：旧写法是 `已选 <选中名>` 上挂 n_embd，行标曾是 (if (isSel) "● " else "○ ")\n'
       '    private fun selectEmbedModel(f: File) {\n')
assert s.count(old) == 1, "桩⑨：没找到 selectEmbedModel"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_green "桩⑨（旧写法只出现在注释里）"
restore_src

# ── 10. 收尾：还原后必须全绿 ──────────────────────────────────────────────
restore_src
must_green "收尾（还原后）"

echo ""
if [ "$bad" = "0" ]; then echo "=== 嵌入状态守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 嵌入状态守卫自测：PASS $ok / FAIL $bad ==="; exit 1
