#!/bin/sh
# 宿主侧运行「RAG 注入纯逻辑」（`RagInject.kt`）的离线单测。
#
# 为什么必须钉：注入这一层的失效形态**全是静默的** ——
#   · 开关关着仍注入 -> 用户没要求却被污染上下文，接口 200；
#   · 低分条目硬塞 -> 用无关段落污染回答，比"没命中"更坏；
#   · 空条目却渲染出 `[知识库]` 表头 -> "非空即已注入"的判据恒真；
#   · 全部低于阈值时退回最高分几条 -> 「没有命中」被伪装成「命中了」。
# 都不抛异常、不崩溃、不报错，只有断言能钉住。
#
# 运行：sh tools/run_rag_inject_tests.sh
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

# kotlinc 是 shell 脚本，内部靠 PATH 找 java。
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
    "$SRC/RagInject.kt" "tools/rag_inject/RagInjectTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagInjectTestKt"
