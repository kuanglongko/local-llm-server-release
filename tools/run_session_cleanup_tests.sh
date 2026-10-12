#!/bin/sh
# 宿主侧运行会话列表**取数口径**的行为测试（`SessionCleanup` 的筛选与时间边界）。
#
# 为什么不能只靠 `run_session_cleanup_guard.sh`：守卫钉的是"结构没改回去"
# （筛选只在渲染层 / 删除取全库 / 默认不勾选 / 删完作废 KV 账本），
# 它证明不了"这几条口径在**边界输入**下真的对"。而这里的难点全在边界：
#
#   · 「多少天算旧」是一条**闭区间**线：`updatedAt` 恰好等于 `now - days`
#     的那一条**算旧**。差一就把它留下来了，而用户选的就是"7 天"这一档 ——
#     同一批会话在连续两次打开时表现不一致，他没法判断删没删干净。
#   · `updatedAt <= 0` 必须**算旧**，否则"清理 90 天"永远清不掉这类文件，
#     而用户看不到原因。
#   · 空关键字 = **不筛**（不是"一个都匹配不上"）。
#   · **一个都没勾就不许删** —— 这条是"不误删"的全部兜底，边界是空集合。
#
# `SessionCleanup` 与 `SessionQuota` 同族：纯逻辑（无 android 依赖），
# 所以直接用**真实现**断言，不像 `SessionCapTest` 那样复刻控制流。
# 它引用 `SessionStore.Info`，那一份跟着一起编；本测试**不调用**任何带
# `Context` 的方法，android.jar 里的 `Stub!` 空壳不会被触发。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /workspace/.omnibot/toolchain/android-35/android.jar（SessionStore.kt import 了
#     android.content.Context；宿主侧只有 Stub! 空壳，本测试不触发它）
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

# classpath 顺序：测试产物 -> android.jar（`org.json` 在这里是 `Stub!` 空壳，
# 但本测试不碰 SessionStore 的任何带 Context 的方法，所以不会被触发）。
#
# ⚠ `SessionQuota.kt` / `ChatArchive.kt` 必须与 `SessionStore.kt` **一起**编译：
#   S3 起 `SessionStore` 引用了 `SessionQuota.decide/MAX_TOTAL_CHARS`（配额闸门）
#   与 `ChatArchive.Session`（导入导出的数据形状）。只给那两个文件的话，
#   `unresolved reference: SessionQuota / ChatArchive` 会让整轮编译失败 ——
#   而本脚本把编译错误单独 grep 出来 `exit 1`，于是这条套件在**任何**机器上都是红的
#   （本测试只用到 `SessionStore.Info` 这个纯数据类，本可以只编 Info，
#   但 Kotlin 没有"只编一个声明"的选项，所以依赖要一起带上）。
#
# ⚠ `RoleCard.kt` 同批加上：`SessionStore.exportOne` 现在要过
#   `RoleCard.effectiveSystem`（导出写**生效的** system）。漏了它 =
#   `unresolved reference: RoleCard` = 整份套件在 kotlinc 阶段就死。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/SessionCleanup.kt" "$SRC/SessionStore.kt" \
    "$SRC/SessionQuota.kt" "$SRC/ChatArchive.kt" \
    "$SRC/TextBoundary.kt" "$SRC/RoleCard.kt" \
    "tools/session_cleanup/SessionCleanupTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar" \
    "com.xiaowan.localinference.SessionCleanupTestKt"
