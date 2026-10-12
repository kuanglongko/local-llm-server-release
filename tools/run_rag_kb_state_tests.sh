#!/bin/sh
# 宿主侧运行「知识库状态分类」（`RagIndexStore.Info` 的 isPending / onDisk / deletable）
# 的离线单测。
#
# 为什么必须钉：这一层的失效形态全是**静默**的（见 tools/rag_kb_state/RagKbStateTest.kt
# 文件头）—— 三态少一档像"屏幕上说了另一件事"，而 deletable 与 onDisk 分家像
# "列表里点得动、按钮说没有它"。两种都不崩、不报错、日志干净。
#
# ⚠ 它是**零逻辑依赖**的（只用到 `Info` 这个 data class，不碰磁盘），所以
#   只需 android.jar（`Info` 住在含 Context 的 object 里，编译时要那个 jar）。
#
# 运行：sh tools/run_rag_kb_state_tests.sh
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

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"

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

CP="$OUT:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/RagIndexStore.kt" "$SRC/RagKbName.kt" "tools/rag_kb_state/RagKbStateTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagKbStateTest"
