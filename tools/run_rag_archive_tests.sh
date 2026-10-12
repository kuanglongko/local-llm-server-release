#!/bin/sh
# 宿主侧运行「知识库正文导入 / 导出」（`RagArchive.kt`）的**行为测试**。
#
# 为什么不能只靠 `run_rag_archive_guard.sh`：守卫钉的是"结构没改回去"
# （信封不带向量 / 格式名判据在读 entries 之前 / 坏行计数 / 删索引在写正文之前），
# 它证明不了"这套结构在**边界输入**下真的对"。而本特性的难点全在边界：
#
#   · 三种形状（信封 / 裸数组 / 裸 JSONL）都要能吃，而别的 App 的备份文件
#     （`{"sessions":[…]}`）必须**拒绝** —— 若"尽力解析"，用户看到的是
#     "导入 0 条"并读成"我的笔记是空的"，而真相是他选错了文件；
#   · 坏行要跳过**并计数**（不整份拒绝、也不静默带上）；末尾空行**不算**坏行
#     （否则每次导入都报一次假警，那个提示很快会被闭眼点掉）；
#   · 超限必须**整份拒绝**而不是截断（静默丢掉后半份笔记不可恢复）；
#   · 往返一致 —— "备份"本身不可信的话，这个功能就没有意义；
#   · 信封里**出现** vector/dim 时要能吃下（用户手改过/未来版本），
#     但**绝不能**把它带进 Entry —— 带上就是"用旧向量命中新正文"那条静默错。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar（org.json 真实现；android.jar 里的 org.json 是 Stub! 空壳）
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

# classpath 顺序：测试产物 -> json.jar（真实现）-> android.jar（org.json 是 Stub! 空壳）。
# RagIndexStore.kt 必须一起编：RagArchive 的条目类型就是它的 Entry，
# 不编译它等于测试的是"另一份 Entry"。它 import 了 android.content.Context，
# 但宿主侧只有 Stub! 空壳 —— 本测试**不调用**任何带 Context 的方法，
# 所以 Stub 不会被触发（与 run_chat_archive_tests.sh 同理）。
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/RagArchive.kt" "$SRC/RagIndexStore.kt" "$SRC/RagKbName.kt" "$SRC/TextBoundary.kt" \
    "tools/rag_archive/RagArchiveTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagArchiveTestKt"
