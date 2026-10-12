#!/bin/sh
# 宿主侧运行「检索对照」（`EmbedRetrieveParity.kt`）的离线单测（ISSUE #269 第 5 步）。
#
# 为什么必须钉：这一层的结论**直接决定**"`g` 能不能按库放行"，而它的失效形态
# 全是"把一个像真的的读数当成结论"：
#   · 两侧都搜不到（`vacuous`）被算成"一致" -> `answerChanged == 0` 被读成
#     "答案没变"，而其实**什么都没量到**；
#   · 只比分数、不比下标 -> "数值不同"与"答案不同"混成一个读数（本层要分开的正是它）；
#   · 集合与名次压成一格 -> "打平处换序"与"答案变了"混一起，而两者处置相反；
#   · `measured` 不单独一格 -> 没测时 `answerChanged` 也是 0（与"全同"同形）。
# 都不抛异常、不崩溃，只有断言能钉住。
#
# 运行：sh tools/run_embed_retrieve_tests.sh
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

# 只编这两个：EmbedRetrieveParity（被验对象）与测试本身。两者都**不依赖**任何
# Android 类型（不碰 Context、不碰文件、不碰 native），所以不需要 android 之外的
# 任何东西 —— 这也是"判据落在纯逻辑层"的意思。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/EmbedRetrieveParity.kt" "tools/embed_retrieve/EmbedRetrieveParityTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.EmbedRetrieveParityTestKt"
