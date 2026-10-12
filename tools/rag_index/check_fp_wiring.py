#!/usr/bin/env python3
"""断言「指纹的取值只有一处」「嵌入身份真的进了指纹」「不可用时说得出是哪一项」。

═══ 这一条守的是什么 ═══
用户报的现象：**建库成功，实际用时提示"嵌入模型或切块参数与建库时不同"**。

这类故障没有任何一处代码看起来是错的 —— 建库侧算得对、检索侧也算得对，
只有"这两次算出来的**不是同一个字符串**"才是错的。而它可以有四种成因，
四种的症状**完全一样**（界面说"参数变了"）：

  ① 模型路径两处拼法不同（一个规范化、一个原样）；
  ② 一处用 `embedDim`、另一处用 `embedOutDim` 或反了；
  ③ 池化 / 模型 stat 只在一处取过（另一处漏了 → 一个写值、一个写空串）；
  ④ 池化方式（CLS / LAST / MEAN）**根本没进指纹** —— 于是"升级了取向量的方式"
     在旧索引上完全不可见：用旧语义的向量命中、注入新语义的正文，
     分数像随机，接口 200、日志干净。

判据分三段，锚**调用关系与取值来源**，不锚"文件里出现过某关键字"
（后者恒真：注释、写错的那一支里都有）：

  single-source    `FpInput` 的构造只允许出现在 `embedFpInput` 一个函数里，
                   且 buildIndex / search 都**调用**它（两处各写一遍必然漂移）；
  embed-identity   池化 / 输出维度 / 模型 stat 必须由那一处取，
                   且 `FpInput(...)` 的实参里能看到它们（缺一项就是"漏算一个维度"）；
  compare-fp       存储层有逐字段比对（`compareFp`）与尾随诊断（`lastDiff`），
                   且 `load` 在用指纹之前**更新**它 —— 少了它，"不可用"就只剩一句
                   笼统的话，用户不知道该换模型还是该重建（用户明确拍板要这一条）。

用法：check_fp_wiring.py <子判据> <文件>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def read(p):
    return Path(p).read_text(encoding='utf-8')


def _construct_sites(src):
    """全仓 `RagIndexStore.FpInput(` / `FpInput(` 的构造点（不含派生解析那个函数）。

    排除两类**不是**构造指纹输入的地方：
      · `fun FpInput(fp: String)` —— 从已有指纹派生（追加/删除时用）；
      · 形参表 / 返回类型里的 `FpInput` 提及（不含 `(`）。
    """
    sites = []
    for m in re.finditer(r'\bFpInput\s*\(', src):
        # 只看前面是 `=` / `(` / `,` 的构造形态，不看形参声明（`fp: FpInput,`）
        head = src[max(0, m.start() - 40):m.start()]
        if re.search(r'fun\s+FpInput\s*$', head):
            continue                      # 派生解析（String -> FpInput）
        if re.search(r':\s*FpInput\s*$', head):
            continue                      # 类型标注
        sites.append(m.start())
    return sites


def single_source(engine):
    """构造点必须**只**在 embedFpInput 里，且两处调用它。"""
    bad = 0
    body, line = kotlin_fn_body(engine, 'embedFpInput')
    if body is None:
        print("FAIL  找不到 embedFpInput（指纹取值的唯一处）—— "
              "没有它，建库与检索就是各写一遍，症状是「建库成功却报参数变了」")
        return 1
    if 'RagIndexStore.FpInput(' not in body:
        print("FAIL  embedFpInput 没有具名构造 RagIndexStore.FpInput")
        bad += 1
    else:
        print("PASS  embedFpInput 具名构造 FpInput（第 %d 行起）" % line)

    # 构造点数：embedFpInput 里 1 处 + 别的函数里 0 处
    sites = _construct_sites(engine)
    # 逐处判断它落在哪个函数体内
    # ⚠ 只取**成员函数**（行首 4 空格缩进），并且只取名字字符串 ——
    #   首版把 `re.Match` 本身塞进列表，于是 owner_of 返回的是一个 Match 对象，
    #   报错文案里出现 `FpInput 的构造点是 [<re.Match object; ...>]`。
    #   判据的**输出**也是判据的一部分：人看不懂的 FAIL 等于没有 FAIL。
    funs = [(m.start(), m.group(1)) for m in
            re.finditer(r'^    (?:@\w+\s*)?(?:private |public |internal |protected |override |open |final |suspend )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]',
                        engine, re.M)]

    def owner_of(pos):
        owner = None
        for ln, name in funs:
            if ln < pos:
                owner = name
            else:
                break
        return owner
    owners = sorted({owner_of(p) for p in sites})
    if owners != ['embedFpInput']:
        print("FAIL  FpInput 的构造点是 %s（应恰好只有 embedFpInput）—— "
              "多一处 = 两次算的不是同一份，而症状是「建库成功却报参数变了」" % (owners,))
        bad += 1
    else:
        print("PASS  全仓只有 embedFpInput 构造 FpInput（取值只有一处）")

    # buildIndex / search 都调用它
    for fn in ('buildIndex', 'search'):
        b, l = kotlin_fn_body(engine, fn)
        if b is None:
            print("FAIL  找不到 %s" % fn)
            bad += 1
        elif 'embedFpInput(' not in b:
            print("FAIL  %s 没有调用 embedFpInput —— 它自己算了一份指纹，"
                  "两次算的不是同一份（症状：建库成功、检索说参数变了）" % fn)
            bad += 1
        else:
            print("PASS  %s 调用 embedFpInput（第 %d 行起）" % (fn, l))
            # 同一处必须传**同一个** cp：建库用用户给的、检索用默认值，
            # 两者都从同一个入口进来才不会分岔。
            m = re.search(r'embedFpInput\s*\(([^)]*)\)', b)
            if not m or 'cp' not in m.group(1):
                print("FAIL  %s 调 embedFpInput 时没有把切块参数传进去（实参 %r）—— "
                      "建库与检索会各取一份默认值，参数一旦不同就是「参数变了」"
                      % (fn, m.group(1).strip() if m else ''))
                bad += 1
    return 1 if bad else 0


def embed_identity(engine):
    """池化 / 输出维度 / 模型 stat 必须由唯一那一处取，并进 FpInput 的实参。"""
    body, line = kotlin_fn_body(engine, 'embedFpInput')
    if body is None:
        print("FAIL  找不到 embedFpInput")
        return 1
    bad = 0
    for needle, desc, why in (
        ('embedPooling', '池化类型', '池化方式直接决定向量是什么，不进指纹 = 升级后旧索引被继续用'),
        ('embedOutDim', '模型声明的输出维度', '库按它分配 buffer，与读取步长 dim 是两条路'),
        ('statModel', '模型 stat（大小 + mtime）', '文件被换成同尺寸的另一份时，只有 mtime 能发现'),
    ):
        if needle not in body:
            print("FAIL  embedFpInput 没有取 %s（%s）" % (desc, why))
            bad += 1
        else:
            print("PASS  embedFpInput 取了%s（%s）" % (desc, needle))
    # 构造实参里必须真的带上它们（取到了不传 = 白取，而这一点看起来完全正常）
    m = re.search(r'RagIndexStore\.FpInput\(([^)]*)\)', body, re.S)
    if not m:
        print("FAIL  找不到 FpInput 的构造实参")
        bad += 1
    else:
        args = m.group(1)
        for needle, desc in (('embedBytes', '嵌入模型字节数'),
                             ('embedMtime', '嵌入模型 mtime'),
                             ('pooling', '池化类型'),
                             ('embedOutDim', '输出维度')):
            if needle not in args:
                print("FAIL  FpInput 的构造实参里没有 %s —— 取了不传 = 白取" % desc)
                bad += 1
        if bad == 0:
            print("PASS  嵌入身份四个字段都在 FpInput 的实参里")
    return 1 if bad else 0


def compare_fp(store):
    """逐字段比对 + 尾随诊断 + load 里真的更新它。"""
    bad = 0
    body, line = kotlin_fn_body(store, 'compareFp')
    if body is None:
        print("FAIL  找不到 compareFp（逐字段比对）—— "
              "只比一行字符串时，「模型或参数变了」是一个用户无法动手的结论")
        return 1
    print("PASS  compareFp 存在（第 %d 行起）" % line)
    # 它必须真的逐字段比（而不是又比一次整串）
    for needle, desc in (('kRequiredFpFields', '必须比的字段表'),
                         ('fpFields', '按前缀拆字段')):
        if needle not in body:
            print("FAIL  compareFp 没有用 %s（%s）—— 逐字段比对退化成了整串比对" % (desc, needle))
            bad += 1
        else:
            print("PASS  compareFp 用 %s（%s）" % (desc, needle))
    # ⚠ "待重建"的判据**不在 compareFp 里**（它在 load：count != rows-1，
    #   那是 savePartial 的形态 —— 指纹整串相等，只有向量是前缀）。
    #   这里要守的是另一件事：compareFp **不许**把"正文 hash 变了"说成待重建
    #   （那会让用户去点重建，而真正的处置是"这份索引描述的正文已经过时"）。
    #   锚它的**返回值实参**：第一项恒 false（不是 same），第二项恒 false（不是 stale）。
    returns = re.findall(r'return\s+FpDiff\(([^)]*)\)', body)
    if not returns:
        print("FAIL  compareFp 里找不到 FpDiff(...) 的返回（判据锚点失效）")
        bad += 1
    elif any([a.strip() for a in r.split(',')][1:2] == ['true'] for r in returns):
        print("FAIL  compareFp 里有 return FpDiff(..., true, ...) —— "
              "把「这份索引已过时」说成「待重建」会让用户点重建；"
              "待重建的判据在 load（count != rows-1），不在这里。"
              "逐字段比对的返回值里不许出现 stale=true")
        bad += 1
    else:
        print("PASS  compareFp 不把任何差异说成「待重建」（stale=true 只出现在 load）")

    load, lline = kotlin_fn_body(store, 'load')
    if load is None:
        print("FAIL  找不到 load")
        bad += 1
    # ⚠ 锚的是 lastDiff 的**来源**（必须来自逐字段比对），不是"体内出现过 lastDiff"——
    #   后者恒真：把 `compareFp(fpIn, expectFp)` 换成一句写死的 `FpDiff(..., "模型或参数变了")`
    #   之后，`lastDiff` 这三个字照样在（本轮真踩到：桩⑳ 打上去守卫没红）。
    #   而那一句写死的话，正是用户拍了板说不要的形态 —— 拿着它没法动手。
    elif not re.search(r'lastDiff\s*=\s*compareFp\s*\(', load):
        print("FAIL  load 没有把逐字段比对的结果写进 lastDiff —— "
              "换成一句写死的话之后，调用方只能印一句笼统的话，"
              "用户不知道该换模型还是该重建（这正是本轮要修的现象）")
        bad += 1
    else:
        print("PASS  load 用 compareFp 的结果更新 lastDiff（第 %d 行起）" % lline)
    return 1 if bad else 0


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    which, path = sys.argv[1], sys.argv[2]
    src = read(path)
    table = {
        'single-source': single_source,
        'embed-identity': embed_identity,
        'compare-fp': compare_fp,
    }
    fn = table.get(which)
    if fn is None:
        sys.stderr.write("未知子判据：%s\n%s" % (which, __doc__))
        return 2
    return fn(src)


if __name__ == '__main__':
    sys.exit(main())
