#!/bin/sh
# 宿主侧运行「知识库索引持久化」（`RagIndexStore.kt`）的离线单测。
#
# 为什么必须钉：索引持久化的失效形态**全是静默的** ——
#   · 头字段偏移与 `rag_index.h` 不一致 -> 把指纹当向量读，维度"对得上"，
#     检索永远无意义，而接口 200、没有异常；
#   · 字节序没显式指定 -> 在别的大端环境里 float 读成乱码，**不崩**；
#   · 截断 / 损坏文件被"半截凑合用" -> 按错的 count 读越界（我们的 decode
#     必须整体拒绝，这一条就是钉它）；
#   · 库名净化把非法名**静默改造成另一个名字**（而不是拒绝）->
#     用户以为在用 A 库，实际读写的是 B 库；
#   · `../` 没挡住 -> 索引落到库目录之外，是任意路径写入；
#   · 三态（none / empty / valid）混成两态 -> 空知识库每次打开都触发一次
#     "重建"，用户看到页面一直在转，而没有一条日志说清它在转什么。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/android-35/android.jar
#   /tmp/json.jar（RagIndexStore 引 org.json 吗？——不引，但 android.jar 的
#     空壳 org.json 会污染 classpath，按本仓库惯例排真实现在前）
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

# kotlinc 是 shell 脚本，内部靠 PATH 找 java（见 run_protocol_fields_tests.sh 同款说明）
# JDK：优先环境变量里已给的，其次常见安装目录（CI 的实际形态见
# tools/run_protocol_fields_tests.sh 的 `$TC/jdk-*/bin/java`）。
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

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/RagIndexStore.kt" "$SRC/RagKbName.kt" "$SRC/TextBoundary.kt" \
    "tools/rag_index/RagIndexStoreTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
# 正文 hash 的**共用向量表**：与 C++ 侧单测读同一个文件（那条走 argv[1]，
# 见 run_rag_index_tests.sh）。不传的话 Kotlin 侧会尝试相对仓库根的默认路径 ——
# 那样一旦有人改了 CWD 就变成"读不到表"，而读不到的判据是 FAIL（不是 SKIP），
# 正好能把这种环境问题暴露出来，而不是静默少测一块。
TEXTS_HASH_VECTORS="$(pwd)/tools/rag_index/texts_hash_vectors.tsv" \
    "$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagIndexStoreTestKt"
