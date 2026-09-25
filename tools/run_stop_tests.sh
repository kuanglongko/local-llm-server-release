#!/bin/sh
# 宿主侧运行 `stop` / `stop_sequences` 的离线单测（不依赖 NDK，也不依赖 Android 设备）。
#
# 覆盖两块：
#   1. 请求侧解析/校验（tools/stop/StopSequencesTest.kt）—— 空串、类型、上限；
#   2. 非流式兜底截断的命中语义（同文件）。
#
# 为什么必须钉：`stop` 此前**完全没实现**，传了被静默忽略。补齐后最容易再犯的是
# 「空串被当合法」→ `indexOf("") == 0` → 输出恒为空，表现为"模型坏了"；
# 以及「按数组顺序截断」→ 同一请求不同写法结果不一致。两者都不抛异常。
#
# 真正的拦截在 native（llama_jni.cpp 的 local-stop 采样器），那一侧由
# tools/run_stop_sampler_tests.sh 用真 C++ 逻辑跑（因为它必须逐 token 判、
# 且在 stream=true 下要在下发前拦住，Kotlin 侧测不到）。
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

# kotlinc 是 shell 脚本，内部靠 PATH 找 java（见 run_sampling_tests.sh 同款说明）
JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

# classpath 顺序：测试产物 -> json.jar（真实现）-> android.jar（org.json 是 Stub! 空壳）
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/StopSequences.kt" "tools/stop/StopSequencesTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.StopSequencesTestKt"
