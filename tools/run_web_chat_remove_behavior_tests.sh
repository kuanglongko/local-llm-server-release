#!/bin/sh
# 「网页版生成中删除」的真实行为测（Issue #110 第二轮 · 模块 D / D-2）。
#
# 为什么不能只靠静态守卫：这一族的失效**没有症状** —— 气泡真的从页面消失了，
# 用户以为删成功了，而被删的回答仍留在 history 里、之后每轮都喂给模型。
# 静态断言（守卫）只能查"两处都写了 splice"，那只证明**存在**，
# 证明不了"删的时候 history 里真的有那一条" —— 旧代码正是"两处都写了"却照样坏。
#
# 所以这里从**落盘源码**里逐字抽出 `removeAt`，在 Node 里原样执行，
# 覆盖三个场景（生成中 / 定型后 / 两侧错位）并对旧写法做**反向对照**。
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

command -v node >/dev/null 2>&1 || missing "node（用于实跑从源码抽出的 removeAt）"

WEB=app/src/main/java/com/xiaowan/localinference/WebChatPage.kt
[ -f "$WEB" ] || { echo "缺少 $WEB"; exit 2; }

node tools/web_chat/remove_at_behavior.mjs "$WEB"
