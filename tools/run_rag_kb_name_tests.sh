#!/bin/sh
# 宿主侧运行「显示名 ↔ 目录名那张表」（`RagKbName.kt`）的离线单测。
#
# 为什么必须钉：这一层的失效形态全是**静默**的（见 `tools/rag_kb_name/RagKbNameTest.kt`
# 文件头）—— 映射丢了像"改名没生效"，映射串了像"改名把另一个库改没了"，
# 唯一性没挡像"内容进了另一个库"。三种都不崩、不报错、日志干净。
#
# ⚠ 这一套**不是零依赖**：`RagKbName` 借用了 `RagIndexStore.sanitizeName`
#   （那一个函数不含 Android 类型，但它所在的 object 含），所以这里要连
#   `RagIndexStore.kt` 一起编、并且需要 android.jar。这不是"顺手多编一个" ——
#   让两处各写一套净化规则的代价见 `RagKbName` 文件头，而单测第 ⑧ 条钉的
#   正是"两处口径逐字一致"。
#
# 运行：sh tools/run_rag_kb_name_tests.sh
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
    "$SRC/RagKbName.kt" "$SRC/RagIndexStore.kt" "tools/rag_kb_name/RagKbNameTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagKbNameTestKt"
