#!/bin/sh
# 「知识库正文导入 / 导出」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一批改动的危险处**全在"错得不响"**
# ═══════════════════════════════════════════════════════════════════════════
#   · 导出**连向量一起带走** —— 导入到另一台机器（或换过嵌入模型的那一台）时
#     指纹对不上：用旧语义的向量命中、注入新边界切出的正文，余弦值量纲都对不上、
#     排序像随机，而**接口 200、日志干净**。这是本特性唯一不可归因的错法。
#   · 导入**覆盖**现有库 —— 不报错、不崩，只是库里原有的笔记在某次"导入"之后
#     变成了别人的。用户要过几天才发现。
#   · 导入后**留着旧索引** —— `list()` 照旧报"有索引、N 条"，而正文已经比它多了；
#     用户以为导入完就能搜，实际每次检索都在用一份与正文对不上的边界。
#   · 选错文件被"尽力解析"成"导入 0 条" —— 用户读成"我的笔记是空的"，
#     真相是选错了一个别的 App 的备份文件。
#   · 坏行**静默丢** —— "导入后少了几条"看起来像 bug，而真相是那几行缺 t/s。
#   · 超限**静默截断** —— 用户以为文件里就这么多。
#   · 请求码与"选模型"/"聊天记录导入"共用 —— 症状是"选完文件什么都没发生"。
#
# 所以判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 在哪一层），
# 不锚"某某函数存在"。纯静态断言，不依赖任何工具链。
# 运行：sh tools/run_rag_archive_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
RA=$SRC/RagArchive.kt
TB=$SRC/TextBoundary.kt
RS=$SRC/RagIndexStore.kt
LE=$SRC/LlmEngine.kt
ACT=$SRC/EngineActivity.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（大括号配平；与 run_chat_archive_guard.sh 同一判据）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
# 剥注释（本文件里判据文本在注释中也出现，不剥会得到恒真的假绿）。
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "RagArchive.kt / RagIndexStore.kt / LlmEngine.kt / EngineActivity.kt 都存在" \
  "[ -f \$RA ] && [ -f \$RS ] && [ -f \$LE ] && [ -f \$ACT ]"

# ── 1) 信封：只带正文，绝不带向量 —— 这是本格式的设计约束 ────────────────
ENC=$(body 'fun encode\(library: String, entries: List<RagIndexStore.Entry>, exportedAt: Long\): String' "$RA")
c "encode() 存在（锚点未漂）" "[ -n \"\$ENC\" ]"
c "信封带格式名与版本号两个常量" \
  "grep -q 'const val FORMAT = \"local-llm-rag\"' \$RA && grep -q 'const val VERSION = 1' \$RA"
# 判据锚"写出去的那份 JSON 里没有向量字段"，而不是"文件里没有 vector 这个词"——
# 后者在解释"为什么不带向量"的注释里必然出现（不剥注释会恒假）。
c "① 信封**不含**任何向量 / 指纹 / 维度字段（只 t 与 s）" \
  "! printf '%s' \"\$ENC\" | nocomment | grep -qE 'put\(\"(vector|vec|dim|fp|fingerprint|chunkParams)\"'"
c "① 每条走 encodeTexts 同形的 t/s 两个键（不是另造字段名）" \
  "printf '%s' \"\$ENC\" | grep -q 'put(\"t\", e.text).put(\"s\", e.source)'"
c "① 导出/导入都用 RagIndexStore 的条目类型（不另造一份 Entry）" \
  "grep -q 'entries: List<RagIndexStore.Entry>' \$RA"

# ── 2) 解码：三种形状按内容判，信封认不出来就拒绝 ─────────────────────────
DEC=$(body 'fun decode\(text: String\): Parsed' "$RA")
c "decode() 存在（锚点未漂）" "[ -n \"\$DEC\" ]"
c "② 按**内容**（首个非空白字符）判形状，不靠扩展名" \
  "printf '%s' \"\$DEC\" | grep -q \"val first = trimmed\[0\]\" && \
   printf '%s' \"\$DEC\" | grep -q \"first == '\\\\['\" && printf '%s' \"\$DEC\" | grep -q \"first == '{'\""
ENV=$(body 'private fun decodeEnvelope\(root: JSONObject\): Parsed' "$RA")
c "decodeEnvelope() 存在（锚点未漂）" "[ -n \"\$ENV\" ]"
# ⚠ 各取第一次命中（`if (!f) f=NR`）：写成 `f=NR` 会被后续同名行覆盖，
# 于是一条"把判据挪到读 entries 之后"的桩会假绿 —— 取首次命中才是"判据在第几行"。
c "② 格式名对不上就返回 error（不是尽力解析）" \
  "printf '%s' \"\$ENV\" | grep -q 'fmt != FORMAT'"
c "② 格式名判据在**读 entries 之前**（顺序反了等于没拒绝）" \
  "printf '%s' \"\$ENV\" | awk '/fmt != FORMAT/ && !f {f=NR} /optJSONArray\(\"entries\"\)/ && !e {e=NR} END{exit !(f && e && f<e)}'"
c "② 版本号高于当前值也拒绝（不按已知字段硬解）" \
  "printf '%s' \"\$ENV\" | grep -q 'ver > VERSION'"
c "② 顶层既无 format 也无 entries 时拒绝（别的 App 的备份文件那一类）" \
  "grep -q '既不是信封，也不是一条条目' \$RA"
# ⚠ 这两条此前锚的是源码里的**字面写法**（`grep -q '既不是信封…'`、
# `grep -q 'if (root.has("t") && root.has("s"))'`）。字面锚在"源码形状要改"时
# 一定会被撞红，而**改的方向恰好是把静默错修掉** —— 那时守卫反而成了阻力
#（这一次就是：多行 JSONL 被 JSONObject 悄然吃成一条，修法必须动这两行）。
# 所以判据从"这行字还在不在"改成**行为契约**：
#   · 单行条目**必须收下**（不许误拒）—— 但必须同时要求"只认单行"这个前提，
#     否则"多行也认成一条"会重新变成绿；
#   · 多行文本**必须按行解析**（那才是它真实的形状）。
# 两条合起来才是原来那句话真正想守的东西。
c "② 单行 JSONL 收下、且判据里带**行数前提**（不是只看 has t/s）" \
  "cat \$RA | nocomment | grep -q 'nonBlank <= 1 && root.has(\"t\") && root.has(\"s\")'"
c "② 多行文本按 JSONL 逐行解析（不许被 JSONObject 悄然吃成一条）" \
  "cat \$RA | nocomment | grep -q 'if (nonBlank > 1)' && \
   cat \$RA | nocomment | grep -q 'return decodeLines(text)'"
c "② 行数由**文本自身**数出来，不从 JSONObject 取（后者只看到第一段）" \
  "cat \$RA | nocomment | grep -q 'val nonBlank = text.lines().count'"
c "错误文案里带上**实际读到的** format（好让用户知道选错了什么文件）" \
  "printf '%s' \"\$ENV\" | grep -q 'format = 「\$fmt」'"

# ── 3) 坏行：跳过并**计数**，且不猜空值 ────────────────────────────────
EO=$(body 'private fun entryOf\(o: JSONObject\): Pair<RagIndexStore.Entry, Int>\?' "$RA")
c "entryOf() 存在（锚点未漂）" "[ -n \"\$EO\" ]"
c "③ 缺 t **或** 缺 s -> 这一条不可用（不猜空值）" \
  "printf '%s' \"\$EO\" | grep -q '!o.has(\"t\") || !o.has(\"s\")'"
# 判据锚"同一行里既有 continue 又有 skipped++"，不是"文件里出现过 skipped++"：
# 后者在"坏行不计数、但别处计数"的桩上会假绿 —— 那种写法下坏行正是被静默丢掉的。
c "③ 坏行**计数**（不是静默丢）" \
  "grep -qE 'if \(e == null\) \{ skipped\+\+; continue \}' \$RA"
c "③ skipped 随 Parsed 一起返回（调用方才能告诉用户）" \
  "grep -q 'val skipped: Int' \$RA && grep -q 'val library: String' \$RA"
c "③ 末尾空行不算坏行（不报假警）" \
  "printf '%s' \"\$(body 'private fun decodeLines\(text: String\): Parsed' \$RA)\" | grep -q 'if (line.isEmpty()) continue'"

# ── 4) 超限：整份拒绝，不静默截断 ──────────────────────────────────────
# 判据锚"上限**等于** maxChunks"，不是"等于 16384" —— 写死数字的话，
# 下次两边一起改就会**一起变绿**（而它们恰恰可能改歪成不等）。
c "④ 单次导入条数上限与 ChunkParams.maxChunks **相等**（不是各自写死一个数）" \
  "$PY tools/rag_archive/check_import_cap.py \"\$RA\" \"\$RS\"" 
c "④ 超限**整份拒绝**（不是截断），且文案说明未导入任何内容" \
  "grep -q 'MAX_IMPORT_ENTRIES（未导入任何内容）' \$RA"
# 判据锚"上限判据在**逐条收之前**"：写在后面等于收完才拒绝（内存先被吃满）。
c "④ 上限判据在逐条收**之前**" \
  "printf '%s' \"\$ENV\" | awk '/arr.length\(\) > MAX_IMPORT_ENTRIES/ && !l {l=NR} /for \(i in 0 until arr.length\(\)\)/ && !f {f=NR} END{exit !(l && f && l<f)}'"

# ── 5) 存储层：importEntries 是**只增不改**，且必须作废索引 ───────────────
IMP=$(body 'fun importEntries\(ctx: Context, name: String, incoming: List<Entry>\): Int' "$RS")
c "importEntries() 存在（锚点未漂）" "[ -n \"\$IMP\" ]"
c "⑤ 追加在**末尾**（before + 新条目），绝不整份覆盖" \
  "printf '%s' \"\$IMP\" | grep -q 'val after = before + encodeTexts(incoming)'"
c "⑤ 合并后**删掉旧索引**（正文变了、向量还是旧的）" \
  "printf '%s' \"\$IMP\" | grep -q 'it.delete()'"
# 顺序：先删索引再写正文。反过来的窗口里会留下"新正文 + 老索引"，
# 而那正是"用旧向量命中新正文"那条静默错 —— 只有行序判据能钉住它。
c "⑤ 删索引在**写正文之前**（先删再写，失败方向才对）" \
  "printf '%s' \"\$IMP\" | awk '/it.delete\(\)/ && !d {d=NR} /writeTexts\(d, after/ && !w {w=NR} END{exit !(d && w && d<w)}'"
c "⑤ 写正文走 writeTexts（既有唯一实现，不另写一份）" \
  "printf '%s' \"\$IMP\" | grep -q 'writeTexts(d, after'"
# 判据锚**整句**（`if (!writeTexts(...)) return -1`），不是"出现过 writeTexts"：
# 后者在"调了但不管返回值"的桩上会假绿 —— 而那种写法下正文写失败仍返回成功，
# 用户看到"已导入"而磁盘上没有。
c "⑤ 正文写失败**必须**报失败（不许吞掉返回值）" \
  "printf '%s' \"\$IMP\" | grep -q 'if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return -1'"
c "⑤ 写完**核对行数**（不符就报失败，不假装成功）" \
  "printf '%s' \"\$IMP\" | grep -q 'if (actual != rowsToCount(rowsAfter)) return -1'"
c "⑤ 不经过 appendEntries（那要嵌入模型在线、逐条 encode）" \
  "! printf '%s' \"\$IMP\" | nocomment | grep -qE 'appendEntries|readState\('"
c "⑤ 条目数超 maxChunks 时拒绝（与建库同一上限）" \
  "printf '%s' \"\$IMP\" | grep -q 'rowsToCount(rowsAfter) > ChunkParams().maxChunks'"

# ── 6) 导出：纯读、不引用界面状态、不要求模型在线 ────────────────────────
EXPST=$(body 'fun exportEntries\(ctx: Context, name: String\): List<Entry>' "$RS")
c "exportEntries() 存在（锚点未漂）" "[ -n \"\$EXPST\" ]"
c "⑥ 导出复用 readEntries（同一份「什么算坏行」的口径）" \
  "printf '%s' \"\$EXPST\" | grep -q '= readEntries(ctx, name)'"
EXPLE=$(body 'fun exportRagArchive\(ctx: android.content.Context, name: String\): String\?' "$LE")
c "⑥ exportRagArchive() 存在（锚点未漂）" "[ -n \"\$EXPLE\" ]"
c "⑥ 导出不碰 native（不要求嵌入模型在线）" \
  "! printf '%s' \"\$EXPLE\" | nocomment | grep -qE 'native|Embed|embed'"
IMPL=$(body 'fun importRagEntries\(ctx: android.content.Context, name: String,' "$LE")
c "⑥ 导入/导出包一层 LlmEngine（UI 不直接拼信封）" \
  "[ -n \"\$IMPL\" ] && grep -q 'RagArchive.encode(name, rows' \$LE"
c "⑥ 导入结果文案**必须**说到「需重建」" \
  "grep -q '点「建库/重建」才能重新检索' \$LE"
c "⑥ 导入结果文案在索引被作废时**显式说明**原索引作废" \
  "grep -q '原索引已作废' \$LE"

# ── 7) UI 接线：请求码分开 + 后台线程 + 落盘前确认 ─────────────────────
c "⑦ 导入用**独立**请求码，不与选模型 42 / 聊天记录 43 共用" \
  "grep -q 'private const val REQ_IMPORT_RAG = 44' \$ACT"
OAR=$(body 'override fun onActivityResult' "$ACT")
c "⑦ onActivityResult 里三个请求码各走各的处理" \
  "printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_RAG && resultCode == RESULT_OK'"
c "⑦ 模型导入与聊天记录导入两条分支**仍在**（新增请求码不许把旧的挤掉）" \
  "printf '%s' \"\$OAR\" | grep -q 'REQ_PICK && resultCode == RESULT_OK' && \
   printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_CHAT && resultCode == RESULT_OK'"
IRF=$(body 'private fun importRagArchiveFile\(uri: Uri\)' "$ACT")
c "读文件 + 解析在后台线程（不许在主线程读整份文件）" \
  "printf '%s' \"\$IRF\" | nocomment | grep -q 'ioExecutor.execute'"
c "解析失败有明确的 error 提示（不静默返回）" \
  "printf '%s' \"\$IRF\" | grep -q '导入失败：\${parsed.error}'"
# ⚠ 签名本轮加了 `fromTextFile`（ISSUE #259）：`.txt / .md` 那条路改成了**直接落盘**，
#   于是它与「导入库」共用同一个确认框 —— 而这个确认框正是"导入不可撤销"的唯一一处
#   反悔机会。锚点跟着改成**允许**带第二个参数（否则判据恒红，而红的原因与
#   "有没有先确认"毫无关系）。
c "落盘前先确认（导入不可撤销）" \
  "grep -qE 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT"
DEX=$(body 'private fun doExportRag\(\)' "$ACT")
c "导出不受界面状态影响（纯读，不读筛选状态）" \
  "printf '%s' \"\$DEX\" | grep -q 'LlmEngine.exportRagArchive(this, name)'"
c "导出文件名用 .json（不是套用日志的 .txt）" \
  "printf '%s' \"\$DEX\" | grep -q 'exportName(\"rag\"' && printf '%s' \"\$DEX\" | grep -q 'ext = \"json\"'"
c "导出落盘声明 application/json（否则 .json 被当纯文本）" \
  "printf '%s' \"\$DEX\" | grep -q 'mime = \"application/json\"'"
c "导出失败 / 无内容两条路径都有提示（不静默丢）" \
  "printf '%s' \"\$DEX\" | grep -q '导出失败' && printf '%s' \"\$DEX\" | grep -q '没有可导出的内容'"
c "导入成功后切到目标库 + 刷列表（用户要看得见结果）" \
  "grep -q 'private fun doImportRagEntries(target: String, parsed: RagArchive.Parsed)' \$ACT && \
   printf '%s' \"\$(body 'private fun doImportRagEntries\(target: String, parsed: RagArchive.Parsed\)' \$ACT)\" | grep -q 'loadRagDoc(target)'"
c "导入按钮/导出按钮都接到了知识库页（不是只定义了控件）" \
  "grep -q 'ragExportBtn.setOnClickListener { doExportRag() }' \$ACT && \
   grep -q 'ragImportBtn.setOnClickListener { pickRagArchiveFile() }' \$ACT"
DEC2=$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' "$ACT")
c "确认框里说明了「追加合并」（不是静默覆盖）" \
  "printf '%s' \"\$DEC2\" | grep -q '追加合并'"
# ⚠ 这一句必须指向「**删库**」，不是「删索引」（ISSUE #228 第 ① 条）：
#   「删索引」在 ISSUE #259 第 ② 条之后**只作废向量、正文一条不动** ——
#   于是"删索引再导入"= 老正文还在 + 新正文追加 = **追加合并**，
#   恰恰是这一句要用户避开的那个结果（用户照旧句子走了一遍，条目翻倍了）。
#   要真覆盖，必须先让目标库连正文一起没了，而那个动作只有「删库」做得到。
c "确认框里给出「要覆盖怎么走」（**删库**再导入）" \
  "printf '%s' \"\$DEC2\" | grep -q '若要的其实是\*\*覆盖\*\*' && \
   printf '%s' \"\$DEC2\" | grep -q '点「删库」，再导入'"
# 反向：这句提示里**不许**把「删索引」当覆盖的走法（它会退化成追加合并）。
c "「要覆盖」那句不许指向「删索引」（它现在只删向量、正文不动）" \
  "! printf '%s' \"\$DEC2\" | grep -q '点「删索引」，再导入'"
c "确认框里报出「已有 N 条 → 导入后 M 条」" \
  "printf '%s' \"\$DEC2\" | grep -q '已有 ' && printf '%s' \"\$DEC2\" | grep -q '导入后 '"

# ── 8) 与既有结构不打架 ───────────────────────────────────────────────
c "⑧ 库名仍走 sanitizeName（不许自己拼目录名）" \
  "grep -q 'RagIndexStore.sanitizeName' \$ACT && ! grep -q 'File(rootDir' \$RA"
c "⑧ 没有第二处拼 texts.jsonl 的文件名" \
  "! grep -rn '\"texts.jsonl\"' \$RA \$LE"
c "⑧ 导入路径不回填 UI 之外的东西（不碰 ragDocDirty 的基线以外状态）" \
  "! printf '%s' \"\$(body 'private fun importRagEntries\(ctx: Context, name: String, incoming: List<Entry>\): Int' \$RS)\" | grep -qE 'LogExport|Toast|EngineActivity'"

# ── 9) 超长正文：切成多条，不许"一条巨大条目" ─────────────────────────
#
# 这一族修的是"导入成功、检索上一片空白 + 界面卡住"（ISSUE #259）。
# 一份**没有手动换行**的文档（整篇一段）在按行收的那条路上是**一行**，
# 而它以前被收成**一条**上限 10 万字的正文：
#   · 3 MB 的整篇 -> 库列表写「1 条」，正文框里一片汪洋；
#   · 3 万字的条目 -> 建库时被嵌入层按 n_ctx(默认 512) **静默截到前 511 个 token**，
#     那一行之后的关键词一个也检索不到，而建库成功、界面上一切正常。
# 切段的判据必须是**结构**（有没有切、切的是同一份内容），不是"某个数出现了"。
SPLIT=$(body 'fun splitLongLine\(line: String, max: Int = EMBED_MAX_CHARS,' "$RA")
c "⑨ splitLongLine() 存在（锚点未漂）" "[ -n \"\$SPLIT\" ]"
c "⑨ 推荐上限是个常量，且**小于**硬顶（超长是切、不是截）" \
  "grep -q 'const val EMBED_MAX_CHARS = ' \$RA && \
   printf '%s' \"\$SPLIT\" | grep -q 'line.length <= max'"
# ⚠ 判据锚"第 1 级走的是**共享原语**"，而不是"本文件里有一张表"：
#   表 + 切点选择 + 字符边界现在都在 TextBoundary（与建库那一路共用同一份）。
#   本文件里留一张私有表，就是这一轮要收掉的那个形态 —— 而它的代价不是"重复几行"，
#   是**改一处另两处不会红**（症状：同一份正文按行导入与按库建索引切得不一样）。
c "⑨ 两级降级：软标点 -> 硬切（最后一级一定切得动）" \
  "printf '%s' \"\$SPLIT\" | grep -q 'TextBoundary.pickCut' && \
   printf '%s' \"\$SPLIT\" | grep -q 'boundaryBefore(line, end)' && \
   grep -q 'onHardCut' \$RA"
# 反向判据：**空白级不许回来**。
# 它不是"少一级降级"，它是"整块丢字"的触发源：空白级切在空白**之前**、而软标点
# 切在分隔符**之后**，两种口径混着再叠加 trim，那截空白两头都不属于 —— 实测
# `"word ".repeat(400)` 丢 2 字、缩进 JSON（480 字）丢 40 字。而每一段都是独立
# 条目，落在夹缝里的正文连向量都不会有。`0.9.175` 否掉五级阶梯的第 4/5 级
# （空格 / `-`）时点的就是它。只查"有硬切"的话，把空白级再插回去照样全绿。
c "⑨ 切段里**没有空白级**（空白级 + trim = 整块丢字）" \
  "! printf '%s' \"\$SPLIT\" | grep -q 'isWhitespace()'"
# 切段**不做内部 trim**：切点是"内部"位置，`trim()` 只该作用在整行两端
# （调用方 decodePlainText 已经 line.trim() 了）。在循环里 trim 的代价是
# 同一截空白被"段尾 trimEnd + 下轮 trimStart"吃两次 —— 拼回去就少字。
c "⑨ 切段不做内部 trim（段尾 trimEnd + 下轮 trimStart = 同一截空白被吃两次）" \
  "! printf '%s' \"\$SPLIT\" | grep -qE 'substring\\(pos[^)]*\\)\\.trim\\(\\)'"
# 反向判据：本文件里**不许**再留一张自己的软标点表。
# 只查"调了共享原语"的话，一份既调共享原语、又留着私有表的实现照样全绿 ——
# 而那一份的私有表随时会被谁捡起来用（那就是重复的由来）。
c "⑨ 切段走**共享**的切点原语（本文件里不再留一张私有软标点表）" \
  "! grep -qE 'val SOFT_CUT_SEPS|private val .*SEPS.*=.*arrayOf' \$RA && \
   grep -q 'fun pickCut' \$TB && \
   grep -q 'val SOFT_CUT_SEPS' \$TB"
# 硬切必须**严格前进**：max 小于一个码点 / 对齐后退回 pos 时死循环会把主线程焊死。
c "⑨ 每一级都严格前进（不前进 = 死循环 = 主线程焊死）" \
  "printf '%s' \"\$SPLIT\" | grep -q 'pos = maxOf(end0, pos + 1)' && \
   printf '%s' \"\$SPLIT\" | grep -q 'boundaryAfter(line, pos + 1)'"
# ⚠ 判据锚"取的是**下标**"：切段要 `substring(pos, cut)` 之后接着往下走，
#   而 `TextBoundary.clipAtCharBoundary` 返回的是**字符串** —— 拿它赋给下标变量
#   是类型错（编译不过，本轮真踩到）。所以硬切走 `TextBoundary.boundaryBefore /
#   boundaryAfter` 那一对"返回下标"的函数。
#   ⚠ 它们**不在本文件里**（那是切分原语，与建库那一路共用的那批一起放在
#   TextBoundary）—— 判据跟着搬家，并且**两处都查**：
#     切段这一路调了它（`$SPLIT` 里有 boundaryBefore），
#     而它本身确实**存在**（定义在 $TB，不是"调用了一个不存在的东西"）。
c "⑨ 硬切按**字符边界**（切在代理对中间会留下孤立代理，落盘变 ? 且不可逆）" \
  "printf '%s' \"\$SPLIT\" | grep -q 'boundaryBefore' && \
   grep -q 'fun boundaryBefore' \$TB && \
   grep -q 'fun boundaryAfter' \$TB"
# 空串不许产出"一条空正文"：那会在正文文件里占一行，而 decodeTextRow 永远读不出它
# —— 条目列表里多一个删不掉的"这一行读不出来"。
c "⑨ 空串不产出任何段（空正文会占一个读不出来的行号）" \
  "printf '%s' \"\$SPLIT\" | grep -q 'if (line.isEmpty()) return emptyList()'"
# ⚠ 单行 JSONL 也要切：同一条正文装进信封（会切）与装成**只有一行的 JSONL**
#   （不切）得到**不同条目数**，而这两种只是同一份数据的外壳。
c "⑨ 单行 JSONL 也走切段（同一份数据不该因外壳不同而条目数不同）" \
  "grep -q 'val (out, nosplit) = explodeLongEntries(listOf(one.first))' \$RA"
# ⚠ 判据锚"切段发生在**三条 JSON 形状汇合之后**"：（局部一处）才是唯一一份口径。
#   三条路各切一遍必然分岔（漏切一条、或用另一个上限），而现象是
#   "同一个库，用信封导入 12 条、用 JSONL 导入 3 条"——用户以为文件坏了。
EXP=$(body 'private fun explodeLongEntries\(entries: List<RagIndexStore.Entry>\)' "$RA")
c "⑨ 三条 JSON 形状共用**同一处**切段（explodeLongEntries）" \
  "[ -n \"\$EXP\" ] && \
   printf '%s' \"\$EXP\" | grep -q 'splitLongLine' && \
   grep -c 'explodeLongEntries(raw)' \$RA | grep -q '^3$'"
c "⑨ 切段发生在 entryOf **之后**（先认条目、再切长条目）" \
  "grep -c 'val (out, nosplit) = explodeLongEntries(raw)' \$RA | grep -q '^3$'"
c "⑨ entryOf 里**不再**有'超长截断'那条静默路径（截的是硬顶，切的是软线）" \
  "printf '%s' \"\$(body 'private fun entryOf\(o: JSONObject\): Pair<RagIndexStore.Entry, Int>\?' \$RA)\" | grep -q 'MAX_TEXT_CHARS'"
c "⑨ 纯文本那一路也是切段（不是收成一条巨大条目）" \
  "printf '%s' \"\$(body 'fun decodePlainText\(text: String, name: String\): Parsed' \$RA)\" | grep -q 'for (seg in splitLongLine(line'"
c "⑨ 纯文本那一路的条数上限判在**切段之后**（否则漏掉'一千万行、每行都很长'）" \
  "$PY tools/rag_plain_import/check_order.py \"\$RA\" decodePlainText 'for (seg in splitLongLine(line' 'out.size >= MAX_IMPORT_ENTRIES'"
# ⚠ 本轮改了一次出处（ISSUE #259）：`.txt / .md` 那条路从"回填编辑器"
#   （`applyRagTextImport`，已删）改成"直接落盘"，于是这句回执搬到了
#   `confirmRagImport` 里 —— 而且它落在**新家是对的**：硬切数要在用户点「导入」
#   之前就看见（导入不可撤销），而不是落盘之后放一个 Toast。
c "⑨ 硬切要**出声**（Parsed.splitNoPunct -> 确认框里说出来）" \
  "grep -q 'val splitNoPunct: Int = 0' \$RA && \
   grep -q 'parsed.splitNoPunct' \$ACT"

# ── 10) 列库判据：**目录**，不是 index.bin ─────────────────────────────
#
# 用户报的第二条：填一个新库名 + 导入 jsonl，提示与 Toast 都有、磁盘上文件都在，
# 而**列表里没有这个库**，切不了、也建不了索引。根因是 list() 按 index.bin 列库，
# 而 importEntries **有意**把 index.bin 删掉（宁可"没索引"也不要"旧向量配新正文"）。
# ⚠ 取 `list` 的函数体**必须**用 tools/rag_import/fnbody.py（真括号配对），
#   不能用本文件上面那个 awk 版 `body()`：awk 不认字符串字面量里的 `{`，
#   而 `list` 的返回类型 `List<Info>` 与表达式体里的 lambda 都带括号/花括号，
#   截出来的"函数体"只有前半段 —— 判据在那半段上恒红（本轮实测踩到）。
LIST=$(python3 tools/rag_import/fnbody.py "$RS" list)
c "⑩ 列库判据含**目录里有正文**（只有 index.bin 的那版会漏掉待重建的库）" \
  "[ -n \"\$LIST\" ] && printf '%s' \"\$LIST\" | grep -q 'texts.isFile' && \
   printf '%s' \"\$LIST\" | grep -q 'if (!hasIndex && !texts.isFile) return@mapNotNull null'"
c "⑩ 空目录**仍然不列**（0 条的幽灵没有'删索引'的对象，删不掉）" \
  "printf '%s' \"\$LIST\" | grep -q '!hasIndex && !texts.isFile'"
c "⑩ '有没有索引'与'正文是否同步'是**两个字段**（合成一个 = 待重建与正文已改同形）" \
  "grep -q 'val inSync: Boolean, val hasIndex: Boolean' \$RS && \
   printf '%s' \"\$LIST\" | grep -q 'hasIndex && textsInSync'"
c "⑩ UI 把两种'需重建'分开说（'待重建' 与 '正文已改'）" \
  "grep -q '待重建' \$ACT && grep -q '正文已改，需重建' \$ACT"
c "⑩ 状态行把'正文在、索引待建'与'该库不存在'分开说" \
  "printf '%s' \"\$(body 'private fun renderRagStatus\(\)' \$ACT)\" | grep -q '索引待建' && \
   printf '%s' \"\$(body 'private fun renderRagStatus\(\)' \$ACT)\" | grep -q '该库不存在'"

# ── 11) 「建库/重建」不认正文框之外的内容（导入后那一步必须走得通）──────
#
# 用户原话：「导入后，直接点击新建索引，会提示请粘贴需要索引的文本，这时候文件
# 实际已经导入私有文件夹的 jsonl 了，但是 app 不认，无法继续」。
# 那条分支此前**只看正文框**，而回填是异步的、还会被切库作废 ——
# 于是"磁盘上有整个库、框里是空的"是一个可达状态，用户在那条路上没有出口。
DBI=$(body 'private fun doBuildIndex\(\)' "$ACT")
c "⑪ 建库前会去问**磁盘**上有没有正文（不再只看正文框）" \
  "printf '%s' \"\$DBI\" | grep -q 'RagIndexStore.entriesCountOf'"
# ⚠ P1-a 之后这一条换了出口：正文框只装**当前一页**的只读预览，所以
#   "先读回框里再建"这个选项**不再存在**（框装不下整库，读回去只是把一页塞满）。
#   磁盘上有内容时的可执行选项现在只有一个、而且是**对的**那个：用磁盘上那份建
#   （`startBuildDisk`）—— 它读的是完整正文，不经过框。判据因此锚"切片态走
#   startBuildDisk 且不再把框当全文"，而不是那个已经删掉的按钮文案。
#   （P1-b 之后框在这一态下**可编辑**了，但"可编辑"说的是"改这一页的字"，
#    不是"框里就是整个库"—— 建索引这一跳仍然只认磁盘那份。）
c "⑪ 磁盘上有内容时给**可执行**的选项（用磁盘那份建，不拿框当全文）" \
  "printf '%s' \"\$DBI\" | grep -q 'startBuildDisk' && \
   printf '%s' \"\$DBI\" | grep -q 'ragDocSliced' && \
   ! printf '%s' \"\$DBI\" | grep -q '重新读回正文框'"
c "⑪ 用磁盘那份建库时条数大也先说多久（两处不许各算一套）" \
  "python3 tools/rag_import/fnbody.py \"\$ACT\" startBuildDisk | grep -q 'RagCost.buildPrompt'"

# ── 12) 逐条编码的进度回执：界面不许'看着卡死' ──────────────────────────
#
# 3 MB 整篇 = 上万块 = 几十分钟，此前屏幕上一句静态的「正在建库…」。
# 用户据此认为卡死，去点屏幕（fling 穿越大布局树）-> 闪退。
# 进度必须走**单独一档**（不进 ring / 不落盘 / 不进 logcat），且 UI 侧按**时间**节流。
c "⑫ LlmEngine 有独立的进度回执入口（不是 uiLog）" \
  "grep -q 'fun uiProgress(t: String)' \$LE && \
   printf '%s' \"\$(body 'fun uiProgress\(t: String\)' \$LE)\" | grep -q 'logSink?.invoke' && \
   ! printf '%s' \"\$(body 'fun uiProgress\(t: String\)' \$LE)\" | nocomment | grep -q 'pushLog'"
c "⑫ 逐条编码循环里报进度（用户看得见'在走'）" \
  "python3 tools/rag_import/fnbody.py \"\$LE\" buildIndex | grep -q 'uiProgress'"
# ⚠ updateProgressLine 的函数体里有 `"${PROGRESS_PREFIX}…"` 这样的模板，
#   用 awk 版 body() 会提前截断 —— 同样走真配对版（见 ⑩ 的说明）。
UPL=$(python3 tools/rag_import/fnbody.py "$ACT" updateProgressLine)
c "⑫ UI 侧按**时间**节流（按条数节流在'块很大很慢'那一半上失效）" \
  "grep -q 'PROGRESS_MS' \$ACT && printf '%s' \"\$UPL\" | grep -q 'ragStatusTv.text'"
c "⑫ 进度只碰状态行一个 TextView（不 renderLog / 不刷列表 —— 每次都重排会真的卡死）" \
  "! printf '%s' \"\$UPL\" | nocomment | grep -qE 'renderLog|refreshRagList|refreshRagRows'"
c "⑫ 进度被单独识别（前缀），不落进日志页渲染那条闸" \
  "grep -q 'PROGRESS_PREFIX' \$ACT && \
   grep -q 'line.startsWith(PROGRESS_PREFIX)' \$ACT && \
   grep -q 'else if (logRenderPending.compareAndSet(false, true))' \$ACT"
# ⚠ 上面那一条本来的锚是 `return@logSink` —— 它锚的是**一个编译不过的写法**。
#   `LlmEngine.logSink` 是 `((String) -> Unit)?`（普通函数类型，非 inline），
#   标签 `logSink` 不生成内联点，于是 `return@logSink` 在真编译器下是
#   `error: 'return' is prohibited here.`（两处：EngineActivity 2015 / 2018）。
#   而守卫只 grep 源码文本 -> 这个错误写法被**正面钉住**，同时 CI 的
#   `scripts/kt_check.sh` 是**后面**才跑，于是"守卫全绿 + 真编译红"。
#   新锚改成"进度那一支与渲染那一支是 if/else 两分"这个**结构关系**：
#   它既表达了"进度不进渲染闸"的意图，又只锚在能编译的写法上。
# ⚠ 判据要**剥注释**：上面那段解释恰好把 `return@logSink` 写进了注释里，
#   不剥注释的话这条判据是在数自己写的字，恒假（本仓库踩过的同族自欺）。
c "⑫ 进度闸不进 UI 主源码里的**非法 return**（非 inline lambda 里 return@logSink 编译不过）" \
  "! nocomment < \$ACT | grep -qE 'return@(logSink|uiProgress)'"

echo ""
if [ "$bad" = "0" ]; then echo "=== 知识库导入/导出守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 知识库导入/导出守卫：PASS $ok / FAIL $bad ==="; exit 1
