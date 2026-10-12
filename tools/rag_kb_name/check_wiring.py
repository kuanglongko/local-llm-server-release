#!/usr/bin/env python3
"""「改名 / 删库 / 按钮名」的接线判据（纯静态，ISSUE #259）。

═══════════════════════════════════════════════════════════════════════
这一组守的是什么
═══════════════════════════════════════════════════════════════════════
用户三条诉求：

  ① *"有了新建库的按钮和逻辑，应该有删库按钮"*；
  ② *"现在 app 内的建库/重建按钮，实际是新建库索引，应该改为建索引/重建索引"*；
  ③ *"现在选中库，然后在库名输入的地方更换新名字，保存的话会直接生成一个新库，
     改名不生效，我认为应该直接生效，且不用重建索引"*。

三者的失效形态全是**静默**的（不崩、不报错、界面照旧"成功"）：

  · 改名走成"新建" -> 磁盘上多一个空库，旧库连着它的索引原样躺着，
    屏幕上两句提示都说"成了"（用户报的就是这个）；
  · 改名顺手重建了索引 -> 几十秒的等待 + 一次完全没必要的向量重算，
    而"改个名字要等半分钟"会被读成"改名字很危险"；
  · 唯一性没挡 -> 两个库同名，"我往哪个库里导入"没有答案；
  · 删库做成 `drop`（只删两个文件、留目录）-> 留下一行 **0 条** 的幽灵：
    列表里还有它，而**没有任何按钮**对一行 0 条的库起作用；
  · 按钮名不改 -> 用户以为"建库"是建一个库，而它其实只算索引；
  · 显示名与目录名混用 -> 列表里是新名、库名框里是旧名，两个信号打架。

判据一律锚**结构关系**（谁调用谁 / 谁不改什么 / 在哪个函数体内），
不锚"某某字符串出现过"（后者恒真：解释这件事的注释里就有那四个字）。
用法：python3 tools/rag_kb_name/check_wiring.py <EngineActivity.kt> <RagIndexStore.kt> <RagKbName.kt>
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

    ⚠ `list()` 是**表达式体函数**（`fun list(...): List<Info> = rootDir(ctx).listFiles { ... }
    ?.mapNotNull { ... }`），而它体内有**多个** lambda 的 `{}`。只数最外层大括号的
    朴素实现会在第一个 lambda 的 `}` 处就收工 —— 结果返回 90 个字符，
    于是"列表给的是不是显示名"这条判据永远报 FAIL，而源码是对的。

    所以这里改成"数 `(){}[]` 三种深度、归零之后看下一行是不是新声明"。
    这条实现**只服务表达式体函数**（今天只有 `RagIndexStore.list`）——
    函数体是 `{}` 的那些一律走 [fnbody_plain]（它更简单，也不会误伤）。
    两套并存看着冗余，但合成一套的代价是：要么朴素版对 `list()` 说假话，
    要么复杂版在某个函数体上多吞几行 —— 而两种症状都是"判据报了一个源码没做的事"，
    本仓库对这种假红的处置一向是"先修判据"（它比假绿更容易被静音）。
    """
    i = src.find(sig)
    if i < 0:
        return None
    # 找签名这一行的结尾（Kotlin 的声明可以先跨几行参数，所以从第一个 '{' 或 '=' 起步）
    j_brace = src.find("{", i)
    j_eq = src.find("=", i)
    starts = [x for x in (j_brace, j_eq) if x > 0]
    if not starts:
        return None
    k = min(starts)
    # ⚠ '=' 要排除 `==` / `<=` / `>=` / `!=` 这类比较（签名里不会出现，但注释里会）
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
            # 表达式体函数：深度归零之后再遇到**行首**的 `}` 或者下一个成员声明就停。
            if depth == 0:
                # 看后面：如果剩下的内容是空白 + 行首的非缩进 `}`，那就到此为止。
                rest = src[k + 1:]
                m = re.match(r"[ \t]*\n\s*\}", rest)
                if m:
                    return src[i:k + 1]
                # 或者：这一行结束之后紧跟下一个顶层声明（同缩进）
                if re.match(r"[ \t]*\n(\s*)\S", rest):
                    ind = re.match(r"[ \t]*\n(\s*)", rest).group(1)
                    if len(ind) <= 4 or re.match(r"[ \t]*\n\s*(?:@|private|public|internal|fun|val|var|/\*)", rest):
                        return src[i:k + 1]
        k += 1
    return None


def strip_comments(s):
    """剥掉注释，**认字符串字面量**。

    ⚠ 不能直接用 `re.sub(r"/\\*.*?\\*/", "", s, flags=re.S)` —— 这份源码里
    `"*/*"` / `"text/*"` 是**字面量**，它们里面的 `/*` 会被那条正则当成注释开头，
    于是从那里一直吃到下一个 `*/`：实测在这份文件上它吞掉了 **64 KB** 真实代码
    （`ragDeleteBtn = btn(...)` 那一段整个没了），而症状是判据报"按钮不存在" ——
    一个**看起来像源码错了**的假红。

    这类"正则当解析器用"的坑本仓库反复踩过（见 run_rag_new_kb_guard_tests.sh 顶部
    说的三种自欺模式里的"锚错位置"），所以这里走一遍字符级状态机：
    字符串 / 行注释 / 块注释三种状态，遇转义跳过。
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


def visible_strings(src):
    """源码里**真的会被编译进去**的字符串字面量。

    必须**先剥注释**再取字面量，两道都不能少：
      · 只 grep 整份源码 -> "解释为什么改名"的注释里必然带着旧按钮名，
        "不许再出现旧名"这条判据**恒红**（而恒红的判据很快会被静音）；
      · 只在注释里取 -> 那不是判据，是把注释当代码读。
    两道合起来才是"用户屏幕上会出现的那串字"。
    """
    return re.findall(r'"((?:[^"\\]|\\.)*)"', strip_comments(src))


def main():
    act_p, store_p, name_p = sys.argv[1], sys.argv[2], sys.argv[3]
    act = read(act_p)
    store = read(store_p)
    name = read(name_p)
    bad = []

    def need(cond, what):
        print(("PASS  " if cond else "FAIL  ") + what)
        if not cond:
            bad.append(what)

    # ═══ ① 删库：一个**真的**把库抹掉的按钮 ═══════════════════════════════
    dk = fnbody(act, "private fun doDeleteKb(")
    dk_c = strip_comments(dk or "")
    need(dk is not None, "doDeleteKb() 存在（锚点未漂）")
    need("RagIndexStore.delete(this, name)" in dk_c,
         "① 删库走 `RagIndexStore.delete`（**不是** `drop`：drop 留目录 = 一行 0 条的幽灵）")
    need("drop(" not in dk_c,
         "① 删库这条路上**不许**出现 drop（两个动作的后果不同，见 RagIndexStore.delete 的注释）")
    # 确认框里必须报出条数：那是"删的是哪个库"唯一的量化证据。
    need("entriesCountOf" in dk_c,
         "① 确认框里报出条数（只写库名时，一个改过名的库与另一个库长得一样）")
    need("deleteRecursively" not in dk_c,
         "① 递归删除收在 RagIndexStore.delete 里（UI 侧不许自己碰文件系统）")
    # 删完必须清归属 / 脏标记 / 当前库 —— 留着就是"检索一直找一个不存在的库"。
    need("ragDocOwner = \"\"" in dk_c, "① 删库清掉编辑器归属（库没了，框里那份不属于任何库）")
    need('if (RagInject.currentKb(this) == name)' in dk_c,
         "① 删的正是当前库时，把「当前库」也清掉（否则检索会一直找不到它而静默为空）")
    # 失败必须如实说：说成"已删除"而目录还在，用户下一次进页面会看到它。
    need("if (!ok)" in dk_c and "没删掉" in dk_c,
         "① 删除失败必须出声（与『已删除』在屏幕上不一样）")
    # 按钮在，且文案说清了它比「删索引」多删什么。
    need('ragDeleteBtn = btn(' in act, "① 「删库」按钮存在")
    # ⚠ 用**正则对着剥完注释的源码**找 `btn("...")` 的实参，而不是对着全部字面量
    #   取 startswith：后者的扫描面里有 1000+ 个字面量，前几版就因为
    #   "注释里的字面量"与"实参里的换行缩进"两个坑先后误报过两次。
    btn_labels = re.findall(r'btn\("((?:[^"\\]|\\.)*)"\)', strip_comments(act))
    del_btn = [s for s in btn_labels if s.startswith("删库")]
    need(len(del_btn) >= 1, "① 「删库」按钮存在且以「删库」开头（用户能按名字找到它）")
    need(all(("正文" in s or "索引" in s) for s in del_btn),
         "① 按钮名说清了它删的不止索引（与「删索引」区分开）")
    # 「删索引」的名字里**不许**出现"正文"（ISSUE #259 第 ② 条，方向反了的那一版）
    #
    # 上一版这里要求名字里带上"（连正文一起删）"—— 那是在**把名字改去迁就错的
    # 行为**（底下 `drop` 确实连正文一起删了）。用户点出来：*"删索引为啥要删正文，
    # 是你按钮写错了还是逻辑写错了"*。**是逻辑写错了**：这一轮修的是 `drop`
    #（只删索引、正文留着），名字跟着回到它本来的意思。
    #
    # [!] 所以这条判据现在**反向**：名字里出现"正文"就说明有人又把行为改回去了
    #     （或者名字又被拿来遮行为）。这是判断"逻辑有没有退回去"最便宜的一条。
    drop_btn = [s for s in btn_labels if s.startswith("删索引")]
    need(len(drop_btn) >= 1, "① 「删索引」按钮还在（两个动作并存，不是二选一）")
    need(all("正文" not in s for s in drop_btn),
         "① 「删索引」的名字里**不出现**『正文』（它只删索引，正文留着 —— "
         "出现就说明行为又退回了『连正文一起删』）")

    # ═══ ② 按钮名 = 它做的事 ═════════════════════════════════════════════
    need('btn("建索引/重建索引")' in act,
         "② 「建索引/重建索引」按钮存在（旧名『建库/重建』会被读成从零建一个库）")
    # ⚠ 反向、且**只看字面量**：「建库/重建」这个旧按钮名在用户可见文案里必须绝迹。
    #   不剥注释的话这条恒红（解释这次改名的注释里必然带着旧名），
    #   而恒红的判据很快会被静音 —— 见 run_rag_kb_name_guard_tests.sh 的说明。
    old_label = [s for s in visible_strings(act) if "建库/重建" in s]
    need(not old_label,
         f"② 用户可见文案里不再有旧名『建库/重建』（实测 {len(old_label)} 处）")
    # pending 那一行也要跟着改口径（"未落盘"这件事的说法必须与新建库那处一致）。
    need("未落盘" in act, "② 「未落盘」这个说法仍在（pending 行与状态行说的是同一件事）")

    # ═══ ③ 改名 = 换显示名，不换目录、不重建 ══════════════════════════════
    rn = fnbody(act, "private fun doRenameCurrentKb(")
    rn_c = strip_comments(rn or "")
    need(rn is not None, "doRenameCurrentKb() 存在（锚点未漂）")
    need("RagIndexStore.rename(this, dir, display)" in rn_c,
         "③ 改名走 `RagIndexStore.rename`（表里那一行改掉）")
    # ⚠⚠ 这一条是整组的核心：改名这条路上**不许**出现任何算向量的动作。
    #   出现了就是"改个名字还要等几十秒"，而那不是用户要的，也不是必需的
    #   （目录名没变 -> 索引里那些向量描述的还是同一批正文）。
    for forbidden, why in (
        ("buildIndex", "算向量"),
        ("loadRagDoc", "重读正文（内容没变，重读只会清掉光标位置）"),
        ("importEntries", "落盘正文"),
        ("deletedRecursively", "删目录"),
        ("mkdirs", "建目录（那正是用户报的『直接生成一个新库』）"),
    ):
        need(forbidden not in rn_c, f"③ 改名这条路上没有 `{forbidden}`（{why}）")
    need("dirOf" in rn_c and ".name" in rn_c,
         "③ 传给 rename 的是**目录名**（表的第一列是磁盘身份，不是显示名）")
    need("ragDocOwner = display" in rn_c,
         "③ 改名之后归属跟着换（不换会让下一次保存写回旧名字的那个库）")
    need("RagInject.setCurrentKb(this, display)" in rn_c,
         "③ 当前库也跟着换（检索读的是它 —— 不换就是『列表新名、检索旧名』）")
    need('ragKbEt.setText(owner)' in rn_c,
         "③ 写盘失败时输入框**回滚成真值**（留着用户打的名字就是两个信号打架）")
    # ⚠⚠ `currentKb` 必须**一起**回滚：调用方 `saveRagKbName` 在进 [doRenameCurrentKb]
    #   之前已经把当前库设成了新名字（"提交名字"这个动作的一部分）—— 不回滚的话，
    #   改名失败之后检索会一直去找一个**不存在的库**（查不到命中、接口 200、日志干净），
    #   而库名框写着旧名（看起来一切正常）。这是本仓库最难查的那族静默错。
    need('RagInject.setCurrentKb(this, owner)' in rn_c,
         "③ 写盘失败时 `currentKb` **一起回滚**（不回滚 = 检索去找一个不存在的库）")

    # 判据锚在"谁在**什么条件下**被调用"上，不是"文件里出现过 isRenameOfCurrent"。
    src_fn = fnbody_plain(act, "private fun switchRagDocTo(")
    src_c = strip_comments(src_fn or "")
    need(src_fn is not None, "switchRagDocTo() 存在（锚点未漂）")
    need(re.search(r"if \(!forceNew && isRenameOfCurrent\(name\)\)\s*\{\s*doRenameCurrentKb\(name\);",
                   src_c) is not None,
         "③ 切库那一跳先判『这是不是改名』（在**唯一入口**上判，不在调用方各判一套）")
    # 顺序：改名判据必须在 `isPresent` 之前 —— 反了的话"改成一个还没被
    # sanitize 命中的名字"会先掉进"打开新库"那一支（清空正文框 = 用户那次编辑没了）。
    rename_i = src_c.find("isRenameOfCurrent")
    disk_i = src_c.find("RagIndexStore.isPresent(this, name)")
    need(rename_i >= 0 and disk_i > rename_i,
         "③ 改名判据排在『磁盘上有没有这个库』**之前**（反了会先掉进『打开新库』清空正文框）")

    rnof = fnbody(act, "private fun isRenameOfCurrent(")
    rnof_c = strip_comments(rnof or "")
    need(rnof is not None, "isRenameOfCurrent() 存在（锚点未漂）")
    # ⚠⚠ 旧判据要求这里出现 `absolutePath`（"解析出的目录是同一个"）—— 而那条
    #   判据**恒假**，正是用户报的"改名还是失败"的根子：改名要写的那条映射在
    #   判断发生的这一刻**必然还不存在**（它就是这次要写进去的那个），于是
    #   `dirOf(新名字)` 回落成新名字自己，两边的目录永不相等（除非用户输入的
    #   新名字恰好等于目录名）。**判据方向搞反了。**
    #
    # 现在的判据 = "当前开着的是一个**磁盘上真有的库**" + "新名字没被别人占"。
    # 这两条已经把"整份正文被写进别人的库"那个风险挡在门外（它的全部来源是
    # "新名字其实指向另一个已存在的库"，而那是被 `checkName` 当场挡下的）。
    need("RagIndexStore.isPresent(this, owner)" in rnof_c,
         "③ 改名要有对象 —— 当前库必须**在磁盘上真有一个目录**（否则名字换了目标不存在）")
    need("checkName" in rnof_c,
         "③ 名字没被别的库占着才算改名（否则那是『改成别人的名字』，表里会出现两行同名）")
    need("owner == ragPendingKb" in rnof_c,
         "③ pending（还没落盘）上的改名不走磁盘那一套（它连目录都还没有）")
    # 反向：这条判据上**不许**再出现按目录比较的旧写法（它会恒假，见上）。
    need("absolutePath" not in rnof_c,
         "③ 判据**不再**按『解析出的目录是同一个』（改名时那条映射还不存在 -> 恒假 -> 改名必败）")
    ck = fnbody(act, "private fun commitRagKbName(")
    ck_c = strip_comments(ck or "")
    need(ck is not None, "commitRagKbName() 存在（锚点未漂）")
    need("ragPendingKb = safe" in ck_c and "ragDocOwner = safe" in ck_c,
         "③ pending 上改名 = 改那个内存里的名字，且**归属一起走**（不一起走会掉进『没有归属的内容』）")
    need("isPresent(this, safe)" in ck_c,
         "③ pending 改名撞上一个已有库 -> 交回带闸的切库那条路（不许静默追加进别人的库）")

    # ═══ ④ 表这一侧：删库要清行、落盘要补行、列库要显示名 ═════════════════
    dl = fnbody(store, "fun delete(")
    dl_c = strip_comments(dl or "")
    need(dl is not None, "RagIndexStore.delete() 存在（锚点未漂）")
    need("pruneNames" in dl_c,
         "① 删库时把表里那一行也清掉（留着会让下一个想用这个名字的库被『重名』拒掉）")
    need("d.isDirectory" in dl_c and "return false" in dl_c,
         "① 删完要看目录**还在不在**（deleteRecursively 的返回值不是『这次删成了没有』）")
    need("deleteRecursively" in dl_c and "rootDir" not in dl_c.split("deleteRecursively")[-1][:200],
         "① 只删那个库的目录（删根目录既没用又会让『成功』变得不可信）")

    ln = fnbody(store, "fun list(")
    ln_c = strip_comments(ln or "")
    need(ln is not None, "list() 存在（锚点未漂）")
    need("displayName(ctx, d.name)" in ln_c,
         "③ 列表给的是**显示名**（拿目录名去显示 = 列表旧名、库名框新名，两个信号打架）")
    rd = fnbody(store, "fun dirOf(")
    rd_c = strip_comments(rd or "")
    need(rd is not None, "dirOf() 存在（锚点未漂）")
    need("readNames(ctx)" in rd_c and ".dirOf(" in rd_c,
         "③ dirOf 过那张表（这是『改名不重建』成立的**唯一**机制：目录名不动）")

    # ═══ ⑤ 纯逻辑那一层的不变式（表在谁手上都不能变）═══════════════════════
    need("object RagKbName" in name, "RagKbName 是零依赖 object（可与单测一起编）")
    for frag, why in (
        ("fun dirOf(display: String): String", "显示名 -> 目录名"),
        ("fun displayOf(dir: String): String", "目录名 -> 显示名"),
        ("return display\n", "查不到时回落成它自己（空表 = 恒等）"),
        ("fun decode(raw: String): Map", "解析（坏行只丢那一行）"),
    ):
        need(frag in name, f"⑤ {why} 在 RagKbName 里")
    # ⚠ 唯一性这一条**必须锚在函数体内**，不能只锚签名：只判"签名在不在"
    #   的话，把函数体换成 `return null`（= 完全不判重名）守卫照样绿 ——
    #   判据就是恒真的，而这一族（"两个库同名"）正是它要挡的东西。
    #   桩④ 就打的这一处，它逼着这条判据必须真的看见那行比较。
    cn = fnbody_plain(name, "fun checkName(")
    cn_c = strip_comments(cn or "")
    need(cn is not None and re.search(r"if \(s == safe && d != dir\)", cn_c) is not None,
         "⑤ 唯一性判据真的在 checkName 体内比较（两个库不许同名：同名时『我往哪导』没有答案）")
    need(cn is not None and "sanitizeName" in cn_c,
         "⑤ checkName 与 sanitizeName 同一口径（不许在这里另立一套净化规则）")

    # ═══ ⑥ 唯一性必须**同时看磁盘目录**（用户报的"两个库同时被选中"）═════════
    # 磁盘上有 test / test1 / test2，表是空的（升级上来的用户从没改过名）。
    # 在 test 上改成 test1：只查表的判据说"没人占用"，而磁盘上明明有目录 test1。
    # 放行的后果是两个库共用显示名（列表两行同时画 ●、dirOf 把真目录遮蔽掉）——
    # 正是用户报的"点任意一个库都同时选中两个、名字一起变"。
    need("liveDirs" in cn_c,
         "⑥ checkName 看 **liveDirs**（只查表会放过『改成磁盘上另一个库的目录名』）")
    # 反向：这条判据不许把"改成自己目录的名"（无操作）也挡下。
    need(re.search(r"safe\s*!=\s*dir\s*&&\s*safe\s+in\s+liveDirs", cn_c) is not None,
         "⑥ 判据是 `safe != dir && safe in liveDirs`（自己那个目录不算『被别人占着』）")
    # 表那一层要能把**已经写坏的**行清掉（给旧版本改过的盘兜底）。
    need("fun dropDirCollisions(" in name,
         "⑥ 表能丢掉『显示名与活目录撞名』的坏行（自愈：光挡新的改名清不掉旧坏表）")
    dc = fnbody_plain(name, "fun dropDirCollisions(")
    dc_c = strip_comments(dc or "")
    need(dc is not None and re.search(r"display\s*!=\s*dir\s*&&\s*display\s+in\s+liveDirs", dc_c) is not None,
         "⑥ dropDirCollisions 的坏行判据与 checkName 同源（两处各写一套会分家）")

    # ═══ ⑦ 两个调用点都必须带 liveDirs + 列库前自愈 ═══════════════════════
    rn = fnbody(store, "fun rename(")
    rn_c2 = strip_comments(rn or "")
    need(rn is not None, "RagIndexStore.rename() 存在（锚点未漂）")
    need("checkName(safeDir, safeDisplay, liveDirs(ctx))" in rn_c2,
         "⑦ 落盘改名这一层带 liveDirs 再判一次（它是对**表**负责的唯一一处）")
    need("liveDirs" in rnof_c,
         "⑦ 界面这一层 isRenameOfCurrent 也带 liveDirs（否则 UI 先放行、库里再拒）")
    need("dropDirCollisions" in fnbody(store, "fun repairNames(") ,
         "⑦ repairNames 复用 dropDirCollisions（坏行判据只有一处）")
    # ⚠⚠ 本轮（ISSUE #228）把 `refreshRagList` 拆成了两层：骨架（只列名字，
    #     主线程一次 `listFiles`）+ 补齐（后台逐库读正文）。自愈那一跳跟着**后台**
    #     那一层搬进了 `loadRagListCounts` —— 判据的语义没变（列库前、在后台、
    #     先修再 list），变的是它在哪个函数体里。所以这里两个函数体各取一次，
    #     只要**其中之一**满足"先自愈 repairNames、再 list"就继续算数。
    #     ⚠ 不把判据弱化成"整个文件里出现过 repairNames"：那样把这一跳删掉、
    #       在别处留一个同名调用照样绿 —— 这一条要判的正是**列库这一跳的顺序**。
    rl = fnbody_plain(act, "private fun refreshRagList(")
    rl_c = strip_comments(rl or "")
    need(rl is not None, "refreshRagList() 存在（锚点未漂）")
    # 自愈那一层的候选：两个函数体，挑出**同时**含 repairNames 与 list( 的那个。
    lanes = []
    for sig in ("private fun refreshRagList(", "private fun loadRagListCounts("):
        b = strip_comments(fnbody_plain(act, sig) or "")
        if b:
            lanes.append(b)
    selfheal = [b for b in lanes if "repairNames" in b]
    need(bool(selfheal),
         "⑦ 列库前在**后台**自愈那份表（修完再 list，否则这一次仍画成两行同名）")
    need(any("repairNames" in b and "RagIndexStore.list(this)" in b for b in selfheal),
         "⑦ 自愈与 list() 在**同一跳**里（先修再列；分到两跳会先画出坏表那一版）")

    print()
    if bad:
        print(f"=== 改名/删库/按钮名接线：FAIL {len(bad)} ===")
        return 1
    print("=== 改名/删库/按钮名接线：PASS ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
