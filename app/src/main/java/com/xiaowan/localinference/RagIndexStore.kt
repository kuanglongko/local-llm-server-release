package com.xiaowan.localinference

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 知识库**索引的持久化**（RAG 阶段 1）。
 *
 * 存储布局（均在 `ctx.filesDir/rag/<库名>/`）：
 * - `index.bin`   —— 二进制索引：头（magic + 指纹长度 + dim + count + 指纹）+ 向量区
 * - `texts.jsonl` —— 正文与来源，**一行一条，行序 == 索引行序**（检索命中后要注入的就是它）
 * - `meta.json`   —— 人类可读的元信息（建库时间、条目数、切块参数），只为人看
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么要「自带指纹」而不是「假设模型不变」
 * ═══════════════════════════════════════════════════════════════════════════
 * 索引里的向量是**用某个特定 embed 模型 + 某组切块参数**算出来的。它一旦与
 * 当前状态不匹配，检索会**静默地**给出无意义的结果 —— 余弦值量纲都对不上，
 * 排序看起来像随机，而接口 200、日志干净、也没有任何异常。
 *
 * 所以索引文件头里落一份指纹（模型路径 / 大小 / mtime / 维度 / 切块参数 /
 * **正文内容 hash** / 格式版本），每次加载**逐字节比对**。
 * 不一致 = 当作没有索引（重建），**绝不"凑合着用"**。
 *
 * 为什么 size 与 mtime 都要算：两者各有盲区，且都真实存在 ——
 * 「就地替换权重」改 mtime 可能不改 size；「重新拷贝同尺寸文件」不改 size 但改 mtime。
 * 只算一个，就有一类真实操作**静默绕过**指纹。
 *
 * 为什么**正文 hash** 也要算进指纹（阶段 2 起）：向量与正文是一对 ——
 * 检索用向量命中行号，注入却拿那一行的正文去拼 prompt。只改正文不重建，
 * 就是"用旧语义的向量命中、注入新内容的正文"（排序像随机，接口 200、日志干净）；
 * 正文文件丢了更糟：命中照样报 3 条，注入出来全是空串。把它焊进同一个指纹后，
 * 这两种不一致与"换模型"走**同一条**处置路径（当作没有索引 -> 重建），
 * 于是不需要第二套"作废"状态，也没有第二个地方能忘记维护它。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么失败一律降级成「没有索引」而不是抛异常
 * ═══════════════════════════════════════════════════════════════════════════
 * 与 embed / kv_prefix 同一条准则：**缓存问题绝不让请求失败**。
 * 索引读不出来最坏就是"重建一遍"，那是慢；而抛异常是功能不可用（乃至闪退）。
 * 但"没有索引"与"索引是空的"必须区分（见 [Load.none] 与 [Load.valid]）：
 * 把空库当"建好了"会让检索恒为空且没有任何提示。
 */
object RagIndexStore {

    private const val TAG = "RagIndexStore"

    /**
     * 索引格式版本。改了头布局或语义就必须 +1（旧索引自动作废重建）。
     *
     * `2`：指纹新增「正文 hash」（`x` 字段）—— 语义变了，不是布局变了。
     * 旧索引（`v1`）与新指纹永不匹配 = 当作没有索引 = 重建一次。
     * 这**是**我们想要的：本次升级后"正文"与"向量"才被绑成一对，
     * 升级前落盘的索引无法证明那对是齐的，宁可变。
     *
     * `3`：追加一条时索引里会留下**来不及算向量的行**（正文先落、向量后补）。
     * 这类索引不是"错了"，而是"只差几条"：旧版本读它只会看到"少了几条"，
     * 而少的那几条**恰恰是刚敲进去的正文**（用户眼里就是"我加的没生效"）。
     * 版本 +1 让它在旧内核上退回"要重建"，而不是安静地少几条。
     */
    const val FORMAT_VERSION = 3

    private const val MAGIC = "RAG1"
    private const val FILE = "index.bin"
    private const val META = "meta.json"
    private const val TEXTS = "texts.jsonl"
    private const val DIR = "rag"

    /**
     * **显示名 ↔ 目录名**那张表（ISSUE #259）。放在根目录**之外**、`rag/` 的上一层。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么不能放在 `rag/` 里面
     * ══════════════════════════════════════════════════════════════════════
     * [list] 是按 `rag/` 下的**子目录**列库的。把这份表做成 `rag/names.tsv`
     * 是一个**文件**，它能躲过那条判据（只取 `isDirectory`）—— 但那是"恰好没踩到"，
     * 不是"不可能踩到"：任何一处把那条判据放宽成"列全部子项"（例如为了显示
     * "有些目录坏了"），这一行就会变成一个**点不动也删不掉的幽灵库**
     * （它没有 `index.bin`、没有 `texts.jsonl`，`drop` 对它无效 —— 正是
     * `list` 的注释里那段"空目录幽灵行"的同一个形态）。
     *
     * 所以放在 `filesDir/rag_names.tsv`：物理上就不可能在 `rag/` 里，
     * 不依赖任何一条"记得过滤"的判据。
     */
    private const val NAMES = "rag_names.tsv"

    /** 那张表的磁盘位置（`rag/` 的**上一层**，见 [NAMES]）。 */
    fun namesFile(ctx: Context): File = File(ctx.filesDir, NAMES)

    /**
     * 切块参数（默认值与 `rag_index.h` 的 ChunkCfg 对齐，手机上一次装 ~100 条的甜点）。
     *
     * [chunkRuleVersion] 是**切块规则**的版本（与 [FORMAT_VERSION] 两回事：
     * 那个是索引文件格式，这个是"从哪儿切"的语义）。为什么它必须在参数里 ——
     * `targetChars` / `overlapChars` 只描述"块多大"，描述不了"从哪儿切"。
     * 本轮把降级规则从"段落 > 换行 > 任意位置"改成"段落 > 句读 > 硬截"，
     * 这两个数**一个都没变**：没有这一个字段，旧索引的指纹会与新的完全相同，
     * 于是"用旧边界算的向量命中、注入新边界切出的正文"，排序像随机、接口 200。
     *
     * 改切块规则（分隔符表 / 降级次序 / 对齐方式）时**必须 +1**，
     * 由 `tools/run_rag_index_guard.sh` 的 `chunk_rule_version_wired` 钉住"它进了指纹"。
     */
    data class ChunkParams(
        val targetChars: Int = 480,
        val overlapChars: Int = 64,
        /**
         * 单条文本最多切出多少块（防一份超大文件把内存吃光）。
         *
         * 4096 -> 16384（用户拍板）。按默认 480/64 算，4096 块 ≈ 170 万字符
         * ≈ 一个 4~5 MB 的纯文本 —— 比这更大的文档**会被静默切掉后半段**：
         * 超出部分一个向量都没有，而"这份只索引了前半段"与"文档本就这么多"
         * 在日志与界面上逐字相同（唯一的可见现象是"后半段搜不到"）。
         * 有了 `LlmEngine.chunk` 的 `truncated` 出参之后这件事会被报出来，
         * 但报出来只是"知情"—— 抬上限才是"能装下"。
         *
         * 代价（用户拍板时已确认）：向量区是 `count × dim × 4` 字节**全量常驻**，
         * 检索是暴力全扫 O(count × dim)。384 维下 16384 块 ≈ 24 MB、
         * 768 维 ≈ 48 MB，检索耗时同比放大 4 倍。所以这个数**不是越大越好**，
         * 16384 是"能装下常见长文档"与"低端机内存/速度"之间的折中。
         *
         * ⚠ 它**必须进指纹**（`c` 那一格）：不进的话，按 4096 截断出来的半份索引
         * 会被当成"仍然可用"，与按 16384 切出的正文混用。
         */
        val maxChunks: Int = 16384,
        /**
         * 切块**规则**版本（进指纹）。
         *
         * 2 -> 3（本轮）：分隔符表达成**一份共享表** `TextBoundary.SOFT_CUT_SEPS`，
         * 按**并集**收口（建库原来 12 条 + 导入侧多出的 `…` `:`）。导入那条路
         * 的结果一个字没变，**建库**这条路的边界真的变了 —— 同样 480 字窗口里
         * 遇到省略号 / 英文冒号，从"硬截"变成"软切"。
         *
         * ⚠ 只改"从哪儿切"时，[targetChars] / [overlapChars] 一个都不动，
         *   指纹于是不变 —— 不升这个数，旧索引会被继续当"可用"，而那些向量
         *   是按旧边界算的。这一格存在的全部理由就在这儿。
         */
        val chunkRuleVersion: Int = 3
    )

    /**
     * 一条进入索引的文本块。
     *
     * [vector]（阶段 3 起）：**这条文本自己的向量**，可为空。
     *
     * 为什么正文条目要带上向量：追加一条时，新行的向量必须与它在
     * `texts.jsonl` 里的**行号**对齐 —— 而"行号"只有在编码正文的那一刻才定下来
     * （空行会被丢弃，所以"第几行非空"与"第几个元素"不一定相等）。
     * 让调用方自己数行号，等于把"向量与正文对齐"这件事交给两处各自的算术，
     * 错位时也不报错（检索分数是对的、注入的内容是错的）。
     * 所以向量**跟着条目走**，由编码那一侧决定谁有向量、排在第几行。
     */
    data class Entry(val text: String, val source: String, val vector: FloatArray = FloatArray(0)) {
        // FloatArray 是引用类型，data class 自动生成的 equals/hashCode 比的是引用 ——
        // 两个内容相同的条目会被判成不等。这里显式改掉，免得调用方（测试、去重）
        // 拿到一个"说它不等、其实一样"的结论。
        override fun equals(other: Any?): Boolean =
            other is Entry && text == other.text && source == other.source &&
                vector.contentEquals(other.vector)

        override fun hashCode(): Int =
            (text.hashCode() * 31 + source.hashCode()) * 31 + vector.contentHashCode()
    }

    /**
     * 索引加载结果。**三态**，不是两态：
     * - [none]  —— 没有索引（文件不存在 / 指纹不匹配 / 文件损坏）。**必须重建**。
     * - [empty] —— 索引有效但**没有任何条目**（知识库确实是空的）。**不必重建**。
     * - [valid] —— 有可用索引。
     *
     * 把 `none` 与 `empty` 混成一个 `null`（或一个空列表）会出一个真实故障：
     * 每次打开空知识库都触发一次"重建"，而重建结果是"还是空" —— 用户看到的
     * 是"知识库页面一直在转"，且没有任何一条日志说清它在转什么。
     */
    sealed class Load {
        /**
         * [stale] 是"为什么没有可用索引"的**定性**（用户拍板：不要把两种作废混成一句）：
         * - `false` —— 我们**确实不能**用这份索引（换了模型 / 池化语义变了 / 文件损坏）。
         *   用户处置：换回原模型，或者重建。
         * - `true`  —— 索引是"我们故意写下的半份"（[savePartial]：正文全量落盘、
         *   向量只到第一个失败条目为止）。用户处置：**重建就好**，别去查模型。
         *
         * 为什么值得为此加一个字段：旧实现让这两种情形**长得一样**（都只是一句
         * "没有可用索引"），而它们在用户手里是两件事 —— 一个要换模型、
         * 一个只要点「建库/重建」。归因错了，用户就会去换一个本来没问题的模型。
         * （这属于用户说的"待重建状态别静默作废"：待重建要**说出来**。）
         */
        object none : Load()
        object empty : Load()
        data class valid(val dim: Int, val dims: Int, val count: Int, val vec: FloatArray) : Load()

        /**
         * **半份索引**（可续）：指纹与当前配置**完全一致**，向量只到第 [count] 行为止，
         * 而正文有 [rowsTotal] 行。它是 [savePartial] 在"建索引途中被杀"时留下的那份，
         * 也是**续编**的入口。
         *
         * ═══════════════════════════════════════════════════════════════════════
         * 为什么它**不是** [valid]
         * ═══════════════════════════════════════════════════════════════════════
         * [valid] 的契约是"能直接检索"（向量与正文行号一一对应、齐整）。
         * 半份**不能**直接检索 —— 缺的那些行检索永远搜不到，而这与"没命中"
         * 长得一模一样（本文件开头那一族"文档与索引各自合法、配在一起却错"）。
         * 所以它必须是一个**独立的状态**：调用方（[LlmEngine.search]）见到它就
         * 当"没有可用索引"，而调用方（[LlmEngine.buildIndex]）见到它就**接着编**。
         * 把两者合成一个 `valid` 的后果是"检索拿半份索引去命中"——
         * 而这正是 [load] 原先那句"count != rows-1 就拒"要防的事。
         *
         * ═══════════════════════════════════════════════════════════════════════
         * 为什么**不必**新造一个文件格式 / 落一份块表
         * ═══════════════════════════════════════════════════════════════════════
         * 指纹里的 `x`（正文 hash）与 `r`（正文行数）用的是**完整正文**的 ——
         * 所以半份索引的指纹与"齐整那份"**逐字节相同**。于是续编不需要"重放块"
         * （[LlmEngine.chunk] 对同一份 docs + 同一套 cp 是确定性的，而 cp 已经在
         * 指纹里），只需要"跳过前 count 块、从第 count 块接着编"。
         * 也就是说：**判据是同一条**（指纹同源 + 前缀连续），
         * 断续跑与"已有库再导入新文本只补增量"是同一件事的两个触发点。
         */
        data class partial(val dim: Int, val dims: Int, val count: Int, val vec: FloatArray,
                           val rowsTotal: Int) : Load()
    }

    /**
     * 一个知识库的展示信息（不加载向量）。
     *
     * [inSync]：**正文与索引是否对得上**（[textsInSync]）。为 false 时索引已被
     * 指纹判为不可用 —— 列表里必须显示出来，否则用户看到的是"库在、N 条、
     * 检索却永远为空"，而这与"没命中"长得一模一样。
     *
     * [hasIndex]：**这个库到底有没有索引**。它与 [inSync] 是**两个问题**，
     * 合成一个会让"从来没建过库"与"建过又失效了"长得一样（见 [list] 的说明）。
     */
    data class Info(val name: String, val entries: Int, val dim: Int, val builtAt: Long,
                    val targetChars: Int, val inSync: Boolean, val hasIndex: Boolean,
                    val isPending: Boolean = false,
                    /**
                     * 这个库的索引是**半份、且可续**（[Load.partial] 的那种）。
                     *
                     * ═══════════════════════════════════════════════════════════
                     * 为什么它与 [hasIndex] 是**两个问题**（而不是 hasIndex 的一种取值）
                     * ═══════════════════════════════════════════════════════════
                     * [hasIndex] 回答"磁盘上有没有索引文件"（能删、能显示）；
                     * [resumable] 回答"这份索引能不能**接着编**"。
                     * 一个半份索引 `hasIndex == true`、`resumable == true`；
                     * 一份"改了正文没重建"的索引 `hasIndex == true`、`resumable == false`；
                     * 而"从来没建过"两者都是 false。
                     *
                     * 为什么必须分开："点建索引"在两处的**代价**差几十分钟 ——
                     * 可续时它只补缺口，不可续时它整份重来。用户判断"我这一下
                     * 要等多久"的唯一依据就是状态行上的这句话，而把两者合成
                     * 一句「待重建」之后，他无法区分（两者长得一模一样）。
                     *
                     * ⚠ 它是**本地可判**的（不需要知道当前模型身份）：判据是
                     * "向量行数 < 正文行数 - 1 且 > 0，且正文 hash 与指纹一致"——
                     * 即"这份索引自洽地少了一截"。真正的续编判据（指纹**全字段**
                     * 与当前配置一致）在 [LlmEngine.buildIndex] 那里，
                     * 因为只有那里知道当前模型身份。UI 这一层报的是**上界**
                     * （"有可能续"），而建库那一刻会给出真正的答案 ——
                     * 这与本仓库"宁可少报一条也别假精确"的口径一致。
                     */
                    val resumable: Boolean = false,
                    /** 可续时：已经编到第几块（[resumable] 为 false 时无意义）。 */
                    val resumeCount: Int = 0) {
        /**
         * 这个库**磁盘上有东西**吗（正文或索引任一）。
         *
         * 与 [isPending] 是**一对反义**，而它们合起来才回答"这个库存在吗"：
         *   · `isPending = true`  -> 磁盘上没有它，它只是界面上的一个名字；
         *   · `isPending = false` -> 磁盘上确实有它（哪怕只有正文、没有索引）。
         *
         * [!] 为什么要把这两个信号分成两个字段而不是一个布尔：它们各自服务
         *     不同的问句 ——"按钮能不能点"问的是前者（**没有东西可删**），
         *     状态行说的是后者（"待重建" vs "还没落盘"）。挤成一个之后，
         *     ISSUE #259 第 ⑤ 条那个状态（杀进程之后留下一个待重建的库）
         *     就会被读成"还没落盘"，而它磁盘上**明明有正文**。
         */
        val onDisk: Boolean get() = !isPending

        /**
         * 这个库在磁盘上**有没有目录**（= 存在过、可以被删掉）。
         *
         * [!] 与 [hasIndex] **不是**同一件事，而这一条正是用户报的那两个按钮
         *     看不出区别的地方（见 [delete] / [drop] 的对照表）：
         *     一个"待重建"的库 `hasIndex` 是 false、而它是可以被删掉的。
         */
        val deletable: Boolean get() = onDisk
    }

    /**
     * 知识库根目录。**永不返回相对路径**（与 [ModelStore.modelsDir] 同一条教训：
     * `getExternalFilesDir` 返回 null 时会退化成相对 CWD 的路径，调用方看不出异常）。
     */
    fun rootDir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /**
     * 库名净化成安全的目录名。
     *
     * 为什么必须净化：库名来自用户输入（远端请求里的 `knowledge_base` 字段、
     * App 里的输入框）。含 `../` 的名字会让索引落到**库目录之外** ——
     * 那是任意路径写入，且它看起来"只是索引存错地方了"。
     * 空串 / 全是非法字符时返回 null（调用方按 400 处理，不静默回落成默认库）。
     */
    fun sanitizeName(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > 64) return null
        val sb = StringBuilder()
        for (c in t) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '-' || c == '_' || c == '.' -> sb.append(c)
                c.isWhitespace() -> sb.append('_')
                else -> return null      // 不"替换掉就放行"：那是把非法名静默改造成另一个名字
            }
        }
        val s = sb.toString()
        if (s.isEmpty() || s == "." || s == "..") return null
        return s
    }

    /**
     * 一个名字（**显示名或目录名都行**）在磁盘上对应的目录。
     *
     * ⚠⚠ 这一处是 ISSUE #259 第 ③ 条的全部落点：用户报的
     *    *"选中库，然后在库名输入的地方更换新名字，保存的话会直接生成一个新库"* ——
     *    旧实现拿**显示名**直接拼路径，于是"改名"在磁盘上只能表现为"换一个目录"
     *    （新目录里什么都没有 = "新生成一个库"，而旧库连同它的索引原样躺在那里）。
     *
     * 现在名字到目录之间隔着一张表（[RagKbName]，存在 [NAMES]）：
     * 显示名变了、目录名不变 —— 于是索引一个字节都不用重算（它认的是目录），
     * 这正是"改名应该直接生效、且不用重建索引"能成立的原因。
     *
     * [!] 表里查不到时**回落成它自己**：老库（升级前建的）在表里没有行，
     *     而它们的显示名就等于目录名。这条回落不是"兜底"，是**不变式 ③**
     *     （空表 = 恒等映射），由 `RagKbName.Map.dirOf` 保证 —— 这里再 sanity
     *     一次 [sanitizeName]，路径安全不依赖表里那一列（表文件用户可手工改）。
     */
    fun dirOf(ctx: Context, name: String): File? {
        val safe = sanitizeName(name) ?: return null
        // 表坏了（读不出来）就按恒等处理：库还在、内容还在，只是名字变回目录名。
        val mapped = runCatching { readNames(ctx) }.getOrDefault(RagKbName.Map(emptyList()))
            .dirOf(safe)
        return File(rootDir(ctx), sanitizeName(mapped) ?: safe)
    }

    fun indexFile(ctx: Context, name: String): File? = dirOf(ctx, name)?.let { File(it, FILE) }
    fun metaFile(ctx: Context, name: String): File? = dirOf(ctx, name)?.let { File(it, META) }

    /** 正文与来源的旁挂文件（一行一条 JSON，行序 == 索引行序）。 */
    fun textsFile(ctx: Context, name: String): File? = dirOf(ctx, name)?.let { File(it, TEXTS) }

    // ── 显示名 ↔ 目录名（那张表）────────────────────────────────────────────

    /**
     * 读那张表。**任何异常 / 文件不存在 -> 空表**（= 恒等映射，见 [dirOf]）。
     *
     * 读的是小文件（几十行），所以不做缓存：加缓存就得管"改名之后有没有失效"，
     * 而那一处漏了的话症状是"改名看着成了、下次进来又变回去"（本仓库最忌讳的
     * 那种"两个信号说的是两件事"）。几十行的读开销在主线程上是微秒级。
     *
     * [!] 剥 UTF-8 BOM：用户拿记事本手工编辑过这个文件的话，第一行的目录名会
     *     多一个 `\uFEFF`，于是那个库**恰好在表里查不到** —— 它会回落成目录名，
     *     而屏幕上表现为"只有第一个库改名失效了"。
     */
    fun readNames(ctx: Context): RagKbName.Map =
        runCatching {
            val f = namesFile(ctx)
            if (!f.isFile) return RagKbName.Map(emptyList())
            RagKbName.Map.decode(f.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        }.getOrElse { RagKbName.Map(emptyList()) }

    /**
     * 写那张表（原子：`.tmp` + `renameTo`，与 [save] / [saveTexts] 同一套路）。
     *
     * [!] 失败必须**如实返回 false**，不许静默吞掉：调用方（[EngineActivity]
     *     的改名那条路）靠它决定要不要把那句"已改名"说出口 —— 说了而没写盘，
     *     用户下次进来看到的是旧名字，而"改名没生效"与"我记错了"长得一样。
     */
    fun writeNames(ctx: Context, map: RagKbName.Map): Boolean = runCatching {
        val f = namesFile(ctx)
        val tmp = File(f.parentFile, NAMES + ".tmp")
        tmp.writeText(map.encode(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { tmp.delete(); false } else true
    }.getOrDefault(false)

    /**
     * 重新落盘一遍显示名（ISSUE #259）。
     *
     * 用户报的那条：*"选中库，然后在库名输入的地方更换新名字，保存的话会直接生成
     * 一个新库，改名不生效，我认为应该直接生效，且不用重建索引"*。
     *
     * 这里做的**只有两件事**：表里那一行改掉、表写回磁盘。
     *   · 不建目录（新目录就是"另一个库"——那正是他报的 bug）；
     *   · 不碰 `index.bin` / `texts.jsonl`（改名与向量无关，所以不需要重建）。
     *
     * [!] 唯一性在这里**再挡一遍**（[RagKbName.Map.checkName]）：界面上那一跳
     *     会先问一次（好给用户一句人话），但这一层是唯一对**表**负责的地方 ——
     *     两处都判不是重复，是"错的代价不对称"：漏了这里，表里就会出现两行同名，
     *     而之后 `dirOf(那个名字)` 返回哪个目录取决于行的先后。
     *
     * @return true = 表已经写回磁盘；false = 名字不合法 / 重名 / 写盘失败（都没改）。
     */
    fun rename(ctx: Context, dir: String, display: String): Boolean {
        val safeDir = sanitizeName(dir) ?: return false
        val safeDisplay = sanitizeName(display) ?: return false
        val cur = readNames(ctx)
        // ⚠⚠ 唯一性必须同时看**表**与**磁盘目录**（[RagKbName.Map.checkName]
        //    的 `liveDirs`）：只查表会把"改成磁盘上另一个库的目录名"放过 ——
        //    那两列（显示名 / 目录名）在列表里是同一列名字，撞上就是两个库共用
        //    一个显示名（用户报的"点任意一个库都同时选中两个、名字一起变"）。
        //    名字没被占用才继续；被占用时**什么都不做**（而不是"改了但没写盘"）。
        val msg = cur.checkName(safeDir, safeDisplay, liveDirs(ctx))
        if (msg != null) {
            // 只可能是重名那一支（名字不合法上面已经返回 false 了）。
            return false
        }
        val next = cur.rename(safeDir, safeDisplay)
        if (next === cur) return true         // 名字没变：不用写盘
        return writeNames(ctx, next)
    }

    /**
     * 一个库的**显示名**。目录名进来也一样（查不到就回落成它自己）。
     *
     * 列表 / 状态行 / 弹框里给用户看的那一处必须走这里，而不是直接拿目录名 ——
     * 后者会让改名之后"列表里是旧名、库名框里是新名"，两个信号打架。
     */
    fun displayName(ctx: Context, name: String): String {
        val safe = sanitizeName(name) ?: return name
        return runCatching { readNames(ctx).displayOf(safe) }.getOrDefault(safe)
    }

    /**
     * 修掉表里那些**与磁盘目录撞名**的行（自愈，ISSUE #259）。
     *
     * 这是给"已经被旧版本改坏过的磁盘"兜底的：老版本允许把 `test` 改名成
     * `test1`（磁盘上已有目录 `test1`），于是表里留下 `test<TAB>test1` 这样一行。
     * 光把**新的**改名挡下来（[rename] / [EngineActivity.isRenameOfCurrent]）不够 ——
     * 那份坏表还在盘上，用户下次进来照样看到两个库同名、点哪个都落到同一个目录。
     *
     * 判据与 [RagKbName.Map.checkName] 的 `liveDirs` 那一条**同源**：
     * 一行 `dir -> display` 是坏的，当且仅当 `display` 同时也是**另一个**
     * 活着的目录名（`display in live && display != dir`）。撞名的那一行**丢掉** ——
     * 丢掉 = 那个库回落成目录名显示（[dirOf] 的恒等回落）。
     *
     * [!] 为什么丢的是"表里那一行"而不是"那个目录"：目录是**内容**（正文 +
     *     索引），表行只是**名字**。丢名字的代价是"显示名变回目录名"（库还在、
     *     内容还在），丢目录的代价是用户的资料没了。两者不对称，所以永远丢名字。
     * [!] 先到先得（按行序）：若**两行**都与同一个目录撞名，只丢后面的
     *     （[decode] 已经保证显示名两两不重复，这里再保一层目录名不重复）。
     * [!] 只在后台那一条列库的路上调（[EngineActivity.refreshRagList]）：
     *     它要 `listFiles` 扫一遍目录，放在主线程的改名那一跳是没必要的 IO。
     *     修不动（写盘失败）也不报错 —— 表读不出来时 [dirOf] 会整份回落成恒等，
     *     库照样能用，只是名字回到目录名（与这里丢掉坏行的结果一致）。
     */
    fun repairNames(ctx: Context, live: Set<String>): Boolean {
        val cur = readNames(ctx)
        // 坏行判据只有一处（[RagKbName.Map.dropDirCollisions]）：这里只负责
        // "扫磁盘目录 -> 喂给它 -> 变了才写盘"。两处各写一套判据时，迟早有一处
        // 漏掉（"挡得住新的坏名字、清不掉旧的坏名字"就是那么来的）。
        val next = cur.dropDirCollisions(live)
        if (next === cur) return true       // 没坏行：不用写盘
        return writeNames(ctx, next)
    }

    /**
     * 删掉那些**磁盘上已经没有的库**在表里留下的行。
     *
     * 为什么需要：表是"目录名 -> 显示名"，而目录可以被删（[drop] / 用户在文件
     * 管理器里删）。留着那些行的直接后果不是"多占几行" —— 是**唯一性判据会
     * 拒掉一个合法的名字**：用户删掉了叫「笔记」的库，之后想把另一个库改名成
     * 「笔记」，却被告知"已经有另一个库叫「笔记」了"，而列表里一个叫这名的都没有。
     *
     * [!] 只在 [delete] 成功之后调（不是每次读表都调）：每次读都去 `listFiles`
     *     会把"读几十行"变成"扫一遍目录"，而那发生在主线程上（[EngineActivity]
     *     的改名那一跳就在主线程）。
     */
    fun pruneNames(ctx: Context, live: Set<String>): Boolean {
        val cur = readNames(ctx)
        val kept = cur.entries.filter { it.first in live }
        if (kept.size == cur.entries.size) return true
        return writeNames(ctx, RagKbName.Map(kept))
    }

    // ── 读写 ───────────────────────────────────────────────────────────────

    /** 头字段在文件中的偏移（与 `rag_index.h` 的 encode_header 严格一致）。 */
    private const val OFF_MAGIC = 0
    private const val OFF_FPLEN = 4
    private const val OFF_DIM = 8
    private const val OFF_COUNT = 12
    private const val OFF_FP = 16

    /**
     * 由指纹字符串 + dim + count + 向量推出文件字节。
     *
     * 向量区用 **小端 float32**（Android arm64 原生序；且显式指定字节序，
     * 免得读的那台机器是别的大端环境 —— 那会把 float 读成乱码，**不崩**）。
     */
    fun encode(fp: String, dim: Int, vec: FloatArray): ByteArray {
        require(dim > 0) { "dim must be > 0" }
        val count = if (vec.isEmpty()) 0 else vec.size / dim
        val fpBytes = fp.toByteArray(Charsets.UTF_8)
        val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        head.put(MAGIC.toByteArray(Charsets.US_ASCII))
        head.putInt(fpBytes.size)
        head.putInt(dim)
        head.putInt(count)
        val body = ByteBuffer.allocate(count * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count * dim) body.putFloat(vec[i])
        return head.array() + fpBytes + body.array()
    }

    /**
     * 解析索引字节。返回 null = 这不是我们的索引 / 被截断 / 被改坏。
     *
     * 任何一步不成立都**整体拒绝**：部分可信的头比没有头更危险 ——
     * 调用方会按一个错的 dim/count 去读向量数组，读到的是一串错位的 float，
     * 维度还能"对得上"，检索永远无意义。
     */
    fun decode(raw: ByteArray): Triple<Int, Int, FloatArray>? {
        if (raw.size < OFF_FP) return null
        if (String(raw, OFF_MAGIC, 4, Charsets.US_ASCII) != MAGIC) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val fpLen = buf.getInt(OFF_FPLEN)
        val dim = buf.getInt(OFF_DIM)
        val count = buf.getInt(OFF_COUNT)
        if (fpLen <= 0 || fpLen > 4096) return null
        if (dim <= 0 || dim > 65536) return null
        if (count < 0) return null
        val headLen = OFF_FP + fpLen
        if (raw.size < headLen) return null
        val need = count.toLong() * dim.toLong() * 4L
        if (raw.size.toLong() < headLen.toLong() + need) return null
        val vec = FloatArray(count * dim)
        for (i in vec.indices) vec[i] = buf.getFloat(headLen + i * 4)
        return Triple(dim, count, vec)
    }

    /** 从字节里取出指纹（不解析向量区，用于快速判失效）。null = 不是我们的文件。 */
    fun fingerprintIn(raw: ByteArray): String? {
        if (raw.size < OFF_FP) return null
        if (String(raw, OFF_MAGIC, 4, Charsets.US_ASCII) != MAGIC) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val fpLen = buf.getInt(OFF_FPLEN)
        if (fpLen <= 0 || fpLen > 4096) return null
        if (raw.size < OFF_FP + fpLen) return null
        return String(raw, OFF_FP, fpLen, Charsets.UTF_8)
    }

    /**
     * 指纹里**与模型有关**的那一半：模型身份 + 维度 + 切块参数。
     *
     * 为什么单独抽一个类型、而不是给 [fingerprintOf] 加第七个参数：
     * 那些参数全是数值，调用处极易串位（`dim` 与 `cp.targetChars` 都是 Int，
     * 编译器不会拦），而串位后的症状是"指纹永远不匹配 → 每次打开都在重建"
     * —— 只是慢，所以没人会去查。具名构造把这件事交给编译器。
     *
     * **不含正文**：正文那一半由 [textsHashOf] 现算，且 [save] 内部现取，
     * 见 [FpInput] 的下一段说明。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 嵌入身份（用户拍板）：[embedBytes] / [embedMtime] / [pooling] / [embedOutDim]
     * ═══════════════════════════════════════════════════════════════════════
     * 它们的**类型就是"是否可以比对"**：`null` = 不知道（`.txt_embed` 那份
     * 手写配置我们没有元数据）。为什么非要走到"用类型表达"这一步 ——
     * 先看两个真实事故，它们都是"值相等但对象不是同一个"：
     *
     *   ① 同一个 `models/` 目录里的两份 gguf 很容易被搞混（大版本切换时尤其），
     *      而路径字符串的拼法在两处可能不同（`a.gguf` vs `./a.gguf`）——
     *      两处各拼一次路径，指纹就可能永不匹配：用户看到
     *      "索引不可用：嵌入模型或切块参数与建库时不同（"tes1"）"。
     *      修法是"只有一份取值实现"（见 [LlmEngine.embedFpInput]），
     *      而 [embedBytes] / [embedMtime] 是这条修法的**兜底**：
     *      文件被换成同尺寸的另一份时 size 给不出信号，mtime 能。
     *   ② 池化方式（CLS / LAST / MEAN）**直接决定那个向量是什么**。旧实现
     *      按 last-token 取、新实现按模型声明的 CLS 取 —— 同一份文本、同一个
     *      模型、同一个维度，两个向量**完全不同**。而指纹里没有它，于是
     *      升级后旧索引被当成"仍然可用"：用 last 语义的向量去命中，
     *      注入的是 CLS 语义下的正文 —— 分数看着像随机，接口 200、日志干净。
     *      这正是本文件开头"文档与索引各自合法、配在一起却错"那一族。
     *
     * ⚠ 为什么用 `null` 而不是哨兵值（`-1` / `""`）：哨兵会把两种**不同**的东西
     * 混成一种 —— "这个模型文件的 mtime 恰好是 -1"（不可能，但类型上允许）
     * 与"我们根本没有它的 mtime"。前者的语义是"已知且相等"，后者是"不知道"。
     * 之后，"不知道"永远不会被当成"相等"而放行一个语义已变的索引：
     * 只要**一边有值**就比 —— 一边有、一边没有 = 我们不确认它们是同一份
     * → 按"变了"处理（宁可让用户重建一次）。（见 [compareFp]）
     */
    data class FpInput(
        val embedPath: String,
        val modelBytes: Long,
        val modelMtime: Long,
        val dim: Int,
        val cp: ChunkParams = ChunkParams(),
        /** 嵌入模型文件字节数；`null` = 不知道（手写配置那份没有元数据）。 */
        val embedBytes: Long? = null,
        /** 嵌入模型文件 mtime；`null` = 不知道。 */
        val embedMtime: Long? = null,
        /** 模型声明的池化类型原值（0=NONE/1=MEAN/2=CLS/3=LAST/-1=UNSPECIFIED）；`null` = 不知道。 */
        val pooling: Int? = null,
        /**
         * 模型**声明的** `n_embd_out`（= GGUF 元数据里的那个值）；`null` = 不知道。
         *
         * 为什么不直接用 [dim]：`dim` 取自运行时 API（`llama_model_n_embd`），
         * 而"GGUF 声明了什么"是**另一条路**。两条路都要在名字里写清是哪一条 ——
         * 前者叫 `dim`（实际用的步长），后者叫 `embedOutDim`（模型自己说的）。
         * 混成一个名字之后，"哪天其中一个改了而另一个没改"就查不出来。
         */
        val embedOutDim: Int? = null,
        /**
         * 合批的**每批条数**（ISSUE #269 第 2 步：合批可见可调）。
         *
         * ⚠ 与上面那四个可选字段**不同**：它是**必填**（非可空）。
         * 为什么：合批是"一次前向编 n 条"，而不同批大小下每条的
         * 位置预算可以不同——"两条路径算出同一个向量"是一个
         * **待验证的断言**（第 3 步才去逐位验），不是已知事实。
         * 用可空类型，"不知道"就会被当成一个合法状态，而旧索引
         *（没有这一格）就会被静默当成"等于当前值"而放行。
         * 所以这里用非空 Int：旧格式指纹缺它 = [compareFp] 的
         * 必填字段缺失 = 重建（与 `c` / `k` 同一条路）。
         */
        val embedBatch: Int = 0
    )

    /**
     * 正文内容 hash（FNV-1a 64 位，小写十六进制）。与 `rag_index.h::texts_hash_of`
     * **逐字节等价** —— 两边各写一遍，共用 `tools/rag_index/texts_hash_vectors.tsv`
     * 这份向量表，各语言的单测都读它（各自实现一遍再对同一份期望值，才能发现
     * 其中一边写错；只在自己的实现里往返是恒真的）。
     *
     * 为什么要算它、并且**焊进指纹**：索引不是只有向量。检索用向量命中行号，
     * 注入却要拿那一行的**正文**去拼 prompt —— 于是"向量"与"正文"是一对。
     * 用户改了正文没重建 -> 用旧语义的向量命中、注入新内容的正文（排序像随机，
     * 接口 200、日志干净）；正文文件丢了 -> 每条命中都取不到正文，
     * 而"注入了 3 条"照样写进日志。两种都是"文档与索引各自合法、配在一起却错"。
     *
     * 焊进同一个指纹之后，不一致 = 指纹不匹配 = 当作没有索引（重建），
     * 与换模型走同一条路：**不需要第二套"作废"状态，也没有第二个地方能忘记维护**。
     */
    fun textsHashOf(raw: ByteArray): String {
        // FNV-1a 64：offset basis 14695981039346656037，prime 1099511628211。
        // Kotlin 的 Long 是**有符号**的，但溢出回绕在二进制补码下与无符号一致，
        // 所以逐位运算的结果与 C++ 的 uint64_t 完全相同（两边单测钉住这一点）。
        var h = -3750763034362895579L          // = 14695981039346656037 的补码表示
        for (b in raw) {
            h = h xor (b.toLong() and 0xFFL)
            h *= 1099511628211L
        }
        // `toString(16)` 对有符号数会给负号，必须走无符号格式化。
        val hex = java.lang.Long.toUnsignedString(h, 16)
        return if (hex.length >= 16) hex else "0".repeat(16 - hex.length) + hex
    }

    /**
     * 指纹字符串。字段顺序与 `rag_index.h` 的 `fingerprint_of` **严格一致** ——
     * 两处各写一遍必然漂移（漂移的后果是"指纹永远不匹配 → 每次都在重建"，
     * 只是慢，所以没人会去查）。守卫里有一条断言逐字段比对两边的字段集合。
     *
     * `textsHash` 与 `rows` **都没有默认值**是刻意的：少传一个参数就编不过，
     * 而"少算正文 hash"与"少算正文行数"的后果都是那些静默错位。让编译器当守卫。
     *
     * ⚠ `rows` 原先是 `= -1`（默认值），理由是"只读路径不知道行数时应当退化"——
     * 那个理由被真机打脸了：`r` 是 [compareFp] 的**必比字段**，索引里写的永远是
     * 真实行数，于是"省略 rows"不等于"退化"，而是"每次都比成不相等"：
     *
     *   [知识库] 索引不可用（"tes2"）：正文行数 变了：索引 正文行数=2 ≠ 当前 -1
     *
     * 一条本该可用的索引被判成不可用（用户："第三次建库成功，却无法在对话中使用"）。
     * 所以把默认值**去掉**：让"忘了传行数"变成编译错误，而不是一个恒假的比较。
     */
    fun fingerprintOf(fp: FpInput, textsHash: String, rows: Int): String {
        val sb = StringBuilder()
        sb.append("v").append(FORMAT_VERSION).append('\u001f')
        sb.append("p").append(fp.embedPath).append('\u001f')
        sb.append("b").append(fp.modelBytes).append('\u001f')
        sb.append("m").append(fp.modelMtime).append('\u001f')
        sb.append("d").append(fp.dim).append('\u001f')
        sb.append("t").append(fp.cp.targetChars).append('\u001f')
        sb.append("o").append(fp.cp.overlapChars).append('\u001f')
        // `k` = 切块**规则**版本。与 `t`/`o` 分开一格是刻意的：本轮改的就是规则
        // 而块大小没动 —— 只有这一格能证明"这份索引不是按旧规则切的"。
        // ⚠ 前缀必须避开已用的 v/p/b/m/d/t/o/c/x/r/e/n/q/z（见下面 14 个字母的说明），
        //   而 `k` 原先写着"留给模型类型/架构"。这次先用掉：切块规则版本
        //   是**当下**唯一的缺口，模型类型那件事还没有字段可放。
        sb.append("k").append(fp.cp.chunkRuleVersion).append('\u001f')
        // `c` = 单条块数上限。为什么它也要**单独一格**：`k` 回答"规则是什么"、
        // `c` 回答"上限是多少" —— 4096 -> 16384 这一改，块集合**根本不同**
        //（大文档从"被截断"变成"完整"），而 `t`/`o`/`k` 一个都没变。
        // 不放进指纹 = 按 4096 截断出来的**半份**索引被当成"仍然可用"：
        // 后半段的正文一个向量都没有，而症状与"文档里没有那一段"逐字相同。
        // 与 `k` 分开报的另一个理由：用户看到 `k` 变了不知道是哪条规则改了，
        // 而 `c` 变了时日志能直说"块数上限 变了：索引 4096 ≠ 当前 16384"。
        sb.append("c").append(fp.cp.maxChunks).append('\u001f')
        sb.append("x").append(textsHash).append('\u001f')
        sb.append("r").append(rows).append('\u001f')
        // ── 嵌入身份四件套（用户拍板）─────────────────────────────────────
        // 字段前缀一律是**单个 ASCII 字母**（见 [fpFields] 的说明），
        // 而 `e`/`n`/`q`/`z` 是当时仅剩的四个还没被占用的 —— 选它们不是风格问题：
        // 前缀一旦撞车（比如两处都写 "d"），[fpFields] 会把后一个当成前一个的
        // 重复而**静默丢掉**（= 漏算一个维度）。`tools/rag_index/check_fp_fields.py`
        // 正面钉住这些字母互不重复、且两边集合相等（本轮加了 `c`，共 15 个）。
        val eb = fp.embedBytes?.toString().orEmpty()
        val em = fp.embedMtime?.toString().orEmpty()
        val pq = fp.pooling?.toString().orEmpty()
        val eo = fp.embedOutDim?.toString().orEmpty()
        sb.append("e").append(eb).append('\u001f')
        sb.append("n").append(em).append('\u001f')
        sb.append("q").append(pq).append('\u001f')
        // `z` 是第 13 个。`k` 曾在注释里写着"留给模型类型/架构"（同一个 GGUF 的架构
        // 决定了它是不是嵌入模型，而"拿生成模型当嵌入"是最坏的一种配错）——
        // 本轮把它先用给了切块规则版本（那是当下的真实缺口），
        // 模型类型那件事另行择字母。
        sb.append("z").append(eo).append('\u001f')
        // `g` = 合批每批条数（batch **g**roup size）。为什么用 `g` 而不是 `b`：
        // `b` 已经是"模型字节数"，而前缀一旦撞车，下面那个 `fpFields()`
        // 会把后一个静默丢掉（= 它不参与比对）。它是**必填**：
        // 旧索引里没有这一格，那就应当被判成"认不出来、重建一次"。
        sb.append("g").append(fp.embedBatch).append('\u001f')
        return sb.toString()
    }

    /**
     * 从一份指纹里取出**正文行数**（`r` 字段）。取不到返回 null（旧格式指纹）。
     *
     * 为什么"行数"也要进指纹：追加一条时，索引里可能**故意**比正文少几行
     * （新加的那条正文落了盘、向量还没算出来）。这时候正文 hash 与模型那一半
     * 全是"匹配"的，只有行数对不上 —— 而"少几行"在界面上就是"我加的没生效"，
     * 与"没命中"长得一模一样。
     *
     * 📌 这一条现在**没有默认值**了（[fingerprintOf] 的 `rows` 亦然）。
     *
     * 早先给过 `-1`，理由写的是"只读路径不知道行数时退化成老行为"。那个理由
     * 是**错的**，而且错得很有代表性：`r` 早已进了 [compareFp] 的必比字段表，
     * 于是"省略"不再是"不比较"，而是"拿 -1 去比真实行数"——**恒假**。
     * 真机上就是每一次检索都判"索引不可用：正文行数 变了"，而索引本身完好。
     *
     * 只读路径（[LlmEngine.search]）其实**拿得到**行数：它已经把正文文件读进
     * 内存了（要算 hash），再数一次行数（[rowsOf]）是零成本。
     * 所以强制传参不是负担，而是把"忘了算"挡在编译期。
     */
    fun rowsInFingerprint(fp: String): Int? =
        fp.split('\u001f').firstOrNull { it.startsWith("r") }?.substring(1)?.toIntOrNull()

    /**
     * 比对两份指纹：**逐字段**，返回 [FpDiff.SAME] 或第一处差异（含是哪个字段、两边各是什么）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么要有它（而不是 `fpIn == expectFp` 一行）
     * ═══════════════════════════════════════════════════════════════════════
     * 用户拍板的两条要求，恰好都落在这一个函数上：
     *
     *   · **不要静默作废**：用户报的是"建库成功 → 检索说『模型或参数已变』"。
     *     一句"模型或参数已变"把 13 个字段的差异压成了一个不可操作的结论 ——
     *     用户不知道是哪一项变了，也不知道该换模型还是该重建。逐字段比对之后，
     *     日志能说"字段 d：指纹里 1024 ≠ 当前 1024"（真的是哪一项、两边各是什么）。
     *   · **"待重建"要能定性**：`待重建` 与"真不可用"是两件事 ——
     *     ①"我们**故意**写下的半份索引"（[savePartial]：正文全量、向量只留到
     *     第一个失败条目），用户处置是"重建就好"；②"这份索引描述的已经不是
     *     当前这套配置"（换了模型 / 池化语义变了），处置是"换回来或重建"。
     *     把两者混成一句"没有可用索引"之后，用户无法判断自己该做什么。
     *
     *     ⚠ ①的判据**不在这个函数里**：`savePartial` 写下的指纹与当前**整串相等**
     *     （正文 hash 与行数都是完整正文的），它只在 `load` 里以
     *     "count != rows-1"的形态暴露出来（见 [load]）。把"正文 hash 变了"
     *     也算成①会误导用户 —— 那时该说的不是"待重建"，是"这份索引过时了"。
     *
     * ⚠ **必须放进指纹的**（真差异 = 索引语义已经不对，不能用来检索）：
     *   `x` 正文 hash / `r` 行数 / `d` 维度 / `q` 池化 / 模型身份 / 切块参数。
     * ⚠ **不该放进指纹的**（只是我们暂时不知道，见 [FpInput] 的三态）：
     *   任一为 `null` 的那几个字段 —— 若把"不知道"当成"变了"，`.txt_embed`
     *   会被永远判成"模型变了"（而它其实是对的），于是用户每次都要重建。
     *
     * 返回 [FpDiff.SAME] 的前置条件是：**所有我们知道的字段都相等**，
     * 且**必须比到的字段都在**。缺一个必须比到的字段（旧格式指纹）→
     * [FpDiff.OLD_FORMAT]（= 要重建），绝不"幸好对上了一半"就放行。
     */
    data class FpDiff(val same: Boolean, val stale: Boolean, val field: String, val detail: String,
                      /**
                       * **只差 `g`（合批每批条数）这一格**，其余语义字段全同。
                       *
                       * ═══════════════════════════════════════════════════════════
                       * 为什么 `g` 要单独定一级，而不是与别的差异共用一个出口
                       * ═══════════════════════════════════════════════════════════
                       * `g` 与 `v/p/b/m/d/t/o/k/c/x/r` 是**两类**东西：
                       *   · 那些字段变了 = 索引的**语义**已经不是当前这套（换模型 /
                       *     换池化 / 正文改过），处置是"换回来或重建"，救无可救；
                       *   · `g` 变了 = **算得慢一点还是快一点的旋钮**动了，而
                       *     "不同批大小下的向量是否逐位相同"是一个**待验证的断言**
                       *     （ISSUE #269 第 5 步才去真机逐位比对，见 CHANGELOG）。
                       *
                       * 把两者压进同一个"从头编"里，用户就**看不到"能救多少"**：
                       * 一份编到 9000/10000 块的索引，因为改了一下批大小被判
                       * 从头编，而日志里只有一行"合批每批条数 变了"—— 他没被告知
                       * 那 9000 块是**语义同源**的（只差旋钮），也没被告知有多少块。
                       *
                       * ⚠ 但**这一级本身不授权沿用**：断言未验之前，`g` 不同时
                       *   前缀向量与当前批大小算出来的向量**可能**不是同一种语义
                       *   （索引会变成两种语义拼起来的）。所以 [salvage] 只用于
                       *   **把话说清楚**（报出可救的块数），沿用与否仍由 `load` 的
                       *   "语义字段全同"这条主判据决定 —— 在断言验出来之前，
                       *   `g` 不同 = 不可沿用（与今天一样），只是**不再沉默**。
                       */
                      val onlyBatch: Boolean = false,
                      /**
                       * 语义同源时可救的**前缀条数**（`-1` = 没算 / 不适用）。
                       *
                       * 只有 [onlyBatch] 为 true 时才有意义：它回答"能救多少"。
                       * 为什么它落在 [FpDiff] 上而不是新造一个状态：`lastDiff` 已经是
                       * 调用方唯一读"为什么不可用"的地方（见 [lastDiff] 的说明），
                       * 而"能救多少"正是同一句诊断里缺的那半句。
                       */
                      val salvage: Int = -1) {
        companion object {
            /** 完全相同（所有已知字段都相等）。 */
            val SAME = FpDiff(true, false, "", "")
            /** 旧格式 / 缺字段：按"必须重建"处理（宁可重建，不可错用）。 */
            val OLD_FORMAT = FpDiff(false, false, "v", "指纹格式不完整（旧版本索引）")
        }
    }

    /** 必须放进指纹、且缺失即判"要重建"的字段（前缀 -> 人类可读名）。 */
    private val kRequiredFpFields = listOf(
        "v" to "格式版本",
        "p" to "嵌入模型路径",
        "b" to "嵌入模型字节数",
        "m" to "嵌入模型 mtime",
        "d" to "向量维度",
        "t" to "切块目标长度",
        "o" to "切块重叠",
        "k" to "切块规则版本",
        "c" to "单条块数上限",
        "x" to "正文 hash",
        "r" to "正文行数",
        "g" to "合批每批条数"
    )

    /**
     * [kRequiredFpFields] 里**语义**的那一部分 —— 去掉 `g`（合批每批条数）。
     *
     * ⚠ 为什么要把 `g` 从这条主判据里摘出来：`g` 是**旋钮**，别的字段是**语义**
     *   （见 [FpDiff.onlyBatch] 的说明）。`load` 用这一份判"索引的语义是不是
     *   当前这套"（这决定**能不能用**），`g` 单独定级（这决定**能不能沿用前缀**
     *   与**能救多少**）。两者混在一条判据里，就会出现"改一下批大小，
     *   一份语义完好的索引被说得像换了模型"。
     *
     * ⚠ **摘出来不等于不要求它存在**：缺 `g`（旧格式）仍在
     *   [kRequiredFpFields] 的缺失检查里判"只能重建"（见 [compareFp] 的第一轮）。
     *   这一份只用于**值比较**。
     */
    private val kSemanticsFpFields = kRequiredFpFields.filterNot { it.first == "g" }

    /**
     * 必须放进指纹、但**允许是"不知道"**的字段（值可以为空串 —— 见 [FpInput]）。
     * 这几个缺失不判"要重建"：它们是这一轮新增的"嵌入身份"，老索引里本来就没有。
     */
    private val kOptionalFpFields = listOf(
        "e" to "嵌入模型字节数",
        "n" to "嵌入模型 mtime",
        "q" to "池化方式",
        "z" to "输出维度"
    )

    fun compareFp(fpIn: String, fpExpected: String): FpDiff {
        if (fpIn == fpExpected) return FpDiff.SAME
        val a = fpFields(fpIn)
        val b = fpFields(fpExpected)
        // 缺"必须比到的字段"：不作猜 —— 旧版本的索引文件里没有它们，
        // 而"拿不到的字段"与"相等的字段"长得一样（都是"没差异"），
        // 直接放行等于让一个语义未知的索引继续被用。
        for ((k, name) in kRequiredFpFields) {
            if (!a.containsKey(k) || !b.containsKey(k)) {
                return FpDiff(false, false, k, "$name 缺失（旧版本索引，只能重建）")
            }
        }
        // 值的比对分两轮：先比**语义**（除 `g` 外全部），再比 `g` 那一格。
        //
        // ⚠ 次序是判据的一部分：语义差异（换模型 / 正文改过）与"只差批大小"
        //   必须报成**两种不同的东西**（见 [FpDiff.onlyBatch]）。若把 `g` 并进
        //   这一轮一起比，一份"语义完好、只差旋钮"的索引会因为命中 `g` 而报成
        //   普通"变了"，于是调用方**看不见"能救多少"**。
        for ((k, name) in kSemanticsFpFields) {
            if (a[k] != b[k]) {
                return FpDiff(false, false, k, "$name 变了：索引 $name=${a[k]} ≠ 当前 ${b[k]}")
            }
        }
        // 嵌入身份（池化 / 输出维度 / 模型 stat）：**两边都空**才算"都不知道"。
        //
        // ⚠ 判据是"任一非空就比"，不是"任一为空就跳过"：后者会把
        // "只有一边知道"静默当成"相等"，于是**升级路径**上最要紧的那一次
        // 比对被跳过 —— 旧索引里没有 `q`，新代码知道 `q=2`（CLS），
        // 而旧索引的向量是按 last 算的。跳过它 = 用旧语义的向量继续检索，
        // 分数像随机、接口 200、日志干净。
        // 一边知道一边不知道 = **我们不确认它们是同一份** → 按"变了"处理
        // （宁可让用户重建一次，也不要让一个语义未知的索引继续出结果）。
        for ((k, name) in kOptionalFpFields) {
            val va = a[k]
            val vb = b[k]
            if (va.isNullOrEmpty() && vb.isNullOrEmpty()) continue
            if (va != vb) {
                val shown = if (va.isNullOrEmpty()) "不知道" else va
                val shownB = if (vb.isNullOrEmpty()) "不知道" else vb
                return FpDiff(false, false, k, "$name 对不上：索引 $name=$shown ≠ 当前 $shownB")
            }
        }
        // ── `g` 那一格（合批每批条数）：单独的**一级**，不是普通"变了" ────────
        // 走到这里 = 所有**语义**字段都相等。那 `g` 不同就意味着：这份索引的
        // 向量语义与当前配置**同源**，只差"一次编几条"这个旋钮。
        // 报成 `onlyBatch`（而不是一个笼统的"合批每批条数 变了"）的理由见
        // [FpDiff.onlyBatch]：调用方要靠它说清"能救多少"，而那句话现在
        // 根本没有地方能拿到 —— 用户只看到一行"从头编"。
        val gIn = a["g"]
        val gExp = b["g"]
        if (gIn != gExp) {
            return FpDiff(false, false, "g",
                "合批每批条数 变了：索引 $gIn ≠ 当前 $gExp（语义同源，只差这一个旋钮）",
                onlyBatch = true)
        }
        // 走到这里 = 所有**我们知道**的字段都相等（含 `g`）。
        // 那还剩什么差异？只剩"索引头里的字段与我们刚算出来的期望值**逐字节**
        // 不同，但逐**字段**都相同"这种情况 —— 实践里只有一种来源：指纹里
        // 多了一个我们不认识的字段（新版本写的索引被旧版本读）。
        // 那属于"我们不能确认它是什么"，按 [FpDiff.OLD_FORMAT] 同一条路处理：
        // **不当成"待重建"**（待重建是有明确定义的：`load` 里 count 与行数对不上，
        // 见 [savePartial]），也不要用户去换模型 —— 只说"认不出来，重建一次"。
        return FpDiff(false, false, "v", "指纹里的字段与当前不认识或对不上（重建一次）")
    }

    /** 把指纹拆成 `前缀 -> 值`。重复前缀取第一个（见 [fingerprintOf] 的前缀说明）。 */
    fun fpFields(fp: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in fp.split('\u001f')) {
            if (part.length < 1) continue
            val k = part.substring(0, 1)
            if (k in out) continue
            out[k] = part.substring(1)
        }
        return out
    }

    /**
     * 从一份指纹里取出**维度**（`d` 字段）。取不到返回 null。
     *
     * 追加/删除要往向量区里搬 float —— 搬的粒度就是维度。从**指纹**里读维度
     * （索引头里也有同一个值）是为了与 out 向量区的那份字节同源：
     * 两者不一致时（文件被改坏），宁可拒绝这一次编辑，也不按错的步长搬。
     */
    fun dimInFingerprint(fp: String): Int? =
        fp.split('\u001f').firstOrNull { it.startsWith("d") }?.substring(1)?.toIntOrNull()

    /**
     * 从一份指纹里取出**合批每批条数**（`g` 字段）。取不到返回 null（旧格式指纹）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么它是 `/health` 缺的那半句：`embed_batch_size_index`
     * ═══════════════════════════════════════════════════════════════════════
     * `/health` 早就有 `embed_batch_size`（**当前配置**的那个），但"建这份索引时
     * 用的是几"**没有任何地方说得出来** —— 而 `g` 是**必比**字段，用户把批大小
     * 从 8 改成 1 之后，磁盘上那份索引（`g=8`）与当前配置（`g=1`）对不上，
     * 整个库**静默不可检索**。用户能读到的只有一句"索引不可用：合批每批条数 变了"，
     * 而**机制**（改了批大小就必须重建索引）一个字都不说 —— 因为两个数里只有
     * 一个在外面。这个函数就是那第二个数的取值处。
     *
     * ⚠ 它读的是**索引里写的**那个值（真值），不是"当前配置"的复制品：
     *   两个数摆在一起才有对账（"我改的" vs "库认的"），一个数只是读数。
     */
    fun batchInFingerprint(fp: String): Int? =
        fp.split('\u001f').firstOrNull { it.startsWith("g") }?.substring(1)?.toIntOrNull()

    /**
     * **磁盘上这份库的索引**里记的批大小（`-1` = 没有索引 / 读不出来 / 旧格式）。
     *
     * 只给诊断面用（`/health` 的 `embed_batch_size_index`）。**不参与判据**：
     * 它回答的是"这个库建索引时用的是几"，而"能不能用"仍然由 [load] 的完整
     * 指纹比对决定（这里连期望指纹都没有，自然不会去比）。
     *
     * 为什么它不抛异常：`/health` 是探活面，读一个坏掉的索引文件**绝不能**
     * 让整份健康检查变成 500 —— 那种"探活把健康的服务判成不可达"本仓库
     * 已经吃过一次（见 `HttpApi` 里那份手拼 JSON 的自检注释）。
     */
    fun batchOfIndex(ctx: Context, name: String): Int {
        val f = indexFile(ctx, name) ?: return -1
        if (!f.isFile) return -1
        val raw = try { f.readBytes() } catch (t: Throwable) { return -1 }
        val fp = fingerprintIn(raw) ?: return -1
        return batchInFingerprint(fp) ?: -1
    }

    /**
     * 正文文件的**行数口径**：按 `\n` 切分后的元素个数。
     *
     * ⚠ 这个口径刻意包含**末尾那个空段**：`"a\nb\n"` 切出 `[a, b, ""]` = 3。
     * 真值（条目数）是 2，两者的差**恒为 1**（编码总是以 `\n` 收尾）。
     *
     * 为什么不用"数非空行"把差值消掉：正文里**允许有空行**（用户粘的文本里
     * 常有一行空白），而"数非空行"会让 `rows` 与索引里的 `count` 在"有坏行被
     * 跳过"时**从 ±1 偏移变成 ±2**，于是判据要分好几种情况。统一用"段数"之后，
     * 不变式只剩一条：**`count == rows - 1`**（见 [load]）。
     * 而这条偏移是两边契约，改它必须同时改 [load] 与测试。
     */
    fun rowsOf(raw: ByteArray): Int = raw.toString(Charsets.UTF_8).split('\n').size

    /** [rowsOf] 的逆：段数 -> 条目数（末尾空段偏移 1）。负数归零。 */
    fun rowsToCount(rows: Int): Int = if (rows > 0) rows - 1 else 0

    /** 从完整指纹派生"只带模型那一半"的 [FpInput]（追加/删除时要用）。 */
    fun FpInput(fp: String): FpInput {
        val parts = fp.split('\u001f')
        fun f(p: String): String? = parts.firstOrNull { it.startsWith(p) }?.substring(1)
        // ⚠ 新字段**必须**在这里一起搬，而且区分"键不存在"与"值为空"：
        // `f("e")` 为 null = 旧格式的指纹（那时还没有这个字段）→ 取值也叫 null
        //（"不知道"，见 [FpInput] 的三态说明）；`f("e")` 是空串 = 这个字段
        // 我们写过、但当时**不知道**（`.txt_embed` 那份手写配置）→ 也是 null。
        // 两者在语义上恰好都是"不知道"，所以 `?.toLongOrNull()` 一条就够 ——
        // 换成一个哨兵值（比如 -1）则会把这些"不知道"写成"已知的 -1"，
        // 于是追加一次之后，[compareFp] 会把"不知道"当"已知"比，
        // 判成"模型变了" —— 用户点一次「追加」就把自己的索引弄失效了。
        fun nullableInt(p: String): Int? = f(p)?.toIntOrNull()
        fun nullableLong(p: String): Long? = f(p)?.toLongOrNull()
        return FpInput(
            embedPath = f("p").orEmpty(),
            modelBytes = f("b")?.toLongOrNull() ?: 0L,
            modelMtime = f("m")?.toLongOrNull() ?: 0L,
            dim = f("d")?.toIntOrNull() ?: 0,
            cp = ChunkParams(
                targetChars = f("t")?.toIntOrNull() ?: ChunkParams().targetChars,
                overlapChars = f("o")?.toIntOrNull() ?: ChunkParams().overlapChars,
                // 与 `k` 同理：**必须一起搬**。不搬的话，追加/删除一条时按默认
                // 上限重算指纹 -> 与索引里那份不相等 -> 用户点一次「追加」
                // 就把自己的索引弄失效了（且日志只会说"参数变了"）。
                maxChunks = f("c")?.toIntOrNull() ?: ChunkParams().maxChunks,
                // `k` 也必须一起搬。不搬的后果与 `t`/`o` 一样：追加一条时
                // 按默认规则版本重算指纹 -> 与索引里那份**不相等** -> 用户点一次
                // 「追加」就把自己的索引弄失效了（且日志只会说"参数变了"）。
                chunkRuleVersion = f("k")?.toIntOrNull() ?: ChunkParams().chunkRuleVersion
            ),
            embedBytes = nullableLong("e"),
            embedMtime = nullableLong("n"),
            pooling = nullableInt("q"),
            embedOutDim = nullableInt("z"),
            // `g`（合批条数）**也必须一起搬**，理由与 `c` / `k` 逐字相同：
            // 不搬的话，追加/删除一条时按默认批大小（0）重算指纹 ->
            // 与索引里那份（比如 8）**不相等** -> 用户点一次「追加」
            // 就把自己的索引弄失效了（日志只会说"合批每批条数 变了"）。
            // 而它不是可空字段：旧格式指纹里没有 `g` 时取 0，与任何真实批大小
            // 都不等 —— 那正是"旧索引只能重建"（必填字段，见 [FpInput.embedBatch]）。
            embedBatch = f("g")?.toIntOrNull() ?: 0
        )
    }

    /**
     * 从一份指纹里取出正文 hash（`x` 字段）。取不到返回 null（旧格式指纹）。
     *
     * 用途：把"指纹不匹配"拆成「模型/参数变了」与「正文变了」两种**诊断** ——
     * 它们对用户是两回事（前者要换回模型，后者只要重建索引）。不拆的话
     * 引擎只能笼统地说"没有可用索引"，而用户看到的现象（配了知识库但回答里
     * 什么都不带）两种情形一模一样，无法归因。
     *
     * 字段分隔符 `\u001f` 在路径里不可能出现（路径要交给文件系统 API，
     * 控制字符进不去），所以按分隔符切分是安全的 —— 与 [fingerprintOf] 同一条前提。
     */
    fun textsHashInFp(fp: String): String? =
        fp.split('\u001f').firstOrNull { it.startsWith("x") }?.substring(1)?.takeIf { it.isNotEmpty() }

    /** 当前 embed 模型的 stat（size + mtime）；文件不可读时返回 null（→ 视为失效）。 */
    fun statModel(path: String): Pair<Long, Long>? {
        val f = File(path)
        if (!f.isFile) return null
        return Pair(f.length(), f.lastModified())
    }

    /**
     * 加载索引。**不抛异常**（调用方在后台线程上跑，未捕获会闪退）。
     *
     * 返回 [Load]：
     * - 文件不存在 / 指纹不符 / 解析失败 → [Load.none]（必须重建）
     * - 有效但 count == 0 → [Load.empty]（不必重建）
     * - 有效 → [Load.valid]
     *
     * [Load.none] 的 `<init>` 参数就是"为什么"（[FpDiff]）—— 调用方用它给用户
     * 一句**可操作**的话，而不是"模型或参数与建库时不同"。见 [compareFp]。
     */
    fun load(ctx: Context, name: String, expectFp: String): Load {
        val f = indexFile(ctx, name) ?: return Load.none
        if (!f.isFile) return Load.none
        val raw = try { f.readBytes() } catch (t: Throwable) {
            Log.w(TAG, "读取索引失败（按无索引处理）: ${t.javaClass.simpleName}")
            return Load.none
        }
        val fpIn = fingerprintIn(raw) ?: return Load.none
        if (fpIn != expectFp) {
            // 换模型 / 换切块参数 / 池化语义变了 = 作废重建；
            // 而"只是正文与行数对不上"= 待重建（我们故意写的半份）。
            // 两种都返回 none（都不能检索），但**原因**要带给调用方 ——
            // 上面那句 notStale 之外什么都不做，正是旧实现"静默作废"的地方。
            // ⚠ 这一行必须**直接**是 `lastDiff = compareFp(...)`（守卫 `check_fp_wiring.py`
            //   锚的正是这个来源）：换成一句写死的 `FpDiff(..., "模型或参数变了")` 会
            //   让调用方拿到一个不可动手的结论 —— 那正是本轮要防的形态。
            lastDiff = compareFp(fpIn, expectFp)
            // ── `g` 只差一格时，把"能救多少"算出来（ISSUE #269 第 5 步的前置）──
            // ⚠ 仍然返回 none（**不可沿用**）：断言未验之前，`g` 不同时前缀向量
            //   与当前批大小算出来的向量**可能**不是同一种语义（见 [FpDiff.onlyBatch]）。
            //   这里只是**不再沉默** —— 把"语义同源、编到 N/M 块"这句话备好，
            //   让调用方（[LlmEngine.buildIndex]）能说清"这 N 块是可救的，
            //   但这次要重编"，而不是一句干巴巴的"从头编"。
            //   为什么放在这里而不是新造一个 [Load] 变体：`lastDiff` 已经是调用方
            //   读"为什么不可用"的唯一出口，而"能救多少"是同一句诊断的另外半句。
            if (lastDiff.onlyBatch) lastDiff = lastDiff.copy(salvage = salvagePrefix(raw))
            return Load.none
        }
        val parsed = decode(raw) ?: return Load.none
        val (dim, count, vec) = parsed
        // `count`（向量行数）与指纹里的 `rows`（正文档数）必须满足 **count == rows - 1**
        // （末尾空段的固定偏移，见 [rowsOf]）。
        // 不满足 = 索引落后于正文（追加了正文但向量没算出来）= 不能检索：
        // 此时前 `count` 行**是对的**，而那恰恰是最坏的一种"部分可用" ——
        // 用户看到"检索有结果"，只是刚加的那条永远搜不到，无从归因。
        val rowsInFp = rowsInFingerprint(fpIn)
        // 向量行数必须**不超过**正文行数（[rowsToCount]）。超了 = 索引比正文还长，
        // 那不是"半份"，是**损坏**（或正文被截断）—— 一律拒（不能往后编）。
        val full = rowsInFp?.let { rowsToCount(it) } ?: count
        if (count > full) {
            lastDiff = FpDiff(false, true, "r", "索引里的向量行数（$count）比正文行数（$full+1）还多（索引损坏）")
            return Load.none
        }
        // 齐整：向量行数 == 正文行数 - 1（末尾空段的固定偏移，见 [rowsOf]）。
        if (count == full) {
            if (count == 0) return Load.empty           // 有效但空：不重建
            return Load.valid(dim, dim, count, vec)
        }
        // ── 半份（可续）：指纹完全一致（上面已比过），只是向量缺了尾段 ────────
        // ⚠ 这里**不再**返回 none + stale：那一版把"我们故意写下的半份"与
        // "换了模型（真不可用）"压成同一个出口，于是用户能做的只有"重建"
        // —— 而续编这条比"重建"快得多的路看不见。现在两者是**两个状态**：
        //   · none + stale（半份但指纹不符）-> 只能重建；
        //   · partial（半份且指纹同源）     -> 接着编。
        // 「接着编」与「重建」的差别：重建要把已经算过的 N 块重算一遍，
        // 而大库的 N 是几万块（几十分钟）。
        if (count == 0) return Load.empty               // 一条都没编出来：与空库同处置
        return Load.partial(dim, dim, count, vec, full + 1)
    }

    /**
     * 一份索引里**已经算过向量的条数**（`-1` = 解不出来 / 没有向量区）。
     *
     * 只给诊断用（"能救多少"）：[load] 在"只差 `g`"那一支上拿它把
     * "编到 N/M 块"这句话拼出来。**不参与任何判据** —— 能不能沿用由语义字段
     * 全同那条主判据决定（见 [FpDiff.onlyBatch] 里那段"这一级本身不授权沿用"）。
     *
     * 为什么它自己解析而不复用 [Load.partial]：`load` 走到这一支时**还没有**
     * 比过行数（它卡在指纹那一步），而 [Load.partial] 的构造要求 `count` 与
     * 正文行数满足半份的两个界 —— 这里只要一个数，不该顺手把状态判了。
     */
    private fun salvagePrefix(raw: ByteArray): Int =
        decode(raw)?.second ?: -1

    /**
     * 取"上次已经算过的向量前缀"（**续编**用）。返回 `已算过的条数 → 扁平向量`，
     * 或 null（没有可续的半份 / 指纹不符 / 文件损坏）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么它**只**认 [Load.partial]、不认 [Load.valid]
     * ═══════════════════════════════════════════════════════════════════════
     * 续编的语义是"补上缺口"。一份**齐整**的索引没有缺口可补（它的全部向量
     * 都在），把它当"可续"会让调用方算出 `起点 = 全部条数`、然后"什么都没编"
     * 就宣告成功 —— 而那与"真的编完了"逐字相同。所以这里只收半份：
     * 齐整那份走的是"无需续编"（调用方直接返回）。
     *
     * ⚠ 空库（[Load.empty]）同样返回 null：没有向量可续，从头（也没有东西可编）。
     * 这一条与上面同理 —— "0 条可续"必须与"没有可续的东西"分开。
     */
    fun loadPrefix(ctx: Context, name: String, expectFp: String): Pair<Int, FloatArray>? {
        val l = load(ctx, name, expectFp)
        if (l !is Load.partial) return null
        return l.count to l.vec
    }

    /**
     * 半份索引的**续编起点**：从第几块接着编。纯函数（宿主单测钉住）。
     *
     * 返回 `count`（= 已经算过向量的块数），调用方跳过前 `count` 块、
     * 从第 `count` 块开始编。**不返回 null 在 `count <= full` 时永远成立** ——
     * 但那不是这里的责任：[load] 已经把越界的（[Load.none]）挡在外面，
     * 这个函数只回答"接着从哪编"，不回答"能不能续"。
     *
     * ⚠ 为什么"起点 = count"是对的：`buildIndex` 的块列表由 `docs` 逐条
     * [LlmEngine.chunk] 拼出来，顺序确定（同一份 docs + 同一套 cp）。
     * 而 cp / 模型身份 / 正文 hash / 正文行数**全在指纹里**，指纹一致即
     * "这次的块列表与上次落盘时逐位相同" —— 这正是 [load] 比过的那件事。
     * 所以"前 count 块"与索引里那 count 个向量**一一对应**，不需要重放。
     */
    fun resumeFrom(count: Int, rowsTotal: Int): Int = count.coerceIn(0, rowsTotal)

    /**
     * 从索引字节 + 正文字节判"这份索引是不是**半份可续**"。纯函数，宿主单测钉住。
     *
     * 返回 `已编块数`；不是可续的半份时返回 null。判据三条（缺一不可）：
     *   ① 索引能解析出 dim/count（[decode]）；
     *   ② 正文 hash 与指纹里的 `x` **一致**（[textsInSync]）—— 正文没被改过；
     *   ③ `0 < count < 正文行数 - 1` —— **自洽地少了一截**。
     *
     * ⚠ ③的两个界都是必要的：
     *   · `count == 0` 是"一条都没编出来"，与空库同处置（不该报"可续 0 条"，
     *     那会让状态行说"接着编吧"而实际是"从头编"）；
     *   · `count >= rows-1` 是**齐整**（或损坏），没有缺口可补 —— 报"可续"会让
     *     用户以为点一下是补几条，而实际是"没有东西可编"。
     *
     * ⚠ 它**不**比模型身份（那是 [LlmEngine.buildIndex] 的事，只有那里知道当前
     * 模型）：所以它报的是"**有可能**续"，而不是"一定能续"。UI 用这个上界
     * 给一句提示已经足够（比"待重建"精确），但**不能**用它替代真正的续编判据。
     */
    fun resumableCount(indexRaw: ByteArray, textsRaw: ByteArray): Int? {
        val fp = fingerprintIn(indexRaw) ?: return null
        val parsed = decode(indexRaw) ?: return null
        val count = parsed.second
        val rowsInFp = rowsInFingerprint(fp) ?: return null
        val full = rowsToCount(rowsInFp)
        if (count <= 0 || count >= full) return null
        if (!textsInSync(indexRaw, textsRaw)) return null
        return count
    }

    /**
     * 最近一次 [load] 的"为什么不可用"。**只为诊断**（日志 / UI 文案），
     * 不参与任何判据 —— 所以它是一个陪衬变量而不是 [Load] 的载荷：
     * 让它进 [Load] 就得让每个调用点都处理一遍，而绝大多数调用点只关心"能不能用"。
     *
     * ⚠ 它是**全局**的（不是每库一份）：多线程下可能读到别的库那一次的结果。
     * 这是刻意的取舍 —— 它只用来生成一句给**当前**用户看的日志，而那次调用
     * 紧接着发生（同一线程、同一入口），错配的窗口是"另一个线程恰好同时检索
     * 另一个库"，那时日志里会多一句解释**另一个库**的 —— 不会误导当前这条
     * （因为 [LlmEngine.search] 只在自己刚 load 完之后读它）。
     * 为一个纯诊断字段加锁，会把"诊断"变成"同步点"，得不偿失。
     */
    @Volatile var lastDiff: FpDiff = FpDiff.SAME
        private set

    /**
     * 落盘。**原子**：先写 `.tmp` 再 `renameTo`。
     *
     * 为什么不直接 `writeBytes`：写到一半被杀（Android 杀后台很常见）会留下
     * 一个**半截文件** —— 头是好的、向量区缺一截。下次加载时若只看头就会
     * 按 count 去读越界（我们的 decode 会拒，但那是靠"幸好校验了"兜住）。
     * `renameTo` 在同一文件系统上是原子的，读到的一定是"完整的新"或"完整的旧"。
     */
    fun save(ctx: Context, name: String, fp: FpInput, dim: Int, vec: FloatArray, meta: String,
             rows: List<Entry> = emptyList()): Boolean {
        val d = dirOf(ctx, name) ?: return false
        if (!d.isDirectory && !d.mkdirs()) return false
        // [!] 索引的字节由**这里**从 rows 现算正文 hash 与完整指纹，调用方只给
        //     "与模型有关的那一半"。让调用方自己算指纹的版本有一个致命窗口：
        //     它算的 hash 与这里实际写下的字节**必须完全一致**，而两者之间隔着
        //     一次 `encodeTexts` —— 一旦哪天有一边改了编码（多一个换行、换个字段），
        //     写完的索引就**立刻**是失效的（指纹不匹配），症状是"建库成功、
        //     检索永远为空"，且没有任何日志指向这里。
        //     把它收进来之后，"写下的正文"与"指纹里的正文"同源，不可能不一致。
        val textsBytes = try { encodeTexts(rows).toByteArray(Charsets.UTF_8) } catch (t: Throwable) {
            Log.w(TAG, "编码正文失败: ${t.javaClass.simpleName}")
            return false
        }
        // `rows` 与向量行数**必须相等**：整库覆盖时正文里每一行都算过向量。
        // 用 `rows.size`（而不是 `vec.size / dim`）是为了挡住"调用方给的 rows
        // 与它给的 vec 不是一对"这种错 —— 两者不等就直接拒绝落盘，
        // 而不是留下一个"索引少几行"的库（那个症状是"某几条怎么也搜不到"）。
        val rowsCount = rowsOf(textsBytes)
        if (vec.size / dim != rows.size) {
            Log.w(TAG, "save 拒绝落盘：向量行数 ${vec.size / dim} != 正文行数 ${rows.size}")
            return false
        }
        val full = fingerprintOf(fp, textsHashOf(textsBytes), rowsCount)
        val bytes = try { encode(full, dim, vec) } catch (t: Throwable) {
            Log.w(TAG, "编码索引失败: ${t.javaClass.simpleName}")
            return false
        }
        // 正文旁挂文件**先写**：先落向量再落正文有一段窗口，期间索引有效而正文缺失 ——
        // 检索会命中行号、注入时取到空正文、于是"检索到了但回答里什么都没有"。
        // 反过来的窗口（正文在、索引没更新）最坏只是正文暂时对不上行号，
        // 而索引头里的指纹会把它判成"要重建"，不会真的被读。
        if (!writeTexts(d, textsBytes)) {
            Log.w(TAG, "写入正文失败（索引不落盘，避免出现\"有向量没正文\"的半份）")
            return false
        }
        val tmp = File(d, "$FILE.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, FILE))) { tmp.delete(); false }
            else { runCatching { File(d, META).writeText(meta) }; true }
        } catch (t: Throwable) {
            Log.w(TAG, "写入索引失败: ${t.javaClass.simpleName}")
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 建库时**有部分条目编码失败**的落盘路径：正文**全量留下**，向量只留能对齐的那部分，
     * 索引**主动标为"需重建"**（指纹里的正文 hash 与落盘正文一致、但行数与向量行数不等，
     * 于是 [textsInSync] 为 false）。
     *
     * ═══════════════════════════════════════════════════════════════════════════
     * 为什么不是"失败的丢掉、成功的照常建"
     * ═══════════════════════════════════════════════════════════════════════════
     * 这是用户明确拍板的语义（原话："归一化失败按照正文留下、向量缺席、整库标记需重建"），
     * 而它同时修掉了三个真实痛点：
     *
     *   ① **"失败的是哪一条"终于看得见。** 早先的路径把失败条目整条丢弃
     *      （既不进索引、也不进正文），于是用户在界面上**看不到**自己敲的那条 ——
     *      100 条里坏 3 条时只能靠二分重试去找，而那 3 条的原文其实就在他粘贴的文本里。
     *      正文留下之后，"哪几条没进向量"是一个可以直接看、可以直接改的事实。
     *   ② **失败不走"整库弃用"。** 早先"全部失败"返回错误、"部分失败"照样算成功，
     *      两种都在误导：前者让用户以为白建了（正文其实还在），后者让用户以为能检索
     *      （少了几条却不说）。现在统一为"正文在、索引待重建"，用户下一步动作唯一。
     *   ③ **向量缺席的那几行不会让索引错位。** 向量只保留**与正文行号一一对应的前缀**
     *      （见下），所以即便有人误读了这份索引，也只会"少几条"，绝不会"第 i 条配第 j 条"。
     *
     * ⚠ 为什么保留的是**前缀**而不是"所有成功条目"：检索命中的行号会直接用来取正文
     * （[entryAt] / [rowText]），所以"向量区第 i 行"必须就是"正文第 i 行"。
     * 中间某条失败时，后面成功条目的向量**不能往前挤** —— 挤了就是整体错位：
     * 检索分数是对的、注入的内容是错的，而它不崩、不报错，只表现为"检索出来的东西
     * 跟问题无关"（看起来像模型在幻觉）。所以遇到第一个失败就停，之后的向量一律不要。
     *
     * 返回 false = 落盘失败（存储空间等），调用方应如实报错 —— **不是**"部分成功"。
     */
    fun savePartial(ctx: Context, name: String, fp: FpInput, dim: Int,
                    rows: List<Entry>, meta: String): Boolean {
        val d = dirOf(ctx, name) ?: return false
        if (!d.isDirectory && !d.mkdirs()) return false
        val textsBytes = try { encodeTexts(rows).toByteArray(Charsets.UTF_8) } catch (t: Throwable) {
            Log.w(TAG, "编码正文失败: ${t.javaClass.simpleName}")
            return false
        }
        // 只保留"行号对得上"的向量前缀：第 i 行有向量就留着，第一个缺向量的
        // 那一行之后**一律不要**（理由见函数头）。维度不符的同样算"缺"。
        val keep = ArrayList<FloatArray>(rows.size)
        for (r in rows) {
            if (r.vector.size == dim) keep.add(r.vector) else break
        }
        val vec = FloatArray(keep.size * dim)
        for (i in keep.indices) System.arraycopy(keep[i], 0, vec, i * dim, dim)
        // 指纹里的正文 hash 用的是**完整**正文（含没有向量的行），行数也是完整行数 ——
        // 于是 `textsInSync` 会把这份索引判成"与正文不一致"（= 需重建），
        // 而它**不需要**第二套"作废"状态：与"改了正文没重建"走**同一条**路径，
        // 也就没有第二个地方能忘记维护（这条是本仓库一贯的做法）。
        val full = fingerprintOf(fp, textsHashOf(textsBytes), rowsOf(textsBytes))
        val bytes = try { encode(full, dim, vec) } catch (t: Throwable) {
            Log.w(TAG, "编码索引失败: ${t.javaClass.simpleName}")
            return false
        }
        // 正文先写：这份索引是"待重建"的，最坏情况是"正文在、索引旧"，
        // 而旧索引会被指纹判成不一致 —— 不会真的被读。
        if (!writeTexts(d, textsBytes)) {
            Log.w(TAG, "写入正文失败（索引不落盘）")
            return false
        }
        val tmp = File(d, "$FILE.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, FILE))) { tmp.delete(); false }
            else { runCatching { File(d, META).writeText(meta) }; true }
        } catch (t: Throwable) {
            Log.w(TAG, "写入索引失败: ${t.javaClass.simpleName}")
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 建索引**中途**的进度落盘（"断了下次接着编"用）：
     * 正文**全量**写下，向量只留前 `done` 块（连续前缀）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么要有这个函数，而不是让调用方自己拼 rows 再调 [savePartial]
     * ═══════════════════════════════════════════════════════════════════════
     * [savePartial] 的契约是"**rows 就是这份库的全部正文**，向量只留前缀"。
     * 而建索引的调用方手里有两个不同的列表：
     *   · `rows`   —— **已编过**的那些块（有向量）；
     *   · `texts`  —— **这份库的全部**块（不管编没编）。
     * 两者只在"整轮跑完"时相等。把前者当后者传进去，`savePartial` 会
     * **照契约**把它当作整份正文写下去 —— 于是后 `texts.size - done` 块的字
     * 从 `texts.jsonl` 里**消失**，而索引与剩下的正文仍然自洽
     * （count == 行数-1、正文 hash 对得上），下次进来判成 [Load.valid]：
     * 界面报"库建成功了"，条目数却是截断后的那个。
     * 0.9.216 的 `f8549e4` 就是这么错的（用户：导 1000 条、建到 216 条被杀、
     * 重启只剩 216 条 —— **导进来的正文被删掉了**，不是"少算几块"）。
     *
     * 同一个错还连带第二处：指纹里的正文 hash 与行数会按**前缀**算，
     * 于是下次 `loadPrefix(fullFp)` 永远比对不上 —— 续编一次都不会生效，
     * 而它不报错，只是"又从 0 开始"。
     *
     * 所以"前缀向量 + 全量正文"这件事收口到**这里一处**：调用方只报
     * "我编完了几块"（[done]），剩下的拼接由这个函数做。这样"传错列表"
     * 在类型上就不可能发生 —— 比在调用点写一行"注意要传全量"的注释可靠：
     * 本仓库那行注释当时**也在**（注释里写着"正文全量"），而它没拦住这个 bug。
     *
     * @param done 已编完的块数（= 有向量的连续前缀长度）。
     * @param texts 这份库的**全部**块（正文），长度必须 >= [done]。
     * @param sources 与 [texts] 同长的来源表（缺项按空串，与调用方的取法一致）。
     * @param vectors 已算好的向量，扁平存放（`done * dim` 个 float）。
     * @param meta 写进 `meta.json` 的说明（建库中途那份，见 `partialMeta`）。
     * @return false = 参数不自洽或写盘失败（调用方应说"这次没落上"）。
     */
    fun saveProgress(ctx: Context, name: String, fp: FpInput, dim: Int, done: Int,
                     texts: List<String>, sources: List<String>, vectors: FloatArray,
                     meta: String): Boolean {
        // 参数自洽：`done` 在 [0, texts.size]、向量区装得下 done 条。
        // 不自洽时**拒绝落盘**（而不是"尽力拼一份"）：拼出来的那份两个文件
        // 各自合法、配在一起却错，而那种错没有任何现象指向它。
        if (done < 0 || done > texts.size) {
            Log.w(TAG, "saveProgress 拒绝落盘：done=$done 超出正文块数 ${texts.size}")
            return false
        }
        if (vectors.size < done * dim) {
            Log.w(TAG, "saveProgress 拒绝落盘：向量只有 ${vectors.size / dim} 行，少于已编 $done 块")
            return false
        }
        val rows = progressRows(done, texts, sources, vectors, dim)
        return savePartial(ctx, name, fp, dim, rows, meta)
    }

    /**
     * [saveProgress] 的核心（**纯函数**，宿主单测钉得住）：把"已编 `done` 块的向量"
     * 与"**全部**正文"拼成 [savePartial] 要的那张表。
     *
     * ⚠ 返回的表的长度**恒为 `texts.size`** —— 这条就是 0.9.217 那个数据丢失 bug 的判据。
     * 写成"只到 done"（=只装已编过的那些）之后，`savePartial` 会照契约把它当整份正文
     * 覆盖 `texts.jsonl`，后 `texts.size - done` 块的字**从磁盘消失**；而索引与剩下的
     * 正文仍然自洽（`count == 行数-1`、hash 对得上），于是下次判成"可用"：
     * 界面报"库建成功了"，条目数是截断后的那个（用户看到的 216 条）。
     *
     * 为什么单独抽成纯函数：文件 IO 那一层在宿主上跑不了（无 Context），
     * 而"这份表有多长"恰恰是唯一需要钉的量 —— 抽出来之后它就能被离线单测覆盖。
     */
    fun progressRows(done: Int, texts: List<String>, sources: List<String>,
                     vectors: FloatArray, dim: Int): List<Entry> {
        val d = done.coerceIn(0, texts.size)
        val rows = ArrayList<Entry>(texts.size)
        for (k in 0 until d) {
            val off = k * dim
            rows.add(Entry(texts[k], sources.getOrElse(k) { "" },
                vectors.copyOfRange(off, off + dim)))
        }
        // 后 `texts.size - d` 块**按正文占位**（空向量）：[savePartial] 的
        // "遇到第一个缺向量的就停"会恰好停在第 d 块，于是这一份就是
        // "向量前缀 = 已编过的那些、正文 = 全部"。这正是续编要的形态。
        for (k in d until texts.size) {
            rows.add(Entry(texts[k], sources.getOrElse(k) { "" }))
        }
        return rows
    }

    /**
     * **只**保存正文（不碰 `index.bin`）。RAG 阶段 2 的「保存正文」用它。
     *
     * 返回 true = 已落盘。false = 这一轮没写（原子落盘的写失败 / 库名非法）。
     *
     * ⚠ 它会让索引**失效**（指纹里的正文 hash 对不上了）：这正是设计意图 ——
     * 正文与向量是一对，改了正文而没重算向量，检索会用旧语义命中、注入新正文。
     * 生效需要「建库/重建」。而"正文没改"时 hash 不变，索引**仍然有效**
     * （保存是幂等的，不必为"看了一眼再保存"付一次重建）。
     *
     * 为什么需要它（而不是让调用方 `save` 全量重建）：编辑期是**多轮**的
     * （加一条、改一句、删一段），每轮都重算全部向量意味着 100 条要等 5~30 秒，
     * 而用户此时并不需要"立刻可检索"，只需要"我的编辑不会丢"。
     */
    fun saveTexts(ctx: Context, name: String, rows: List<Entry>): Boolean {
        val d = dirOf(ctx, name) ?: return false
        if (!d.isDirectory && !d.mkdirs()) return false
        val bytes = try { encodeTexts(rows).toByteArray(Charsets.UTF_8) } catch (t: Throwable) {
            Log.w(TAG, "编码正文失败: ${t.javaClass.simpleName}")
            return false
        }
        return writeTexts(d, bytes)
    }

    /**
     * **按行偏移**把若干行的正文改掉 —— 其余行**逐字节**保持磁盘上那份（ISSUE #228 的 P1-b）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 与 [saveTexts] 的分工（这两条长得很像，后果相反）
     * ══════════════════════════════════════════════════════════════════════════
     * · [saveTexts] 拿**一整份**条目表把正文整份覆盖 —— 它的前提是"调用方手里
     *   这份就是全库"。旧版编辑器能装下整个库，所以那时它是对的；
     * · 本函数拿**改动过的那几行**（行号 + 新内容）去改磁盘上那份 —— 它的前提
     *   是"调用方只知道自己改了哪几行"。P1-b 之后编辑器里只有**一页**，
     *   拿一页去 [saveTexts] 会把整个库砍成一页（且没有副本），
     *   所以"改字"这件事必须走这一条：**没改的行，真值来自磁盘**。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么它不做增删行（行数必须**恰好**不变）
     * ══════════════════════════════════════════════════════════════════════════
     * [indices] 是行号、[texts] 是那一行改完的内容，两者**等长**。文件总行数不变
     * ——于是"第 i 行的向量 <-> 第 i 行的正文"这条对应关系**一格都没挪**
     * （见下面那段落盘不变式）。中间增删行要把它之后每一行往后/前挪，那是
     * `appendEntries` / `removeEntry` 的事（各有各的入口，各自处理了行号重排），
     * 混进"编辑框保存"里会让一次保存同时是"改几个字"与"重排几十行向量" ——
     * 而后者一旦错位**不报错**（分数对、内容错）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 索引怎么办：**不删**，让它自己失效
     * ══════════════════════════════════════════════════════════════════════════
     * 正文改了、向量没重算 —— 指纹里的正文 hash（`x`）立刻对不上
     * （[textsInSync] 会报 false），于是列表/状态行说"需重建"。
     * 这与 [saveTexts] 的口径**逐字相同**（"改正文不重算向量 = 索引失效"是设计意图），
     * 所以这里**不**像 [importEntries] 那样删掉 index.bin：那一条删的理由是
     * "行数变了、索引的行数对不上、留着它会报出一个假的行数"；而这里行数没变，
     * 索引的行数/count 仍然**是真的**，只是"关于正文内容的那个 hash 过期了" ——
     * 一个说得清、可恢复的状态（点一次「建库/重建」即可），比"整个索引消失"更少惊吓。
     *
     * @param indices 要改的行号（正文文件里的行号；可乱序，内部会排好）
     * @param texts   与 [indices] 逐位对齐的新正文 —— ⚠ **解码之后的正文**（`t` 字段
     *                的值），不是正文文件里那一行 JSON。编码在这里做
     *                （[encodeTextRow]），调用方**不许**自己拼 JSON 行。
     * @return 写盘结果：[EditResult.OK] = 已落盘；[EditResult.FAILED] = 一个字节都没写。
     */
    fun replaceRowsInPlace(ctx: Context, name: String, indices: List<Int>,
                           texts: List<String>): EditResult {
        if (indices.isEmpty() || indices.size != texts.size) return EditResult.FAILED
        val d = dirOf(ctx, name) ?: return EditResult.FAILED
        val raw = readTexts(ctx, name)?.toString(Charsets.UTF_8) ?: return EditResult.FAILED
        val after = spliceRows(raw, indices, texts) ?: return EditResult.FAILED
        if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return EditResult.FAILED
        return EditResult.OK
    }

    /**
     * [replaceRowsInPlace] 的**纯函数**版（喂磁盘上那份正文字符串，返回改完的字符串；
     * `null` = 拒绝，一个字节都没动）。宿主单测钉得住。
     *
     * 拒绝的条件（全都返回 `null`，调用方据此如实报告，而不是"写了一半"）：
     *   · 要改的行号越界 —— 行号来自一次读盘（`readEntriesAligned` 的下标），
     *     越界说明"这份表"与"磁盘上那份"已经不是同一份（库在两次读之间被改过）
     *     -> 宁可整次拒绝，也绝不按一个越界的行号去写；
     *   · 要改的那一行本来就是**空行** —— 空行不是条目（`decodeTextRow` 返回 null，
     *     列表里也不给它「删」）。把它写成有内容的一行会**凭空多出一条条目**，
     *     而"第 i 行"从此不再是原来那个下标（行序没变，但那一格的含义变了）。
     *
     * ⚠ 末行那个由结尾换行产生的空串**不在可改范围**：它是 `rowsOf` 计数口径的
     *   一部分（`split('\n').size`），但它是文件**结尾**的标记，不是一条正文。
     *   这里以"空行不许改"一条同时挡住它 —— 少一条判据就多一条"最后一行被写坏"的路。
     */
    fun spliceRows(raw: String, indices: List<Int>, texts: List<String>): String? {
        if (indices.isEmpty() || indices.size != texts.size) return null
        val lines = raw.split('\n')
        for (k in indices.indices) {
            val i = indices[k]
            if (i < 0 || i >= lines.size) return null
            if (lines[i].isEmpty()) return null
        }
        // 复制一份再改：原表**一个元素都不许动**（调用方可能正拿着它做别的判断，
        // 而这里改的是"磁盘那份"的替身）。改完按原序拼回去 —— 行数不变。
        val out = lines.toMutableList()
        for (k in indices.indices) {
            val i = indices[k]
            // ⚠⚠ 写回去的必须是**一行 JSON**（`{"t":…,"s":…}`），不是那串纯文本！
            //    这一处的入参是**解码之后**的正文（`decodeTextRow().text`），
            //    直接 `out[i] = texts[k]` 会把普通文字写进一个每行都是 JSON 的文件 ——
            //    后果：那一行 `decodeTextRow` 返回 null（"读不出来"），
            //    于是**刚改的那一条从列表与检索里消失**，而磁盘上多了一行垃圾、
            //    行号仍然对得上。不崩不报错，是本屏最贵的一类。
            //    来源（`s`）**照原样保留**：它是"这条从哪儿来"，与"改了它的字"
            //    无关；重写一个默认值会把用户看得见的来源列换掉。
            val src = decodeTextRow(raw, i)?.source ?: ""
            out[i] = encodeTextRow(texts[k], src)
        }
        return out.joinToString("\n")
    }

    /**
     * 「改内容、行数不变」→ **正文与向量同时按行替换**（ISSUE #228 的特性请求）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它与 [replaceRowsInPlace] 的差别只有一处，而那一处是全部
     * ══════════════════════════════════════════════════════════════════════════
     * [replaceRowsInPlace] 只写正文：向量区**一个字节不动**，于是改过的那几行
     * 变成"新正文配旧向量"—— 指纹里的正文 hash（`x`）随即对不上，整个索引被判
     * "需重建"，检索停用直到用户点一次「建索引/重建索引」（大库 = 几十分钟）。
     *
     * 本函数把**改过的那几行向量**一起换掉，并**重算指纹里的正文 hash**（其余
     * 字段从**磁盘上那份指纹原样搬**，见下），于是：
     *   · 第 i 行的正文 <-> 第 i 行的向量 仍然一格没挪（行数不变）；
     *   · 索引与正文**重新同步**（`textsInSync` 为 true）—— **不用重建**，
     *     检索照常可用，而且命中的就是改完的新内容。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么"只重算改过的那几行"是对的（而不是必须整库重算）
     * ══════════════════════════════════════════════════════════════════════════
     * 向量是**逐条独立**的：第 i 行的向量只由第 i 行的正文决定（分块在**建库**
     * 时就已经定下来，本函数不动块集合）。所以改了第 3、7 行，只重算这两条，
     * 其余每一行的向量**逐位仍是对的**。这正是用户要的那条路。
     *
     * ⚠⚠ 但这条路**只对"完整可检索的索引"成立**。所以本函数的前置判据是
     *     "索引**齐整**且与磁盘正文同步"（见 [spliceRowsWithVectors] 的 ③）：
     *     · 索引是**半份**（建库中途被杀）-> 后面那些行**本来就没有向量**，
     *       "只重算改过的那几行"无从谈起（那些行没得改）；
     *     · 索引与正文**已经不同步**（上一次保存只写了正文）-> 我们不知道
     *       磁盘上那些向量是按哪一版正文算的，"只改这一行"会把一份本来就不一致的
     *       索引假装成一致的（**不再报需重建**，而其余行仍是旧语义）。
     *     两种都整次拒绝（调用方退回"只写正文 + 报需重建"，即 [replaceRowsInPlace]
     *     那条老路），而不是"尽力而为地写一半"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 指纹：**只换 `x`（正文 hash）**，其余 12 格从磁盘上那份原样搬
     * ══════════════════════════════════════════════════════════════════════════
     * 这是最要紧的一处。新指纹**不能**用 `embedFpInput(...)` 现算一遍 ——
     * 那样算出来的是"**当前**这套配置"，而当前配置里的 `g`（合批每批条数）等
     * 字段可能与磁盘上那份**不同**（用户改了设置）。一旦不同，写下去的索引会
     * 立刻被 [load] 判成"配置变了"—— 于是这次"免重建"的修法**当场自相矛盾**
     * （刚写完就报需重建，且指向一个用户没动过的字段）。
     *
     * 所以：**模型那一半（p/b/m/d/t/o/k/c/e/n/q/z/g）逐字节照搬磁盘那份**，
     * 只把 `x` 换成新正文算出来的 hash、`r` 保持原值（行数没变）。
     * 于是新索引与磁盘上那份**只差正文 hash**，而它正是"这次改了字"这一件事的
     * 唯一记录 —— 也正是 [load] 认它可用的依据。
     *
     * ⚠ 向量是按**当前的**嵌入模型算的，而指纹里记的模型身份来自磁盘那份。
     *   两者必须真的是同一份 —— 这条由调用方（[LlmEngine.rewriteRows]）在
     *   编码**之前**用 [LlmEngine.search] 那条同源的判据挡（索引可用 = 当前模型
     *   与磁盘那份同源）。本函数只负责"写下去的两份文件彼此一致"。
     *
     * @param indices 要改的行号（正文文件里的行号；可乱序）
     * @param texts   与 [indices] 逐位对齐的新正文（**解码之后**的，不是 JSON 行）
     * @param vectors 与 [indices] 逐位对齐的新向量（每一条 `dim` 个 float，已归一化）
     * @return [EditResult.OK] = 两侧都落了盘；[EditResult.FAILED] = 一个字节都没写。
     */
    fun replaceRowsWithVectors(ctx: Context, name: String, indices: List<Int>,
                               texts: List<String>, vectors: List<FloatArray>): EditResult {
        if (indices.isEmpty() || indices.size != texts.size || indices.size != vectors.size) {
            return EditResult.FAILED
        }
        val d = dirOf(ctx, name) ?: return EditResult.FAILED
        val textsRaw = readTexts(ctx, name)?.toString(Charsets.UTF_8) ?: return EditResult.FAILED
        val indexRaw = try {
            val f = indexFile(ctx, name)
            if (f == null || !f.isFile) null else f.readBytes()
        } catch (t: Throwable) {
            Log.w(TAG, "读取索引失败: ${t.javaClass.simpleName}"); null
        } ?: return EditResult.FAILED
        val plan = spliceRowsWithVectors(textsRaw, indexRaw, indices, texts, vectors)
            ?: return EditResult.FAILED
        // ⚠ 与 [appendEntries] / [removeEntry] **同一个次序**：先写索引、后写正文。
        //   两个文件各自是原子的（tmp + rename），但**一对**不是。先写索引失败
        //   -> 正文还是旧的、索引也是旧的（这次改字读不出来，但库仍然是自洽的）；
        //   先写正文失败 -> 索引是新的、正文是旧的，而新索引的 `x` 是按**新**正文
        //   算的 —— [load] 会把这份"索引说的正文"与磁盘上那份比成不一致，
        //   于是判"需重建"（可恢复，且**不会**用错内容命中）。两个方向都可恢复，
        //   但后者的现象（突然要重建）比前者（改动没生效）更费解，所以选先索引。
        if (!writeIndexAtomic(d, plan.indexBytes)) return EditResult.FAILED
        if (!writeTexts(d, plan.textsBytes)) return EditResult.STALE
        return EditResult.OK
    }

    /**
     * [replaceRowsWithVectors] 的**纯函数**那一半（宿主单测钉得住）：不碰文件，
     * 由"磁盘上正文字符串 + 索引字节"算出"改完之后的两份字节"。
     *
     * `null` = **整次拒绝**（调用方据此如实报告，而不是"写了一半"）。拒绝的条件：
     *
     *   ① 参数不配对 / 空计划 —— [indices] / [texts] / [vectors] 三者必须等长且非空
     *      （空 = 什么都没改，不是"写一次"）；
     *   ② 索引解不出来 / 行号越界 / 改到空行 —— 与 [spliceRows] **同一条**判据
     *      （那一行的语义就是"这一行是不是一条正文"），这里不另写一遍：
     *      两处各写一遍必然分叉，而分叉的症状是"这一版拒绝了、那一版没拒绝"；
     *   ③ **索引不齐整或与正文不同步** —— 见 [replaceRowsWithVectors] 那段说明。
     *      判据是 `count == rows - 1`（索引完整）**且** `textsInSync`（两边同源）。
     *      半份索引（建库中途被杀）与"上次只写了正文"都在这里被拒 —— 它们**不是**
     *      "改几个字"，而是"这份索引本来就不完整"，处置完全不同（重建 / 续编）；
     *   ④ 向量维度对不上 / 条数与行号数不等 —— 那是调用方把"哪条配哪行"搞错了，
     *      写下去就是"第 i 行配第 j 条的向量"（分数对、内容错，不报错）。
     *
     * 返回值里的 [SplicePlan.textsBytes] 是**新正文**、[SplicePlan.indexBytes] 是
     * **新索引**（指纹只换了 `x` 那一格，见 [replaceRowsWithVectors]）。
     */
    fun spliceRowsWithVectors(textsRaw: String, indexRaw: ByteArray,
                              indices: List<Int>, texts: List<String>,
                              vectors: List<FloatArray>): SplicePlan? {
        if (indices.isEmpty() || indices.size != texts.size || indices.size != vectors.size) return null
        // 正文那一半复用 [spliceRows]：行号越界 / 空行 / 编成 JSON 行这一族判据
        // 只在那一处，重复实现会让两条路在"什么算可改的行"上分家。
        val after = spliceRows(textsRaw, indices, texts) ?: return null
        val parsed = decode(indexRaw) ?: return null
        val (dim, count, vec) = parsed
        if (dim <= 0) return null
        // ③ 索引必须**齐整**（count == 段数-1）且与正文**同源**（`x` 对得上）。
        //    不齐整 = 后面那些行本来就没有向量；不同源 = 我们不知道磁盘上那些向量
        //    是按哪一版正文算的。两种都拒绝 —— 见函数头那段。
        val before = textsRaw.toByteArray(Charsets.UTF_8)
        val rows = rowsOf(before)
        if (count != rowsToCount(rows)) return null
        if (!textsInSync(indexRaw, before)) return null
        // ④ 每一条向量都必须正好 `dim` 个 —— 少一个就是"这一行没算出来"，
        //    而它写下去的后果是向量区整体错位（后续行全部前移一位）。
        for (v in vectors) if (v.size != dim) return null
        // 按行号把新向量替进去，其余行**逐字节**搬（copyOf 只复制不改）。
        val newVec = vec.copyOf()
        for (k in indices.indices) {
            val i = indices[k]
            if (i < 0 || i >= count) return null
            System.arraycopy(vectors[k], 0, newVec, i * dim, dim)
        }
        // 指纹：模型那一半**逐字节照搬磁盘那份**，只换 `x`（见 [replaceRowsWithVectors]）。
        val fpIn = fingerprintIn(indexRaw) ?: return null
        val fpAfter = fingerprintOf(FpInput(fpIn), textsHashOf(after.toByteArray(Charsets.UTF_8)), rows)
        val indexBytes = try { encode(fpAfter, dim, newVec) } catch (t: Throwable) {
            Log.w(TAG, "编码索引失败: ${t.javaClass.simpleName}"); return null
        }
        return SplicePlan(after.toByteArray(Charsets.UTF_8), indexBytes)
    }

    /**
     * [spliceRowsWithVectors] 的产物：两份**新字节**（正文 + 索引），由调用方落盘。
     *
     * 为什么是两个字节串而不是"直接写"：纯函数那一半要在宿主上被单测钉住，
     * 而它一碰文件就跑不了（没有 `Context`）。把"算"与"写"分开之后，
     * 最要紧的那两个量（**写下去的是什么字节**）就能离线断言。
     */
    class SplicePlan(val textsBytes: ByteArray, val indexBytes: ByteArray)

    // ══════════════════════════════════════════════════════════════════════════
    //  落盘不变式：索引与正文的**对应关系**只有一处（这里）
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 索引文件与 `texts.jsonl` 之间的全部依赖只有两条：
    //
    //   ① 第 i 行的正文 <-> 第 i 行的向量（**行号**是唯一的对应关系）；
    //   ② 索引头的 `count` == 正文行数（否则就是"索引落后于正文"，
    //      两者同时进指纹的 `x`（正文 hash）与 `r`（行数）两项）。
    //
    // 这两条在**四个**写盘入口上都要成立：save（整库覆盖）/ saveTexts（只改正文）/
    // appendEntries / removeEntry。逐个手写"记得同时改 count 与 r"是必定漂移的
    // ——漂移的方向恰好是"少改一处"，而它的现象是"某几条永远搜不到"或
    // "检索命中的是隔壁那一行"，全都不报错。所以四个入口**共用**下面这对函数。
    //
    // ⚠ 为什么先写索引、再写正文（`index-first`），以及它为什么与 [save] 相反：
    //   · [save] 先写正文：它写的是**一份全新的索引**，中途失败最坏是"白写一次正文"；
    //   · 这里先写索引：两个文件各自都是原子的（tmp + rename），但**一对**不是。
    //     先写索引、后写正文失败 -> 索引 count = N，正文 = N-1 行 -> 落到
    //     "索引领先于正文"。这个状态由 [load] 的 rows 校验与 [rowsOf] 的行数
    //     判据一起挡住：指纹里的 `r` 是 N，重建后会按正文重算 —— 不会错位。
    //     反过来先写正文、后写索引失败 -> 索引 count = N-1、正文 = N 行，
    //     此时**前一 N-1 行仍然一一对应**，检索照样给出"看起来正常"的结果，
    //     用户只会觉得"新加的那条搜不到"。两种都可恢复，但后者没有任何现象指向它，
    //     所以宁可让失败落在前者。

    /**
     * 追加/删除的结果。**三态**，不是"成功/失败"两态。
     *
     * [OK] 与 [STALE] 都表示"正文确实写下去了、索引也确实更新了"，区别在**覆盖到哪**：
     * - [OK]    索引与正文完全对齐（`count == rows`，可检索）；
     * - [STALE] 有行落下了（追加时某条没算出向量 / 删除时索引补写失败）。
     *           这些行**留在正文里**（用户敲的东西不许丢），但索引没有它们 ——
     *           于是整个索引被判为"需重建"。
     *
     * 为什么把 [STALE] 单列出来而不是统一报失败：报失败时用户会以为"没存上"、
     * 重敲一遍；而实际是"存上了、只是还搜不到"。这两种情况的下一步操作完全不同
     * （重敲 vs 点「建库/重建」）。
     */
    enum class EditResult { OK, STALE, FAILED }

    /**
     * 在**末尾**追加若干条目（正文追加、索引尾部补向量）。
     *
     * 为什么追加只能在末尾：中间插入要把插入点之后的**每一行**都往后挪一格 ——
     * 那是全量重写正文 + 全量重排向量，与"重建"没有区别，却多出一条"挪了半截"
     * 的失败路径。用户可以在编辑器里改任意一行再重建，那才是"插入"的正路。
     *
     * 为什么没算出向量的条目**照样把正文写进去**：`texts.jsonl` 是用户敲进去的
     * 东西的唯一副本。这里丢掉它 = 用户敲的字没了，而界面只能说一句"失败"。
     */
    fun appendEntries(ctx: Context, name: String, newOnes: List<Entry>): EditResult {
        if (newOnes.isEmpty()) return EditResult.OK
        val before = readState(ctx, name) ?: return EditResult.FAILED
        val d = before.d
        val dim = before.dim
        val vec = before.vec
        val textsBytes = before.textsBytes
        val rowsBefore = before.rows
        val sb = StringBuilder()
        for (r in newOnes) {
            val o = org.json.JSONObject()
            o.put("t", r.text)
            o.put("s", r.source)
            sb.append(o.toString()).append('\n')
        }
        val after = textsBytes + sb.toString().toByteArray(Charsets.UTF_8)
        // 正文行数 = 行数(旧) + 追加条数：追加时**每一条**都占正文的一行
        //（即使它没有向量）。所以 `rows` 用"新正文的行数"现算，
        // 而不是"旧 rows + addVec.size" —— 后者在有条目被跳过时会少算，
        // 于是刚敲进去的那条正文行数没进指纹，下一次加载把它判成"没索引"。
        val rowsAfter = rowsOf(after)
        // 只有"算了向量且维度对得上"的条目进向量区。
        val addVec = ArrayList<FloatArray>(newOnes.size)
        var dropped = 0
        for (r in newOnes) {
            if (r.vector.size == dim) addVec.add(r.vector) else dropped++
        }
        // 兜底：追加进向量区的条数不许超过"正文新增的条目数"。超过了说明
        // 调用方给的向量与它给的条目不是一对 —— 那会让索引整体偏移，
        // 而偏移后的两份文件各自都合法（只有"第 i 行配第 j 行"错了）。
        if (addVec.size > rowsToCount(rowsAfter) - rowsToCount(rowsBefore)) return EditResult.FAILED
        val newVec = FloatArray(vec.size + addVec.size * dim)
        System.arraycopy(vec, 0, newVec, 0, vec.size)
        for (i in addVec.indices) System.arraycopy(addVec[i], 0, newVec, vec.size + i * dim, dim)
        val fpAfter = fingerprintOf(FpInput(before.fp), textsHashOf(after), rowsAfter)
        val okIdx = writeIndexAtomic(d, encode(fpAfter, dim, newVec))
        if (!okIdx) return EditResult.FAILED
        // 索引里 count 与 rows 必须**同时**跟着正文走（这里是唯一一处）。
        if (!writeTexts(d, after)) return EditResult.STALE
        return if (dropped == 0 && addVec.size == newOnes.size) EditResult.OK
        else EditResult.STALE
    }

    /**
     * 删除**某一行**（正文与向量同时移掉，后面的行整体前移一位）。
     *
     * [index] 是**正文文件的行号** —— 也就是检索命中报的那个行号，不是"第几条非空行"。
     * 两者在"正文里有空行"时不相等，而这种不等不会报错：删错一行之后的索引
     * 仍然"能命中 N 条"，只是每一条的内容都往前/往后错了一位。
     *
     * 三道闸：
     * · 行号越界 / 那一行本来就是空行 -> 拒绝（删空行 = 界面上条目数不变，用户以为没删掉）；
     * · 索引里没有这一行（`index >= count`）-> 拒绝（那是"正文领先于索引"的状态，
     *   删它会让两个文件**整体错位一行**）；
     * ⚠ 两处行号口径必须分清：`lines[index]` 里的 `index` 是**正文段里的下标**
     *   （0 基，含空行），而 `index >= count` 里的 `count` 是**索引行数**。
     *   正常状态下 `count == rows - 1`，两者恰好一致；**有坏行被跳过**时
     *   索引行数会少于正文条目数 —— 那种状态下 `index >= count` 会拒掉一些
     *   本来能删的行，这是刻意的：宁可让用户点「建库/重建」，也不要在两个文件
     *   本就不同的前提下"猜着删"。
     * · 删完不许为空（条目数 <= 1）-> 拒绝（"删掉最后一条"与"这个库从来没东西"
     *   在界面上应当可分；空库由「删索引」那条路处理）。
     */
    fun removeEntry(ctx: Context, name: String, index: Int): EditResult {
        if (index < 0) return EditResult.FAILED
        val before = readState(ctx, name) ?: return EditResult.FAILED
        val d = before.d
        val dim = before.dim
        val count = before.count
        val vec = before.vec
        val textsBytes = before.textsBytes
        val lines = textsBytes.toString(Charsets.UTF_8).split('\n')
        val rowsBefore = before.rows
        if (index >= lines.size || lines[index].isEmpty()) return EditResult.FAILED
        if (index >= count) return EditResult.FAILED      // 索引里没有这一行 = 不许动它
        // 删完不许为空：条目数（段数 - 1）必须 >= 2 才允许删这一次，
        // 也就是"删完还剩至少一条"。"删掉最后一条"与"这个库从来没东西"
        // 在界面上应当可分（后者由「删索引」那条路处理）。
        if (rowsToCount(rowsBefore) <= 1) return EditResult.FAILED
        val kept = ArrayList<String>(lines.size - 1)
        for (i in lines.indices) if (i != index) kept.add(lines[i])
        // 行尾必须留一个换行（`[a,b]` -> `a\n`）：少了它，"最后一行"会与
        // 下一次追加的内容粘成一行，而两边的行数都算作"没变"。
        val after = (kept.joinToString("\n")).toByteArray(Charsets.UTF_8)
        val rowsAfter = rowsOf(after)
        val countAfter = rowsToCount(rowsAfter)
        val newVec = FloatArray(countAfter * dim)
        // 逐行搬：删掉的那一行之后的每一行都**前移一位**。少搬一行或多搬一行
        // 都是"分数对、内容错"的错位，且不会报错 —— 所以这里不用 copyOf 之类的
        // 批量运算，而是一行一行搬，让"哪一行去哪儿"在代码里看得见。
        var dst = 0
        for (r in 0 until count) {
            if (r == index) continue
            System.arraycopy(vec, r * dim, newVec, dst * dim, dim)
            dst++
        }
        if (dst != countAfter) return EditResult.FAILED   // 搬运数与预期不符：不落盘
        val fpAfter = fingerprintOf(FpInput(before.fp), textsHashOf(after), rowsAfter)
        if (!writeIndexAtomic(d, encode(fpAfter, dim, newVec))) return EditResult.FAILED
        if (!writeTexts(d, after)) return EditResult.STALE
        return EditResult.OK
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  导入 / 导出：知识库正文的"生命周期出口"
    // ══════════════════════════════════════════════════════════════════════════
    //
    // 在它之前，正文只在"点库回填编辑器 -> 改 -> 保存/重建"这一条路里活着，
    // 而「删索引」会把 index.bin 与 texts.jsonl **一起**删 —— 删了就没了。
    // 换手机 / 刷机 / 重装 App = 攒下来的知识库归零，而正文是用户亲手敲的唯一副本。

    /**
     * 导出用：读出某个库的全部条目（含来源）。库不存在 / 读不出都返回**空表**
     * （调用方据此提示"没有可导出的内容"，**不抛异常**）。
     *
     * 与 [readEntries] 的分工：那个是给"编辑 / 检索"用的（坏行跳过、返回压缩后的表），
     * 这个是给"原样带走"用的 —— 它**复用同一个实现**而不是再写一遍：
     * 两处各写一遍"什么算坏行"会在导出的那一份上分岔，而分岔后的现象是
     * "导出的文件比库里少几条"，谁也不会发现。
     */
    fun exportEntries(ctx: Context, name: String): List<Entry> = readEntries(ctx, name)

    /**
     * 导入用：把一批正文**合并**进 `name` 这个库。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这条路径**不**走 [appendEntries]
     * ══════════════════════════════════════════════════════════════════════════
     * [appendEntries] 要求库里有**可用的索引**（它要读出 dim 与向量区往后接），
     * 而且要求每条都给向量。导入的场景两条都不成立：
     *
     *   · 导入的正文是**没有向量**的（信封里刻意不带向量，见 [RagArchive]）；
     *   · 而且导入往往发生在一个"待重建"或"从未建过索引"的库上。
     *
     * 更根本的是成本：`appendEntries` 逐条 encode 需要嵌入模型在线，
     * 导入 500 条就得等几分钟。而导入的语义本来就是"正文先到、索引待重建"，
     * 那正是 `保存正文` 那条路已有的状态 —— 不是新造状态。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么是**合并**而不是整份覆盖
     * ══════════════════════════════════════════════════════════════════════════
     * 用户的动机几乎总是"把一份笔记补进来"，而不是"取代现有的"。覆盖不会报错，
     * 只会让库里原有的内容在某次"导入"之后消失 —— 那正是本仓库反复要消灭的那种
     * 静默错。所以已有的条目**只增不改**，追加在末尾。
     *
     * ⚠ 合并之后**索引必然作废**（正文比索引多），这是**刻意的**，
     * 也是本功能唯一正确的失败方向：宁可让用户点一次「建库/重建」，
     * 也绝不能出现"用旧边界算的向量命中、注入新边界切出的正文"——
     * 那种状态下排序像随机、接口 200、日志干净。
     * 所以这里**同时**把 index.bin 删掉（而不是留着它）：留着它的话
     * `list()` 会照旧报出"有索引"，而 `textsInSync` 报 false ——
     * 两个信号打架时，用户看到的那个（列表）说的是"能用"。
     * 删掉它，列表直接变成「没有索引」，与真相一致。
     *
     * @return 合并后的条目总数；库名非法 / 写盘失败返回 -1。
     */
    fun importEntries(ctx: Context, name: String, incoming: List<Entry>): Int {
        if (incoming.isEmpty()) return -1
        val d = dirOf(ctx, name) ?: return -1
        if (!d.isDirectory && !d.mkdirs()) return -1
        // 已有的正文（可能为空 = 新库）。**只读一次**，后面的写法与它无关 ——
        // 分成两次读的窗口里恰好有一次重建落盘的话，写回去的是"新正文 + 旧行数"。
        val before0 = readTexts(ctx, name)?.toString(Charsets.UTF_8) ?: ""
        // 接缝前先补上结尾换行（空串除外）：这份文件是**可以被外部改的**
        // （见 [entriesCountOf] 的注释），用户手工编辑过、或在别处导入而来的
        // `texts.jsonl` 末尾可能没有 `\n`。直接拼的话，旧末条与本次首条会
        // 粘成同一行，`decodeTextRow` 解析失败 → 那两条都"读不出来"，
        // 而 `rowsToCount` 数的是段数、行数没变，回执上的数字对得上 ——
        // 用户看到的是"导入成功，但库里那两条打不开"，无从归因。
        val before = if (before0.isEmpty() || before0.endsWith("\n")) before0 else before0 + "\n"
        val after = before + encodeTexts(incoming)
        val rowsAfter = rowsOf(after.toByteArray(Charsets.UTF_8))
        if (rowsToCount(rowsAfter) > ChunkParams().maxChunks) return -1
        // 先删索引：万一正文写失败，剩下的是"没有索引 + 老正文"（可重建、不误导）；
        // 反过来先写正文再删索引，中间被杀掉留下的是"新正文 + 老索引"，
        // 而那正是我们要避免的"用旧向量命中新正文"。
        indexFile(ctx, name)?.let { runCatching { it.delete() } }
        if (!writeTexts(d, after.toByteArray(Charsets.UTF_8))) return -1
        // 行数核对：写完再数一遍，与预期不符就如实返回 -1（调用方提示需重建，
        // 而不是报"导入成功"）。这里用与 [load] 同一口径 [rowsOf]。
        val actual = rowsToCount(rowsOf(readTexts(ctx, name) ?: ByteArray(0)))
        if (actual != rowsToCount(rowsAfter)) return -1
        return actual
    }

    /**
     * 某个库已有的条目数（导入前给用户看"库里已有 N 条"）。库不存在返回 0。
     *
     * ⚠ 口径**必须**是 [rowsToCount]（段数 - 1），与索引侧、[importEntries] 的
     * 行数核对、[load] 的 `count == rows - 1` 是同一条。
     *
     * 旧实现数的是"非空行个数"，两者在**自产文件**上恰好相等（[encodeTexts]
     * 每行都以 `\n` 收尾、不留空行），而这份文件是**可以被外部改的** ——
     * 导入路径 `importEntries` 写的是 `已有正文 + encodeTexts(新增)`，其中
     * "已有正文"是**从磁盘原样读回来的**：用户手工编辑过 texts.jsonl、
     * 或在其中留了一个空行，两种口径立刻分家（实测：末尾无换行、中间有空行、
     * 多个连续空行等 6 种形状都不等）。
     *
     * 分家的后果是**用户看到的是假数**：确认框说"已有 3 条 → 导入后 5 条"，
     * 而实际落地是按段数算的另一个值 —— 一处对不上，两处都不可信。
     * 所以这里与其它所有地方共用同一个函数，**不另立一套数法**。
     */
    fun entriesCountOf(ctx: Context, name: String): Int =
        readTexts(ctx, name)?.let { rowsToCount(rowsOf(it)) } ?: 0

    /**
     * 追加/删除前要读的**那一对**（索引 + 正文）以及它们的不变式检查。
     *
     * 为什么把"读一对"收成一个函数：这两份文件必须来自**同一个时刻**。
     * 分成两次读（先读正文、再读索引）时，中间恰好有一次重建落盘的话，
     * 拿到的就是"新正文 + 旧向量"，而拼接的结果完全合法 —— 只有行数或
     * hash 对不上时才会被指纹逮住。这里一次读齐，并立刻校验三件事：
     * 指纹里能取出 dim、`r`（正文行数）与正文文件**实际**行数一致、
     * 索引里的 `count` 与 `r` 一致。任何一条不成立都返回 null（这一次编辑
     * 整个不做），而不是"尽力而为地写一半"。
     */
    private class State(
        val d: File, val dim: Int, val count: Int, val vec: FloatArray, val fp: String,
        val textsBytes: ByteArray, val rows: Int
    )

    private fun readState(ctx: Context, name: String): State? {
        val d = dirOf(ctx, name) ?: return null
        val textsBytes = readTexts(ctx, name) ?: return null
        val raw = try {
            val f = indexFile(ctx, name)
            if (f == null || !f.isFile) null else f.readBytes()
        } catch (t: Throwable) {
            Log.w(TAG, "读取索引失败: ${t.javaClass.simpleName}"); null
        } ?: return null
        val fp = fingerprintIn(raw) ?: return null
        val dim = dimInFingerprint(fp) ?: return null
        val rowsInFp = rowsInFingerprint(fp) ?: return null
        val parsed = decode(raw) ?: return null
        val (dimInHead, count, vec) = parsed
        if (dim <= 0 || dimInHead != dim) return null
        val rows = rowsOf(textsBytes)
        // 三条同时成立才允许编辑：指纹记的行数 == 正文实际段数、索引 count == 段数-1、
        // 头的维度 == 指纹的维度。任何一条不成立都**整个不做**，而不是写一半 ——
        // "写一半"之后的两个文件是"合法的两份、配在一起却错"，没有任何现象指向它。
        if (rows != rowsInFp || count != rowsToCount(rowsInFp)) return null
        return State(d, dim, count, vec, fp, textsBytes, rows)
    }

    /**
     * 原子写索引（`.tmp` + `renameTo`，与 [save] 同一套路；**不碰正文**）。
     *
     * 为什么单独抽出来（而不是复用 [save]）：[save] 会**由 rows 现编正文**
     * 并一起落盘，而追加/删除这两条路上"正文"是调用方已经算好 hash 的那一份字节 ——
     * 让 save 重编一遍就会出现"写下去的正文"与"指纹里那一半"两份来源，
     * 它们之间隔着一次编码，任一边变了就是"新索引立刻失效"（每次打开都在重建）。
     */
    private fun writeIndexAtomic(d: File, bytes: ByteArray): Boolean {
        val tmp = File(d, "$FILE.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, FILE))) { tmp.delete(); false } else true
        } catch (t: Throwable) {
            Log.w(TAG, "写入索引失败: ${t.javaClass.simpleName}")
            runCatching { tmp.delete() }
            false
        }
    }

    /** 读出正文文件的原始字节。文件不在 / 读失败都返回 null（**不抛异常**）。 */
    fun readTexts(ctx: Context, name: String): ByteArray? {
        val f = textsFile(ctx, name) ?: return null
        if (!f.isFile) return null
        return try { f.readBytes() } catch (t: Throwable) {
            Log.w(TAG, "读取正文失败: ${t.javaClass.simpleName}")
            null
        }
    }

    /** 读出全部条目（一行一条，行序 == 索引行序）。坏行**跳过**，返回可用的那些。 */
    fun readEntries(ctx: Context, name: String): List<Entry> {
        val raw = readTexts(ctx, name)?.toString(Charsets.UTF_8) ?: return emptyList()
        return readEntriesTexts(raw)
    }

    /**
     * [readEntries] 的**纯函数**版（喂正文字节，不碰文件）。宿主单测钉得住。
     *
     * 为什么必须和读文件那版是同一个实现：UI 的条目列表、删除时的行号、
     * 建库时的正文读回，全都走它 —— 各自实现一遍会在"坏行怎么算"上分岔
     * （跳过还是留空行），于是"第几行"在两处不一样，而错位删行不报错。
     */
    fun readEntriesTexts(raw: String): List<Entry> {
        val out = ArrayList<Entry>()
        val lines = raw.split('\n')
        for (i in lines.indices) {
            if (lines[i].isEmpty()) continue
            val e = decodeTextRow(raw, i) ?: continue
            out.add(e)
        }
        return out
    }

    /**
     * 读出全部条目，**行号对齐**：下标 `i` 就是索引里的第 `i` 行，坏行/空行留 `null` 占位。
     *
     * 为什么不能直接用 [readEntries]：它把坏行/空行**跳过**，于是返回的 list 会被压紧 ——
     * 下标 `i` 变成"第 i 条可用条目"而不是"第 i 行"。而注入侧 `render` 是按
     * **行号**取正文的（`rows[p.index]`，见 [RagInject.render]），两者只在
     * "坏行恰好都在末尾"时偶然相等。命中靠后的行时，压紧的数组下标不够 →
     * `p.index >= rows.size` → **整块被跳过**（用户报的"选中 1 条但正文为空"）；
     * 命中落在前几行时更坏：注入的是**另一行**的正文（分数对、内容错，不报任何错）。
     *
     * 所以这里保留 `null` 占位：宁可让调用方看到"第 3 行是空的"，也不能把行号往后挪。
     *
     * 只读一次文件（比 [rowText] 逐行各读一次全文更省），且与 [decodeTextRow]
     * 是同一份解析口径 —— 那条"两个文件行序必须一致"的硬约束就落在这里。
     *
     * 为什么正文与来源一起返回（而不是调用方各取一次）：它们同属一条条目，
     * 分开两次读文件就可能读到**不同版本**的正文（重建恰好发生在两次读之间），
     * 于是"正文是 A 段的、来源写的是 B 段" —— 这种错位没有任何现象。
     */
    fun readEntriesAligned(ctx: Context, name: String): List<Entry?> {
        val raw = readTexts(ctx, name)?.toString(Charsets.UTF_8) ?: return emptyList()
        return readEntriesAlignedTexts(raw)
    }

    /**
     * [readEntriesAligned] 的**纯函数**版（喂正文字节，不碰文件）。宿主单测钉得住。
     *
     * 行数取 `split('\n').size`（与 [rowsOf] 同一口径），于是末行那个由结尾换行
     * 产生的空串也会占一个 `null` —— 它正是索引侧 `rows` 的计数方式，
     * 少算一格会让"最后一行"在两边差一位。
     */
    fun readEntriesAlignedTexts(raw: String): List<Entry?> {
        val lines = raw.split('\n')
        val out = ArrayList<Entry?>(lines.size)
        for (i in lines.indices) out.add(decodeTextRow(raw, i))
        return out
    }

    /**
     * 索引文件里的指纹与**当前正文**是否对得上（只看正文那一项）。
     *
     * 为什么单独有这一条判据（而不是让调用方自己比指纹）：整份指纹还含模型与
     * 切块参数，而"索引是否可用"要三样都对。这里回答的只是其中一个问题：
     * **正文有没有被改过**。UI 靠它给出可操作的提示（「正文已改：需重建」），
     * 引擎靠它把"没有可用索引"拆成两种诊断。两者都只需要这一个原子问题。
     *
     * 纯函数（喂字节，不碰文件），宿主单测钉得住。
     */
    fun textsInSync(indexRaw: ByteArray, textsRaw: ByteArray): Boolean {
        val fp = fingerprintIn(indexRaw) ?: return false
        val h = textsHashInFp(fp) ?: return false
        return h == textsHashOf(textsRaw)
    }

    /** 索引文件在不在（不解析内容）。用于把"从来没建过"与"建过但失效了"分开提示。 */
    fun isPresent(ctx: Context, name: String): Boolean {
        val d = safeDir(ctx, name) ?: return false
        // ⚠⚠ 判据必须与 [list] **逐字一致**（"目录里有正文或索引"），
        //     差一个字符就会造出一整个族的两难：
        //       · 按 `index.bin` 判 -> "建索引没走完就退出"的库在列表里看得见、
        //         而点它时「删库」说"磁盘上没有这个库"（ISSUE #259 第 ⑤ 条：
        //         用户报的"没有任何操作手段删库"）；
        //       · 按 `texts.jsonl` 判 -> 一个只有索引的老库（阶段 1）删不掉。
        //     两种症状都是"列表里点得动、按钮说没有它"，而屏幕上没有一处能解释
        //     两者为什么不同 —— 所以这把尺子只允许有一处（这里），
        //     [list] 那一边**逐字**是同一句判据（守卫钉着这条"同源"）：
        //     改一处而不改另一处时，`run_rag_kb_state_guard.sh` 会当场变红。
        return File(d, FILE).isFile || File(d, TEXTS).isFile
    }

    /**
     * 库名 -> 目录，**不做 `mkdirs`**（只回答"它在不在"的那条路上用）。
     *
     * 为什么需要它：[dirOf] 是"拿名字拼路径"的唯一入口，但它只净化、不建目录 ——
     * 真正会建目录的是 `RagIndexStore` 里几个写路径（它们在写入前 `mkdirs`）。
     * 判"在不在"这件事**绝不能**碰文件系统（一碰就造出一个空目录，
     * 而空目录正好是"删不掉的幽灵行"的来源）。
     * 这里显式写出来，是为了让"只看不建"这件事在调用点读得出来。
     */
    private fun safeDir(ctx: Context, name: String): File? = dirOf(ctx, name)

    /** [textsInSync] 的读文件版（UI / 引擎用）。文件缺失一律按"不一致"处理。 */
    fun textsInSync(ctx: Context, name: String): Boolean {
        val fp = indexFingerprint(ctx, name) ?: return false
        val h = textsHashInFp(fp) ?: return false
        // 正文文件不存在时按 0 字节算 —— 与 [save] 写"空正文"时用的是同一份输入，
        // 所以"本来是空库"不会被误判成"被改坏了"（空 hash 对空 hash）。
        return h == textsHashOf(readTexts(ctx, name) ?: ByteArray(0))
    }

    /**
     * 只读索引**头**里的指纹（不读向量区）。
     *
     * 为什么要区分"读头"与"读全文"：一次检索已经要把向量区读进来算点积了，
     * 若再为了拿指纹把整份文件（100 条 × 384 维 = 150KB）读第二遍，就是白读。
     * 而头是定长的：`magic(4) | fpLen(4) | dim(4) | count(4) | fp(fpLen)` ——
     * 读 16 字节拿到 fpLen，再读 fpLen 字节即可，与向量区多大无关。
     */
    fun indexFingerprint(ctx: Context, name: String): String? {
        val f = indexFile(ctx, name) ?: return null
        if (!f.isFile) return null
        return try {
            val head = ByteArray(16)
            java.io.RandomAccessFile(f, "r").use { raf ->
                // readFully 而不是 read：`read` 在短读时返回实际字节数（本地文件
                // 通常不会短读，但"偶尔读到一半"的症状是**取到一个截断的指纹**，
                // 它会安静地永不匹配 = 每次都在重建，没人会去查）。
                raf.readFully(head)
                if (String(head, OFF_MAGIC, 4, Charsets.US_ASCII) != MAGIC) return null
                val fpLen = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(OFF_FPLEN)
                if (fpLen <= 0 || fpLen > 4096) return null
                val fp = ByteArray(fpLen)
                raf.readFully(fp)
                String(fp, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读取索引指纹失败: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 写正文旁挂文件（原子：`.tmp` + `renameTo`，与 `index.bin` 同一套路）。
     *
     * 为什么不用 `meta.json` 存正文：meta 是**只给人看**的（排障时一眼看出用的哪个模型、
     * 什么切块参数），判据一律不读它。把正文塞进去会让"给人看的"与"给机器判的"混成一份，
     * 改一句文案就可能动了判据；而且正文里含任意字符（换行、引号、控制符），
     * 塞进单个 JSON 文档要么转义地狱、要么整个文档解析失败。
     *
     * 行格式：一行一个 JSON 对象 `{"t":"正文","s":"来源"}`。**按行**而不是整个 JSON 数组，
     * 是为了让"少读几行"不影响其余部分 —— 整份解析失败会让 100 条全丢，
     * 只有一条坏掉时我们只丢那一条（见 [row]）。
     *
     * [!] 行序 **必须** 与索引行序一致：这是两个文件之间唯一的对应关系，
     * 错位会让"第 3 条的向量"配上"第 7 条的正文"——检索分数是对的，注入的内容是错的。
     */
    private fun writeTexts(d: File, bytes: ByteArray): Boolean {
        val tmp = File(d, "$TEXTS.tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(d, TEXTS))) { tmp.delete(); false } else true
        } catch (t: Throwable) {
            Log.w(TAG, "写入正文失败: ${t.javaClass.simpleName}")
            runCatching { tmp.delete() }
            false
        }
    }

    /**
     * 把条目编成正文文件的字节（**纯函数**，宿主单测钉得住）。
     *
     * 行格式一行一个 JSON 对象，行序 == 索引行序。来源为空时**也写出来**（`"s":""`）：
     * 缺键与空值是两种不同的东西，读的那一侧会按"键不存在 = 这份文件不是我们写的"处理。
     */
    fun encodeTexts(rows: List<Entry>): String {
        val sb = StringBuilder()
        for (r in rows) sb.append(encodeTextRow(r.text, r.source)).append('\n')
        return sb.toString()
    }

    /**
     * 把一条正文/来源编成**正文文件里的一行**（不含换行）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么单独立出来（而不是在 [encodeTexts] 里就地 `JSONObject`）
     * ══════════════════════════════════════════════════════════════════════
     * 正文文件的**每一行**都必须是一个 JSON 对象（`{"t":…,"s":…}`）——
     * 这是 [decodeTextRow] 的契约，也是"行号 = 下标"这条口径的另一半。
     * 而"往文件里写**一行**"这件事现在有**两个**入口：
     *   · [encodeTexts]（整份写：建库 / 保存 / 追加）；
     *   · [spliceRows]（按行改：P1-b 的"改字"）。
     * 两处各写一遍 `JSONObject` 就是两个实体 —— 改了一处另一处不会红，
     * 而分家的形态是"按行改的那一行不是 JSON" -> [decodeTextRow] 返回 null ->
     * **整行读不出来**（`readEntriesAligned` 给 null、列表显示"这一行读不出来"），
     * 且不报错。所以编码只有**这一份**。
     */
    fun encodeTextRow(text: String, source: String): String {
        val o = org.json.JSONObject()
        o.put("t", text)
        o.put("s", source)
        return o.toString()
    }

    /**
     * 从正文文件字节里取第 [index] 行的条目（**纯函数**）。
     *
     * 越界 / 那一行坏了都返回 null，**不抛异常**：正文里含任意字符，一条坏掉
     * 不该让其余 99 条全不可用（那会把"一条编错了"放大成"知识库不可用"）。
     * 而**索引**那边的策略相反（整体拒绝）—— 那边的半份会读出越界 float
     * 且维度"对得上"，属于静默错误；这边最坏只是"少注入一条"，属于能力降级。
     */
    fun decodeTextRow(raw: String, index: Int): Entry? {
        if (index < 0) return null
        val lines = raw.split('\n')
        if (index >= lines.size) return null
        val line = lines[index]
        if (line.isEmpty()) return null
        return try {
            val o = org.json.JSONObject(line)
            // `t` 与 `s` 都必须存在：缺一个说明这一行不是我们写的（截断/版本不符），
            // 按"这条不可用"处理，而不是猜一个空值 —— 猜出来的空正文会让注入多一条空行。
            if (!o.has("t") || !o.has("s")) null
            else Entry(o.getString("t"), o.getString("s"))
        } catch (t: Throwable) { null }
    }

    /**
     * 取第 [index] 行的正文 / 来源。越界、文件不在、那一行坏了都返回 null（**不抛异常**）。
     *
     * 为什么整行解析失败就丢那一行、而不是整份拒绝：正文里含任意字符，一条坏掉
     * 不该让其余 99 条全不可用（那会把"一条编错了"放大成"知识库不可用"）。
     * 而**索引**那边的策略相反（整体拒绝）—— 那边的半份会读出越界 float
     * 且维度"对得上"，属于静默错误；这边最坏只是"少注入一条"，属于能力降级。
     */
    /**
     * 取第 [index] 行的条目（读文件 + 上面那个纯函数）。
     *
     * **每行各读一次全文**：注入只取 top-k（默认 3）条，读 3 次全文（每次 100 行以内）
     * 在手机上可忽略；而把它缓存起来会让"重建恰好发生在两次读之间"时的错位
     * （正文是 A 版、索引是 B 版）变得**看不出来** —— 现读至少保证同一份文件。
     */
    private fun row(ctx: Context, name: String, index: Int): Entry? {
        val f = textsFile(ctx, name) ?: return null
        if (!f.isFile) return null
        return try {
            decodeTextRow(f.readText(), index)
        } catch (t: Throwable) {
            Log.w(TAG, "读取正文失败（该条按空处理）: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * 第 [index] 行的整条条目（UI 列表用）。取不到返回 null（**不抛异常**）。
     *
     * 与 [rowText] / [rowSource] 的分工：[row] 是"取一条命中要用的东西"，
     * 服务于注入路径；这里是"把库里的每一行原样列出来"，服务于编辑路径
     * （用户要看到自己刚才加的那一行、要能点掉某一行的删除按钮）。
     * 让 UI 走 [row] 那一族（每次只取一个字段）会让"列表 = 一行一条"这件事
     * 变成"逐字段拼出来的一行"，两边的行号口径一旦分岔就没人能发现。
     */
    fun entryAt(ctx: Context, name: String, index: Int): Entry? = row(ctx, name, index)

    /** 第 [index] 行的正文；取不到返回 null。 */
    fun rowText(ctx: Context, name: String, index: Int): String? =
        row(ctx, name, index)?.text?.takeIf { it.isNotEmpty() }

    /** 第 [index] 行的来源标签；取不到返回 null（调用方按空标签渲染，**不崩**）。 */
    fun rowSource(ctx: Context, name: String, index: Int): String? =
        row(ctx, name, index)?.source

    /** 库里的条目总数（读正文文件行数）。取不到返回 0。 */
    fun textsCount(ctx: Context, name: String): Int =
        readTexts(ctx, name)?.toString(Charsets.UTF_8)?.split('\n')?.count { it.isNotEmpty() } ?: 0

    /**
     * 正文行数的**有界**数法：最多读 [capBytes] 字节就停手 —— 返回 `null` = "这一段里数不完"。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么它必须是"有界"的，而不是又一个 [textsCount]
     * ═══════════════════════════════════════════════════════════════════════
     * [textsCount] 的代价是**整份文件**：读进内存 + `toString` 一份 6 MB 的字符串
     * + `split('\n')` 再建 7000 个字符串对象。100 条短笔记（几十 KB）上那是毫秒级，
     * 而 7000 条（6 MB 中文）是**几百毫秒到秒级** —— 且它与"屏幕上要显示什么"无关。
     *
     * 这个函数存在的唯一理由是：**主线程**上有一个必须保持便宜的取值
     *（状态行的"库里几条"，见 [EngineActivity.renderRagStatus]），而它此前
     * 要么走整份读、要么走一份**可能对不上**的快照表。有界数法把两件事分开：
     *   · 小文件（≤ capBytes）-> 数完，给**精确**数（绝大多数库都在这一档）；
     *   · 大文件 -> 读了 `capBytes` 就停，给 `null`（= "这里数不完"）。
     *
     * ⚠ `null` **不是** 0，也不是"这个库是空的"。调用方必须显式处置这个"数不完"
     *   （本仓库对"两个意思挤在同一个字上"一贯是红的：`null` = 我还没量到，
     *   `0` = 它就是 0 条 —— 在屏幕上它们是同一个字，说出口的却是相反的结论）。
     *
     * ⚠ 数的是**换行**、不是解码后的行：[textsCount] 用 `split('\n')` 数非空行，
     *   而换行字节 `0x0A` 在 UTF-8 里**不可能**出现在多字节字符的中间（UTF-8 的
     *   续字节高位恒为 1），所以按字节数换行与按字符串切行得到的是同一个数。
     *   这一条让"有界"真的成立：不必先把整段解码成字符串（那正是要省掉的那一步）。
     *
     * @param capBytes 单次读取的上限。传 0 或负数时**退化成 [textsCount]**（无界）——
     *   刻意留这个口子给"确定这个库很小"的调用点，但产品路径上不该这么用。
     */
    fun textsCountCapped(ctx: Context, name: String, capBytes: Int): Int? {
        if (capBytes <= 0) return textsCount(ctx, name)
        val f = textsFile(ctx, name) ?: return 0
        if (!f.isFile) return 0
        val size = f.length()
        // 文件比闸门还大 -> **先看大小就停手**，连一次 read 都不做：这正是"有界"的
        // 全部意思（读 `capBytes` 也会把主线程卡在那 `capBytes` 的 IO 上）。
        if (size > capBytes) return null
        return try {
            f.readBytes().count { it == '\n'.code.toByte() }
        } catch (t: Throwable) {
            Log.w(TAG, "读取正文失败: ${t.javaClass.simpleName}")
            null
        }
    }

    /** 一条检索命中：行号 + 余弦分数。 */
    data class SearchHit(val index: Int, val score: Float)

    /**
     * 暴力 top-k：对索引里每一行算余弦（= 点积，因为向量都已归一化），取分数最高的 k 条。
     *
     * 为什么不上向量索引库（FAISS / sqlite-vec）：它们都要原生库，会破本仓库
     * 「Kotlin 侧零第三方运行时依赖」的底线；而 100 条 × 384 维的全量点积在手机上
     * 是微秒级 —— 这个规模根本不需要索引结构（与 embed_text.h 的 top_k 同一判断）。
     *
     * 两条硬约定（与 embed_text.h 的 top_k **逐条对齐**，否则同一份数据在
     * App 内与远端会给出不同答案）：
     *   · `minScore` 以下的一条都不要 —— 没有命中时正确行为是"不注入"，
     *     而不是把最不像的几条硬塞进 prompt（那会用一个无关段落污染回答，且看不出是谁干的）；
     *   · 同分按下标小的优先（稳定排序）—— 让同一次查询在同一份数据上永远返回
     *     同一个顺序，否则真机验收会出现"这次检索 A、下次检索 B"的不可复现结果。
     */
    fun topK(matrix: FloatArray, rows: Int, dim: Int, query: FloatArray,
             k: Int, minScore: Float): List<SearchHit> = rank(matrix, rows, dim, query, k, minScore).hits

    /**
     * 检索的**完整**结果：命中的 top-k + 「就算没命中，最高的那个分是多少」。
     *
     * 为什么单有 [topK] 不够（这是用户拍板要的可观测性，见第 2 步）：
     * `topK` 在阈值处就把低分的丢掉了，于是**「差一点」和「完全不像」在日志上
     * 一模一样** —— 调用方只能印一句「检索 0 条命中」，用户拿它分不清
     * 「再降一点阈值就有了」和「库里根本没有相关内容」。
     * [best] 是**过滤之前**的最高分（一条都没有时是 `NaN`），把它打出来，
     * 这两种情形一眼可分：
     *
     *   没命中（最高分 0.281 < 阈值 0.350）   ← 差一点，可以调阈值
     *   没命中（最高分 0.047 < 阈值 0.350）   ← 完全不像，调阈值也没用
     *
     * [above] 是**过阈值**的条数（不等于 `hits.size`：`hits` 还被 `k` 截断过），
     * 用它区分「一条都没过」与「过了 20 条但只注入 3 条」。
     */
    data class Ranked(val hits: List<SearchHit>, val best: Float, val above: Int)

    fun rank(matrix: FloatArray, rows: Int, dim: Int, query: FloatArray,
             k: Int, minScore: Float): Ranked {
        // NaN 的 `best` = 「一条都没算过」，比 0f 更诚实（0f 会被读成"最高分是 0"）。
        if (dim <= 0 || rows <= 0 || query.size != dim) return Ranked(emptyList(), Float.NaN, 0)
        if (k <= 0) return Ranked(emptyList(), Float.NaN, 0)
        if (matrix.size < rows * dim) return Ranked(emptyList(), Float.NaN, 0)  // 索引被截断（兜底）
        val passed = ArrayList<SearchHit>(rows)
        var best = Float.NaN
        for (r in 0 until rows) {
            var dot = 0.0
            val base = r * dim
            for (i in 0 until dim) dot += matrix[base + i].toDouble() * query[i].toDouble()
            val score = dot.toFloat()
            // NaN 分数不参与「最高分」的比较。
            if (!score.isNaN() && (best.isNaN() || score > best)) best = score
            // ⚠ NaN **必须**在这里显式丢掉，不能只靠下面那句阈值比较 ——
            //    `minScore > 0f` 为假时（= 调用方显式关掉阈值，见 [select] 的口径）
            //    那个比较整段被跳过，NaN 于是进了 `passed`：
            //      · `compareByDescending` 把 NaN 排在**最前**（Kotlin 的 Float
            //        比较里 NaN 大于一切），于是它**占掉一个 top-k 名额**，
            //        把一条真正及格的命中挤出结果 —— 排序全对、只是少了一条，
            //        接口 200、日志干净；
            //      · 下游 `RagInject.render` 按行号注入一条"分数是 NaN"的条目，
            //        而 NaN 的成因（除零 / 非有限值）恰恰说明这一条的向量是坏的。
            //    所以判据是「**只**收有限分数」，与阈值开不开无关。
            if (!score.isFinite()) continue
            if (minScore > 0f && !(score >= minScore)) continue
            passed.add(SearchHit(r, score))
        }
        passed.sortWith(compareByDescending<SearchHit> { it.score }.thenBy { it.index })
        val hits = if (passed.size > k) passed.subList(0, k).toList() else passed
        return Ranked(hits, best, passed.size)
    }

    /**
     * **删索引**：只作废 `index.bin`，**正文与目录原样留着**。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 它以前连正文一起删 —— 那是逻辑错了，不是按钮名错了（ISSUE #259 第 ② 条）
     * ══════════════════════════════════════════════════════════════════════
     * 用户的原话：*"删索引按钮，应该不删正文啊，删索引为啥要删正文，是你按钮写错了
     * 还是逻辑写错了"*。**是逻辑写错了。**
     *
     * 老实现的理由是"留着会出现没有向量但有正文的状态，而 [list] 按 `index.bin`
     * 列库 —— 于是目录从列表里消失、磁盘上还占着正文，没有任何入口能清掉它"。
     * 那个理由的**每一句都已经不成立**：
     *
     *   · [list] 现在的判据是**目录里有正文或索引**（不是 `index.bin`）——
     *     所以"只有正文"的库**照样在列表里**，看得见、点得动；
     *   · 它的状态是一个**正常状态**：`hasIndex=false` = "待重建"，而
     *     状态行本来就会说"正文已改：需重建"；
     *   · 而"清掉它"的入口有两个：「建索引/重建索引」（拿这份正文重算向量）、
     *     「删库」（连目录一起抹掉）。
     *
     * 也就是说，当初那个"孤儿正文"根本不是孤儿 —— 它是**这个库唯一的资产**。
     * 把它连索引一起删掉的真实代价是：用户点"删索引"（他以为删的是可再生的那一半，
     * 向量确实可再生），结果**唯一的正文**没了、且不可撤销。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 两个删除动作的分工（这一轮之后**才是真正有意义的**）
     * ══════════════════════════════════════════════════════════════════════
     * | 动作     | index.bin | texts.jsonl | 目录 | 列表里       | 可撤销 |
     * |----------|-----------|-------------|------|--------------|--------|
     * | [drop]   | **删**    | 留          | 留   | 留着（待重建）| 能（重新建索引）|
     * | [delete] | 删        | 删          | 删   | 消失         | 不能   |
     *
     * 于是两个按钮的差别第一次变得**在屏幕上看得见**：删索引之后那一行还在
     *（状态：待重建），删库之后那一行消失。以前两者看起来一样（正文都丢了），
     * 那正是用户问"这俩按钮有啥区别"的原因。
     *
     * [!] `meta.json` **留着**：它记的是"上次重建时切了多少块"，与索引同生共死
     *     会让排障时少一个对照。正文行数才是"库里有几条"的真值（见 [list]）。
     * [!] 返回值仍是"索引文件删掉了没有"。索引本来就不在（只有正文的库）时返回
     *     false —— 调用方据此说"本来就没有索引"，而不是"已删除"。
     */
    fun drop(ctx: Context, name: String): Boolean {
        val f = indexFile(ctx, name) ?: return false
        return runCatching { f.delete() }.getOrDefault(false)
    }

    /**
     * **删库**：把磁盘上这个库整个抹掉（目录 + 索引 + 正文 + meta），并清掉表里那一行。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 与 [drop] 的分工（这两个动作**不是**同一个东西）
     * ══════════════════════════════════════════════════════════════════════════
     * | 动作     | index.bin | texts.jsonl | 目录 | 列表里       | 可撤销 |
     * |----------|-----------|-------------|------|--------------|--------|
     * | [drop]   | 删        | 留          | 留   | 留着（待重建）| 能     |
     * | [delete] | 删        | 删          | 删   | 消失         | 不能   |
     *
     * 两者的用户意图完全不同：`drop` 是"这份索引不要了"（正文留着，下一步重建
     * 索引就能恢复检索 —— 这也是它**可撤销**的全部内容）；`delete` 是"这个库
     * 我不要了"（正文一起没，不可撤销）。用 `drop` 顶替 `delete` 的后果很具体：
     * 用户以为删干净了，而 `rag/` 下那些目录会变成一行行 **只剩正文** 的库 ——
     * 既搜不出东西、又看不出该拿它怎么办。
     *
     * [!] **只删目录，不删根目录**：`rootDir` 是 [list] 的扫描面，删了它下次
     *     进知识库页会重建一个（`mkdirs`），所以删它既没用又会让自己看起来"成功"。
     * [!] `deleteRecursively` 的返回值**不转发**（Android 上它对已删除/不存在的
     *     路径返回 false，而"本来就不存在"与"删失败了"是两件事）。判据改成
     *     "目录还在不在"这个**事实**：那是用户关心的那个问句。
     * [!] 表里那一行**要一起清**：留着的话，下一个用户想起名叫「笔记」的库会
     *     被唯一性判据拒掉（"已经有另一个库叫这个了"），而列表里一个都没有 ——
     *     一句无法自查的假话。见 [pruneNames]。
     */
    fun delete(ctx: Context, name: String): Boolean {
        val d = dirOf(ctx, name) ?: return false
        if (!d.isDirectory) return false
        runCatching { d.deleteRecursively() }
        // 目录还在 = 没删成（权限 / 占用 / 部分失败）。**如实返回 false**，
        // 让调用方说"没删掉，磁盘上还在" —— 说成"已删除"而其实还在，
        // 用户会在下一次进页面时看到它、然后怀疑自己刚才点了什么。
        if (d.isDirectory) return false
        // 表里那一行：按**目录名**清（表的第一列就是它）。失败不影响删除本身。
        runCatching { pruneNames(ctx, liveDirs(ctx)) }
        return true
    }

    /**
     * **轻读**库列表：只回答"我有哪些库"，每个库读的载荷与库里的字节数**无关**。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 与 [list] 的分工（这不是"又一个 list"，是同一件事的两半）
     * ═══════════════════════════════════════════════════════════════════════
     * [list] 为**每一个库**读完整份 `texts.jsonl` —— 它要数正文行数（条目数）
     * 与正文 hash（`inSync`）。那两个数越大越贵，而它们的代价是 **O(全库字节数)**。
     *
     * 于是"点一个几千条的库 -> 整张列表点不动"这件事的根子就在这里：
     * 每一次选择都重算一遍所有库的正文，而"我有哪些库"这件事**一个字节都没变**。
     * 本函数只读：目录里的文件名/大小/时间（`File.isFile` / `length` / `lastModified`）
     * 与 `index.bin` 的**定长头**（指纹、dim、count）——
     * 全是 O(1) 的元数据，**一行正文都不读**。库从 100 条长到 2500 条，
     * 这里的代价一模一样。
     *
     * ⚠ 因此它给出的 [Info.entries] 是**索引行数**（与索引自洽的那个数），
     *   而 `inSync` 恒为 true —— 两个字段在"只列名字"这一层**都不许被当成真值读**：
     *   正文被改过之后（`保存正文` 那条路不重算向量），索引行数会落后于正文行数，
     *   而这一层的"一致"只是"我没去比"。所以它画出来的列表**只允许显示名字**，
     *   一个数字都不许报（见 `EngineActivity.renderRagList` 的 `withCounts`
     *   那一支：没有数字时就把"几条 · 状态"整段留白，而不是填一个可能过期的数）。
     *
     * [!] 与 [list] 的**同名口径必须逐字一致**：两边都按"目录里有索引**或**正文"
     *     判一个目录是不是库（[isPresent] 那句）。分家了就会出现"骨架里少一个库、
     *     补完画又多出来一个"—— 用户看到的是**一行自己冒出来**。
     */
    fun liveInfos(ctx: Context): List<Info> =
        rootDir(ctx).listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val idx = File(d, FILE)
                val texts = File(d, TEXTS)
                if (!idx.isFile && !texts.isFile) return@mapNotNull null
                val hasIndex = idx.isFile
                // 条目数：**只用索引**（`count` 就在定长头里）。读不出来就是 0 ——
                // 而这一层不报数，所以它只会以"库里几条"之外的形态出现（上限截断那句）。
                val posted = if (hasIndex) {
                    runCatching {
                        val buf = d.resolve(FILE).readBytes()
                        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt(OFF_COUNT)
                    }.getOrDefault(0)
                } else 0
                // dim 与指纹同源（同一个头），所以 `hasIndex` 为真时它一定读得出来。
                val dim = if (hasIndex) {
                    runCatching {
                        val buf = d.resolve(FILE).readBytes()
                        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt(OFF_DIM)
                    }.getOrDefault(0)
                } else 0
                Info(displayName(ctx, d.name), posted, dim,
                    if (hasIndex) idx.lastModified() else 0L, 0,
                    true, hasIndex, false, false, 0)
            }
            ?.sortedBy { it.name } ?: emptyList()

    /** `rag/` 下**真实存在**的目录名集合（[pruneNames] 的输入）。 */
    fun liveDirs(ctx: Context): Set<String> =
        rootDir(ctx).listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet()

    /**
     * 列出全部知识库（扫目录，不加载向量）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 列库的判据是**目录**，不是 `index.bin`
     * ═══════════════════════════════════════════════════════════════════════
     * 上一版按 `index.bin` 存在与否列库。那条判据本身没问题（"有索引才叫库"），
     * 但它与**导入**撞出了一条用户报的死路：
     *
     *   ① 「导入库」到**一个新库名** -> `importEntries` 建目录、写 `texts.jsonl`，
     *      并**有意删掉** `index.bin`（理由见 [importEntries]：宁可让列表变成
     *      "没有索引"，也不能留下"旧向量配新正文"）；
     *   ② 于是这个库**不进列表** —— 磁盘上文件都在、条目数也对，而界面上什么都没有；
     *   ③ 于是点不到它，`loadRagDoc` 也就永远不会被调用，正文框里还是上一份内容；
     *   ④ 而状态行里的「当前库」是在导入结束时**主动设成新库的**，它照旧显示新名字。
     *      **列表里没有这个库，状态行却说当前库是它** —— 两个信号打架，
     *      而用户看到的那个（状态行）说的是"成了"。这正是用户报的：
     *      "库确实建了、内容确实写入了，但页面没变化，这个新建的库不显示"。
     *
     * 所以判据改成**目录里有东西**（`texts.jsonl` 或 `index.bin` 任一）：
     * 一个只有正文的目录是一个**真实存在的库**（它的状态叫"待重建"，见 [Info.hasIndex]），
     * 而不是"应该被藏起来的东西"。藏起来才是那个 bug。
     *
     * [!] 条目数读的是**正文文件的行数**，不是 `meta.json` 里的 `entries`。
     *     阶段 2 起「保存正文」会改正文而**不**重建索引，于是 meta 里的那个数字
     *     会立刻过期 —— 拿它显示就会出现"列表说 12 条、实际 15 条"，
     *     而这两份数据谁也不报错。正文行数才是"库里有几条"的唯一真值，
     *     meta 保留它只为排障时看"那次重建时切了多少块"。
     *     正文文件不存在（阶段 1 的老索引）时回落到 meta，这样老库也显示得出来。
     *
     * [!] **空目录**（连两个文件都没有）仍然不列：用户手工建的一个空目录不是库，
     *     而把它列出来会得到一行"0 条"的幽灵，删也删不掉（它没有「删索引」的对象）。
     */
    fun list(ctx: Context): List<Info> =
        rootDir(ctx).listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                val idx = File(d, FILE)
                val hasIndex = idx.isFile
                val texts = File(d, TEXTS)
                // 目录里有正文或索引，才算一个"库"。空目录跳过（见上）。
                //
                // ⚠⚠ `texts.isFile` 而不是 `texts.length() > 0`，这两个判据差着
                //     ISSUE #259 第 ⑤ 条那个状态（用户报的："建库时退出了 app，
                //     下次进来一个建了一半的库，**按钮全部不可用**，我没有任何操作
                //     手段删库"）：
                //   · 一份**空正文**（导入了一份空文件 / 建库在写正文之前就中断了）
                //     仍然是"这个库存在过"的证据。按长度判就会把这种库
                //     **从列表里藏掉** —— 用户看到的是一片空白，而磁盘上
                //     那些目录还在（他只能去文件管理器里删）。
                //   · 藏掉一个"有目录、没内容"的库，与上面那句"空目录跳过"
                //     是**不同**的一件事：这里连**目录里什么都没有**都算不上 ——
                //     它有一个 `texts.jsonl`，只是那一份是空的。
                if (!hasIndex && !texts.isFile) return@mapNotNull null   // == isPresent(ctx, name)
                val idxRaw = try { if (hasIndex) idx.readBytes() else null } catch (t: Throwable) { null }
                // 指纹读不出来 / 索引不存在 = 没有可用索引。**不再因此把库藏起来** ——
                // 那种藏法正是上面那条死路。信息照样给全，由 UI 去说"待重建"。
                val textsRaw = try {
                    if (texts.isFile) texts.readBytes() else null
                } catch (t: Throwable) { null }
                // 条目数：正文行数优先，其次 meta（老索引没有正文文件）
                val entries = if (textsRaw != null) {
                    textsRaw.toString(Charsets.UTF_8).split('\n').count { it.isNotEmpty() }
                } else {
                    runCatching {
                        Regex("\"entries\"\\s*:\\s*(\\d+)").find(File(d, META).readText())
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }.getOrDefault(0)
                }
                // 维度：只在读得出索引头时才有意义（0 = 没有可用索引）
                val dim = if (idxRaw == null) 0 else runCatching {
                    ByteBuffer.wrap(idxRaw).order(ByteOrder.LITTLE_ENDIAN).getInt(OFF_DIM)
                }.getOrDefault(0)
                val t = runCatching {
                    Regex("\"targetChars\"\\s*:\\s*(\\d+)").find(File(d, META).readText())
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }.getOrDefault(0)
                // inSync 与搜索路径用**同一对判据**（textsInSync），不是另写一套近似。
                // ⚠ 没有索引时它是 **false**（"对不上"），而 UI 必须靠 [hasIndex] 把这个
                //   false 读成"待重建"而不是"正文被改过" —— 两句话的处置不同
                //（一个要点重建，一个是"你改过正文、索引已停用"）。
                // [!] `name` 给的是**显示名**（ISSUE #259）：改名之后列表 / 状态行 /
                //     弹框里出现的都必须是用户给它起的那个名字，而磁盘身份（目录名）
                //     由 [dirOf] 反查 —— 拿目录名去显示，就是"列表里是旧名、
                //     库名框里是新名"，两个信号打架（那一族错的共同形态）。
                // 可续（半份索引）与 inSync 判据**同源**（都用 [resumableCount] /
                // [textsInSync]，不另写一套近似）。idxRaw 为 null（没有索引文件）时
                // 一律不可续 —— 那正是"从来没建过"。
                val resumeN = if (idxRaw != null && textsRaw != null) {
                    resumableCount(idxRaw, textsRaw)
                } else null
                Info(displayName(ctx, d.name), entries, dim,
                    if (hasIndex) idx.lastModified() else 0L, t,
                    hasIndex && textsInSync(idxRaw ?: ByteArray(0), textsRaw ?: ByteArray(0)),
                    hasIndex, false, resumeN != null, resumeN ?: 0)
            }
            ?.sortedBy { it.name } ?: emptyList()
}
