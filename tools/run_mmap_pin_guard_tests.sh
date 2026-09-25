#!/bin/sh
# run_mmap_pin_guard.sh 的自测：**每条判据都要能红**，且红在真的坏形态上。
#
# 为什么必须有这一条：守卫失效的形态是"全绿"，而全绿与"真的通过了"同形。
# 本轮（M4 反转）最可能的假绿，与上一轮**方向相反**：
#   · 上一轮怕的是"加了日志与辅助函数，却没真把 mp.devices 改掉"；
#   · 本轮怕的是"`mp.devices` 出现过赋值，但值不是 nullptr、或不在 useMmap 的分支里"
#     —— "显式全池"是个**契约**，只判"存在赋值"会把它顶成恒真。
# 所以下面的桩同时打这两面：旧形态（钉池回来了）与新形态（赋值在分支外/值不对）。
#
# 桩都取自真实历史形态或真机上真会发生的退化：
#   · 钉池机制原样回来（本轮要撤的东西）；
#   · `mp.devices` 只在日志里出现、真赋值没写（上一轮的假绿形态）；
#   · 赋值挪到 useMmap 的 if/else **之外**（判存在 vs 判归属：这一条专打判归属）；
#   · 只写一支 `nullptr`（把"两支都写"退回成"落一半"，正是 da30046 的病）；
#   · `mmap=1` 支改成指向别的池（从全池变回子集 = 过度修复换个写法回来）；
#   · 分类不再报默认 buft 名（逐组不可观测，用户又要从设备名猜组）；
#   · 日志不再说"不互斥 / 加速不受影响"（用户无从判断代价）。
#
# 运行：sh tools/run_mmap_pin_guard_tests.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
chk() {
    name="$1"; want="$2"
    if sh tools/run_mmap_pin_guard.sh >/dev/null 2>&1; then got=green; else got=red; fi
    if [ "$got" = "$want" ]; then echo "PASS  $name（$got）"; ok=$((ok+1));
    else echo "FAIL  $name：期望 $want，实得 $got"; bad=$((bad+1)); fi
}

STASH=/tmp/mmap_pin_stash
rm -rf "$STASH"; mkdir -p "$STASH"
cp "$JNI" "$STASH/llama_jni.cpp"
restore() { cp "$STASH/llama_jni.cpp" "$JNI"; }
trap restore EXIT

stub() {
    name="$1"; want="$2"; prog="$3"
    cp "$STASH/llama_jni.cpp" "$JNI"
    printf '%s\n' "$prog" | python3 - "$JNI"
    chk "$name" "$want"
}

# ① 钉池机制原样回来（本轮撤回的对象重新出现）
stub "钉池机制回来：mp.devices = g_cpu_only_devs" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=1"
assert old in s, "源码形状变了，本自测要一起更新"
new = ("        static lm_ggml_backend_dev_t g_cpu_only_devs[2] = {nullptr, nullptr};\n"
       "        g_cpu_only_devs[0] = lm_ggml_backend_dev_by_type(LM_GGML_BACKEND_DEVICE_TYPE_CPU);\n"
       "        mp.devices = g_cpu_only_devs;\n"
       "        jlog(\"[mmap池] mmap=1")
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ② 核心假绿（上一轮形态）：mp.devices 只在日志里出现，真赋值没写
stub "只加日志、mmap=1 支的 mp.devices 真赋值没写" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=1"
assert old in s
new = "        /* mp.devices 忘了写 */\n        jlog(\"[mmap池] mmap=1"
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ③ 赋值挪到 if/else **之外**（判存在 vs 判归属：专打判归属那一组判据）
stub "两支赋值都挪到 useMmap 分支之外（判归属必须逮住）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
a = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=1"
b = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=0"
assert a in s and b in s
s = s.replace(a, "        jlog(\"[mmap池] mmap=1", 1)
s = s.replace(b, "        jlog(\"[mmap池] mmap=0", 1)
# 在 if 之前补一次"全局"赋值：文件里仍有 mp.devices = nullptr，但不在分支内
anchor = "    if (useMmap == JNI_TRUE) {\n        jlog(\"[mmap池] mmap=1"
assert anchor in s
s = s.replace(anchor, "    mp.devices = nullptr;\n" + anchor, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ④ 只写一支 nullptr（"两支都写"退回"落一半"，da30046 的病）
stub "只写一支 nullptr（else 支不显式回落）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=0"
assert old in s
s = s.replace(old, "        jlog(\"[mmap池] mmap=0", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑤ mmap=1 支改成指向别的池（全池 → 子集，过度修复换个写法回来）
stub "mmap=1 支改成指向别的池（又从全池变回子集）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        mp.devices = nullptr;\n        jlog(\"[mmap池] mmap=1"
assert old in s
new = ("        static lm_ggml_backend_dev_t g_cpu_only[2] = {nullptr, nullptr};\n"
       "        mp.devices = g_cpu_only;\n"
       "        jlog(\"[mmap池] mmap=1")
s = s.replace(old, new, 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑥ 分类不再报默认 buft 名（逐组不可观测，用户又要从设备名猜组）
stub "分类丢掉默认 buft 名（逐组不可观测）" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "        buft_name = lm_ggml_backend_buft_name(bt);"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "        buft_name = \"?\"; (void) bt;", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑦ 日志不再说"不互斥 / 加速不受影响"（用户无从判断代价是否被付掉）
stub "mmap=1 日志丢掉『不互斥』那半句" red '
import sys, io
p = sys.argv[1]; s = io.open(p, encoding="utf-8").read()
old = "两者不互斥，加速不受影响"
assert old in s, "源码形状变了，本自测要一起更新"
s = s.replace(old, "池已就绪", 1)
io.open(p, "w", encoding="utf-8").write(s)
'

# ⑧ 反向：真源码（本轮修复后的写法）必须全绿
stub "修复后的写法（反向对照）" green '
import sys
'

if [ "$bad" -eq 0 ]; then
    echo "=== mmap 设备池守卫自测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== mmap 设备池守卫自测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
