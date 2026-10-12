#!/bin/sh
# 自测 `tools/run_kt_test_source_guard.py`：把已知该红的源码就地打桩，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 那个守卫靠"剥掉注释与字符串、再在**代码**里找 JS 惯用法"成立。它有两个自欺模式：
#   1. **恒绿**：剥字符串剥过头（把代码一起剥掉），或 JS 惯用法清单被改空 ——
#      于是"没有 JS 语法"永远成立，等于没写；
#   2. **恒红**：清单里放了 Kotlin 也合法的形态（`===`、`const val`、`x.let {}`），
#      天天误红之后必然被 `|| true` 掉。
# 在 CI 里"跑一遍看它绿"发现不了任何一种。唯一有效的做法是对着**已知该红**的桩跑。
#
# 桩全部取自**真实发生过的那次**：`89ad274` 把页面内嵌 JS 抄进单测时带进来的
# `html.match(/…/g)`。另加 4 个形态不同的 JS 惯用法（覆盖判据清单里剩下的几条），
# 以及 3 个**必须保持绿**的 Kotlin 写法（防恒红）。
#
# 不需要工具链。运行：sh tools/run_kt_test_source_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_kt_test_source_guard.py
KT=tools/web_chat/WebChatPageTest.kt
PAGE=app/src/main/java/com/xiaowan/localinference/WebChatPage.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$KT" "$TMP/kt.bak"; cp "$PAGE" "$TMP/page.bak"
restore() { cp "$TMP/kt.bak" "$KT"; cp "$TMP/page.bak" "$PAGE"; rm -rf "$TMP"; }
# 就地打桩前先抢工作区独占锁：本脚本会改真源码再 trap 还原，
# 与其他同套路自测并行时会互相盖桩（假红/漏桩）。见 tools/with_worktree_lock.sh。
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() { set +e; "$PY" "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    # 只取 **FAIL 那一行**：同一个判据名在输出里可能同时出现在别的行上
    # （比如"防恒真"那条判据的 PASS 行也带同样的字），取 head -1 会取错。
    if grep -F "$key" "$TMP/guard.out" | grep -q '^FAIL'; then
        line=$(grep -F "$key" "$TMP/guard.out" | grep '^FAIL' | head -1)
        case "$line" in *FAIL*) ck "$name：指到了「$key」" 1 ;;
                         *)     ck "$name：指到了「$key」（该行不是 FAIL）" 0 ;; esac
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() { # 名字
    if run_guard; then ck "$1：守卫保持绿" 1; else ck "$1：守卫保持绿" 0; sed -n '1,20p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 1. 桩①（真实的那个 bug：把 JS 抄进 Kotlin 单测）─────────────────────────
# 这一条就是本守卫存在的理由。`html.match(/…/g)` 在 Kotlin 里编不过（String 无 match）。
"$PY" - $KT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'ck("有三个页签", countOf(html, "<button class=\\"tab") == 3)'
assert s.count(old) == 1, "stub1: anchor not found"
new = 'ck("有三个页签", (html.match(/class="tab/g) ?: emptyList()).size == 3)'
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩①（单测里出现 JS 的 String.match）" "没有 JS 专有语法"
restore_src() { cp "$TMP/kt.bak" "$KT"; cp "$TMP/page.bak" "$PAGE"; }
restore_src

# ── 2. 桩②（箭头函数块：JS 惯用法，Kotlin 里没有这种形态）──────────────────
"$PY" - $KT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'fun main() {'
assert s.count(old) == 1, "stub2: anchor not found"
new = 'val f = (x) => { x }\nfun main() {'
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩②（单测里出现箭头函数块）" "没有 JS 专有语法"
restore_src

# ── 3. 桩③（document. 直取 DOM：页面是字符串产物，单测里不该有 DOM 访问）──
"$PY" - $KT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'fun main() {'
assert s.count(old) == 1, "stub3: anchor not found"
new = 'val el = document.getElementById("x")\nfun main() {'
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_red "桩③（单测里出现 document.）" "没有 JS 专有语法"
restore_src

# ── 4. 桩④（括号配平：删一个右括号 —— 没被编译器验过时的最低证据）──────────
"$PY" - $KT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'fun main() {'
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'fun main() {', 1).replace("}", "", 1))
PYEOF
must_red "桩④（括号不配平）" "括号配平"
restore_src

# ── 5. 桩⑤（防恒真：JS 惯用法清单被改空 —— 判据① 会变成恒绿）─────────────
# 判据③（自证）钉的是"清单能命中 JS"；这里钉的是它的**另一半**：
# 清单被清空后，判据① 不再命中任何东西 -> 必须变红（否则就是恒绿）。
# 注意：这一桩改的是**守卫自己**，所以必须显式还原，不能靠源码树还原。
cp "$GUARD" "$TMP/guard.bak"
"$PY" - "$GUARD" <<'PYEOF'
import re, sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
# 把清单清空：判据① / 判据③ 都应因此失效
m = re.search(r"JS_IDIOMS = \[.*?\n\]", s, re.S)
assert m, "stub5: JS_IDIOMS not found"
open(p, "w", encoding="utf-8").write(s[:m.start()] + "JS_IDIOMS = []" + s[m.end():])
PYEOF
set +e; "$PY" "$GUARD" > "$TMP/g5.out" 2>&1; rc5=$?; set -e
if [ "$rc5" != "0" ]; then ck "桩⑤（清单清空 -> 判据①/③ 变红，不是恒绿）" 1
else ck "桩⑤（清单清空 -> 判据①/③ 变红，不是恒绿）" 0; fi
cp "$TMP/guard.bak" "$GUARD"
if "$PY" "$GUARD" >/dev/null 2>&1; then ck "桩⑤ 还原后守卫恢复全绿" 1; else ck "桩⑤ 还原后守卫恢复全绿" 0; fi

# ── 6. 三个**必须保持绿**的 Kotlin 写法（防恒红）────────────────────────────
# 这三条是本仓库里真实存在、且**合法 Kotlin** 的形态。守卫若把它们判红，
# 就是"天天误红 -> 被人 || true 掉"的那条路。每加一条都单独打一次桩。
"$PY" - $KT <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'fun main() {'
assert s.count(old) == 1, "stub6: anchor not found"
new = ('private const val LEGAL_CONST = 1\n'
       'private fun legalLet(x: String) = x.let { it.length }\n'
       'private fun legalRefEq(a: Any, b: Any) = a === b\n'
       'fun main() {')
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PYEOF
must_green "桩⑥（const val / x.let{} / 引用相等 === 都是合法 Kotlin）"
restore_src

echo ""
if [ "$bad" = "0" ]; then echo "=== Kotlin 单测源码守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== Kotlin 单测源码守卫自测：PASS $ok / FAIL $bad ==="; exit 1
