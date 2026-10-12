#!/bin/sh
# 宿主侧运行「嵌入合批读数」（`EmbedBatchStats.kt`）的离线单测（ISSUE #269 第 1 步）。
#
# 为什么必须钉：这一层是**唯一**能回答"合批到底有没有生效、省了多少"的地方，
# 而它的失效形态全是"读数本身变成一个像正常数的东西"：
#   · 批次数与条数对不上（一个涨、一个没涨）—— 看着像个正常数；
#   · 负耗时被记进去 —— 平均毫秒无意义，而它看着像个正常数；
#   · 空态返回 0.0 而不是 null —— 会被读成"合批命中率 0%"（一个**结论**），
#     而真相是"还没跑过"（没有读数），两者的行动方向相反；
#   · reset 后仍有残留 —— 换了模型，读数还描述上一个模型。
# 都不抛异常、不崩溃，只有断言能钉住。
#
# 运行：sh tools/run_embed_stats_tests.sh
# 缺工具链时 REQUIRED=0 退化为 SKIP（CI 是 AFK 编包场景），否则硬失败。
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

TC=/workspace/.omnibot/toolchain
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAR="$TC/android-35/android.jar"

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"

if [ -z "${JAVA_HOME:-}" ]; then
    for j in "$TC"/jdk-*/bin/java /usr/lib/jvm/*/bin/java; do
        [ -x "$j" ] && JAVA_HOME=$(dirname "$(dirname "$j")") && break
    done
fi
[ -n "${JAVA_HOME:-}" ] || missing "JDK（JAVA_HOME 或 $TC/jdk-*/）"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || missing "JDK 的 java 可执行文件（$JAVA）"
PATH="$JAVA_HOME/bin:$PATH"
export PATH JAVA_HOME

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

CP="$OUT:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# 只编 EmbedBatchStats.kt + 它的测试：本对象刻意**不依赖**任何 Android 类型
# （不碰 Context、不碰文件），所以这里不需要 android 之外的任何东西。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/EmbedBatchStats.kt" "tools/embed_stats/EmbedBatchStatsTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.EmbedBatchStatsTest"
