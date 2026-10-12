#!/bin/sh
# 「日志页过滤态的正文/说明行必须同一帧自洽」的行为测（Issue #110 第二轮 · D-3）。
#
# 为什么不能只靠静态守卫：旧写法 `if (filtered) { if (justSwitchedTab) logTv.text = tail }`
# 是一个**二选一** —— 过滤态下要么实时（清掉用户的文本选择）、要么冻结（正文停在旧快照）。
# 而说明行是每次重算的，于是页面开始说自相矛盾的话：
#
#     T1 点「HTP」  -> 正文 2 行、说明"命中 2 行"       （一致）
#     T2 稳态来新行 -> 正文**仍是 2 行**、说明"命中 3 行"  ← 自相矛盾
#
# 静态守卫能查"写过 logTv.text"，查不出"写与不写**在什么条件下**分叉"。
# 所以这里从**落盘源码**抽出那段分流判据，翻译成 JS 后逐字跑，并对旧写法做反向对照。
#
# Node 是可选依赖：缺 node 时 REQUIRED=0 退化为 SKIP（CI 里按契约走）。
set -e
cd "$(dirname "$0")/.."

missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then
        echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"
        exit 0
    fi
    echo "缺少 $1"
    exit 2
}

command -v node >/dev/null 2>&1 || missing "node（用于实跑从源码抽出的 renderLog 分流判据）"

ENGINE=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
[ -f "$ENGINE" ] || { echo "缺少 $ENGINE"; exit 2; }

node tools/log_filter/render_log_behavior.mjs "$ENGINE"
