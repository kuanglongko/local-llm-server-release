#!/bin/sh
# 宿主侧运行「思考开关」的离线单测。
#
# 为什么单独一份：「关闭思考」不是调 API，而是往渲染好的 prompt 里补一段闭合 think 段——
# 纯字符串拼接，判错了、插错位置，既不报错也不崩，只表现为"设了没生效"甚至"回答为空"。
#
# 真实发生过的两处：
#   1. 判据只看模板里有没有 enable_thinking，而 LFM2.5 的"思考开"是模板生成后缀硬编码的，
#      判据恒假 -> 从不注入 -> 「默认关闭思考」怎么设都没用；
#   2. 判据修对之后若照旧在 prompt 末尾追加整块 <think>...</think>，
#      会与后缀里已有的 <think> 形成两个开标签，最早那个永不闭合 -> 回答被当思考段吞掉。
# 两处都没有异常、没有日志，只能靠断言钉住。
#
# 依赖与 run_health_tests.sh 相同（缺失时打印获取方式后退出，不静默跳过）。
set -e
cd "$(dirname "$0")/.."

TC=/workspace/.omnibot/toolchain
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAVA=$(ls -d "$TC"/jdk-*/bin/java 2>/dev/null | head -1)
JAR="$TC/android-35/android.jar"
JSON=/tmp/json.jar

[ -x "$KOTLINC" ] || { echo "缺少 kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"; exit 2; }
[ -x "$JAVA" ]    || { echo "缺少 JDK：解压 OpenJDK*-jre_*_linux_hotspot_*.tar.gz 到 $TC"; exit 2; }
[ -f "$JAR" ]     || { echo "缺少 android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"; exit 2; }
[ -f "$JSON" ]    || { echo "缺少 /tmp/json.jar：curl -o /tmp/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"; exit 2; }

JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

# classpath 顺序：测试产物 -> json.jar（真实现）-> android.jar（其 org.json 是抛 Stub! 的空壳）
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/ThinkingControl.kt" "tools/thinking/ThinkingControlTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ThinkingControlTestKt"
