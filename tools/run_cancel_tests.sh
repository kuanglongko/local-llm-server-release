#!/bin/sh
# 宿主侧运行「请求取消」（客户端断连 / POST /v1/abort）的离线单测。
#
# 覆盖两块：
#   1. 归属判定与三态断连判据（tools/cancel/RequestCancelTest.kt）—— 纯逻辑；
#   2. 判据与两处入口的一致性（同文件末尾的源码级断言）。
#
# 为什么必须钉：这个特性两个方向的失效都**没有任何异常、没有日志**——
# 漏判（客户端走了还继续跑）只是白烧 CPU，误判（客户端在却掐了生成）表现为
# 「回答说到一半停住」。两者都不会让测试自己变红，只有断言能钉住。
# 另外这里真的开一对 socket 跑一遍写探测：de "对端正常关闭后写还成不成功、
# 读得到什么"这类事实只有真连一次才知道，而整个判据都建立在这上面。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar
#   /workspace/.omnibot/toolchain/android-35/android.jar
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
    "$SRC/RequestCancel.kt" "tools/cancel/RequestCancelTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RequestCancelTestKt"
