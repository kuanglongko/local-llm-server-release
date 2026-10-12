#!/bin/sh
# 宿主侧运行 `RoleCard` 与 `RoleCardStore.parse/encode` 的**行为测试**。
#
# 为什么不能只靠 `run_role_card_guard.sh`：守卫钉的是"结构没改回去"
# （切卡作废 KV / 落盘 tmp+rename / 会话存快照），它证明不了"这几条口径在
# **边界输入**下真的对"。而这一族的难点全在边界，且**全都没有症状**：
#
#   · **快照优先**：卡片删掉之后会话还能拿出那段文本（只存 id 的版本恒为空，
#     而症状只是"回答怪怪的"，与模型抽风同形）；
#   · **match 比内容**：只比 id 会把"卡片改了文案"判成 OK，
#     而那正是让用户误以为"改了卡片所有会话都跟着变"的错法；
#   · **读盘尽力而为**：一张坏卡不该让整个库读不出来；但顶到上限要如实报；
#   · **上限是闭区间**：正好放得下必须收。
#
# 依赖（缺失时打印获取方式后退出，不静默跳过）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc
#   /workspace/.omnibot/toolchain/jdk-*/bin/java
#   /tmp/json.jar（org.json 真实现；android.jar 里的 org.json 是 Stub! 空壳）
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

# `RoleCardStore` import 了 android.content.Context / android.util.Log（只为 load/save），
# 但本测试**不调用**带 Context 的方法，所以 android.jar 里那些 Stub! 空壳不会被触发
# （与 run_chat_archive_tests.sh 编 SessionStore.kt 同理）。
"$KOTLINC" -nowarn -classpath "$JAR:$JSON" -d "$OUT" \
    "$SRC/RoleCard.kt" "$SRC/RoleCardStore.kt" \
    "tools/role_card/RoleCardTest.kt" "tools/role_card/RoleCardStoreTest.kt" > "$LOG" 2>&1 || true
if grep -qE ": error:" "$LOG"; then
    grep -E ": error:" "$LOG"
    exit 1
fi
# ⚠ classpath 顺序是**判据的一部分**，不是随手排的：
#   android.jar 里的 `org.json` 是**抛 Stub! 的空壳**，而它排在 json.jar 前面时
#   会**赢**——`run { JSONArray(text) }.getOrNull()` 于是把真实现挡在外面、静默
#   拿到一个"每次都是 null"的解析器（症状是"任何卡片库都读不出来"，
#   而真机上完全正常）。`run_role_card_tests.sh` 第一版正是这么排的，
#   当场把它自己的三条断言判成了红。json.jar **必须在前**（同 run_chat_archive_tests.sh）。
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar" \
    "com.xiaowan.localinference.RoleCardTestKt"
"$JAVA" -Dfile.encoding=UTF-8 -cp "$OUT:$JSON:$JAR:$TC/kotlinc/lib/kotlin-stdlib.jar" \
    "com.xiaowan.localinference.RoleCardStoreTestKt"
