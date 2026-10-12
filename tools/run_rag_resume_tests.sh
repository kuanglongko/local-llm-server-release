#!/bin/sh
# 宿主侧运行「知识库续编：那一句状态」(`RagResumeUi.kt`) 的离线单测。
#
# 为什么必须钉：这是用户判断「点建索引这一下要等多久」的**唯一**依据，
# 而它这一轮第一次有了两种答案（可续 = 只补缺口 / 要重建 = 整份重来，
# 两者差几十分钟）。失效形态全是"状态看起来对、其实指错方向"：
#   · 「可续」与「待重建」塌成一句 -> 用户分不清"补 20 块"与"重跑两小时"；
#   · 「可续」与「正文已改」写反   -> 半份索引被报成"你改过正文"（本仓库栽过这一族）；
#   · 「未落盘」被画成「待重建」   -> 在 0 条的库上反复点重建（ISSUE #259 ③）；
#   · 报"预计还要多久"             -> 块耗时差一个数量级，推出来的剩余**假精确**。
#
# 运行：sh tools/run_rag_resume_tests.sh
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

[ -x "$KOTLINC" ] || missing "kotlinc：解压 kotlin-compiler-2.0.21.zip 到 $TC"
[ -f "$JAR" ]     || missing "android.jar：解压 platform-35_r02.zip，取 android-35/android.jar 放到 $TC/android-35/"

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

CP="$OUT:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# 只编 RagResumeUi.kt + 它的测试：本对象刻意**不依赖**任何东西
#（不 import android、不 import 本仓库其它文件），所以只靠 kotlin 标准库。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/RagResumeUi.kt" "tools/rag_resume/RagResumeUiTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.RagResumeUiTestKt"
