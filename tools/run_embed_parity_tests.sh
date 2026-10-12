#!/bin/sh
# 宿主侧运行「向量逐位比对」（`EmbedParity.kt`）的离线单测（ISSUE #269 第 5 步）。
#
# 为什么必须钉：这一层的结论**直接决定**"改批大小要不要重建索引"，而它的
# 失效形态全是"结论变成一个像真的的东西"：
#   · 把"缺失"（一侧没向量）报成"相同" -> 据此沿用，索引里是两种语义；
#   · 把"浮点级差异（CLOSE）"报成"逐位相同" -> 同上，只是更隐蔽；
#   · `allIdentical` 用 `different == 0` 判 -> "有条目缺失"也会算成立；
#   · 长度不等按短的比 -> 整体错位被判成"前几位的差异"（方向错）。
# 都不抛异常、不崩溃，只有断言能钉住。
#
# 运行：sh tools/run_embed_parity_tests.sh
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

# 只编这几个：EmbedParity（被验对象）、EmbedParityProbe（样本集，纯常量）、
# EmbedLimits（批大小 / topK 这两个数的出处 —— 单测要读它们来判"样本条数与
# 批大小对齐""语料多于 topK 两倍"），以及测试本身。它们都**不依赖**任何
# Android 类型（不碰 Context、不碰文件、不碰 native），所以不需要 android
# 之外的任何东西。
#
# ⚠ 这几行曾经漏了 EmbedLimits（那时这两个数住在 LlmEngine 里），而单测里
#   写着 `LlmEngine.kEmbedBatchDefault` —— 于是这份套件**编不过**、一次都没跑过，
#   而它输出的是"SKIP（缺工具链）"。见 `tools/run_kt_test_runner_guard.py` 的文件头。
"$KOTLINC" -nowarn -classpath "$JAR" -d "$OUT" \
    "$SRC/EmbedParity.kt" "$SRC/EmbedParityProbe.kt" "$SRC/EmbedLimits.kt" \
    "tools/embed_parity/EmbedParityTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.EmbedParityTest"
