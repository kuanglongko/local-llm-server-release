#!/usr/bin/env python3
"""判 `HttpApi.ragFieldOf` 的**整段声明**里不再现读 `remoteKb`。

═══════════════════════════════════════════════════════════════════════════
为什么不能直接用 `_fnbody.kotlin_fn_body('ragFieldOf')`
═══════════════════════════════════════════════════════════════════════════
`ragFieldOf` 写的是**表达式体**：

    private fun ragFieldOf(kb: String, count: Int): String =
        RagProtocol.responseField(
            if (count > 0) RagProtocol.Injection(kb, count) else null)

它后面**没有 `{`**。而 `kotlin_fn_body` 的取法是"从签名往后找第一个 `{` 再配对"——
于是它会跨过 `ragFieldOf`，把**下一个函数**（`compChunkJson`）的 `{...}` 当成它的
函数体。判据于是在别人身上跑：`remoteKb(` 在那个函数里当然找不到，**恒绿**。

（本轮真踩到：桩⑬ 把 `remoteKb(...)` 塞进 `ragFieldOf` 之后，守卫照样报 PASS。
这就是"判据恒真"最典型的形态 —— 它不是判错了，是**根本没判这一个函数**。）

所以这里取的是**声明级**范围：从 `fun ragFieldOf` 那行起，到一个空行 / 下一个
`fun` / 下一个 `@` 注解为止。对表达式体与块体都对。
"""
import re
import sys


def span(src: str, name: str):
    lines = src.split('\n')
    start = None
    for i, ln in enumerate(lines):
        if re.search(r'\bfun\s+' + re.escape(name) + r'\b', ln):
            start = i
            break
    if start is None:
        return None
    out = [lines[start]]
    for ln in lines[start + 1:]:
        s = ln.strip()
        if s == '' or s.startswith('fun ') or s.startswith('private fun ') or s.startswith('@'):
            break
        out.append(ln)
    return '\n'.join(out)


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    src = open(sys.argv[1], encoding='utf-8').read()
    body = span(src, 'ragFieldOf')
    if body is None:
        print('FAIL  找不到 ragFieldOf（改名/拆掉？）—— 判据会恒真')
        return 1
    # 判据本体：整段声明里不许出现 remoteKb 的读取。
    # 出现即说明库名是在**响应时**现读的（与条数不同源），
    # 于是"生成期间用户改了库名"会产生一对从未同时成立过的 {kb, injected}。
    if re.search(r'\bRagInject\s*\.\s*remoteKb\s*\(', body):
        print('FAIL  ragFieldOf 体内现读 remoteKb —— 库名与条数不同源，'
              '长生成期间改库名会发出一对没同时成立过的事实')
        return 1
    # 反向：必须确实用上了传进来的形参（否则判据可能是在一个空壳上跑）
    if 'kb' not in body:
        print('FAIL  ragFieldOf 的整段声明里没有出现 kb 形参 —— 抽取可能失效（判据恒真）')
        return 1
    print('PASS  ragFieldOf 用形参 kb（不在响应时现读 remoteKb）')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
