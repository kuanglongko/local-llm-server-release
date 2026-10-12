#!/bin/sh
# 宿主侧运行「建索引进度读数」（`RagProgress.kt`）的离线单测。
#
# 为什么必须钉：这是**建库那几十分钟里**用户手上唯一的参照物，而它的失效形态
# 全是"读数本身变成噪声或谎言"，一条都不崩、不报错、HTTP 无关：
#   · 位宽抖动（`1:5` vs `1:50`）-> 一行每 250 ms 抖一次，读起来像"闪"不像"走"；
#   · 分母不知道还报占比（`0/0 条（NaN%）`）-> 没有意义的数看着像读数；
#   · 第一秒内报速率 -> 分母极小放大的噪声看着像真的；
#   · 报了"预计剩余" -> 块与块耗时差一个数量级，推出来的剩余**假精确**。
#
# 运行：sh tools/run_rag_progress_tests.sh
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

# 只编 RagProgress.kt + 它的测试：本对象刻意**不依赖**任何东西
#（不 import android、不 import 本仓库其它文件），所以这里只需要 android.jar
# 之外的 kotlin 标准库 —— 连 org.json 都不用。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/RagProgress.kt" "tools/rag_progress/RagProgressTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagProgressTestKt"
