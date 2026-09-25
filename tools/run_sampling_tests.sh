#!/bin/sh
# 宿主侧运行采样参数的离线单测（不依赖 NDK，也不依赖 Android 设备）
#
# 覆盖两块最容易静默出错、且真机上极难排查的语义：
#   1. SamplingParams 的默认值 / 校验 / seed 规整（SamplingParamsTest.kt）
#   2. 采样链的**顺序**与门禁条件（SamplingChainTest.kt，照抄 llama_jni.cpp 的建链分支）
# 症状不自明的采样 bug（presence_penalty 被丢弃、惩罚排在截断之后、
# 相邻请求拿到同一个 seed）在 APK 里只能靠"输出看着不对劲"发现，
# 因此这里用断言把语义钉死，改 native 建链顺序时会立刻报错。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc   Kotlin 2.0.21
#   /workspace/.omnibot/toolchain/jdk-*/bin/java       JDK 21（编译+运行）
#   /tmp/json.jar                                      org.json 真实现（android.jar 里是 stub）
#   /workspace/.omnibot/toolchain/android-35/android.jar
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：CI 是 AFK 编包场景，
# 缺工具链属环境问题；与之相对，**测试断言失败**必须拦住。缺省 REQUIRED=1。
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

# kotlinc 是 shell 脚本，内部靠 PATH 找 java。TC 不在默认 PATH 里，
# 不显式导出会得到 "java: command not found" —— 而 kotlinc 此时仍返回 0，
# 于是脚本会一路走到运行期才报 ClassNotFoundException，原因完全看不出来。
JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

# android.jar 里的 org/json 是抛 "Stub!" 的空壳，运行期必须让真实现优先。
# 因此 classpath 顺序为：测试产物 -> json.jar -> android.jar。
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

fail=0
for t in SamplingParamsTest SamplingChainTest; do
    echo "== $t =="
    # 编译日志先落文件再判定：kotlinc 非零退出遇上 set -e 会直接带崩脚本
    # （表现为"编译失败"，其实只是没匹配到 grep），故显式吞掉退出码，
    # 改由日志内容判定（CLI 的报错前缀是 `path:line:col: error:`）。
    # 日志写到 OUT 之外：OUT 是 kotlinc 的 -d 产物目录，往里塞非 class 文件容易被清理规则波及。
    "$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
        "$SRC/SamplingParams.kt" "tools/sampling/$t.kt" > "$LOG" 2>&1 || true
    if grep -qE ": error:" "$LOG"; then
        grep -E ": error:" "$LOG"
        exit 1
    fi
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.${t}Kt" || fail=1
done

[ "$fail" = 0 ] && echo "=== 采样参数单测全部通过 ===" || { echo "=== 采样参数单测失败 ==="; exit 1; }
