package com.xiaowan.localinference

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识库**正文的导入导出格式**（纯逻辑，宿主可单测，不碰 Context / 文件系统）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 导出的到底带不带走向量 —— 这是本文件全部设计的起点
 * ══════════════════════════════════════════════════════════════════════════
 * 索引里的向量是**「某个嵌入模型 + 某组切块参数 + 某个池化语义」**算出来的，
 * 指纹（见 [RagIndexStore.fingerprintOf]）存在的唯一理由就是让"配不上"变成
 * "宁可重建"。所以导出有两种选法：
 *
 *   · 只导正文（本文件的做法）：导入后是"正文在、索引待重建"——这正是仓库里
 *     已经有的状态（`保存正文` 那条路），不是新造状态。
 *   · 连向量一起导：要求对面那台**同模型文件（size + mtime 都算）**、同维度、
 *     同池化、同切块参数，任何一项对不上都退回重建 —— 用户还以为自己省了时间。
 *
 * 选前者的理由是**失败方向**：连向量导时，指纹对不上会让"用旧语义的向量命中、
 * 注入新正文"，余弦值量纲都对不上、排序像随机，而**接口 200、日志干净**；
 * 只导正文则从第一秒就明说是"正文，索引要重建"。**不装**。
 *
 * 所以信封里**没有** vector / dim / 指纹字段。带上它们就是把上面那条静默错法
 * 重新请回来 —— 这不是"少写几个字段"，是这条格式的设计约束。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么内层键名直接沿用 `texts.jsonl` 的 `t` / `s`
 * ══════════════════════════════════════════════════════════════════════════
 * 同一份数据在"文件里"与"库里"应该是**同一种条目形状**，否则就要写第三份编解码，
 * 而"三份里有一份把空来源写成了 null"是没有任何现象的。这里直接复用
 * [RagIndexStore.encodeTexts] / [RagIndexStore.decodeTextRow] 那一对既有口径：
 * 缺 `t` 或 `s` 任一 = 这行不是我们写的 -> 跳过并**计数**，不猜空值。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 导入接受三种形状（按**内容**判，不靠扩展名）
 * ══════════════════════════════════════════════════════════════════════════
 * 1. 本应用导出的信封：`{"format":"local-llm-rag","version":1,…,"entries":[…]}`
 * 2. 裸 JSON 数组：`[{"t":"…","s":"…"}, …]`
 * 3. 裸 JSONL：一行一个 `{"t":"…","s":"…"}`（用户从别处导出的那种）
 * 4. 纯文本 `.txt` / `.md`：一行一条（见 [decodePlainText]，**不经过** [decode]）
 *
 * 按内容判而不是按扩展名：SAF 上不少文件管理器把 `.json` 报成 `text/plain`
 * 或 `application/octet-stream`，按扩展名判会出现"明明在，却选不中"，
 * 而那种现象会被读成"这个 App 不支持导入"。
 *
 * 但**信封认不出来就整份拒绝**（第 1 种）：`{"sessions":[]}` 这类别的 App 的
 * 备份文件若"尽力解析"，会得到"导入 0 条"，而用户读成"我的知识库是空的" ——
 * 真相是他选错了文件。所以只有"裸数组 / 裸 JSONL"这两种**明确是条目流**的形状
 * 才走宽松路径，其余一律拒绝并说清读到了什么。
 */
object RagArchive {

    /** 信封的格式名。与聊天记录的 `local-llm-chat` **分开**：选错文件能被明确拒绝。 */
    const val FORMAT = "local-llm-rag"

    /** 当前格式版本。**只在读侧放宽、写侧恒为当前值**。 */
    const val VERSION = 1

    /**
     * 单次导入的条目数上限。
     *
     * 与 [RagIndexStore.ChunkParams.maxChunks]（16384）对齐：索引本来就装不下更多，
     * 收进来了也只会在建库时被截断 —— 而"被截断"与"文件里就这么多"长得一样。
     * 超限**整份拒绝**而不是截断：静默丢掉后半份笔记是不可恢复的。
     *
     * ⚠ 这两个数必须**一起**动。只抬 maxChunks 而不抬这里，会出现
     * "导入通过了但建库被截断"—— 又一条静默路径（用户以为 2 万条全进来了）。
     * `tools/run_rag_archive_guard.sh` 正面钉住两者相等。
     */
    const val MAX_IMPORT_ENTRIES = 16384

    /** 单条正文上限。超长截断并计数（防一份文件撑爆 UI 与 prompt）。 */
    const val MAX_TEXT_CHARS = 100_000

    /**
     * 单条正文的**推荐**上限（字符）。超过它不拒绝、**也不截**，而是切段。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么必须有这个数（它是"导入成功、检索上一片空白"的直接解药）
     * ═══════════════════════════════════════════════════════════════════════
     * 嵌入模型常驻时 `n_ctx` 默认 **512**（`llama_jni.cpp` 的 `kEmbedDefaultCtx`），
     * 而 `nativeEmbed` 对超过 ctx 的输入**截断到 511 个 token 再编码** —— 它不失败，
     * 只打一行日志。于是"一条 3 万字的正文"从头到尾只贡献了**前 511 个 token**
     * 的语义：正文在库里、界面显示 N 条，而那一段之后的每一个关键词都检索不到，
     * 接口 200、日志干净。
     *
     * ⚠ 这个函数的成败**就落在这一条上**。用户报的现象是"3 MB 长文本导入后界面卡住"，
     * 卡住是其一（见 EngineActivity 的进度回执），而即使不卡，那份长文按**行**收下
     * 之后仍然是一条远超 ctx 的条目 —— 导入这条路把一个"能装进去但检索不到"的
     * 结果端给用户，而界面上一切正常。
     *
     * 512 是**硬切口径**（token），而这里是**字符**口径，且刻意比它小：中文在多数
     * 嵌入 vocab 里约 1 token/字，取 448 留出 12% 余量，好让 BPE 把常见词合成
     * 一个 token 时那一条也不会撞上 ctx。**宁可多切一段，也不让一段被静默截断。**
     *
     * 与 [MAX_TEXT_CHARS] 的关系：那个是"再大就截断并计数"的**硬顶**（防一份文件
     * 撑爆 UI 与 prompt），这个是"超过就切段"的**软线**。两个都在：软线只管切，
     * 硬顶兜住"某种畸形输入切完之后仍然巨大"的极端情形。
     */
    const val EMBED_MAX_CHARS = 448

    /**
     * 解析结果。
     *
     * `error == null` 才算成功；`skipped` 是被**有意丢掉**的坏行条数
     * （缺 `t`/`s` 的行、被截断的超长正文）—— 它必须报给用户，
     * 否则"导入后少了几条"看起来像 bug。
     *
     * `library` 是信封里带的**库名**（裸数组 / 裸 JSONL 时为空串）。
     * 导入时用它默认填"新库名"，而不是静默落到某个默认库 ——
     * 落到默认库就等于把别人的笔记混进当前库，而界面上什么都不会说。
     */
    data class Parsed(
        val entries: List<RagIndexStore.Entry>,
        val library: String,
        val skipped: Int,
        val error: String?,
        /**
         * 因超长被切成多段、而窗口里**一个软标点都没有**（只能硬切）的次数。
         *
         * 与 [skipped] 分开：那个是"丢了内容"（超过 [MAX_TEXT_CHARS] 的硬顶被截掉），
         * 这个是"内容一条没丢、只是某些段的边界没有句读可依"。两者处置不同 ——
         * 前者要去改文件，后者只是检索边界糙一点，不拦用户。
         *
         * 为什么必须报：一份没有标点的长串（Base64 / 一整篇无标点的外文）在
         * 界面上与"正常切段"**逐字相同**（条目数、条数、正文全对），
         * 而它的检索粒度天生差。不说，用户就只能猜。
         */
        val splitNoPunct: Int = 0,
    ) {
        val ok: Boolean get() = error == null
    }

    // ────────────────────────────────────────────────────────────────────
    // 写：序列化
    // ────────────────────────────────────────────────────────────────────

    /**
     * 把某个库的正文编成信封 JSON 字符串。
     *
     * 行序**原样保持**：导出的语义是"把库里的东西原样带走"，重排就等于
     * 换了一份数据（虽然重建后检索仍能工作，但用户的 diff 会显示全变了）。
     *
     * 正文**不做任何裁剪**：导出套上 [MAX_TEXT_CHARS] 会让用户拿到一份
     * "看起来完整、实际被截掉开头"的文件，而无从判断是导出坏了还是本来就短。
     */
    fun encode(library: String, entries: List<RagIndexStore.Entry>, exportedAt: Long): String {
        val arr = JSONArray()
        for (e in entries) {
            // `s` 为空时**也写出来**（`"s":""`）：缺键与空值是两种不同的东西，
            // 读的那一侧按"键不存在 = 这份文件不是我们写的"处理（见 decodeEntry）。
            arr.put(JSONObject().put("t", e.text).put("s", e.source))
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAt)
            .put("library", library)
            .put("entries", arr)
            .toString()
    }

    // ────────────────────────────────────────────────────────────────────
    // 读：解析 + 校验
    // ────────────────────────────────────────────────────────────────────

    /**
     * 解析一份文件（三种形状自动判）。
     *
     * 判据顺序刻意从"最外层"读到"最内层"：信封（若有）的格式名 → 版本 →
     * entries 数组 → 逐条。任何一层不合法都返回**具体**的 error 文案 ——
     * "导入失败"四个字会让用户以为是自己操作错了，而真相可能是选错了文件。
     */
    fun decode(text: String): Parsed {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return fail("文件是空的")
        // 形状按**第一个非空白字符**判：`[` = 裸数组，`{` = 可能是信封也可能是
        // 一行 JSONL（逐行解析）。这是唯一能在"内容"上做的判别，且不依赖扩展名。
        val first = trimmed[0]
        return when {
            first == '[' -> decodeBareArray(trimmed)
            first == '{' -> decodeObjectOrLines(trimmed)
            else -> fail("这不是知识库正文文件（开头是「$first」，应为「[」或「{」）")
        }
    }

    /**
     * 形状 ④：纯文本 `.txt` / `.md` —— 一行一条。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 它为什么必须由**内容**判，而不能由扩展名判
     * ═══════════════════════════════════════════════════════════════════════
     * 这条路的用户动因不是"想导一个 txt"，而是**Android 的剪贴板装不下他要粘的
     * 那段字**：一份几百 KB 的笔记根本粘不进输入框，而它作为文件是完整的。
     * 所以它要接的是**粘贴建库那条路**（[EngineActivity.ragDocEntries]，一行一条），
     * 不是"再解析一种格式"。与 JSON 那三种形状的判据也不打架：
     *
     *   · 含 `NUL` -> **拒绝**。二进制被逐字节按行切开，得到的是一份"每行都读得
     *     出来、每一行都不是他说的话"的索引 —— 检索**会命中**，命中的是乱码。
     *     这是本形状唯一不可归因的错法，所以它排在最前面。
     *   · 开头是 `[` -> **拒绝**，并说明它该走「导入库」。⚠ 这一条不是给 [decode]
     *     用的（那边在 `[` 上就分流走了），它护的是 UI 侧那条会把"看起来像 JSON
     *     数组的**短文本**"送进来的路径（[EngineActivity.ragDocEntries]）——
     *     在那里，"用户手里是 JSON 数组"与"我要把一段文字切成条目"是两个动作，
     *     判据必须在这一处（唯一一份）说清，而不是在 UI 里各猜一套。
     *   · 其余一律按行收下。**认不认得出是 JSON 在这里不重要** —— 以 `{` 开头
     *     （被粘碎的 JSON 片段）按字面收下是**有意**的：粘贴建库那条路本来就把
     *     任何一行文字当正文，这里若把它拒掉，"粘贴能建、导入却不行"就分家了。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么 `skipped` 在这一路上**只报计数、不掐掉内容**
     * ═══════════════════════════════════════════════════════════════════════
     * 空行（`CR` 也剥掉，Windows 记事本导出的文件照样能用）不算条目。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 超长的一行：**切段**，不是截断、也不是"一条"
     * ═══════════════════════════════════════════════════════════════════════
     * 一份没有手动换行的文档（整篇一段）在这里是**一行**。此前那条路把它收成
     * **一条**正文（上限 [MAX_TEXT_CHARS]，超出部分截掉），于是两种坏法同时发生：
     *
     *   · 3 MB 的整篇一段 -> 库列表里写着「1 条」，里面装着 3 MB 正文。用户在
     *     条目列表里看到一行、在正文框里看到一片汪洋，"我的文件被切成了几行"
     *     这件事没有任何地方说得清；
     *   · 一条 3 万字（在 [MAX_TEXT_CHARS] 之内、所以**连计数都不会报**）走到
     *     建库时被嵌入层按 n_ctx 截到前 511 个 token —— 那一行之后的每一个关键词
     *     都检索不到，而建库成功、界面上一切正常。
     *
     * 所以超过 [EMBED_MAX_CHARS] 的行**按软标点切成多段**（见 [splitLongLine]），
     * 切出来的每一段都是**独立条目**：段数看得见（回执里报条数）、每一段都装得进
     * 嵌入模型的 ctx、检索的粒度也对（命中一段比命中一整篇更有用）。
     *
     * 切不出软标点的极长串（Base64 / 一整篇无标点的外文）**照样切**，只是切在
     * 字符边界上、并如实计入 [Parsed.splitNoPunct] —— 那种段没有句读可依，
     * 边界是硬的，说清楚比不说强。
     *
     * @param name 选中文件的**显示名**，只用来派生**来源标签**（`文件-<名>`）。
     *   空串合法（见 [plainSourceLabel]），此时标签回落到 `文件`。
     *
     * ══════════════════════════════════════════════════════════════════════
     * ⚠⚠ 它**不再**派生"要导进哪个库" —— 那是用户选中的那个库（ISSUE #259）
     * ══════════════════════════════════════════════════════════════════════
     * 上一版这里回填的是 `plainLibName(name)`，也就是**把文件名当库名**。
     * 后果是这条按钮与「导入库」**语义不一致**：
     *
     *   · 「导入库」（.json / .jsonl）—— 目标库 = 用户**选中的那个库**
     *     （信封里带 `library` 时优先，否则用当前库），文件叫什么名字无关；
     *   · 「导入文档」（.txt / .md）—— 目标库 = **文件名**。
     *
     * 于是"往现有库里追加一篇文档"这件事只能靠**先把文件改名**成那个库的名字 ——
     * 用户报的原文：*"他现在默认将 txt 文本名字，当做导入的库名，这与导入 jsonl
     * 的逻辑不一样，导致追加到现有库只能通过事先改到与库名一样来实现"*。
     * 改名是 App 该在界面里问一句的事，不是用户要去文件管理器里干的事；
     * 而它更坏的形态是**静默建出第二个库**：用户以为在往 A 里追加，
     * 实际磁盘上多了一个"文件名"库 —— 界面上两句提示（"已导入"、"库名 A"）
     * 都在说"成了"，而 A 里一条都没多。
     *
     * 所以这里回填 `library = ""`（与 [decodeBareArray] / 裸 JSONL 同形），
     * 由 UI 侧 [EngineActivity.confirmRagImport] 按**同一条规则**取目标库：
     * 信封带的优先 -> 否则当前选中的库。两条导入路从此共用那个答案。
     * ⚠ 这不是把"目标库"这件事**弄丢**了，是把它交回唯一知道答案的人 ——
     *   用户在 [EngineActivity.ragKbEt] 里选的那个库。
     */
    fun decodePlainText(text: String, name: String): Parsed {
        if (text.isEmpty()) return fail("文件是空的")
        if (text.contains('\u0000')) {
            return fail("这看起来不是文本文件（内容里有 NUL 字节）—— " +
                "导入库支持 .json / .jsonl，导入 .txt / .md 走的是这个按钮")
        }
        val t = text.trim()
        if (t.startsWith("[")) {
            return fail("这是 JSON 数组（一行一条的库文件），请用「导入库」；" +
                "这条按钮读的是 .txt / .md 那种一行一条的纯文本")
        }
        val label = plainSourceLabel(name)
        var nosplit = 0
        val out = ArrayList<RagIndexStore.Entry>()
        for (raw in text.split('\n')) {
            val line = raw.trim().trimEnd('\r').trim()
            if (line.isEmpty()) continue
            // ⚠ 条数上限的判据在**切段之后**逐段做（切出来的每一段都是要落库的一条）。
            //    切之前先判会漏掉"一千万行、每行都很长"这种形态。
            for (seg in splitLongLine(line, onHardCut = { nosplit++ })) {
                if (out.size >= MAX_IMPORT_ENTRIES) {
                    return fail("文件超过单次上限 $MAX_IMPORT_ENTRIES 条（未导入任何内容）")
                }
                out.add(RagIndexStore.Entry(seg, label))
            }
        }
        if (out.isEmpty()) return fail("文件里没有可用的条目（没有非空行）")
        // 目标库**不在这里定**（见上面那段）：回填空串，由 UI 侧按与「导入库」
        // 同一条规则取"用户选中的库"。文件名只进来源标签（[plainSourceLabel]）。
        return Parsed(out, "", 0, null, splitNoPunct = nosplit)
    }

    /**
     * 一条超长正文 -> 若干段，每段不超过 [EMBED_MAX_CHARS]（见那里为什么是 448）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 逐级降级，最后一级**一定切得动**
     * ═══════════════════════════════════════════════════════════════════════
     * 1. 在窗口 `(pos, pos+max]` 里找**最靠右**的软标点（段落 → 句读，见
     *    [TextBoundary.SOFT_CUT_SEPS]），在它**之后**切（标点跟着前一段，读起来不断句）；
     * 2. 窗口里一个软标点都没有（Base64、一长串没有标点的外文、被粘碎的一行 JSON）
     *    -> 在 `pos + max` 处**按字符边界**硬切，并让调用方计入 [Parsed.splitNoPunct]。
     *
     * ⚠ 第 1 级**不是**本文件自己实现的：走 [TextBoundary.pickCut]，与建库那一路
     *   （[LlmEngine.chunk] 的 `pickChunkCut`、C++ 的 `pick_chunk_cut`）**同一条
     *   控制流、同一张表**。三处各写一套的代价不是"重复几行"，是**改一处另两处不会红**
     *   —— 而"切在哪儿"这件事两边一岔，症状是"同一份文档建库与导入切得不一样"，
     *   逐字相同地看不出来。
     *
     * ⚠ **空白级（"窗口里没有软标点就找最靠右的空白、在它之前切"）已被删掉。**
     *   它不只是"多一级降级"，它和这个函数的收尾 `trim()` 合起来**真的丢字**：
     *   空白级切在**空白之前**（`cut = i`），而软标点级切在**分隔符之后**
     *   （`cut = p + n`）—— 同一个函数里"切点含不含分隔符"两种口径混着，
     *   段尾 `trim()` 一收，那个被切在"之前"的空白就**两边都不属于**：
     *     · 建库（`LlmEngine.chunk`）从 `0.9.175` 起就否掉过这五级阶梯的第 4/5 级
     *       （空格 / `-`），理由正是它是"整块丢字"的主要触发源；导入这条路
     *       此前**正带着被否掉的那一级在跑**；
     *     · 实测 `"word ".repeat(400).trim()`（1999 字，唯一分界符是空格）
     *       拼回去丢 4 字；`"    int a = 1;\n" × 100` 丢 15 字。而每一段都是
     *       **独立条目** —— 落在夹缝里的正文连向量都不会有。
     *       （`RagArchiveTest.kt` ⑦ 那两条按**旧实现实跑过**，确实会红；
     *       不是"看着像边界"的语料 —— 我第一版挑了缩进 JSON，它在旧实现下
     *       是**绿的**：整段没软标点、又没过 448，一条道走到硬切。）
     *   删掉它之后，硬切接管空白处：段数略变（原来靠空白切的地方改为硬切），
     *   但**每段都还在**，`joinToString("") == 原文` 这条判据才立得住。
     *
     * ⚠ 第 2 级是这条路的**存在理由**：没有它，一份没有标点的长串就会走回
     * "一条巨大的条目"，而那条路的问题是**静默的**（嵌入层按 ctx 截断，日志里
     * 只有一行 `[embed] 文本 N tok 超过 ctx`，界面上什么也看不出来）。
     * 硬切至少让内容**全都进了索引**，只是某些段没有句读可依。
     *
     * ⚠ 每一级都必须**严格前进**：窗口内找不到可切点、或算出来的切点 `<= pos`
     * （中文尾巴 / 代理对 / max 小于一个码点）时，一律回落到下一级，绝不"不前进"
     * （那会死循环，把主线程焊死在构建字符串上）。
     *
     * 与 [RagIndexStore.ChunkParams] 的分工：那个是**建库时**按 targetChars 切块
     * （几百条短笔记根本不该被切），这个是**导入 / 粘贴落库前**把"一条装不进 ctx
     * 的正文"切成多条 —— 它改的是**条目数**，用户看得见（回执里报条数、
     * 条目列表里能逐条删改）。
     *
     * @param onHardCut 第 2 级发生一次就回调一次（调用方拿它凑 [Parsed.splitNoPunct]）。
     */
    fun splitLongLine(line: String, max: Int = EMBED_MAX_CHARS,
                      onHardCut: () -> Unit = {}): List<String> {
        // ⚠ 空串**要交回空表**而不是 `listOf("")`：条目是"库里有东西"的单位，
        //   一条空正文会变成正文文件里一个 `{"t":"","s":…}` 的行 —— 而 `decodeTextRow`
        //   把它当坏行（空 t 不可用），于是它占着行号却永远读不出来，
        //   条目列表里就多出一个"这一行读不出来"的红字，用户删不掉也改不掉。
        if (line.isEmpty()) return emptyList()
        if (max <= 0 || line.length <= max) return listOf(line)
        // 表里最长分隔符的宽度：找切点时只需要回看这么多（由 [TextBoundary.maxSepWidth]
        // 给出，而不是在本函数里再扫一遍表 —— 与 C++ 侧把宽度当已知量同一形状）。
        val sepMaxWidth = TextBoundary.maxSepWidth()
        val out = ArrayList<String>()
        var pos = 0
        while (pos < line.length) {
            val end = minOf(pos + max, line.length)
            if (end >= line.length) {
                out.add(line.substring(pos))
                break
            }
            // ① 段落 + 软标点（窗口 `(pos, end]` 里最靠右的一个），在它之后切。
            //    ⚠ 走的是 [TextBoundary.pickCut] —— 与建库那一路（[LlmEngine.chunk]
            //    的 `pickChunkCut`）**同一条控制流、同一张表**。此前这里是第二份实现
            //    加第二张表，而第二张表没有任何判据绑着，改一处另一处不会红。
            var cut = TextBoundary.pickCut(line, pos, end, sepMaxWidth)
            if (cut <= pos) {
                // ② 硬切：按字符边界对齐（切在代理对中间会留下孤立代理，
                //    落成 UTF-8 时变 `?` 且不可逆 —— 与 entryOf 同一条理由）。
                // ⚠ 这里要的是**下标**，不是截出来的字符串 —— 所以走 boundaryBefore()
                //   而不是 TextBoundary.clipAtCharBoundary（那个返回 String，赋给
                //   `cut` 是类型错；本仓库另外三处截断用它是对的，因为那里要的就是串）。
                cut = TextBoundary.boundaryBefore(line, end)
                // max 小于一个码点（畸形参数）时向前对齐会退到 pos：只能往后推一个码点。
                if (cut <= pos) cut = TextBoundary.boundaryAfter(line, pos + 1)
                onHardCut()
            }
            // ⚠ 收尾**不做任何 trim**：切段是**内部**切，而 `trim()` 只该作用在
            //   整行两端的空白上（调用方 `decodePlainText` 已经 `line.trim()` 了）。
            //   在循环里 trim 的代价是**两头都不属于**：段尾被 `trimEnd` 收掉、
            //   段首又被下一轮的 `trimStart` 收掉 —— 同一截空白被吃两次，
            //   于是"导入 2000 字、库里只有 1998 字"，而条目数 / 每段长度 / 检索
            //   全都正常，只有 `joinToString("") == 原文` 对不上（判据③抓的就是它）。
            //   保留原文的每一段、只把外沿留给调用方，是唯一能让判据立住的口径。
            val end0 = minOf(cut, line.length)
            val seg = line.substring(pos, end0)
            if (seg.isNotEmpty()) out.add(seg)
            pos = maxOf(end0, pos + 1)
        }
        return out
    }

    /** 纯文本导入时写给每条条目的来源标签（与粘贴建库那条路的"粘贴-<库名>"同一形状）。 */
    fun plainSourceLabel(fileName: String): String {
        val base = fileName.substringBeforeLast('.').trim().replace('\n', ' ').take(60)
        return if (base.isEmpty()) "文件" else "文件-$base"
    }

    /**
     * 形状 ①：裸 JSON 数组。
     *
     * 走宽松路径（**不要求** format 字段）：这种文件是用户从别处导出的，
     * 它的形状本身就说明了"这是一串条目"。但仍要求每条**同时**有 `t` 与 `s`，
     * 缺了就跳过并计数 —— 与库里同一口径。
     */
    private fun decodeBareArray(text: String): Parsed {
        val arr = try { JSONArray(text) } catch (t: Throwable) {
            return fail("不是合法的 JSON：${t.javaClass.simpleName}")
        }
        if (arr.length() > MAX_IMPORT_ENTRIES) {
            return fail("文件里有 ${arr.length()} 条，超过单次上限 $MAX_IMPORT_ENTRIES（未导入任何内容）")
        }
        var skipped = 0
        val raw = ArrayList<RagIndexStore.Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { skipped++; continue }
            val e = entryOf(o)
            if (e == null) { skipped++; continue }
            raw.add(e.first)
            skipped += e.second
        }
        if (raw.isEmpty()) return fail("文件里没有可用的条目（$skipped 条无法识别）", skipped)
        val (out, nosplit) = explodeLongEntries(raw)
        return Parsed(out, "", skipped, null, splitNoPunct = nosplit)
    }

    /**
     * 形状 ②/③：以 `{` 开头 —— 可能是信封，也可能是**一行一条**的 JSONL。
     *
     * 判据是"整个文件能不能当一个 JSON 对象解析"：
     * · 能，且 `format` 对得上 → 信封（形状 ②）；
     * · 能，但 `format` 对不上 → **拒绝**。这是本条最关键的一处：
     *   别的 App 的备份文件（`{"sessions":[…]}`、`{"version":1,"data":[…]}`）
     *   若在这里被"尽力解析"，会得到"导入 0 条"或更糟 —— 把无关内容当笔记收进来。
     * · 不能（多行 JSON 各自合法但拼起来不是合法 JSON）→ 按 JSONL 逐行解析（形状 ③）。
     *
     * ⚠ 为什么要区分"能当 JSON 解析但 format 不对"与"根本不能当 JSON 解析"：
     * 前者是"选错了文件"，文案要说清；后者是"这确实是一份 JSONL"，
     * 它同样是明确的条目流形状，该走宽松路径。混为一谈会把 JSONL 误判成坏文件。
     */
    private fun decodeObjectOrLines(text: String): Parsed {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root != null && root.has("format")) {
            // 有 format 字段 = 声称自己是某一种信封。那就按信封的规矩办：
            // 对不上就拒绝，不做"尽力解析"。
            return decodeEnvelope(root)
        }
        if (root != null && root.has("entries")) {
            // 有 entries 但没有 format：可能是早期/手工改过的导出文件。
            // 这里**接受**（形状明确是一批条目），但版本号仍要判。
            return decodeEnvelope(root)
        }
        if (root != null) {
            // ⚠⚠ 走到这里**必须先数行**，再决定认不认这个对象。
            //
            // JSONObject 不是"严格的解析器"：它从第一个 `{` 开始吃，遇到配平的 `}` 就
            // 收工，**后面的字节全部忽略**。于是
            //
            //     {"t":"甲","s":"x"}\n{"t":"乙","s":"x"}\n{"t":"丙","s":"x"}
            //
            // 这份**三行 JSONL** 能 parse 成 `{"t":"甲","s":"x"}` —— 一个合法的单条
            // 对象。若照旧认它，用户选进来的 3 条 import 后只剩 **1 条**，而且
            // `error == null`、`skipped == 0`：确认框说"文件里有 1 条正文"、Toast 说
            // "已导入 1 条"，余下两条**无声消失**。这正是本仓库最恨的那种静默错。
            //
            // 所以把"根对象"当作**候选**：只有当整份文本除这一个对象外再无别的
            // 非空行时，它才能充当"单行 JSONL"。多行文本一律按行解析 —— 与
            // 文件头"三种形状按**内容**判"同一条准则，只是这里补上了"内容里
            // 到底有几行"这个此前被 JSONObject 悄悄吃掉的事实。
            val nonBlank = text.lines().count { it.isNotBlank() }
            if (nonBlank <= 1 && root.has("t") && root.has("s")) {
                // 只有一行、且本身就是一条条目（连末尾换行都可能没有）的 JSONL。
                // 不认它会让用户手里那份单行文件报"这不是知识库文件"，
                // 而那正是他刚导出的东西（见下方 4c 用例）。
                val one = entryOf(root)
                if (one != null) {
                    // ⚠ 单行 JSONL 也**必须**走切段：一个文件里只有一条正文时，
                    //   "够不够长"与"它是第几行"两件事都没有区别 —— 而同一条正文
                    //   装进信封（会切）与装成单行 JSONL（不切）得到**不同的条目数**，
                    //   分岔之后用户只会以为其中一份文件坏了。
                    val (out, nosplit) = explodeLongEntries(listOf(one.first))
                    return Parsed(out, "", one.second, null, splitNoPunct = nosplit)
                }
            }
            if (nonBlank > 1) {
                // 多行：无论第一行长什么样，都按 JSONL 逐行 —— 那才是这份文件的
                // 真实形状。逐行解析失败的行会**被计数**（skipped），不像上面那样
                // 静默吞掉；错误从一个"看不见的丢失"变成一行可读的提示。
                return decodeLines(text)
            }
            // 真·单行，但既不是信封，也不是一条条目。拒绝 —— 这正是
            // `{"sessions":[…]}`、`{"version":1,"data":[…]}` 那一类文件落入的分支。
            // 不"尽力解析"：那样会得到"导入 0 条"，用户读成"我的笔记是空的"。
            return fail("这不是知识库正文文件（没有 format / entries / t 这些字段，"
                + "读到的顶层键：${root.keys().asSequence().take(4).joinToString("/").ifEmpty { "无" }}）")
        }
        // 不是单个 JSON 对象 -> 按 JSONL 逐行
        return decodeLines(text)
    }

    /** 信封：格式名 → 版本 → entries → 逐条。顺序反了等于没拒绝（见守卫的行序判据）。 */
    private fun decodeEnvelope(root: JSONObject): Parsed {
        val fmt = root.optString("format", "")
        if (fmt.isNotEmpty() && fmt != FORMAT) {
            return fail("这不是本应用的知识库文件（format = 「$fmt」，应为「$FORMAT」）")
        }
        val ver = root.optInt("version", -1)
        // 缺 version 且没有 format 时按允许（手工文件）；但只要声称了格式，
        // 版本就必须在支持区间内 —— 高于当前版本不猜（按已知字段硬解会静默丢新字段）。
        if (fmt == FORMAT && (ver <= 0 || ver > VERSION)) {
            return fail("文件版本 $ver 不被当前版本支持（支持 1..$VERSION）")
        }
        val arr = root.optJSONArray("entries")
            ?: return fail("文件里没有 entries 列表")
        if (arr.length() > MAX_IMPORT_ENTRIES) {
            return fail("文件里有 ${arr.length()} 条，超过单次上限 $MAX_IMPORT_ENTRIES（未导入任何内容）")
        }
        var skipped = 0
        val raw = ArrayList<RagIndexStore.Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { skipped++; continue }
            val e = entryOf(o)
            if (e == null) { skipped++; continue }
            raw.add(e.first)
            skipped += e.second
        }
        if (raw.isEmpty()) return fail("文件里没有可用的条目（$skipped 条无法识别）", skipped)
        val (out, nosplit) = explodeLongEntries(raw)
        return Parsed(out, root.optString("library", ""), skipped, null, splitNoPunct = nosplit)
    }

    /**
     * JSONL：一行一个 JSON 对象。
     *
     * 空行**跳过且不计数**：绝大多数编辑器会在末尾留一个换行，把它算成"坏行"
     * 会让每次导入都报一次假警。只有"非空行但不是合法条目"才算坏行。
     */
    private fun decodeLines(text: String): Parsed {
        val lines = text.split('\n')
        var skipped = 0
        val raw = ArrayList<RagIndexStore.Entry>()
        for (line0 in lines) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            val o = runCatching { JSONObject(line) }.getOrNull()
            if (o == null) { skipped++; continue }
            val e = entryOf(o)
            if (e == null) { skipped++; continue }
            raw.add(e.first)
            skipped += e.second
        }
        if (raw.isEmpty()) return fail("文件里没有可用的条目（$skipped 条无法识别）", skipped)
        val (out, nosplit) = explodeLongEntries(raw)
        return Parsed(out, "", skipped, null, splitNoPunct = nosplit)
    }

    /**
     * 三种 JSON 形状**共用**的最后一跳：把每条超长正文按 [EMBED_MAX_CHARS] 切段。
     *
     * 为什么必须抽成一处、且放在三条路**汇合之后**：同一条 3 万字的正文，
     * 装在本应用的信封里、装在一个裸 JSON 数组里、装在一行 JSONL 里，
     * 是**同一件事**。三条路各切一遍必然分岔（其中一条忘了切、或者用了另一个
     * 上限），而分岔后的现象是"同一个库，用信封导入 12 条、用 JSONL 导入 3 条" ——
     * 用户只会以为其中一份文件坏了。
     *
     * 段数**不设上限**（不像 [MAX_IMPORT_ENTRIES] 那样整份拒绝）：一条 10 万字的
     * 正文切成 224 段是**正常**结果，不是错误，拦它等于告诉用户"你的笔记太长，
     * 不给导"。总量控制在 [decodeLines] 那一层由 [MAX_IMPORT_ENTRIES] 兜底。
     */
    private fun explodeLongEntries(entries: List<RagIndexStore.Entry>)
            : Pair<List<RagIndexStore.Entry>, Int> {
        var nosplit = 0
        // 快路径：没有一条超长时**原样返回同一个 list**（绝大多数导入都是这样，
        // 不必为每一份文件多复制一遍几百条条目）。
        if (entries.none { it.text.length > EMBED_MAX_CHARS }) return entries to 0
        val out = ArrayList<RagIndexStore.Entry>(entries.size)
        for (e in entries) {
            for (seg in splitLongLine(e.text, EMBED_MAX_CHARS) { nosplit++ }) {
                out.add(RagIndexStore.Entry(seg, e.source))
            }
        }
        return out to nosplit
    }

    /**
     * 从一条 JSON 对象取条目。**缺 `t` 或 `s` 任一 -> null（这条不是我们写的）**。
     *
     * 为什么"缺键"不等于"空值"：正文与来源同属一条条目，来源为空时导出方会写出
     * `"s":""`。一个连 `s` 都没有的对象只可能来自截断或别的格式 —— 猜一个空来源
     * 会让它在注入时多出一个空尾巴。这与 [RagIndexStore.decodeTextRow] 同一口径，
     * 不另立一套判断（两套会在"什么算坏行"上分岔）。
     *
     * 返回 `(条目, 额外跳过数)`：只有超过 [MAX_TEXT_CHARS] 硬顶的那一步会产生
     * 额外的跳过计数（超长截断按**字符边界**截 —— 裸 `substring` 切在代理对中间
     * 会留下孤立代理：它在内存里与 `==` 比较下"看着是对的"（单测用 `.length`
     * 断言恒绿），落成 UTF-8 时被替换成 `?`（不可逆），而用户看到的是"已导入"。
     * 见 [TextBoundary.clipAtCharBoundary]）。
     *
     * ⚠ **[EMBED_MAX_CHARS] 的切段不在这里做**，它由 [decodeLines] / [decodeBareArray] /
     * [decodeEnvelope] 的**调用方那一层**统一做（见 [explodeLongEntries]）。
     * 若在这里做，同一条超长正文在"信封 / 裸数组"与"JSONL"两条路上会走出
     * 不同的条目数 —— 而这两种形状只是同一份数据的外壳。
     */
    private fun entryOf(o: JSONObject): Pair<RagIndexStore.Entry, Int>? {
        if (!o.has("t") || !o.has("s")) return null
        val t = o.optString("t", "")
        if (t.isEmpty()) return null
        val s = o.optString("s", "")
        var extra = 0
        val text = if (t.length > MAX_TEXT_CHARS) {
            extra = 1
            TextBoundary.clipAtCharBoundary(t, MAX_TEXT_CHARS)
        } else t
        return RagIndexStore.Entry(text, s) to extra
    }

    // ────────────────────────────────────────────────────────────────────
    // 从 SAF 的 Uri 读一份文本（纯文本导入专用的入口）
    // ────────────────────────────────────────────────────────────────────

    /** [readText] 的失败原因 —— 文案**一律不出现** Uri 与文件名（见 [readText]）。 */
    class NotText(val reason: String) : Exception(reason)

    /** 单份文件的字节上限（16 MB）。见 [readText] 里"选错文件"那一段。 */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024

    /**
     * 把用户选中的文件读成 UTF-8 文本。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么读出来的字节要被**检查**，而不是直接 toString
     * ═══════════════════════════════════════════════════════════════════════
     * 选错文件这件事在这一条路上**没有 JSON 那种兜底**：JSON 那边一份别的文件
     * 会因为"形状不认识"被拒（[decode]），而一个 PDF / APK / 图片被读成文本
     * **照样是文本** —— 一行行切成条目、建库成功、检索**能命中**，命中的是乱码。
     * 所以这里三道闸：
     *   · `NUL` 字节 = 二进制，直接拒（[decodePlainText] 里还有一道，两道都要 ——
     *     它同时也给"文本是从别处来的"调用方兜底，不只这一条入口）；
     *   · 体积超 [MAX_FILE_BYTES] 直接拒 —— 读一份几百 MB 的文件进内存，在手机上
     *     就是 OOM，而那恰恰是"选错文件"的典型形态。超限**明说**，不静默截断
     *     （截进来的是"看起来能用、实则半份"的库）；
     *   · `U+FFFD`（U+FFFD）= 这份字节根本不是 UTF-8（GBK 的中文笔记最常见）。此时给
     *     一句能执行的话（"另存为 UTF-8"），而不是让它变成一库问号。⚠ 只在这一个
     *     方向上拒绝：一份**本来就是 UTF-8** 的文本里出现 `U+FFFD` 完全正常
     *     （用户复制来的内容里就有），那种情况不该被拒。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么错误文案里不出现 Uri / 文件名 —— 这是本函数唯一的坏味道，说明在此
     * ═══════════════════════════════════════════════════════════════════════
     * `Uri.toString()` 里**可能带用户名**：`content://com.example.docs/…/我的病历.txt`
     * 在一个多用户 / 分享出的 URI 上足以认出是谁。而这句文案的去向有三处，
     * 两处是"会被导出去"的：Toast、`LlmEngine.uiLog`（-> 内存 ring -> 日志页 ->
     * **日志导出** -> **崩溃探针**）。本仓库上一轮刚刚为此把 Uri 从日志里删掉，
     * 这里不重新请回来。文件名本身由**调用方**持有 —— 那边用
     * [ExternalModel.displayName]，与选模型那条路同一口径。
     */
    fun readText(cr: ContentResolver, uri: Uri): String {
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() }
            ?: throw NotText("无法打开这个文件（读取被拒绝或文件已不在）")
        if (bytes.isEmpty()) throw NotText("文件是空的")
        if (bytes.size > MAX_FILE_BYTES) {
            throw NotText("文件太大（${bytes.size / 1024 / 1024} MB）—— " +
                "知识库是给人查的，单份上限 ${MAX_FILE_BYTES / 1024 / 1024} MB，请先切分")
        }
        if (bytes.contains(0.toByte())) {
            throw NotText("这看起来不是文本文件（内容里有 NUL 字节）—— " +
                "导入库支持 .json / .jsonl，导入 .txt / .md 走的是这个按钮")
        }
        val text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            throw NotText("这份文件不是 UTF-8 编码（中文变成了一串乱码字符）—— " +
                "用记事本另存为 UTF-8 后再导入")
        }
        return text
    }

    private fun fail(msg: String, skipped: Int = 0): Parsed =
        Parsed(emptyList(), "", skipped, msg)
}
