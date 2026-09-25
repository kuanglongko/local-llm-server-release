#!/bin/sh
# 宿主侧运行「HTTP 监听生命周期」的离线**复刻**测试（模块 A 的 A-1 / A-2 / A-4）。
#
# 为什么是"复刻"而不是"直接跑 HttpApi"：`HttpApi.kt` 依赖 Android 栈
# （`android.util.Log`、`LlmEngine`、`ModelStore`…），宿主编不过；
# 而这三条的失效**只在多线程交错时出现**，稳态下 100% 通过 —— 读代码定不了，
# 必须有可运行程序去**主动构造交错**（"旧 listener 慢一拍还进 finally"）。
# 复刻与真实现的逐条对应关系写在 tools/lifecycle/HttpLifecycleTest.kt 的注释里。
#
# 依赖（缺失时按 REQUIRED 语义退化，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
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
SRC=app/src/main/java/com/xiaowan/localinference

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-*.zip 到 $TC"
[ -x "$JAVA" ]    || missing "JDK：解压 OpenJDK*.tar.gz 到 $TC"

JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

CP="$OUT:$TC/kotlinc/lib/kotlin-stdlib.jar"

# 复刻程序是**自足**的：不编译 HttpApi.kt（它依赖 Android 栈）。
"$KOTLINC" -nowarn -d "$OUT" "tools/lifecycle/HttpLifecycleTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
# 加一道"源码级对应"检查：复刻里必须真的存在这三条判据，否则"测试全绿"
# 只说明这份复刻自己能跑，不说明仓库里的实现有这三条（判据在守卫里，但这里
# 也顺手把"复刻与实现脱钩"这件事挡一下：实现里没有代际号就找不到锚点）。
for pat in 'listenerGen' 'desired' 'activeConns'; do
    if ! grep -q "$pat" "$SRC/HttpApi.kt"; then
        echo "FAIL  实现里找不到 $pat —— 复刻测试与实现已脱钩"
        exit 1
    fi
done
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.HttpLifecycleTestKt"
