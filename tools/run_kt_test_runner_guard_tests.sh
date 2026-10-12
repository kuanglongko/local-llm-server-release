#!/bin/sh
# 自测 `tools/run_kt_test_runner_guard.py`：把已知该红的源码就地打桩，
# 断言"该红的红、指得出是哪一条、还原后全绿"。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这条自测防的是"守卫自己失效"
# ═══════════════════════════════════════════════════════════════════════════
# 那份守卫有四个判据，每一个都能以两种方式失效：
#   ① 主类名 == 测试源码生成的类名 —— 恒绿（`class_names` 退化成"永远返回真"）
#      或恒红（把 `object Foo` + 顶层 `main` 并存的那几份误报，本仓库真实存在）；
#   ② 编进来的文件不引用未编译的类型 —— 恒绿（类型清单为空）或恒红
#      （同文件自己声明的同名类型被误判成"住在别的文件"，真发生过两处）。
#   ③ 防恒真自证；④ EmbedLimits 的纯度 + 不许第二份字面量。
# 在 CI 里"跑一遍看它绿"发现不了任何一种。唯一有效的做法是对着**已知该红**的桩跑。
#
# 桩全部取自**真实发生过的那两次**：
#   · `run_embed_retrieve_tests.sh` 的主类名少了一个 `Kt`（顶层 `fun main` 生成
#     `…TestKt`）-> 该套件**一次都没跑过**，#308 / #309 却都记成"本机 SKIP"；
#   · `HealthCheck.kt` 引用 `LlmEngine.kEmbedBatchRows`，而 `run_health_tests.sh`
#     没编 `LlmEngine.kt` -> `unresolved reference`。
# 另加"必须保持绿"的桩（防恒红）：object + 顶层 main 并存的运行器、
# 同名类型各文件各声明一份。
#
# 需要 python3（只用标准库），不需要工具链。运行：sh tools/run_kt_test_runner_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
. "$(dirname "$0")/with_worktree_lock.sh"   # 工作区独占锁：见该文件顶部说明

PY=${PYTHON:-python3}
GUARD=tools/run_kt_test_runner_guard.py

RR=tools/run_embed_retrieve_tests.sh
HC=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt
LE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
EL=app/src/main/java/com/xiaowan/localinference/EmbedLimits.kt

command -v "$PY" >/dev/null 2>&1 || { echo "缺少 python3（本测试只用标准库）"; exit 2; }

ok=0; bad=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; ok=$((ok+1));
       else echo "FAIL  $1"; bad=$((bad+1)); fi; }

TMP=$(mktemp -d)
cp "$RR" "$TMP/rr.bak"; cp "$HC" "$TMP/hc.bak"; cp "$LE" "$TMP/le.bak"; cp "$EL" "$TMP/el.bak"
restore_src() { cp "$TMP/rr.bak" "$RR"; cp "$TMP/hc.bak" "$HC"
                cp "$TMP/le.bak" "$LE"; cp "$TMP/el.bak" "$EL"; }
restore() { restore_src; rm -rf "$TMP"; }
worktree_lock_acquire "$0"
trap restore EXIT INT TERM HUP

run_guard() { set +e; "$PY" "$GUARD" > "$TMP/guard.out" 2>&1; rc=$?; set -e; return $rc; }

must_red() { # 名字 期望命中的 FAIL 关键词
    name=$1; key=$2
    if run_guard; then ck "$name：守卫变红" 0; else ck "$name：守卫变红" 1; fi
    if grep -F "$key" "$TMP/guard.out" | grep -q '^FAIL'; then
        ck "$name：指到了「$key」" 1
    else
        ck "$name：指到了「$key」（守卫里没这条判据）" 0
    fi
}
must_green() { # 名字
    if run_guard; then ck "$1：守卫保持绿" 1
    else ck "$1：守卫保持绿" 0; sed -n '1,20p' "$TMP/guard.out"; fi
}

# ── 0. 基线 ─────────────────────────────────────────────────────────────────
must_green "基线：未改动源码树"

# ── 1. 桩①（真实的那个 bug：主类名少一个 Kt）────────────────────────────────
# `run_embed_retrieve_tests.sh` 里 `java … EmbedRetrieveParityTest` —— 而那份测试
# 是顶层 `fun main`，Kotlin 生成的类名是 `EmbedRetrieveParityTestKt`。
# 症状不是红、不是绿，而是 `ClassNotFoundException`：**一条断言都没跑过**。
"$PY" - "$RR" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'com.xiaowan.localinference.EmbedRetrieveParityTestKt'
assert s.count(old) == 1, "stub1: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "com.xiaowan.localinference.EmbedRetrieveParityTest", 1))
PYEOF
must_red "桩①（主类名缺 Kt）" "主类名 == 测试源码生成的类名"
restore_src

# ── 2. 桩②（真实的那次：编进来的文件引用了没编进来的类型）───────────────────
# `HealthCheck.kt` 读 `LlmEngine.kEmbedBatchRows`，而运行器只编 `HealthCheck.kt`。
# 编译器会报 `unresolved reference 'LlmEngine'`；这里把它改回去看守卫认不认。
"$PY" - "$HC" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'EmbedLimits.kEmbedBatchRows'
assert s.count(old) == 1, "stub2: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, "LlmEngine.kEmbedBatchRows", 1))
PYEOF
must_red "桩②（引用了未编译的 LlmEngine）" "住在未编译文件里"
restore_src

# ── 3. 桩③（防恒真：把它要读的三个数从 EmbedLimits 里删掉）──────────────────
# 判据④ 要求三个数都在 EmbedLimits 里、且是字面量。删掉一个 -> 必须红
# （否则那两条判据就是恒绿：读不到也能过）。
"$PY" - "$EL" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '    const val kRetrieveParityTopK = 3\n'
assert old in s, "stub3: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, '', 1))
PYEOF
must_red "桩③（三个数少了一个）" "三个数都在 EmbedLimits 里"
restore_src

# ── 4. 桩④（真发生过的那族：同一个数在两处各写一份字面量）────────────────────
# `LlmEngine` 里再写一遍 `= 8` -> 两处各有一份，改一处就是静默失效。必须红。
"$PY" - "$LE" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'const val kEmbedBatchDefault = EmbedLimits.kEmbedBatchDefault'
assert s.count(old) == 1, "stub4: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'const val kEmbedBatchDefault = 8', 1))
PYEOF
must_red "桩④（LlmEngine 里又写了一遍字面量）" "只有引用"
restore_src

# ── 5. 桩⑤（判据④ 的另一半：EmbedLimits 碰了 Android 就不再"纯"）───────────
# 它之所以能救活那三份套件，全部前提是"宿主上编得到"。加一句 android import 就破。
"$PY" - "$EL" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = 'object EmbedLimits {'
assert s.count(old) == 1, "stub5: anchor not found"
open(p, "w", encoding="utf-8").write(s.replace(old, 'import android.content.Context\n\n' + old, 1))
PYEOF
must_red "桩⑤（EmbedLimits 碰 Android）" "不碰 Android"
restore_src

# ── 6. 防恒红：object + 顶层 main 并存的运行器必须保持绿 ─────────────────────
# `tools/rag_cost/RagCostTest.kt` 里**既有** `object RagCostTest` **又有**顶层
# `fun main()` —— 两个类名都会生成。第一版守卫只取"第一个匹配"，把这一批**对的**
# 运行器全判红（判据恒红 -> 下一手就是 `|| true`）。
# 这里把它那份测试**换给检索对照那个运行器**（只换"编哪份测试"与主类名，
# 不换别的），断言守卫认"两个类名都给"。⚠ 这一桩刻意**保持绿**：
# 它是防恒红的，不是防恒绿的。
"$PY" - "$RR" <<'PYEOF'
import sys
p = sys.argv[1]; s = open(p, encoding="utf-8").read()
old = '"$SRC/EmbedRetrieveParity.kt" "tools/embed_retrieve/EmbedRetrieveParityTest.kt"'
assert old in s, "stub6: anchor not found"
s = s.replace(old, '"$SRC/EmbedRetrieveParity.kt" "tools/rag_cost/RagCostTest.kt"', 1)
old2 = 'com.xiaowan.localinference.EmbedRetrieveParityTestKt'
assert old2 in s, "stub6: class anchor not found"
s = s.replace(old2, 'com.xiaowan.localinference.RagCostTestKt', 1)
open(p, "w", encoding="utf-8").write(s)
PYEOF
# ⚠ 这一桩同时会碰到判据②（RagCostTest.kt 引用了未编译的 RagCost）——
# 所以这里**只看判据① 是否保持绿**，不看整体退出码。
set +e; "$PY" "$GUARD" > "$TMP/g6.out" 2>&1; set -e
if grep -E '^PASS +每个运行器的主类名' "$TMP/g6.out" >/dev/null; then
    ck "桩⑥（object + 顶层 main 并存：判据① 不误红）" 1
else
    ck "桩⑥（object + 顶层 main 并存：判据① 不误红）" 0
    grep -E '主类名' "$TMP/g6.out" | head -3
fi
restore_src

# ── 7. 防恒红：同名类型各文件各声明一份必须保持绿 ────────────────────────────
# `HealthCheck.kt` 自己声明 `enum class Verdict`，`EmbedParity.kt` 里也有一个
# `Verdict` —— "类型名 -> 唯一文件"的映射会把前者误判成"引用了未编译的
# EmbedParity.Verdict"（第一版就恒红在这里）。这一桩钉住"别退回去"。
must_green "桩⑦（同名类型各文件各声明一份：不误红）"

echo ""
if [ "$bad" = "0" ]; then echo "=== Kotlin 单测运行器守卫自测：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== Kotlin 单测运行器守卫自测：PASS $ok / FAIL $bad ==="; exit 1
