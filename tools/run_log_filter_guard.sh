#!/bin/sh
# 「日志页筛选」的源码级守卫：**标签多选** + **任意关键字**。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# 这个特性本身**没有任何异常、没有任何 HTTP 响应**，全是界面上的取数口径。
# 它最危险的坏法不是"点了没反应"，而是**看起来正常但取数口径错了**：
#
#   ① 在**渲染后的 120 行**上过滤 —— ring 是 2000 行，某标签的行完全可能整段
#      落在窗口之外，此时页面是**空白**；而日志页的空白语义是"没记日志"，
#      用户会去查"为什么没打日志"，实际日志写得好好的。这是本仓库
#      `0.9.70`（「开了探针，日志页不显示」）那一族故障的同一形态。
#   ② 筛选渗进**导出/崩溃现场** —— 点「导出本次」拿到的是"当前筛出来的几行"，
#      而导出的场景恰恰是"页面看不全要拿给别人看"；渗进崩溃现场更坏：
#      `crash_last.txt` 会在筛出来恰好没命中时变成空文件，而现场只有一次机会。
#   ③ 判据用 `contains(标签)` —— 正文里出现 `[内存]` 四个字（用户就在问这个）
#      会被当成读数捞进来；过滤结果里混进无关行**没有任何症状**。
#   ④ 筛完空白页**不说为什么** —— 与"日志坏了"不可区分。
#   ⑤ 清空只清行、不清口径 —— 下次进日志页看到"筛选项还在、一行都没有"。
#   ⑥（多选引入的）**空集合被当成"放行 0 行"** —— 界面上"一个 chip 都没选"
#      最自然的读法是"全部"，写成"全部不放行"就是一张空白页。
#   ⑦（多选引入的）chip 点击写成**换档**（单选残留）—— 点第二个档会顶掉第一个，
#      而"多选"这个词在界面上看不出来：用户只会觉得"点了计时，HTP 那档没了"。
#   ⑧（关键字引入的）关键字**没 trim** / 参与了标签判据 —— 前者让"搜两个空格"
#      变成筛掉所有不含空格的行（说明行还会说"筛 含「  」"），
#      后者让关键字搜到标签文字时把别的档的行也带出来。
#
# 所以钉的是**结构与同源**，不是"界面上出现过 chip"：
#   ① 判据与标签在同一个文件里（`LogFileStore.LogFilter` 直接用那几个 TAG 常量）；
#   ② 筛选发生在 ring 层（`LlmEngine.recentLogs` 内先筛后取尾）；
#   ③ 导出与崩溃现场走**不过滤**的取数；
#   ④ 空白页有话说（说清筛了什么、共多少行、怎么回全部），且清空会把口径一起清掉；
#   ⑤ 两维（档位集合 / 关键字）**一次写完**，界面里没有第二个写点。
#
# 运行：sh tools/run_log_filter_guard.sh
set -e
cd "$(dirname "$0")/.."
LFS=app/src/main/java/com/xiaowan/localinference/LogFileStore.kt
ENG=app/src/main/java/com/xiaowan/localinference/LlmEngine.kt
ACT=app/src/main/java/com/xiaowan/localinference/EngineActivity.kt
APP=app/src/main/java/com/xiaowan/localinference/App.kt

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "LogFileStore.kt 存在" "[ -f \$LFS ]"
c "LlmEngine.kt 存在"    "[ -f \$ENG ]"
c "EngineActivity.kt 存在" "[ -f \$ACT ]"

# ── 1) 判据与标签**同源**：过滤直接引用 TAG_* 常量，不另抄一份字面量 ────────
# 抄一份字面量（`"[内存]"`）在今天是等价的，改标签那天就开始静默取不到数 ——
# 而"取不到数"的表现正好是空白页。所以要求判据体里引用常量。
c "LogFilter 定义在 LogFileStore 内（与标签同一文件）" \
  "grep -q 'object LogFilter {' \$LFS"
c "「计时」档用 TAG_TIME 常量判"   "grep -q 'TIME in keys && l.startsWith(TAG_TIME)' \$LFS"
c "「内存」档用 TAG_MEM 常量判"    "grep -q 'MEM in keys && l.startsWith(TAG_MEM)' \$LFS"
c "「HTP」档同时覆盖参与与实测两个标签" \
  "grep -q 'HTP in keys && (l.startsWith(TAG_HTP) || l.startsWith(TAG_HTP_MEASURED))' \$LFS"
# 上面三条钉的是"判据里引用了常量"。但**引用常量**与"**只**引用常量"是两回事：
# 有人在分支里补一句 `|| l.startsWith("[内存]")`，前三条照旧全绿，而那一支就是
# 本仓库最擅长记的静默失真（正文里提到标签也被捞进来）。所以这里要求
# labelMatch 体内**一个标签字面量都不许出现** —— 它只该经由 Line.startsWith 比较常量。
#
# ⚠ 这条判据自己差点踩恒真的坑：第一版写成"`startsWith(\"[[` 是否出现"（而被禁形态
# 是 `startsWith(\"[` + 标签），多一个方括号就**永不命中** —— 而"永不命中的恒静默"
# 正是本轮要防的东西。现在逐个标签字面量点名，并由自测（桩②）确认它真的会红。
# isError 里的 `[错误]` 是失败标记、不在本判据内：它不是取数标签、没有对应常量，
# 把它一起禁掉只会逼人写假判据。
c "labelMatch 体内不出现任何标签字面量（引用常量的同时也不许旁路）" \
  "python3 - \$LFS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun labelMatch(')
j = s.index('\n        }', i)
sys.exit(1 if 'startsWith(\"[[' in s[i:j] or 'startsWith(\"[计' in s[i:j]
         or 'startsWith(\"[内' in s[i:j] or 'startsWith(\"[H' in s[i:j] else 0)
PY"

# ── 2) 判据是**行首**匹配，不是 contains ───────────────────────────────────
# `contains` 会把正文里提到标签的行一起捞进来。这是"看起来能用"的写法，
# 也正是本仓库最擅长记的那类静默失真。
c "标签判据走 Line 的行首形态（startsWith），不复用原串" \
  "grep -q 'fun startsWith(prefix: String) = t.startsWith(prefix)' \$LFS"
c "Line 的 t 是 trimStart 后的形态（行首带缩进的标签也算命中）" \
  "grep -q 'fun of(line: String) = Line(line, line.trimStart())' \$LFS"
c "matches/labelMatch 里不出现 line.contains( 当标签判据" \
  "python3 - \$LFS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun matches(keys: Set<String>, line: String)')
j = s.index('\n        /**', i)
sys.exit(1 if 'line.contains(TAG' in s[i:j] else 0)
PY"
c "四档表 labels 就是那四个档（不含「全部」：空集合即全部）" \
  "grep -q 'val labels = listOf(TIME, MEM, HTP, ERR)' \$LFS"

# ── 2b) 多选语义：空集合 = 全部，「或」聚合，「仅错误」单独收口 ──────────────
# 这三条是多选真正的坑，且**每一条的症状都是一张看不出是 bug 的空白页**。
c "标签多选是「或」：三支判据并列、命中任一即放行" \
  "python3 - \$LFS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun labelMatch(')
j = s.index('\n        }', i)
b = s[i:j]
sys.exit(0 if b.count('in keys &&') == 3 and ' || ' in b else 1)
PY"
c "空集合不参与标签这一维（不是「全部不放行」）" \
  "grep -q 'val byLabel = if (!anyLabel(keys)) true else labelMatch(keys, l)' \$LFS"
c "anyLabel 把「仅错误」排除在标签之外（它不是标签，是"这行是不是一次失败"）" \
  "grep -q 'fun anyLabel(keys: Set<String>) = keys.any { it != ERR }' \$LFS"
c "「仅错误」与其它档叠加时是**收窄**（与），单独选时不看标签" \
  "grep -q 'return byLabel && (!errorOnly(keys) || isError(l))' \$LFS"
# ⚠ 半：`⚠` 必须**行内**都算。真实失败行长这样：`[repack] ⚠ …`、`[日志] ⚠ …`
#（标签在前、⚠ 在后）。若也要求它在行首，"仅错误"在本仓库几乎永远筛不出东西 ——
# 而"筛不出东西"与"确实没出错"同形，用户会得出"没出过错"的结论。
c "「仅错误」认行内的 ⚠（真实失败行是「标签在前、⚠ 在后」）" \
  "grep -q 'l.line.contains(WARN_MARK)' \$LFS"
c "⚠ 符号只在一处定义（不散落字面量）" \
  "grep -q 'private const val WARN_MARK = \"⚠\"' \$LFS"
# 方括号标记半：只认行首。行内查找会把"解释一下什么是 [错误] 码"这类正常输出算成错误行。
c "方括号错误标记只认行首（行内查找会把正文误判成错误行）" \
  "grep -q 'FALLBACK_ERR.any { l.startsWith(it) }' \$LFS"
c "「仅错误」不认汉字"失败/错误"（正文里随时会出现）" \
  "! grep -q 'contains(\"失败\")' \$LFS && ! grep -q 'contains(\"错误\")' \$LFS"
c "errorOnly 是「仅错误」的唯一判据（不在这里再抄一次 ERR 字面量）" \
  "grep -q 'fun errorOnly(keys: Set<String>) = ERR in keys' \$LFS"

# ── 2c) 关键字：子串、大小写不敏感、空即不筛 ───────────────────────────────
c "关键字判据是子串且大小写不敏感" \
  "grep -q 'line.contains(keyword, ignoreCase = true)' \$LFS"
c "空关键字不筛（否则"没打关键字"变成"什么都不命中"）" \
  "grep -q 'if (keyword.isEmpty()) return true' \$LFS"
c "关键字**不参与**标签判据（只在 keywordMatches 里用）" \
  "python3 - \$LFS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun matches(keys: Set<String>, line: String)')
j = s.index('fun keywordMatches(', i)
sys.exit(1 if 'keyword' in s[i:j] else 0)
PY"
# 关键字的作用域是**整行**（正文），不是行首形态 —— 用户要搜的就是正文里那句话。
c "关键字搜的是整行原文（不是 trimStart 后的形态）" \
  "grep -q 'fun keywordMatches(keyword: String, line: String)' \$LFS"

# ── 3) 筛选发生在 **ring 层**：先筛后取尾 ─────────────────────────────────
# 判据必须锚在 recentLogs 的**函数体**里，全文 grep 会在别处恒真。
# ⚠ 这条判据差点写成"体内出现过 logFilterKeys / logRing.filter / keywordMatches"
# 三个字符串。那会踩**恒真**的坑：桩③（把分支改成 `if (true)`，筛选整段成了死代码）
# 里这三个字符串一个都没少 —— 判据全绿，而功能已经没了。
# 所以判据锚的是**筛选那次取数的形状本身**：
#   · 入口条件必须同时看两维（空集合/空关键字才走全量）；
#   · `logRing.filter` 必须挂在 else 支上（也就是"在筛"的那一支）。
c "LlmEngine.recentLogs 只在两维都空时走全量、否则按口径筛 ring" \
  "python3 - \$ENG <<'PY'
import sys, re
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun recentLogs(): List<String>')
j = s.index('\n    }', i)
body = s[i:j]
# 只用行首（缩进后）的语句做判据，避免注释里提到同样字样就恒真
code = [l.strip() for l in body.splitlines()
        if l.strip() and not l.strip().startswith('//')
        and not l.strip().startswith('*') and not l.strip().startswith('/*')]
gate = 'if (keys.isEmpty() && kw.isEmpty()) logRing.toList()'
ok = (gate in code
      and 'else logRing.filter {' in code
      and 'LogFileStore.LogFilter.matches(keys, it) &&' in code
      and 'LogFileStore.LogFilter.keywordMatches(kw, it)' in code)
sys.exit(0 if ok else 1)
PY"
c "过滤后仍取 tail（先筛后取尾的顺序写在渲染侧）" \
  "grep -q 'ring 里的最后 \[LOG_VIEW_LINES\] 行' \$ACT"
# 最坏写法：在渲染出的 120 行上再过滤。这种写法在源码里长得像"多筛一层"，
# 但它必须不存在于 renderLog 的正文里。
c "不在渲染结果（tail 字符串）上再过滤一遍" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun renderLog()')
j = s.index('\n    }', i)
body = s[i:j]
bad = ('tail.split' in body) or ('tail.lines' in body) or ('.filter' in body)
sys.exit(1 if bad else 0)
PY"
c "「在筛」的判据取自 LlmEngine 那一份口径（不是本地字段，防两处口径打架）" \
  "grep -q 'val filtered = LlmEngine.logFilterActive()' \$ACT"

# ── 4) 导出与崩溃现场**不过滤** ────────────────────────────────────────────
# 这两条是同一个不变式："筛选是界面上的视图开关，不是数据的属性"。
c "LlmEngine 另有一个不过滤的取数口 recentLogsUnfiltered" \
  "grep -q 'fun recentLogsUnfiltered(): List<String>' \$ENG"
c "「导出本次」的兜底取数用不过滤的那一个" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('val logExportBtn = btn(')
j = s.index('val logPrevBtn = btn(', i)
sys.exit(0 if 'recentLogsUnfiltered()' in s[i:j] else 1)
PY"
c "「导出上次会话」的本次兜底也用不过滤的那一个" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('val logPrevBtn = btn(')
j = s.index('logProbeRow.addView(logProbeBtn', i)
sys.exit(0 if 'recentLogsUnfiltered()' in s[i:j] else 1)
PY"
c "崩溃现场（App.kt 的 crash dump）用不过滤的那一个" \
  "grep -q 'recentLogsUnfiltered()' \$APP"
# 上面三条都是"用了对的函数"，还必须防它们**同时**又留着旧的过滤口 ——
# 那说明有一条通路没改干净（导出里过滤与不过滤各来一遍，读数就成了两套）。
c "导出/崩溃路径里不出现带过滤的 recentLogs()" \
  "! grep -qE 'recentLogs\\(\\)' \$APP"
c "「导出本次/上次」那两段里也不出现带过滤的 recentLogs()" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('val logExportBtn = btn(')
j = s.index('logProbeRow.addView(logProbeBtn', i)
sys.exit(1 if 'recentLogs()' in s[i:j] else 0)
PY"

# ── 5) 空白页要把话说出来（与"日志坏了"区分开）────────────────────────────
c "有空白页提示的构造函数（两维 + 命中数 + 总数）" \
  "grep -q 'fun emptyHint(keys: Set<String>, keyword: String, hit: Int, total: Int): String' \$LFS"
c "提示区分「空日志」与「筛出来没命中」两件事" \
  "grep -q '日志为空：还没有写入任何日志行' \$LFS && grep -q '没有同时满足' \$LFS"
c "提示给出口径（筛了什么）与出口（清掉筛选）" \
  "grep -q '清掉筛选或关键字看全部' \$LFS"
c "口径描述含两维（档位 + 关键字）" \
  "grep -q 'fun describe(keys: Set<String>, keyword: String): String' \$LFS"
c "ring 原始行数与命中数是两个口（否则说不清"这档没有"与"日志本来就空"）" \
  "grep -q 'fun ringSize(): Int' \$ENG"
c "渲染侧真的调用了 emptyHint（不是定义了没人用）" \
  "grep -q 'LogFileStore.LogFilter.emptyHint(keys, kw, total, ringAll)' \$ACT"
c "说明行也说清"筛的是什么"（只有命中数等于让人猜）" \
  "grep -q '筛 \$what：命中 \$total 行' \$ACT"

# ── 6) 清空要把口径一起清掉 ───────────────────────────────────────────────
c "clearLogs 体内同时复位两维口径" \
  "python3 - \$ENG <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('fun clearLogs()')
j = s.index('\n    }', i)
b = s[i:j]
sys.exit(0 if ('logFilterKeys = emptySet()' in b and 'logFilterKeyword = \"\"' in b) else 1)
PY"

# ── 7) 界面：chip 由 labels 生成、点击是**取反**、口径只有一个写点 ─────────
c "chip 由 LogFilter.labels 循环生成（不写死四个）" \
  "grep -q 'for (label in LogFileStore.LogFilter.labels)' \$ACT"
c "chip 点击走 toggleLogFilterKey（多选取反，不是换档）" \
  "grep -q 'setOnClickListener { toggleLogFilterKey(label) }' \$ACT"
c "toggle 是**取反**集合（读到就删、读不到就加）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun toggleLogFilterKey(label: String)')
j = s.index('\n    }', i)
b = s[i:j]
sys.exit(0 if ('logFilterKeys.remove(label)' in b and 'logFilterKeys.add(label)' in b) else 1)
PY"
c "点击 chip 与关键字变化都收敛到 applyLogFilter（口径唯一写点）" \
  "grep -q 'override fun afterTextChanged(s: android.text.Editable?) = applyLogFilter()' \$ACT"
c "口径**两维一次写完**（不写成两次赋值，中间那一瞬间是半截口径）" \
  "grep -q 'LlmEngine.setLogFilter(logFilterKeys, kw)' \$ACT"
c "界面里没有第二个写口径的地方（LlmEngine.setLogFilter 只出现一次）" \
  "[ \$(grep -c 'LlmEngine.setLogFilter(' \$ACT) -eq 1 ]"
c "关键字统一 trim 后再进判据（否则"搜两个空格"会筛掉所有正常行）" \
  "grep -q 'keywordEt.text?.toString().orEmpty().trim().take(KEYWORD_MAX)' \$ACT"
c "筛完复位贴底标志（否则最新命中的行不在视野里）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('private fun applyLogFilter()')
j = s.index('\n    }', i)
sys.exit(0 if 'logFollowBottom = true' in s[i:j] else 1)
PY"
c "口径写 prefs（退出重进沿用）" \
  "grep -q 'putStringSet(LOG_FILTER_KEY, HashSet(logFilterKeys))' \$ACT && grep -q 'putString(LOG_KEYWORD_KEY, kw)' \$ACT"
c "进页面时读回档位，且读回的值必须在 labels 里（防脏值造成整页空白）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('.getStringSet(LOG_FILTER_KEY')
j = s.index('\n', i)
sys.exit(0 if 'takeIf' in s[i:i+240] or 'filter { LogFileStore.LogFilter.labels.contains(it) }' in s[i:i+240] else 1)
PY"
c "进页面时读回关键字，且截到 KEYWORD_MAX（防脏值把每帧比较拖慢）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('.getString(LOG_KEYWORD_KEY')
j = s.index('\n', i)
sys.exit(0 if 'take(KEYWORD_MAX)' in s[i:i+160] else 1)
PY"
c "关键字框的 TextWatcher 是"输入即筛"（没有额外的搜索按钮）" \
  "! grep -q '搜索按钮\|onClick.*searchLogs' \$ACT"

# ── 正文与说明行必须同一帧自洽（D-3）────────────────────────────────────────
# 过滤态下此前是 `if (filtered) { if (justSwitchedTab) logTv.text = tail }` ——
# 只在"切页/换档"那一帧写正文，而说明行**每次重算**。于是稳态一来新行，页面就
# 开始说假话：正文停在旧快照（2 行），说明行说"命中 3 行"。最坏的一支是命中数
# 0 -> 正 时正文不重写 —— 用户看到"命中 3 行"、下面一行都没有，而
# `LogFilter.emptyHint` 存在的第 2 条理由就是"区分不了这该没有还是 ring 本来就空"。
#
# 判据锚**结构关系**，不锚"出现过 logTv.text"（旧代码也"写过"它）：
#   · 过滤态的写正文必须由"内容变了没"决定（有上次快照的字段与比对）；
#   · 旧写法（只看 justSwitchedTab）必须绝迹；
#   · "不每帧重排"这个目的本身也要在（内容没变就不写）—— 否则修 D-3 会把
#     "别清掉用户的文本选择"那条理由一起改掉。
c "过滤态写正文的判据是"内容变了没"（有上次快照字段）" \
  "grep -q 'private var logLastBody' \$ACT && grep -q 'logLastBody != tail' \$ACT"
c "旧写法（过滤态只看 justSwitchedTab 才写正文）必须绝迹（不看注释行）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
bad = [l for l in s.splitlines() if 'if (justSwitchedTab) logTv.text = tail' in l
       and not l.lstrip().startswith('//')]
sys.exit(1 if bad else 0)
PY"
c "不过滤时照旧每帧写（这条行为没被改坏）" \
  "python3 - \$ACT <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('val tail = lines.takeLast(shown).joinToString')
j = s.index('if (curTab != 3) return', i)
blk = s[i:j]
sys.exit(0 if '} else {' in blk and 'logTv.text = tail' in blk else 1)
PY"
c "命中 0 -> 正 时正文会重写（空 != 非空，快照比对必然触发）" \
  "grep -q 'if (justSwitchedTab || logLastBody != tail)' \$ACT"

if [ "$bad" -eq 0 ]; then
    echo "=== 日志页筛选守卫（多选 + 关键字）全部通过（$ok 条）==="
    exit 0
else
    echo "=== 日志页筛选守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
