#!/bin/sh
# 「.txt / .md 当正文导入（走粘贴建库那条路）」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一条修的是什么
# ═══════════════════════════════════════════════════════════════════════════
# Android 的剪贴板装不下用户那段要粘进知识库的字（几百 KB 的笔记），
# 而同一份笔记**作为文件是完整的** —— 手里有文件，却没有路走。
# 于是要新增"导入 .txt / .md"，并且它必须落回**粘贴建库**那条路
#（回填编辑器 -> 用户点「建库/重建」），而不是另造一条直接写库的路。
#
# 同时「导入正文」这个名字要改成「导入库」：它读的是一份**已经是一条条条目**的
# 库文件（信封 / 裸数组 / 裸 JSONL），"导入正文"这四个字让人以为
# "把一篇正文导进来"才是它的用途 —— 这正是用户报的那处误解。
#
# ═══════════════════════════════════════════════════════════════════════════
# 这一族改动的危险处**全在"错得不响"**
# ═══════════════════════════════════════════════════════════════════════════
#   · 纯文本导入**直接写库**（而不是回填编辑器）—— 文档里那些本该并行的短行
#     （标题 / 页码 / 列表项）在用户还没看清时就落了盘，而导入**没有撤销**。
#     更坏的是它绕开了"归属"这一层：写进的是哪个库由当时的输入框决定。
#   · 格式判据**靠扩展名** —— SAF 上 `.md` 常被报成 application/octet-stream，
#     于是"明明在，却选不中"被读成"这个 App 不支持 md"；反过来选错文件时，
#     二进制内容被逐行收成条目：建库成功、检索**能命中**，命中的是乱码。
#   · 一行一条与"一行一段"混用 —— 一份没有手动换行的文档（整篇一段）会变成
#     **一条**巨大条目，用户看到的库是"1 条"。这件事必须在回执上说出来
#     （报条数），否则"导入成功了但库里只有一条"没有任何线索指向原因。
#   · 非 UTF-8 的中文文件（GBK 笔记）被当 UTF-8 读 —— 每一行都变成一串 `�`，
#     落盘、建库、可检索，而内容全是乱码，**没有任何一步会报错**。
#   · 请求码与「导入库」共用 —— 症状是"选完文本文件后被当库文件解析"。
#   · 回填**不置脏** —— 切库时编辑器被整段换掉，用户刚导进来的长文静默消失。
#
# 判据一律锚**结构关系**（谁调用谁 / 谁设置哪个状态 / 在哪个函数体内 / 行序），
# 不锚"某某字符串出现过"（后者恒真：注释里就有）。纯静态断言，不依赖工具链。
# 运行：sh tools/run_rag_plain_import_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
RA=$SRC/RagArchive.kt
TB=$SRC/TextBoundary.kt
ACT=$SRC/EngineActivity.kt
PY=${PYTHON:-python3}

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "RagArchive.kt / EngineActivity.kt 都存在" "[ -f \$RA ] && [ -f \$ACT ]"

# ── 1) 纯文本解码：按内容判形状，且**出口是编辑器那条路** ────────────────
PT=$(body 'fun decodePlainText\(text: String, name: String' "$RA")
c "① decodePlainText() 存在（锚点未漂）" "[ -n \"\$PT\" ]"
c "① 二进制（NUL）直接拒 —— 选错文件时不许把乱码收成条目" \
  "printf '%s' \"\$PT\" | grep -q \"text.contains('\\\\\\\\u0000')\""
c "① 一行一条（按 \\n 切），且空行不成条目" \
  "printf '%s' \"\$PT\" | grep -q \"for (raw in text.split('\\\\\\\\n'))\" && \
   printf '%s' \"\$PT\" | grep -q 'if (line.isEmpty()) continue'"
# ⚠ 这两条本轮**改写过**：超长的一行不再"截断"（截到 10 万字仍然是**一条装不进
#   嵌入模型 ctx 的条目** —— 那份正文会被 native 静默截到前 511 个 token，
#   于是那一段之后的关键词一个也检索不到，而建库成功、界面上一切正常）。
#   现在它按软标点**切成多段**，每一段都是独立条目（ISSUE #259）。
#   所以判据从"截是用哪个函数截的"改成"每一条都装得进 ctx、且来源标签没丢"。
c "① 超长的一行切成多段（不是收成一条巨大的条目）" \
  "printf '%s' \"\$PT\" | grep -q 'for (seg in splitLongLine(line' && \
   grep -q 'fun splitLongLine(line: String, max: Int = EMBED_MAX_CHARS,' \$RA"
c "① 切段后的每一条都带上非空来源标签（不是空串）" \
  "printf '%s' \"\$PT\" | grep -q 'RagIndexStore.Entry(seg, label)' && \
   grep -q 'fun plainSourceLabel(fileName: String): String' \$RA"
# ⚠ 判据锚"取的是**下标**"（`boundaryBefore` 返回 Int）：切段要
#   `substring(pos, cut)` 之后接着往下走，而 `TextBoundary.clipAtCharBoundary`
#   返回的是**字符串** —— 拿它赋给下标变量是类型错（编译不过，本轮真踩到）。
#   ⚠ 定义处**不在 RagArchive 里**（切分原语搬去了 TextBoundary，与建库那一路共用），
#   所以查两处：这里**调用**、那边**存在**。
c "① 硬切按**字符边界**（不是裸 substring，代理对切开不可逆）" \
  "grep -q 'boundaryBefore' \$RA && grep -q 'fun boundaryBefore' \$TB"
# ⚠⚠ 这一条本轮**改了方向**（ISSUE #259 的用户拍板）：库名**不再从文件名派生**。
#   上一版这里是 `plainLibName(fileName)` —— 把文件名当目标库名，于是这条路与
#   「导入库」（目标库 = 用户选中的库）语义分家：想往现有库里追加一篇文档，
#   唯一的办法是先把文件改名成库名（用户报的原文）；更坏的是静默建出第二个库。
#   现在回填空串（与裸 JSONL 同形），由 UI 按**同一条规则**取目标库。
#   文件名只进**来源标签**（那才是它该去的地方）。
# 判据锚两点：① 返回的库名是**空串**（用 `Parsed(out,` 后紧跟一个空引号的字面形态，
# 引号本身不进模式 —— 那层转义在 `eval` 里会破坏引号配对，本仓库踩过）；② 旧的
# 文件名派生函数**真的没了**（留着它就是两套语义并存，而它们对"目标库是谁"
# 给出的答案相反，用户无法从界面上分辨）。
c "① 目标库**不从文件名派生**（返回空串 + 旧函数已删，与「导入库」共用一条规则）" \
  "printf '%s' \"\$PT\" | grep -qE 'return Parsed\(out, [^a-zA-Z]' && \
   ! grep -q 'fun plainLibName' \$RA"
c "① 文件名只进**来源标签**（文件-名 这种标签，不是库名）" \
  "printf '%s' \"\$PT\" | grep -q 'plainSourceLabel(name)' && \
   grep -q 'fun plainSourceLabel(fileName: String): String' \$RA"
c "① 解码是纯逻辑：不碰存储 / 上下文（宿主可直接单测）" \
  "sh tools/rag_plain_import/check_pure.sh \"\$RA\" decodePlainText"
# ⚠ 这一条是本功能的核心接线：格式判据必须在**按行收之前** —— 写在后面等于
#   先把 JSON 收成"一行行 JSON 文本"再拒绝，而那时条目已经进 out 了。
# ⚠ 判据用 python 取行号，不用 awk + 正则：`startsWith("[")` 里的 `[` 在 awk 的
#   ERE 里是字符类开头，转义层数一多就会写成"恒不匹配"—— 那正是本仓库最怕的假红/假绿。
c "① 形状判据（JSON 数组）写在按行收**之前**" \
  "$PY tools/rag_plain_import/check_order.py \"\$RA\" decodePlainText 'startsWith(' 'for (raw in text.split'"


# ── 2) 读文件这一跳：编码要**检查**，且不把 Uri 写进日志 ──────────────────
RD=$(body 'fun readText\(cr: ContentResolver, uri: Uri\): String' "$RA")
c "② readText() 存在（锚点未漂）" "[ -n \"\$RD\" ]"
c "② NUL 与 U+FFFD 两道闸都在（二进制 / 非 UTF-8 都会被拒）" \
  "printf '%s' \"\$RD\" | grep -q 'bytes.contains(0.toByte())' && \
   printf '%s' \"\$RD\" | grep -q \"text.contains('\\\\\\\\uFFFD')\""
c "② 非 UTF-8 的提示是**可执行**的（让用户另存为 UTF-8），不是只报一个编码名" \
  "printf '%s' \"\$RD\" | grep -q '另存为 UTF-8 后再导入'"
c "② 有体积上限，且超限**明说**（不静默截断 —— 截了就是半份数据）" \
  "printf '%s' \"\$RD\" | grep -q 'MAX_FILE_BYTES' && \
   printf '%s' \"\$RD\" | grep -q '文件太大'"
# 隐私：这条日志会进 ring / 日志页 / 导出 / 崩溃探针，Uri 里可能带用户名。
# 判据锚**函数体内**不得出现 uri.toString() 一类取值，不是"文件里别出现 uri"。
c "② 错误文案里**不带** Uri / 文件名（它会被写进会被导出的日志）" \
  "! printf '%s' \"\$RD\" | nocomment | grep -qE 'uri\\.(toString|encodedPath|path)|lastPathSegment'"
c "② 去查是哪份文件由调用方用 displayName 说（不是在这里拼 Uri）" \
  "printf '%s' \"\$(body 'private fun importRagTextFile\\(uri: Uri\\)' \$ACT)\" | grep -q 'ExternalModel.displayName'"

# ── 3) UI：解析完**先确认、再落盘**，框只是落盘之后的回读窗口 ─────────────
# ⚠⚠ 这一节本轮**整体改写过**（ISSUE #259 的追问）。
#
# 上一版这条路是"读进正文框、等用户点「建库/重建」才落盘"，判据钉的是
# "回填编辑器、不直接写库"。而它被框卡死：框一次只装 4 万字符、这条路又不落盘，
# 于是"框被截" = "建库必须拒绝" = **3 MB 的文档一个字都进不了库**，
# 而出路只有"把文档切小、分成几个库导入"—— 把 App 该干的活推给了用户。
#
# 这一版把落盘时机提前：解析完 -> 确认框 -> 直接落盘（与「导入库」同一个容量：
# 16 MB / 16384 条），框是落盘之后 [loadRagDoc] 回读出来的窗口。
# 所以判据也换了：**不再**钉"不写库"（那是上一版的正确方向，现在是错的），
# 改钉三件这一版真正会静默错的事：
#   · 落盘**必须经过确认框**（导入是追加合并、不可撤销 —— 这是唯一一次能反悔）；
#   · 确认框必须是**同一处**（两条路各写一套 -> 改一处另一处不会红）；
#   · 这条路**不许**自己再写一遍"落盘之后做什么"（切库/回读/刷列表都在
#     [doImportRagEntries] 里，各写一份必然漂移）。
IRTF=$(body 'private fun importRagTextFile\(uri: Uri\)' "$ACT")
c "③ importRagTextFile() 存在（锚点未漂）" "[ -n \"\$IRTF\" ]"
c "③ 读文件 + 解析在后台线程（不许在主线程读整份文件）" \
  "printf '%s' \"\$IRTF\" | nocomment | grep -q 'ioExecutor.execute'"
c "③ 解析失败有明确提示（不静默返回）" \
  "printf '%s' \"\$IRTF\" | grep -q '导入失败：\${parsed.error}'"
# ⚠ 这一条是本轮的核心接线：解析完之后**必须**进确认框，不能直接落盘。
#   直接调的后果是"用户还没看清条数，磁盘上的库已经被改了"——而导入不可撤销。
c "③ 解析完**先进确认框**（不是直接落盘 —— 导入不可撤销）" \
  "printf '%s' \"\$IRTF\" | nocomment | grep -q 'confirmRagImport(parsed, fromTextFile = true)'"
c "③ 这条路**自己不许**调 doImportRagEntries（落盘只走确认框那一条路）" \
  "! printf '%s' \"\$IRTF\" | nocomment | grep -qE 'doImportRagEntries|importRagEntries'"
c "③ 这条路**不许**自己写落盘后的动作（切库/回读在 doImportRagEntries 里）" \
  "! printf '%s' \"\$IRTF\" | nocomment | grep -qE 'setCurrentKb|loadRagDoc|refreshRagList'"
# 上一版那个函数必须**真的没了** —— 留着它就是"两条路同时存在"，而它们对
# "框被截时能不能建库"给出的答案相反（一条拒绝、一条落盘），用户无法从界面上分辨。
c "③ 上一版那条「回填编辑器」的路已被删掉（不许两套语义并存）" \
  "! grep -q 'private fun applyRagTextImport' \$ACT"
# 确认框里必须报出条数：一份没有手动换行的文档（整篇一段）会变成 1 条，
# 而"导进库了、里头只有 1 条"没有任何线索指向"你的文件里本来就没有换行"。
c "③ 确认框里报出**条数**（整篇一段会变成 1 条，必须让用户看得见）" \
  "printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q '文件里有 \${parsed.entries.size} 条正文'"
# ⚠ 标题按来源分流：`.txt` 那一版以前走的是"回填编辑器"，文案里没有一句"会写盘"。
#   现在它落盘了，标题还写着旧口气 -> 用户以为"只是读进框里"，那一步就被他当成
#   可逆的（本仓库最忌讳的"提示与动作不符"）。
c "③ 确认框标题按来源分流（.txt 那一版必须说"会写盘"）" \
  "printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q 'fromTextFile) \"导入文档（追加合并，会写盘）\"'"
c "③ 截断（skipped）**出声**，不静默" \
  "printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q 'parsed.skipped > 0'"
# ── ③b 目标库：两条导入路**共用同一条规则**（ISSUE #259）─────────────────
# 规则：信封带的 `library` 优先 -> 否则**当前选中的库**。
# ⚠ 第二条是本轮的关键（`.txt / .md` 从前走的是"文件名当库名"）。
# ⚠ 还有一条反向断言：**不许**再凭空兜底出 "导入的库" 那个名字 ——
#   它会让"我明明选了库"与"App 给我编了一个库"在屏幕上长得一样，
#   而后者会静默建出一个用户没要的新库。
# ⚠ 判据体一律**内联** `$(body ...)`（不要先存进一个 shell 变量再喂给 eval）：
#   函数体里有 `"`，而 eval 会把自己那一层重新展开一次 —— 变量里的引号会把
#   外层引号配对拆掉，表现是"这条判据恒红、而且看不出为什么"（本轮实测踩到）。
c "③b 目标库预填 = 信封库名 ?: **当前选中的库**（两条路同一规则）" \
  "printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q 'val fromEnvelope = ' && \
   printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q 'val fromCurrent = ' && \
   printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q 'val suggested = fromEnvelope ?: fromCurrent'"
# ⚠ 这一条必须**剥注释**：解释"为什么不再那么兜底"的注释里必然带着那个名字，
#   不剥就是恒红 —— 而恒红的判据很快会被 `|| true` 静音（本仓库对这两个自欺
#   模式都踩着：见 run_rag_plain_import_guard_tests.sh 顶部那段）。
#   剥注释之后剩下的才是"真的这么写了吗"：`setText("导入的库")` 之类。
c "③b **不许**再凭空兜底一个「导入的库」（那会静默建出用户没要的新库）" \
  "! printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | nocomment | grep -q '导入的库'"
# "目标库从哪儿来"必须在点「导入」**之前**说清：`.txt` 那条路的用户先验正好是反的
#（他以为目标会是文件名），不说清他会以为 App 又建了一个新库 / 或追加进了文件名库。
c "③b 确认框里说清目标库的**来路**（信封 / 当前选中 / 还没选）" \
  "printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q '文件里带的库名' && \
   printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q '你当前选中的库' && \
   printf '%s' \"\$(body 'private fun confirmRagImport\(parsed: RagArchive.Parsed, fromTextFile: Boolean = false\)' \$ACT)\" | grep -q '还没有选中任何库'"

# ── 4) 两个按钮：名字与动作对上，且各自的"指路"都在 ─────────────────────
c "④ 「导入库」这个名字在位（旧的导入正文不许留着）" \
  "grep -q 'ragImportBtn = btn(\"导入库\")' \$ACT && ! grep -q '\"导入正文\"' \$ACT"
c "④ 「导入库」按钮接的是**库文件**那条路（pickRagArchiveFile）" \
  "grep -q 'ragImportBtn.setOnClickListener { pickRagArchiveFile() }' \$ACT"
c "④ 「导入库」的确认框标题也改了（不再叫导入知识库正文）" \
  "grep -q '导入库（追加合并）' \$ACT && ! grep -q '导入知识库正文' \$ACT"
c "④ .txt/.md 按钮在位，且接的是**文档导入**那条路" \
  "grep -q 'ragTextImportBtn = btn(' \$ACT && \
   grep -q 'confirmDiscardRagDoc(\"导入文件\") { pickRagTextFile() }' \$ACT"
c "④ 文本选择器不写死单一 MIME（.md 在部分机型上不是 text/plain）" \
  "printf '%s' \"\$(body 'private fun pickRagTextFile\\(\\)' \$ACT)\" | grep -q 'EXTRA_MIME_TYPES'"
c "④ 按钮存在 —— 不是只定义了控件没挂上去" \
  "grep -q 'pageRag.addView(ragTextImportBtn)' \$ACT"

# ── 5) 请求码分开 + 分流到位 ─────────────────────────────────────────────
c "⑤ 文本导入用**独立**请求码（不与 42/43/44 共用）" \
  "grep -q 'private const val REQ_IMPORT_RAG_TEXT = 45' \$ACT"
OAR=$(body 'override fun onActivityResult' "$ACT")
c "⑤ onActivityResult 里四个请求码各走各的（四十二到四十五都在）" \
  "printf '%s' \"\$OAR\" | grep -q 'REQ_PICK && resultCode == RESULT_OK' && \
   printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_CHAT && resultCode == RESULT_OK' && \
   printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_RAG && resultCode == RESULT_OK' && \
   printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_RAG_TEXT && resultCode == RESULT_OK'"
c "⑤ 两个知识库入口**各自**分流到各自的处理函数（不许串）" \
  "printf '%s' \"\$OAR\" | awk '/REQ_IMPORT_RAG && resultCode/ && !a {a=NR} /importRagArchiveFile\\(uri\\)/ && !b {b=NR} /REQ_IMPORT_RAG_TEXT && resultCode/ && !c2 {c2=NR} /importRagTextFile\\(uri\\)/ && !d {d=NR} END{exit !(a && b && c2 && d && a<b && b<c2 && c2<d)}'"

# ── 6) 旧那条路不许被挤掉 / 被改坏 ───────────────────────────────────────
c "⑥ 「导出库」仍在，且仍走 doExportRag" \
  "grep -q 'ragExportBtn.setOnClickListener { doExportRag() }' \$ACT"
c "⑥ JSON 那三种形状的解码仍在（三条都还在 decode 里分流）" \
  "grep -q 'decodeBareArray' \$RA && grep -q 'decodeObjectOrLines' \$RA && \
   printf '%s' \"\$(body 'fun decode\\(text: String\\): Parsed' \$RA)\" | grep -q 'decodeBareArray'"

echo ""
if [ "$bad" = "0" ]; then echo "=== 纯文本导入守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 纯文本导入守卫：PASS $ok / FAIL $bad ==="; exit 1
