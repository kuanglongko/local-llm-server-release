#!/usr/bin/env python3
"""断言「追加一条 / 删除单条」（RAG 阶段 3）真的成立。

═══ 这一条守的是什么 ═══
阶段 2 结束时，"加一条"只有两条路：重粘全量再重建（100 条要等 5~30 秒），
或者改完正文只保存（索引立刻失效、检索停用）。于是"维护知识库"在用户手里
永远是一次**整库操作**，而他想做的往往只是"补一句笔记""删掉写错的那句"。

阶段 3 加了增量编辑。增量的失效形态**全是不崩不报错的错位**：

  · 追加时把向量写在行号之外（没算出向量的条目也占正文一行，而向量不占）：
    索引整体错位一行，检索**给出结果**，只是内容全错了 —— 这是最坏的一种；
  · 删除时只删正文不删向量（或反之）：后面每一行都往前挪一位，同上；
  · 索引头的 `count` 与正文行数不再相等（"索引落后于正文"）：刚加的那条
    永远搜不到，与"没命中"一模一样；
  · 写完不重算指纹（正文 hash / 行数都在指纹里）：索引当场失效，
    用户看到的是"追加成功了、检索却停了"，需要重建 —— 增量编辑白做；
  · UI 列表用"第几个视图"当行号：有坏行被跳过时删掉的是**隔壁那一行**。

判据一律锚**函数体内的那一跳 / 那一处取值**，不锚"文件里出现过某关键字"
（后者恒真：注释、别的分支里都有）。判据本体在 tools/rag_edit/ 下。

子判据：
  append-encodes        追加前真的编码了**这一条**（不是"标脏等重建"）
  append-precondition   追加前要求"该库已有索引 + 指纹对得上"（不补一份本来就不齐的）
  append-rows           `rows` 进指纹用的是**新正文的行数**（现算，不是简单累加条数）
  append-count          向量只加"算出来的那几条"（没算出的不占向量行）
  idx-before-texts      两个写入口都**先写索引后写正文**（失败落在可归因的一侧）
  rows-checks           写入前校验"指纹行数 == 正文行数 == count」（三者缺一即拒）
  delete-line-index     删除按**正文行号**：两个文件同时移掉同一行
  delete-shift          删除后剩余向量**逐行前移**（不是原地留洞）
  delete-guard          删除有三道闸（行号越界 / 索引里没有 / 不许删空）
  ui-rows               UI 列出条目、按行号删、删除前确认
  no-draft-clobber      追加**不碰**编辑器里的草稿
  no-full-rebuild       追加/删除不触发整库重建（否则增量就没有意义）
  status-count          状态行报出"库里几条"（追加到底进去没有的唯一可见答案）

用法：check_entry_wiring.py <子判据> <文件>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def read(p):
    return Path(p).read_text(encoding='utf-8')


def raw_span(src, name):
    """含签名的整段原文（`_fnbody` 会抹掉字符串字面量，取值表达式多在模板串里）。"""
    code, sline = None, None
    try:
        from _fnbody import kotlin_fn_span
        code, sline = kotlin_fn_span(src, name)
    except Exception:
        return None
    if code is None:
        return None
    lines = src.split(chr(10))
    end = min(sline + code.count(chr(10)), len(lines))
    return chr(10).join(lines[sline - 1:end])


def append_encodes(engine):
    """追加必须**真的把这一条编码成向量**，而不是"标脏、等用户重建"。"""
    body, line = kotlin_fn_body(engine, 'addEntry')
    if body is None:
        print("FAIL  找不到 LlmEngine.addEntry（追加的唯一入口）")
        return 1
    bad = 0
    if 'embed(' not in body:
        print("FAIL  addEntry 没有编码这一条 —— 它就不是增量，是标脏等重建")
        bad += 1
    else:
        print("PASS  addEntry 编码了这一条（第 %d 行起）" % line)
    if 'appendEntries' not in body:
        print("FAIL  addEntry 没有走存储层的 appendEntries（自己拼文件 = 绕过指纹与行号校验）")
        bad += 1
    else:
        print("PASS  addEntry 走存储层 appendEntries")
    # 编码结果必须**真的带进去**：`Entry(clipped, src, v)` 那个 v 是向量。
    # 只判"出现过 embed(" 会漏掉"算了但没传"这种形态。
    if not re.search(r'RagIndexStore\.Entry\([^)]*\bv\b', body):
        print("FAIL  addEntry 算了向量却没把它交给 Entry —— 索引里不会有这一条")
        bad += 1
    else:
        print("PASS  编码结果随 Entry 一起交给存储层")
    return 1 if bad else 0


def append_precondition(engine):
    """追加前必须确认「该库已有索引」且「指纹对得上」。

    没有第一道闸的版本会往一个**不出现在库列表里**的目录里写（库列表按
    `index.bin` 列库）——"存成功了但看不到"。没有第二道闸的版本会把一条向量
    补到一份**本来就不齐**的索引后面（正文被改过 / 换过模型），于是那份索引
    看起来"新了"，实际还是错的。
    """
    body, line = kotlin_fn_body(engine, 'addEntry')
    if body is None:
        print("FAIL  找不到 LlmEngine.addEntry")
        return 1
    bad = 0
    if 'RagIndexStore.isPresent' not in body:
        print("FAIL  addEntry 没检查「该库已有索引」—— 会写进谁也看不见的孤儿目录")
        bad += 1
    else:
        print("PASS  addEntry 检查了该库是否已有索引")
    if 'hasEmbedModel' not in body:
        print("FAIL  addEntry 没检查嵌入模型 —— 没加载时 embed() 返回 null，"
              "而「追加成功」照样显示")
        bad += 1
    else:
        print("PASS  addEntry 检查了嵌入模型")
    # 指纹对不上 = 索引本来就不齐，存储层会拒（那里是唯一判据点）。
    store = read(str(Path(__file__).resolve().parents[2] /
        'app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt'))
    if 'readState(ctx, name) ?: return EditResult.FAILED' not in store:
        print("FAIL  存储层的追加/删除没有「读一对并校验」这一步 —— "
              "两份文件来自不同时刻时，拼出来的结果完全合法")
        bad += 1
    else:
        print("PASS  存储层的追加/删除先读一对并校验不变式")
    return 1 if bad else 0


def append_rows(store):
    """`rows` 必须用**新正文的行数现算**，而不是「旧 rows + 追加条数」。

    后者在"有条目没算出向量"时会**多算**：多出来的那一行只在正文里、
    不在向量区里，于是 `count != rows` —— 下一次加载把这份索引判成"没有索引"
    （每次打开都在重建），而用户只看到"加了之后就搜不到了"。
    """
    body, line = kotlin_fn_body(store, 'appendEntries')
    if body is None:
        print("FAIL  找不到 RagIndexStore.appendEntries")
        return 1
    bad = 0
    if 'rowsOf(after)' not in body:
        print("FAIL  appendEntries 没有按新正文现算行数 —— 算错就等于索引与正文对不上")
        bad += 1
    else:
        print("PASS  appendEntries 按新正文现算行数（第 %d 行起）" % line)
    if 'fingerprintOf(FpInput(before.fp), textsHashOf(after), rowsAfter)' not in body:
        print("FAIL  指纹没用新正文 hash + 新行数重算 —— 写完的索引当场失效"
              "（用户看到 追加成功了、检索却停了 ）")
        bad += 1
    else:
        print("PASS  指纹按新正文重算（hash 与行数一起进）")
    if 'dropped == 0 && addVec.size == newOnes.size' not in body:
        print("FAIL  追加的返回没有区分「全部有向量」与「有落下的」—— "
              "两种情况下一次该做的事不同（继续编辑 vs 点重建）")
        bad += 1
    else:
        print("PASS  追加区分了 OK 与 STALE（落下的行留在正文里）")
    return 1 if bad else 0


def append_count(store):
    """向量区只加"算出来的那几条"；没算出的**不占向量行**（它只占正文行）。"""
    body, _ = kotlin_fn_body(store, 'appendEntries')
    if body is None:
        print("FAIL  找不到 RagIndexStore.appendEntries")
        return 1
    bad = 0
    if not re.search(r'if\s*\(\s*r\.vector\.size\s*==\s*dim\s*\)\s*addVec\.add', body):
        print("FAIL  追加没有「维度对得上才进向量区」的过滤 —— 维度不符的向量会"
              "按 dim 步长被搬进去，后面的行全部错位")
        bad += 1
    else:
        print("PASS  只有维度对得上的向量进向量区")
    if 'addVec.size * dim' not in body:
        print("FAIL  新向量区的长度不是按「算出来的条数」算的 —— 会多出没有对应的行")
        bad += 1
    else:
        print("PASS  向量区按实际加进去的条数扩容")
    return 1 if bad else 0


def idx_before_texts(store):
    """追加/删除都**先写索引、后写正文**。

    两个文件各自是原子的（tmp + rename），但**一对**不是。顺序决定了失败时
    留下哪一半：
      · 先索引后正文失败 -> "索引 N 行、正文 N-1 行"，被 `r` 校验挡住（需重建）；
      · 先正文后索引失败 -> "正文 N 行、索引 N-1 行"，而**前 N-1 行是对应的**，
        检索照样给出"看起来正常"的结果，用户只觉得新加的那条搜不到。
    两种都可恢复，但后者没有任何现象指向它。所以判据钉**顺序**。

    ⚠ 必须用 `raw_span`：`_fnbody` 会抹掉字符串字面量，而这里要比的
    `writeIndexAtomic` / `writeTexts` 调用顺序在抹掉之后**仍然**可见 ——
    但 `readState` 那一行（含 `?: return`）也在同一段里，用 body 就够。
    真正需要原文的是 `writeIndexAtomic` 内部（`"$FILE.tmp"` 那句）。
    """
    bad = 0
    for fn in ('appendEntries', 'removeEntry'):
        body, _ = kotlin_fn_body(store, fn)
        if body is None:
            print("FAIL  找不到 %s" % fn)
            bad += 1
            continue
        i_idx = body.find('writeIndexAtomic')
        i_txt = body.find('writeTexts')
        if i_idx < 0 or i_txt < 0:
            print("FAIL  %s 没有同时出现两个写入口（索引 %d / 正文 %d）" % (fn, i_idx, i_txt))
            bad += 1
        elif i_idx > i_txt:
            print("FAIL  %s 先写正文后写索引 —— 失败会留下「前 N-1 行都对」的错位，"
                  "而它没有任何现象指向自己" % fn)
            bad += 1
        else:
            print("PASS  %s 先写索引后写正文（失败落在可归因的一侧）" % fn)
    return 1 if bad else 0


def rows_checks(store):
    """写入前必须校验「指纹行数 == 正文实际行数 == 索引 count」三者一致。"""
    body, line = kotlin_fn_body(store, 'readState')
    if body is None:
        print("FAIL  找不到 readState（追加/删除前「读一对」的那一处）")
        return 1
    bad = 0
    if 'rowsInFingerprint' not in body:
        print("FAIL  readState 没读指纹里的行数 —— 校验不出「索引落后于正文」")
        bad += 1
    else:
        print("PASS  readState 读了指纹里的行数（第 %d 行起）" % line)
    if 'rowsOf(textsBytes)' not in body:
        print("FAIL  readState 没数正文的实际行数 —— 那一项只能来自文件本身，"
              "不能信指纹里写的（写的可能是上一次的）")
        bad += 1
    else:
        print("PASS  readState 数了正文的实际行数")
    # ⚠ 判据锚的是**表达式本身**：`rows != rowsInFp || count != rowsToCount(rowsInFp)`。
    #   两边口径不同（rows 是"段数"，count 是"条目数"，差 1）是刻意的，
    #   见 RagIndexStore.rowsOf 的说明；把 rowsToCount 去掉就等于"以为两者相等"，
    #   而那会让每一份正常索引都被判成"索引落后于正文"（每次都重建）。
    if not re.search(r'rows\s*!=\s*rowsInFp\s*\|\|\s*count\s*!=\s*rowsToCount\(rowsInFp\)', body):
        print("FAIL  readState 的三者一致性判据不完整（rows / rowsInFp / rowsToCount(count) 缺一项）")
        bad += 1
    else:
        print("PASS  readState 校验三者一致后才允许编辑（含 rows -> count 的口径换算）")
    if 'dimInHead != dim' not in body:
        print("FAIL  readState 没校验「头的维度 == 指纹的维度」—— "
              "按错的步长搬 float，检索分数对不上内容")
        bad += 1
    else:
        print("PASS  readState 校验了维度来源一致")
    return 1 if bad else 0


def delete_line_index(store):
    """删除必须按**正文行号**同时移掉两个文件里的同一行。"""
    body, line = kotlin_fn_body(store, 'removeEntry')
    if body is None:
        print("FAIL  找不到 removeEntry")
        return 1
    bad = 0
    if 'lines[index]' not in body:
        print("FAIL  removeEntry 没有按行号取正文那一行（按「第几条非空行」取会删错）")
        bad += 1
    else:
        print("PASS  removeEntry 按行号取正文（第 %d 行起）" % line)
    if not re.search(r'if\s*\(\s*r\s*==\s*index\s*\)\s*continue', body):
        print("FAIL  搬向量时没有跳过被删的那一行 —— 它会被搬进新数组，"
              "于是正文少一行、向量多一行")
        bad += 1
    else:
        print("PASS  搬向量时跳过了被删的那一行")
    return 1 if bad else 0


def delete_shift(store):
    """删除后剩余向量必须**逐行前移**，且搬运数与预期不符时不落盘。"""
    body, _ = kotlin_fn_body(store, 'removeEntry')
    if body is None:
        print("FAIL  找不到 removeEntry")
        return 1
    bad = 0
    if 'dst' not in body or not re.search(r'if\s*\(\s*dst\s*!=\s*countAfter\s*\)\s*return\s+EditResult\.FAILED', body):
        print("FAIL  removeEntry 没有「搬运数 != 预期就不落盘」的兜底 —— "
              "少搬一行不会报错，只是从此以后每一条命中都指向隔壁")
        bad += 1
    else:
        print("PASS  removeEntry 有搬运数兜底（不一致即拒）")
    if not re.search(r'if\s*\(\s*r\s*==\s*index\s*\)\s*continue', body) or 'dst++' not in body:
        print("FAIL  removeEntry 没有「跳过被删行 + 目标下标自增」这一对 —— "
              "二者缺一都是错位")
        bad += 1
    else:
        print("PASS  removeEntry 逐行前移（跳过被删行 + 目标自增）")
    return 1 if bad else 0


def delete_guard(store):
    """删除的三道闸。"""
    body, _ = kotlin_fn_body(store, 'removeEntry')
    if body is None:
        print("FAIL  找不到 removeEntry")
        return 1
    bad = 0
    if not re.search(r'index\s*>=\s*lines\.size\s*\|\|\s*lines\[index\]\.isEmpty\(\)', body):
        print("FAIL  removeEntry 没挡「行号越界 / 那一行本来就空」—— "
              "删空行时条目数不变，用户以为没删掉")
        bad += 1
    else:
        print("PASS  removeEntry 挡了越界与空行")
    if not re.search(r'index\s*>=\s*count', body):
        print("FAIL  removeEntry 没挡「索引里没有这一行」—— 那是「正文领先于索引」的状态，"
              "删它会让两个文件整体错位一行")
        bad += 1
    else:
        print("PASS  removeEntry 挡了「索引里没有这一行」")
    if not re.search(r'rowsToCount\(rowsBefore\)\s*<=\s*1', body):
        print("FAIL  removeEntry 没挡「删完为空」—— 「删掉最后一条」与「库从来没东西」"
              "在界面上应当可分")
        bad += 1
    else:
        print("PASS  removeEntry 挡了「删空」")
    return 1 if bad else 0


def ui_rows(ea):
    """UI 必须列出条目、按**行号**删、删除前确认。"""
    bad = 0
    body, line = kotlin_fn_body(ea, 'renderRagRows')
    if body is None:
        print("FAIL  找不到 renderRagRows（条目列表的渲染）")
        return 1
    # ⚠ 判据锚"渲染时记下了行号"，不能只判"forEachIndexed 出现过"：
    #   把 `ragRowsShown = rows.mapIndexed { i, t -> i to t }` 改成 `rows.map { 0 to it }`
    #    之后标识符还在，而删除会永远删第 1 行。
    if 'ragRowsShown = rows.mapIndexed' not in body:
        print("FAIL  renderRagRows 没有把**行号**记下来 —— 删除只能用视图下标，"
               "有坏行被跳过时删掉的是隔壁那一行")
        bad += 1
    else:
        print("PASS  renderRagRows 记下了行号（第 %d 行起）" % line)
    if 'confirmDeleteEntry(i' not in body:
        print("FAIL  删除按钮传的不是**捕获到的行号** i")
        bad += 1
    else:
        print("PASS  删除按钮传的是捕获到的行号")
    conf, _ = kotlin_fn_body(ea, 'confirmDeleteEntry')
    if conf is None or 'AlertDialog' not in conf:
        print("FAIL  删除没有确认框 —— 列表只显示前 60 字，点错隔壁那一行不会有任何挽回机会")
        bad += 1
    else:
        print("PASS  删除前弹确认框（不可撤销的动作）")
    # 确认框里那段**预览**必须按代理对安全地截（ISSUE #326 的 D1）：
    # 裸 `substring(0, 300)` 切在 emoji 中间会留下孤立高代理，确认框里显示成
    # `?` —— 而用户正是靠这段预览判断"要删的是不是这一条"，判错就删错了。
    if conf is None:
        print("FAIL  找不到 confirmDeleteEntry（预览截断判据无法核）")
        bad += 1
    elif 'clipAtCharBoundary' not in conf:
        print("FAIL  确认框预览没有走 clipAtCharBoundary（裸 substring 会切出孤立代理）")
        bad += 1
    elif 'substring(0, 300)' in conf:
        print("FAIL  确认框预览仍有裸 substring(0, 300)（切在代理对中间）")
        bad += 1
    else:
        print("PASS  确认框预览按代理对安全地截（clipAtCharBoundary）")
    ref, _ = kotlin_fn_body(ea, 'refreshRagRows')
    if ref is None or 'readEntries' not in ref:
        print("FAIL  refreshRagRows 不是从磁盘上的条目读的（列表与真实行号必须同源）")
        bad += 1
    else:
        print("PASS  条目列表从磁盘的正文行读（行号与删除口径同源）")
    return 1 if bad else 0


def no_draft_clobber(ea):
    """追加**不许**碰编辑器里的草稿。"""
    body, _ = kotlin_fn_body(ea, 'doAppendEntry')
    if body is None:
        print("FAIL  找不到 doAppendEntry")
        return 1
    for needle, what in (('setRagDocText', '把追加内容写进编辑器（会覆盖未保存的草稿）'),
                         ('ragDocEt.setText', '直接改写编辑器文本')):
        if needle in body:
            print("FAIL  doAppendEntry 里出现了 %s —— %s" % (needle, what))
            return 1
    print("PASS  追加不碰编辑器（草稿与库内容互不覆盖）")
    return 0


def no_full_rebuild(ea):
    """追加/删除都不得触发整库重建。"""
    bad = 0
    for fn, expect in (('doAppendEntry', 'LlmEngine.addEntry'), ('doDeleteEntry', 'LlmEngine.deleteEntry')):
        body, _ = kotlin_fn_body(ea, fn)
        if body is None:
            print("FAIL  找不到 %s" % fn)
            bad += 1
            continue
        if expect not in body:
            print("FAIL  %s 没有调 %s" % (fn, expect))
            bad += 1
        elif 'buildIndex' in body:
            print("FAIL  %s 里出现了 buildIndex —— 变成整库重建，增量编辑就没有意义了" % fn)
            bad += 1
        else:
            print("PASS  %s 走增量入口且不重建" % fn)
    return 1 if bad else 0


def status_count(ea):
    """状态行必须报出"库里几条"。

    ⚠ 判据不能用 `_fnbody`：它把字符串字面量抹成 `""`，而这里要判的正是
    **那条取值表达式**（条数取值出现在 `parts.add(...)` 里）。
    抹掉之后 `parts.add("")` 与"什么都没加"长得一样，判据会恒假。
    所以读**原文区间**。

    ⚠ 取值来源在 `0.9.195` 换了：此前是 `RagIndexStore.textsCount(this, kb)`
    ——它会把整份 `texts.jsonl` 读进内存再 `split('\n')`，而状态行每次重画都在
    **主线程**上调它（7021 条 / 6 MB 的库上直接焊死知识库页）。现在改查
    `ragListEntries[kb]`（`RagIndexStore.list` 在后台遍历时顺手留下的一份快照）。
    **判据锚的是"报没报这个数"，不是"数字从哪来"** —— 所以跟着改成新形态；
    但"报没报"这条语义一个字没松。
    """
    raw = raw_span(ea, 'renderRagStatus') or ''
    if not raw:
        print("FAIL  找不到 renderRagStatus")
        return 1
    # ⚠ 取值出口有两个形态，**两个都算**（判据判的是"有没有报出来"，不是"用哪种取法"）：
    #   · `RagIndexStore.textsCount(this, kb)` —— 老形态：直接数盘上的正文行数；
    #   · `ragListEntries[kb]`               —— 新形态（PR #265）：查后台快照表。
    #     为什么必须接受这一种：`textsCount` 会把**整个 texts.jsonl 读进内存**
    #     （7021 条 = 6 MB），而 `renderRagStatus` 会被切库/刷列表/导入/建库结束
    #     一路调用 —— 于是这一屏每次重画都在**主线程**上读一次整文件。改成查表
    #     是那一轮的目的，**不是**"不再报条目数"。
    #   本轮实测：不改这条判据时它会恒红，而它红的原因与源码实际上报没报条数
    #   **无关** —— 正是本仓库反复收口的"判据锚在具体写法上、写法一改判据说谎"。
    if ('RagIndexStore.textsCount' not in raw and 'ragListEntries[' not in raw
            and 'RagIndexStore.textsCountCapped' not in raw):
        print("FAIL  状态行没报条目数 —— 「我追加的那条到底进去了没有」在界面上没有答案")
        return 1
    # 只判"出现过 textsCount"还是不够：算出来不放进 parts 等于没显示。
    #
    # ⚠ 正则不能用 `parts\.add\([^)]*textsCount`：`[^)]*` 在**嵌套括号**上会
    #   提前停住，而状态行里有一处是 `parts.add("⚠ 正文在（${…textsCount(this, kb)} 条）…")`
    #   —— 取值在**第二层**括号里，那个写法恒不匹配（本轮实测：判据恒红，
    #   而看起来像"源码没报条目数"）。改成"在 `parts.add(` 之后的一段里出现
    #   `textsCount`"，边界取到**那一个** `parts.add` 调用的实参区间（到 `)` 结束
    #   为止用非贪婪匹配，允许中间有括号）。
    # ⚠ 但 `.*?` 太宽：它会把**另一支**（"正文在、索引待建"那句里也报了条数）
    #   当成"条目数进去了"。本轮实测：把"索引就绪时报条数"那一行整个换成注释，
    #   判据照样绿 —— 因为上面那一支里也有 `textsCount`。
    #   所以锚**那一行的完整形态**：`if (exists) parts.add("${RagIndexStore.textsCount(`
    #   （`exists` 是"有索引"那一支的判据，与"待重建"那一支互斥）。
    # [!] 两种形态各锚各的，**不许**用一条宽正则同时认下来：宽正则会把
    #     "某一支里出现过取值"当成"条目数进去了"，于是把另一支整行删掉它照样绿
    #     （本轮实测过，见上面那段注释）。
    #   · 老形态：取值直接内联在 `parts.add("${…textsCount…}")` 里；
    #   · 新形态：先 `val cnt`（带 `null` = 快照未到），再由 `parts.add("$cnt 条")` 出口。
    #     ⚠ 新形态里 `cnt` 那一次取值**必须**能在 `parts` 上看到出口 ——
    #       算了不放 = 没显示，那正是这条判据存在的理由。
    old_form = re.search(r'if\s*\(exists\)\s*parts\.add\(\"\$\{RagIndexStore\.textsCount', raw)
    new_form = re.search(r'val\s+cnt\s*:[^=]*=\s*[^\n]*ragListEntries\[', raw) and \
        re.search(r'parts\.add\(\"\$cnt', raw)
    # ⚠⚠ 第三形态（本轮，ISSUE #228）：`ragListEntries[kb]` 那一次之后**还有一次
    #     有界轻读**（`textsCountCapped`）。加它不是因为"又多了一个取法好看"，
    #     是因为**点库这一跳不刷库列表** —— 快照表里可能还没有刚建好的那个库，
    #     而"我这一下到底建成了几条"正是用户此刻唯一的疑问。
    #     同一条判据（报没报条目数）不该因为取法多了一次就变红，所以这里多认一种
    #     **取值表达式**：`val cnt` 后面跟着一个 `if (…/!hasTexts…) 0 else 回落到
    #     ragListEntries[...] ?: lightRead` 的形状。
    #     ⚠ 放宽的只是"怎么取值"这一半；"有没有出口"与"null 不许回落成 0"
    #       仍然各钉各的（那才是这条判据真正的语义）。
    capped_form = re.search(
        r'val\s+cnt\s*:[^=]*=\s*if\s*\([^\n]*\)\s*0\s*\n\s*else\s+ragListEntries\[', raw) and \
        re.search(r'parts\.add\(\"\$cnt', raw)
    if not (old_form or new_form or capped_form):
        print("FAIL  状态行算了条目数却没放进 parts（算给谁看？）")
        return 1
    # 新形态额外钉一条：`null`（快照未到）**不许**回落到 0 —— 两者的区别是
    # "我还不知道"与"这个库是空的"，而它们在屏幕上是同一个字、相反的结论。
    if (new_form or capped_form) and not re.search(r'cnt\s*!=\s*null', raw):
        print("FAIL  新形态没有区分『快照未到(null)』与『库里是 0 条』（同一句话，相反的结论）")
        return 1
    print("PASS  状态行报出库里条目数")
    return 0


def append_truncation(engine):
    """追加超长时必须**出声**（第 3 步）。

    背景：`addEntry` 会把超长文本截到 `cp.targetChars`，注释写着"并提示"，但
    代码里**没有任何提示**。后果是一件用户看不见的事：
      · 这一条的**向量**只覆盖了前 480 个字；
      · 检索命中它时注入的却是**全文**。
    于是「相关性判断」与「注入内容」说的不是一件事 —— 用户看到"这条命得挺准"，
    模型看到的却是另一段文本。没有这句提示，用户永远查不到这里。

    判据锚两件事、缺一不可：
      · 截断**有判定**（`truncated` 从长度比较得出，而不是又写一遍 substring）；
      · 截断**有出口**（成功路径的返回里带得出这句话）。
    只锚"文件里出现过 targetChars"会恒真：substring 那一行本来就有它。
    """
    body, line = kotlin_fn_body(engine, 'addEntry')
    if body is None:
        print("FAIL  找不到 LlmEngine.addEntry")
        return 1
    bad = 0
    # ① 截断判定必须单独存在（不是把 substring 与"是否截断"混成一个表达式）
    if not re.search(r'val\s+truncated\s*=\s*trimmed\.length\s*>\s*cp\.targetChars', body):
        print("FAIL  addEntry 没有把「是否被截断」单独判出来 —— "
              "于是无法在成功路径上提示；用户以为整条进去了")
        bad += 1
    else:
        print("PASS  addEntry 单独判出了 truncated（第 %d 行起）" % line)
    # ② 成功路径（EditResult.OK）里必须带上截断提示。
    # ⚠ 这一支的块体里嵌套了 `if (...) "..." else null`，所以不能拿"第一个
    #    缩进回退的 }"当结束 —— 那样会把 `if (truncated)` 那一行截掉（判据假红）。
    #    改成取 OK 分支的**起点到 FAILED 分支之前**整段：那是这一支的全部内容。
    m0 = re.search(r'EditResult\.OK\s*->\s*\{', body)
    m1 = re.search(r'EditResult\.FAILED\s*->', body)
    seg = body[m0.end():m1.start()] if (m0 and m1 and m1.start() > m0.end()) else body
    if 'truncated' not in seg:
        print("FAIL  addEntry 的**成功**路径没有用到 truncated —— "
              "截断了却什么都不说，用户不知道这条为什么检索不准")
        bad += 1
    else:
        print("PASS  addEntry 的成功路径带了截断提示")
    # ③ 截断提示必须**真的返回出去**（是"成功且带话"的第三态），不能只是
    #    打了个日志 —— 日志页用户未必看，而这条要出现在"追加"的**回执**上。
    #    ⚠ 判据锚的是 `if (truncated) ... else null` 这个**三元返回**的形态：
    #    `_fnbody` 会抹掉字符串内容，所以不能靠"消息里有没有某个词"来判断
    #    （那样在本仓库的判据基础设施下恒假 —— 本轮真踩到）。
    if not re.search(r'if\s*\(\s*truncated\s*\)', seg):
        print("FAIL  addEntry 的成功路径没有按 truncated 分流 —— "
              "截断了却只当成功返回 null，回执上什么都没有")
        bad += 1
    else:
        print("PASS  截断时成功路径按 truncated 给出可操作的回执")
    return 1 if bad else 0


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    which, path = sys.argv[1], sys.argv[2]
    src = read(path)
    table = {
        'append-encodes': append_encodes,
        'append-precondition': append_precondition,
        'append-rows': append_rows,
        'append-count': append_count,
        'append-truncation': append_truncation,
        'idx-before-texts': idx_before_texts,
        'rows-checks': rows_checks,
        'delete-line-index': delete_line_index,
        'delete-shift': delete_shift,
        'delete-guard': delete_guard,
        'ui-rows': ui_rows,
        'no-draft-clobber': no_draft_clobber,
        'no-full-rebuild': no_full_rebuild,
        'status-count': status_count,
    }
    fn = table.get(which)
    if fn is None:
        sys.stderr.write("未知子判据：%s\n%s" % (which, __doc__))
        return 2
    return fn(src)


if __name__ == '__main__':
    sys.exit(main())
