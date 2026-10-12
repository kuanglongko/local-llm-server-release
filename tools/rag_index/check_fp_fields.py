#!/usr/bin/env python3
"""比对「指纹字段集合」在 C++（rag_index.h）与 Kotlin（RagIndexStore.kt）两边是否一致。

═══════════════════════════════════════════════════════════════════════════
为什么必须对集合，而不只是"两边都能算出一个字符串"
═══════════════════════════════════════════════════════════════════════════
指纹的作用是"任一影响向量的因素变了就作废索引"。它失效的方向**永远偏向更宽松**：
少算一个字段 = 那一类变化不再作废索引 = 旧向量被继续使用。

而"少算"的后果全是不崩不报错的：
  · 少算 mtime -> "就地替换权重"后继续用旧索引；
  · 少算 size  -> "重新拷贝一份同尺寸权重"后继续用旧索引；
  · 少算 dim   -> 换 embed 模型后继续用旧索引（余弦量纲直接对不上，排序全乱）；
  · 少算切块参数 -> 改了 targetChars 后旧索引与新块边界混用，检索命中率凭空下降；
  · 少算正文 hash -> 用户改了正文而检索仍用旧向量命中、注入新正文：
    排序看着像随机，接口 200、日志干净（"文档与索引各自合法、配在一起却错"）。

所以判据是**集合相等**：两边算的字段集合必须一模一样。多算无害（只是更严），
少算必须红。字段用前缀字母标识（v/p/b/m/d/t/o/x），前缀是两边实现里
`"x" + value` 的那个字面量。

用法：check_fp_fields.py <rag_index.h> <RagIndexStore.kt>
"""
import re
import sys


def strip_comments(src):
    """剥掉行/块注释（保留字符串字面量 —— 字段前缀**就是**字面量）。

    ⚠ 为什么必须剥注释：本判据是按字面量提取的，而"把加字段那一行注释掉"
    这种退化形态在**不剥注释**时照样能提取到那个字母（注释里也有）——判据于是恒真，
    一个漏算字段的指纹会被判成"完整"（本轮真踩到）。
    字符串字面量**不能**剥：字段前缀本身就是字面量。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find(chr(10), i)
            i = n if j < 0 else j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c in ('"', chr(39)):
            q = c
            j = i + 1
            while j < n:
                if src[j] == chr(92):
                    j += 2
                    continue
                if src[j] == q:
                    j += 1
                    break
                j += 1
            out.append(src[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def cpp_fields(src):
    """从 `fingerprint_of` 里取 `s += "x";` 的 x（跳过 `\\x1f` 分隔符那几行）。"""
    src = strip_comments(src)
    m = re.search(r'fingerprint_of\s*\([^)]*\)\s*\{', src)
    if not m:
        return None
    body = src[m.end():m.end() + 3000]
    out = []
    for line in body.split('\n'):
        for lit in re.findall(r'"([^"]*)"', line):
            # 分隔符与纯字母前缀都收，再由下面统一过滤
            out.append(lit)
    # 字段字面量是"单个字母"，分隔符是 \x1f（源码里写作 '\x1f'，正则可能拿到 '\\x1f'）
    # ⚠ 返回**有序列表**（不是 set）：本判据还要查"哪一个前缀出现了两次"——
    #    set 会把这个事实直接抹掉（见 main 里的 dup 判据）。
    return [x for x in out if len(x) == 1 and x.isalpha()]


def kt_fields(src):
    """从 `fingerprintOf` 里取 `sb.append("x")` 的 x。

    ⚠ 与 C++ 侧同理：只收 `sb.append("…")` 这一形态的第一个实参，
    不收"函数体里所有单字母字面量" —— 值也可能是单字母（本轮的字段里有
    两个值是 `std::string`，源码里以变量形态出现），按"出现过"收会把值当前缀。
    """
    src = strip_comments(src)
    m = re.search(r'fun\s+fingerprintOf\s*\(', src)
    if not m:
        return None
    body = src[m.end():m.end() + 4000]
    out = []
    for lit in re.findall(r'sb\.append\("([^"]*)"\)', body):
        out.append(lit)
    # 同 C++ 侧：返回有序列表（查重复前缀要用到）
    return [x for x in out if len(x) == 1 and x.isalpha()]


def main():
    if len(sys.argv) != 3:
        sys.stderr.write(__doc__)
        return 2
    cpp_seq = cpp_fields(open(sys.argv[1], encoding='utf-8').read())
    kt_seq = kt_fields(open(sys.argv[2], encoding='utf-8').read())
    cpp = None if cpp_seq is None else set(cpp_seq)
    kt = None if kt_seq is None else set(kt_seq)

    fails = []

    # ── 前缀**不得重复**（顺序敏感：set 看不见这一条）──────────────────────
    # 为什么必须单独判：字段前缀一旦撞车（两处都写 "d"），`fpFields()` 里那句
    # `if (k in out) continue` 会把**后一个**当成前一个的重复而静默丢掉 ——
    # 于是那个字段根本不参与指纹比对（= 那一类变化不再作废索引，旧向量被继续用）。
    # 而合成指纹的函数本身**照旧把两个值都拼进去**，字符串是合法的、
    # 两边（C++ / Kotlin）也照样"一致"—— 集合判据完全看不出来。
    #
    # ⚠ 本判据原先只比**集合**，而实测 e04ea3c 引入的一处粘贴重复
    #    （`sb.append("k")` 上方那段 6 行注释被写了两遍）正是被"集合相等"放过的：
    #    注释里有 `"k"` 字面量、`strip_comments` 会剥掉它，所以那次是**注释**里的
    #    重复（无功能影响）；但同一种粘贴动作落在 `sb.append` 那一行上就是真漏字段。
    #    判据必须对"重复"本身敏感，不能依赖"重复恰好发生在注释里"。
    for label, seq in (("C++", cpp_seq), ("Kotlin", kt_seq)):
        if seq is None:
            continue
        seen = set()
        for f in seq:
            if f in seen:
                fails.append('%s 侧的字段前缀 %r 出现了两次 —— fpFields() 会静默丢掉后一个'
                             '（= 那个字段不参与指纹比对）' % (label, f))
                break
            seen.add(f)
    if cpp is None:
        fails.append('C++ 侧提取不到 fingerprint_of（函数改名/拆掉了？）')
    if kt is None:
        fails.append('Kotlin 侧提取不到 fingerprintOf（函数改名/拆掉了？）')
    if cpp is not None and kt is not None:
        if not cpp:
            fails.append('C++ 侧字段集合为空（提取失效 = 判据恒真）')
        if not kt:
            fails.append('Kotlin 侧字段集合为空（提取失效 = 判据恒真）')
        # ⚠ `g`（合批每批条数，ISSUE #269 第 2 步）在本次加入。
        #   为什么它不进下面那个"关键字段必须两边都在"的名单：那个名单是"少了就会失效" 的
        #   老字段，而 `g` 是新加的 —— 但它同样**必填**（两边各自的 fingerprint_of
        #   都写它），所以集合相等那一条就足以钉住"两边都有"。
        expect = {'v', 'p', 'b', 'm', 'd', 't', 'o', 'k', 'c', 'x', 'r', 'e', 'n', 'q', 'z', 'g'}
        if cpp != kt:
            fails.append('字段集合不一致：C++=%s Kotlin=%s（差集 C++-Kotlin=%s / Kotlin-C++=%s）'
                         % (sorted(cpp), sorted(kt), sorted(cpp - kt), sorted(kt - cpp)))
        # 关键字段必须**两边都在**：少一个就是"那一类变化不再作废索引"
        for f, why in (('m', 'mtime'), ('b', 'size'), ('d', 'dim'),
                       ('t', 'targetChars'), ('o', 'overlapChars'), ('v', '格式版本'),
                       ('k', '切块规则版本'), ('x', '正文 hash'),
                       ('g', '合批每批条数')):
            if f not in cpp:
                fails.append('C++ 侧漏了字段 %s（%s 变了不作废索引）' % (f, why))
            if f not in kt:
                fails.append('Kotlin 侧漏了字段 %s（%s 变了不作废索引）' % (f, why))
        if cpp == expect and kt == expect:
            print('指纹字段集合两边一致且完整：%s' % ''.join(sorted(expect)))

    if fails:
        for f in fails:
            print('FAIL  %s' % f)
        return 1
    print('PASS  指纹字段集合一致（%s）' % ''.join(sorted(cpp)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
