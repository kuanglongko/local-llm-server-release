#!/bin/sh
# 宿主侧运行「在途连接上限」的离线**复刻**测试。
#
# 为什么是复刻而不是直接跑 HttpApi：`HttpApi.kt` 依赖 Android 栈
# （android.util.Log / LlmEngine / ModelStore），宿主编不过；而本轮的失效
# （无上限 → 线程枯竭；计数泄漏 → 永久拒连）在稳态下完全看不出来 ——
# 一个客户端只开 1~2 条连接，与"有上限"表现一致。必须有可运行程序
# 去"主动打满"，才看得到区别。
#
# 复刻与真实现的逐条对应写在 tools/conncap/ConnCapTest.kt 的注释里；
# "复刻与实现是否脱钩"由 tools/run_http_lifecycle_guard.sh 的源码断言兜底。
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
"$KOTLINC" -nowarn -d "$OUT" "tools/conncap/ConnCapTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi

# 脱钩检查：复刻锚定的这几个东西必须真的在实现里 —— 否则"测试全绿"
# 只说明这份复刻自己能跑，不说明仓库里的实现有上限。
for pat in 'MAX_CONNS' 'admitConn' 'releaseConn' 'rejectOverLimit'; do
    if ! grep -q "$pat" "$SRC/HttpApi.kt"; then
        echo "FAIL  实现里找不到 $pat —— 复刻测试与实现已脱钩"
        exit 1
    fi
done

"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ConnCapTestKt"
