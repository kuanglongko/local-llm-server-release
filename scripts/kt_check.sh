#!/bin/sh
# 本地 Kotlin 类型检查
#
# 为什么需要：CI 一轮 5~8 分钟，而一个 `raf.seek(Int)` 式的类型错误
#（LogFileStore 里 seek 传了 Int）本该在提交前 30 秒被抓到——这类错误
# kt_balance.py 查不出来。AGP 编译 Kotlin 用的就是 SDK 的 android.jar，
# 所以拿同一份 android.jar + 同版本 kotlinc 在本地就能提前抓到。
#   2) kotlinc 可用（未装时自动下载，用 LOCAL_LLVM_HOME 指定已装位置）
# 依赖（一次性下载到持久目录，/workspace 是共享工作区，不会随环境重置丢失）：
#   /workspace/.omnibot/toolchain/kotlinc/bin/kotlinc      （Kotlin 2.0.21，与 build.gradle.kts 一致）
#   /workspace/.omnibot/toolchain/android-35/android.jar   （compileSdk 35）
# 缺失时打印获取方式后退出，不静默跳过（静默跳过会让人误以为"已自检通过"）。
#
# 注意 kotlinc CLI 与 Gradle 的报错前缀不同：CLI 是 `path:line:col: error: ...`，
# Gradle 才是 `e: file:///...`。因此这里按 `: error:` 判定，不按 `^e: `（后者只在 Gradle 侧出现）。
TC=/workspace/.omnibot/toolchain
KOTLINC="$TC/kotlinc/bin/kotlinc"
JAR="$TC/android-35/android.jar"

[ -x "$KOTLINC" ] || { echo "缺少 kotlinc：下载 kotlin-compiler-2.0.21.zip 解压到 $TC"; exit 2; }
[ -f "$JAR" ] || { echo "缺少 android.jar：下载 https://dl.google.com/android/repository/platform-35_r02.zip，取其中 android-35/android.jar 放到 $TC/android-35/"; exit 2; }

SRC=$(find app/src/main/java -name '*.kt')
[ -n "$SRC" ] || { echo "没找到 .kt 源文件（请在仓库根目录运行）"; exit 2; }

OUT=/tmp/kt_check.log
# -nowarn：只看错误。android.jar 是 stub，会有大量与真实 AGP 编译无关的告警，
# 留着只会淹没真错。
$KOTLINC -nowarn -classpath "$JAR" -d /tmp/kt_check_out $SRC > "$OUT" 2>&1
grep -E ": error:|^e: " "$OUT" | sed 's|/workspace/local-llm-server/||' | head -40
rm -rf /tmp/kt_check_out
if grep -qE ": error:|^e: " "$OUT"; then
    echo "=== kt_check 失败：存在编译错误 ==="
    exit 1
fi
echo "=== kt_check 通过（$(echo "$SRC" | wc -l) 个 .kt，无 error）==="
exit 0
