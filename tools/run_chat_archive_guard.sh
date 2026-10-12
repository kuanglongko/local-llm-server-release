#!/bin/sh
# 「聊天记录导入 / 导出」的接线守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么单靠"能编译 + 手点一下"不够
# ═══════════════════════════════════════════════════════════════════════════
# 这一批改动的危险处**全在"错得不响"**：
#
#   ① 导入把现有会话**覆盖**掉 —— 覆盖不会报错、不会崩，只会让本机已有的
#      对话在某次"导入一份备份"之后变成别人的记录。用户甚至要过几天才发现。
#   ② 选错文件（比如选了自己的 APK / 一张图）被"尽力解析"成"0 个会话" ——
#      提示"导入成功 0 个"会被读成"记录是空的"，而真相是文件选错了。
#   ③ 导入的 role 不过滤，把非 user/assistant 的东西塞进历史 —— 历史是直接
#      喂给模型的，症状只是"导入后回答怪怪的"，与"模型抽风"完全同形。
#   ④ 导出**套上 MAX_CHARS 裁剪** —— 用户拿到一份"看起来完整、实际被截掉开头"
#      的记录，而"导出坏了"与"会话本来就短"在文件里长得一模一样。
#   ⑤ 导出 MIME 写成 text/plain —— 文件名是 .json，系统却按纯文本处理，
#      点开是记事本、分享出去变 .txt。**没有任何报错**。
#   ⑥ 导入的**会话文件布局**在别处自己拼（`File(..., "s_$id.json")`）——
#      改命名规则那天会静默"导出空"或"导入不进去"。
#   ⑦ 导入的请求码与"选模型"共用 42 —— 选模型那次回调会被聊天解析器吃掉，
#      症状是"选完文件什么都没发生"，两个功能互相抵消。
#   ⑧ 导入把会话数上限**静默截断** —— 用户以为文件里就这么多。
#   ⑨ **会话顶层 `system` 被丢，一个字都不提** —— 用户导出一份带人设的备份、
#      换台机器导回来，人设没了，而 `skipped` 只数"非 user/assistant 的 role
#      与超长正文"。他的症状是"我的角色怎么不跟着走"，与"换台机器就不听话"同形。
#      ⚠ 这一条的判据形状与前面八条**不一样**：它不是"某个动作为了必须被拒绝"，
#        而是"一个**如实发生**的丢弃必须被说出来"。所以判据锚的是
#        "计数进 Parsed / 内容进 Session / 与 skipped 分开 / 确认框与结果提示
#        两侧都要读它" —— 少任何一处，这条修复就只是嘴上说说。
#
# 所以判据一律锚**结构关系**（谁调用谁 / 参数从哪儿来 / 在哪一层），
# 不锚"某某函数存在"。纯静态断言，不依赖任何工具链。
# 运行：sh tools/run_chat_archive_guard.sh
set -e
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/xiaowan/localinference
ARC=$SRC/ChatArchive.kt
SS=$SRC/SessionStore.kt
ACT=$SRC/EngineActivity.kt
LEX=$SRC/LogExport.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

# 取某个 kotlin 函数的函数体（大括号配平；与 run_chat_bubble_guard.sh 同一判据）。
body() { awk -v sig="$1" '
  $0 ~ sig && !start { start=NR }
  start && NR>=start {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}"); depth+=n-m
    print
    if (depth==0 && NR>start) exit
  }' "$2"; }
# 剥注释（本文件里判据文本在注释中也出现，不剥会得到恒真的假绿）。
nocomment() { grep -vE '^[[:space:]]*(\*|/\*|//)'; }

c "ChatArchive.kt / SessionStore.kt / EngineActivity.kt / LogExport.kt 都存在" \
  "[ -f \$ARC ] && [ -f \$SS ] && [ -f \$ACT ] && [ -f \$LEX ]"

# ── 1) 格式信封：认不出来就必须拒绝，不许"尽力解析" ────────────────────────
c "信封带格式名与版本号两个常量" \
  "grep -q 'const val FORMAT = \"local-llm-chat\"' \$ARC && grep -q 'const val VERSION = 1' \$ARC"
# 判据是**拒绝了**，不是"文件里出现过 FORMAT" —— 后者在常量定义处就恒真。
DEC=$(body 'fun decode\(text: String\): Parsed' "$ARC")
c "decode() 存在（锚点未漂）" "[ -n \"\$DEC\" ]"
c "① 格式名对不上就返回 error（不是尽力解析）" \
  "printf '%s' \"\$DEC\" | grep -q 'fmt != FORMAT'"
# ⚠ **各取第一次命中**（`if (!f) f=NR`）：写成 `f=NR` 会被后续同名行覆盖，
# 于是一条"把判据挪到读 sessions 之后、但文件后面还有别的 sessions 读"的桩
# 会假绿 —— 第一版就是这么漏掉桩⑨的。取首次命中才是"判据在第几行"。
c "① 格式名判据在**读 sessions 之前**（顺序反了等于没拒绝）" \
  "printf '%s' \"\$DEC\" | awk '/fmt != FORMAT/ && !f {f=NR} /optJSONArray\(\"sessions\"\)/ && !s {s=NR} END{exit !(f && s && f<s)}'"
c "② 版本号高于当前值也拒绝（不按已知字段硬解）" \
  "printf '%s' \"\$DEC\" | grep -q 'ver > VERSION'"
c "错误文案里带上**实际读到的** format（好让用户知道选错了什么文件）" \
  "printf '%s' \"\$DEC\" | grep -q 'format = 「\${fmt.ifEmpty'"

# ── 2) role 过滤：只收 user / assistant，且计数上报 ─────────────────────────
c "③ 只收 user/assistant 两种 role" \
  "printf '%s' \"\$DEC\" | grep -q 'role != \"user\" && role != \"assistant\"'"
# 判据锚**同一行里既有 continue 又有 skipped++**，不是"文件里出现过 skipped++"：
# 后者在"过滤分支不计数、但别处（比如超长截断）计数"的桩上会假绿 ——
# 而那种写法下 role 脏数据正是被静默丢掉的。桩⑬ 就是这一条。
c "③ 被跳过的脏数据**计数**（不是静默丢）" \
  "printf '%s' \"\$DEC\" | grep -qE 'role != \"user\" && role != \"assistant\"\) \{ skipped\+\+; continue \}'"
c "③ skipped 随 Parsed 一起返回（调用方才能告诉用户）" \
  "grep -q 'data class Parsed(' \$ARC && grep -q 'val skipped: Int' \$ARC"

# ── 3) 导出：原样带走，不许套 MAX_CHARS 裁剪 ────────────────────────────────
ENC=$(body 'fun encode\(sessions: List<Session>, exportedAt: Long\): String' "$ARC")
c "encode() 存在（锚点未漂）" "[ -n \"\$ENC\" ]"
# 剥注释再判：注释里为了讲清"为什么不裁剪"必然出现这两个词 ——
# 不剥会得到恒假的假红（第一版就是这么红的）。
c "④ 导出不做任何字符数裁剪（不能引用 MAX_CHARS / capChars）" \
  "! printf '%s' \"\$ENC\" | nocomment | grep -qE 'MAX_CHARS|capChars'"
c "④ 导出走 SessionStore.exportAll（文件布局只在一处）" \
  "grep -q 'SessionStore.exportAll(this)' \$ACT"
# 判据锚"没有第二处拼会话文件名"，不是"文件里没有 s_ 这个串"。
c "⑥ 会话文件名的拼法只在 SessionStore 里（别处不得自己拼 s_\$id.json）" \
  "! grep -rn 's_\$id.json' \$ARC \$ACT \$LEX"
c "⑥ 导出/导入都从 SessionStore 走（不在 UI 里读会话文件）" \
  "grep -q 'SessionStore.exportOne' \$ACT && ! grep -q 'filesDir' \$ARC"

# ── 4) 落盘 MIME：.json 必须声明 application/json ──────────────────────────
c "⑤ LogExport.saveText 接受 mime 入参（默认 text/plain 保旧行为）" \
  "grep -q 'mime: String = \"text/plain\"' \$LEX"
c "⑤ MediaStore 用入参 mime，不再写死 text/plain" \
  "grep -q 'put(MediaStore.Downloads.MIME_TYPE, mime)' \$LEX"
# ⚠ 锚点必须收在**聊天导出那个函数体**里：知识库导出（`doExportRag`）也用同一对
# 写法（`mime = "application/json"` / `ext = "json"`），全文件 grep 会让
# "聊天导出把 MIME 写回去"的桩**假绿** —— 它命中的是知识库那一份。
# 这是本轮新增知识库导出时暴露出来的一处既有隐患：判据的**作用域**比它想说的
# 那句话宽（它想说的是"聊天记录导出这一处"）。
EXPS=$(body 'private fun exportSessions\(all: Boolean, only: String\? = null\)' "$ACT")
c "exportSessions() 存在（锚点未漂）" "[ -n \"\$EXPS\" ]"
c "⑤ 聊天记录导出显式声明 application/json" \
  "printf '%s' \"\$EXPS\" | grep -q 'mime = \"application/json\"'"
c "⑤ 导出的文件名是 .json（不是套用日志的 .txt）" \
  "printf '%s' \"\$EXPS\" | grep -q 'ext = \"json\"'"

# ── 5) 导入：只增不改 ──────────────────────────────────────────────────────
IMP=$(body 'fun importAll\(ctx: Context, sessions: List<ChatArchive.Session>\): Int' "$SS")
c "importAll() 存在（锚点未漂）" "[ -n \"\$IMP\" ]"
# ① 覆盖的坏法就是"按原 id 写文件" —— 新 id 是判据。
WI=$(body 'private fun writeImported' "$SS")
c "writeImported() 存在（锚点未漂）" "[ -n \"\$WI\" ]"
c "① 导入的每条都拿**新 id**（绝不按原 id 覆盖）" \
  "printf '%s' \"\$IMP\" | grep -q 'writeImported' && \
   printf '%s' \"\$WI\" | grep -q 'System.currentTimeMillis().toString(36)'"
c "① 新 id 与**原文件的 id 无关**（不得把 s.id 当新会话的文件名）" \
  "! printf '%s' \"\$WI\" | nocomment | grep -qE 'fileOf\\(ctx, s\\.id\\)|s\\.id'"

# ⚠ 上面那条 `! grep s.id` 很脆：它把"任何地方出现 s.id"都判红，而参数名本身
# 随时会改。真正要钉的是"文件名用的是**新造的 id**"—— 锚法改成"新 id 变量被
# 用进了 fileOf"，这样它才在"把 s.id 写进 fileOf"时变红、在只是读 s.title 时不变红。
c "① fileOf 用的是**新造的 id**变量（覆盖的坏法就在这一句）" \
  "printf '%s' \"\$WI\" | nocomment | grep -qE 'fileOf\\(ctx, id\\)|fileOf\\(ctx, newId\\)'"
c "① 导入路径**不碰**已存在的会话（不得出现 rename/mutate 现有 id）" \
  "! printf '%s' \"\$IMP\" | grep -qE 'rename\(|clearMessages\(|deleteAt\('"
c "⑧ 按预算收：能收几条先算出来，收满就停（不是写完再说" \
  "printf '%s' \"\$IMP\" | grep -q 'SessionQuota.acceptCount(usedChars(ctx), sessions.map { charsOf(it) })' && \
   printf '%s' \"\$IMP\" | grep -q 'if (written >= quota) break'"
c "⑧ 返回真实写入条数（调用方要能报告"有几个没进去"）" \
  "printf '%s' \"\$IMP\" | grep -q 'return written'"
c "标题去重：同名会话拿到 (2) (3) 后缀，且本次已定的名字也记进去" \
  "printf '%s' \"\$IMP\" | grep -q 'val titles = list(ctx).map { it.title }.toMutableSet()' && \
   printf '%s' \"\$IMP\" | grep -q 'titles.add(title)'"
c "导入后第一条成为当前会话（用户点完能看到结果）" \
  "printf '%s' \"\$IMP\" | grep -q 'firstNew?.let { setCurrent(ctx, it) }'"
c "导入一条会话是**一次写完**（不许先建空壳再覆盖）" \
  "! printf '%s' \"\$WI\" | nocomment | grep -q 'mutate('"
c "写入失败不假装成功（writeImported 返回 null 时 continue）" \
  "printf '%s' \"\$IMP\" | grep -q '?: continue'"

# ── 6) 标题去重是纯逻辑，判据钉在 ChatArchive 上 ───────────────────────────
UT=$(body 'fun uniqueTitle\(base: String, existing: Collection<String>\): String' "$ARC")
c "uniqueTitle() 存在（锚点未漂）" "[ -n \"\$UT\" ]"
c "题目为空时给默认名（不是空标题）" \
  "printf '%s' \"\$UT\" | grep -q 'ifEmpty { \"导入的会话\" }'"
c "查重走 existing.contains（不是自己另建一份集合）" \
  "printf '%s' \"\$UT\" | grep -q 'existing.contains'"

# ── 6b) 「丢人设必须说出来」：计数 + 内容 + 与 skipped 分开 + 两个出口都读它 ──
#
# 这一段的判据全部锚**数据流**（谁算出它 / 它跟着谁返回 / 谁消费它），
# 不锚"源码里出现过 systemDropped" —— 后者在字段声明处就恒真。
c "⑨ 存下'丢了什么'的是 Session 上的字段（不是只留一个布尔/计数）" \
  "grep -q 'val systemDropped: String? = null' \$ARC"
c "⑨ 计数与逐条说明**都在** Parsed 里（只有计数，用户点不出是哪条会话）" \
  "grep -q 'val systemDropped: Int = 0' \$ARC && \
   grep -q 'systemDropped = sys.ifEmpty { null }' \$ARC"
# 判据锚"读它的**目的是**知道丢了什么"：README 那段注释与赋值必须在同一段里。
# 只 grep `optString("system"` 会命中 encode 侧（那一处本来就有），恒真。
c "⑨ decode 读顶层 system 之后**真的去判空并计数**（不是读了就扔）" \
  "printf '%s' \"\$DEC\" | grep -q 'val sys = o.optString(\"system\", \"\")' && \
   printf '%s' \"\$DEC\" | grep -q 'if (sys.isNotEmpty()) systemDropped++'"
# ⚠ 顺序：判空必须在 `out.add(Session(` **之前**。放到之后就是"构造完再回头改"，
# 而 Session 是 data class（不可变）—— 也就是根本改不了，只能靠字段默认值，
# 那正是"计数对、内容全 null"那种半吊子修复。
c "⑨ 计数发生在**构造 Session 之前**（Session 不可变，事后补不上）" \
  "printf '%s' \"\$DEC\" | awk '/if \(sys.isNotEmpty\(\)\) systemDropped\+\+/ && !c {c=NR} /out.add\(/ && !a {a=NR} END{exit !(c && a && c<a)}'"
c "⑨ 早退分支（空文件）也带上计数（不是只有成功那条路报）" \
  "grep -q 'Parsed(emptyList(), skipped, \"记录文件里没有任何会话\", systemDropped)' \$ARC"
c "⑨ 人设丢失**不进** skipped（两者处置相反：一个不用管、一个要记下来告诉用户）" \
  "printf '%s' \"\$DEC\" | grep -q 'if (sys.isNotEmpty()) systemDropped++' && \
   ! printf '%s' \"\$DEC\" | nocomment | grep -q 'skipped++.*system'"
c "⑨ systemDropped **不进信封**（encode 不写它，它不是格式的一部分）" \
  "! printf '%s' \"\$ENC\" | nocomment | grep -q 'systemDropped'"

# 两个出口都要**读**它 —— 只算不报与没算等价。
CIM=$(body 'private fun confirmImport\(parsed: ChatArchive.Parsed\)' "$ACT")
c "confirmImport() 存在（锚点未漂）" "[ -n \"\$CIM\" ]"
c "⑨ 落盘前的确认框里读 systemDropped（用户按下'导入'之前就知道会丢什么）" \
  "printf '%s' \"\$CIM\" | grep -q 'parsed.systemDropped > 0'"
c "⑨ 确认框里**逐条**列出丢了哪几段（只报个数时用户没法判断该不该在意）" \
  "grep -q 'private fun systemDroppedDetail(parsed: ChatArchive.Parsed)' \$ACT && \
   printf '%s' \"\$CIM\" | grep -q 'systemDroppedDetail(parsed)'"
DET=$(body 'private fun systemDroppedDetail\(parsed: ChatArchive.Parsed\)' "$ACT")
c "⑨ 逐条说明按会话标题点名（否则用户不知道是哪一条）" \
  "printf '%s' \"\$DET\" | grep -q 's.title'"
c "⑨ 预览超长时**显式标出省略**（静默截会让用户以为'本来就这么长'）" \
  "printf '%s' \"\$DET\" | grep -q 'SYSTEM_DROPPED_PREVIEW' && \
   printf '%s' \"\$DET\" | grep -q 'body.take(SYSTEM_DROPPED_PREVIEW) + \"…\"'"
c "⑨ 条数超过上限时如实报'另有 N 个未列出'（不是悄悄砍掉）" \
  "printf '%s' \"\$DET\" | grep -q 'MAX_SYSTEM_DROPPED_LIST' && \
   printf '%s' \"\$DET\" | grep -q '另有 \${hit.size - MAX_SYSTEM_DROPPED_LIST} 个会话的人设未列出'"
# 结果那一侧：确认框点掉就没了，Toast 是事后唯一的留痕。
c "⑨ 导入**结果**那一句里也带上（确认框关掉后还能对证）" \
  "printf '%s' \"\$CIM\" | grep -q 'system 人设未导入'"
# 反向：不丢的时候**不许**出声 —— 绝大多数文件都没有 system，
# 每次都弹一句没用的警告，用户会学会无视全部警告（同 MAX_SESSIONS_WARN 的口径）。
c "⑨ 不丢人设时不出声（否则每次导入都弹一句没用的警告）" \
  "printf '%s' \"\$CIM\" | grep -q 'if (parsed.systemDropped > 0)'"

# ── 7) UI 接线：请求码分开 + 导入走后台线程 + 落盘前先确认 ─────────────────
c "⑦ 导入用**独立**请求码，不与选模型共用 42" \
  "grep -q 'private const val REQ_IMPORT_CHAT = 43' \$ACT"
OAR=$(body 'override fun onActivityResult' "$ACT")
c "⑦ onActivityResult 里两个请求码各走各的处理" \
  "printf '%s' \"\$OAR\" | grep -q 'REQ_IMPORT_CHAT && resultCode == RESULT_OK'"
c "⑦ 模型导入那条分支**仍在**（新增请求码不许把旧的挤掉）" \
  "printf '%s' \"\$OAR\" | grep -q 'REQ_PICK && resultCode == RESULT_OK'"
IAF=$(body 'private fun importArchiveFile\(uri: Uri\)' "$ACT")
c "读文件 + 解析在后台线程（不许在主线程读整份文件）" \
  "printf '%s' \"\$IAF\" | nocomment | grep -q 'ioExecutor.execute'"
c "读文件不在主线程上直接 openInputStream" \
  "! printf '%s' \"\$IAF\" | grep -B2 'openInputStream' | grep -q 'statusTv.text = '"
c "解析失败有明确的 error 提示（不静默返回）" \
  "printf '%s' \"\$IAF\" | grep -q '导入失败：\${parsed.error}'"
c "落盘前先确认（导入不可撤销）" \
  "grep -q 'private fun confirmImport(parsed: ChatArchive.Parsed)' \$ACT"
# 两个入口都要**看得见**：导入原先只在长按会话项里，而它是唯一一个不针对
# 某个会话的动作 —— 用户"在列表里找过，没有"（见 [showSessionDialog] 那段注释）。
# 现在首访入口在会话管理上，与对偶「导出全部」同层；长按菜单那一项保留。
c "导出/导入都能从会话管理那一层到达" \
  "grep -q '导出全部' \$ACT && grep -q '导入记录文件' \$ACT && \
   grep -q 'setPositiveButton(\"导入\")' \$ACT"
EXP=$(body 'private fun exportSessions' "$ACT")
c "导出不受界面状态影响（纯读，不读筛选/搜索状态）" \
  "printf '%s' \"\$EXP\" | nocomment | grep -q 'SessionStore.exportAll(this)'"
c "导出失败的路径有提示（不静默丢）" \
  "printf '%s' \"\$EXP\" | grep -q '导出失败'"

echo ""
if [ "$bad" = "0" ]; then echo "=== 聊天记录导入/导出守卫：PASS $ok / FAIL 0 ==="; exit 0; fi
echo "=== 聊天记录导入/导出守卫：PASS $ok / FAIL $bad ==="; exit 1
