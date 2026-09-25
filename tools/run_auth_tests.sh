#!/bin/sh
# 宿主侧运行「Bearer 鉴权 + 免鉴权路径判据」的离线单测（不依赖 NDK，也不依赖设备）。
#
# 为什么单独一份：这一整套的失效**两个方向都长得像"功能坏了"**——
#   · 免鉴权的路径被判成要鉴权：`/health` 一旦要 token，服务端自己的看门狗
#     （`HttpApi.probeHealthy`）与 App 内「存活探测」（`HealthCheck`）会把自己
#     判成不可达（现象："服务明明在跑，探测说连不上"）；`OPTIONS` 一旦要 token，
#     浏览器预检 401、正式请求压根不会发出去（现象："加了鉴权浏览器就调不通"）。
#     这两种都不会让任何别的测试变红。
#   · 拆头/比较写宽松：每宽容一格就多一条绕过路径，而它只会让"唯一挡住
#     同网段任何人"的那道门变薄，请求本身全都照样 200。
#
# 判据本体在 ApiAuth（纯函数），这里跑的是同一份源码（不是重抄 —— 重抄等于没测）。
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

CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# ApiAuth.kt 是本轮唯一一处鉴权判据（HttpApi 只接线，不另写一份），
# 所以只需要它 + 测试本体。它不引 Android / org.json，但 classpath 保持一致。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/ApiAuth.kt" \
    "tools/auth/ApiAuthTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ApiAuthTestKt"
