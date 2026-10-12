#!/bin/sh
# 「嵌入合批：从头到尾是通的，且口径与逐条逐项相同」的源码级守卫（ISSUE #269 的另一半）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠某个单测）
# ═══════════════════════════════════════════════════════════════════════════
# PR #273 拿回来的是"线程数按本机核数"（多核那一份算力），它在自己的
# "放弃方案"里**明确没走**另一半：多条目 batch。那一半现在落地了 ——
# 隔离靠 `llama_batch` 的 `seq_id`（库按 seq 分块建注意力掩码），
# 每条的 `pos` 从 0 起（**不是**整批的全局下标）。
#
# 这一族错全是**静默的**：
#   · 只清 seq 0 的 KV（不遍历所有 seq）-> 第二排之后复用到上一批的 KV，
#     向量混味，接口 200；
#   · `pos` 用整批全局下标 -> RoPE 一转，向量变成"另一条文本在另一段的语义"，
#     而长度 / 维度 / 归一化**全对**；
#   · 两处批大小不等 -> 每批都在 native 被拒、回落逐条，合批白做，
#     而界面上完全看不出来（日志里只有一行 Unavailable）。
#
# **单测钉不住它们**：`embedBatch` 的单测喂什么批大小就跑什么批大小，
# "native 的 n_seq_max 到底多少"它看不见，"KV 清没清全"更看不见。
# 所以判据必须锚源码结构关系。
#
# 运行：sh tools/run_embed_batch_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/embed_text.h
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "三个源文件都在" "[ -f \$JNI ] && [ -f \$HDR ] && [ -f \$KT ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "嵌入合批从 native 计划到 Kotlin 建库全程接通、且失败口径只有一处" \
  "$PY tools/embed_batch/check_embed_batch_wiring.py \"\$JNI\" \"\$KT\" \"\$HDR\""

# ② 合批**不许**把池化口径改掉：`batchable_pooling` 必须只放行库不自己池化的档
#    （NONE / UNSPECIFIED），其余档回落逐条。锚的是"走 library_gives_seq"这个
#    结构关系，不是"函数里出现过哪个数"。
c "batchable_pooling 只放行库不池化的档（与 library_gives_seq 同源）" \
  "$PY tools/embed_batch/check_batchable_scope.py \"\$HDR\""

# ③ 上面那条判据的**结论**（"BGE / Qwen3-Embedding 这两档拿不到收益"）只许由
#    **一个**地方给出：`batchable_pooling`。所以这一条钉的是"档位清单没有被
#    抄第二份" —— `nativeEmbedBatch` 必须拿 `E.pool_type` 去问那个函数，
#    而不许自己比 `pooling == 2` / `== 3`。
#
#    为什么值得单列：抄成两份的症状是"走的路径"与"报的档位"不是同一件事
#    （向量按逐条算的，界面上却写着已合批；或者反过来，读错 buffer 的档被合批，
#    而**长度、维度全对**）—— 两者都不报错，只在换模型时出现，
#    看起来像"这个模型不行"。
c "合批的档位判据只有一处（native 拿 E.pool_type 问 batchable_pooling）" \
  "$PY tools/ingest/check_batchable_by_pooling.py \"\$JNI\" \"\$HDR\""

echo
echo "=== 嵌入合批守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
