#!/bin/sh
# 宿主侧运行「一屏最多画多少」（`RagUiCap.kt`）的离线单测。
#
# 为什么必须钉：这一层的失效形态全是**静默**的，而且方向恰好相反 ——
#   · 截了不说 -> 用户以为库就这么多；
#   · 没截也说 -> 警告变噪音，真被截的那次一起被无视。
# 两者都不抛异常、不崩溃，只有断言能钉住。
#
# 运行：sh tools/run_rag_ui_cap_tests.sh
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

# 只编 RagUiCap.kt + TextBoundary.kt + 它的测试：这两个对象刻意**零依赖**
# （连 org.json 都不用），所以这里不需要 android 之外的任何东西。
# ⚠ TextBoundary.kt 必须一起编：RagUiCap.clipDoc 转发的就是它 ——
#   漏一个源文件 = 整套在编译阶段就死，而报出来像"测试没通过"，真相是一行断言都没跑。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/RagUiCap.kt" "$SRC/TextBoundary.kt" "tools/rag_ui_cap/RagUiCapTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagUiCapTestKt"
