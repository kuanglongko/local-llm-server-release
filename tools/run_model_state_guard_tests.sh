#!/bin/sh
# 自测 `tools/run_model_state_guard.sh`：对着**已知该红**的桩源码跑，
# 断言"该红的红、点得出是哪一条"，还原后必须恢复全绿。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一份防的是"守卫自己失效"，而本守卫的失败模式恰是本仓库最怕的那类
# ═══════════════════════════════════════════════════════════════════════════
# 守卫里有一批**负向**断言（"旧写法应绝迹"）。负向断言天生恒绿：
# pattern 一旦写错（写成源码里根本不存在的措辞），"不出现"就永远成立，
# 这条判据从此零信息量，而它在 CI 里照样打印 PASS。
# 所以桩①②必须**真的把旧写法写回去**，守卫要变红；只跑一次看它绿发现不了。
#
# 桩来源都是**本 issue 真正的旧写法**，不是编出来的反例：
#   · 状态行只报选中（`当前: <文件名>`）—— 修复前就是这一行；
#   · 行标只由选中态决定（`if (isSel) "● " else "○ "`）—— 修复前就是这一行；
#   · 驻留判据改问 modelFile（"我以为加载了"）—— 最诱人的错法；
#   · 轮询不比较驻留值快照 —— 服务侧卸载这条路径从此不再被发现。
#
# 桩是"就地改真源码 + 改完还原"，不维护副本（副本会随源码漂移）。
# 运行：sh tools/run_model_state_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"

PY=${PYTHON:-python3}
GUARD=tools/run_model_state_guard.sh
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

# ── 1. 桩①：状态行退回"只报选中"（本 issue 修复前的那一行）───────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = '        if (sel == null) return "$loadedTxt（未选择模型）"\n'
new = ('        if (sel == null) return "$loadedTxt（未选择模型）"\n'
       '        return "当前: ${sel.name}  (${sel.length() / 1048576} MB)"\n')
assert s.count(old) == 1, "桩①：没找到状态行的选中分支"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩①（状态行只报选中）" "状态行不再退化成只报选中"
restore_src

# ── 2. 桩②：行标只由选中态决定（本 issue 修复前的那一行）─────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
# ⚠ 自嵌入侧（`run_embed_state_guard.sh`）落地后，同一段三态行标在文件里出现了**两处**
# （嵌入表 ~2258 在前、对话表 ~3061 在后）。本桩只打**对话表**那一处，用它的
# 专属尾巴 `r.external`（外链标记只在对话表里）**倒着**定位到自己那一块 mark
# （`rfind`，不是 `find` —— `external` 出现在 mark 之后）；写成
# `s.replace(..., 1)` 会改到前面那张嵌入表去，那样守卫红的会是嵌入那份的判据，
# 测试却以为验的是对话那份。
i = s.index('if (r.external) "🔗 " else ""')
j = s.rindex('            val mark = when {', 0, i)
t = s.index('            val tailTxt = when {', j)
assert 'isSel && isLoaded' in s[j:t], "桩②：没找到对话表的行标三态"
new = '            val mark = (if (isSel) "● " else "○ ")\n'
open(p, 'w', encoding='utf-8').write(s[:j] + new + s[t:])
PYSTUB
must_red "桩②（行标只由选中态决定）" "行标不再只由选中态决定"
restore_src

# ── 3. 桩③：驻留判据改问 modelFile（"我以为加载了"）───────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun loadedFile(): File? = LlmEngine.loadedPath?.let { File(it) }\n"
new = "    private fun loadedFile(): File? = modelFile\n"
assert s.count(old) == 1, "桩③：没找到 loadedFile 实现"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩③（驻留判据改问选中态）" "驻留判据不得改问本页选中态"
restore_src

# ── 4. 桩④：轮询不比较驻留值快照（服务侧卸载从此不可见）──────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('                val loadedNow = LlmEngine.loadedPath.orEmpty()\n'
       '                if (loadedNow != shownLoadedPath) {\n'
       '                    shownLoadedPath = loadedNow\n'
       '                    updateModelTv()\n'
       '                    refreshModelList()\n'
       '                }\n')
assert s.count(old) == 1, "桩④：没找到轮询的快照比较"
open(p, 'w', encoding='utf-8').write(s.replace(old, '', 1))
PYSTUB
must_red "桩④（轮询不比较驻留值）" "轮询里比较驻留值快照"
restore_src

# ── 5. 桩⑤：卸载后不重画（标记会停在旧的那一行）──────────────────────────
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = ('        updateModelTv()\n'
       '        refreshModelList()\n'
       '        // 卸载后必须重画提示区，否则 HTP 说明还停留在上一个模型的信息上\n')
new = '        // 桩：卸载后不重画\n        // 卸载后必须重画提示区，否则 HTP 说明还停留在上一个模型的信息上\n'
assert s.count(old) == 1, "桩⑤：没找到卸载路径的重画两行"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_red "桩⑤（卸载后不重画）" "卸载后重画状态行"
restore_src

# ── 6. 对照：把旧写法只写进注释，必须**保持全绿** ─────────────────────────
# 判据锚的是**代码行**。注释里出现旧写法是正常说明（本文件就在解释它为什么被换掉），
# 算进代码行会让守卫在自己的说明文字上假红 —— 本仓库在这一族上栽过一次。
"$PY" - "$ACT" <<'PYSTUB'
import sys
p = sys.argv[1]; s = open(p, encoding='utf-8').read()
old = "    private fun selectModel(f: File) {\n"
new = ('    // 校准注释：旧写法是 modelTv.text = "当前: <文件名>"，行标曾是 (if (isSel) "● " else "○ ")\n'
       '    private fun selectModel(f: File) {\n')
assert s.count(old) == 1, "桩⑥：没找到 selectModel"
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PYSTUB
must_green "桩⑥（旧写法只出现在注释里）"
restore_src

# ── 7. 收尾：还原后必须全绿 ───────────────────────────────────────────────
restore_src
must_green "收尾（还原后）"

echo ""
if [ "$bad" = "0" ]; then echo "=== 模型状态守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 模型状态守卫自测：PASS $ok / FAIL $bad ==="; exit 1
