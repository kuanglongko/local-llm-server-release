#!/bin/sh
# 宿主侧运行「暗色模式档位」的行为测试（跑 `ThemePref` 的**真实现**）。
#
# 为什么能直接跑真实现而不是复刻：`ThemePref` 里带 `Context` 的三个函数
# 各自只有一行（读写 prefs / 读 `Configuration`），档位判定全是纯函数
# —— 没有 android 依赖，宿主可直接编。这一点本身由接线守卫
# （`run_dark_mode_guard.sh`）钉住：它断言 `ThemePref.kt` 里
# **没有 `import android`**（除了 `systemDark` 那一处全限定引用）。
#
# 依赖与 run_pure_logic_tests.sh 相同（缺失时按 REQUIRED 决定退化或拦下）。
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

OUT=$(mktemp -d); LOG=$(mktemp)
trap 'rm -rf "$OUT"; rm -f "$LOG"' EXIT
CP="$OUT:$TC/kotlinc/lib/kotlin-stdlib.jar"

# ⚠ `ThemePref.kt` 与测试**一起**编：只编测试会在 `ThemePref` 未解析上红，
#   而那条红看起来像"测试写错了"。真实现进 classpath 之后，
#   编译失败就一定是真源码的问题。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "app/src/main/java/com/xiaowan/localinference/ThemePref.kt" \
    "tools/dark_mode/ThemePrefTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ThemePrefTestKt"
