#!/bin/sh
# 宿主侧运行「模块 I 之 PR-2（I-2 / I-3 / I-4 / I-5）」的行为复刻测试。
#
# 为什么单靠源码守卫（run_ui_io_guard.sh）不够：守卫钉的是"结构没改回去"，
# 证明不了"这套结构在**并发**与 **null** 下真的对"。而四条里：
#   · I-4 的折叠与噪音计数是纯内存逻辑（不碰 android.*），可以在宿主上
#     用**真多线程**跑出实数 —— "省略 N 条"的数字失真就是它的全部价值所在；
#   · I-2 / I-3 / I-5 的对象带 Context / Activity，宿主下没有可用实现
#     （android.jar 里是抛 `Stub!` 的空壳），改用**同形判据复刻**。
#
# 每一组都同时跑**修复后**与**旧写法**两份，断言旧写法在同形输入下必须暴露问题
# —— 这就是"双向验证"，不是"写完就绿"。
#
# 依赖与 run_thinking_tests.sh 相同（缺失时 REQUIRED=0 退化为 SKIP，不静默跳过）。
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

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -x "$JAVA" ]    || missing "JDK：解压 OpenJDK*-jre_*_linux_hotspot_*.tar.gz 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"

JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT
CP="$OUT:$TC/kotlinc/lib/kotlin-stdlib.jar"

"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "tools/ui_io/UiIoTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.UiIoTestKt"
