#!/bin/sh
# 「合批时第 j 条的池化向量取自哪一行」这件事有判据、且只有一处（ISSUE #269 第 3 步）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条
# ═══════════════════════════════════════════════════════════════════════════
# 0.9.207 及以前，MEAN / CLS / LAST(3) 三档**不合批** —— 判据写的是
# "库自己池化的档读 `embd_seq`，那份 buffer 在多 seq 下的布局本仓库没验过"。
# 用户手上那两个模型（`bge-small-zh-v1.5-gguf` 是 CLS，`Qwen3-Embedding-0.6B`
# 是 LAST）因此**一条都合批不上**，只有"线程数"那一半收益。
#
# 0.9.208 把"没验过"补成"验过"：`embd_seq` 在多 seq 下就是**每个 seq 一行**，
# 第 j 条读 `llama_get_embeddings_seq(ctx, j)`（单条路径实参写死 0 是因为它
# 只有一个 seq，不是因为只有第 0 行有数据）。于是这条闸撤了。
#
# 撤闸之后**新增的唯一风险**就是"第 j 条读第几行"，而它的失效形态**全静默**：
#   · 取错行 -> 8 条不同文本得到同一个向量，"总是命中同几条"，长度维度全对；
#   · 越界取 -> `get_embeddings_seq` 对越界**没有契约**，可能返回别的行的指针
#     （比返回 null 更坏：那是别人的语义，而下游一切正常）；
#   · 走错 API -> 读 `embd.data` 那块从未写入的内存（池化结果在
#     `result_embd_pooled` 张量里），拿到全零 -> 逐条 ZERO_NORM，
#     看起来像"合批把好条目弄坏了"。
#
# 这三条都**不报错**，所以判据必须锚源码结构关系（谁调谁、谁在前）。
#
# 运行：sh tools/run_embed_seq_guard.sh
set -e
cd "$(dirname "$0")/.."
PY=${PYTHON:-python3}

JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/embed_text.h
KT=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
PREF=app/src/main/java/com/xiaowan/localinference/EmbedCtxPref.kt
HTTP=app/src/main/java/com/xiaowan/localinference/HttpApi.kt
HEALTH=app/src/main/java/com/xiaowan/localinference/HealthCheck.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "两个源文件都在" "[ -f \$JNI ] && [ -f \$HDR ]"

# ① 全链判据收在一个 checker 里（它们是同一个契约的几个面），失败时逐条点名。
c "合批取池化向量的行号有判据、越界先判、且与单条路径同一个分岔器" \
  "$PY tools/embed_seq/check_pooled_row_layout.py \"\$JNI\" \"\$HDR\""

# ①b ⚠⚠ 0.9.209 修的那一条**进程级 abort**：合批把 8 条塞进同一个 batch 时
#     整批 token 数会越过库的 `n_batch`（8 × 511 = 4088 > 2048），而
#     `llama_batch_allocr::init` 的那条断言在 `decode` 的**正常路径**上 ——
#     release 里断言失败 = `abort()`：用户看到的是**建索引闪退**，
#     日志最后一行只有知识库进度。这一族错只有真机能看到，所以判据锚源码结构。
c "合批前把整批 token 数拦在库的 n_batch 之内（拦在 llama_decode 之前）" \
  "$PY tools/embed_seq/check_batch_capacity.py \"\$JNI\" \"\$HDR\""

# ①c ⚠⚠ 0.9.211 补的**两道门的读数**：真机上"合批 8→4→2 都崩、只有 1 不崩"这件事，
#     要判它就得知道"这一批真实提交了多少 token"与"库的两扇门各是多少" ——
#     而这两处此前**一行都没打**。于是"撞的是 `:1711`（`n_batch`）还是 `:1426`
#     （`n_ubatch`）"只能靠倒推，而两个方向的倒推都说得通（PR #285 讨论里照此推错过两轮）。
#     这一条钉"读数必须真的在、且读的是**库的实数**（不是 cp 的值、不是推的上限）"。
c "两道门的实数在日志里读得到（加载期报库的 n_batch/n_ubatch，提交前报本段条数/token 数）" \
  "$PY tools/embed_seq/check_embed_gate_readings.py \"\$JNI\""

# ①d ⚠⚠ 0.9.212 修的第二条门：嵌入 ctx 的 `n_batch` / `n_ubatch` 此前**一个都没显式设**
#     （全是库默认：`n_batch=2048`，`n_ubatch=min(n_batch,512)`）。真机读数写死了后果：
#     **bge-large-zh-v1.5（BERT，非因果 → 库走 `llama_encode`）合批 8/4/2 全崩、只有 1 不崩** ——
#     因为 bge 只看 `n_ubatch` 那扇 512 的门（`:1426`），而批大小拧的是 `n_seq_max`（另一个旋钮）。
#     这一条钉三件：两道门**显式设且同源**（收口到 `kEmbedGate`、钳到 ctx）、读数是**库的实数**、
#     以及那一格**用户改得动**（EmbedCtxPref + 设置页 + doLoadEmbed 真的传下去）——
#     最后一件是本 issue 的原始形态（"线程数输入框对建库完全无效"）的正面判据。
c "嵌入 ctx 的两道门显式设、且那一格用户改得动（设置->加载->/health 全程接通）" \
  "$PY tools/embed_seq/check_embed_gate_settings.py \"\$JNI\" \"$KT\" \"$ACT\" \"$PREF\" \"$HTTP\" \"$HEALTH\""

# ② `pooled_vector_slot` 必须是**纯函数且不依赖 native**：它算出来的行号要能被
#    宿主侧单测逐项对照（`run_embed_text_tests.sh` 组 9 就是这么做的）。
#    锚"函数体里没有 I/O / 没有 llama 符号"，而不是"文件里出现过这个名字"。
c "pooled_vector_slot / pooled_slot_is_row 是纯函数（能被宿主单测直接跑）" \
  "$PY -c \"
import re, sys
s = open('$HDR', encoding='utf-8').read()
fails = []
for name in ('pooled_vector_slot', 'pooled_slot_is_row'):
    m = re.search(r'inline\s+\w+\s+' + name + r'\s*\([^)]*\)\s*\{(.*?)\n\}', s, re.S)
    if not m:
        fails.append(name + ' 的函数体找不到（判据锚点失效）')
        continue
    body = m.group(1)
    for bad in ('llama_', 'printf', 'fopen', 'std::vector<std::string>'):
        if bad in body:
            fails.append(name + ' 的函数体里出现 ' + bad + ' —— 它必须能被宿主单测直接编译（纯函数）')
for f in fails:
    print('FAIL  ' + f)
sys.exit(1 if fails else 0)
\"" 

# ③ 反面判据：合批里**不许**出现"库自己池化的档不合批"那个式子 ——
#    它不会红在任何结构判据上（仍然一份清单、仍然同源），代价却正是本轮
#    要消掉的那件事（BGE / Qwen3 一条都合批不上，而界面上只是"没变快"）。
c "没有退回『库自己池化的档不合批』（BGE / Qwen3 的收益不许凭空消失）" \
  "$PY -c \"
import re, sys
s = open('$HDR', encoding='utf-8').read()
m = re.search(r'inline bool batchable_pooling\(int poolTypeRaw\)\s*\{(.*?)\n\}', s, re.S)
if not m:
    print('FAIL  batchable_pooling 的函数体找不到（判据锚点失效）'); sys.exit(1)
if re.search(r'!\s*library_gives_seq', m.group(1)):
    print('FAIL  batchable_pooling 又退回了『库自己池化的档不合批』——'
          'BGE（CLS）/ Qwen3-Embedding（LAST）会一条都合批不上，而界面上只是「没变快」')
    sys.exit(1)
sys.exit(0)
\"" 

# ④ `read_from_seq` 仍必须走 `library_gives_seq`（三档的放行清单只此一份）——
#    撤的是"合批这一层的闸"，不是"读取侧的分岔"。
c "read_from_seq 仍与 library_gives_seq 同源（读取侧的分岔没被一起撤掉）" \
  "$PY -c \"
import re, sys
s = open('$HDR', encoding='utf-8').read()
m = re.search(r'inline bool read_from_seq\(int poolTypeRaw\)\s*\{(.*?)\n\}', s, re.S)
if not m:
    print('FAIL  read_from_seq 的函数体找不到（判据锚点失效）'); sys.exit(1)
if 'library_gives_seq' not in m.group(1):
    print('FAIL  read_from_seq 丢掉了 library_gives_seq —— 撤的该是「合批那一层的闸」，'
          '不是「读取侧的分岔」：后者决定读哪一路 buffer，撤掉它 MEAN/CLS/LAST 会去读'
          'embd.data（全零），而 NONE 会去读 embd_seq')
    sys.exit(1)
sys.exit(0)
\""

echo
echo "=== 嵌入多 seq 池化取值守卫：PASS $ok / FAIL $bad ==="
[ "$bad" = "0" ] || exit 1
