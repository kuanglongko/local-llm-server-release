#!/bin/sh
# 「双常驻（对话 + 嵌入）两份 Session 彼此隔离」的源码级接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 双常驻把进程里的模型从一份变成两份（native 侧 `static Session S` 对话、
# `static Session E` 嵌入）。它的失效模式**全部静默**：
#
#   ① 加载嵌入模型时顺手拆了对话模型
#      —— native 侧 `unloadModelInternal(E)` 写成 `(S)` 就成这样。
#      症状：在 App 里选一个嵌入模型 → 下次聊天变成"没加载模型"。
#      不崩、不报错，只有一次"模型莫名没了"。
#
#   ② 卸载对话模型时把嵌入模型也拆了
#      —— 每次换对话模型都白丢一份嵌入模型（以及它那份常驻 KV）。
#      症状：检索时好时坏（取决于刚才换过模型没），无任何日志。
#
#   ③ `embeddings=true` 没设上 / 没被强制
#      —— `llama_get_embeddings_ith` 取到 null 或未初始化 buffer，
#      nativeEmbed 返回 null，检索**全空**，接口 200。
#      症状：RAG 明明配好了，回答里永远不带知识库内容。
#
#   ④ 嵌入模型的 n_ctx 没被钳制上限
#      —— `embeddings=true` 的 output buffer 随 n_ctx 线性分配，
#      给大了直接加载失败（手机上见过 ctx create failed）。
#      症状：用户按对话模型的经验给 4096/8192，嵌入模型"加载失败"。
#
#   ⑤ 嵌入路径与生成路径共用一把锁
#      —— 双常驻的全部意义就是"检索不用等生成"。共用锁会让检索排在
#      一整轮生成后面，与单模型形态没有任何区别（白做）。
#
# 五条都**不崩、不报错、也未必有日志**，所以判据锚**结构关系**，
# 不锚"某个字符串出现过"（那是恒真的：写错的那一支也会有关键字）。
#
# 运行：sh tools/run_dual_model_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "llama_jni.cpp 存在" "[ -f \$JNI ]"
c "LlmEngine.kt 存在"   "[ -f \$KT ]"

# 去注释后的源码（判据一律锚实现，注释里提到关键词不算）。
# 与 run_static_order_guard.sh 同口径：先剥块注释与行注释。
# ⚠ 用独立脚本而不是 sh 里的 heredoc：`$(f)` 里嵌 heredoc 的写法在 dash 上
#   会把 `$1` 展开错、静默返回空串 —— 那样下面全部 `grep -q` 都会假红，
#   而"假红"比假绿好一点，却同样浪费时间。独立脚本没有这个问题。
JNI_CODE=$("$PY" tools/dual_model/strip_comments.py "$JNI")
KT_CODE=$("$PY" tools/dual_model/strip_comments.py "$KT")

# ── ① 两份 Session 都存在，且都带角色标记 ──────────────────────────────────
c "native 侧存在对话 Session（static Session S）" \
  "printf '%s' \"\$JNI_CODE\" | grep -q 'static Session S;'"
c "native 侧存在嵌入 Session（static Session E）" \
  "printf '%s' \"\$JNI_CODE\" | grep -q 'static Session E;'"
c "Session 结构体有 embedding 角色标记" \
  "printf '%s' \"\$JNI_CODE\" | grep -q 'bool  *embedding *=' "

# ── ② 加载嵌入模型的路径里**不得**出现拆 S 的调用 ─────────────────────────
# 真词法：抽出 nativeLoadEmbedModel 的函数体，只在这个范围内找 unload 调用。
# 用 grep 全文件会把 nativeLoadModel 里的 unloadModelInternal(S) 数进来（那是**应该**有的），
# 于是判据恒红；反过来只判"出现过 unloadModelInternal(E)"又恒真（写在别处也算）。
c "nativeLoadEmbedModel 体内只拆 E（不得拆 S）" \
  "$PY tools/dual_model/check_unload_target.py \$JNI --fn nativeLoadEmbedModel --want E --forbid S"

# ── ③ 卸载嵌入的路径只拆 E；卸载对话的路径只拆 S ───────────────────────────
c "nativeUnloadEmbedModel 只拆 E" \
  "$PY tools/dual_model/check_unload_target.py \$JNI --fn nativeUnloadEmbedModel --want E --forbid S"
c "nativeUnloadModel 只拆 S（不得连嵌入一起拆）" \
  "$PY tools/dual_model/check_unload_target.py \$JNI --fn nativeUnloadModel --want S --forbid E"

# ── ④ 嵌入加载必须调 llama_set_embeddings(ctx, true) ───────────────────────
c "嵌入加载路径调用 llama_set_embeddings(..., true)" \
  "$PY tools/dual_model/check_embed_flags.py \$JNI"

# ── ④b output 下标必须是 0（不是 n_tokens-1）─────────────────────────────
# 这是本特性里最易写错、又最哑的一处：`llama_get_embeddings_ith(ctx, i)` 的 i 是
# **output 数组下标**（只收 logits!=0 的 token），而实现只要了最后一个 token 的
# output —— 所以下标是 0。写成 n_tokens-1 时，超过 1 个 token 就取到无效下标，
# 返回 null → 检索全空，而接口 200。
c "nativeEmbed 读 output 的**排位**来自计划，且按语义（LAST/CLS）分岔" \
  "$PY tools/dual_model/check_embed_output_index.py \$JNI"

# ── ⑤ n_ctx 必须被钳到上限（且上限与默认值都是本仓库自己的常量）───────────
c "嵌入 n_ctx 有默认常量 kEmbedDefaultCtx" \
  "printf '%s' \"\$JNI_CODE\" | grep -q 'kEmbedDefaultCtx'"
c "嵌入 n_ctx 有上限常量 kEmbedMaxCtx" \
  "printf '%s' \"\$JNI_CODE\" | grep -q 'kEmbedMaxCtx'"
c "n_ctx 超过上限时被钳制（clamp），不是直接放行" \
  "$PY tools/dual_model/check_embed_ctx_clamp.py \$JNI"

# ── ⑥ Kotlin 侧：嵌入路径有自己的锁，不与 genLock 共用 ─────────────────────
c "LlmEngine 有独立的 embedLock（不与 genLock/loadLock 共用）" \
  "printf '%s' \"\$KT_CODE\" | grep -q 'val embedLock'"
c "loadEmbedModel 持 embedLock 而非 loadLock" \
  "$PY tools/dual_model/check_embed_lock.py \$KT"
c "embed() 也持 embedLock（同一份 native 状态，必须串行）" \
  "$PY tools/dual_model/check_embed_lock.py \$KT --fn embed"

# ── ⑦ Kotlin 声明与 native 导出逐项对齐（防 UnsatisfiedLinkError）──────────
c "Kotlin 声明了 nativeLoadEmbedModel"  "grep -q 'external fun nativeLoadEmbedModel' \$KT"
c "Kotlin 声明了 nativeUnloadEmbedModel" "grep -q 'external fun nativeUnloadEmbedModel' \$KT"
c "Kotlin 声明了 nativeHasEmbedModel"   "grep -q 'external fun nativeHasEmbedModel' \$KT"
c "Kotlin 声明了 nativeEmbedDim"        "grep -q 'external fun nativeEmbedDim' \$KT"
c "Kotlin 声明了 nativeEmbed("          "grep -q 'external fun nativeEmbed(' \$KT"

echo
if [ "$bad" = 0 ]; then
    echo "=== 双常驻隔离守卫 全部通过（$ok 条）==="
else
    echo "=== 双常驻隔离守卫 $bad 条失败（共 $((ok+bad)) 条）==="
    exit 1
fi
