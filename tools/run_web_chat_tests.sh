#!/bin/sh
# 宿主侧运行「自带测试页（折叠设置区 + 多轮会话）」的离线单测（不依赖 NDK / 设备）。
#
# 为什么单独一份：页面是纯字符串产物，**完全不参与编译**。少一个 id、字段名写歪、
# 历史没带上，全都是"编译过、HTTP 200、只有打开浏览器的人看得出不对"。
# 而它们的失效形态又都长得像"服务端有问题"（字段漂移 = 参数不生效），
# 排查方向一开始就是错的 —— 只能靠断言钉住。
#
# 判据本体在 WebChatPage（纯函数），这里跑的是**同一份源码**（不是重抄）。
#
# 依赖与 run_cors_tests.sh 相同，缺失时 REQUIRED=0 退化为 SKIP。
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

CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# 页面本体 + 字段默认值的唯一来源（SamplingParams）。两者一起编，
# 少了哪一个都会在编译期就暴露（正是我们想要的：默认值不许各写一份）。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/WebChatPage.kt" "$SRC/SamplingParams.kt" \
    "tools/web_chat/WebChatPageTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.WebChatPageTestKt"
