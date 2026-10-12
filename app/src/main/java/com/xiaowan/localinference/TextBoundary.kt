package com.xiaowan.localinference

/**
 * 文本**边界**的纯逻辑（零依赖：不 import 任何东西，连 `org.json` 都不用）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么单独一个文件、而不是挂在某个已有的 object 上
 * ══════════════════════════════════════════════════════════════════════════
 * 需要一个"按字符边界截断"的地方分散在三处，而它们各自的编译单元**不共享依赖**：
 *
 *   · `RagArchive.kt`    —— 纯逻辑（导入导出），不碰 Context / 文件系统；
 *   · `ChatArchive.kt`   —— 同上（它自己的单测**只编它和 SessionStore**，
 *                           多一个依赖就 "unresolved reference" 挂掉）；
 *   · `LlmEngine.kt`     —— 大对象（要 Context / native / Android）。
 *
 * 把这段挂进 `RagIndexStore` 时，`ChatArchive` 就被迫拖进 `RagIndexStore` 的
 * 全部依赖（Context / File / android.util.Log），而 `run_chat_archive_tests.sh`
 * 的源列表里没有它 —— 整套编译以 `unresolved reference: RagIndexStore` 结束。
 * 这不是"补一行源列表"能解决的：那会让一个**纯逻辑**文件开始依赖 Android 栈。
 *
 * 所以它自成一个零依赖的小文件：谁都能引，且**不会**因为引它而多出任何依赖。
 * 与 `rag_index.h` 的 `align_utf8_back` 是同一件事的两个单位（那边按字节，
 * 这边按 UTF-16 code unit），目的都是"不许切在码点中间"。
 */
object TextBoundary {

    /**
     * 按**字符边界**截断到 `limit` 个 code unit —— 绝不切在代理对中间。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么这件事必须在**落盘之前**做（而不是"反正超长的不多"）
     * ═══════════════════════════════════════════════════════════════════════
     * 截断点写在正文**要落进 `texts.jsonl`** 的那条路上。而 Kotlin 的
     * `String.substring` 下标是 **UTF-16 code unit**，一个 emoji（非 BMP 字符）
     * 占**两个** unit：`"😀".length == 2`。`substring(0, n)` 的 `n` 落在
     * 代理对中间时，切出来的是一个**孤立的高位代理**（U+D800~U+DBFF）。
     *
     * 这个值在内存里"看着是对的"—— `JSONObject` 往返、`==` 比较全都通过，
     * 于是单测只要用 `.length` 或字符串相等去断言，**永远是绿的**。
     * 而它一旦 `toByteArray(UTF_8)` 落盘，Java 的 UTF-8 编码器会把这个
     * 非法码点替换成 `?`（U+FFFD）——**不可逆**：
     *
     *   · 用户看到"已导入 1 条"，而正文里存的是被替换过的内容；
     *   · 更坏的是**指纹**：`textsHashOf` 算的是落盘后的字节，而 `load`/检索
     *     读回的也是那些字节，两边自洽 —— 于是没有任何一处会报错，
     *     只有内容悄悄少了一个字符。
     *
     * 与 `rag_index.h` 的 `align_utf8_back` 是同一件事的两个单位（那边按**字节**，
     * 这边按 **UTF-16 code unit**）：两边的目的都是"不许切在码点中间"。
     *
     * 判据只有一条：`s[limit]` 是 **low surrogate** 时说明 `limit` 恰好落在
     * 一个代理对中间，往前收一格（把整个码点留给下一段）。
     * ⚠ 不能用 `(c.code and 0xC0) == 0x80` 那条**按字节**的判据 —— 那是 C++
     * 的 continuation 判法，搬到 UTF-16 上会把"低字节恰好是 0x80~0xBF"的
     * 正常汉字（如 `文` U+6587）误判成半个字符（`LlmEngine.alignUtf8` 的注释里
     * 记着这个真踩过的坑）。
     */
    fun clipAtCharBoundary(s: String, limit: Int): String {
        if (limit <= 0) return ""
        if (limit >= s.length) return s
        var n = limit
        // 切点后面紧跟 low surrogate = 切点把一个码点劈成了两半 -> 往前收一格。
        while (n > 0 && s[n].isLowSurrogate()) n--
        return s.substring(0, n)
    }

    // ────────────────────────────────────────────────────────────────────
    // 软标点表：切分时优先在哪些字符**之后**断开
    // ────────────────────────────────────────────────────────────────────

    /**
     * 表项 = `分隔符 to 它是不是段落级` —— 段落级（`\n\n`）在选切点时**先于**句读级。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么这张表是**一份共享常量**，而不是各处各写一张
     * ═══════════════════════════════════════════════════════════════════════
     * 它有**三个**消费方，而它们的编译单元互不包含：
     *
     *   · `LlmEngine.chunk`  —— 建库 / 追加时按 `targetChars` 切**向量块**；
     *     它与 `rag_index.h` 的 `kChunkSeps` 是逐条契约（那条契约有判据
     *     `tools/rag_index/check_chunk_rules.py` 正面钉着）—— 表分了家而
     *     `t`/`o` 两个数相同，指纹就相同，旧边界算的向量会被当新的用，**静默**；
     *   · `RagArchive.splitLongLine` —— 导入 / 粘贴落库前把"一条装不进嵌入 ctx
     *     的正文"切成多条**条目**（它改的是条目数，用户看得见）。
     *
     * 两个消费方此前各写一张表，且**两张表不一致**：导入那张多了 `…` `:`，
     * 少了对齐用的段落级。它不算错（两张表各自的服务对象不同），但它意味着
     * "换一个切分场景就再抄一遍表"，而抄出来的那张与 C++ 侧没有判据绑着 ——
     * 下一次谁去改其中一个，另一处不会红。共享一份，让"改表"这件事只有一处入口。
     *
     * ⚠ **不含** `.`（小数 / 版本号 / 域名）、`-` `_` `/`（词内连接符）与空格：
     * 在连字符上断开会把 `llama.cpp` 切成两条、而两条都不再是那个词。
     * 那是把用户的正文**改坏**，比"段边界糙一点"严重得多。
     * `rag_index` 那条守卫另外正面钉着"空格 / `-` / `·` 不许进表"。
     */
    val SOFT_CUT_SEPS: List<Pair<String, Boolean>> = listOf(
        "\n\n" to true,   // 段落：作者**显式**画下的语义切分
        "\n" to false,     // 换行
        "。" to false,     // 句号
        "！" to false,
        "？" to false,
        "；" to false,     // 分号
        "，" to false,     // 逗号
        "、" to false,     // 顿号
        "…" to false,
        "!" to false,
        "?" to false,
        ";" to false,
        "," to false,
        ":" to false,
    )

    /**
     * 在窗口 `(pos, end]` 里挑一个**最靠右**的合法切点；返回 `<= pos` = 没找到。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 两条铁律（它们就是本函数存在的理由）
     * ═══════════════════════════════════════════════════════════════════════
     * 1. **只在窗口内找**。上一版的路子是"窗口里找不到就往回找最近的一次"，
     *    于是 `cut <= pos` 时兜底成"前进 1 个字节"，把 `pos` 到那次匹配之间的
     *    正文**整段跳过** —— 不报错、块数正常、内容少一截。
     * 2. **分隔符必须整段落在窗口内**（匹配起点 `<= end - n`）。放宽成
     *    `<= end - 1` 看起来"更贴合窗口末位"，但它会让 1 字符分隔符（换行）
     *    落在窗口末位时被选中、切点变成 `end` —— 那是一处**两边分家**：
     *    C++ 侧是前者。分家后的症状是"同一份文档建库与追加切得不一样"，
     *    逐字相同地看不出来（tools/rag_index/chunk_rule/run_diff.sh 逐字节抓它）。
     *
     * 与 `rag_index.h` 的 `pick_chunk_cut` / `LlmEngine.pickChunkCut` **同一条
     * 控制流**：先段落、后句读；同级里取最靠右的；对齐后 `<= pos` 就当没找到。
     * `tools/rag_index/check_chunk_rules.py` 正面钉着"窗口内找不到就硬截"这一条。
     *
     * @param end 窗口右端（含）。调用方保证 `end <= text.length`。
     * @param maxSepWidth 表里最长分隔符的宽度 —— 找切点时只需回看这么多。
     *   由调用方传进来（而不是在本函数里扫一遍表算）是为了**与 C++ 侧的控制流
     *   逐行同形**：那边同样把"最长分隔符宽度"当已知量。
     */
    fun pickCut(text: String, pos: Int, end: Int, maxSepWidth: Int): Int {
        if (text.isEmpty() || pos >= end || end > text.length) return -1
        for (pass in 0..1) {
            var best = -1
            for ((sep, isPara) in SOFT_CUT_SEPS) {
                if ((pass == 0) != isPara) continue
                val n = sep.length
                if (n > maxSepWidth) continue
                if (end < n) continue
                // `lastIndexOf` 的起点上界 end-n：保证匹配点 p 满足 p+n <= end
                // （分隔符必须**整段**落在窗口内，与 C++ 侧同一口径）。
                val p = text.lastIndexOf(sep, end - n)
                if (p < 0) continue
                val cutRaw = p + n
                if (cutRaw <= pos || cutRaw > end) continue   // 铁律 1：只在窗口内
                val cut = boundaryBefore(text, cutRaw)
                if (cut <= pos) continue                  // 对齐后仍不算
                if (cut > best) best = cut
            }
            if (best > pos) return best
        }
        return -1
    }

    /** [SOFT_CUT_SEPS] 里最长分隔符的宽度（code unit）。切点回看距离 / C++ 侧同形控制流要用。 */
    fun maxSepWidth(): Int {
        var w = 0
        for ((sep, _) in SOFT_CUT_SEPS) if (sep.length > w) w = sep.length
        return w
    }

    /**
     * 把下标 `i` 向前收到一个**字符边界**上（返回的下标处不会切在代理对中间）。
     *
     * 与 [clipAtCharBoundary] 是同一件事、**两种返回**：那个返回截好的字符串
     * （调用方要的是"截到哪"），这个返回下标（调用方要的是"下一段从哪儿起"，
     * 夹在中间的那一步必须能拿到位置）。判据同 [clipAtCharBoundary] 的注释。
     */
    fun boundaryBefore(s: String, i: Int): Int {
        if (i >= s.length) return s.length
        var j = i.coerceAtLeast(0)
        while (j > 0 && s[j].isLowSurrogate()) j--
        return j
    }

    /**
     * 从 `i` **向后**找下一个字符边界；找不到返回 `length`。
     *
     * 只在"上限小于一个码点"（畸形参数）时用得上：那时向前对齐会退到 `pos`、
     * 等于不前进，而 `pos + 1` 会切在代理对中间。
     */
    fun boundaryAfter(s: String, i: Int): Int {
        var j = i.coerceIn(0, s.length)
        while (j < s.length && s[j].isLowSurrogate()) j++
        return j
    }
}
