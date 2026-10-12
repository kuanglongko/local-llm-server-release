#!/bin/sh
# 「知识库索引：判据两边一致 + 接线不漏」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 本轮的索引分成了**三处**实现，两两之间必须严丝合缝：
#
#   · `app/src/main/cpp/rag_index.h`   —— 纯逻辑（分块/指纹/头布局），宿主单测钉住；
#   · `app/src/main/java/.../RagIndexStore.kt` —— 落盘/读盘/检索（Kotlin）；
#   · `app/src/main/java/.../LlmEngine.kt`     —— 组装（建库/检索入口）。
#
# 单测验得了"每一处自己算得对"，**验不了三处之间对齐**。而对齐一旦破，
# 症状全是静默的：
#
#   ① 头字段偏移两边不一致（C++ 写 4/8/12，Kotlin 读成别的）
#      -> 把指纹当向量读，维度"对得上"，检索永远无意义，接口 200。
#      这一条**唯一**能验的方式就是拿两边各自的实现跑同一份输入比字节 ——
#      本守卫调 tools/rag_index/cross_layout.py 干这件事。
#
#   ② 指纹字段集合两边不一致（一边算 mtime 一边不算）
#      -> "指纹永远不匹配 -> 每次打开都在重建"，只是慢，所以没人会去查。
#      与 ① 同源，同一个交叉脚本一并比。
#
#   ③ 建库路径没检查 `hasEmbedModel`
#      -> 没加载嵌入模型时 embed() 逐条返回 null，于是整库向量全空，
#         而"建库成功"照样返回。表现为"检索永远为空"，看不出是模型没加载。
#
#   ④ 检索路径没做指纹比对（直接 decode 就返回）
#      -> 换 embed 模型后旧索引被继续使用，余弦量纲对不上，排序全乱。
#
#   ⑤ 落盘不是原子的（直接 writeBytes 而不是 tmp + renameTo）
#      -> 写到一半被杀留下半截文件（Android 杀后台很常见），下次加载
#         靠"幸好 decode 校验了"兜住；写的那一侧没有不变式。
#
#   ⑥ 库名没净化就拼路径
#      -> `../` 会把索引落到库目录之外（任意路径写入），而它看起来
#         "只是索引存错地方了"。
#
#   ⑦ 检索与建库用的切块参数**各写各的默认值**
#      -> 拿 A 参数建、拿 B 参数检索 -> 指纹永远不匹配 -> 检索恒为空
#         （"配了知识库但回答里永远不带内容"），且没有任何日志指出参数不一致。
#
# 判据一律锚**结构关系**（"这个函数里有没有那一跳"、"两边的字段集合是否相等"），
# 不锚"文件里出现过某个字符串"（那是恒真的：写错的那一支也会有关键词）。
#
# 运行：sh tools/run_rag_index_guard.sh
set -e
cd "$(dirname "$0")/.."
JNI=app/src/main/cpp/llama_jni.cpp
HDR=app/src/main/cpp/rag_index.h
STORE=app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt
ENGINE=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ARCHIVE=app/src/main/java/com/xiaowan/localinference/RagArchive.kt
TB=app/src/main/java/com/xiaowan/localinference/TextBoundary.kt
STRIP=tools/rag_index/strip_comments.py
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "rag_index.h 存在"        "[ -f \$HDR ]"
c "RagIndexStore.kt 存在"   "[ -f \$STORE ]"
c "LlmEngine.kt 存在"       "[ -f \$ENGINE ]"

# 判据一律锚实现，注释里提到关键词不算（与 dual_model / kv_cache 守卫同口径）。
STORE_CODE=$("$PY" "$STRIP" "$STORE")
ENGINE_CODE=$("$PY" "$STRIP" "$ENGINE")
HDR_CODE=$("$PY" "$STRIP" "$HDR")

# ── ① 头布局与指纹字段：两边跑同一份输入，比字节（这是本守卫的核心）──────
# 用 python 直接实现两边各自的算法（从源码里提取常量），而不是 import 任何东西 ——
# "从源码提取"这一步本身就是判据：常量改变了而提取不到 = 守卫变红。
c "头布局交叉校验（C++ vs Kotlin，比字节）" \
  "$PY tools/rag_index/cross_layout.py"

# ── ② 指纹字段集合：两边必须算同一组字段 ──────────────────────────────────
# 判据：rag_index.h 的 fingerprint_of 与 RagIndexStore 的 fingerprintOf
# 必须出现**同一组**字段前缀（v/p/b/m/d/t/o/x）。少一个 = 有一边漏算一个维度，
# 而"漏算"的方向恰好是"更宽松"（旧索引被当新的用）。x（正文 hash）尤其如此：
# 少算它 = 改了正文而检索仍用旧向量命中、注入新正文（排序像随机，接口 200）。
c "指纹字段集合两边一致（v/p/b/m/d/t/o/x）" \
  "$PY tools/rag_index/check_fp_fields.py \"\$HDR\" \"\$STORE\""

# ── ②b 分块规则：表 / 降级次序 / 硬截 / 规则版本进指纹 ────────────────────
# ⚠ 这一族判据守的是**本轮新加的一条轴**：分块的**规则**（不是"块多大"）。
#    指纹里 `t`/`o` 只描述块多大，描述不了"从哪儿切" —— 规则两边分家、
#    或规则改了而版本没动，全都是静默的：
#      · 两边表不同 -> 建库/追加两条路切出的边界不同，旧向量命中、新正文注入；
#      · 规则改了却没进指纹 -> 旧边界算的向量被当成"仍然可用"。
#    判据锚**结构**（两张表逐条相等、规则版本真的落进 `k` 那一格），
#    由 tools/rag_index/check_chunk_rules.py 一条条比。
c "分块规则两边逐条一致（表 / 次序 / 硬截 / 规则版本进指纹）" \
  "$PY tools/rag_index/check_chunk_rules.py \"\$HDR\" \"\$ENGINE\" \"\$STORE\" \"\$TB\""

# ── ②c 截断（撞 maxChunks）必须两边同判、且**必须报出来** ────────────────
# 这一族守的是本轮新加的那条轴：`maxChunks` 撞上限 = **文档后半段没进索引**。
# 它此前完全没有痕迹 —— `chunk()` 撞上限就 return，`buildIndex` 只报块数，
# 于是"这份只索引了前半段"与"文档本就这么多"在日志与界面上**逐字相同**
# （唯一可见现象是"后半段搜不到"，而它与"文档里没有"一模一样）。
#
# 判据锚**结构**：
#   · 两边都有 truncated 出参（提取得到那个形参名）；
#   · 两边的返回处都把它置成"还没切完"（不是写死 false / true）；
#   · 两边的 chunk 循环条件都真的含 maxChunks；
#   · 出参**真的被调用方接住并写进日志**（纯逻辑全绿、界面上什么都没有，
#     正是这一族缺陷的形状 —— 单测看不出来）；
#   · maxChunks 真的进了指纹（`c` 那一格）。
c "C++ chunk_text 有 truncated 出参且真的被判据赋值" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q '\*truncated = !brokeByEnd'"
c "Kotlin chunk 有同一判断（truncated 取自『正常收尾』标志，不是写死）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'truncated\[0\] = !brokeByEnd'"
# 两边都不许把 truncated 判成"块数 == maxChunks"，也不许写成 `pos < len`：
# 那一档在"正好切满且到文末"时是**完整**的，报成截断会让用户白建一遍，
# 而 `pos < len` 更坏 —— 每份正常文档都会被报成截断（那条日志从此没人看）。
# ⚠ 判据必须锚**赋值那一行本身**，不能只 grep 'pos < len' ——
#   `while (pos < len && ...)` 的循环条件里就有这两个词，于是"写错"的桩
#   在"全文出现过 pos < len"下照样绿（本桩第一版就是这么假绿的，自测抓到了）。
c "C++ 的『正常收尾』是显式标志（不靠 pos 猜：末块开头也 < len）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'brokeByEnd = true'"
c "Kotlin 的『正常收尾』也是显式标志（两边同一条控制流）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'brokeByEnd = true'"
# 循环条件里的 maxChunks：两边都要有（否则"上限"根本没生效，判据全成摆设）。
c "C++ chunk_text 的循环条件真的含 maxChunks（上限生效）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'out.size() < maxChunks'"
c "Kotlin chunk 的循环条件真的含 maxChunks" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'out.size < cp.maxChunks'"
# 调用方接住 + 写进日志。⚠ 锚**函数体**（不是全文）：全文出现过恒真
#   （注释、别处的引用都算），而"只在注释里被提到"恰恰意味着没接上。
# ⚠ 判据锚**函数体**（不是全文）：全文出现过恒真（注释、别处的引用都算），
#   而"只在注释里被提到"恰恰意味着没接上。
# ⚠⚠ 又要锚**字面量**（日志原文就是判据），而 `_fnbody.py` 会抹掉字符串 ——
#    所以这里分两步：先证明"函数体里有那一跳"，再在**完整源码**里查那句话。
#    只做后者会被"别处的同名日志"骗（本仓库踩过：两条断言全文件 grep，
#    于是桩打在 A 处、判据读的是 B 处，假绿）。
c "buildIndex 函数体里有『被截断才报』这一支（不是只算不报）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'if (truncatedDocs > 0)'"
c "buildIndex 的截断计数**按条**（用户能动手的对象是『哪一条太大』，不是块数）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'truncatedDocs++'"
# 文案两处都锚：一句说"多少条"，一句说"未进索引"（说成"慢一点"就把一件事讲成了另一件）。
c "截断日志带条数（`N 条被截断`）" \
  "grep -q '条被截断：单条上限' \$ENGINE"
c "截断日志明说『超出的部分未进索引』（不是『慢一点』）" \
  "grep -q '未进索引' \$ENGINE"
# 硬截与截断必须**分开报**（根因不同、处置不同）：两句日志都在 buildIndex 里。
c "硬截仍单独一句（与截断不合并）" \
  "grep -q '块是硬截的' \$ENGINE"
# maxChunks 必须进指纹（`c` 那一格）。why：4096 -> 16384 时 t/o/k 一个都不变，
# 不进指纹 = 按 4096 截断出来的**半份**索引被当成"仍然可用"。
# ⚠ `append("c")` 里的 "c" 是**字面量**，而 `_fnbody.py` 会抹掉字符串 ——
#    所以这一条查的是**完整源码里那个函数体**（用 strip_comments 的结果）。
#    锚"值取自 cp.maxChunks"而不是"出现过 "c""：后者在"写了 c 但值写死"
#    的桩上照样绿，而那种写法下 4096 -> 16384 依然不进指纹（正是要防的那件事）。
c "Kotlin 指纹里把 maxChunks 写进 \`c\` 那一格（值取自 cp）" \
  "printf '%s' \"\$STORE_CODE\" | sed -n '/fun fingerprintOf/,/^    }/p' | grep -q 'append(\"c\").append(fp.cp.maxChunks)'"
c "C++ 指纹里把 maxChunks 写进 \`c\` 那一格（值取自 fp）" \
  "printf '%s' \"\$HDR_CODE\" | sed -n '/fingerprint_of/,/^}/p' | grep -q 's += \"c\";.*fp.maxChunks'"
# 从指纹回填时必须**一起搬** maxChunks：不搬的话追加/删除一条会把索引弄失效
# （按默认值重算指纹 -> 与索引里那份不相等），且日志只会说"参数变了"。
c "从指纹回填时 maxChunks 一起搬（\`c\` 那一格）" \
  "grep -q 'maxChunks = f(\"c\")' \$STORE"
# `c` 必须是**必比字段**（不是可选）：它缺了只能重建，不是"不知道就不比"。
c "\`c\` 在必比字段表里（缺失即判『旧版本索引，只能重建』）" \
  "printf '%s' \"\$STORE_CODE\" | grep -q '\"c\" to \"单条块数上限\"'"

# 「窗口内找不到就硬截」这条铁律：两边的切点选择函数里**必须**有
# 「对齐后 <= pos 就当没找到」那道闸。没有它 = 又回到老路径（会整块丢字）。
c "C++ 切点选择有「对齐后 <= pos 即视为没找到」这道闸" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py pick_chunk_cut | grep -q 'cut <= pos'"
# ⚠ Kotlin 侧多一跳：`pickChunkCut` 是**转发**（实体在 TextBoundary.pickCut，
#   与导入超长正文的切段共用同一张表 + 同一条控制流）。所以查的是**实体**那一处
#   —— 只查转发的话，转发到一个没有闸的实现上会**恒绿**；而转发本身也要在
#   （它证明建库这一路确实走的是共享原语，不是又长出一套本地实现）。
c "Kotlin 切点选择有同一道闸（两边同一条铁律）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py pickChunkCut | grep -q 'TextBoundary.pickCut' && \
   cat \$TB | $PY tools/rag_index/_fnbody.py pickCut | grep -q 'cut <= pos'"
# 硬截**必须**按码点对齐（否则那一块 encode 必然失败、整库标"需重建"）。
# ⚠ 判据不能只 grep `align_utf8_back` / `alignUtf8` —— 那个函数在 **overlap
#   那一行**也会出现（`next = align_utf8_back(text, next)`），于是桩㉛
#   （把硬截那一行的对齐拿掉）照样绿。这是我第一版判据的假绿，被自测抓到了。
#   锚点必须是**硬截那一行本身**：`hard = align_*... (text, end)`。
c "C++ 硬截那一行走 align_utf8_back(text, end)（切点落在码点边界上）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'hard = align_utf8_back(text, end)'"
c "Kotlin 硬截那一行走 alignUtf8(text, end)（同一处理）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'hard = alignUtf8(text, end)'"
# target 小于一个码点时只能**向后**推进到下一个码点起点，绝不 `pos + 1`
# （`pos + 1` 会切在码点中间 —— 老实现的那条兜底就是这么错的）。
c "C++ 有 next_utf8_start（target 小于一个码点时的安全推进）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'next_utf8_start'"
c "Kotlin 有 nextUtf8Start（同一档的同一处理）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'nextUtf8Start'"
# 「硬截」必须能被**数出来**：它要进日志（用户拍板），而"有没有硬截"决定了
# 一份文档的召回率为什么低。两边都要有这个出参。
c "C++ chunk_text 报硬截次数（hardCuts 出参）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'hardCuts'"
c "Kotlin chunk 报硬截次数（hardCuts 出参）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'hardCuts'"
# 硬截必须**写进日志**（用户拍板：记进日志）。判据锚 buildIndex 体内那一句，
# 而不是"文件里出现过硬截二字"（后者恒真：注释、参数名都算）。
# ⚠ 判据锚**结构**，不锚日志文案：`_fnbody.py` 会把字符串字面量抹掉，于是
#   grep `'硬截'` 是**恒假**（本轮真踩到，一版就红）。锚"有 `hardCuts > 0` 这道
#   条件，且它里面确实打了一条日志"——文案改了不会误红，而"忘了打日志"必红。
c "buildIndex 有「有硬截才打日志」这道条件" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'hardCuts > 0'"
c "buildIndex 在硬截那一支里真的调了 uiLog（不是只算了个数）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -A4 'hardCuts > 0' | grep -q 'uiLog'"
c "buildIndex 把每个文档的硬截数累进总数（不是只看最后一份）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'hardCuts += hc'"

# ── 硬截轮**不重叠**（软切轮保留重叠）──────────────────────────────────────
# 硬截 = 窗口内一个分隔符都没有。回退 `overlap` 得到的下一块，其窗口整段仍在
# 上一轮已消化过的区间里 —— 那一轮同样找不到分隔符、同样硬截。于是那段正文被切
# **两遍**（相邻块最长公共片段 ≥ overlap，检索时互相抢 top-k），硬截计数被灌水。
# 判据锚**结构**：两边都要有"这一轮是不是软切"这个标志，且它真的参与"下一块起点"
# 那个决策。只判"文件里出现了 cutByRule"是恒真（赋值也算）——锚它**进了那个三元/分支**。
#   · 行为判据在 `tools/rag_index/chunk_rule/run_space_cases.sh`（真源码抽出编译后跑）；
#   · 这里只保证"两边的控制流没有一边被改回去"（C++/Kotlin 各自都留着这道闸）。
c "C++ chunk_text 有「本轮是不是软切」的标志（cutByRule）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -q 'cutByRule'"
c "C++ 「下一块起点」按 cutByRule 分家（硬截不重叠 / 软切才重叠）" \
  "printf '%s' \"\$HDR_CODE\" | $PY tools/rag_index/_fnbody.py chunk_text | grep -A6 'size_t next' | grep -q 'cutByRule'"
c "Kotlin chunk 有「本轮是不是软切」的标志（cutByRule）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -q 'cutByRule'"
c "Kotlin 「下一块起点」按 cutByRule 分家（与 C++ 同一条控制流）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py chunk | grep -A6 'var next' | grep -q 'cutByRule'"

# ── ③ 建库前必须确认嵌入模型在位 ──────────────────────────────────────────
# 锚"buildIndex 体内出现 hasEmbedModel 判定"，不锚"文件里出现过 hasEmbedModel"。
c "buildIndex 检查了嵌入模型是否已加载" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'hasEmbedModel'"
c "buildIndex 检查了维度可用（embedDim > 0）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'embedDim'"
c "buildIndex 走 RagIndexStore.save 落盘（不是自己写文件）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'RagIndexStore.save'"

# ── ④ 检索路径必须做指纹比对 ──────────────────────────────────────────────
# 关键点：search 里构造的指纹必须来自 **当前** embed 模型与切块参数，
# 然后交给 load() 比对。缺这一跳 = 换模型后继续用旧索引（静默排序全乱）。
c "search 按当前模型构造指纹" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'fingerprintOf'"
c "search 把指纹交给 load 比对" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'RagIndexStore.load'"
c "search 对 none / empty / valid 三态分别处理" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'Load.none'"
# ⚠ 这条**不能**只判 "出现 Load.empty" —— 桩⑫ 证明那会假绿：把 empty 分支的
# 返回值从"空命中"改成"没有索引"（= 三态退化成两态，空库每次触发重建），
# `Load.empty` 这几个字照样在。判据必须锚**那一支的返回值**。
#
# ⚠ 判据随返回型变化（第 2 步）：`search` 的返回从 `List<SearchHit>?` 换成了
#   带理由的 `Search`，于是"空库返回空列表"的**写法**变成
#   `Load.empty -> return Search.Hits(... emptyList() ...)`（`Ranked` 里包着空列表）。
#   锚点跟着改，但**语义没放宽**：空库那一支必须落到"有索引但 0 条"，
#   绝不能落到 `NoIndex`（那会让空库每次触发重建）或别的东西。
c "search 的 empty 分支落到「有索引但 0 条」（**不是** NoIndex —— 否则空库每次触发重建）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'Load.empty -> return Search.Hits'"
c "search 的 empty 分支里确实是空命中（Ranked 包着 emptyList）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'Load.empty -> return Search.Hits(RagIndexStore.Ranked(emptyList()'"
c "search 在 valid 分支才算 query 向量（不白算）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'Load.valid'"

# ── ④b 正文 hash：契约与"只保存正文"的那条路 ──────────────────────────────
# ⚠ 这一族判据守的是阶段 2 新加的第二条轴："索引是否可用"不只取决于模型，
#    还取决于**正文有没有被改过**。少任何一条都会退化成"配了知识库但回答里
#    永远不带内容"，或者更坏——用旧语义的向量命中、注入新写进去的正文。
#
# ① 指纹必须由**正文 hash** 一起构成（不是"另有一套作废状态"）
c "fingerprintOf 的形参里有正文 hash（且没有默认值 —— 少传就编不过）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py fp-signature \"\$STORE\""
# ② 写下的正文与指纹里的正文**同源**：save 内部现算，调用方只管模型那一半
c "save 在函数体内由 rows 现算正文 hash（写下的与判定的同源）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-same-source \"\$STORE\""
# ⚠ 判据要看**签名**（`fp: FpInput` 在形参表里），而 `_fnbody.py save` 只输出函数体 ——
#   用它判会恒假（本轮真踩到）。改走 check_texts_hash_wiring.py，它读整段声明。
c "save 的指纹形参是 FpInput（只带模型那一半），不是拼好的字符串" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-fp-param \"\$STORE\""
# ③ buildIndex / search 都必须走 FpInput + textsHashOf（各写一份必然漂移）
# ⚠ 判据已随"嵌入身份进指纹 + 判据输入只有一处"更新：
#   判"buildIndex 体内出现过 RagIndexStore.FpInput"在把构造**抽出**成
#   `embedFpInput` 之后变成**恒假**（实现被搬走了，而它恰恰是被搬对了）——
#   这正是本仓库反复记录的那类"实现搬走反而判红"。
#   改成锚**调用那一处**：两处都必须调 embedFpInput（同一个取值处），
#   而 embedFpInput 体内真的具名构造 FpInput。细则见 check_fp_wiring.py。
c "buildIndex 走唯一的指纹取值处（embedFpInput）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py buildIndex | grep -q 'embedFpInput'"
c "search 走同一个取值处（两处各写一遍 = 建库成功却报\"参数变了\"）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'embedFpInput'"
c "指纹输入只有一处构造（embedFpInput 体内具名构造 FpInput）" \
  "$PY tools/rag_index/check_fp_wiring.py single-source \"\$ENGINE\""
c "嵌入身份进指纹（池化 / 输出维度 / 模型 stat 由同一处取）" \
  "$PY tools/rag_index/check_fp_wiring.py embed-identity \"\$ENGINE\""
c "search 用当前正文的 hash 构造指纹（改过正文 -> 判为没索引）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py search-texts-hash \"\$ENGINE\""
# 改字重算那条路的"索引此刻可用吗"闸：省略 `r` 的后果与 search 逐字相同
# （每一次改字都被判索引不可用 -> 退回只写正文 + 待重建，而用户以为是特性没生效）。
c "改字重算的可用性判据现算 rows（与检索同一把尺子）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py rewrite-rows-usable \"\$ENGINE\""
c "search 读的是磁盘上的正文（不是内存里的一份副本）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'RagIndexStore.readTexts'"
# ⚠ 判据从"体内出现过 textsInSync"换成锚**逐字段比对**：
#   旧版只比正文 hash，于是"换模型 / 池化变了 / 切块参数变了"全归成同一句
#   "模型或切块参数与建库时不同" —— 用户拿着那句话没法动手（不知道该换模型
#   还是该重建，也不知道是哪一项）。用户拍板：不要静默作废，要说清是哪一项。
c "search 用逐字段比对给出\"不可用\"的原因（不是一个笼统结论）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'lastDiff'"
c "\"待重建\"与\"真不可用\"给的是两句不同的话（用户处置不同）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'd.stale'"
c "存储层提供逐字段比对（compareFp）与尾随诊断（lastDiff）" \
  "$PY tools/rag_index/check_fp_wiring.py compare-fp \"\$STORE\""

# ── ④c 检索出口必须**可区分**（第 2 步，用户拍板）────────────────────────
# 旧代码把三件**处置相反**的事压成同一个出口：
#   · 索引不可用（要重建 / 换回模型）；
#   · 查询编码失败（偶发，与索引无关，重试即可）；
#   · 真的没命中（正常，什么都不用做）。
# 于是"时好时坏、日志说加入成功却不引用"这条观察**从日志上分不出成因**。
# 判据锚两件事，缺一不可：
#   ① `embed(query)` 的失败**必须**走单独一支（`Search.QueryFailed`），
#      绝不许写成 `embed(query) ?: return emptyList()` 与"没命中"合流；
#   ② 调用方按三态分别印**三句不同**的话（否则拆了也不可观测）。
c "search 把「查询编码失败」单独分出一支（不与「没命中」合流）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'Search.QueryFailed'"
# ⚠ 判据锚**那一行的返回值**（`embed(query) ?: return Search.QueryFailed`），不锚
#   "文件里没有 `?: return emptyList`" —— 后者只在旧写法**逐字保留**时才红，
#   把返回值换成 `Search.Hits(空 ...)` 这种"没有报错、只是混流"的写法照样绿
#   （桩㉔ 第一形态就是这么骗过它的）。这里要求 `embed(query)` 的失败出口
#   **就是** QueryFailed 那一支。
c "查询编码失败**必须**从 QueryFailed 那一支出口（不许与「没命中」混流）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py search | grep -q 'embed(query) ?: return Search.QueryFailed'"
# ⚠ 这两条锚在 $ENGINE_CODE（**去注释、保字符串**）上，而不是 `_fnbody ragContext`：
#   要判的那个值恰好写在日志的字符串模板（`${fmtScore(r.rank.best)}`）里，而
#   `_fnbody` 会把整段字符串（含其中的插值）抹掉 —— 用它判会**恒假**。
#   注意 $ENGINE_CODE 已剥掉注释，所以"注释里提到过"不会被算成实现。
c "「没命中」时把**最高分**打出来（差一点 vs 完全不像，一眼可分）" \
  "printf '%s' \"\$ENGINE_CODE\" | grep -q 'fmtScore(r.rank.best)'"
c "「索引不可用」与「待重建」在调用方是两句不同的话（处置不同）" \
  "printf '%s' \"\$ENGINE_CODE\" | grep -q '(r.stale)'"
c "存储层给出「过滤前的最高分」（Ranked.best，NaN 表示一条都没算过）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py rank | grep -q 'var best = Float.NaN'"
# ⚠ 这条判据管的是**判据自己有没有执行力**：`0.9999f` 不是"全挡"阈值
#（它 = 0.99989998…，`1.0f >= 0.9999f` 为真），拿它当"全部挡在阈值之上"用，
# 那一格就从来进不了全挡分支 —— 两条断言因此一红一恒真，而中间那条
# "best 仍是 1.0" 恰好为真，正好把"这格没在测全挡"盖住。
# 真正能全挡的是 `1.00001f`（相邻 topK 那一格用的正是它，而 topK 就是
# `rank(...).hits` —— 同一份实现，两处阈值不一致本身就是痕迹）。
c "rank 测试的「全挡」阈值**严格大于**矩阵最高分（0.9999f 挡不住任何一条）" \
  "$PY tools/rag_index/check_rank_full_block.py"
c "rank 测试里不再用 0.9999f 冒充「全挡」阈值" \
  "! cat tools/rag_index/RagIndexStoreTest.kt | grep -q 'rank(.*0.9999f'"
# ── ③b NaN 分数必须**在阈值比较之外**被丢掉 ─────────────────────────────
# ⚠ 旧实现只写 `if (minScore > 0f && !(score >= minScore)) continue`：
#    `minScore > 0f` 为假时（调用方显式关阈值）那个比较整段被跳过，而 NaN
#    在 Kotlin 的 Float 比较里**大于一切** —— `compareByDescending` 会把它
#    排到最前，**占掉一个 top-k 名额**，把一条真及格的命中挤出去。
#    排序全对、只是少一条，接口 200、日志干净。
#    所以判据是"收进 passed 之前有 isFinite 这道闸"，且它排在阈值比较**之前**。
c "rank 丢弃非有限分数（NaN/Inf 不许进 passed 占 top-k 名额）" \
  "$PY tools/rag_index/_fnbody.py rank < \"\$STORE\" | grep -q 'if (!score.isFinite()) continue'"
c "rank 的 NaN 闸排在阈值比较之前（阈值关掉时也要丢 NaN）" \
  "$PY tools/rag_index/check_rank_nan_guard.py \"\$STORE\""

# ── ③c 截断点必须落在**字符边界**上（不许切在代理对中间）──────────────────
# ⚠ 这一族和上面 Kotlin UTF-16 对齐是同一件事的**另一条路**：那边管分块切点，
#    这边管"按字符数截断"（`substring(0, N)`）。砍在代理对中间会留下一个孤立
#    高位代理 —— 内存里 JSON 往返与 `==` 全通过（单测用 `.length` 断言**恒绿**），
#    而 `toByteArray(UTF_8)` 时被替换成 `?`（不可逆），用户看到的是"已导入 N 条"。
#    而落盘的字节正是**指纹 hash 的输入**，两边自洽 —— 没有任何一处会报错。
c "TextBoundary 有 clipAtCharBoundary，且用 isLowSurrogate 判「半个字符」" \
  "$PY tools/rag_index/_fnbody.py clipAtCharBoundary < \"\$TB\" | grep -q 'isLowSurrogate'"
c "clipAtCharBoundary 不按字节的 0xC0 判据（那会把正常汉字误判成半个）" \
  "! $PY tools/rag_index/_fnbody.py clipAtCharBoundary < \"\$TB\" | grep -q '0xC0'"
c "RagArchive 的 MAX_TEXT_CHARS 截断确实调用 clipAtCharBoundary" \
  "$PY tools/rag_index/_fnbody.py entryOf < \"\$ARCHIVE\" | grep -q 'clipAtCharBoundary'"
c "RagArchive 不再裸 substring(0, MAX_TEXT_CHARS)（会切在代理对中间）" \
  "! $PY tools/rag_index/_fnbody.py entryOf < \"\$ARCHIVE\" | grep -q 'substring(0, MAX_TEXT_CHARS)'"
c "LlmEngine.addEntry 的超长截断走 clipAtCharBoundary" \
  "$PY tools/rag_index/_fnbody.py addEntry < \"\$ENGINE\" | grep -q 'clipAtCharBoundary'"
c "LlmEngine.addEntry 不再裸 substring(0, cp.targetChars)" \
  "! $PY tools/rag_index/_fnbody.py addEntry < \"\$ENGINE\" | grep -q 'substring(0, cp.targetChars)'"
# ⚠ 这三处"独立编译"的源列表必须带上 `TextBoundary.kt`：
#    `RagArchive` / `ChatArchive` 是**纯逻辑**文件（不碰 Android 栈），而它们的
#    单测只编"这个文件 + 少量依赖"。引用一个新文件却忘了进源列表的后果不是
#    "少测一块"，是**整套以 `unresolved reference` 结束** —— 而脚本里那句
#    `|| true` 会把退出码吞掉，只有 `grep ': error:'` 拦得住。
#    （本轮真踩到：`ChatArchive` 引 `RagIndexStore` 时就是这么挂的，
#      而 `run_chat_archive_tests.sh` 的注释里恰好早写着同一形态的教训。）
c "RagIndexStore 单测的源列表带 TextBoundary.kt" \
  "grep -q '\$SRC/TextBoundary.kt' tools/run_rag_index_store_tests.sh"
c "ChatArchive 单测的源列表带 TextBoundary.kt" \
  "grep -q '\$SRC/TextBoundary.kt' tools/run_chat_archive_tests.sh"
c "RagArchive 单测的源列表带 TextBoundary.kt" \
  "grep -q '\$SRC/TextBoundary.kt' tools/run_rag_archive_tests.sh"

# ④ 「只保存正文」这条路的三个不变式
c "saveTexts（只写正文）不碰 index.bin：体内没有写索引那一跳" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-texts-only \"\$STORE\""
c "saveTexts / writeTexts 是正文文件的**唯一**写入口（多一处写就会漏掉 hash 同步）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py single-writer \"\$STORE\""
# ── ③d 「库里已有 N 条」的口径必须与索引侧**同一条**（rowsToCount）──────
# ⚠ 旧实现数的是"非空行个数"，它在**自产文件**上恰好等于 `rowsToCount`，
#    而 texts.jsonl 是**可以被外部改的**（导入路径写的是"磁盘上已有正文 +
#    本次新增"，其中"已有正文"是从磁盘原样读回来的）。一旦用户手工编辑过它、
#    或在其中留了空行，两种口径立刻分家（实测末尾无换行 / 中间空行 / 连续空行
#    等 6 种形状都不等）—— 于是确认框说"已有 3 条 → 导入后 5 条"，而实际落地是另一个数。
c "entriesCountOf 与索引侧同口径（rowsToCount(rowsOf(...))，不数非空行）" \
  "$PY tools/rag_index/check_entries_count.py \"\$STORE\""
# ── ③e 导入接缝：拼接前保证 `before` 以换行收尾（ISSUE #326 的 C1）────────
# ⚠ `importEntries` 写的是 `before + encodeTexts(incoming)`，而 `before` 是从
#    磁盘**原样读回**的。自产文件以换行收尾没问题，但这份文件**可以被外部改**
#    （同上面的注释承认过），末尾无换行时旧末条与本次首条会**粘成同一行** ——
#    `decodeTextRow` 对那一行解析失败返回 null -> 两条都"读不出来"，而
#    `rowsToCount` 数的是段数、行数没变，回执数字对得上（静默）。
#    判据必须锚"拼接**之前**做了收尾对齐"，不锚"出现过 endsWith"（注释里也有）。
c "importEntries 拼接前把 before 对齐到「以换行收尾」（末尾无换行的外部文件也接得上）" \
  "grep -vE '^[[:space:]]*(\*|/\*|//)' \"\$STORE\" | grep -q 'before0.endsWith(\"\\\\n\")'"
c "importEntries 不再把磁盘原文**裸拼**给 encodeTexts（「before0 + encodeTexts」已绝迹）" \
  "! grep -vE '^[[:space:]]*(\*|/\*|//)' \"\$STORE\" | grep -q 'before0 + encodeTexts(incoming)'"
c "LlmEngine.saveTexts 拒绝\"该库还没有索引\"（否则写进谁也看不见的孤儿目录）" \
  "$PY tools/rag_index/check_texts_hash_wiring.py save-guard \"\$ENGINE\""

# ── ④d Kotlin 的 UTF-16 对齐：只有 low surrogate 才算"半个字符"─────────────
# ⚠ 这一族是本轮**差分测试撞出来的**两个真实缺陷（都不是新增的），锚在这里
#    免得再被写回去：
#      ① 旧写法 `if (s[j].code and 0xC0) == 0x80` 把 C++ 那套**按字节**的判断
#         搬到了 Kotlin 的 **UTF-16 code unit** 上。`文` = U+6587，
#         `0x87 and 0xC0 == 0x80` —— 一个正常汉字被当成半个字符，切点被凭空前移。
#         CJK 正文里这种字很常见，于是块边界随文档内容漂移、两边对不上。
#      ② 旧写法 `coerceIn(0, s.length)` 允许 `j == s.length`，而 `while` 先取
#         `s[j]` —— 空/越界下标直接抛 StringIndexOutOfBoundsException。
#    正确判据只有一条：`isLowSurrogate()`（Kotlin 的下标只有在代理对中间才是
#    半个字符；BMP 字符含全部 CJK 永远不在码点中间）。
c "Kotlin alignUtf8 用 isLowSurrogate 判「半个字符」（不是按字节的 0xC0 判据）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py alignUtf8 | grep -q 'isLowSurrogate'"
c "Kotlin 对齐不再用 (code and 0xC0) 这套**字节**判据（对 UTF-16 code unit 是误判）" \
  "! printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py alignUtf8 | grep -q '0xC0'"
# ⚠ 判据锚"到末尾/越界那一档被**显式分流**"，而不是锚"没有某个写法"：
#   后者是负向断言，实现一换成别的越界形态（比如把 coerceIn 去掉）就恒真。
c "Kotlin alignUtf8 对 i >= length 显式返回 length（不在越界下标上取字符）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py alignUtf8 | grep -q 'if (i >= s.length) return s.length'"
c "Kotlin nextUtf8Start 用 isLowSurrogate（与 alignUtf8 同一口径）" \
  "printf '%s' \"\$ENGINE_CODE\" | $PY tools/rag_index/_fnbody.py nextUtf8Start | grep -q 'isLowSurrogate'"

# ── ④e 分块规则的差分测试必须在 CI 里跑（结构判据替代不了行为比对）────────
# ⚠ 查的是**调用清单**而不是 `.cnb.yml` 这一个文件：2026-10-10 之前 CI 在这里，
#   之后 stage 正文搬去了 `scripts/ci-apk-stage.sh`（`.cnb.yml` 里那份内联
#   170 KB 会让 CNB runner 的 fork/exec 报 `argument list too long`）。
#   判据锚的是"这一步真的在流水线里被执行" —— 入口文件叫什么名字不是它的问题，
#   所以两处**任一命中**即算（两份都查，哪一天再搬回来也不会假红）。
c "CI 里有分块规则差分这一步（C++ vs Kotlin 真源码抽出）" \
  "grep -q 'chunk_rule/run_diff.sh' .cnb.yml scripts/ci-apk-stage.sh"

# ── ⑤ 落盘必须原子（tmp + renameTo）──────────────────────────────────────
c "save 写临时文件后 renameTo（原子落盘）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py save | grep -q 'renameTo'"
# ⚠ `_fnbody` 会**抹掉字符串字面量**（那是它的本职：判据锚实现不锚字面量），
# 于是 `File(d, "$FILE.tmp")` 与 `File(d, FILE)` 在它眼里**完全一样** ——
# 首版守卫就是靠"两份文件名"判的，那让桩⑦照样绿（假绿，本轮真踩到）。
# 正确的判据锚**变量名**：临时文件必须写进一个与正式文件**不同的句柄**，
# 而 `val tmp = ...` 这个临时句柄的存在本身就是"有中转"。
c "save 用了独立的临时句柄（tmp 中转，而不是直写正式文件）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py save | grep -q 'val tmp'"
# 反例：save 体内**不得**直接 writeBytes 到正式文件
c "save 没有绕过 tmp 直写正式文件" \
  "! printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py save | grep -q 'File(d, FILE).writeBytes'"

# ── ⑥ 库名净化：拼路径前必须过 sanitizeName ──────────────────────────────
c "dirOf 走了 sanitizeName（防目录穿越）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py dirOf | grep -q 'sanitizeName'"
# 白名单判定：`c == '-'` / `c == '_'` / `c == '.'` 三个字面量被 `_fnbody` 抹成
# `c == ""`，所以数"字符比较的个数"而不是比字面量 —— 个数不对就是白名单被改短/改长。
# 这是**双向**判据：删掉 `/` 之类其实本就不在白名单里（不需要删），
# 而**新增**一个 `c == '/'` 会让个数变多、这条立刻红。
# ⚠ 三个字面量在**同一行**（`c == "" || c == "" || c == ""`），所以数的是
# *出现次数* 而不是 *行数* —— `grep -c` 数行，这里必须 `grep -o | wc -l`。
c "sanitizeName 的字符白名单是 3 个显式字符（个数锚定）" \
  "[ \"\$(printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py sanitizeName | grep -o 'c == ' | wc -l)\" = \"3\" ]"
c "sanitizeName 对非法字符返回 null（不静默替换）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py sanitizeName | grep -q 'return null'"
# `..` / `.` 的拒绝也在字符串里（被抹掉），改判"返回值上有两道闸"：
# 一是空串/超长，二是净化结果等于 . 或 .. —— 后者必然伴随 `s == ` 的比较。
c "sanitizeName 有第二道闸（净化结果为空 / . / .. 时返回 null）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py sanitizeName | grep -q 's == '"

# ── ⑦ decode 的拒绝面：magic + 长度 + 荒谬值 ──────────────────────────────
c "decode 校验 magic（不是我们的文件就拒）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py decode | grep -q 'MAGIC'"
c "decode 校验向量区长度（截断即拒）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py decode | grep -q 'raw.size'"
c "decode 拒绝荒谬 dim（0 / 超上限）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py decode | grep -q 'dim <= 0'"

# ── ⑧ 显式小端 + 不猜文件 ────────────────────────────────────────────────
c "encode/decode 显式指定 LITTLE_ENDIAN（别的大端环境不读成乱码）" \
  "printf '%s' \"\$STORE_CODE\" | grep -c 'LITTLE_ENDIAN' | grep -qv '^0$'"
c "指纹读取先判 magic（不猜文件类型）" \
  "printf '%s' \"\$STORE_CODE\" | $PY tools/rag_index/_fnbody.py fingerprintIn | grep -q 'MAGIC'"

# ── ⑨ 纯逻辑头文件必须真的被编进 native（否则它只是一份文档）───────────
# 判据：**在 include 区**（文件前 60 行）里出现。不判"全文出现过" ——
# 全文出现过恒真（注释、字符串、别处的引用都算），而"只在文件尾部被提到"
# 恰恰意味着它没有被真正编进去。
c "rag_index.h 在 llama_jni.cpp 的 include 区里（前 60 行）" \
  "head -60 \"\$JNI\" | grep -q 'rag_index.h'"
c "rag_index.h 声明了命名空间 ragindex（与守卫判据同锚）" \
  "printf '%s' \"\$HDR_CODE\" | grep -q 'namespace ragindex'"

echo
echo "PASS $ok / FAIL $bad"
[ "$bad" = "0" ] || exit 1
