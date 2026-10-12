#!/bin/sh
# 宿主侧运行协议字段（max_completion_tokens / logit_bias / logprobs）的离线单测。
#
# 为什么必须钉：这三个字段的失效形态**全是静默的** —— 客户端设了值、服务端照 200 回、
# 参数没生效（或只生效一半），而从输出上分不出来：
#   · `max_completion_tokens` 是**新版 OpenAI SDK 的默认字段名**。不认识它时每轮跑满
#     默认 512，而调用方以为自己设的是 100；
#   · `logit_bias` 传了等于没传（请求里根本没这个字段）；
#   · `top_logprobs` 单独给是协议错误（OpenAI 也报 400），而"静默接受"会让调用方
#     以为拿到了候选置信度。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar
#   /workspace/.omnibot/toolchain/android-35/android.jar
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：CI 是「AFK 编包」场景，
# 缺工具链属环境问题、不是代码问题。与之相对，**测试断言失败**必须拦住。
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

# classpath 顺序：测试产物 -> json.jar（真实现）-> android.jar（org.json 是 Stub! 空壳）。
# SamplingParams.kt 必须一起编：别名 `max_completion_tokens` 的判据（上限 / 400 文案）
# 是从它那里引的，不编译它就等于没钉住"两个字段名共用同一份"这件事。
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/SamplingParams.kt" "$SRC/ProtocolFields.kt" "$SRC/FinishReason.kt" \
    "tools/protocol_fields/ProtocolFieldsTest.kt" "tools/protocol_fields/FinishReasonTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
# 两份都跑：协议字段的解析判据（含 n）与 finish_reason 的档位判据。
# FinishReason.kt 无 android import、纯逻辑，宿主侧能直接跑。
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ProtocolFieldsTestKt"
echo
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.FinishReasonTestKt"
