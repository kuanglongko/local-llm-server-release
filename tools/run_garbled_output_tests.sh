#!/bin/sh
# 「初次安装后模型输出乱码」的取证判据。
#
# 现象：装机后首次加载模型，输出是 ` patribora@akar站©站站()||]>=\*\*stood ground站)>= ...`
# 这类词表碎片；卸载模型再加载一次就恢复正常。
#
# 日志事实（2026-09-18 15:33 probe）：
#   - 同一进程里 loadModel 被**并发调了两次**（tid=8596 与 tid=8695，间隔 4s，都跑了 ~24s）
#   - 随后生成 300 token 全是碎片、且卡在 max_tokens 上限（n=300 = max_tokens）
#   - 之后卸载 + 重新加载（只剩一条 loadModel），同一个模型、同一个请求立刻正常
#   ⇒ 不是模型坏了、也不是解码器坏了，是「两个加载同时进行」把 KV/权重搞成了脏状态。
#
# 这一套把「并发加载的痕迹必须可在日志里判定」钉住：并发本身若不能避免，
# 至少要能一眼看出来，而不是让用户靠"卸载再加载试试"去猜。
set -e
cd "$(dirname "$0")/.."
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# ── 1) 加载必须串行化：并发 nativeLoadModel 是碎片输出的首要嫌疑
c "nativeLoadModel 有互斥保护"        "grep -q 'loadLock' \$ENG"
c "互斥覆盖整个 loadModel 入口"       "sed -n '/fun loadModel(/,/^    }/p' \$ENG | grep -q 'synchronized(loadLock)'"
c "加载入口未加锁时不再静默并发"      "grep -q 'loadInFlight' \$ENG"
c "有并发加载的明确日志判据"          "grep -q '已有一次加载在进行中' \$ENG"

# ── 2) 卸载同样要串行：卸载与加载交错同样会污染状态
c "卸载与加载共用同一把锁"           "sed -n '/fun unload() = synchronized(loadLock)/,/^    }/p' \$ENG | grep -q 'nativeUnloadModel'"

# ── 3) 输出形状要有留痕，碎片能当场判定而不是靠眼睛认
c "乱码成因写进了代码（不靠人记）"    "grep -q '初次安装后乱码' \$ENG"

if [ "$bad" -eq 0 ]; then
    echo "=== 乱码/并发加载单测 全部通过（$ok 条）==="
    exit 0
else
    echo "=== 乱码/并发加载单测 $bad 条失败（共 $ok 条）==="
    exit 1
fi
