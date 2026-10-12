#!/usr/bin/env python3
"""断言 buildIndex **把正文一起落盘**（索引不能只有向量）。

═══ 这一条守的是什么 ═══
阶段 1 只落了向量：`RagIndexStore.save(ctx, name, fp, dim, vec, meta)`。
那么阶段 2 的注入会**永远拿不到正文** —— 检索能说出"命中了第 3 条"，
却拿不出第 3 条是什么，于是"注入了 3 条"与"没注入"在回答里表现一样
（都没有内容），而接口 200、日志说"注入 3 条"。

这类缺口不存在于任何单测里（存储层的往返测试只验向量），只能锚**接线**：

  ① buildIndex 体内把正文列表传给 save（不是只传向量）；
  ② 正文列表与向量**共用同一个计数器**（分头计数必然错位 ->
     "第 N 条的向量"配上"第 M 条的正文"，检索分数对、内容错）；
  ③ RagIndexStore.save 的签名里真的有承载正文的参数。

用法：check_texts.py <LlmEngine.kt> [RagIndexStore.kt]
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fnbody import kotlin_fn_body  # noqa: E402


def main():
    eng = Path(sys.argv[1]).read_text(encoding='utf-8')
    bad = 0

    body, line = kotlin_fn_body(eng, 'buildIndex')
    if body is None:
        print("FAIL  找不到 buildIndex")
        return 1

    # ③ save 的调用必须带上正文参数（第 7 个实参）
    m = re.search(r'RagIndexStore\.save\(([^)]*)\)', body)
    if not m:
        print("FAIL  buildIndex 没有调用 RagIndexStore.save")
        bad += 1
    else:
        args = [a.strip() for a in m.group(1).split(',')]
        if len(args) < 7:
            print("FAIL  RagIndexStore.save 只传了 %d 个实参（缺少承载正文的那一个）" % len(args))
            bad += 1
        else:
            print("PASS  RagIndexStore.save 带上了正文参数（共 %d 个实参）" % len(args))

    # ① buildIndex 体内必须构造出正文列表（含 Entry 文本与来源）
    if not re.search(r'RagIndexStore\.Entry\(', body):
        print("FAIL  buildIndex 没有构造带正文的条目 —— 索引只会有向量，注入无内容可拼")
        bad += 1
    else:
        print("PASS  buildIndex 构造了带正文的条目（RagIndexStore.Entry）")

    # ② 正文与向量必须**成对**追加：同一个循环里，要么成功（文本带向量进 rows），
    #    要么失败（文本不带向量进 rows）。分头计数 / 分头循环必然错位 ——
    #    "第 N 条的向量"配上"第 M 条的正文"，检索分数对、注入内容错，且不报错。
    #
    # ⚠ 判据已随 `savePartial`（失败条目正文留下、向量缺席）更新：
    #    早先只有一个 `keptTexts`，现在失败分支也要 add 正文，所以锚点从
    #    "有 keptTexts 这个容器"改成"两条 add 都在**编码循环**里、各自配一次
    #    System.arraycopy 或显式的无向量形态"。判据锚**调用实参形态**，
    #    不锚"文件里出现过某个变量名"（后者恒真 —— 变量改名/挪位置都会骗过它）。
    if 'rows.add(RagIndexStore.Entry(' not in body:
        print("FAIL  buildIndex 没有正文累加容器（rows.add(RagIndexStore.Entry(...))）")
        bad += 1
    else:
        # ⚠ 循环下标**不再写死 `i`**（ISSUE #269 的另一半把编码改成了合批：
        #    `for (k in i until end)`，文本与向量都用 `k`）。判据因此锚
        #    "两条 add 用**同一个**下标、且都取 `texts`"，而不是"出现过 `texts[i]`"——
        #    后者会在变量一改名时**恒红**（而恒红的判据很快会被静音，比没有更坏）。
        #    仍要求同一下标：分头用不同下标追加正是本判据要挡的错位形态。
        #
        # 成功路径：文本与向量在同一条 add 里（Entry 的第 3 个实参是向量）
        ok_add = re.search(
            r'rows\.add\(RagIndexStore\.Entry\(\s*texts\[(\w+)\][^)]*,\s*v\s*\)', body, re.S)
        # 失败路径：文本不带向量进 rows（Entry 只有 2 个实参）
        fail_add = re.search(
            r'rows\.add\(RagIndexStore\.Entry\(\s*texts\[(\w+)\]\s*,\s*src\s*\)', body)
        if not ok_add:
            print("FAIL  成功路径的 rows.add 没有把向量一起带上（Entry(texts[..], src, v)）——"
                  "正文与向量分头追加会让行序错位")
            bad += 1
        elif not fail_add:
            print("FAIL  失败路径的 rows.add 不是「无向量」形态（Entry(texts[..], src)）——"
                  "带上错向量或干脆丢弃条目，都会让索引与正文对不上")
            bad += 1
        elif ok_add.group(1) != fail_add.group(1):
            print("FAIL  成功路径的下标（texts[%s]）与失败路径的下标（texts[%s]）不是同一个 ——"
                  "两条路各用一套下标，行序必然错位"
                  % (ok_add.group(1), fail_add.group(1)))
            bad += 1
        else:
            print("PASS  正文与向量在编码循环里成对追加（成功带向量 / 失败无向量，"
                  "同一下标 texts[%s]）—— 行序不会错位" % ok_add.group(1))

    # ③ 存储层签名：RagIndexStore.save 得真的有 rows 形参
    if len(sys.argv) > 2:
        store = Path(sys.argv[2]).read_text(encoding='utf-8')
        if not re.search(r'fun\s+save\([^)]*rows\s*:\s*List<Entry>', store, re.S):
            print("FAIL  RagIndexStore.save 签名里没有承载正文的形参")
            bad += 1
        else:
            print("PASS  RagIndexStore.save 签名里有 rows: List<Entry>")

    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
