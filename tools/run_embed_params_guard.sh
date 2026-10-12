#!/bin/sh
# 「`/v1/embeddings` 的三个协议参数真的接上了」的**源码级接线**守卫（0.9.170）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 这轮做的是三件"看起来做完了、其实可以静默错"的事：
#
#   ① `dimensions` 明确 400 —— 静默忽略不是"没生效"，是调用方按错维度去用：
#      set dimensions=512、拿到 200、拿到 1024 维向量，然后截断 —— 静默错位。
#   ② `encoding_format=base64` —— 字节序写反时 base64 解码照样成功、长度照样对，
#      只是数值离谱；写成"逐维各编一段"时长度是 4N 段，调用方按整段解当场报废。
#      这两种都没有任何一处会报错。
#   ③ token id 数组 —— 若哪天被"顺手优化"成把 id 当字符串拼、或自己重新 tokenize
#      一遍，向量就与调用方以为的文本无关了，而接口 200。
#
# 单测（EmbeddingsApiTest）钉的是"算得对不对"，钉不住"接线有没有接上"：
# 把 handleEmbeddings 里那句 detokenize 删掉，单测照样全绿（它不走 HTTP 层）。
# 所以这一条必须锚**源码结构关系**，纯静态，不依赖工具链。
#
# 运行：sh tools/run_embed_params_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

EA=app/src/main/java/com/xiaowan/localinference/EmbeddingsApi.kt
API=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
JNI=app/src/main/cpp/llama_jni.cpp

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "三个源文件都在" \
  "[ -f \$EA ] && [ -f \$API ] && [ -f \$JNI ] && [ -f \$ENG ]"
c "存在性兜底：dimensions 的拒绝文案在 EmbeddingsApi 里" \
  "$PY -c \"import sys; sys.exit(0 if 'dimensions 不支持' in open('\$EA').read() else 1)\""
c "存在性兜底：Encoding.BASE64 在 EmbeddingsApi 里出现" \
  "$PY -c \"import sys; t=open('\$EA').read(); sys.exit(0 if 'BASE64' in t else 1)\""
c "detokenize 声明与 native 导出成对存在" \
  "grep -q 'external fun nativeDetokenize' \$ENG && grep -q 'nativeDetokenize' \$JNI"
c "handleEmbeddings 真的调了 LlmEngine.detokenize" \
  "grep -q 'LlmEngine.detokenize(' \$API"

# 上面几条是"存在性"兜底；真正的判据在下面这个 checker 里（锚函数体、锚字节序、
# 锚调用实参）。分成两层是为了让"文件没了/函数改名了"也能点得出是哪一条。
echo
# checker 的每条判据自己打印 PASS/FAIL，这里把它的条数并进总数，
# 让最后那行汇总自报**整份守卫**的条数（Kotlin 宿主单测那一族是逐条打印、
# 不汇总；本守卫不学那一族 —— 文档里的「N 条」得有尺子）。
CHK=$("$PY" tools/embed_params/check_embed_params.py "$EA" "$API" "$JNI") || true
printf '%s\n' "$CHK"
nchk=$(printf '%s\n' "$CHK" | grep -c '^PASS' || true)
badchk=$(printf '%s\n' "$CHK" | grep -c '^FAIL' || true)
ok=$((ok + nchk)); bad=$((bad + badchk))

echo
if [ "$bad" = 0 ]; then
    echo "=== 嵌入协议参数守卫 全部通过（$ok 条）==="
else
    echo "=== 嵌入协议参数守卫 $bad 条失败（共 $((ok+bad)) 条）==="
    exit 1
fi
