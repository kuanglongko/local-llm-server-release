#!/bin/sh
# 宿主侧运行「存活探测」（GET /health）的离线单测。
#
# 为什么单独一份：探测要回答的是"端口到底通不通"，而它最可能的失效方式是**把结论报反**——
# 服务器没回 200 / status 不是 ok / 模型没加载（生成必 503）却显示"存活"，
# 或者真故障（连接被拒、连上没人应答）被当成"服务正常"。
# 这类错不抛异常、界面看着也正常，只能靠断言钉住；另外本测试会真开临时端口连一次，
# 覆盖"超时是否生效、正文是否含响应头、连接被拒是否快速返回"这些只有真连才暴露的问题。
#
# 依赖与 run_sampling_tests.sh 相同（缺失时打印获取方式后退出，不静默跳过）。
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：
# CI 是「AFK 编包」场景，缺工具链属环境问题、不是代码问题，不该拖垮整条流水线；
# 与之相对，**测试断言失败**必须拦住（那才是代码问题）。
# 缺省 REQUIRED=1 = 严格模式（本地/默认都按"必须跑到"处理）。
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

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/HealthCheck.kt" "tools/health/HealthCheckTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.HealthCheckTestKt"
