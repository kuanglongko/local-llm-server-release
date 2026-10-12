#!/bin/sh
# 分块规则**差分测试**：C++ `chunk_text` vs Kotlin `LlmEngine.chunk`，
# 同一份语料、逐字节比输出（块数 / 硬截数 / 每块内容）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么这条测试不可省（前面那些守卫都替代不了它）
# ═══════════════════════════════════════════════════════════════════════════
# 纯逻辑单测（run_rag_index_tests.sh）只跑 C++ 那一侧；Kotlin 侧单测只编
# RagIndexStore.kt（`chunk` 在 LlmEngine 里，进不去）。而源码守卫只能判
# **结构**（"两边都有那张表"），判不了**行为**（"同一份输入切出同一份块"）。
#
# 两边行为分家的后果是静默的：建库按 A 规则切、追加/编辑按 B 规则切，
# 同一个索引里混着两套边界；而指纹里只有 t/o（块多大），看不见"从哪儿切"。
# 唯一能发现它的方式就是**真的两边各跑一遍、比输出**。
#
# 语料分三轮：
#   ① ASCII —— 逐字节比（单位相同，能发现块内容/边界的任何分家）；
#   ② CJK   —— 比每块的**字符数**（单位无关，专测 CJK 分隔符）。少了这一轮，
#              "Kotlin 漏掉「、」"这类差异完全不可见（本轮真踩到）。
#   ③ emoji —— 同上，另加"4 字节码点比 target 还长"那一档。
#
# 运行：sh tools/rag_index/chunk_rule/run_diff.sh
set -e
cd "$(dirname "$0")/../../.."     # 仓库根

TC=/workspace/.omnibot/toolchain
KOTLINC="${KOTLINC:-$TC/kotlinc/bin/kotlinc}"
JAR="${ANDROID_JAR:-$TC/android-35/android.jar}"
PY=${PYTHON:-python3}

missing() {
    if [ "${REQUIRED:-1}" = "0" ]; then
        echo "SKIP  缺少 $1（REQUIRED=0，CI 允许退化）"
        exit 0
    fi
    echo "缺少 $1"
    exit 2
}

ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
# ⚠ 表与切点选择是**共享原语**（在 TextBoundary，导入超长正文的切段也用同一份），
#   不在 LlmEngine 里 —— 差分测试要比"两条路切得一样"，所以两边都要喂进来。
TB=app/src/main/java/com/xiaowan/localinference/TextBoundary.kt
HDR=app/src/main/cpp/rag_index.h
EXTRACT=tools/rag_index/chunk_rule/extract_chunk.py

command -v "$PY" >/dev/null 2>&1 || missing "python3"
command -v g++ >/dev/null 2>&1 || missing "g++（宿主 C++ 编译器）"
[ -x "$KOTLINC" ] || missing "kotlinc（见 tools/run_rag_index_store_tests.sh 的获取方式）"

# JDK：kotlinc 是 shell 脚本，靠 PATH 找 java
if [ -z "${JAVA_HOME:-}" ]; then
    for j in "$TC"/jdk-*/bin/java /usr/lib/jvm/*/bin/java; do
        [ -x "$j" ] && JAVA_HOME=$(dirname "$(dirname "$j")") && break
    done
fi
[ -n "${JAVA_HOME:-}" ] || missing "JDK（JAVA_HOME 或 $TC/jdk-*/）"
JAVA="$JAVA_HOME/bin/java"
PATH="$JAVA_HOME/bin:$PATH"; export PATH JAVA_HOME

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

"$PY" "$EXTRACT" kt  "$ENGINE" "$TB" "$TMP/probe.kt" || exit 1
"$PY" "$EXTRACT" cpp "$HDR"    "$TMP/probe.cpp" || exit 1

g++ -std=c++17 -O1 -g -Wall -Wextra -I app/src/main/cpp "$TMP/probe.cpp" -o "$TMP/probe_cpp"
KOTLIN_STDLIB="$TC/kotlinc/lib/kotlin-stdlib.jar"
[ -f "$KOTLIN_STDLIB" ] || KOTLIN_STDLIB=$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar
"$KOTLINC" -nowarn -d "$TMP/ktout" "$TMP/probe.kt" > "$TMP/ktc.log" 2>&1 || {
    grep -E ": error:" "$TMP/ktc.log" || cat "$TMP/ktc.log"
    echo "FAIL  Kotlin 探针编译失败（抽出来的 chunk 变了吗？）"
    exit 1
}

# ---------- 一轮比对 ----------
# $1 = 语料名（给红时看）  $2 = gen_corpus 的第一个参数  $3 = CHUNK_MODE
#   ascii 模式：比**逐字节**（单位相同）——能发现"块内容/边界"的任何分家；
#   chars 模式：只比**每块的字符数**（单位无关）——CJK 上唯一能比的东西，
#               专测 ASCII 语料测不到的 CJK 分隔符（。！？；，、）。
# ⚠ 两种模式都要跑。少了 chars 那一轮，"Kotlin 漏掉「、」"这类差异
#   **在 ASCII 语料上完全不可见**（本轮真踩到：拿掉顿号后 ASCII 差分照样全绿）。
fail=0
run_round() {
    name=$1; gen=$2; mode=$3; extra=$4
    if [ -n "$extra" ]; then
        "$PY" tools/rag_index/chunk_rule/gen_corpus.py "$gen" 1200 "$extra" > "$TMP/corpus.txt"
    else
        "$PY" tools/rag_index/chunk_rule/gen_corpus.py "$gen" 1200 > "$TMP/corpus.txt"
    fi
    CHUNK_MODE="$mode" "$TMP/probe_cpp" < "$TMP/corpus.txt" > "$TMP/cpp.out"
    # ⚠ 三个 -D 都要：不指定 stdin/stdout 编码时，非 ASCII 语料会让 kotlin 的
    #   Console 抛 MalformedInputException —— 那是"跑不起来"，不是"判据红"，
    #   会被误读成 flaky。
    CHUNK_MODE="$mode" "$JAVA" -Dfile.encoding=UTF-8 -Dstdin.encoding=UTF-8 \
        -Dstdout.encoding=UTF-8 -cp "$TMP/ktout:$KOTLIN_STDLIB" probe.ProbeKt \
        < "$TMP/corpus.txt" > "$TMP/kt.out"
    n_cpp=$(wc -l < "$TMP/cpp.out")
    n_kt=$(wc -l < "$TMP/kt.out")
    if [ "$n_cpp" != "$n_kt" ]; then
        echo "FAIL  [$name] 两侧输出行数不同：C++=$n_cpp Kotlin=$n_kt（一侧提前退出了？）"
        fail=1; return
    fi
    if diff -u "$TMP/cpp.out" "$TMP/kt.out" > "$TMP/diff.txt"; then
        echo "PASS  [$name] C++ 与 Kotlin 在 $n_cpp 份语料上一致（mode=$mode）"
        return
    fi
    fail=1
    echo "FAIL  [$name] 分块规则差分不一致（mode=$mode，下面前 30 行）"
    echo "      ⚠ 指纹里只有 t/o，看不见这类分家 —— 建库与追加会混着两套边界。"
    head -30 "$TMP/diff.txt" | sed 's/^/      /'
    echo "      复现：$PY tools/rag_index/chunk_rule/gen_corpus.py $gen 1200 $extra"
}

run_round "ASCII 逐字节" ascii bytes ""
run_round "CJK 字符数"   cjk   chars ""
run_round "emoji 字符数" cjk   chars "emoji"

[ "$fail" = "0" ] || exit 1
echo "PASS  分块规则差分：两种模式全部一致"
exit 0
