#!/usr/bin/env python3
"""判「建索引那一行的进度读数」在**收发两端是通的**，且不报量不准的数。

═══════════════════════════════════════════════════════════════════════
为什么必须有这一条（而不是靠 RagProgressTest）
═══════════════════════════════════════════════════════════════════════
`RagProgress` 的单测只证明"拼得对、边界对"。它证明不了**接上了** ——
而这一族缺陷的形状恰恰是"纯逻辑全绿、屏幕上什么都没有"，
本轮就是在这个形态上栽的（真机上已经栽了很多轮）：

  · [A] 前缀在**收方**另写了一份副本。发方写 `"[知识库] 正在编码："`、收方写
        `"[知识库] 正在逐条编码："` —— 两个字符串从第一天起就不相等，于是
        `line.startsWith(PROGRESS_PREFIX)` **恒假**：
          - 进度**从来没有**走到 `updateProgressLine`；
          - 每一行 `正在编码：37/210` 都落进**日志渲染**那条闸（2000 行 ring
            的排版 + 标签筛选），一次建库几百轮全量重排 ——
            正是 `uiProgress` 那段注释里"上万行不进 ring"要防的事。
        判据恒假 = 整条保护一行都没生效，而它**看起来**一直在。
  · [B] 开工时刻用**墙钟**（`currentTimeMillis`）做差 -> NTP 跳变/用户改时间
        把"已耗时"拉歪甚至算成负数（本仓库 `run_monotonic_clock_guard.sh` G1
        那一族：墙钟差被当成"时长"会让真实卡死被静音）。
  · [C] 切页那一跳退回一句**静态话**（没有读数）-> 用户报的第 ④ 条原样复现：
        "切换页面后，**建索引提示丢失**"——那条提示的价值全在"它还在走"这个
        判断上，而静止的一句话恰恰把这个判断交回用户（他得去翻日志）。
  · [D] 建库**结束后**不清最后读数 -> 下一次切页把一句早已过期的读数原样带出来，
        一个**已完成的**库看起来像"还在跑"（"骗人的进度字"，与"骗人的绿字"同族）。
  · [E] native 侧 `format_elapsed` 与 Kotlin 侧 `RagProgress.elapsed` 格式分岔 ->
        漂移形态是"日志里 `1:23`、界面上 `1:5`"，两者都"没错"，只是说的不是
        同一件事，而这种漂移**没有任何迹象**。

判据一律锚**结构关系**（谁从哪儿取前缀 / 开工时刻取的是哪口钟 / 哪一处写了
最后读数 / 两个格式化函数对同一组边界值是否给出同一串），不锚"某某函数存在"。
用法：check_progress_wiring.py <EngineActivity.kt> <LlmEngine.kt> <RagProgress.kt> <embed_text.h>
"""
import re
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0] + '/../rag_index')
from _fnbody import kotlin_fn_body, kotlin_fn_span  # noqa: E402


def kt_fn(src, name):
    """取一个 Kotlin 函数的函数体，**字符串字面量原样保留**。

    ⚠ 为什么不能直接用 `_fnbody.kotlin_fn_body`：它的 `_strip` 把字符串字面量
    换成 `""`（那对"只看结构"的判据是对的），而本 checker 有几条判据要看的
    恰恰是**字面量的内容**（进度前缀那个字符串、`"$h:${two(m)}:${two(s)}"`）。
    用它会让那两条**恒假**：本仓库对"假红很快被静音"判据很硬，所以这里自己
    抽一份保留字面量的版本，与 `strip_kt` 共用同一套剥注释逻辑。
    """
    code = strip_kt(src)          # 剥注释、**保留**字符串
    for m in re.finditer(r'\bfun\s+' + re.escape(name) + r'\s*[\(<]', code):
        k = m.end() - 1
        depth, opened = 0, False
        while k < len(code):
            if code[k] == '(':
                depth += 1
                opened = True
            elif code[k] == ')':
                depth -= 1
                if opened and depth == 0:
                    k += 1
                    break
            k += 1
        while k < len(code) and code[k] != '{' and code[k] != ';':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        depth, start = 0, k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    return code[start:k + 1]
            k += 1
    return None


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def strip_kt(src):
    """剥注释（含行尾 `//` 与块注释），字符串字面量**保留**。

    为什么不剥字符串：本 checker 有几条判据要看**字面量的值**（前缀那个字符串、
    日志格式串里的字段）。剥掉它等于把判据要锚的那个东西删了 ——
    本仓库在 embed_seq 那一族栽过一次同款（"锚的是一片里出现过这个词"，
    而注释里也有那个词 -> 恒真）。
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            out.append('\n' * src.count('\n', i, j))
            i = j
        elif c in '"\'':
            q = c
            j = i + 1
            while j < n:
                if src[j] == '\\':
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


def cpp_fn_body(src, name):
    """C++ 侧：按真括号配对取一个函数体。"""
    code = strip_kt(src)
    for m in re.finditer(re.escape(name) + r'\s*\(', code):
        k = m.end() - 1
        depth = 0
        while k < len(code):
            if code[k] == '(':
                depth += 1
            elif code[k] == ')':
                depth -= 1
                if depth == 0:
                    k += 1
                    break
            k += 1
        while k < len(code) and code[k] in ' \t\r\n':
            k += 1
        if k >= len(code) or code[k] != '{':
            continue
        depth, start = 0, k
        while k < len(code):
            if code[k] == '{':
                depth += 1
            elif code[k] == '}':
                depth -= 1
                if depth == 0:
                    return code[start:k + 1]
            k += 1
    return None


def main():
    if len(sys.argv) != 5:
        sys.stderr.write(__doc__)
        return 2
    act = read(sys.argv[1])
    eng = read(sys.argv[2])
    prog = read(sys.argv[3])
    eth = read(sys.argv[4])

    act_c = strip_kt(act)
    eng_c = strip_kt(eng)
    prog_c = strip_kt(prog)

    fails = []

    # ── [A] 前缀只许有一处定义，收发两边都从它取 ──────────────────────────
    # 判据是"这个字面出现在几个地方"，而不是"有没有出现" —— 后者在
    # "发方用一份副本、收方用另一份副本"时**照样绿**，而那正是本轮的故障。
    lits = re.findall(r'"\[知识库\] 正在[^"]*"', act_c + '\n' + eng_c)
    if len(lits) > 1:
        fails.append('进度前缀有 %d 处字面定义（%s）—— 只允许一处（RagProgress.PREFIX），'
                     '否则收发两边会分岔（本轮故障：收方多"逐条"两个字 -> startsWith 恒假）'
                     % (len(lits), '、'.join(sorted(set(lits)))))
    if not re.search(r'const\s+val\s+PREFIX\s*=\s*"\[知识库\] 正在编码："', prog_c):
        fails.append('RagProgress 里没有 PREFIX 的字面定义（锚点漂了，或前缀被挪走了）')

    # 发方：buildIndex 里用 `RagProgress.PREFIX + RagProgress.line(...)` 拼
    bi = kt_fn(eng, 'buildIndex')
    if bi is None:
        fails.append('LlmEngine 里取不到 buildIndex（改名/拆走了？）')
    else:
        if 'RagProgress.PREFIX' not in bi:
            fails.append('buildIndex 里没有用 RagProgress.PREFIX（发方自己拼一串字面量 = 第二份定义）')
        if 'RagProgress.line(' not in bi:
            fails.append('buildIndex 里没有 RagProgress.line —— 进度行还是"只有一个 N/M"')
        # ⚠ 报的必须是**已完成条数**（起点 0），不是"当前条号"（起点 1）。
        #    差 1 看起来无害，而它在**最后一条**上会算出 `210/210 条（100.0%）`
        #    提前整整一批出现（那一条还没编完）—— 用户据此以为可以走了。
        if not re.search(r'RagProgress\.line\(\s*\n?\s*i\s*,', bi):
            fails.append('buildIndex 传给 RagProgress.line 的第一个实参不是 `i`（已完成条数）'
                         '—— 传 `i + 1` 会让进度在最后一批**提前报满**')
        if 'uiProgress(' not in bi:
            fails.append('buildIndex 里没有 uiProgress（进度不穿过 logSink 就到不了界面）')

    # 收方：EngineActivity 从 RagProgress 取前缀，且**不再自己写一份**
    if 'PROGRESS_PREFIX = RagProgress.PREFIX' not in act_c:
        fails.append('EngineActivity 的 PROGRESS_PREFIX 不是取 RagProgress.PREFIX'
                     '（自己写一份副本就是本轮那个恒假判据的来源）')

    # ── [B] 开工时刻取**单调钟**（墙钟做差会被跳变拉歪）────────────────────
    if bi is not None:
        # ⚠ 必须锚**开工那一刻那个变量**（`buildT0`），不能锚"文件里出现过
        #    nanoTime" —— `buildIndex` 里还有一处批级计时的 `batchT0 = System.nanoTime()`，
        #    存在性断言在"开工时刻被换成墙钟"时**照样绿**（桩②实测如此）。
        #    判据要问的是"给 RagProgress.line 喂的那个耗时是从哪口钟来的"。
        m_t0 = re.search(r'val\s+buildT0\s*=\s*System\.nanoTime\(\)', bi)
        if m_t0 is None:
            fails.append('buildIndex 的开工时刻不是 `buildT0 = System.nanoTime()` —— '
                         '"已耗时"必须由**单调钟**做差（墙钟会被 NTP 跳变/用户改时间拉歪，'
                         '甚至算成负数；见 run_monotonic_clock_guard.sh G1）')
        elif not re.search(r'System\.nanoTime\(\)\s*-\s*buildT0', bi):
            fails.append('buildIndex 里没有用 `System.nanoTime() - buildT0` 算已耗时'
                         '（开工时刻与读数必须同口钟）')
        if re.search(r'val\s+buildT0\s*=\s*System\.currentTimeMillis\(\)', bi):
            fails.append('buildIndex 的开工时刻是**墙钟**（`currentTimeMillis`）—— '
                         '时长必须由单调钟算（见 run_monotonic_clock_guard.sh G1）')
        # ⚠ 不能一刀切"文件里不许出现 currentTimeMillis"：`builtAt`（落盘时间戳）
        #    **必须**是墙钟（nanoTime 的起点任意，拿它当时间戳会给出一个没有意义、
        #    且随进程重启归零的数 —— 本仓库对这一条有专门的反向判据，见
        #    run_monotonic_clock_guard.sh 的"对外时间戳没被误改成 nanoTime"）。
        #    这里要钉的是**它没有被拿去算时长**：即不许出现
        #    `currentTimeMillis() - <开工时刻>` 这种做差形态。
        if re.search(r'currentTimeMillis\(\)\s*-', bi) or re.search(r'-\s*System\.currentTimeMillis\(\)', bi):
            fails.append('buildIndex 里拿墙钟做了差（`... - System.currentTimeMillis()`）—— '
                         '时长必须由单调钟算（见 run_monotonic_clock_guard.sh G1）')

    # ── [C] 切页那一跳必须带**读数**（不是一句静态话）─────────────────────
    sel = kt_fn(act, 'selectTab')
    if sel is None:
        fails.append('取不到 selectTab（切页那一跳的判据没处可锚）')
    else:
        if 'lastProgressBody' not in sel:
            fails.append('selectTab 里没有带出 lastProgressBody —— 切页会退回一句静态话'
                         '（用户报的第 ④ 条原样复现："建索引提示丢失"）')
        if 'ragBuilding' not in sel:
            fails.append('selectTab 里没有 ragBuilding 判据（建库进行中就不许重画状态行）')

    # ── [D] 建库结束后必须**清掉**最后读数 ───────────────────────────────
    # 不清的形态：下一次切页把一句早已过期的读数带出来，一个**已完成的**库
    # 看起来像"还在跑"—— 与"骗人的绿字"同族的"骗人的进度字"。
    sb = kt_fn(act, 'startBuildIndex')
    if sb is not None:
        # 只在 `ui.post { ... ragBuilding = false ... }` 那个块里找
        m = re.search(r'ragBuilding\s*=\s*false', sb)
        if m is None:
            fails.append('startBuildIndex 里没有 `ragBuilding = false`（收工那一跳的锚点漂了）')
        else:
            tail = sb[m.end():]
            if 'lastProgressBody = ""' not in tail:
                fails.append('建库收工后没有把 lastProgressBody 清空 —— 下一次切页会带出'
                             '一句过期的读数（已完成的库看起来像"还在跑"）')

    # ── [E] 两处格式化（native / Kotlin）对同一组边界值必须给出同一串 ─────
    fe = cpp_fn_body(eth, 'format_elapsed')
    if fe is None:
        fails.append('embed_text.h 里取不到 format_elapsed（native 那份格式化没了？）')
    else:
        # native 侧必须是 `%02lld` 的分/秒补零，且小时档用 `%lld:%02lld:%02lld`。
        # 锚的是**格式串本身**：改成 `%lld:%lld`（不补零）会产出 `1:5`，
        # 而"文件里出现过 %02lld"这种存在性断言在别处也能命中（本仓库踩过）。
        if not re.search(r'snprintf\(out,\s*cap,\s*"%lld:%02lld:%02lld"', fe):
            fails.append('native format_elapsed 的小时档不是 `%lld:%02lld:%02lld`'
                         '（分/秒必须补零，否则 `1:1:1` 与 `1:01:01` 分不清）')
        if not re.search(r'snprintf\(out,\s*cap,\s*"%lld:%02lld"', fe):
            fails.append('native format_elapsed 的分档不是 `%lld:%02lld`（秒位必须补零）')
        # 负数按 0 处理（墙钟回跳不许产出负时长）
        if 'ms < 0' not in fe:
            fails.append('native format_elapsed 没有把负数钳成 0（墙钟回跳会产出负时长）')
        # 缓冲区/空指针退化输入必须先返回（cap 为 0 时 snprintf 不写，
        # 但显式返回让"调用方拿到的永远是有效串"这条契约在代码里可见）
        if 'out == nullptr' not in fe or 'cap == 0' not in fe:
            fails.append('native format_elapsed 没有处理 nullptr / cap == 0（退化输入不许写）')

    el = kt_fn(prog, 'elapsed')
    if el is None:
        fails.append('RagProgress 里取不到 elapsed（Kotlin 那份格式化没了？）')
    else:
        # Kotlin 侧必须也是"小时档带小时、否则 M:SS"，且秒位补零
        # 锚的是"哪几个量、谁补零"，不是一整条字符串的**逐字**形态：
        # 源码里它长得像 `"$h:${two(m)}:${two(s)}"`，而 `_strip` 会把字符串
        # 原样保留 —— 但用逐字断言去对，改一处引号/换行就会假红（本仓库对
        # "假红很快被静音"这件事判据很硬）。这里改成结构化：小时档三个量都在、
        # 分秒**都**过 `two()`，分档只有两个量。
        if not re.search(r'"\$h:\$\{two\(m\)\}:\$\{two\(s\)\}"', el):
            fails.append('Kotlin elapsed 的小时档不是 `$h:${two(m)}:${two(s)}`'
                         '（与 native 的 `%lld:%02lld:%02lld` 是同一条口径）')
        if not re.search(r'"\$m:\$\{two\(s\)\}"', el):
            fails.append('Kotlin elapsed 的分档不是 `$m:${two(s)}`（秒位必须补零）')
        if 'ms > 0' not in el and 'coerceAtLeast' not in el:
            fails.append('Kotlin elapsed 没有把负数钳成 0（墙钟回跳会产出负时长）')

    # 两边的**同一组边界值**必须在各自单测里都出现 —— 只写一边的话，
    # 漂移要等到有人在真机上同时看日志和屏幕才会被发现。
    tet = read(__file__.rsplit('/', 1)[0] + '/RagProgressTest.kt')
    tet_cpp = read(__file__.rsplit('/', 1)[0] + '/../embed_text_test.cpp')
    for val, label in [('"1:05"', '65 秒 -> 1:05'), ('"0:05"', '5 秒 -> 0:05'),
                       ('"59:59"', '3599 秒 -> 59:59'), ('"1:00:00"', '3600 秒 -> 1:00:00'),
                       ('"1:01:01"', '3661 秒 -> 1:01:01'), ('"2:46:40"', '10000 秒 -> 2:46:40')]:
        if val not in tet:
            fails.append('Kotlin 单测里没有边界值 %s（%s）—— 两处格式化会分岔' % (val, label))
        if ('std::string(b) == ' + val) not in tet_cpp:
            fails.append('native 单测里没有边界值 %s（%s）—— 两处格式化会分岔' % (val, label))

    if fails:
        for f in fails:
            print('FAIL  %s' % f)
        return 1
    print('PASS  建索引进度读数：前缀唯一 / 单调钟 / 切页带读数 / 收工清读数 / 两处格式化同口径')
    return 0


if __name__ == '__main__':
    sys.exit(main())
