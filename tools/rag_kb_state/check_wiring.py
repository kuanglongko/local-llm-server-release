#!/usr/bin/env python3
"""「知识库状态」（存在 / 待重建 / 未落盘）与「库名框提交」的接线判据（ISSUE #259 第二批）。

═══════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════
用户一次报了五条，全部来自**同一个根**：知识库页上有**五套互不相同的"这个库是什么状态"**
判据，它们各自回答不同的问句，而屏幕上只有一个答案：

  ① *"点击已存在的库 test，然后在库名编辑处修改库名为 test1，点击确定，
     库状态显示新建了库 test1，**改名不生效**"*；
  ② *"输入 test2，点击建库，库状态位置会出现 test2 新库，**有短暂延迟**"*；
  ③ *"…点击删索引（连正文一起删），库直接被删了；…点击删库（连同索引与正文），
     库直接被删了；**那么这俩按钮有啥区别**"*；
  ④ *"点击建索引…我切换页面后，再回到知识库页面，**建索引提示丢失**，
     建库提示位置显示『⚠ 该库不存在（还没落盘任何东西）』『⚠ 正文框里只是头部』"*；
  ⑤ *"建库时我退出了 app…建了一半的库因为 app 退出没索引，此时我不想建库，
     那么我**没有任何操作手段删库，按钮全部不可用**"*。

五条各自对应一个**静默**的失效形态（不崩、不报错、界面照旧"成功"）：

  · 库名框里的字面值被当成"当前库" -> 覆盖掉真值。用户改了名还没提交，
    而屏幕已经在说"新建了库 testX / 该库不存在"（他报的 ① 与 ④）；
  · 那个覆盖还会**污染导入的目标库**（文件选择器让库名框失焦 -> 覆盖 ->
    用户从选择器回来，落盘落在了另一个库上）；
  · 「未落盘」与「待重建」用同一句话 -> 用户在一个 0 条的库上把每个按钮点一遍，
    而屏幕上每个字都说"点重建"（他问的 ③）；
  · 删除按钮的判据是"有没有索引 / 有没有正文文件" -> 一个"建库被杀进程打断"的库
    （有目录、没索引）进得了列表，而**每个按钮都是灰的**（他报的 ⑤）；
  · 两个删除按钮的分工（留目录 / 连目录）在 0 条的库上**没有任何可见差别**，
    而确认框里一个字都不说（他问的 ③）。

判据一律锚**结构关系**（哪个函数读哪个真值 / 按钮判据里有哪几个量 /
两个删除函数各自做了什么），不锚"某某字符串出现过"（后者恒真：注释里就有）。
用法：python3 tools/rag_kb_state/check_wiring.py <EngineActivity.kt> <RagIndexStore.kt>
"""
import re
import sys


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def fnbody_plain(src, sig):
    """朴素版：数最外层大括号。对**函数体是 `{}`** 的函数是对的。"""
    i = src.find(sig)
    if i < 0:
        return None
    j = src.find("{", i)
    if j < 0:
        return None
    depth = 0
    k = j
    in_str = None
    while k < len(src):
        c = src[k]
        if in_str:
            if c == "\\":
                k += 2
                continue
            if c == in_str:
                in_str = None
        elif c == '"':
            in_str = '"'
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[i:k + 1]
        k += 1
    return None


def fnbody(src, sig):
    """取一个函数/属性**从签名到它真正结束**的那一段（认字符串与嵌套括号）。

    ⚠ `list()` 是**表达式体函数**（`fun list(ctx: Context): List<Info> =
    rootDir(ctx).listFiles { ... }?.mapNotNull { ... }`），体内有**多个** lambda 的
    `{}`。只数最外层大括号的朴素实现会在第一个 lambda 的 `}` 处收工 —— 实测返回
    90 个字符，于是"两把尺子逐字同源"这条判据**永远 FAIL 而源码是对的**。

    所以这里数 `(){}[]` 三种深度，归零之后再看到**行首的 `}` 或下一个成员声明**才收工。
    （这一份与 tools/rag_kb_name/check_wiring.py 里那个同名函数是同一套实现 ——
      两处都只是守卫自己的基础设施，不是被守的源码；真要合并得先有一个共享模块，
      而本仓库的离线守卫一律"一个套件一个目录"，故保持两份。）
    """
    i = src.find(sig)
    if i < 0:
        return None
    j_brace = src.find("{", i)
    j_eq = src.find("=", i)
    starts = [x for x in (j_brace, j_eq) if x > 0]
    if not starts:
        return None
    k = min(starts)
    if k == j_eq:
        prev = src[k - 1] if k > 0 else " "
        nxt = src[k + 1] if k + 1 < len(src) else " "
        if prev in "=!<>" or nxt == "=":
            k = j_brace
    depth = 0
    in_str = None
    while k < len(src):
        c = src[k]
        if in_str:
            if c == "\\":
                k += 2
                continue
            if c == in_str:
                in_str = None
        elif c == '"':
            in_str = '"'
        elif c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
            if depth == 0:
                rest = src[k + 1:]
                if re.match(r"[ \t]*\n\s*\}", rest):
                    return src[i:k + 1]
                m = re.match(r"[ \t]*\n(\s*)\S", rest)
                if m and (len(m.group(1)) <= 4 or re.match(
                        r"[ \t]*\n\s*(?:@|private|public|internal|fun|val|var|/\*)", rest)):
                    return src[i:k + 1]
        k += 1
    return None


def strip_comments(s):
    """剥掉注释，**认字符串字面量**。

    不能直接用 `re.sub(r"/\\*.*?\\*/", ...)`：这份源码里 `"*/*"` / `"text/*"`
    是**字面量**，它们里面的 `/*` 会被当成块注释开头，一口气吞掉几万字符真实代码
    （实测在 `check_wiring.py` 上吞掉 64 KB，症状是判据报"按钮不存在" ——
    一个看起来像源码错了的假红，见 tools/rag_kb_name/check_wiring.py 的同一段）。
    """
    out = []
    i = 0
    n = len(s)
    state = None          # None / '"' / 'line' / 'block'
    while i < n:
        c = s[i]
        if state is None:
            if c == '"':
                state = '"'
                out.append(c)
                i += 1
            elif c == '/' and i + 1 < n and s[i + 1] == '/':
                state = 'line'
                i += 2
            elif c == '/' and i + 1 < n and s[i + 1] == '*':
                state = 'block'
                i += 2
            else:
                out.append(c)
                i += 1
        elif state == '"':
            if c == '\\' and i + 1 < n:
                out.append(s[i:i + 2])
                i += 2
                continue
            if c == '"':
                state = None
            out.append(c)
            i += 1
        elif state == 'line':
            if c == '\n':
                state = None
                out.append(c)
            i += 1
        else:  # block
            if c == '*' and i + 1 < n and s[i + 1] == '/':
                state = None
                i += 2
            else:
                i += 1
    return "".join(out)


def main():
    act_p, store_p = sys.argv[1], sys.argv[2]
    # 第三参可选：RagResumeUi.kt（列表状态措辞的纯逻辑层）。本轮把
    # "未落盘 / 待重建 / 可续 / 正文已改 / 齐整" 五档收进了它 —— 于是
    # "『未落盘』与『待重建』分开说"这条判据的**真值**从 EngineActivity
    # 搬到了那个文件里。不传时跳过那一档（老调用点仍可用）。
    ui_p = sys.argv[3] if len(sys.argv) > 3 else None
    act = read(act_p)
    store = read(store_p)
    act_c = strip_comments(act)
    store_c = strip_comments(store)
    ui_c = strip_comments(read(ui_p)) if ui_p else ""
    bad = []
    ok = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        (ok if cond else bad).append(what)

    # ═══ ① 库名框里的字面值**不是**当前库 ═══════════════════════════════
    # `renderRagStatus` 是屏幕上"这个库现在是什么状态"的唯一出口。它若按输入框的
    # 字面值取数，用户"改了名还没提交"那一刻屏幕上就会说"新建了库 testX /
    # 该库不存在"—— 而他手里那个库好好的（ISSUE #259 第 ①④ 条）。
    rs = fnbody(act_c, "private fun renderRagStatus(")
    need(rs is not None, "renderRagStatus() 存在（锚点未漂）")
    need("RagInject.currentKb(this)" in rs,
         "① 状态行读的是**已提交的真值**（`RagInject.currentKb`），不是输入框里的字面值")
    need("ragKbEt.text" not in rs,
         "① 状态行**不许**读库名框里的字面值（用户改了名还没提交时它就是假的）")

    # 「磁盘上有没有这个库」只能有一个口径：`RagIndexStore.isPresent`
    #（与 `list()` 那条判据逐字同源）。自己拼 `texts.jsonl` / `index.bin` 的
    # 路会与列表分家，症状正是"列表里看得见、状态行说它不存在"。
    need("RagIndexStore.isPresent(this, kb)" in rs,
         "④ 状态行的『库在不在』问 `isPresent`（与库列表同一把尺子）")
    need("indexFile(this, kb)?.isFile" in rs,
         "④ 状态行里只有『有没有索引』那一问才直接看 index.bin（那是另一个问句）")
    need(re.search(r"RagIndexStore\.textsFile\(this, kb\)", rs) is None,
         "④ 状态行**不许**自己去看 texts.jsonl（那把尺子与 list() 会分家）")

    # ── 弹系统文件选择器之前必须**冻结**导入的目标库 ────────────────────
    # 选择器是另一个 App：它出现的过程中库名框会失焦，而失焦那条路会覆盖
    # `currentKb`。不冻结 -> 落盘落在用户打了一半的那个名字上（静默建新库）。
    for sig, label in (("private fun pickRagArchiveFile(", "导入库"),
                       ("private fun pickRagTextFile(", "导入文档")):
        b = fnbody(act_c, sig)
        need(b is not None, f"① {label} 的入口存在（锚点未漂）")
        need("freezeImportTarget" in (b or ""),
             f"① 「{label}」在**弹选择器之前**冻结目标库（失焦会覆盖 currentKb）")
    fr = fnbody(act_c, "private fun freezeImportTarget(")
    need(fr is not None, "freezeImportTarget() 存在（锚点未漂）")
    need("RagInject.currentKb(this)" in (fr or ""),
         "① 冻结的是**持久化真值**（不是输入框里的半成品名字）")
    need("ragKbEt.text" in (fr or ""),
         "① 冻结时把『输入框里的名字 ≠ 当前库』这件事**说出来**（不替用户选）")
    ci = fnbody(act_c, "private fun confirmRagImport(")
    need(ci is not None, "confirmRagImport() 存在（锚点未漂）")
    need("val cur = ragImportTarget" in (ci or ""),
         "① 确认框的目标库用**冻结的那份**，不读此刻的 currentKb")

    # ═══ ② 「未落盘」与「待重建」必须是两个状态 ═════════════════════════
    # 它们在屏幕上是同一行字，而处置**相反**：一个"点重建就能救回来"、
    # 一个"里面一条内容都没有"。合成一句的后果就是用户报的第 ③ 条。
    info = store_c[store_c.find("data class Info("):]
    info = info[:info.find("\n\n")]
    need("val isPending: Boolean" in info,
         "② Info 里带 `isPending`（『磁盘上有没有它』必须是一个**独立**字段）")
    need("val onDisk: Boolean" in info or "val deletable: Boolean" in info,
         "②『磁盘上有东西吗』有名字（按钮判据靠它，不靠『有没有索引』）")

    ln = fnbody(store_c, "fun list(")
    need(ln is not None, "list() 存在（锚点未漂）")
    need("isPending" not in ln,
         "② list() 只列**磁盘上真有的**库（pending 由 UI 层另画，不进这张表）")
    need("hasIndex && textsInSync(" in ln,
         "② 列表仍然把 `hasIndex` / `inSync` 分开给（三态必须分得开）")

    rl = fnbody(act_c, "private fun renderRagList(")
    need(rl is not None, "renderRagList() 存在（锚点未漂）")
    # ⚠ 判据要**锚定表达式**，不能只写 `r.isPending`：那个字符串在同一个函数体里
    #   还出现在颜色分支（`r.isPending -> 蓝`）上，只写它的话**把状态那一档整支
    #   删掉守卫照样绿**（桩⑤ 当场抓到过一次，所以这里收紧了）。
    #
    # ⚠⚠ 本轮判据**换了锚点**：五档状态（未落盘/待重建/可续/正文已改/齐整）
    #    收进了 `RagResumeUi.state(...)`（纯逻辑，宿主单测钉得住）。于是
    #    "『未落盘』与『待重建』分开说"这条真值一分为二，两半**都要**钉住，
    #    合成一处又会让"整支删掉"重新变成静默：
    #      · 收方：renderRagList 把 `r.isPending` **喂给** RagResumeUi.state；
    #      · 发方：RagResumeUi.state 里 `isPending ->` 是**独立且最前**的一档。
    need(re.search(r"isPending\s*=\s*r\.isPending", rl) is not None,
         "② 库列表把 `r.isPending` 喂给 RagResumeUi.state（状态判据同源）")
    need("RagResumeUi.state(" in rl,
         "② 库列表的状态走 RagResumeUi.state（纯逻辑一处，不在这里另写一串 when）")
    if ui_c:
        # `isPending ->` 必须在**第一个** when 分支（排在"没有索引"之前）——
        # 落到后面时，"未落盘"的库会被先说成"待重建"（正是 ISSUE #259 第 ③ 条）。
        m = re.search(r"\bwhen\s*\{([\s\S]*?)\n\s*\}", ui_c)
        body = m.group(1) if m else ""
        ip_pos = body.find("isPending ->")
        ni_pos = body.find("!hasIndex ->")
        need(ip_pos >= 0 and ni_pos >= 0 and ip_pos < ni_pos,
             "② RagResumeUi 里 `isPending ->` 是独立且**最前**的一档（与『待重建』分开说）")
    # 同一个名字不许出现两行：落盘完成之前是"未落盘"那一行、之后是真库那一行，
    # 而 `ragPendingKb` 要等后台刷新才清 —— 中间那一段就是用户看到的"短暂延迟"。
    need("shown.none { it.name == ragPendingKb }" in rl,
         "② 磁盘上已经有了这个库时**不再画**『未落盘』那一行（否则同名两行打架）")

    # ═══ ③ 两个删除按钮：分工与判据 ═════════════════════════════════════
    dd = fnbody(act_c, "private fun doDeleteKb(")
    dd_c = dd or ""
    need(dd is not None, "doDeleteKb() 存在（锚点未漂）")
    need("RagIndexStore.isPresent(this, name)" in dd_c,
         "③ 删库的判据是『**库在不在**』，不是『索引在不在』（否则半成品库删不掉）")
    need("indexFile(this, name)" not in dd_c,
         "③ 删库这条路上不许出现按索引判的可点性（那是「删索引」的问句）")
    need("entries > 0" in dd_c,
         "③ 确认框按条数分两句说（0 条的库上『俩按钮有啥区别』必须当场回答）")
    dp = fnbody(act_c, "private fun doDropIndex(")
    dp_c = dp or ""
    need(dp is not None, "doDropIndex() 存在（锚点未漂）")
    need("RagIndexStore.drop(this, name)" in dp_c,
         "③「删索引」走 `drop`（留目录）——与「删库」的 `delete` 是两个动作")
    need("if (!ok)" in dp_c,
         "③「删索引」读返回值并如实说（『已删除』而磁盘上还在 = 用户下一次扑空）")
    need("本来就没有" in dp_c,
         "③「删索引」说清**剩下什么 / 本来有没有**（0 条时说『这次什么也没删』）")
    # ⚠ 判据要锚**"读它的返回值"这个结构**（`val ok = … drop(…)`），不能只写
    #   "有 hadIndex 这个变量"：一个声明了却从不用于判定的变量照样满足后者
    #   （桩⑧ 当场抓到过一次 —— 它把两行取值删掉，守卫照样绿）。
    need(re.search(r"val ok = runCatching \{ RagIndexStore\.drop\(this, name\) \}"
                   r"\.getOrDefault\(false\)", dp_c) is not None,
         "③「删索引」读 `drop` 的**返回值**（不读就永远说『已删除』，而磁盘上还在）")
    need("hadIndex" in dp_c,
         "③ 删除前的状态留着（说清『本来有没有索引』——删完再问就永远是 false）")
    # ⚠⚠ 本轮**反向**（ISSUE #259 第 ② 条）：旧判据要求 `hadTexts` 出现
    #   （"说清正文一起删了"）。而现在 `drop` 根本不碰正文，`hadTexts` 已经没有
    #   意义 —— 继续要求它反而是"逼着 UI 去说一句关于正文的假话"。
    #   用户报的：*"删索引为啥要删正文"* -> 逻辑错了，行为已改（drop 只删索引）。
    #   所以这里改成钉"说过正文留着"：那句话是用户**唯一**能知道"内容没丢"的地方
    #   （不说的话，他会以为内容没了而去重导 —— 而导入是追加合并，重导 = 条目翻倍）。
    need("hadTexts" not in dp_c,
         "③「删索引」这条路上不再出现 `hadTexts`（它不碰正文 —— "
         "出现就说明有人又把正文删回去了）")
    need("正文还在" in dp_c,
         "③「删索引」必须说清**正文还在**（不说的话用户以为内容丢了，去重导 = 条目翻倍）")

    # 按钮判据：可点性只看『库在不在』。这一条与上面 `isPresent` 那条是**两件事** ——
    # 这里钉的是 UI 侧真的把它接上了（判据写在别处而按钮用旧量 = 白改）。
    need("ragDropBtn.isEnabled = diskExists" in rs,
         "⑤「删索引」的可点性 = **库在磁盘上**（不是『有没有索引』）")
    need(re.search(r"ragDeleteBtn\.isEnabled = diskExists", rs) is not None,
         "⑤「删库」的可点性 = **库在磁盘上**（不是『有没有正文文件』）")
    need("hasTexts && !ragBuilding" not in rs,
         "⑤ 旧判据 `hasTexts && …` 已从按钮上撤掉（它正是『按钮全灰』的成因）")
    need("diskExists" in rs,
         "⑤ `diskExists` 这条判据真的被算出来了（锚点未漂）")

    # 状态行的"库不存在"那一句必须**指出出口**，而不是一句判决。
    need("点「新建库」开它" in rs or "点「确定」" in rs,
         "④『磁盘上没有这个库』那一句带出口（用户报了『该库不存在』之后无处可去）")

    # ═══ ④ 建索引被切页打断：提示不许被下一页覆盖 ═══════════════════════
    # 切到知识库页那一跳会 `renderRagStatus()` —— 而它跑在**建库进行中**时，
    # 会把 `["建索引/重建索引"] 正在建库…` 那一行整段盖掉（用户报的"提示丢失"）。
    tabs = fnbody(act_c, "fun selectTab(")
    need(tabs is not None, "selectTab() 存在（锚点未漂）")
    need("ragBuilding" in tabs,
         "④ 切到知识库页时，**建库进行中就不许重画状态行**（提示会被盖掉）")

    # ═══ ⑤ 两把尺子逐字同源（改一处而不改另一处，判据当场红）═════════════
    ip = fnbody(store_c, "fun isPresent(")
    need(ip is not None, "isPresent() 存在（锚点未漂）")
    need("File(d, FILE).isFile || File(d, TEXTS).isFile" in (ip or ""),
         "⑤ isPresent 的表达式 = 目录里有索引**或**正文")
    need("!hasIndex && !texts.isFile" in ln and "return@mapNotNull null" in ln,
         "⑤ list() 里是**同一句**判据（两把尺子只允许有一处写法）")

    # ⚠ 局部名**不能**沿用上面那个 `rl`（那是 `renderRagList`）—— 本轮实测：
    #   写成 `rl = fnbody(act_c, "private fun refreshRagList(")` 会把上一行那个
    #   绑定的名字**覆盖掉一半的判据**（⑥ 里两条用的是新值、⑦ 那几条用的是旧值），
    #   而症状是一条判据恒红、看起来像"源码里顺序写反了"。名字分开就不会再撞。
    frl = fnbody(act_c, "private fun refreshRagList(")
    need(frl is not None, "⑥ refreshRagList() 存在（锚点未漂）")

    # ═══ ⑥ 库列表分两层：名字先出，数字后补（ISSUE #228）═════════════════
    # 用户报的：*"点击一个 2500 条知识库…还是会触发卡住库列表，表现为**选中其他库，
    # 状态不切换**为其他库，一直卡在这个 2500 条的大库上"*。
    #
    # 那不是"选中状态没刷新"，是**点击压根没被派发**：主线程在做 O(全库字节数) 的活。
    # 而这条路上的每一处都在做：`list()` 为每个库读完整份正文、`renderRagList`
    # 逐库建 View、`renderRagStatus` 再数一次。库从 100 条长到 2500 条，
    # 每一步的代价跟着长 —— 所以"修过又回来了"，因为**触发它的乘积一直在**。
    #
    # 判据钉的是那个乘积被拆开的**结构**，不是"某个函数变快了"：
    #   · 列表先出**只读名字**的那一面（[RagIndexStore.liveInfos]，不读正文）；
    #   · 数字（条数/状态）由**后台**算完再补画，且**补画之前先写下"量过了"**；
    #   · 数字缺席时**一个数都不报**（不许回落到索引里的 `entries`：那个数在
    #     `保存正文` 之后会落后于正文 —— 报它 = 报一个过期数）。
    sk = fnbody(act_c, "private fun renderRagListSkeleton(")
    need(sk is not None, "⑥ renderRagListSkeleton() 存在（锚点未漂）")
    need("RagIndexStore.liveInfos(this)" in (sk or ""),
         "⑥ 库列表先出骨架：名字从 `liveInfos` 来（它只读目录元数据，不碰正文）")
    need("RagIndexStore.list(this)" not in (sk or ""),
         "⑥ 骨架那一跳**不许**调 `list()`（它逐库读正文 —— 那就还是原来那一下卡顿）")
    need("renderRagListSkeleton()" in (frl or "") and "loadRagListCounts()" in (frl or "")
         and frl.index("renderRagListSkeleton()") < frl.index("loadRagListCounts()"),
         "⑥ refreshRagList 先画骨架、再去后台补数字（顺序写反 = 先卡一下再出名字）")
    lc = fnbody(act_c, "private fun loadRagListCounts(")
    need(lc is not None, "⑥ loadRagListCounts() 存在（锚点未漂）")
    need("ioExecutor.execute" in (lc or "") and "RagIndexStore.list(this)" in (lc or ""),
         "⑥ 数字那一层在**后台**算（`list()` 逐库读正文 —— 放主线程就是那个卡顿本身）")
    need("ragListCounts =" in (lc or ""),
         "⑥ 算完先写下『这批数我量过了』（[ragListCounts]），再补画")
    need("renderRagList(" in (lc or ""),
         "⑥ 补画那一次真的画了同一张列表（只算不画 = 数字永远不出现）")
    # 数字缺席 -> 整段留白，而不是回落成 `r.entries`。
    need("val counts = ragListCounts[r.name]" in (rl or ""),
         "⑥ 那一行按**库名**取数（按下标取会把 A 的条数画到 B 的名字后面）")
    need(re.search(r"if\s*\(counts\s*==\s*null\)\s*\"\"", rl or "") is not None,
         "⑥ 数字缺席时**留白**（写成 0 / 回落 `r.entries` = 报一个「0 条」或过期数）")

    # ═══ ⑦ 状态行的"几条"在主线程上必须**有界**（ISSUE #228）═════════════
    # 状态行是"我点了之后到底成没成"的唯一反馈面，所以它留在主线程（异步渲染会让它
    # 慢半拍地显示上一步的结论 —— 那正是"骗人的绿字"）。而它此前为了一次取值
    # 可以在主线程上读完整份 `texts.jsonl`（几千条 = 几 MB）。
    # 判据：那一次取值必须走**有界**的 `textsCountCapped`，且它的 `null`（量不完）
    # **不许**被当成 0 —— 两者在屏幕上是同一个字、相反的结论。
    need("RagIndexStore.textsCountCapped(" in (rs or ""),
         "⑦ 状态行的条数走**有界**轻读（不是 `textsCount` 的整份读）")
    need(re.search(r"\?:\s*RagIndexStore\.textsCount\(\)?", rs or "") is None,
         "⑦ 状态行里**不许**再出现无界的 `textsCount`（那正是主线程那一次卡顿）")
    need("RAG_STATUS_COUNT_CAP_BYTES" in (rs or ""),
         "⑦ 闸门是一个**具名常量**（内联一个字面数 = 下一个人不知道它是一条判据）")
    cap = fnbody(store_c, "fun textsCountCapped(")
    need(cap is not None, "⑦ RagIndexStore.textsCountCapped() 存在（锚点未漂）")
    need(re.search(r"if\s*\(size\s*>\s*capBytes\)\s*return\s+null", cap or "") is not None,
         "⑦ 超闸门时**先看大小就停手**并返回 `null`（读了再截 = 还是把那一下 IO 付了）")
    need("textsCount(ctx, name)" in (cap or ""),
         "⑦ 闸门为 0 时退化成无界那一条（留一个显式口子，而不是悄悄当 0 条）")

    print()
    # 收尾报**条数**：`tools/count_suite_totals.py` 只认带数字的汇总行，
    # 只印"PASS"会让清单把那一条对成 None —— 而清单记着真值，于是每次
    # 对账都报一条**假漂移**（看着像"这份套件跑不动"，其实是读数格式不对）。
    if bad:
        print(f"=== 知识库状态接线：PASS {len(ok)} / FAIL {len(bad)} ===")
        return 1
    print(f"=== 知识库状态接线：PASS {len(ok)} / FAIL 0 ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
