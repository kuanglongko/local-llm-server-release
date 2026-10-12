#!/bin/sh
# 宿主侧运行会话**配额判据**的行为测试（`SessionQuota` 的准入与批量算术）。
#
# 为什么不能只靠 `run_session_quota_guard.sh`：守卫钉的是"结构没改回去"
# （闸门只挂在新增路径上 / 软提示线不参与拒绝 / 导入按预算从前往后收），
# 它证明不了"这套算术在**边界输入**下真的对"。而配额的本体就是算术：
#
#   · `已用 + 本次 == 上限` 必须**收下**（边界闭区间）—— 差一就拒会让用户
#     对着"刚好放得下"的文件反复重试同一个文件；
#   · 批量的能收**条数**要按从前往后算，且**遇到第一条收不下的就停** ——
#     跳过它继续试后面会让结果随文件顺序变，用户数不清到底进去几个。
#
# `SessionQuota` 是纯逻辑（无 android 依赖），所以直接用**真实现**断言。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
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

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -x "$JAVA" ]    || missing "JDK：解压 OpenJDK*-jre_*_linux_hotspot_*.tar.gz 到 $TC"

# ⚠ `JAVA_HOME` 与 `PATH` 必须在**调 kotlinc 之前**设好：
#   kotlinc 是个 shell 脚本，内部靠 `java` 起编译器（`$TC/kotlinc/bin/kotlinc`
#   第 98 行就是这一句）。本仓库的工具链是**解压出来的私有 JDK**，
#   系统 PATH 上未必有 java —— 于是 kotlinc 以 `java: command not found`
#   退出（`|| true` 把退出码吞了），`$OUT` 里一个 class 都没有，
#   下一步 java 报 `Could not find or load main class ...TestKt`。
#   症状看起来像"测试类名写错了"，实际是**编译器根本没跑起来**。
#   同批的 session_cap / session_migrate / chat_archive 都设了这三行，
#   本脚本漏了 —— 补齐，不另立一套写法。
JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME
PATH="$JAVA_HOME/bin:$PATH"
export PATH

SRC=app/src/main/java/com/xiaowan/localinference
OUT=$(mktemp -d)
LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT

"$KOTLINC" -nowarn -d "$OUT" \
    "$SRC/SessionQuota.kt" \
    "tools/session_quota/SessionQuotaTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$TC/kotlinc/lib/kotlin-stdlib.jar" \
    "com.xiaowan.localinference.SessionQuotaTestKt"
