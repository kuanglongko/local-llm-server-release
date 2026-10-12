#!/bin/sh
# 宿主侧运行「建库成本提示」（`RagCost.kt`）的离线单测。
#
# 为什么必须钉：这一层是**唯一**在用户按下「建库/重建」之前告诉他"要多久"的地方，
# 而它的失效形态全是"提示本身变成噪音或谎言"：
#   · 条数小也弹框 -> 用户每次闭眼点确定，真有一条贵的提示时也被无视；
#   · 量级算错（秒当分钟）-> 用户据此判断"现在建还是等一下"，方向反了；
#   · 撞 maxChunks 时不换词 -> 提示说"耐心等"，等完发现后半段根本没进去；
#   · 文案塞进半篇说明书 -> 与用户明确要求的那条"简短"直接冲突。
# 都不抛异常、不崩溃，只有断言能钉住。
#
# 运行：sh tools/run_rag_cost_tests.sh
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

# 只编 RagCost.kt + 它的测试：本对象刻意**不依赖** RagIndexStore（那要 Android 栈），
# 所以这里不需要 android 之外的任何东西，也不需要 org.json。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/RagCost.kt" "tools/rag_cost/RagCostTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagCostTestKt"
