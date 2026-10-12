#!/usr/bin/env python3
"""断言「知识库正文的编辑闭环」真的接上了 UI（阶段 2 的 1+2 条）。

═══ 这一条守的是什么 ═══
阶段 1 把索引落盘、0.9.152 把嵌入模型装上、0.9.153 把检索接到聊天上 ——
但**编辑器是单向的**：粘贴框只被"读"（建库时），从来不被"写"。
于是三件事同时发生，且都不报错：

  · 点另一个库，框里还是上一个库的文本（"这个库的内容"看起来就是那份错的）；
  · 退出重进后框是空的（内容其实在 `texts.jsonl` 里，只是没人读回来）；
  · 用户每次加一条都要重粘全量（没有"保存"这条路，只有"整库重建"）。

判据锚**结构关系**（这个函数体内有没有那一跳），不锚"文件里出现过某关键字"
（后者恒真：注释、别的分支里都有）。

子判据：
  load-on-select    点库 -> 读该库的正文回填编辑器（不是只改库名）
  restore-load      启动 / 回页时也回填（否则"退出重进空白"仍然存在）
  save-writes-text  保存按钮 -> LlmEngine.saveTexts（不是 buildIndex）
  no-rebuild        保存**不触发重建**（否则"保存一下"要等几十秒，用户就不保存了）
  dirty-guard       切库前问一句（改动不许静默消失）
  dirty-flag        脏标记来自**真实编辑**，且程序化回填不算（防误报吞掉真提示）
  owner-target      保存的目标是"编辑器归属的那个库"，不是库名输入框的字面值
  doc-gen           回填走代际计数（否则快速点两个库会串内容）
  list-insync       库列表把"有索引 / 正文是否同步"两态**分开**说
                    （"待重建（还没有索引）" vs "正文已改，需重建"）
  row-aligned       条目列表按**行号对齐**读正文（readEntriesAligned，不是压紧的 readEntries）
  row-index-kept    列表渲染保留行号（跳过的行不重排下标，删按钮拿到的必须是正文行号）

用法：check_edit_wiring.py <子判据> <EngineActivity.kt>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body, kotlin_fn_span  # noqa: E402


def read(p):
    return Path(p).read_text(encoding='utf-8')


def raw_span(src, name):
    """含签名的整段原文（`_fnbody` 会抹掉字符串字面量，取值表达式多在模板串里）。"""
    code, sline = kotlin_fn_span(src, name)
    if code is None:
        return None
    lines = src.split(chr(10))
    end = min(sline + code.count(chr(10)), len(lines))
    return chr(10).join(lines[sline - 1:end])


def load_on_select(ea):
    """
    ⚠ 这一组判据本轮**换过锚点**（ISSUE #259 的 ③）：切库现在只有**一个**入口
    [switchRagDocTo]（"点列表里的库"与"提交库名框"都汇到它），而 `selectRagKb`
    只剩一行转发。原来那三条判据钉在 `selectRagKb` 体内，于是它们锚的是
    **"谁调用了谁"这个具体写法**：把逻辑搬进一个共用函数（行为完全不变、
    而且正是为了避免两套语义并存）就会让三条判据一起变红 —— 而它们红的原因
    跟"点库会不会回填"没有关系。这类"判据锚在具体写法上、写法一改判据说谎"
    在这里反复踩过，所以这轮跟着源码一起搬锚。

    新的锚法分两层，缺一层都不算通过：
      · `selectRagKb` **必须转发**到那个唯一入口（否则点库那条路又自己写一套）；
      · 那个唯一入口体内**必须**同时做到三件事（回填 / 持久化 / 先确认）。
    """
    bad = 0
    sel, sline = kotlin_fn_body(ea, 'selectRagKb')
    if sel is None:
        print("FAIL  找不到 selectRagKb（点库的入口）")
        return 1
    if 'switchRagDocTo(' not in sel:
        print("FAIL  selectRagKb 没有转发到 switchRagDocTo —— 点库那条路又自己写了一套"
              "（两套并存 = 迟早有一套忘了清/忘了回填）")
        bad += 1
    else:
        print("PASS  selectRagKb 转发到切库的唯一入口（第 %d 行起）" % sline)
    body, line = kotlin_fn_body(ea, 'switchRagDocTo')
    if body is None:
        print("FAIL  找不到 switchRagDocTo（切库的唯一入口）")
        return 1
    if 'loadRagDoc(' not in body:
        print("FAIL  switchRagDocTo 没有调用 loadRagDoc —— 点库只改库名，编辑器里还是上一个库的内容")
        bad += 1
    else:
        print("PASS  switchRagDocTo 调用 loadRagDoc（点库即回填，第 %d 行起）" % line)
    if 'RagInject.setCurrentKb' not in body:
        print("FAIL  switchRagDocTo 没有写当前库（持久化）")
        bad += 1
    else:
        print("PASS  switchRagDocTo 写了当前库")
    if 'confirmDiscardRagDoc' not in body:
        print("FAIL  switchRagDocTo 没有先确认未保存的改动 —— 切库会把编辑器整段换掉，改动静默消失")
        bad += 1
    else:
        print("PASS  switchRagDocTo 先确认未保存的改动")
    # ⚠ 新建库那条分支也必须在这里（它同样是"切库"，只是目标是空的）：
    #   少了它，"点「新建库」"就绕开了回填/清空那一层 —— 而正文框里还留着上一个库的内容。
    if 'openNewRagKb(' not in body:
        print("FAIL  switchRagDocTo 没有处理「新库」那条分支 —— 新建库会绕开清空正文框")
        bad += 1
    else:
        print("PASS  switchRagDocTo 里「新库」与「已有库」两条分支都在")
    return 1 if bad else 0


def restore_load(ea):
    """启动路径（refreshBackendState 之外的那段首屏恢复）必须回填。

    判据锚"启动那段里出现了 loadRagDoc(...)"：找 `ragKbEt.setText(RagInject.currentKb`
    这一行所在函数体内是否有 loadRagDoc。这样改名/搬家都会被抓住。
    """
    for m in re.finditer(r'ragKbEt\.setText\(RagInject\.currentKb', ea):
        # 所在函数：回看最近的**成员**函数（行首 4 空格缩进的 `private fun`）。
        # ⚠ 不能取"任意 `fun`" —— 这一屏有多个 `override fun onTextChanged` 这类
        #    **局部**函数，取最近的会把所在的成员函数认成那个 lambda（本轮真踩到，
        #    判据指着一个不存在的启动路径报红）。成员函数在本仓库一律 4 空格缩进。
        head = ea[:m.start()]
        fns = list(re.finditer(r'^    (?:@\w+\s*)?(?:private |public |internal |protected |override |open |final |suspend )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', head, re.M))
        if not fns:
            continue
        name = fns[-1].group(1)
        body, _ = kotlin_fn_body(ea, name)
        if body is None:
            continue
        if 'loadRagDoc(' in body:
            print("PASS  启动路径（%s）回填了编辑器 —— 退出重进后不再是空白" % name)
            return 0
        print("FAIL  启动路径（%s）没有回填编辑器 —— 退出重进后输入框仍是空白" % name)
        return 1
    print("FAIL  找不到启动时的库名恢复那一处（判据失效 = 恒真）")
    return 1


def save_writes_text(ea):
    body, line = kotlin_fn_body(ea, 'runRagSave')
    if body is None:
        print("FAIL  找不到 runRagSave（保存正文的背景流程）")
        return 1
    bad = 0
    if 'LlmEngine.saveTexts' not in body:
        print("FAIL  runRagSave 没有调 LlmEngine.saveTexts")
        bad += 1
    else:
        print("PASS  runRagSave 调了 LlmEngine.saveTexts（第 %d 行起）" % line)
    if 'buildIndex' in body:
        print("FAIL  runRagSave 里出现了 buildIndex —— 保存不该重算向量（那是「建库/重建」）")
        bad += 1
    else:
        print("PASS  runRagSave 不触发重建（保存是毫秒级的）")
    # 失败时**不许**清脏标记：清了就等于告诉用户"存住了"，而磁盘上没有
    raw = raw_span(ea, 'runRagSave') or ''
    if 'ragDocDirty = false' not in raw:
        print("FAIL  runRagSave 没有维护脏标记（保存成功后编辑器里那份 == 库里那份）")
        bad += 1
    else:
        print("PASS  runRagSave 维护了脏标记")
    return 1 if bad else 0


def dirty_guard(ea):
    raw = raw_span(ea, 'confirmDiscardRagDoc') or ''
    if not raw:
        print("FAIL  找不到 confirmDiscardRagDoc（切库前的那一问）")
        return 1
    bad = 0
    if 'ragDocDirty' not in raw:
        print("FAIL  confirmDiscardRagDoc 没有判脏标记 —— 它永远弹不出来（等于没做）")
        bad += 1
    else:
        print("PASS  confirmDiscardRagDoc 判的是脏标记")
    # ⚠ 不能拿"归属为空就放行"当提前返回：那种内容（没建过库就敲的字）同样会
    #    静默消失。判据锚"提前返回的条件里**只有**脏标记"。
    m = re.search(r'if\s*\(!ragDocDirty[^)]*\)\s*\{[^}]*return\s*\}', raw)
    if not m or 'ragDocOwner' in m.group(0):
        print("FAIL  confirmDiscardRagDoc 的提前返回把\"归属为空\"也放行了 —— "
              "那种内容切库时会静默消失")
        bad += 1
    else:
        print("PASS  confirmDiscardRagDoc 只在\"没有未保存改动\"时才放行")
    if 'AlertDialog' not in raw:
        print("FAIL  confirmDiscardRagDoc 没有弹确认框（静默丢弃 = 用户不知道丢了什么）")
        bad += 1
    else:
        print("PASS  confirmDiscardRagDoc 弹了确认框")
    if 'runRagSave' not in raw:
        print("FAIL  confirmDiscardRagDoc 的「保存」分支没有真的保存")
        bad += 1
    else:
        print("PASS  confirmDiscardRagDoc 的「保存」分支走 runRagSave")
    # 归属为空（还没建过库就敲了字）时也必须问 —— 那种内容同样会静默消失，
    # 只是没有"保存到哪个库"的答案（所以那一支没有"保存"按钮）。
    if not re.search(r'owner\.isEmpty\(\)', raw):
        print("FAIL  confirmDiscardRagDoc 没处理\"编辑器内容不属于任何库\"的情形 —— "
              "那种内容切库时会静默消失（且没有提示）")
        bad += 1
    else:
        print("PASS  confirmDiscardRagDoc 处理了归属为空的情形")
    return 1 if bad else 0


def dirty_flag(ea):
    """脏标记必须由**用户编辑**置位，且程序化回填不能置位。

    判据锚 TextWatcher 那段里的两件事：
      · 置位那一跳存在；
      · 它被 ragDocLoading（"正在程序化回填"）挡住。
    少了后者 -> 点库回填立刻被记成"用户改过"，切库弹一次"要不要保存"（用户什么都没改），
    这个确认框很快会变成"闭眼点掉"的噪声，真正该拦的那次一起被点掉。
    """
    bad = 0
    # ⚠ 这一段以前用 `{0,1200}` 去框 TextWatcher —— 那是一个**跟着源码长度走**的
    #   魔数：本轮在 `afterTextChanged` 里加了一段（新建库自动落盘，ISSUE #259），
    #   窗口就不够了，于是判据读到的是一段**被截断的** watcher —— 它报的是
    #   "没有置脏标记"，而脏标记就在窗口外面一行（判据在说谎，且方向是"红"，
    #   很容易被当成源码坏了去改源码）。改成按**结构**取到 watcher 的收尾：
    #   从 `addTextChangedListener` 起，到与它配对的 `)` 为止（真括号配对）。
    i = ea.find('addTextChangedListener')
    if i < 0:
        print("FAIL  编辑器没有挂 TextWatcher（脏标记无从产生）")
        return 1
    depth = 0
    j = ea.find('(', i)
    if j < 0:
        print("FAIL  addTextChangedListener 后面没有括号（判据失效 = 恒真）")
        return 1
    k = j
    in_str = None
    while k < len(ea):
        c = ea[k]
        if in_str:
            if c == '\\':
                k += 2
                continue
            if c == in_str:
                in_str = None
        elif c == '"':
            in_str = '"'
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                break
        k += 1
    seg = ea[i:k + 1]
    if not seg:
        print("FAIL  取不到 TextWatcher 那段（判据失效 = 恒真）")
        return 1
    if 'ragDocDirty = true' not in seg:
        print("FAIL  TextWatcher 里没有置脏标记 —— 改了正文切库也不会提示")
        bad += 1
    else:
        print("PASS  TextWatcher 里置了脏标记")
    if 'ragDocLoading' not in seg:
        print("FAIL  TextWatcher 没有用 ragDocLoading 挡住程序化回填 —— "
              "点库回填会被当成'用户改过'，确认框立刻变成噪声")
        bad += 1
    else:
        print("PASS  TextWatcher 用 ragDocLoading 挡住了程序化回填")
    # 回填那一侧必须真的把旗标包起来（否则旗标是死代码）
    raw = raw_span(ea, 'setRagDocText') or ''
    if 'ragDocLoading = true' not in raw or 'ragDocLoading = false' not in raw:
        print("FAIL  setRagDocText 没有包住 ragDocLoading 的开与关（旗标是死代码）")
        bad += 1
    else:
        print("PASS  setRagDocText 真的把回填写在 ragDocLoading 里")
    return 1 if bad else 0


def owner_target(ea):
    """保存的目标库必须是**编辑器归属**，不是库名输入框的字面值。

    按输入框存的版本有一个真实的写错库：把库名框改成 B 之后，编辑器里装的还是 A 的
    正文 —— 于是 A 的正文被整份写进 B（覆盖 B 原有内容），而界面上没有任何异常。
    """
    raw = raw_span(ea, 'doSaveTexts') or ''
    if not raw:
        print("FAIL  找不到 doSaveTexts")
        return 1
    bad = 0
    # ⚠ 判据锚"**取值的来源**"而不是"出现了 ragDocOwner 这几个字"：把目标改成
    #    `val owner = typed` 之后，`ragDocOwner` 在别的行（比对那一行）里还在 ——
    #    按出现判会**照样绿**（本轮真踩到）。锚赋值表达式才对。
    if not re.search(r'val\s+owner\s*=\s*ragDocOwner\b', raw):
        print("FAIL  doSaveTexts 的目标库不是取自 ragDocOwner —— 会把 A 的正文写进 B")
        bad += 1
    else:
        print("PASS  doSaveTexts 按编辑器归属（ragDocOwner）定目标库")
    if 'ragDocEntries(owner)' not in raw:
        print("FAIL  doSaveTexts 交给保存的条目不是按归属库构造的")
        bad += 1
    else:
        print("PASS  doSaveTexts 按归属库构造条目")
    if 'sanitizeName(typed)' not in raw:
        print("FAIL  doSaveTexts 没有比对'输入框里的库名'与'编辑器的归属'（错库写入没说出口）")
        bad += 1
    else:
        print("PASS  doSaveTexts 比对了输入框库名与归属（不一致时明确提示）")
    return 1 if bad else 0


def doc_gen(ea):
    """切库不许串内容：先发的那次结果不许覆盖后发的。

    ══════════════════════════════════════════════════════════════════════
    ⚠ P1-a 之后这条判据的**锚点**从 loadRagDoc 挪到了 refreshRagRows
    ══════════════════════════════════════════════════════════════════════
    判据要守的东西**一个字没变**：快速点两个库时，先发的那次读回来不许把后发
    那个库的内容（不管是条目列表还是正文预览）盖上。

    变的是**它在哪个函数里成立**：P1-a 之前，正文回填走的是 `loadRagDoc` 自己
    那一支后台读（`++ragDocGen` + `ioExecutor` + `ragDocOwner != name`）；
    P1-a 之后框不再装整个库，回填 = 画"当前这一页"（[renderRagDocSlice]），
    而那一跳的数据源与条目列表**同一个**：[ragRowsShown]，由 `refreshRagRows`
    一次 `readEntriesAligned` 读进来。

    于是"快速切库不串内容"这件事现在只靠 `refreshRagRows` 那一处保证
    （`++ragRowsGen` + 落地前 `ragDocOwner != name`），而 `loadRagDoc` 本身
    **不再有**后台读 —— 硬按旧锚点判会恒红（本仓库对恒红判据的定义：
    恒红很快会被静音，然后它守的东西就真的没人守了）。

    所以判据跟着**实质**走：仍锚**表达式**（`++ragRowsGen` 递增 + 用 `myGen`
    比对 + 复核 `ragDocOwner`），只是读的是那个函数的体。
    """
    body, _ = kotlin_fn_body(ea, 'refreshRagRows')
    if body is None:
        print("FAIL  找不到 refreshRagRows（P1-a 之后它是切库不串内容的唯一保证）")
        return 1
    bad = 0
    # ⚠ 锚**表达式**：把 `++ragRowsGen` 改成 `ragRowsGen`（不递增）之后，
    #    标识符还在，按出现判会照样绿 —— 而那正是"代际计数失效"的真实形态。
    if not re.search(r'\+\+\s*ragRowsGen', body):
        print("FAIL  refreshRagRows 没有**递增**代际计数 —— 快速点两个库会串内容"
              "（后到的旧结果把新库的条目与预览盖掉）")
        bad += 1
    elif not re.search(r'myGen\s*!=\s*ragRowsGen', body):
        print("FAIL  refreshRagRows 递增了代际却没用它挡结果（计数器是死代码）")
        bad += 1
    else:
        print("PASS  refreshRagRows 用了代际计数（递增 + 落地前比对）")
    if 'ragDocOwner != name' not in body:
        print("FAIL  refreshRagRows 没有复核归属（代际只挡同一次读的两份结果，"
              "而切库后归属也变了）")
        bad += 1
    else:
        print("PASS  refreshRagRows 落地前复核了归属")
    # ⚠ P1-a 之后 loadRagDoc **自己**不许再有后台读：它的正文预览来自
    #   [ragRowsShown]（refreshRagRows 读的），另起一条读盘就是"两份真值"。
    lb, _ = kotlin_fn_body(ea, 'loadRagDoc')
    lb = lb or ''
    if 'ioExecutor.execute' in lb or 'readEntries(' in lb:
        print("FAIL  loadRagDoc 又自己起了后台读 —— P1-a 之后预览的数据源必须是"
              " refreshRagRows 那一份（两份真值迟早说不到一起）")
        bad += 1
    else:
        print("PASS  loadRagDoc 不再另起后台读（预览与列表同源）")
    # 两次独立的后台读不能共用一个计数器
    if re.search(r'ragListGen\s*=\s*\+\+ragRowsGen|ragRowsGen\s*=\s*\+\+ragListGen', ea):
        print("FAIL  库列表与条目读取共用了代际计数器")
        bad += 1
    else:
        print("PASS  库列表与条目读取用各自独立的代际计数器")
    return 1 if bad else 0


def list_insync(ea, ui_path=None):
    """库列表里的**两种"需重建"必须分开说**（ISSUE #259 的第 10 条修法）。

    ⚠ 这条判据本轮被**改写过**，原因值得记下来：上一版要求"非 inSync 分支里
    渲染出含『需重建』的那句话"，而本轮为了修"导入后的新库不进列表"，
    把那个三元表达式换成了 `when { !hasIndex -> …; !inSync -> … }` ——
    语义更强（多分了一态），而旧判据因为锚的是 `if (!r.inSync)` 这个**具体写法**
    而变红。**红得不冤但方向反了**：它拦住的是正确的改动。

    所以判据改成锚**语义**：两态各自有话说，且判据读的是 `hasIndex`
    （"有没有索引"）与 `inSync`（"正文对不对得上"）**两个不同的字段**。
    合成一个字段时，用户带着一个刚从别处导入的库去看"正文已改"，
    然后去翻自己什么时候改过正文 —— 而他从没改过。
    """
    body, _ = kotlin_fn_body(ea, 'renderRagList')
    if body is None:
        print("FAIL  找不到 renderRagList（库列表渲染）")
        return 1
    bad = 0
    if '.inSync' not in body:
        print("FAIL  库列表没有用 Info.inSync —— 列表里看不出'这个库的正文已改'"
              "（用户只会看到'库在、N 条、检索却永远为空'）")
        bad += 1
    else:
        print("PASS  renderRagList 用了 Info.inSync（列表里标出'需重建'）")
    if '.hasIndex' not in body:
        print("FAIL  库列表没有用 Info.hasIndex —— '从来没有索引'与'索引被改作废了'"
              "在列表上长得一样（前者是导入后的正常中间状态，后者说明用户改过正文）")
        bad += 1
    else:
        print("PASS  renderRagList 用了 Info.hasIndex（'待重建'与'正文已改'分开）")
    raw = raw_span(ea, 'renderRagList') or ''
    # ⚠⚠ 本轮**又**换了一次锚点（第二次，理由与上一次同类）：五档状态
    #    （未落盘/待重建/**可续**/正文已改/齐整）收进了 `RagResumeUi.state(...)`
    #    —— 一个纯逻辑层，宿主单测钉得住。于是"两态各自绑到**各自的字段**上"
    #    这条真值一分为二，**两半都要**钉住（只钉一处会让"整支删掉"重新静默）：
    #      · 收方：renderRagList 把 `r.hasIndex` / `r.inSync` / `r.resumable`
    #        **喂给** RagResumeUi.state（不是在这里另写一串 when）；
    #      · 发方：RagResumeUi.state 里 `!hasIndex -> "…待重建…"` 与
    #        `!inSync -> "…正文已改…"` 各是一支，且读的是**两个不同的布尔**
    #        （写成一个字段时，"导入来的新库"会被说成"正文已改"——
    #         用户去翻自己什么时候改过正文，而他从没改过）。
    #    ⚠ 判据仍然锚**表达式**（`!hasIndex ->` 那支的**存在与相对次序**），
    #      不锚"字面量出现过"：合成一个分支时字面量还在文件里，后者恒真。
    m1 = re.search(r'!\s*hasIndex\s*->\s*"[^"]*待重建', ui_path and read(ui_path) or '')
    m2 = re.search(r'!\s*inSync\s*->\s*"[^"]*正文已改', ui_path and read(ui_path) or '')
    fed = (re.search(r'hasIndex\s*=\s*r\.hasIndex', raw) is not None
           and re.search(r'inSync\s*=\s*r\.inSync', raw) is not None)
    if not fed:
        print("FAIL  库列表没有把 hasIndex/inSync 喂给 RagResumeUi.state —— "
              "没有索引的库（导入后的常态）就得不到解释")
        bad += 1
    else:
        print("PASS  库列表把 hasIndex/inSync 喂给 RagResumeUi.state（两态同源）")
    if not m1:
        print("FAIL  状态层没有把'待重建'挂在**非 hasIndex** 那一支上 —— "
              "没有索引的库（导入后的常态）就得不到解释")
        bad += 1
    else:
        print("PASS  状态层在非 hasIndex 分支里说明'待重建（还没有索引）'")
    if not m2:
        print("FAIL  状态层没有把'正文已改'挂在**非 inSync** 那一支上 —— "
              "条件被改成恒假时字面量还在，判据会恒真")
        bad += 1
    else:
        print("PASS  状态层在非 inSync 分支里渲染出'正文已改，需重建'")
    return 1 if bad else 0


def _rows_source(ea):
    """取"条目列表数据来源"那一处的函数体。

    本轮把刷新拆成了 `refreshRagRows`（读）与 `renderRagRows`（画）两个函数，
    两者都可能在"行号对齐"上出错 —— 所以判据在两个函数体里都要过人。
    返回 (refreshBody, renderBody)，取不到时是空串（会让下面的断言假红，
    这正是我们要的：函数改名/拆掉时判据必须红，而不是静默变绿）。
    """
    # ⚠ `kotlin_fn_body` 返回的是 `(body, startLine)` 元组 —— 忘了取 `[0]`
    #    会让下游 `re.search` 拿一个 tuple 去匹配，直接抛 TypeError（本轮真踩到，
    #    表现为"判据自己崩了"，而崩的那条恰好是新增的）。
    refresh = kotlin_fn_body(ea, 'refreshRagRows')[0] or ''
    render = kotlin_fn_body(ea, 'renderRagRows')[0] or ''
    return refresh, render


def row_aligned(ea):
    """条目列表读正文必须走**行号对齐**的那条路。

    ⚠ 为什么不能只判"文件里出现了 readEntriesAligned"：它在文件里出现两次
    （注入侧译一处、这里一处），按"出现过"判会在**列表这一处没改**时照样绿 ——
    而缺陷恰好就在列表这一处。所以判据锚在 `refreshRagRows` 的**函数体**里。
    """
    refresh, render = _rows_source(ea)
    if not refresh:
        print("FAIL  取不到 refreshRagRows 的函数体（改名/拆掉？）")
        return 1
    bad = 0
    if 'readEntriesAligned' not in refresh:
        print("FAIL  条目列表没走 readEntriesAligned —— 压紧后的下标不是正文行号，"
              "点删除会删错一行（空行/坏行一旦出现就错位）")
        bad += 1
    else:
        print("PASS  条目列表走 readEntriesAligned（下标 == 正文行号）")
    # 反向：函数体里**不许**出现压紧那版（出现即说明又改回去了）
    if re.search(r'\bRagIndexStore\.readEntries\s*\(', refresh):
        print("FAIL  refreshRagRows 体内仍调用压紧的 readEntries —— 行号会被压掉")
        bad += 1
    else:
        print("PASS  refreshRagRows 体内不再调用压紧的 readEntries")
    return 1 if bad else 0


def row_index_kept(ea):
    """列表渲染保留行号：跳过的行不许重排下标。

    ⚠ 缺陷形态：为了"跳过空段/坏行"而写成 `rows.filter{...}.forEachIndexed`，
    或先 filter 再 mapIndexed —— 那时下标是"过滤后的位置"，不再是行号，
    删按钮拿到的数字随之跑偏。判据因此是**两条**：
      · 行号是从 `mapIndexed` 的 index 来的；
      · 跳过的行用 `continue`（保留 i），**不是** filter 掉。
    """
    _refresh, render = _rows_source(ea)
    if not render:
        print("FAIL  取不到 renderRagRows 的函数体（改名/拆掉？）")
        return 1
    bad = 0
    if not re.search(r'mapIndexed\s*\{\s*i\s*,', render):
        print("FAIL  列表没有用 mapIndexed 记住下标（行号无处可来）")
        bad += 1
    else:
        print("PASS  列表用 mapIndexed 记住下标（行号来源）")
    # "先 filter 再遍历"会把下标变成"过滤后的位置"。两种形态都要挡：
    #   · `xx.filter{...}.forEachIndexed { i, ... }`（链式）
    #   · `for ((i, t) in xx.filter{...})`（for 里现 filter）
    # ⚠ 不用一个复杂正则去一次性覆盖：写完先自查它能不能编译（本轮真踩到
    #    括号不平衡的写法，判据自己抛异常 = 比恒真更坏的失效）。
    # 形态：把**行数组**先 filter 过一道再取下标。两种写法都挡：
    #   · `rows.filter {...}.mapIndexed {...}`（链式，下标是过滤后的位置）
    #   · `for ((i, t) in rows.filter {...})`（for 里现 filter）
    # ⚠ 别用"任意 .filter{...} 后面跟 Indexed"这种松判据：判据自己也不能恒真。
    chained = re.search(r'\.filter\s*\{[^}]*\}\s*\.\s*[A-Za-z_]*[Ii]ndexed', render)
    chained2 = re.search(r'\.filter\s*\{[^}]*\}\s*\.\s*forEachIndexed', render)
    for_in_filter = re.search(r'\bin\s+[A-Za-z_][\w.]*\s*\.filter\s*\{', render)
    if chained or chained2 or for_in_filter:
        print('FAIL  列表先 filter 再遍历 —— 下标变成"过滤后的位置"，不再是行号')
        bad += 1
    else:
        print('PASS  列表没有"先 filter 再遍历"（跳过行时不重排下标）')
    if 'continue' not in render:
        print("FAIL  渲染里没有 continue —— 跳过空段/坏行时行号会被重排")
        bad += 1
    else:
        print("PASS  渲染用 continue 跳过空段/坏行（行号 i 原样保留）")
    return 1 if bad else 0


def main():
    # 第三参可选：RagResumeUi.kt（状态措辞的纯逻辑层）。本轮把五档状态收进了它，
    # 于是 list-insync 的"两态各自有话说"要**两半都钉**（见 list_insync 的说明）。
    if len(sys.argv) not in (3, 4):
        sys.stderr.write(__doc__)
        return 2
    which, path = sys.argv[1], sys.argv[2]
    extra = sys.argv[3] if len(sys.argv) > 3 else None
    src = read(path)
    table = {
        'load-on-select': load_on_select,
        'restore-load': restore_load,
        'save-writes-text': save_writes_text,
        'dirty-guard': dirty_guard,
        'dirty-flag': dirty_flag,
        'owner-target': owner_target,
        'doc-gen': doc_gen,
        'list-insync': list_insync,
        'row-aligned': row_aligned,
        'row-index-kept': row_index_kept,
    }
    fn = table.get(which)
    if fn is None:
        sys.stderr.write("未知子判据：%s\n%s" % (which, __doc__))
        return 2
    if which == 'list-insync':
        return fn(src, extra)
    return fn(src)


if __name__ == '__main__':
    sys.exit(main())
