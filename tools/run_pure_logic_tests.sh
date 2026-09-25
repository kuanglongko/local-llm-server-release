#!/bin/sh
# 宿主侧运行「模块 C 纯逻辑四条修复」的行为复刻测试。
#
# 为什么单靠源码守卫（run_pure_logic_guard.sh）不够：守卫钉的是"结构没改回去"，
# 证明不了"这套结构在**边界输入**下真的对"。而 C-1 的对象 `SessionStore.capChars`
# 签名带 `android.content.Context`（只为打日志），宿主下没有可用实现
# （android.jar 里是抛 `Stub!` 的空壳），因此那一条只能"逐字复刻控制流"来证。
#
# 复刻与实现是否脱钩由守卫兜底（它同时钉住实现里的两级裁剪那两行）——
# 只复刻不断言实现，就会变成"测自己写的一份近似"。
#
# 依赖与 run_sampling_tests.sh 相同（缺失时打印获取方式后退出，不静默跳过）。
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：CI 是 AFK 编包场景，
# 缺工具链属环境问题；与之相对，**测试断言失败**必须拦住。缺省 REQUIRED=1。
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
    "tools/pure_logic/PureLogicTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.PureLogicTestKt"
