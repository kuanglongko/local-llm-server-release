#!/bin/sh
# 「设置页写的键」必须与「加载时读的键」是同一个 —— 否则档位永远传不到 native。
#
# ═══════════════════════════════════════════════════════════════════════════
# 现象与根因（0.9.127 真机回归）
# ═══════════════════════════════════════════════════════════════════════════
# 用户装机后报（原文）：
#
#     [repack] use_extra_bufts=1（来源：库默认（本仓库未设过））｜
#              设置页档位=未设过，属性 lm_extra_repack=（读不到：getprop 未返回任何值）
#
# 而用户明确说"有设置项"、且已在设置页选中了「关」。两边都没说谎：
#
#   · 设置页把档位写进 `engine_params` 的 `"repack"` 一格 ——
#     `EngineActivity.persistParams()` 里的 `"repack" to (repackMode?.toString() ?: "")`；
#     并且还原时**也从同一格读**（`savedParams["repack"]`）—— 所以 UI 上那一档
#     显示"已选中"，看起来一切正常；
#   · 加载时交给 native 的值却来自 `ModelStore.extraBufts()` ——
#     0.9.125~0.9.127 它读的是**另一个 prefs 键** `use_extra_bufts`（`KEY_EXTRA_BUFTS`），
#     而**全仓没有任何一处写这个键**（`setExtraBufts()` 被定义出来就从没被调用）。
#
# 于是 `extraBufts()` 恒为 `null` → `setRepackMode(-1)` → native `g_extra_bufts_ui = -1`
# → `model_use_extra_bufts()` 落回库默认 → 日志**永远**写"来源：库默认（本仓库未设过）"。
# 与用户装哪一版、勾哪一档**无关** —— 这是"再怎么重装也一样"的原因。
#
# ═══════════════════════════════════════════════════════════════════════════
# 判据锚「两个键是不是同一个」，不锚"某个函数/名字出现过"
# ═══════════════════════════════════════════════════════════════════════════
# 这一族最容易被写出的假绿：
#   · 「存在 extraBufts()」—— 恒真，它一直在，问题正是它读错了地方；
#   · 「存在 nativeSetRepack」—— 恒真，native 那一侧本来就是对的；
#   · 「文件里出现过 `repack`」—— 到处都有（注释、日志、UI 文案）。
# 上一版（0.9.127）判据全绿而缺陷原样：因为**两处各自都能自证**，没人对过"这两个
# 键是不是同一个"。所以本守卫**横向对账**：把设置页持久化的键与 extraBufts 读的键
# 分别取出来，比出它们相等；并钉住"没有第二个键"以及"读到的值真的进了下发链"。
#
# 运行：sh tools/run_repack_channel_guard.sh
set -e
cd "$(dirname "$0")/.."
MS=app/src/main/java/com/xiaowan/localinference/ModelStore.kt
EA=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
LL=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

nocomment() { sed -e 's://.*::' | grep -vE '^[[:space:]]*(\*|/\*|//)'; }
stripstr() { sed -e 's/"[^"]*"/""/g'; }
# 键名判据要**留下字符串字面量**（`"repack"` 本身就是被对账的对象），只剥注释。
# 剥字面量会把两边的键都抹成 `""`，于是"两个键是不是同一个"永远判绿 —— 正是本族假绿。
msn() { nocomment < "$MS"; }
ean() { nocomment < "$EA"; }
lln() { nocomment < "$LL"; }

for f in $MS $EA $LL; do c "$(basename $f) 存在" "[ -f \$f ]"; done

# ── ① 设置页确实把 repack 写进 engine_params（单子来源的"写"端）──────────
# 锚归属：这段赋值必须落在 persistParams() 的函数体里。
persist() { awk '/private fun persistParams\(\)/{inb=1} inb{print} inb && /^    }$/{exit}' "$EA"; }

c "persistParams() 里确实持久化了 repack 档位" \
  "persist | nocomment | grep -q '\"repack\" to'"

# 设置页还原也读同一格（UI 的"显示已选中"与写端同源，不是另一条旁路）
c "设置页还原时也从 savedParams[\"repack\"] 读（写读同一格）" \
  "ean | grep -q 'savedParams\[\"repack\"\]'"

# ── ② extraBufts() 读的**就是**那一格（"读"端）──────────────────────────
# 这一条是本轮的核心：不再有第二个键。
c "extraBufts() 从 engineParams 的 \"repack\" 取值（单一真源）" \
  "msn | grep -q 'engineParams(ctx)\[\"repack\"\]'"

# ── ③ 第二个键必须**不在了**（否则又是"两处各自自证"）──────────────────
c "ModelStore 里不再存在第二个 repack 存储键（KEY_EXTRA_BUFTS）" \
  "! msn | grep -q 'KEY_EXTRA_BUFTS ='"

c "也不再有无人调用的写入端 setExtraBufts()（死通道清零）" \
  "! msn | grep -q 'fun setExtraBufts('"

# ── ④ 读到的值**真的进了下发链**（不是存了不用）─────────────────────────
c "加载前把 extraBufts() 的结果交给 setRepackMode（值真的下发）" \
  "lln | grep -q 'setRepackMode(appCtx?.let { ModelStore.extraBufts(it) } ?: -1)'"

c "setRepackMode 真的调 native 主名 nativeSetRepack" \
  "lln | grep -q 'nativeSetRepack(mode)'"

# ── ⑤ 反向锚：单子来源的**语义**仍在（别为了修这条把三态压成两态）────────
# `""`（没设过）必须仍能表达 —— 落回库默认，而不是被当成 `0`（关）。
c "未设过仍与显式 0 可分（读端 takeIf \"0\"/\"1\" 的判据还在）" \
  "msn | grep -q 'takeIf { it == \"0\" || it == \"1\" }'"

c "未设过仍与显式 0 可分（写端 \"\" 的兜底还在）" \
  "persist | nocomment | grep -q 'repackMode?.toString() ?: \"\"'"

# ── ⑥ 判据网自身：不得只锚「关键词出现过」──────────────────────────────
c "本守卫剥了注释与字符串字面量（注释里必然引用坏形态）" \
  "grep -q 'stripstr' tools/run_repack_channel_guard.sh && \
   grep -q 'nocomment' tools/run_repack_channel_guard.sh"

if [ "$bad" -eq 0 ]; then
    echo "=== repack 通道守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== repack 通道守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
