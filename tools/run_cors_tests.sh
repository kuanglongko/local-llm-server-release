#!/bin/sh
# 宿主侧运行「CORS 白名单 + 预检 + 自带测试页」的离线单测（不依赖 NDK，也不依赖设备）。
#
# 为什么单独一份：这一整套东西的失效**两个方向都静默**——
#   · 放行过宽（回 `*` / 把非法 Origin 规范化成合法）：接口无鉴权，回 `*` 等于
#     「你在浏览器里打开的任何一个网页都能调这台手机的模型」；不会让任何现有测试变红；
#   · 放行过窄（白名单判反 / 漏了预检）：浏览器侧只表现为"请求失败"，
#     与"服务根本没起来"完全同形 —— 而那正是本次要修的那个问题本身。
#
# 判据本体在 CorsPolicy（纯函数），这里跑的是同一份源码（不是重抄 —— 重抄就等于没测）。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /workspace/.omnibot/toolchain/android-35/android.jar
#   /tmp/json.jar
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：
# CI 是「AFK 编包」场景，缺工具链属环境问题、不是代码问题，不该拖垮整条流水线；
# 与之相对，**测试断言失败**必须拦住（那才是代码问题）。
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
JAVA=$(ls -d "$TC"/jdk-*/bin/java 2>/dev/null | head -1)
JAR="$TC/android-35/android.jar"
JSON=/tmp/json.jar

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -x "$JAVA" ]    || missing "JDK：解压 OpenJDK*-jre_*_linux_hotspot_*.tar.gz 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"
[ -f "$JSON" ]    || missing "/tmp/json.jar：curl -o /tmp/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"

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

# WebChatPage.kt 是自带测试页的页面本体（CorsPolicy.pageHtml 现在转发给它），
# 所以本文件也要一起编 —— 不带上它 CorsPolicy.kt 里那个转发调用直接编不过。
# SamplingParams.kt 只取它的 DEF_* 默认值（页面字段表的默认值引它，不各写一份字面量）。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/CorsPolicy.kt" "$SRC/WebChatPage.kt" "$SRC/SamplingParams.kt" \
    "tools/cors/CorsPolicyTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.CorsPolicyTestKt"
