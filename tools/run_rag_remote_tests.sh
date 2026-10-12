#!/bin/sh
# 宿主侧运行「RAG 阶段 3：远端路径」纯逻辑的离线单测。
#
# 覆盖面：`EmbeddingsApi`（/v1/embeddings 的请求解析 / 校验 / 响应拼装）+
# `RagProtocol`（远端注入的协议层可见面）。
#
# 为什么必须钉：这两层的失效形态**全是静默的** ——
#   · index 按"第几个成功"算 -> 中间有一条失败时向量与文本整体错位，检索全错、接口 200；
#   · token id 数组被"尽力解释"成文本 -> 调用方以为编码了自己的输入，实际是另一段；
#   · 数值用 %f 格式化 -> 小分量被压成 0，向量方向悄悄变了；
#   · 没注入却在响应里留 local_rag 空值 -> "没开"与"开了没命中"不可分。
# 都不抛异常、不崩溃、不报错，只有断言能钉住。
#
# 运行：sh tools/run_rag_remote_tests.sh
# 缺工具链时 REQUIRED=0 退化为 SKIP（CI 是 AFK 编包场景），否则硬失败。
set -e
cd "$(dirname "$0")/.."

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
JAR="$TC/android-35/android.jar"
JSON=/tmp/json.jar

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"
[ -f "$JSON" ]    || missing "/tmp/json.jar：curl -o /tmp/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"

if [ -z "${JAVA_HOME:-}" ]; then
    for j in "$TC"/jdk-*/bin/java /usr/lib/jvm/*/bin/java; do
        [ -x "$j" ] && JAVA_HOME=$(dirname "$(dirname "$j")") && break
    done
fi
[ -n "${JAVA_HOME:-}" ] || missing "JDK（JAVA_HOME 或 $TC/jdk-*/）"
JAVA="$JAVA_HOME/bin/java"
[ -x "$JAVA" ] || missing "JDK 的 java 可执行文件（$JAVA）"
PATH="$JAVA_HOME/bin:$PATH"
export PATH JAVA_HOME

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# 只编这两个源文件 + 两份测试：`RagInject.kt` 是 RagProtocol 的间接依赖
# （RagProtocol 不引用它，但 EmbeddingsApi 也不需要 —— 两者都是纯逻辑，
#  依赖只有 org.json；多编一个文件会引入 android.* 依赖而编不过）。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/EmbeddingsApi.kt" "$SRC/RagProtocol.kt" \
    "tools/rag_remote/EmbeddingsApiTest.kt" "tools/rag_remote/RagProtocolTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.EmbeddingsApiTestKt"
