#!/usr/bin/env python3
"""断言「正文与索引是一对」这件事在**接线**上成立（阶段 2 新增的那条轴）。

═══ 这一条守的是什么 ═══
阶段 2 之前，"索引是否可用"只取决于**模型**（路径/大小/mtime/维度/切块参数）。
阶段 2 起还要取决于**正文**：检索用向量命中行号，注入却拿那一行的正文拼 prompt。
于是"改了正文不重建"会出一个不崩、不报错、接口 200 的错位 ——

  · 用**旧语义**的向量命中，注入**新写进去**的正文：排序看着像随机；
  · 正文文件丢了：命中照样报 3 条，注入出来全是空串（回答里什么都没有）。

判据全是**结构关系**（"指纹里有没有正文那一半"、"谁写正文文件"），
不锚"文件里出现了某关键字"（后者恒真：注释里也有）。

子判据：
  fp-signature      fingerprintOf 有正文 hash 形参，且**没有默认值**
                    （默认值 = 少传也编得过 = "漏算"能静默发生）
  save-same-source  save 体内由 rows 现算 hash（写下的与判定的同源）
  search-texts-hash search 用当前正文 hash 构造指纹
  save-texts-only   saveTexts（只写正文）体内不得写 index.bin
  single-writer     正文文件只有一个写入口
  save-guard        LlmEngine.saveTexts 拒绝"该库还没有索引"

用法：check_texts_hash_wiring.py <子判据> <文件>
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def read(p):
    return Path(p).read_text(encoding='utf-8')


def fp_signature(store):
    """指纹形参：`fun fingerprintOf(fp: FpInput, textsHash: String)` —— 无默认值。"""
    m = re.search(r'fun\s+fingerprintOf\s*\(([^)]*)\)', store)
    if not m:
        print("FAIL  找不到 fingerprintOf（判据无法成立）")
        return 1
    args = m.group(1)
    if 'textsHash' not in args:
        print("FAIL  fingerprintOf 没有正文 hash 形参 —— 正文改了不作废索引")
        return 1
    # `textsHash: String = "..."` 这种默认值会让"少传"编得过
    if re.search(r'textsHash\s*:\s*String\s*=', args):
        print("FAIL  textsHash 有默认值 —— 少传一个参数就能静默漏算正文")
        return 1
    # `rows: Int = -1` 是同一个坑的**另一只脚**，而且真机上踩响过：
    # `r`（正文行数）是 compareFp 的必比字段，索引里写的永远是真实行数，
    # 于是"省略 rows"= 拿 -1 去除真实行数 = **恒不等** = 每次检索都判
    # "索引不可用：正文行数 变了"（真机日志原话），而索引本身完好。
    if re.search(r'rows\s*:\s*Int\s*=', args):
        print("FAIL  fingerprintOf 的 rows 有默认值 —— 省略它并不会「退化」，"
              "而是拿哨兵值去比较真实行数（恒不等 -> 每次检索都判索引不可用）")
        return 1
    if not re.search(r'rows\s*:\s*Int', args):
        print("FAIL  fingerprintOf 没有 rows 形参 —— 那么正文行数根本没进指纹")
        return 1
    print("PASS  fingerprintOf 有正文 hash / 行数形参且均无默认值（%s）" % args.strip())
    return 0


def save_fp_param(store):
    """save 的指纹形参必须是 `FpInput`（只带模型那一半），且必须是具名的。

    为什么要判这个（而不是只看"体内有没有 textsHashOf"）：把完整指纹交给 save
    的版本里，调用方算的 hash 与 save 实际写下的字节之间隔着一次 `encodeTexts` ——
    任一边改了编码，写完的索引就**立刻**失效（"建库成功、检索永远为空"）。
    让 save 自己算的版本没有这个窗口。
    """
    m = re.search(r'fun\s+save\s*\(([^)]*)\)', store, re.S)
    if not m:
        print("FAIL  找不到 fun save（判据无法成立）")
        return 1
    args = m.group(1)
    if 'FpInput' not in args:
        print("FAIL  save 的指纹形参不是 FpInput（拿拼好的字符串 = 写下的正文与"
              "指纹里的那一半可能不是同一份）")
        return 1
    if re.search(r'textsHash', args):
        print("FAIL  save 的形参里出现了 textsHash —— 正文 hash 必须由 save 自己现算")
        return 1
    print("PASS  save 的指纹形参是 FpInput（%s）" % ' '.join(args.split()))
    return 0


def save_same_source(store):
    body, line = kotlin_fn_body(store, 'save')
    if body is None:
        print("FAIL  找不到 save")
        return 1
    bad = 0
    if 'textsHashOf' not in body:
        print("FAIL  save 体内没有现算正文 hash —— 写下的正文与指纹里的那一半可能不是同一份")
        bad += 1
    else:
        print("PASS  save 体内现算正文 hash（第 %d 行起）" % line)
    if 'encodeTexts' not in body:
        print("FAIL  save 体内没有编码正文 —— 那它写的到底是哪一份正文？")
        bad += 1
    else:
        print("PASS  save 体内编码正文（写下的字节就是算 hash 的那一份）")
    # 顺序：先算 hash 再落盘（反过来就是"先写后判"，中间崩了会留下不一致的一对）
    i_hash = body.find('textsHashOf')
    i_write = body.find('writeTexts')
    if i_write >= 0 and i_hash >= 0 and i_hash > i_write:
        print("FAIL  save 先写正文后算 hash —— 中途失败会留下『写下的正文』与『指纹里的正文』不一致")
        bad += 1
    elif i_write >= 0:
        print("PASS  save 先算 hash 再落盘（失败时不会留下不一致的一对）")
    return 1 if bad else 0


def search_texts_hash(engine):
    body, line = kotlin_fn_body(engine, 'search')
    if body is None:
        print("FAIL  找不到 search")
        return 1
    bad = 0
    for needle, desc in (
        ('textsHashOf', '用当前正文的 hash 构造指纹'),
        ('FpInput', '指纹的模型那一半走具名构造（防参数串位）'),
    ):
        if needle not in body:
            print("FAIL  search 没有 %s" % desc)
            bad += 1
        else:
            print("PASS  search 的%s（%s）" % (desc, needle))
    bad += _require_rows_in_fp(body, 'search',
                               "导致每一次检索都判「索引不可用：正文行数 变了」（索引其实是好的）")
    return 1 if bad else 0


def _require_rows_in_fp(body, who, why):
    """`who` 这个函数里调 `fingerprintOf` 时**必须**把 `rowsOf(...)` 作为第三个实参。

    ⚠ 只判"有没有调 fingerprintOf"是不够的：省略第三个参数在 Kotlin 里编得过
    （曾经有默认值 -1），于是拿 `r=-1` 的期望指纹去比索引里真实的 `r=2` ——
    恒不等，而索引完好。锚**调用本身就是这个三参形态**。

    抽成函数是因为 `0.9.236` 起有**第二个**这样的调用点
    （`indexUsableForRewrite`：改字重算那条路的"索引此刻可用吗"闸）——
    它省略 `r` 的后果与 `search` 一模一样（每一次改字都判"索引不可用"，
    于是退回只写正文 + 待重建，而用户看到的是"免重建没生效"，没有任何线索）。
    两处各写一遍这段判据就是下一次分家，所以收在这里。
    """
    if re.search(r'fingerprintOf\s*\((?:[^()]|\([^()]*\))*?rowsOf\s*\(', body, re.S):
        print("PASS  %s 把当前正文行数（rowsOf）传进了 fingerprintOf" % who)
        return 0
    print("FAIL  %s 调 fingerprintOf 时没有把 rowsOf(...) 作为正文行数传进去 —— "
          "期望指纹的 r 会退化成哨兵值，与索引里真实行数恒不等，%s" % (who, why))
    return 1


def rewrite_rows_usable(engine):
    """`indexUsableForRewrite`（改字重算那条路的可用性闸）必须**现算 rows**。

    它省略 `r` 的后果与 `search` 逐字相同：期望指纹的 `r` 恒不等于索引里那个，
    于是**每一次**改字都被判"索引不可用" -> 退回"只写正文 + 待重建"。
    表面现象是"免重建没生效"（用户以为特性没做出来），而根因在一个少传的参数上。
    """
    body, line = kotlin_fn_body(engine, 'indexUsableForRewrite')
    if body is None:
        print("FAIL  找不到 indexUsableForRewrite（改字重算那条路的可用性闸）")
        return 1
    bad = 0
    if 'embedFpInput' not in body:
        print("FAIL  indexUsableForRewrite 没有走 embedFpInput（与检索不同源，会放行另一种语义的索引）")
        bad += 1
    else:
        print("PASS  indexUsableForRewrite 与检索同源取值（embedFpInput）")
    bad += _require_rows_in_fp(body, 'indexUsableForRewrite',
                               "于是每一次改字都被判「索引不可用」，退回只写正文 + 待重建")
    return 1 if bad else 0


def save_texts_only(store):
    body, line = kotlin_fn_body(store, 'saveTexts')
    if body is None:
        print("FAIL  找不到 saveTexts（\"只保存正文\"那条路的入口）")
        return 1
    # 它只允许写正文：不得出现写 index.bin 的两跳
    bad = 0
    for needle, what in (('File(d, FILE)', '正式索引文件'), ('"$FILE.tmp"', '索引临时文件')):
        if needle in body:
            print("FAIL  saveTexts 体内出现了写索引的 %s —— 它必须只写正文" % what)
            bad += 1
    if 'writeTexts' not in body:
        print("FAIL  saveTexts 没有走 writeTexts（另写一份落盘 = 绕过原子性与唯一入口）")
        bad += 1
    if not bad:
        print("PASS  saveTexts 只走 writeTexts，不碰 index.bin（第 %d 行起）" % line)
    return 1 if bad else 0


def single_writer(store):
    """正文文件（TEXTS）只允许在**一个**函数里被写。

    ⚠ 判据必须读**原文区间**，不能用 `_fnbody`：它会抹掉字符串字面量（那是它的
    本职：判据锚实现不锚字面量），而这里要数的正是那个字面量 `"$TEXTS.tmp"` ——
    抹掉之后源码里一个都找不到，判据会**恒红**（本轮真踩到）。
    所以：按行扫，找到写临时正文文件的那一行，再回看它落在哪个函数体内。

    为什么按"函数"而不是"出现次数"：`textsFile()` 是读路径（row / readTexts 都要用），
    真正要守的是"往 TEXTS 写"这件事只有一处 —— 多一处写就多一条忘记同步 hash 的路。
    """
    lines = store.split('\n')
    funs = []          # (行号, 函数名)
    for i, l in enumerate(lines):
        # 带修饰符（private / suspend / override ...）也要认，否则"所在函数"会被
        # 认成上一个**无修饰符**的函数 —— 本轮真踩到：`private fun writeTexts` 不算数，
        # 于是 owner 被认成更早的 indexFingerprint（判据假红）。
        m = re.match(r'\s*(?:[a-z]+\s+)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[(<]', l)
        if m:
            funs.append((i, m.group(1)))
    hits = []
    for i, l in enumerate(lines):
        if 'TEXTS.tmp' not in l:
            continue
        # 收紧到"写临时文件"的形态：`File(d, "$TEXTS.tmp")`
        if 'TEXTS.tmp' not in l or 'File(' not in l:
            continue
        # 所在函数 = 最后一个在它之前的 fun
        owner = None
        for ln, name in funs:
            if ln < i:
                owner = name
            else:
                break
        hits.append(owner)
    if hits != ['writeTexts']:
        print("FAIL  写正文临时文件的函数是 %s（应恰好只有 writeTexts）" % (hits or '无'))
        return 1
    print("PASS  正文文件只有一个写入口（writeTexts）")
    return 0


def save_guard(engine):
    body, line = kotlin_fn_body(engine, 'saveTexts')
    if body is None:
        print("FAIL  找不到 LlmEngine.saveTexts")
        return 1
    bad = 0
    if 'isPresent' not in body:
        print("FAIL  LlmEngine.saveTexts 没有检查\"该库已有索引\" —— "
              "没有索引的目录不会出现在库列表里，正文会被写进谁也看不见的孤儿目录")
        bad += 1
    else:
        print("PASS  LlmEngine.saveTexts 检查了该库是否已有索引（%d 行起）" % line)
    if 'RagIndexStore.saveTexts' not in body:
        print("FAIL  LlmEngine.saveTexts 没有走 RagIndexStore.saveTexts")
        bad += 1
    else:
        print("PASS  LlmEngine.saveTexts 走存储层的 saveTexts")
    return 1 if bad else 0


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    which, path = sys.argv[1], sys.argv[2]
    src = read(path)
    table = {
        'fp-signature': fp_signature,
        'save-fp-param': save_fp_param,
        'save-same-source': save_same_source,
        'search-texts-hash': search_texts_hash,
        'rewrite-rows-usable': rewrite_rows_usable,
        'save-texts-only': save_texts_only,
        'single-writer': single_writer,
        'save-guard': save_guard,
    }
    fn = table.get(which)
    if fn is None:
        sys.stderr.write("未知子判据：%s\n%s" % (which, __doc__))
        return 2
    return fn(src)


if __name__ == '__main__':
    sys.exit(main())
