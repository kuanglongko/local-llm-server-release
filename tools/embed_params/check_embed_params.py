#!/usr/bin/env python3
"""断言 `/v1/embeddings` 的三个**协议参数**真的接上了（0.9.170 新增）。

═══ 守的是哪三条，以及它们为什么必须**静态**守 ═══
用户这轮拍板的三条，失效形态全是"不崩、不报错、接口还 200"：

  ① `dimensions` 必须**明确 400**。静默忽略的后果不是"没生效"，是调用方
     设了 `dimensions:512`、拿到 200、拿到一段 1024 维向量，然后按 512 去用
     —— 静默错位。所以判据是"源码里**有**这条拒绝分支"，而不是"看起来没问题"。
  ② `encoding_format=base64` 必须**真的实现**，且是**小端 float32、整段编码**。
     字节序写反时 base64 解码照样成功、长度照样对，只是数值离谱 —— 没有任何
     一处会报错。所以判据要锚到"位运算里 low byte 写在前面"这种**结构**上，
     不能只 grep `base64` 这个词（那个词在注释里也会出现，恒真）。
  ③ token id 数组必须**先还原成文本**再走同一条编码路径。若哪天有人"顺手优化"
     成"把 id 当字符串拼起来"或"自己重新 tokenize 一遍"，向量就与调用方以为的
     文本无关了，而接口 200。判据是"HTTP 路径上真的调了 detokenize"。

为什么不用单测代替：单测（`EmbeddingsApiTest`）钉的是"算得对不对"，
钉不住"接线有没有接上" —— 把 `handleEmbeddings` 里那句 `detokenize` 删掉，
单测照样全绿（它从不经过 HTTP 层）。

用法：check_embed_params.py <EmbeddingsApi.kt> <HttpApi.kt> <llama_jni.cpp>
"""
import re
import sys
from pathlib import Path




def _strip_comments_only(src):
    """剥注释、**保留字符串字面量**。

    为什么不能直接用 `_fnbody._strip`：它连字符串一起抹成 `""`，于是
    `j.has("dimensions")`、`ef == "base64"` 这类判据全变成 `""` —— 全假红。
    本文件的判据恰恰要读**字段名与名值**，所以只剥注释。

    字符串本身也要正确跳过（里面可能有 `//`、`/*`，比如错误文案里的 URL），
    否则会把字符串内的 `//` 当行注释，切掉半行。
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
            q, j = c, i + 1
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


def kt_raw_span(src, name):
    """取 `fun name(...) ... { ... }` 的**原文区间**（保留字符串字面量）。

    与 `_fnbody.kotlin_fn_body` 的差别只有一处：这里用 `_strip_comments_only`，
    所以字符串里的字段名/名值都还在 —— 本文件的判据读的就是它们。
    """
    code = _strip_comments_only(src)
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


def main():
    if len(sys.argv) < 4:
        print("用法：check_embed_params.py <EmbeddingsApi.kt> <HttpApi.kt> <llama_jni.cpp>")
        return 2
    api_src = Path(sys.argv[1]).read_text(encoding="utf-8")
    http_src = Path(sys.argv[2]).read_text(encoding="utf-8")
    jni_src = Path(sys.argv[3]).read_text(encoding="utf-8")

    bad = 0

    def ck(name, ok, detail=""):
        nonlocal bad
        if ok:
            print("PASS  " + name)
        else:
            print("FAIL  " + name + (" —— " + detail if detail else ""))
            bad += 1

    body = kt_raw_span(api_src, 'fromRequest')
    ck("找得到 EmbeddingsApi.fromRequest", body is not None)
    if body is None:
        return 1

    # ── ① dimensions：存在**拒绝**分支（不是读取后忽略）───────────────────
    # 锚"has(dimensions) 且返回错误"：只 grep `dimensions` 会命中注释（恒真）。
    ck("dimensions 有值就报错（不是读取后忽略）",
       re.search(r'has\(\s*"dimensions"\s*\)', body) is not None
       and re.search(r'has\(\s*"dimensions"\s*\)[\s\S]{0,400}?return\s+Triple\(\s*null\s*,\s*null',
                     body) is not None,
       "没有 dimensions 的拒绝分支 —— 静默忽略会让调用方按错维度截，静默错位")
    # 反向：`dimensions: null` 不该被拒（与字段缺失同义）
    ck("dimensions=null 不拒（只拒有值）",
       re.search(r'isNull\(\s*"dimensions"\s*\)', body) is not None,
       "没判 isNull —— `dimensions: null` 会被当成有值而拒，误伤")

    # ── ② encoding_format：base64 已实现，且是**小端** float32 ─────────────
    ck("encoding_format 接受 base64",
       re.search(r'encoding_format[\s\S]{0,600}?"base64"', body) is not None,
       "base64 没有被接受 —— 调用方按 base64 解一段 JSON 数字数组会直接崩在它那边")
    ck("encoding_format 取值非法仍是 400",
       re.search(r'encoding_format 只支持', body) is not None,
       "非法取值没有明确报错文案（400 的分界要看得见）")

    b64_body = kt_raw_span(api_src, 'base64Of')
    ck("找得到 base64Of", b64_body is not None)
    if b64_body is not None:
        # 小端：最低字节写在 index*4 处。锚"toRawBits + 按 4 字节切分 + 低位在前"
        ck("base64 走 toRawBits（不是先转 double）",
           'toRawBits()' in b64_body,
           "没用 toRawBits —— float 经 double 中转会改位模式，解析出来与原向量不等")
        # 必须锚到"哪一段移多少位"，不能只锚"这几行在不在"：把低位与高位对调
        #（大端）后，`bytes[i*4] =` / `ushr 8` / `ushr 24` 全都还在 ——
        # 只锚存在性的断言照样绿，而数值已经全错。本仓库吃过这个亏。
        ck("base64 是**小端**（低位字节写在 index*4、高位在 index*4+3）",
           re.search(r'bytes\[\s*i\s*\*\s*4\s*\]\s*=\s*\(\s*bits\s+and\s+0xFF', b64_body) is not None
           and re.search(r'bytes\[\s*i\s*\*\s*4\s*\+\s*3\s*\]\s*=\s*\(\s*\(\s*bits\s+ushr\s+24',
                         b64_body) is not None,
           "字节序不是小端（低位应在 index*4、高位在 index*4+3）—— base64 解码照样成功但数值离谱")
        # 不是逐维各编一段：整段先拼成 ByteArray（size*4）再 b64
        ck("base64 是整段编码（不是逐维各编一段）",
           re.search(r'ByteArray\(\s*embedding\.size\s*\*\s*4\s*\)', b64_body) is not None
           and re.search(r'\bb64\(', b64_body) is not None,
           "没有'先拼 4N 字节再整段 base64' —— 逐维编码的长度是 4N 段，调用方解不出来")
        # base64 分支必须**不改** embedding 的长度（NaN 也不折 null，否则字节流错位）
        ck("base64 分支不把非有限值折成 null（否则字节流错位）",
           'if (v.isFinite())' not in b64_body,
           "base64 分支里出现了 isFinite 折 null —— 会让字节流长度不再是 4N")

    # itemJson 必须按 encoding 分岔，且 base64 分支回的是**字符串**
    item_body = kt_raw_span(api_src, 'itemJson')
    ck("itemJson 按 encoding 分岔",
       item_body is not None and 'Encoding.BASE64' in item_body,
       "itemJson 没有 encoding 分支 —— 调用方要 base64 却拿到数字数组")
    ck("base64 项的 embedding 是字符串（不是数字数组）",
       item_body is not None
       and re.search(r'Encoding\.BASE64[\s\S]{0,200}?"\\""\s*\+\s*base64Of', item_body) is not None,
       "base64 项的 embedding 没被拼成 JSON 字符串")

    # ── ③ token id 数组：HTTP 路径上真的**还原成文本**再编码 ───────────────
    hbody = kt_raw_span(http_src, 'handleEmbeddings')
    ck("找得到 handleEmbeddings", hbody is not None)
    if hbody is not None:
        ck("HTTP 路径真的调了 detokenize（id 数组还原成文本）",
           re.search(r'LlmEngine\.detokenize\(', hbody) is not None,
           "没调 detokenize —— id 数组要么被当字符串拼、要么被静默重编码")
        ck("id 还原失败算**这一条**失败（不是整批 500）",
           re.search(r'detokenize\([\s\S]{0,300}?errorItemJson', hbody) is not None,
           "id 越界时没有落到该项的 error（整批 500 会让调用方永远不知道是哪条）")
        ck("base64 的取用来自解析结果 opt.encoding",
           re.search(r'itemJson\(\s*i\s*,\s*vec\s*,\s*opt\.encoding\s*\)', hbody) is not None,
           "itemJson 没带上 opt.encoding —— 调用方要 base64 却拿到数字数组")

    # ── native：detokenize 真的导出，且越界**整条拒绝** ───────────────────
    # 锚"导出符号 + 紧跟的 `(`"，而不是"文件里出现过这个词" —— 后者在改名成
    # `..._nativeDetokenize_GONE` 时照样命中（子串），判据恒真。
    ck("native 导出 nativeDetokenize",
       re.search(r'Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize\s*\(', jni_src) is not None,
       "native 没有 detokenize 导出 —— Kotlin 侧的声明会 UnsatisfiedLinkError")
    dt_body, _ln = _cpp_fn(jni_src, 'Java_com_xiaowan_localinference_LlmEngine_nativeDetokenize')
    ck("找得到 nativeDetokenize 函数体", dt_body is not None)
    if dt_body is not None:
        ck("id 越界整条拒绝（返回空串，不跳过该 id 继续）",
           'nVocab' in dt_body
           and 'NewStringUTF("")' in dt_body
           and 'continue' not in dt_body,
           "越界 id 没有整条拒绝 —— 跳过会让向量对应一段调用方没给过的文本，接口 200")
        ck("用的是嵌入模型那一份 vocab（E.vocab，不是 S）",
           'E.vocab' in dt_body and 'S.vocab' not in dt_body,
           "读的不是嵌入模型的 vocab —— 双常驻下会拿对话模型的 id 空间去还原")

    return 1 if bad else 0


def _cpp_fn(src, symbol):
    """按真括号配对抽出 C 函数体（跳过注释与字符串），只看实现。"""
    src = _strip_comments_only(src)
    i = src.find(symbol)
    if i < 0:
        return None, None
    j = src.find('{', i)
    if j < 0:
        return None, None
    depth, k, n = 0, j, len(src)
    while k < n:
        c = src[k]
        if c == '/' and k + 1 < n and src[k + 1] == '*':
            e = src.find('*/', k + 2)
            k = n if e < 0 else e + 2
            continue
        if c == '/' and k + 1 < n and src[k + 1] == '/':
            e = src.find('\n', k)
            k = n if e < 0 else e
            continue
        if c in '"\'':
            q, m = c, k + 1
            while m < n:
                if src[m] == '\\':
                    m += 2
                    continue
                if src[m] == q:
                    m += 1
                    break
                m += 1
            k = m
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[j:k + 1], j
        k += 1
    return None, None


if __name__ == '__main__':
    sys.exit(main())
