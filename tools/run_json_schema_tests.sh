#!/bin/sh
# 宿主侧运行 `response_format`（结构化输出）请求侧解析/校验的离线单测。
# 不依赖 NDK、设备、模型。
#
# 为什么必须钉（见 tools/json_schema/JsonSchemaFormatTest.kt 文件头）：
#   · 不带 response_format 时必须解析成"无约束"，且**不产生任何 native 调用** ——
#     否则所有既有客户端的输出分布都会被悄悄改掉，而没有任何测试会变红；
#   · 拼错的 type（json_shema）必须 400，不能被当成"没给"静默忽略；
#   · json_object 必须折成空串（不是 null），否则这个类型静默失效。
#
# 真正的 schema -> GBNF 转换在库内（templates_apply），那一侧由
# tools/run_schema_sampler_tests.sh 做源码级守卫（异常不得穿过 JNI、必须降级）。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar
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

# 一起编进来的三份**纯逻辑**（都只依赖 org.json / kotlin-stdlib，宿主下能编）：
#   · RenderedPrompt  —— 生成后缀的载体（结构化输出要与 prompt 对齐，见 ResponseFormat.GenerationPrompt）
#   · RequestContext   —— 模板同源 + 生成后缀的三态折叠（判据本体，必须与线上同一份实现）
#   · ThinkStream      —— RenderedPrompt 引用了它的 OPEN/CLOSE 常量
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/JsonSchemaFormat.kt" "$SRC/RenderedPrompt.kt" "$SRC/ThinkStream.kt" \
    "$SRC/RequestContext.kt" "tools/json_schema/JsonSchemaFormatTest.kt" \
    "tools/json_schema/RenderedPromptWireTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.JsonSchemaFormatTestKt"
# 回传格式的往返单测（native 渲染侧 -> 宿主）：0.9.87 那次"genPrompt=无"就是
# 这条链断在回传上，而既有断言只查"字段存不存在"，抓不到"加了字段没接线"。
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RenderedPromptWireTestKt"
