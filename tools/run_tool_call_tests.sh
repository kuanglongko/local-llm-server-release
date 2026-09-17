#!/bin/sh
# 宿主侧运行工具调用（tools / function calling）线上形状的离线单测。
#
# 为什么单独一份：工具调用的语法解析在 native（common_chat_parse 按模型模板推导），
# 宿主侧没有 llama 运行库、也没必要为它拉一份模型；但**响应拼装**是纯 Kotlin 字符串/JSON 逻辑，
# 恰恰是这里出过两处只在装机后才暴露的错：
#   - 把 native 的原生形状当 OpenAI 的 message.tool_calls 直接吐出去（少 type/function 包装，SDK 静默丢弃）；
#   - arguments 是字符串内嵌 JSON，直接拼接会撑破外层响应体。
# 这两类错误都不报错、只是"客户端说没收到工具调用"，因此用断言钉住。
#
# 依赖与 run_sampling_tests.sh 相同（缺失时打印获取方式后退出，不静默跳过）。
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
    "$SRC/ToolCalls.kt" "tools/sampling/ToolCallsTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ToolCallsTestKt"
