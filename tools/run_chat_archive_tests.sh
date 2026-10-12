#!/bin/sh
# 宿主侧运行聊天记录导入导出的**行为测试**（`ChatArchive` 的往返与边界）。
#
# 为什么不能只靠 `run_chat_archive_guard.sh`：守卫钉的是"结构没改回去"
# （格式名判据在读 sessions 之前 / 导出不引用 capChars / 导入造新 id），
# 它证明不了"这套结构在**边界输入**下真的对"。而本特性的难点恰恰全在边界：
#
#   · 格式信封缺字段 / 版本过高 / sessions 不是数组 —— 三种都必须**拒绝**，
#     且理由要能区分（选错文件 vs 版本不支持）。"尽力解析"下选错一个 APK
#     会被读成"导入成功 0 个"，用户会以为自己的记录是空的。
#   · 脏 role 必须**跳过并计数**：既不整份拒绝（500 条里一条脏就全丢是更坏的
#     失败方向），也不静默带上（历史直接喂模型，别的 role 会被当指令执行，
#     症状只是"回答怪怪的"）。
#   · 往返一致 —— "备份"本身不可信的话，这个功能就没有意义。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar（org.json 真实现；android.jar 里的 org.json 是 Stub! 空壳）
#   /workspace/.omnibot/toolchain/android-35/android.jar
set -e
cd "$(dirname "$0")/.."

# REQUIRED=0 时缺工具链退化为 SKIP（exit 0 并打印原因）：CI 是「AFK 编包」场景，
# 缺工具链属环境问题、不是代码问题。与之相对，**测试断言失败**必须拦住。
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

# classpath 顺序：测试产物 -> json.jar（真实现）-> android.jar（org.json 是 Stub! 空壳）。
# SessionStore.kt 必须一起编：ChatArchive 的 Session 里带 `List<SessionStore.Msg>`，
# 不编译它就等于测试的是"另一份 Msg"。它虽然 import 了 android.content.Context，
# 但宿主侧只有 Stub! 空壳 —— 本测试**不调用**任何带 Context 的方法，
# 所以 Stub 不会被触发（与 run_protocol_fields_tests.sh 编 SamplingParams.kt 同理）。
CP="$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar"

# ⚠ `SessionQuota.kt` 必须一起编：S3 起 `SessionStore` 引用了
#   `SessionQuota.decide/MAX_TOTAL_CHARS`（三处，见配额闸门）。
#   漏了它，整轮编译以 `unresolved reference: SessionQuota` 结束 ——
#   而下面那个 `|| true` 把退出码吞掉，只有 `grep ': error:'` 拦得住。
#   本套件此前正是这么挂的（同一批 session_cleanup / session_quota 也各差一条依赖）。
#
# ⚠ `RoleCard.kt` 同样必须一起编：`SessionStore.exportOne` 现在要过
#   `RoleCard.effectiveSystem`（导出写**生效的** system，卡片内容不再在导出里消失）。
#   漏了它 = `unresolved reference: RoleCard` = 整份套件在 kotlinc 阶段就死，
#   而报出来像"测试没通过"，真相是一行断言都没跑（同上面那条教训）。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/ChatArchive.kt" "$SRC/SessionStore.kt" "$SRC/SessionQuota.kt" "$SRC/TextBoundary.kt" \
    "$SRC/RoleCard.kt" \
    "tools/chat_archive/ChatArchiveTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
"$JAVA" -Dfile.encoding=UTF-8 -cp "$CP" "com.xiaowan.localinference.ChatArchiveTestKt"
