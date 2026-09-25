#!/bin/sh
# 「CORS + OPTIONS 204 + GET /」的**响应形状**校验。
#
# 与 run_cors_tests.sh 的分工：那一份验判据（CorsPolicy 纯函数），
# 这一份验**拼出来的响应头长什么样** —— 例如"预检的 CORS 头有没有落在头块内"、
# "空白名单下预检是不是还是 204"、"Content-Length 是字节数还是字符数"
#（后者因为页子里有中文，写错不会报错、只会让页面被截断）。
#
# 依赖同 run_cors_tests.sh。缺工具链时 REQUIRED=0 退化为 SKIP。
set -e
cd "$(dirname "$0")/.."
missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"; exit 0; fi
    echo "缺少 $1"; exit 2
}
TC=/workspace/.omnibot/toolchain
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAVA=$(ls -d "$TC"/jdk-*/bin/java 2>/dev/null | head -1)
JAR="$TC/android-35/android.jar"
[ -x "$KOTLINC" ] || missing "kotlinc"
[ -x "$JAVA" ]    || missing "JDK"
[ -f "$JAR" ]     || missing "android.jar"
# 运行期 classpath 里 json.jar 必须排在 android.jar **之前**：
# android.jar 里的 org.json 是抛 `Stub!` 的空壳，而自带测试页的字段表会真的构造
# JSONArray（WebChatPage.fieldsJson）。顺序反了不会"编译失败"，而是在跑到页面那几条
# 断言时抛 `RuntimeException: Stub!` —— 与"页面有 bug"完全同形。
JSON=/tmp/json.jar
[ -f "$JSON" ]    || missing "/tmp/json.jar：curl -o /tmp/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
JAVA_HOME=$(dirname "$(dirname "$JAVA")"); export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"; export PATH
SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d); LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT
# 编译清单必须与 run_cors_tests.sh 对齐，少一个就直接编不过：
#   · WebChatPage.kt —— 页面本体已从 CorsPolicy 搬到独立文件，CorsPolicy 里
#     只剩 `WebChatPage.PAGE_TITLE` 与 `WebChatPage.html(...)` 两处转发，
#     不带它就是「unresolved reference 'WebChatPage'」；
#   · SamplingParams.kt —— WebChatPage 的字段表默认值引用它的 DEF_*（不各写一份字面量）。
# 这两条此前一直缺，于是本脚本在 main 上**根本编不过**（见下）。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/CorsPolicy.kt" "$SRC/WebChatPage.kt" "$SRC/SamplingParams.kt" \
    "tools/cors/live_check.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then grep -E ": error:" "$LOG"; exit 1; fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar" \
    "com.xiaowan.localinference.Live_checkKt"
