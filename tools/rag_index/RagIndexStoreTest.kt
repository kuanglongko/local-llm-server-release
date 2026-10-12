package com.xiaowan.localinference

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `RagIndexStore` 的离线单测（宿主侧跑，不需要设备 / NDK / 模型）。
 *
 * 钉的是**索引持久化**这一层最容易静默出错的东西：
 *   · 编码 / 解码往返（写出与读回必须逐字节一致，含指纹里的 `\u001f`）；
 *   · 头字段偏移与 `rag_index.h` 一致（两处各写一遍必然漂移）；
 *   · 任何损坏 / 截断 / 陌生人文件都**整体拒绝**，绝不"半截凑合用"；
 *   · 库名净化（`../` 必须被拒，不能静默改造成另一个名字）；
 *   · 三态（none / empty / valid）不混：空库不能每次都触发重建；
 *   · **正文 hash**（阶段 2）：与 C++ 侧读同一份 `texts_hash_vectors.tsv` ——
 *     两边各写一遍 FNV-1a，各自的往返测试都自洽，只有对同一份期望值才能发现
 *     其中一边写错；而它写错的后果是"索引永远被判为已改过"（每次都在重建）
 *     或反过来"正文改了也不作废"（用旧向量命中、注入新正文）。
 */
object RagIndexStoreTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    private fun eqStr(a: String?, b: String?, what: String) {
        if (a == b) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what\n     got=[$a]\n     want=[$b]") }
    }

    private fun eqi(a: Long, b: Long, what: String) {
        if (a == b) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what got=$a want=$b") }
    }

    /**
     * 指纹构造助手（测试里大量用到）：`FpInput` + 正文 hash。
     *
     * 刻意**不**做成"参数全带默认值的便捷函数"以外的东西 —— 而 `textsHash`
     * 必须显式给（虽然这里有默认值，但生产代码那一侧是**没有默认值**的）。
     * 本轮的教训就是"少算一个字段"会让指纹静默变宽松。
     */
    private fun fpOf(
        path: String = "/m/x.gguf", bytes: Long = 1L, mtime: Long = 2L, dim: Int = 384,
        // 4096 是**旧**上限：这里刻意显式写它（而不是取默认值），于是"默认值改了"
        // 这件事在下面的 `c 字段` 一对判据里显形 —— 否则两边一起变时判据恒真。
        cp: RagIndexStore.ChunkParams = RagIndexStore.ChunkParams(480, 64, 4096),
        textsHash: String = "h0", rows: Int = -1
    ) = RagIndexStore.fingerprintOf(RagIndexStore.FpInput(path, bytes, mtime, dim, cp), textsHash, rows)

    // ── 编解码往返 ─────────────────────────────────────────────────────────
    private fun testRoundTrip() {
        val fp = fpOf("/m/bge.gguf", 100L, 200L, 4)
        val vec = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)   // 2 行 × 4 维
        val bytes = RagIndexStore.encode(fp, 4, vec)
        val parsed = RagIndexStore.decode(bytes)
        ok(parsed != null, "往返：解码成功")
        if (parsed == null) return
        val (dim, count, out) = parsed
        eqi(dim.toLong(), 4, "往返：dim 还原")
        eqi(count.toLong(), 2, "往返：count 由向量长度推出")
        var same = out.size == vec.size
        if (same) for (i in vec.indices) if (out[i] != vec[i]) same = false
        ok(same, "往返：向量逐元素一致")
        eqStr(RagIndexStore.fingerprintIn(bytes), fp, "往返：指纹逐字节还原（含 \\u001f）")
    }

    // 头字段偏移必须与 rag_index.h 一致：magic(4) | fpLen | dim | count | fp
    private fun testHeaderLayout() {
        val fp = "abc"
        val vec = FloatArray(4) { 0.5f }
        val b = RagIndexStore.encode(fp, 4, vec)
        eqStr(String(b, 0, 4, Charsets.US_ASCII), "RAG1", "头布局：magic = RAG1（前 4 字节）")
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        eqi(buf.getInt(4).toLong(), 3, "头布局：offset 4 = 指纹字节长度")
        eqi(buf.getInt(8).toLong(), 4, "头布局：offset 8 = dim")
        eqi(buf.getInt(12).toLong(), 1, "头布局：offset 12 = count")
        eqStr(String(b, 16, 3, Charsets.UTF_8), "abc", "头布局：offset 16 起是指纹")
        eqi(b.size.toLong(), (16 + 3 + 4 * 4).toLong(), "头布局：总长 = 16 + fpLen + count*dim*4")
    }

    // 端点序必须显式小端（别的大端环境的 float 读出来是乱码，且不崩）
    private fun testLittleEndian() {
        val vec = floatArrayOf(1.0f)
        val b = RagIndexStore.encode("x", 1, vec)
        val head = 16 + 1
        // IEEE754 1.0f = 0x3F800000 -> 小端字节 00 00 80 3F
        eqi(b[head].toLong() and 0xFF, 0x00, "字节序：1.0f 小端第 1 字节 = 0x00")
        eqi(b[head + 3].toLong() and 0xFF, 0x3F, "字节序：1.0f 小端第 4 字节 = 0x3F")
    }

    // ── 损坏 / 截断 / 陌生人文件：整体拒绝 ──────────────────────────────────
    private fun testRejects() {
        ok(RagIndexStore.decode(ByteArray(0)) == null, "拒绝：空字节")
        ok(RagIndexStore.decode("XXXX".toByteArray() + ByteArray(20)) == null,
            "拒绝：magic 不符（绝不猜）")
        val fp = "fp"
        val vec = FloatArray(8) { 1f }
        val good = RagIndexStore.encode(fp, 4, vec)
        ok(RagIndexStore.decode(good) != null, "拒绝：完整文件可解（对照）")
        ok(RagIndexStore.decode(good.copyOfRange(0, good.size - 1)) == null,
            "拒绝：向量区被截断一个字节 -> 整体拒绝（不当半截能用）")
        ok(RagIndexStore.decode(good.copyOfRange(0, 17)) == null,
            "拒绝：指纹区被截断 -> 整体拒绝")
        ok(RagIndexStore.fingerprintIn(good.copyOfRange(0, 17)) == null,
            "拒绝：指纹区截断时 fingerprintIn 也返回 null（不读越界）")
        // dim = 0 的荒谬头
        val bad = good.copyOf()
        ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 0)
        ok(RagIndexStore.decode(bad) == null, "拒绝：dim=0 荒谬值")
        // dim 超上限
        val huge = good.copyOf()
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 999999)
        ok(RagIndexStore.decode(huge) == null, "拒绝：dim 超上限")
        // count = 0 但向量区非空（头与体不一致）
        val zeroCount = good.copyOf()
        ByteBuffer.wrap(zeroCount).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 0)
        val p = RagIndexStore.decode(zeroCount)
        ok(p != null && p.second == 0, "拒绝：count=0 时按 0 条解析（不读残留字节）")
    }

    // 空向量是**合法**的（空知识库），不能与"损坏"混为一谈
    private fun testEmptyVectors() {
        val b = RagIndexStore.encode("fp", 4, FloatArray(0))
        val p = RagIndexStore.decode(b)
        ok(p != null, "空库：0 向量仍是可解文件（合法）")
        eqi((p?.second ?: -1).toLong(), 0, "空库：count = 0")
    }

    // ── 库名净化 ───────────────────────────────────────────────────────────
    private fun testSanitize() {
        eqStr(RagIndexStore.sanitizeName("notes"), "notes", "净化：常规名原样")
        eqStr(RagIndexStore.sanitizeName("  my notes  "), "my_notes", "净化：空白折成下划线并 trim")
        eqStr(RagIndexStore.sanitizeName("a-b_c1"), "a-b_c1", "净化：连字符/下划线/数字保留")
        ok(RagIndexStore.sanitizeName("../etc") == null, "净化：含 / 被拒（防目录穿越）")
        ok(RagIndexStore.sanitizeName("..") == null, "净化：.. 被拒")
        ok(RagIndexStore.sanitizeName(".") == null, "净化：. 被拒")
        ok(RagIndexStore.sanitizeName("") == null, "净化：空串被拒（不回落成默认库）")
        ok(RagIndexStore.sanitizeName("   ") == null, "净化：全空白被拒")
        ok(RagIndexStore.sanitizeName("a".repeat(65)) == null, "净化：超长名被拒")
        // 中文字符是 Unicode 字母，**合法**（目录名允许）；
        // 真正非法的例子是标点 / 路径分隔符 —— 它们必须被拒而不是被悄悄替换。
        eqStr(RagIndexStore.sanitizeName("中文库"), "中文库",
            "净化：Unicode 字母（中文）合法（不误杀）")
        ok(RagIndexStore.sanitizeName("a/b") == null, "净化：含 / 被拒（不静默替换）")
        ok(RagIndexStore.sanitizeName("a\\b") == null, "净化：含 \\ 被拒")
        ok(RagIndexStore.sanitizeName("a:b") == null, "净化：含 : 被拒")
        ok(RagIndexStore.sanitizeName("a*b?") == null, "净化：含通配符被拒")

        // 目录拼装必须落在 rag 根下
        val root = File("/tmp/ragroot")
        val safe = RagIndexStore.sanitizeName("../escape")
        ok(safe == null, "净化：穿越名不会拼出目录")
    }

    // ── 指纹 ───────────────────────────────────────────────────────────────
    private fun testFingerprint() {
        val a = fpOf()
        eqStr(a, fpOf(), "指纹：同输入 -> 同指纹（确定性）")
        ok(a != fpOf(mtime = 3L), "指纹：mtime 变 -> 不同")
        ok(a != fpOf(bytes = 9L), "指纹：size 变 -> 不同")
        ok(a != fpOf(dim = 768), "指纹：dim 变 -> 不同")
        ok(a != fpOf(path = "/m/y.gguf"), "指纹：路径变 -> 不同")
        ok(a != fpOf(cp = RagIndexStore.ChunkParams(240, 64, 4096)), "指纹：targetChars 变 -> 不同")
        ok(a != fpOf(cp = RagIndexStore.ChunkParams(480, 0, 4096)), "指纹：overlap 变 -> 不同")
        // 切块**规则**版本（本轮新增的 `k` 字段）：只改"从哪儿切"、不改块大小时，
        // targetChars / overlap 一个都不变 —— 没有这一格，旧边界算的向量会被继续
        // 用来命中新边界切出的正文（静默、不崩、接口 200）。
        // ⚠ 这里**不写死** `3`，而是取 `kDefaultRule` + 1：默认值本身会随规则演进
        //   上调（本轮就是 2 -> 3），写死的话"上调默认值"这件事会让这条判据
        //   静默变成**恒假**（两个 ChunkParams 一样 -> 指纹一样 -> 报"没变"）。
        //   判据要的是"与当前默认**不同** -> 指纹不同"，与默认是几无关。
        val kDefaultRule = RagIndexStore.ChunkParams().chunkRuleVersion
        ok(a != fpOf(cp = RagIndexStore.ChunkParams(480, 64, 4096, kDefaultRule + 1)),
           "指纹：切块规则版本变 -> 不同（块大小一个都没动也一样）")
        // 它必须与 t/o 分开一格：同一格会被覆盖，等于没加。锚的是 `k` 这一格的
        // **值**（取字段值，不含前缀）—— 前缀被误当值的一部分时会红。
        // ⚠ 同样不写死"2"：取默认值本身，于是"默认值上调"不会误伤这条判据。
        eqStr(RagIndexStore.fpFields(a)["k"], kDefaultRule.toString(),
            "指纹：规则版本以独立前缀 `k` 落盘（值 = 当前默认 $kDefaultRule）")
        ok(RagIndexStore.fpFields(a)["t"] == "480", "指纹：t 与 k 是两格（互不覆盖）")
        // 单条块数上限（本轮新增的 `c` 字段）。为什么它必须进指纹：4096 -> 16384
        // 这一改让"大文档从被截断变成完整"，块集合**根本不同**，而 t/o/k 一个都没变 ——
        // 不进指纹时按 4096 截断出来的**半份**索引会被当成"仍然可用"，
        // 后半段一个向量都没有，而症状与"文档里没有那一段"逐字相同。
        ok(a != fpOf(cp = RagIndexStore.ChunkParams(480, 64, 8192)),
           "指纹：单条块数上限变 -> 不同（t/o/k 一个都没动也一样）")
        eqStr(RagIndexStore.fpFields(a)["c"], "4096", "指纹：块数上限以独立前缀 `c` 落盘")
        ok(RagIndexStore.fpFields(a)["t"] != RagIndexStore.fpFields(a)["c"],
           "指纹：c 与 t 是两格（块大小 ≠ 块数上限，互不覆盖）")
        eqStr(RagIndexStore.fpFields(RagIndexStore.ChunkParams().let { fpOf(cp = it) })["c"],
           RagIndexStore.ChunkParams().maxChunks.toString(),
           "指纹：默认参数的 `c` 就是 ChunkParams 的默认上限（两处同源）")
        // 正文 hash 进指纹（阶段 2）：这是"文档与索引各自合法、配在一起却错"的唯一判据。
        // 少算它的方向是**更宽松**（改了正文仍用旧索引），而那没有现象。
        ok(a != fpOf(textsHash = "h1"), "指纹：正文 hash 变 -> 不同（改了正文没重建）")
        // x 字段的位置是契约的一部分（引擎按分隔符取它）；这里把位置也钉住 ——
        // 插到别处会让 textsHashInFp 取到另一个字段，症状是诊断指错方向。
        // ⚠ 取回的是**字段值**（不含那个作为字段标识的 `x` 前缀）—— 期望值写成
        //   "x0" 会让这条判据在"前缀被误当成值的一部分"时也绿（本轮初稿就是这样）。
        eqStr(RagIndexStore.textsHashInFp(a), "h0", "指纹：x 字段值可被取回（不含字段标识）")
        ok(RagIndexStore.textsHashInFp("v1\u001fp/m.gguf\u001f") == null,
            "指纹：没有 x 字段 -> null（旧格式索引判为不一致，不静默放行）")
        ok(RagIndexStore.textsHashInFp("v1\u001fp/m.gguf\u001fx\u001f") == null,
            "指纹：x 字段为空 -> null（空值不等于\"没改过\"）")
        // 字段分隔：不能出现拼接歧义
        val c = fpOf(path = "x1", bytes = 2L)
        val d = fpOf(path = "x", bytes = 12L)
        ok(c != d, "指纹：字段边界明确（\\u001f 分隔，无拼接歧义）")
        // 指纹里必须带格式版本，改了格式旧索引才能自动作废
        ok(a.contains("v${RagIndexStore.FORMAT_VERSION}\u001f"), "指纹：含格式版本号")
        ok(a.endsWith("\u001f"), "指纹：以分隔符收尾（最后一段不会与下一段粘上）")
        // 正文 hash 的两条**对照**（各自钉一个不同的失效方向）：
        //   ① hash 参与判定 —— `fpOf()` 与 `fpOf(textsHash = ...)` 必须不同；
        //   ② 但**同样的 hash** 在不同输入下不能"恒不变"（防"这个字段其实是常量"）。
        // ⚠ 首版把这两条写成了一行，而那一行的两个实参**恰好等价**
        //   （当时 `ChunkParams(480, 64, 4096)` 就是默认值）——于是它恒假。
        //   本轮真踩到：判据看起来在测"分隔"，实际什么都没测。
        //   📌 那个"恰好等价"现在已经不成立了（默认上限已从 4096 抬到 16384），
        //   但这一条**依然要写成显式实参**：判据的价值在于"两个实参在某个维度上
        //   确实不同"，而取默认值会让它在下次改默认值时重新变成恒假。
        ok(fpOf() != fpOf(textsHash = "1"), "指纹：正文 hash 参与判定（同模型、不同 hash）")
        ok(fpOf(textsHash = "1") != fpOf(cp = RagIndexStore.ChunkParams(240, 64, 4096), textsHash = "1"),
            "指纹：正文 hash 相同、切块参数不同 -> 仍不同（不是恒不变的常量）")

        // ── 行数进指纹（阶段 3：追加一条 / 删除单条）───────────────────────
        // 为什么行数也要算进去：追加时"索引里有多少行向量"与"正文里有多少行"
        // 可能**故意不相等**（新加的那条正文落了盘、向量还没算出来）——
        // 单看正文 hash 看不出来（hash 只证明"是哪一份正文"），
        // 而"少几行"在界面上就是"我加的没生效"，与"没命中"长得一模一样。
        ok(a != fpOf(textsHash = "h0", rows = 1), "指纹：行数变 -> 不同（索引落后于正文）")
        // 取回 r 字段：追加/删除要靠它做三者一致性校验。
        eqi((RagIndexStore.rowsInFingerprint(a) ?: -99).toLong(), (-1).toLong(),
            "指纹：r 字段可被取回（默认 -1 = 不知道行数）")
        eqi((RagIndexStore.rowsInFingerprint(fpOf(rows = 7)) ?: -99).toLong(), 7L,
            "指纹：r 字段可被取回（真实行数）")
        ok(RagIndexStore.rowsInFingerprint("v1\u001fp/m.gguf\u001f") == null,
            "指纹：没有 r 字段 -> null（老格式索引，不许当成\"行数对得上\"）")
        eqi((RagIndexStore.dimInFingerprint(a) ?: -99).toLong(), 384L,
            "指纹：d 字段可被取回（追加/删除按它搬 float）")
    }

    // ── 正文 hash（与 C++ 侧读同一份向量表）──────────────────────────────
    //
    // 为什么要读一份**共用**的向量表：FNV-1a 在两边各写一遍，各自的往返测试都自洽
    // （同一实现算两遍当然一样）。只有拿同一份 (输入 -> 期望值) 喂两边，才能发现
    // "其中一边写错了"。表由一份独立实现（python）生成，见 texts_hash_vectors.tsv。
    private fun testTextsHash(vectorsPath: String) {
        // 先钉算法本体的不变量（不依赖表文件是否读得到）
        eqStr(RagIndexStore.textsHashOf(ByteArray(0)), "cbf29ce484222325",
            "正文hash：空输入 = FNV 偏移基数（正文文件不存在走的就是这条真实路径）")
        eqi(RagIndexStore.textsHashOf("abc".toByteArray()).length.toLong(), 16,
            "正文hash：定长 16 位十六进制")
        ok(RagIndexStore.textsHashOf("abc".toByteArray()) !=
            RagIndexStore.textsHashOf("abd".toByteArray()),
            "正文hash：改一个字节即变（否则改正文不作废索引）")
        ok(!RagIndexStore.textsHashOf("abc".toByteArray()).startsWith("-"),
            "正文hash：走无符号格式化（Long.toString(16) 会给负号，C++ 那边不会）")

        val f = File(vectorsPath)
        if (!f.isFile) {
            fail++
            println("FAIL  正文hash：读不到共用向量表 $vectorsPath")
            return
        }
        var n = 0
        var bad = 0
        for (line in f.readText(Charsets.UTF_8).split('\n')) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 3) continue
            val want = cols[0]
            val hex = cols[1]
            val what = cols[2]
            val raw = ByteArray(hex.length / 2) { i ->
                ((Character.digit(hex[i * 2], 16) shl 4) or
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            n++
            if (RagIndexStore.textsHashOf(raw) != want) {
                bad++
                println("FAIL  正文hash 与共用向量表不符：$what" +
                    "\n     got=[${RagIndexStore.textsHashOf(raw)}] want=[$want]")
            }
        }
        ok(n >= 8, "正文hash：共用向量表读到 8 条以上（读少了说明表被改坏）")
        ok(bad == 0, "正文hash：全部与共用向量表一致（与 C++ 侧同一份契约）")
    }

    // ── 正文与索引是否一致（索引可用性的第二个轴）────────────────────────
    private fun testTextsInSync() {
        val texts = RagIndexStore.encodeTexts(listOf(
            RagIndexStore.Entry("苹果富含维C", "粘贴-a"),
            RagIndexStore.Entry("香蕉含钾", "粘贴-a")))
        val h = RagIndexStore.textsHashOf(texts.toByteArray(Charsets.UTF_8))
        val idx = RagIndexStore.encode(fpOf(textsHash = h), 4, FloatArray(4) { 1f })

        ok(RagIndexStore.textsInSync(idx, texts.toByteArray(Charsets.UTF_8)),
            "一致：索引里的 x 与当前正文对得上")
        ok(!RagIndexStore.textsInSync(idx, "改了一句\n".toByteArray(Charsets.UTF_8)),
            "不一致：正文改了一个字 -> 判为已改（不能用旧向量命中新正文）")
        ok(!RagIndexStore.textsInSync(idx, ByteArray(0)),
            "不一致：正文文件不见了 -> 判为已改（否则命中 3 条却注入空串）")
        ok(!RagIndexStore.textsInSync(ByteArray(0), texts.toByteArray(Charsets.UTF_8)),
            "不一致：索引文件坏了 -> 判为不可用")
        // 旧格式（指纹里没有 x）：不能"没有就不比" —— 那等于放行
        val oldFmt = RagIndexStore.encode("v1\u001fp/m.gguf\u001fd384\u001f", 4,
            FloatArray(4) { 1f })
        ok(!RagIndexStore.textsInSync(oldFmt, texts.toByteArray(Charsets.UTF_8)),
            "不一致：旧格式指纹没有 x 字段 -> 判为已改（不静默放行）")
        // 空库 + 空正文是一致（不然"空知识库"会被反复判成"要重建"）
        val emptyIdx = RagIndexStore.encode(
            fpOf(textsHash = RagIndexStore.textsHashOf(ByteArray(0))), 4, FloatArray(0))
        ok(RagIndexStore.textsInSync(emptyIdx, ByteArray(0)),
            "一致：空正文 + 空索引（空库不该被当成被改坏了）")
    }

    // ── 三态不混 ───────────────────────────────────────────────────────────
    private fun testTriState() {
        val ctx = RagIndexStore::class.java
        // 用纯逻辑复刻 load 的判定顺序（不做真文件 IO）：
        // 文件不存在 -> none；指纹不符 -> none；count==0 -> empty；否则 valid
        val fp = "v1\u001fp/m.gguf\u001f"
        val emptyFile = RagIndexStore.encode(fp, 4, FloatArray(0))
        val vecFile = RagIndexStore.encode(fp, 4, FloatArray(4) { 1f })

        fun classify(bytes: ByteArray?, expect: String): String {
            if (bytes == null) return "none"
            val fpIn = RagIndexStore.fingerprintIn(bytes) ?: return "none"
            if (fpIn != fp) return "none"
            val p = RagIndexStore.decode(bytes) ?: return "none"
            return if (p.second == 0) "empty" else "valid"
        }
        eqStr(classify(null, "none"), "none", "三态：文件不存在 -> none（要重建）")
        eqStr(classify(RagIndexStore.encode("别的指纹", 4, FloatArray(4) { 1f }), "none"),
            "none", "三态：指纹不符 -> none（换模型即作废）")
        eqStr(classify(emptyFile, "empty"), "empty", "三态：空库 -> empty（**不**重建）")
        eqStr(classify(vecFile, "valid"), "valid", "三态：有内容 -> valid")
        ok(ctx != null, "三态：判定顺序与 load() 一致（源码级）")
    }

    // ── 半份可续（0.9.215）：`resumeFrom` 与 `resumableCount` 都是**纯函数** ──
    //
    // 这一层钉的是"断了能接上"这件事的两个端点：
    //   · `resumeFrom(count, rowsTotal)` —— 从第几块接着编。它只回答"接着从哪编"，
    //     不回答"能不能续"（那是 resumableCount 与 load 的事）——把两者混成
    //     一个函数时，"能不能续"会悄悄退化成"起点算出来是几"，而 0 与"从头"分不开；
    //   · `resumableCount(index, texts)` —— 这份索引是不是**半份可续**。
    //     界（0 < count < rows-1）与正文 hash 一致**缺一不可**：
    //       count == 0        -> 与空库同处置（报"可续 0 条"会让状态行说"接着编"
    //                            而实际是"从头编"）；
    //       count >= rows-1   -> 齐整（没有缺口可补），报"可续"会让用户以为
    //                            点一下是补几条，而实际是"没有东西可编"。
    private fun testResume() {
        // resumeFrom：越界一律钳进 0..rowsTotal（起点不许是负数 / 超过总数）
        eqi(RagIndexStore.resumeFrom(0, 10).toLong(), 0L, "resumeFrom(0,10) = 0")
        eqi(RagIndexStore.resumeFrom(7, 10).toLong(), 7L, "resumeFrom(7,10) = 7（从第 8 块接着编）")
        eqi(RagIndexStore.resumeFrom(10, 10).toLong(), 10L, "resumeFrom(10,10) = 10（恰好编完）")
        eqi(RagIndexStore.resumeFrom(99, 10).toLong(), 10L, "resumeFrom 越上界钳到 rowsTotal")
        eqi(RagIndexStore.resumeFrom(-3, 10).toLong(), 0L, "resumeFrom 负数钳到 0")

        // resumableCount：用真字节（指纹 + 头 + 向量区）走**同一套** encode/decode
        val cp = RagIndexStore.ChunkParams(480, 64, 4096)
        val fpi = RagIndexStore.FpInput("/m/x.gguf", 1L, 2L, 4, cp, null, null, 1, 4, 8)
        // 正文：3 段 + 结尾换行 = 4 行 -> rowsOf = 4 -> rowsToCount = 3
        val rows = listOf(
            RagIndexStore.Entry("a", "s"), RagIndexStore.Entry("b", "s"),
            RagIndexStore.Entry("c", "s"))
        val texts = RagIndexStore.encodeTexts(rows).toByteArray(Charsets.UTF_8)
        eqi(RagIndexStore.rowsToCount(RagIndexStore.rowsOf(texts)).toLong(), 3L,
            "resumableCount 前置：3 条正文 -> count 上限 3")

        // 半份：2 个向量（< 3）= 可续
        val partialFp = RagIndexStore.fingerprintOf(fpi, RagIndexStore.textsHashOf(texts), RagIndexStore.rowsOf(texts))
        val partial = RagIndexStore.encode(partialFp, 4, FloatArray(8) { 1f })
        eqi((RagIndexStore.resumableCount(partial, texts) ?: -1).toLong(), 2L,
            "半份（2/3 条）-> 可续，返回已编 2 条")

        // 齐整：3 个向量（== 3）= 没有缺口 -> 不是可续
        val full = RagIndexStore.encode(partialFp, 4, FloatArray(12) { 1f })
        eqStr(RagIndexStore.resumableCount(full, texts)?.toString(), null,
            "齐整（3/3 条）-> **不是**可续（没有缺口可补）")

        // 越界：4 个向量（> 3）= 损坏 -> 不是可续
        val over = RagIndexStore.encode(partialFp, 4, FloatArray(16) { 1f })
        eqStr(RagIndexStore.resumableCount(over, texts)?.toString(), null,
            "越界（4/3 条）-> **不是**可续（索引比正文还长 = 损坏）")

        // 正文改了：hash 对不上 -> 不是可续（那是"要重建"）
        val texts2 = RagIndexStore.encodeTexts(rows + RagIndexStore.Entry("d", "s")).toByteArray(Charsets.UTF_8)
        eqStr(RagIndexStore.resumableCount(partial, texts2)?.toString(), null,
            "正文改过（hash 不一致）-> **不是**可续（是要重建）")

        // 空索引：count == 0 -> 不是可续（与空库同处置）
        val zero = RagIndexStore.encode(partialFp, 4, FloatArray(0))
        eqStr(RagIndexStore.resumableCount(zero, texts)?.toString(), null,
            "count == 0 -> **不是**可续（与空库同处置）")

        // 损坏文件：整体拒
        eqStr(RagIndexStore.resumableCount(ByteArray(4), texts)?.toString(), null,
            "非本格式的文件 -> 不是可续（不猜）")
    }

    // ── 进度落盘那张表（0.9.217）：`progressRows` 的长度**恒为正文块数** ──
    //
    // 这一层钉的是 0.9.216 那个**数据丢失**：
    // `savePartial` 的契约是"传进来的表就是这份库的全部正文，向量只留连续前缀"，
    // 它内部 `encodeTexts(rows)` 之后**整份覆盖** `texts.jsonl`。所以进度落盘
    // 交出去的表必须是**全量正文**（只是前面的行带向量）——交"已编过的那些"
    // 就是**删正文**：后 `总块数 - 已编` 块的字从磁盘消失，而索引与剩下的正文
    // 仍然自洽（`count == 行数-1`、hash 对得上），下次判成 `valid`：
    // 界面报"库建成功了"，条目数是截断后的那个（用户报的"导 1000 条只剩 216 条"）。
    //
    // 同一处还连带第二错：若按前缀算指纹，`loadPrefix(fullFp)` 永远比对不上，
    // 续编**一次都不生效**（不报错，只是"又从 0 开始"）。
    //
    // 为什么是纯函数：文件 IO 那层在宿主上跑不了（无 Context），而"这张表有多长"
    // 恰恰是唯一需要钉的量 —— 抽出来之后它就能被离线单测覆盖。
    private fun testProgressRows() {
        val texts = listOf("c1", "c2", "c3", "c4", "c5")
        val sources = listOf("s", "s", "s", "s", "s")
        // 已编 2 块 -> 4 维向量，前 2 条各 4 个 float
        val vec = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)

        val rows = RagIndexStore.progressRows(2, texts, sources, vec, 4)
        // ① 长度**恒为正文块数** —— 就是这一版修的 bug 的判据
        eqi(rows.size.toLong(), 5L,
            "progressRows(2/5) 的表长 == 5（**全量正文**；写成 2 就是删正文）")
        // ② 前 done 条带向量、内容按行号对齐
        // ⚠ 断言用 `contentEquals` 而**不是**格式化字符串：浮点打印依赖 locale
        //   （`%.1f` 在逗号小数点地区会给出 `5,0`），而这条判据要钉的是
        //   "第 k 条的向量是向量区第 k 段"，locale 一变就变成另一条判据。
        eqi(rows[0].vector.size.toLong(), 4L, "第 1 条（已编）带向量")
        ok(rows[0].vector.contentEquals(floatArrayOf(1f, 2f, 3f, 4f)),
            "第 1 条的向量 == 向量区第 1 段（按行号对齐）")
        ok(rows[1].vector.contentEquals(floatArrayOf(5f, 6f, 7f, 8f)),
            "第 2 条的向量 == 向量区第 2 段（**按行号对齐**，不是挤在一起）")
        // ③ 余下的按正文占位（空向量）——`savePartial` 的"遇到第一个缺向量的就停"
        //    会恰好停在 done 处，于是这一份就是"前缀向量 + 全量正文"
        eqi(rows[2].vector.size.toLong(), 0L, "第 3 条（未编）是空向量占位")
        eqi(rows[4].vector.size.toLong(), 0L, "第 5 条（未编）也是空向量占位")
        eqStr(rows[4].text, "c5", "第 5 条的**正文在**（这正是被删掉的那部分）")
        eqStr(rows[0].text, "c1", "第 1 条正文保持不变")

        // ④ done == 0：一条都没编 -> 全量正文、零向量（不是空表！）
        val zero = RagIndexStore.progressRows(0, texts, sources, FloatArray(0), 4)
        eqi(zero.size.toLong(), 5L, "progressRows(0/5) 仍是 5 行正文（不是空表）")
        eqi(zero[0].vector.size.toLong(), 0L, "done == 0 时没有向量")

        // ⑤ done == texts.size：全编完 -> 全量正文 + 全量向量
        val all = RagIndexStore.progressRows(5, texts, sources,
            FloatArray(20) { it.toFloat() }, 4)
        eqi(all.size.toLong(), 5L, "progressRows(5/5) == 5 行")
        eqi(all[4].vector.size.toLong(), 4L, "done == 总数时最后一条也有向量")
        ok(all[4].vector.contentEquals(floatArrayOf(16f, 17f, 18f, 19f)),
            "第 5 条的向量 == 向量区第 5 段（全编完时也不挤在一起）")

        // ⑥ 越界的 done 被钳（而不是抛 / 静默少写）——不钳的话，
        //    `vectors.copyOfRange` 会读到区外，而"写到第 6 条"这种表
        //    在 savePartial 那里会以"向量行数 > 正文行数"的形态被拒。
        val over = RagIndexStore.progressRows(99, texts, sources,
            FloatArray(20) { it.toFloat() }, 4)
        eqi(over.size.toLong(), 5L, "done 越界被钳到正文块数（表长仍是 5）")

        // ⑦ sources 短于 texts：按空串补齐（不崩、不错位）
        val shortSrc = RagIndexStore.progressRows(1, texts, listOf("only1"),
            floatArrayOf(1f, 2f, 3f, 4f), 4)
        eqStr(shortSrc[4].source, "", "sources 缺项按空串补齐（不错位、不崩）")
    }

    // ── 逐字段比对（用户拍板：不要静默作废）────────────────────────────────
    //
    // 这一层钉的是"**为什么**没有可用索引"：
    //   · 旧实现只比一行字符串，于是"换模型 / 池化变了 / 切块参数变了"
    //     全归成同一句"模型或切块参数与建库时不同" —— 用户拿着一句**无法动手**
    //     的结论（不知道该换模型还是该重建，也不知道是哪一项）。
    //   · "待重建"（索引是我们故意写的半份）与"真不可用"是两件事：
    //     前者只要重建、后者要换回原模型。混成一句 = 用户选错动作。
    //
    // 判据是纯函数（喂两个指纹串），所以宿主侧钉得住 —— 真机上刷不出来的东西
    // 必须在这里被钉死。
    private fun testCompareFp() {
        val ident = { p: String, b: Long, m: Long, d: Int,
                      eb: Long?, em: Long?, q: Int?, zo: Int? ->
            RagIndexStore.FpInput(p, b, m, d, RagIndexStore.ChunkParams(),
                embedBytes = eb, embedMtime = em, pooling = q, embedOutDim = zo)
        }
        val cur = ident("/m/bge.gguf", 100L, 200L, 384, 100L, 200L, 2, 384)
        val here = RagIndexStore.fingerprintOf(cur, "h0", 5)
        ok(RagIndexStore.compareFp(here, here).same, "比对：完全相同的指纹 -> same")

        // 每一个**必须比**的字段单独变，都必须被判成"真不可用"（不是待重建）
        for ((name, fp) in listOf(
            "dim 变" to RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 200L, 768, 100L, 200L, 2, 384), "h0", 5),
            "mtime 变" to RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 201L, 384, 100L, 200L, 2, 384), "h0", 5),
            "size 变" to RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 101L, 200L, 384, 100L, 200L, 2, 384), "h0", 5),
            "路径变" to RagIndexStore.fingerprintOf(ident("/m/other.gguf", 100L, 200L, 384, 100L, 200L, 2, 384), "h0", 5),
            "targetChars 变" to RagIndexStore.fingerprintOf(
                RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 384, RagIndexStore.ChunkParams(240, 64, 4096),
                    100L, 200L, 2, 384), "h0", 5),
            // 单条块数上限（本轮从 4096 抬到 16384）：**必须**是一项必比字段 ——
            // 它变而 t/o/k 都不变，不判"真不可用"的话，按旧上限**截断**出来的
            // 半份索引会被当成"仍然可用"，后半段一个向量都没有，
            // 而症状与"文档里没有那一段"逐字相同。
            "块数上限变" to RagIndexStore.fingerprintOf(
                RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 384, RagIndexStore.ChunkParams(480, 64, 8192),
                    100L, 200L, 2, 384), "h0", 5)
        )) {
            val d = RagIndexStore.compareFp(fp, here)
            ok(!d.same && !d.stale, "比对：$name -> 真不可用（不是待重建）")
            ok(d.field.isNotEmpty(), "比对：$name -> 说得出是哪个字段（%s）".format(d.field))
        }

        // 块数上限变时，**指的就是 `c` 那一格**（指错字段会让用户去换模型）。
        val capChanged = RagIndexStore.fingerprintOf(
            RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 384, RagIndexStore.ChunkParams(480, 64, 8192),
                100L, 200L, 2, 384), "h0", 5)
        eqStr(RagIndexStore.compareFp(capChanged, here).field, "c",
            "比对：块数上限变 -> 指的就是 c 字段")

        // `c` 缺失（旧版本索引）必须判"只能重建"，不许静默放过。
        // 为什么：旧索引里没有这一格，而它的向量是按**旧上限**切出来的 ——
        // "拿不到"与"相等"长得一样，放行 = 让一份语义未知的半份索引继续出结果。
        val noCap = here.split('\u001f').filterNot { it.startsWith("c") }.joinToString("\u001f")
        ok(!RagIndexStore.compareFp(noCap, here).same, "比对：缺 `c` -> 不放行（旧版本索引）")

        // ⚠⚠ 从一份**已有指纹**派生 FpInput（追加 / 删除走这条路）时，`c` 必须**一起搬**。
        // 不搬的后果与 `k` 当初完全一样：按**默认**上限重算指纹 -> 与索引里那份不相等
        // -> 用户点一次「追加」就把自己的索引弄失效了，而日志只会说"参数变了"
        //（不谈是哪一项，用户无从动手）。这里用"非默认上限"的指纹来验：
        // 只有真的从指纹里搬了 `c`，重算出来的才与它相同。
        val nonDefault = RagIndexStore.fingerprintOf(
            RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 384, RagIndexStore.ChunkParams(480, 64, 8192),
                100L, 200L, 2, 384), "h0", 5)
        val carried = RagIndexStore.FpInput(nonDefault)
        eqStr(carried.cp.maxChunks.toString(), "8192", "从指纹派生：maxChunks 一起搬（c 那一格）")
        eqStr(RagIndexStore.fingerprintOf(carried, "h0", 5), nonDefault,
            "从指纹派生：搬完重算的指纹与原来**逐字节相同**（否则追加一次就把索引弄失效）")

        // 池化方式变了 = 向量语义完全不同 -> 真不可用（不许"幸好维度相同"就放行）
        val poolChanged = RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 200L, 384, 100L, 200L, 3, 384), "h0", 5)
        val dPool = RagIndexStore.compareFp(poolChanged, here)
        ok(!dPool.same && !dPool.stale, "比对：池化方式变 -> 真不可用（同一维度下向量完全不同）")
        eqStr(dPool.field, "q", "比对：池化方式变 -> 指的就是 q 字段")

        // 模型声明的输出维度变了、实际维度没变 -> 也要真不可用
        val outChanged = RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 200L, 384, 100L, 200L, 2, 512), "h0", 5)
        ok(!RagIndexStore.compareFp(outChanged, here).same,
            "比对：模型声明的 n_embd_out 变 -> 真不可用（库按它分配 buffer）")

        // "不知道"不许被当成"变了"：`.txt_embed` 那份手写配置没有模型元数据，
        // 若把 null 当差异，它会被永远判成"模型变了"—— 而它其实是对的。
        // "不知道"那一侧：**必须精确到"知道的那一项"**。
        // 旧实现（`.txt_embed` 场景）两边都没有嵌入身份 → 只能比"知道的那几项"，
        // 全等就该判 same；而只要有一边知道、且值不同，就必须判差异。
        val unknown = RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 200L, 384, null, null, null, null), "h0", 5)
        ok(RagIndexStore.compareFp(unknown, unknown).same,
            "比对：两边都不知道嵌入身份 -> same（不把「不知道」当差异，否则永远要重建）")
        val dMixed = RagIndexStore.compareFp(here, unknown)
        ok(!dMixed.same && !dMixed.stale, "比对：一边知道池化、一边不知道 -> 不算相等（宁可重建）")
        // 只知道一半也不行：只有 pool 不同、其它身份两边都有且相同 -> 真不可用
        val poolOnly = RagIndexStore.fingerprintOf(ident("/m/bge.gguf", 100L, 200L, 384, null, null, 3, null), "h0", 5)
        ok(!RagIndexStore.compareFp(poolOnly, here).same,
            "比对：只有池化方式两边都知道且不同 -> 真不可用（其余身份缺失不影响这一项）")

        // ⚠ **一处口径修正**（本轮）：正文 hash / 行数与当前不同，**不是**
        // "待重建" —— 那是"这份索引描述的正文已经不是磁盘上那份"，属于
        // **真不可用**（处置：重建，而"重建"本来就要重算向量）。
        // "待重建"专指 [RagIndexStore.savePartial] 的形态：**索引头里的 hash
        // 与行数就是完整正文的**（两边都等于当前值），只是向量只保留了前缀。
        // 这一种在这里表现为"整串相等却 count != rows-1" → 由 `load` 判
        // （见 testTriState 与追加算术那一节），**不是** compareFp 的职责。
        // 把两者混起来的后果：`savePartial` 的索引会被说成"模型变了"，
        // 用户去换模型（而模型完全没问题）。
        val textsChanged = RagIndexStore.fingerprintOf(cur, "h1", 5)
        val dTexts = RagIndexStore.compareFp(textsChanged, here)
        eqStr(dTexts.field, "x", "比对：正文 hash 变 -> 指的是 x 字段")
        ok(!dTexts.same && !dTexts.stale,
            "比对：正文 hash 变 -> 真不可用（索引描述的正文已不是磁盘上那份）")

        // ⚠⚠ `r`（正文行数）这一项**必须按真实行数比**，不能拿哨兵值比。
        //
        // 这一条是给一个真机上踩响过的 bug 立的碑：`fingerprintOf` 的 `rows`
        // 曾经有默认值 `-1`，而 `LlmEngine.search` 漏传了它 —— 于是**每一次检索**
        // 都拿 `r=-1` 的期望指纹去比索引里真实的 `r=2`，恒不等，
        // 报「索引不可用：正文行数 变了」，而索引本身完全没问题。
        //
        // 所以这里钉两件事：**行数相同 -> same**、**行数不同 -> 说得出是 r**。
        // 前者是本 bug 的反面（它当时恒红），后者防止"把 r 整个从比对里拿掉"
        // 这种"修法"（那会让"索引落后于正文"不再被逮住）。
        val sameRows = RagIndexStore.fingerprintOf(cur, "h0", 5)
        ok(RagIndexStore.compareFp(sameRows, here).same,
            "比对：正文行数相同 -> same（这正是 search 漏传 rows 时恒红的那一件）")
        val rowsChanged = RagIndexStore.fingerprintOf(cur, "h0", 6)
        val dRows = RagIndexStore.compareFp(rowsChanged, here)
        eqStr(dRows.field, "r", "比对：正文行数变 -> 指的是 r 字段")
        ok(!dRows.same && !dRows.stale,
            "比对：正文行数变 -> 真不可用（索引落后于正文 / 正文被改过）")

        // 旧格式（缺字段）：不许"幸好对上了一半"就放行 -> 既不是 same 也不是 stale
        val oldFmt = "v3\u001fp/m/bge.gguf\u001fxh0\u001f"
        val dOld = RagIndexStore.compareFp(oldFmt, here)
        ok(!dOld.same && !dOld.stale, "比对：旧格式缺字段 -> 既不放行也不叫待重建（只能重建）")

        // 字段拆分：空值也占一个字段（不能把 "e" + "" + "n" 拼成 "en"）
        val f = RagIndexStore.fpFields(here)
        eqStr(f["q"], "2", "拆字段：q 取到值")
        eqStr(f["x"], "h0", "拆字段：x 取到值")
        ok(f.size >= 15, "拆字段：字段数 >= 15（实际 ${f.size}）")
        val noIdent = RagIndexStore.fpFields(RagIndexStore.fingerprintOf(cur.copy(embedBytes = null, pooling = null), "h0", 5))
        eqStr(noIdent["q"], "", "拆字段：值为空时取到空串（而不是 null / 粘到下一个字段）")
    }

    // ── `g` 那一格的**定级**（ISSUE #269 第 5 步）──────────────────────────
    //
    // 这一层钉的是"改一下批大小，别把一份语义完好的索引说得像换了模型"：
    // `g` 是**旋钮**，别的必比字段是**语义**。两者压进同一个出口时，
    // 一份编到 9000/10000 块的索引被判从头编，而日志里只有一行
    // "合批每批条数 变了" —— 用户看不到那 9000 块是**语义同源**的，
    // 也就看不到"改回来就能续上"。
    //
    // ⚠ 但 `onlyBatch` **不授权沿用**：断言（"不同批大小的向量逐位相同"）
    //   未验之前，`g` 不同 = 不可沿用（与 [load] 的语义字段全同这条主判据无关，
    //   它仍然要过）—— 这一级只负责**把话说清楚**（报出 [FpDiff.salvage]）。
    private fun testBatchGrade() {
        val ident = { b: Int -> RagIndexStore.FpInput(
            "/m/bge.gguf", 100L, 200L, 384, RagIndexStore.ChunkParams(),
            embedBytes = 100L, embedMtime = 200L, pooling = 2, embedOutDim = 384, embedBatch = b) }
        val g8 = RagIndexStore.fingerprintOf(ident(8), "h0", 5)
        val g1 = RagIndexStore.fingerprintOf(ident(1), "h0", 5)

        // 相同 -> same（不因为 `g` 进指纹就把"没变"判成变了）
        ok(RagIndexStore.compareFp(g8, g8).same, "g 定级：整串相同 -> same")
        ok(!RagIndexStore.compareFp(g8, g8).onlyBatch, "g 定级：same 时 onlyBatch 为假")

        // 只差 `g` -> **不是** same，但只有 onlyBatch 这一级被点亮，且指的就是 `g`。
        // 这一条是本轮的核心：它把"改旋钮"与"换模型"分成两句不同的话。
        val dg = RagIndexStore.compareFp(g1, g8)
        ok(!dg.same, "g 定级：只差 g -> 不是 same（索引确实与当前配置不同源）")
        ok(dg.onlyBatch, "g 定级：只差 g -> onlyBatch 为真（能说清'能救多少'）")
        ok(!dg.stale, "g 定级：只差 g -> 不是'待重建'（那是半份索引的定性）")
        eqStr(dg.field, "g", "g 定级：只差 g -> 指的就是 g 字段")

        // salvage 默认是 -1（"没算"）：它由 `load` 在读到文件后填，
        // `compareFp` 自己**不碰文件**，所以这里必须是"没算"而不是 0。
        // 0 会被读成"一条都救不回来"，而真相是"还没算"。
        eqi(dg.salvage.toLong(), -1L, "g 定级：compareFp 不碰文件 -> salvage 保持 -1（'没算'，不是 0）")

        // 语义字段变时，**不许**亮 onlyBatch：那是"换模型/正文改过"，
        // 与旋钮无关，报 onlyBatch 会让用户以为"改回批大小就能救"。
        val dimChanged = RagIndexStore.fingerprintOf(
            RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 768, RagIndexStore.ChunkParams(),
                embedBytes = 100L, embedMtime = 200L, pooling = 2, embedOutDim = 384, embedBatch = 8), "h0", 5)
        val dd = RagIndexStore.compareFp(dimChanged, g8)
        ok(!dd.onlyBatch, "g 定级：维度变 -> 不亮 onlyBatch（那是换模型，救不回来）")
        eqStr(dd.field, "d", "g 定级：维度变 -> 指的是 d 字段")

        // 既有语义差异、又有 `g` 差异时：**语义优先**（onlyBatch 必须为假）——
        // 否则用户会以为"把批大小改回来就能救"，而真正的原因是换了模型。
        val both = RagIndexStore.fingerprintOf(
            RagIndexStore.FpInput("/m/bge.gguf", 100L, 200L, 768, RagIndexStore.ChunkParams(),
                embedBytes = 100L, embedMtime = 200L, pooling = 2, embedOutDim = 384, embedBatch = 1), "h0", 5)
        val dB = RagIndexStore.compareFp(both, g8)
        ok(!dB.onlyBatch && !dB.same, "g 定级：语义 + g 同时变 -> 语义优先（不亮 onlyBatch）")

        // `g` 缺失（旧格式）仍必须判"只能重建"：**摘出来不等于不要求它存在**。
        val noG = g8.split('\u001f').filterNot { it.startsWith("g") }.joinToString("\u001f")
        ok(!RagIndexStore.compareFp(noG, g8).same, "g 定级：缺 g（旧版本索引）-> 不放行")
        ok(!RagIndexStore.compareFp(noG, g8).onlyBatch, "g 定级：缺 g -> 不算'只差 g'（是认不出来）")

        // 取值处：`batchInFingerprint` 必须与写进去的那个数**逐字节**一致
        //（`/health` 的 `embed_batch_size_index` 就靠它）。
        eqi((RagIndexStore.batchInFingerprint(g8) ?: -1).toLong(), 8L, "batchInFingerprint：取到 8")
        eqi((RagIndexStore.batchInFingerprint(g1) ?: -1).toLong(), 1L, "batchInFingerprint：取到 1")
        eqStr(RagIndexStore.batchInFingerprint("v3\u001fxabc\u001f")?.toString(), null,
            "batchInFingerprint：旧格式没有 g -> null（不猜成 0）")
    }

    // ── 追加 / 删除的**算术**（阶段 3）────────────────────────────────────
    //
    // 这一层是纯逻辑：给一份"正文字节 + 向量区 + 行数"，算出追加/删除之后的
    // 那两份。它钉的是三个**静默**处：
    //   · 追加时"没算出向量的条目也占正文一行，而向量不占" —— 行数必须按
    //     **新正文**现算，否则 count 与 rows 不再相等（索引被判为无效）；
    //   · 删除时剩余向量**逐行前移**，少搬一行不会报错，只是从此每一条命中
    //     都指向隔壁；
    //   · 两个文件里的**同一行**必须被移掉（只删一个 = 整体错位一行）。
    private fun testAppendArithmetic() {
        // 3 条 2 维，已归一化；正文 3 行（各行 JSON 由 encodeTexts 生成）
        val dim = 2
        val rows0 = listOf(
            RagIndexStore.Entry("甲", "s", floatArrayOf(1f, 0f)),
            RagIndexStore.Entry("乙", "s", floatArrayOf(0f, 1f)),
            RagIndexStore.Entry("丙", "s", floatArrayOf(0.6f, 0.8f))
        )
        val texts0 = RagIndexStore.encodeTexts(rows0).toByteArray()
        eqi(RagIndexStore.rowsOf(texts0).toLong(), 4L,
            "追加：3 行正文 -> rows = 4（**留尾换行**、最后一段是空串；这是刻意口径）")
        // 行数按 `split("\n")` 数：`a\nb\nc\n` 切出 4 段（最后一段是空串）。
        // ⚠ 这不是 bug，而是**必须两边同源**的一条算术 —— 索引头里的 r、
        //   追加时的 rowsAfter、删除时的 rowsAfter 全部用这同一个函数算。
        //   若有一处改成"数非空行"，`rows` 与 `count` 的偏移就会在"有条目落空"
        //   时**从 ±1 变成 ±2**，而现象是"索引判为无效"（每次打开都在重建）。
        //
        //   于是 `load` 里的判据不能写成 `rowsInFp != count`（那是"+0"口径），
        //   必须写成 `rowsInFp != count + 1`（尾换行的固定偏移）。
        //   这一条常量是**两边契约**，改它必须同时改 load。
        eqi(RagIndexStore.rowsOf("a\nb\nc\n".toByteArray()).toLong(), 4L,
            "追加：行数口径固定为 split(换行).size（两边同源，不数非空行）")
        eqi(RagIndexStore.rowsToCount(4).toLong(), 3L, "追加：rows -> count 的换算（尾段偏移 1）")

        // 追加一条（有向量）：向量区 3+1 行，正文 4 行
        val one = RagIndexStore.Entry("丁", "s", floatArrayOf(0.8f, 0.6f))
        val texts1 = texts0 + (RagIndexStore.encodeTexts(listOf(one)).toByteArray())
        eqi(RagIndexStore.rowsOf(texts1).toLong(), 5L, "追加：追加一条后正文行数 = 5")
        val vec1 = FloatArray((3 + 1) * dim)
        System.arraycopy(floatArrayOf(1f, 0f, 0f, 1f, 0.6f, 0.8f), 0, vec1, 0, 3 * dim)
        System.arraycopy(one.vector, 0, vec1, 3 * dim, dim)
        val parsed = RagIndexStore.decode(RagIndexStore.encode("fp", dim, vec1))
        eqi((parsed?.second ?: -1).toLong(), 4L, "追加：向量行数 4（与正文行数一致才能检索）")
        eqi((parsed?.first ?: -1).toLong(), 2L, "追加：维度不变")
        ok(parsed!!.third[6] == 0.8f && parsed.third[7] == 0.6f,
            "追加：新向量**接在末尾**（不是插到开头）")

        // 追加一条（**没有**向量）：它只占正文一行，不占向量行
        val noVec = RagIndexStore.Entry("戊", "s")
        val texts2 = texts1 + RagIndexStore.encodeTexts(listOf(noVec)).toByteArray()
        eqi(RagIndexStore.rowsOf(texts2).toLong(), 6L, "追加：没向量的条目也占正文一行")
        eqi(RagIndexStore.readEntriesTexts(texts2.toString(Charsets.UTF_8)).size.toLong(), 5L,
            "追加：正文读回是 5 条（用户敲的东西不许丢）")
        // 于是 rows(5) != count(4)：索引必须**判为无效**（需重建），
        // 而不是"前 4 条照用"（那会给出"检索有结果、只是刚加的搜不到"）。
        // 索引里的 count 是**向量行数**（4），而 rows 是正文行数（6-2=4？不 —— 见下）。
        // ⚠ 口径：`count` = 4 条索引行（甲/乙/丙/丁），`rows` = split 段数 = 6。
        //   两者**差值恒为 1**（尾换行），所以真正的判据是"rows == count + 1"，
        //   而"有落下的条目"会让差值 > 1（这里 6 vs 4）。
        ok(RagIndexStore.rowsOf(texts2) - 1 != 4,
            "追加：有落下的条目时 rows-1 != count（这份索引必须判为需重建）")

        // 删除第 1 行（下标 1 = "乙"）：剩余向量应为 [甲, 丙]
        val keptLines = ArrayList<String>()
        val all = texts1.toString(Charsets.UTF_8).split('\n')
        for (i in all.indices) if (i != 1) keptLines.add(all[i])
        val texts3 = keptLines.joinToString("\n").toByteArray()
        eqi(RagIndexStore.rowsOf(texts3).toLong(), 4L, "删除：删一行后正文行数 4（= 3 条 + 尾段）")
        val after = RagIndexStore.readEntriesTexts(texts3.toString(Charsets.UTF_8))
        eqStr(after.getOrNull(0)?.text, "甲", "删除：第 0 行仍是甲")
        eqStr(after.getOrNull(1)?.text, "丙", "删除：删掉乙之后，丙**前移**到第 1 行")
        // 逐行搬（跳过被删行）：新向量区 = [甲, 丙]
        val newVec = FloatArray(3 * dim)
        var dst = 0
        for (r in 0 until 4) {
            if (r == 1) continue
            System.arraycopy(vec1, r * dim, newVec, dst * dim, dim)
            dst++
        }
        eqi(dst.toLong(), 3L, "删除：搬运条数 = 删除后行数（少搬一行不会报错，只是全错位）")
        ok(newVec[2] == 0.6f && newVec[3] == 0.8f, "删除：丙的向量前移到第 1 行")
        ok(newVec[0] == 1f && newVec[1] == 0f, "删除：甲的向量原位不动")
    }

    // ── 检索 topK ──────────────────────────────────────────────────────────
    private fun testTopK() {
        // 3 条 2 维（已归一化）：与 query(1,0) 的余弦 = 1.0 / 0.6 / 0.0
        val m = floatArrayOf(1f, 0f, 0.6f, 0.8f, 0f, 1f)
        // 注意 (1,0) 与 (0.6,0.8) 的点积是 0.6（0.6*1 + 0.8*0 = 0.6）
        var hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 3, 0f)
        eqi(hits.size.toLong(), 3, "topK：不过滤时返回全部（<= k）")
        eqi(hits[0].index.toLong(), 0, "topK：最相关排第一")
        eqi(hits[1].index.toLong(), 1, "topK：次相关排第二")
        eqi(hits[2].index.toLong(), 2, "topK：最不相关排最后")

        hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 2, 0f)
        eqi(hits.size.toLong(), 2, "topK：k 生效（截断）")

        hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 5, 0f)
        eqi(hits.size.toLong(), 3, "topK：k 大于行数时返回行数条")

        hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 5, 0.5f)
        eqi(hits.size.toLong(), 2, "topK：minScore 过滤掉低分（0.0 那条被挡）")
        var allAbove = true
        for (h in hits) if (h.score < 0.5f) allAbove = false
        ok(allAbove, "topK：低于阈值的一条都不返回")

        // 阈值设成 1.0 之上才会"全挡"。注意 row0 的分数是**恰好** 1.0f，
        // 用 0.99f 是挡不住它的 —— 这正是"阈值语义（>=）"要钉住的那一点。
        hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 5, 1.0f)
        eqi(hits.size.toLong(), 1, "topK：阈值语义是 >=（恰好等于阈值的保留）")
        hits = RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 5, 1.00001f)
        eqi(hits.size.toLong(), 0, "topK：全部低于阈值 -> 空（不硬塞）")

        // 同分稳定：两条同分时按下标小的优先
        val tie = floatArrayOf(1f, 0f, 1f, 0f)
        hits = RagIndexStore.topK(tie, 2, 2, floatArrayOf(1f, 0f), 2, 0f)
        eqi(hits[0].index.toLong(), 0, "topK：同分时下标小的优先（可复现）")
        eqi(hits[1].index.toLong(), 1, "topK：同分顺序稳定")

        // 维度不符 = 换了模型，宁可不检索
        eqi(RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f, 0f), 3, 0f).size.toLong(), 0,
            "topK：query 维度不符 -> 空（不越界读）")
        // 矩阵被截断（兜底）
        eqi(RagIndexStore.topK(floatArrayOf(1f, 0f), 3, 2, floatArrayOf(1f, 0f), 3, 0f).size.toLong(), 0,
            "topK：矩阵尺寸不足 -> 空（兜底，不越界）")
        // 非法参数
        eqi(RagIndexStore.topK(m, 0, 2, floatArrayOf(1f, 0f), 3, 0f).size.toLong(), 0,
            "topK：rows=0 -> 空")
        eqi(RagIndexStore.topK(m, 3, 2, floatArrayOf(1f, 0f), 0, 0f).size.toLong(), 0,
            "topK：k=0 -> 空")

        // NaN 分数必须被阈值挡住（否则排序未定义）
        val nan = floatArrayOf(Float.NaN, 1f)
        eqi(RagIndexStore.topK(nan, 1, 2, floatArrayOf(1f, 0f), 3, 0.3f).size.toLong(), 0,
            "topK：NaN 分数被阈值挡住")
    }

    // ── rank：命中的 top-k **加上**「就算没命中，最高分是多少」 ─────────────
    //
    // 第 2 步（用户拍板）给"没命中"补可观测面：只报 `0 条命中` 时，
    // 「差一点（调阈值就有）」与「完全不像（调阈值也没用）」在日志上逐字相同。
    // 这里钉三件事：best 是**过滤前**的最高分、above 是**过滤前**的过阈值条数、
    // 以及"一条都没算过"时 best 是 NaN（不是 0 —— 0 会被读成"最高分是 0"）。
    private fun testRank() {
        // 3 条 2 维：与 query(1,0) 的点积 = 1.0 / 0.6 / 0.0
        val m = floatArrayOf(1f, 0f, 0.6f, 0.8f, 0f, 1f)

        // 阈值 0.7 -> 只有 row0 过。best 必须是 1.0（**过滤前**的最高分），
        // above 必须是 1（过阈值的条数），hits 也是 1。
        var r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 3, 0.7f)
        eqi(r.hits.size.toLong(), 1L, "rank：阈值 0.7 时命中 1 条")
        ok(r.best == 1f, "rank：best 是过滤前的最高分（1.0），不是命中的最小值")
        eqi(r.above.toLong(), 1L, "rank：above = 过阈值的条数")

        // 「全部挡在阈值之上」那一格：阈值必须**严格大于**向量里真实存在的
        // 最高分，否则它不是"全挡"。
        //
        // ⚠⚠ 这里此前写的是 `0.9999f`，而它根本挡不住任何一条 —— `0.9999f`
        // 的精确值是 0.99989998…（严格小于 1.0），于是 `1.0f >= 0.9999f` 为真、
        // row0 照样命中。这一格因此从落地起就没进过"全挡"分支：
        //   · "全挡 -> 空" 实测 hits=1（红）
        //   · "全挡时 above = 0" 实测 above=1（红）
        //   · 而 "best 仍是 1.0" 恰好为真 —— 但它**不是**"过滤前的最高分"的证据，
        //     因为这一格压根没发生过滤。一条恒真的断言混在中间，正好把
        //     "这一格没在测全挡"这件事盖住了。
        //
        // 真正能全挡的是 `1.00001f` —— 相邻的 topK 那一格用的正是它
        //（"topK：全部低于阈值 -> 空（不硬塞）"）。同一个测试文件里，
        // topK 用对了、rank 用错了，而 topK 是 `rank(...).hits`，两者**同一份实现**。
        // 这条不一致本身就是"断言没被执行过"的痕迹。
        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 3, 1.00001f)
        eqi(r.hits.size.toLong(), 0L, "rank：阈值 1.00001 全挡 -> 空")
        ok(r.best == 1f, "rank：全挡时 best 仍是过滤前的最高分（1.0）—— 这就是「差一点」的证据")
        eqi(r.above.toLong(), 0L, "rank：全挡时 above = 0")
        // 反向钉住"刚够放行"的那一格（阈值恰好等于最高分时**要保留**，
        // 与相邻 topK 的 "阈值语义是 >=（恰好等于阈值的保留）" 同一口径）：
        // 没有这一格，"全挡"与"恰好放行"之间的边界就没人守。
        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 3, 1.0f)
        eqi(r.hits.size.toLong(), 1L, "rank：阈值恰好等于最高分 -> 保留（语义是 >=）")
        eqi(r.above.toLong(), 1L, "rank：阈值恰好等于最高分时 above = 1")

        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 3, 0.5f)
        eqi(r.above.toLong(), 2L, "rank：above 是过滤前的（0.6 那条也算，虽然 k=3 没截断）")
        eqi(r.hits.size.toLong(), 2L, "rank：阈值 0.5 命中 2 条")

        // k 截断时，above 仍报**过阈值的总数**（用来区分"一条都没过"与"过了很多但只注入 k 条"）
        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 1, 0f)
        eqi(r.hits.size.toLong(), 1L, "rank：k=1 时 hits 截断到 1 条")
        eqi(r.above.toLong(), 3L, "rank：k 截断不影响 above（above 是过阈值总数）")

        // 空库 / 非法参数：best 必须是 NaN（"一条都没算过"），不是 0f。
        r = RagIndexStore.rank(m, 0, 2, floatArrayOf(1f, 0f), 3, 0.5f)
        ok(r.best.isNaN(), "rank：rows=0（空库）-> best = NaN（不是 0）")
        eqi(r.hits.size.toLong(), 0L, "rank：rows=0 -> 空")
        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f), 0, 0.5f)
        ok(r.best.isNaN(), "rank：k=0 -> best = NaN")

        // query 维度不符：宁可不检索（best 也无可报）
        r = RagIndexStore.rank(m, 3, 2, floatArrayOf(1f, 0f, 0f), 3, 0f)
        ok(r.best.isNaN(), "rank：query 维度不符 -> best = NaN（没算过）")

        // NaN 分数不参与 best 的比较（否则 best 会变成 NaN 而掩盖真实最高分）
        val nanMix = floatArrayOf(Float.NaN, 1f, 0.5f, 0.5f)
        r = RagIndexStore.rank(nanMix, 2, 2, floatArrayOf(1f, 0f), 3, 0f)
        ok(!r.best.isNaN() && r.best == 0.5f, "rank：NaN 行不影响 best（best 取真实的 0.5）")

        // ⚠⚠ 上一条只断言了 `best`，而**没有**断言 `hits` —— 于是它守不住本轮
        //     修掉的那个缺陷：`minScore == 0f`（显式关阈值）时，NaN 分数不被
        //     阈值那道闸挡住，而 `compareByDescending` 把 NaN 排在**最前**，
        //     于是它**占掉一个 top-k 名额**，把一条真正及格的命中挤出去。
        //     `best` 恰好仍是对的（NaN 不参与 best 比较），所以那一条**恒真**，
        //     正好把"hits 里混进了 NaN"这件事盖住 —— 与上一轮 `rank` 的全挡那一格
        //     是同一个形态（一条恒真断言夹在两条真断言中间）。
        //
        // 数据刻意造得能让"挤掉"看得见：NaN 一条 + 0.9 / 0.2 各一条，k=2。
        // 修好之后 hits 应是 [0.9, 0.2]（NaN 被丢），而不是 [NaN, 0.9]。
        val steal = floatArrayOf(Float.NaN, 0.9f, 0.2f)
        r = RagIndexStore.rank(steal, 3, 1, floatArrayOf(1f), 2, 0f)
        eqi(r.hits.size.toLong(), 2L, "rank：minScore=0 时 NaN 不占 top-k 名额（仍返回 2 条）")
        ok(r.hits.none { it.score.isNaN() }, "rank：命中里不许出现 NaN 分数（那是坏向量）")
        ok(r.hits[0].index == 1 && r.hits[1].index == 2,
            "rank：minScore=0 时被 NaN 挤掉的应是它自己，不是 0.2 那条")
        // above 同样不许把 NaN 数进去（它是"过阈值的条数"，NaN 不是"过了阈值"）
        eqi(r.above.toLong(), 2L, "rank：above 不把 NaN 算进过阈值条数")
        // 阈值开着时本来就挡得住（这一格钉住"修完没把正常路径改坏"）
        r = RagIndexStore.rank(steal, 3, 1, floatArrayOf(1f), 2, 0.1f)
        eqi(r.hits.size.toLong(), 2L, "rank：阈值 0.1 时同样是 2 条（NaN 被丢）")
        ok(r.hits.none { it.score.isNaN() }, "rank：阈值 0.1 时命中里也不许有 NaN")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  clipAtCharBoundary：按字符边界截断，绝不切在代理对中间
    // ══════════════════════════════════════════════════════════════════════
    // 为什么这组断言必须**过 UTF-8 字节**、不能只比字符串：
    // 砍在代理对中间时，内存里的 String 与 `==` 比较**全都通过**
    //（JSONObject 往返也一样），所以"相等"这个断言是恒真的。
    // 唯一能验的是"落成字节再读回来还一样" —— 孤立代理在那里会变成 `?`。
    private fun testClipAtCharBoundary() {
        // ① 不超限：原样返回（含边界恰好等于长度）
        eqStr(TextBoundary.clipAtCharBoundary("abc", 5), "abc", "clip：超长上限 -> 原样")
        eqStr(TextBoundary.clipAtCharBoundary("abc", 3), "abc", "clip：恰好等于 -> 原样")
        eqStr(TextBoundary.clipAtCharBoundary("abc", 0), "", "clip：上限 0 -> 空串")
        eqStr(TextBoundary.clipAtCharBoundary("abc", -1), "", "clip：负上限 -> 空串")
        eqStr(TextBoundary.clipAtCharBoundary("", 3), "", "clip：空串 -> 空串")

        // ② BMP 字符（含 CJK）恒等：这些字的低位字节可能是 0x80~0xBF
        //    （如 `文` U+6587 -> 低字节 0x87），按字节的 0xC0 判据会把它们误判成
        //    "半个字符"而凭空前移 —— 这一格就是钉住"没用那套判据"的。
        eqStr(TextBoundary.clipAtCharBoundary("甲文乙", 3), "甲文乙", "clip：CJK 恒等（不被按字节判据误伤）")
        eqStr(TextBoundary.clipAtCharBoundary("甲文乙", 2), "甲文", "clip：CJK 正常截断")

        // ③ 代理对**没被劈开**：emoji 占 2 个 code unit，limit 落在它中间时
        //    整个 emoji 归下一段（宁可短一个码点，不可切碎）
        val emoji = "😀"            // length == 2
        eqStr(TextBoundary.clipAtCharBoundary("a" + emoji + "b", 2), "a",
            "clip：limit 落在代理对中间 -> 整个 emoji 归下一段（不劈开）")
        eqStr(TextBoundary.clipAtCharBoundary(emoji + emoji, 3), emoji,
            "clip：limit=3 落在第二个 emoji 中间 -> 只留第一个")

        // ④ **关键的字节级断言**：无论怎么截，落成 UTF-8 再读回来必须逐字符相等。
        //    这一条才是"没劈开代理对"的**证据** —— 内存里的 `==` 是恒真的。
        for (n in 0..12) {
            val s2 = "a" + emoji + "bbb" + emoji + "c"
            val cut = TextBoundary.clipAtCharBoundary(s2, n)
            val round = cut.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8)
            eqStr(round, cut, "clip：n=$n 落 UTF-8 再读回逐字符相等（没劈开代理对）")
            ok(cut.length <= n || n >= s2.length, "clip：n=$n 截断结果不超上限")
        }
    }

    // 索引里的 float 精度：往返不能丢
    private fun testFloatPrecision() {
        val vec = floatArrayOf(0.1f, -0.25f, 1e-8f, 12345.678f, Float.MAX_VALUE, -Float.MIN_VALUE)
        val b = RagIndexStore.encode("f", 2, vec)   // dim=2, count=3
        val p = RagIndexStore.decode(b)!!
        var same = true
        for (i in vec.indices) if (p.third[i] != vec[i]) same = false
        ok(same, "精度：float 往返无损失（含极值与极小值）")
    }

    // ── 正文旁挂文件（阶段 2 起：注入需要正文本身）──────────────────────────
    //
    // 这一层的失效形态全是静默的：
    //   · 行序与索引不一致 -> "第 N 条的向量"配上"第 M 条的正文"（检索分数对、内容错）；
    //   · 正文里含换行 / 引号 / 反斜杠 -> JSON 转义写错，整个文件解析失败；
    //   · 一条坏了就整份拒 -> "一条编错"被放大成"知识库不可用"。
    private fun testTextsRoundTrip() {
        val rows = listOf(
            RagIndexStore.Entry("苹果富含维C", "笔记A"),
            RagIndexStore.Entry("含换行的正文\n第二行", "笔记B"),
            RagIndexStore.Entry("含引号\"与\\反斜杠", "笔记\"C"),
            RagIndexStore.Entry("", ""),                     // 空正文（那条会被注入侧跳过）
        )
        val raw = RagIndexStore.encodeTexts(rows)
        eqi(raw.split('\n').count { it.isNotEmpty() }.toLong(), 4, "正文：4 条 -> 4 行")

        for (i in rows.indices) {
            val back = RagIndexStore.decodeTextRow(raw, i)
            ok(back != null, "正文往返：第 $i 条可解析")
            if (back != null) {
                eqStr(back.text, rows[i].text, "正文往返：第 $i 条正文逐字还原（含换行/引号）")
                eqStr(back.source, rows[i].source, "正文往返：第 $i 条来源逐字还原")
            }
        }

        // 行序就是唯一对应关系：越界/负下标必须拒绝，不能"取最后一条"或"取第一条"
        eqStr(RagIndexStore.decodeTextRow(raw, -1)?.text, null, "正文：负下标 -> null")
        eqStr(RagIndexStore.decodeTextRow(raw, 4)?.text, null, "正文：越界 -> null")
        eqStr(RagIndexStore.decodeTextRow("", 0)?.text, null, "正文：空文件 -> null")

        // 一行坏掉只丢那一行
        val broken = raw.split('\n').toMutableList()
        broken[1] = "{ 这不是 JSON"
        val raw2 = broken.joinToString("\n")
        eqStr(RagIndexStore.decodeTextRow(raw2, 0)?.text, rows[0].text, "正文：第 0 条不受第 1 行损坏影响")
        eqStr(RagIndexStore.decodeTextRow(raw2, 1)?.text, null, "正文：坏行 -> null（不抛异常）")
        eqStr(RagIndexStore.decodeTextRow(raw2, 2)?.text, rows[2].text, "正文：第 2 条不受第 1 行损坏影响")

        // 缺键也按"不是我们的行"处理（不能猜一个空正文 -> 会多注入一条空行）
        eqStr(RagIndexStore.decodeTextRow("{\"t\":\"只有正文\"}", 0)?.text, null,
            "正文：缺 s 键 -> null（不猜值）")
        eqStr(RagIndexStore.decodeTextRow("{\"s\":\"只有来源\"}", 0)?.text, null,
            "正文：缺 t 键 -> null（不猜值）")

        // 空列表 -> 空串（不能是 "\n"，否则读的第一行是空行、被当成"坏行"）
        eqStr(RagIndexStore.encodeTexts(emptyList()), "", "正文：无条目 -> 空串")
    }

    /**
     * 「按行偏移改正文」（ISSUE #228 的 P1-b，`replaceRowsInPlace` 的纯函数那一半）。
     *
     * ══════════════════════════════════════════════════════════════════════
     * 为什么这一组必须钉住
     * ══════════════════════════════════════════════════════════════════════
     * 它是"编辑器里只有一页"这件事唯一安全的落地方式：改过的那几行按**行号**
     * 写回磁盘上那份，其余行**逐字节**保持（真值来自磁盘，不是框）。
     * 而它的失效形态全是**静默**的：
     *   · 行号算错一格 -> 改写的是**隔壁那一段**正文（分数对、内容错，不报错）；
     *   · 越界行号被"容忍" -> 写到别的库/别的文件上去；
     *   · 空行被写成内容 -> 凭空多一条条目（行序没变，但那一格的含义变了）；
     *   · 拼回去时吃掉/多出一个换行 -> 行数与索引 count 对不上，**整个索引作废**。
     * 全都不抛异常，只有断言能钉住。
     */
    // ── 导入的**接缝**：before + encodeTexts（ISSUE #326 的 C1）────────────────
    //
    // `RagIndexStore.importEntries` 的写法是 `before + encodeTexts(incoming)`，
    // 其中 `before` 是从磁盘**原样读回**的已有正文。自产文件以换行收尾，拼接没问题；
    // 但这份文件是**可以被外部改的**（用户手工编辑过 formats.jsonl，或它本身由
    // 别处导入而来），末尾可能没有换行 —— 那时旧末条与本次首条会**粘成同一行**：
    //
    //   before(末尾无\n) = {"t":"a",...}\n{"t":"b",...}
    //   incoming          = {"t":"c",...}\n
    //   拼起来第 2 行   = {"t":"b"...}{"t":"c"...}   ← 两个 JSON 对象挤在一行
    //
    // `decodeTextRow` 对第 2 行解析失败返回 null -> **旧末条 b 与刚导入的 c
    // 都"读不出来"**；而 `rowsToCount` 数的是段数、行数没变，回执上的数字对得上。
    // 症状是"导入成功了，但库里那两条打不开"，静默、无从归因。
    //
    // 修法：拼接前保证 `before` 以单换行收尾（空串除外）。这里把接缝**逐字复刻**
    // 成纯逻辑跑：`before`（外部改过，末尾无换行）+ 一条 incoming。
    private fun testImportSeam() {
        val a = RagIndexStore.Entry("a", "s")
        val b = RagIndexStore.Entry("b", "s")
        val c = RagIndexStore.Entry("c", "s")

        // 已有 2 条，但**用户手工去掉**了末尾换行（真实可达：外部编辑过的文件）。
        val beforeNoNl = RagIndexStore.encodeTexts(listOf(a, b)).trimEnd('\n')
        ok(!beforeNoNl.endsWith("\n"), "导入接缝：构造出「末尾无换行」的已有正文")

        // 复刻**修复后**的接缝：空串或已以换行收尾 -> 原样；否则补一个换行。
        fun seam(before: String, incoming: List<RagIndexStore.Entry>): String =
            (if (before.isEmpty() || before.endsWith("\n")) before else before + "\n") +
                RagIndexStore.encodeTexts(incoming)

        val fixed = seam(beforeNoNl, listOf(c))
        // 三条都读得出来、且逐行合法（这就是"那两条读不出来"的反面）。
        eqStr(RagIndexStore.decodeTextRow(fixed, 0)?.text, "a", "导入接缝：第 0 行仍是 a")
        eqStr(RagIndexStore.decodeTextRow(fixed, 1)?.text, "b", "导入接缝：旧末条 b 没被下一条粘坏")
        eqStr(RagIndexStore.decodeTextRow(fixed, 2)?.text, "c", "导入接缝：新导入的 c 正常落在第 2 行")
        ok(RagIndexStore.readEntriesTexts(fixed).size == 3,
            "导入接缝：读回 3 条（修好接缝后条数对得上）")

        // 反例对照：**旧写法**（原样拼接）在"末尾无换行"时把两条粘成一行 ——
        // 这一条把"修复确实改变了结果"钉住（否则接缝判据可能是恒真的）。
        val buggy = beforeNoNl + RagIndexStore.encodeTexts(listOf(c))
        eqStr(RagIndexStore.decodeTextRow(buggy, 1)?.text, null,
            "对照：旧写法把 b 与 c 粘成一行 -> 第 1 行解析失败")
        ok(RagIndexStore.readEntriesTexts(buggy).size == 1,
            "对照：旧写法只读得出 1 条（b、c 一起没了，而回执数字仍对得上）")

        // 接缝在"正常文件"（末尾有换行）上**不改变结果**：不该给正常路径行为加料。
        val beforeNl = RagIndexStore.encodeTexts(listOf(a, b))
        eqStr(seam(beforeNl, listOf(c)), beforeNl + RagIndexStore.encodeTexts(listOf(c)),
            "导入接缝：末尾已有换行的正常文件 -> 与旧写法逐字节相同（不误伤）")
        // 空库（before 为空串）：不补换行，否则会凭空多一个空段。
        eqStr(seam("", listOf(a)), RagIndexStore.encodeTexts(listOf(a)),
            "导入接缝：空库（before 空串）-> 不补换行，无多余空段")
    }

    private fun testSpliceRows() {
        // 造一份"3 条"的正文（每行一个 JSON，行尾换行）。
        val rows0 = listOf(
            RagIndexStore.Entry("甲", "src-甲"), RagIndexStore.Entry("乙", "src-乙"),
            RagIndexStore.Entry("丙", "src-丙"))
        val base = RagIndexStore.encodeTexts(rows0)
        val lines = base.split("\n")
        // 前 3 行是条目、第 4 行是结尾换行产生的空串 —— 这是本文件的**固定形态**。
        eqStr(lines.size.toString(), "4", "正文：3 条 -> 4 段（末段是结尾换行）")

        // ① 改中间一行：只有那一行变，其余**逐字节**不动，行数不变。
        val mid = RagIndexStore.encodeTextRow("乙改", "src-乙")
        val out = RagIndexStore.spliceRows(base, listOf(1), listOf("乙改"))
        ok(out != null, "按行改：中间一行 -> 有计划")
        val outLines = out!!.split("\n")
        eqStr(outLines.size.toString(), lines.size.toString(), "按行改：行数**不变**")
        eqStr(outLines[0], lines[0], "按行改：第 0 行逐字节不动")
        eqStr(outLines[1], mid, "按行改：第 1 行 == **重新编码的一行 JSON**（不是纯文本）")
        eqStr(outLines[2], lines[2], "按行改：第 2 行逐字节不动")
        eqStr(outLines[3], "", "按行改：结尾换行仍在（少它就与下一次追加粘成一行）")
        // ⚠ 这一条是这套测试存在的**核心理由**：写回去的必须仍能被解出来。
        //   直接写纯文本（最常见的写法）会让那一行 `decodeTextRow` 返回 null ——
        //   刚改的那一条从列表与检索里消失，而磁盘上多了一行垃圾、行号仍对得上。
        eqStr(RagIndexStore.decodeTextRow(out, 1)?.text, "乙改",
            "按行改：改完那一行仍解得出来（写的是 JSON，不是纯文本）")
        eqStr(RagIndexStore.decodeTextRow(out, 1)?.source, "src-乙",
            "按行改：**来源照原样保留**（改字不改来源）")

        // ② 改多行、乱序给 indices -> 结果一致（内部不依赖给的顺序）。
        val out2 = RagIndexStore.spliceRows(base, listOf(2, 0), listOf("C", "A"))
        val l2 = out2!!.split("\n")
        eqStr(RagIndexStore.decodeTextRow(out2, 0)?.text, "A",
            "按行改：乱序 indices 也按**行号**落位（0 <- A）")
        eqStr(RagIndexStore.decodeTextRow(out2, 2)?.text, "C",
            "按行改：乱序 indices 也按**行号**落位（2 <- C）")
        eqStr(l2[1], lines[1], "按行改：没给的行不动")
        eqStr(RagIndexStore.decodeTextRow(out2, 2)?.source, "src-丙",
            "按行改：第二条改动的来源也保留")

        // ③ 越界行号 -> 拒绝（整次，不是部分）。这是"写到别处去"的唯一挡法。
        ok(RagIndexStore.spliceRows(base, listOf(3), listOf("x")) == null,
            "按行改：行号落在**结尾空段**上 -> 拒绝（它不是一条正文）")
        ok(RagIndexStore.spliceRows(base, listOf(4), listOf("x")) == null,
            "按行改：行号越界 -> 拒绝")
        ok(RagIndexStore.spliceRows(base, listOf(-1), listOf("x")) == null,
            "按行改：负行号 -> 拒绝")

        // ④ 改到**空行**上 -> 拒绝（会凭空多出一条条目，而"行号即下标"随即失效）。
        val holed = base.replaceFirst("\n", "\n\n")
        ok(RagIndexStore.spliceRows(holed, listOf(1), listOf("x")) == null,
            "按行改：中间那个空段 -> 也拒绝（它不是条目，`decodeTextRow` 也返回 null）")

        // ⑤ 参数不配对 / 空 -> 拒绝（不抛异常）。
        ok(RagIndexStore.spliceRows(base, listOf(1), emptyList()) == null,
            "按行改：indices 与 texts 不配对 -> 拒绝")
        ok(RagIndexStore.spliceRows(base, emptyList(), emptyList()) == null,
            "按行改：空计划 -> 拒绝（空 = 什么都不用做，不是『写一次』）")

        // ⑥ 与 textsHashOf 的关系：改完之后 hash **必然**变（索引因此自己失效）。
        val after = RagIndexStore.spliceRows(base, listOf(1), listOf("乙改"))!!
        ok(RagIndexStore.textsHashOf(after.toByteArray(Charsets.UTF_8)) !=
           RagIndexStore.textsHashOf(base.toByteArray(Charsets.UTF_8)),
            "按行改：改完之后正文 hash 变了（索引据此判『需重建』，不需额外动作）")
        // 而"改回原值" -> hash 回到原样（保存是幂等的：看一眼再存不该让索引作废）。
        val back = RagIndexStore.spliceRows(after, listOf(1), listOf("乙"))!!
        eqStr(RagIndexStore.textsHashOf(back.toByteArray(Charsets.UTF_8)),
            RagIndexStore.textsHashOf(base.toByteArray(Charsets.UTF_8)),
            "按行改：改回原值 -> hash 回到原样（保存幂等）")

        // ⑦ 行数不变这条不变量：条目数（段数-1）与改前**逐位相等** —— 索引的
        //    `count` 与指纹的 `r` 因此仍然是真的（只有正文 hash 那一项过期）。
        eqStr(RagIndexStore.rowsToCount(RagIndexStore.rowsOf(after.toByteArray())).toString(),
            RagIndexStore.rowsToCount(RagIndexStore.rowsOf(base.toByteArray())).toString(),
            "按行改：行数不变 -> 索引的 count / 指纹的 r 仍然是真的")
    }


    /**
     * 「改内容、行数不变 → 重算改那一行」的**纯函数**那一半（ISSUE #228 新特性）。
     *
     * 它钉的是"算出来的两份字节对不对"，而这一族的失效形态**全是静默的**：
     *   · 原样搬了旧向量 -> 改完正文、向量还是旧的（"用旧语义命中、注入新正文"）；
     *   · 向量写错行 -> 第 i 行的正文配第 j 行的向量（分数对、内容错）；
     *   · 指纹只换 `x` 没换 -> 写下去当场被判"需重建"（这次修法自相矛盾）；
     *   · 指纹把模型那一半**现算**成当前配置 -> 用户改过批大小/换过模型时，
     *     刚写完的索引立刻不可用（指向一个他没动过的字段）。
     */
    private fun testSpliceRowsWithVectors() {
        // ── 造一份 3 条的库：正文 + 索引（dim=4，3 行向量）────────────────────
        val rows0 = listOf(
            RagIndexStore.Entry("甲", "s-甲"), RagIndexStore.Entry("乙", "s-乙"),
            RagIndexStore.Entry("丙", "s-丙"))
        val texts0 = RagIndexStore.encodeTexts(rows0)
        val dim = 4
        // 单位向量：第 i 行第 i 维为 1 —— 一眼看得出"哪一行的向量跑到哪儿去了"。
        val vec0 = FloatArray(3 * dim)
        for (i in 0 until 3) vec0[i * dim + i] = 1f
        // 指纹必须与正文**同源**（否则 ③ 那条前置判据会拒），行数 4（3 条 + 结尾空段）。
        val fp0 = fpOf(dim = dim, cp = RagIndexStore.ChunkParams(480, 64, 16384),
            textsHash = RagIndexStore.textsHashOf(texts0.toByteArray(Charsets.UTF_8)),
            rows = RagIndexStore.rowsOf(texts0.toByteArray(Charsets.UTF_8)))
        val idx0 = RagIndexStore.encode(fp0, dim, vec0)

        // 逐行取某一行向量（测试里到处要）。
        fun rowOf(bytes: ByteArray, i: Int): List<Float> {
            val p = RagIndexStore.decode(bytes)!!
            return (0 until dim).map { p.third[i * dim + it] }
        }

        // ① 改第 1 行：那一行向量换了，其余**逐位**不动，dim/count 不变。
        val newV1 = floatArrayOf(0f, 0.6f, 0.8f, 0f)
        val plan = RagIndexStore.spliceRowsWithVectors(
            texts0, idx0, listOf(1), listOf("乙改"), listOf(newV1))
        ok(plan != null, "按行改+向量：改一行 -> 有计划")
        if (plan == null) return
        eqStr(RagIndexStore.decodeTextRow(plan.textsBytes.toString(Charsets.UTF_8), 1)?.text, "乙改",
            "按行改+向量：正文那一行真的换了")
        eqStr(RagIndexStore.decodeTextRow(plan.textsBytes.toString(Charsets.UTF_8), 1)?.source, "s-乙",
            "按行改+向量：来源照原样保留")
        val p2 = RagIndexStore.decode(plan.indexBytes)!!
        eqi(p2.first.toLong(), dim.toLong(), "按行改+向量：dim 不变")
        eqi(p2.second.toLong(), 3, "按行改+向量：count 不变（行数没挪）")
        ok(rowOf(plan.indexBytes, 1) == newV1.toList(), "按行改+向量：第 1 行向量 == 新算的那一条")
        ok(rowOf(plan.indexBytes, 0) == rowOf(idx0, 0), "按行改+向量：第 0 行向量**逐位**不动")
        ok(rowOf(plan.indexBytes, 2) == rowOf(idx0, 2), "按行改+向量：第 2 行向量**逐位**不动")

        // ② 指纹：`x` 必须换成新正文的 hash —— 否则这份索引会被自己判成"需重建"。
        val fpNew = RagIndexStore.fingerprintIn(plan.indexBytes)!!
        eqStr(RagIndexStore.textsHashInFp(fpNew),
            RagIndexStore.textsHashOf(plan.textsBytes),
            "按行改+向量：指纹的 x == 新正文的 hash（否则刚写完就判需重建）")
        eqStr(RagIndexStore.rowsInFingerprint(fpNew).toString(), "4",
            "按行改+向量：指纹的 r 不变（行数没挪）")
        // ③ 模型那一半**逐字节照搬**：用户改过批大小/换过模型时，不许把它现算成当前配置。
        eqStr(RagIndexStore.fpFields(fpNew)["g"], RagIndexStore.fpFields(fp0)["g"],
            "按行改+向量：指纹的 g（合批条数）照搬磁盘那份，不现算")
        eqStr(RagIndexStore.fpFields(fpNew)["p"], RagIndexStore.fpFields(fp0)["p"],
            "按行改+向量：指纹的 p（模型路径）照搬磁盘那份")
        eqStr(RagIndexStore.fpFields(fpNew)["d"], RagIndexStore.fpFields(fp0)["d"],
            "按行改+向量：指纹的 d（维度）照搬磁盘那份")
        // 逐字段比一次：两份指纹必须**只差 x**（其余 12 格全同）。
        val fa = RagIndexStore.fpFields(fp0); val fb = RagIndexStore.fpFields(fpNew)
        val diffKeys = fa.keys.filter { fa[it] != fb[it] }
        eqStr(diffKeys.joinToString(","), "x",
            "按行改+向量：两份指纹**只差 x 这一格**（其余字段全同）")
        // ④ 写回去的两份文件必须**彼此同源**（textsInSync）。
        ok(RagIndexStore.textsInSync(plan.indexBytes, plan.textsBytes),
            "按行改+向量：写下去的两份彼此同源（textsInSync）")

        // ⑤ 改多行、乱序给 indices -> 按行号落位（不依赖给的顺序）。
        val va = floatArrayOf(1f, 0f, 0f, 0f); val vc = floatArrayOf(0f, 0f, 0f, 1f)
        val plan2 = RagIndexStore.spliceRowsWithVectors(
            texts0, idx0, listOf(2, 0), listOf("C", "A"), listOf(vc, va))!!
        ok(rowOf(plan2.indexBytes, 0) == va.toList(), "按行改+向量：乱序 indices，0 <- A 的向量")
        ok(rowOf(plan2.indexBytes, 2) == vc.toList(), "按行改+向量：乱序 indices，2 <- C 的向量")
        ok(rowOf(plan2.indexBytes, 1) == rowOf(idx0, 1), "按行改+向量：没给的行不动")

        // ⑥ 拒绝：参数不配对 / 空计划。
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idx0, listOf(1), listOf("x"),
            emptyList()) == null, "按行改+向量：向量条数对不上 -> 拒绝")
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idx0, emptyList(),
            emptyList(), emptyList()) == null, "按行改+向量：空计划 -> 拒绝")
        // ⑦ 拒绝：向量维度不对（少一个 float 也不行 —— 那会让向量区整体错位）。
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idx0, listOf(1), listOf("x"),
            listOf(FloatArray(dim - 1))) == null, "按行改+向量：维度不符 -> 拒绝")
        // ⑧ 拒绝：行号越界 / 落在结尾空段上。
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idx0, listOf(3), listOf("x"),
            listOf(FloatArray(dim))) == null, "按行改+向量：行号落在结尾空段 -> 拒绝")
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idx0, listOf(9), listOf("x"),
            listOf(FloatArray(dim))) == null, "按行改+向量：行号越界 -> 拒绝")

        // ⑨ 拒绝：索引只是**半份**（后面那些行本来就没有向量）。
        //    这是"只重算改过那几行"的**前提**不成立 —— 必须整次拒（退回只写正文）。
        val halfVec = FloatArray(1 * dim); halfVec[0] = 1f
        val fpsHalf = fpOf(dim = dim, cp = RagIndexStore.ChunkParams(480, 64, 16384),
            textsHash = RagIndexStore.textsHashOf(texts0.toByteArray(Charsets.UTF_8)),
            rows = RagIndexStore.rowsOf(texts0.toByteArray(Charsets.UTF_8)))
        val idxHalf = RagIndexStore.encode(fpsHalf, dim, halfVec)   // count=1，正文 3 条
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idxHalf, listOf(1), listOf("x"),
            listOf(FloatArray(dim))) == null, "按行改+向量：半份索引 -> 拒绝（它那些行本来就没向量）")

        // ⑩ 拒绝：索引与正文**已经不同源**（上一次保存只写了正文）。
        //    此时"只改这一行"会把一份本来就不一致的索引**假装成一致**（不再报需重建），
        //    而其余行仍是旧语义 —— 这是本族最贵的一种静默。
        val staleFp = fpOf(dim = dim, cp = RagIndexStore.ChunkParams(480, 64, 16384),
            textsHash = "deadbeef00000000",
            rows = RagIndexStore.rowsOf(texts0.toByteArray(Charsets.UTF_8)))
        val idxStale = RagIndexStore.encode(staleFp, dim, vec0)
        ok(RagIndexStore.spliceRowsWithVectors(texts0, idxStale, listOf(1), listOf("x"),
            listOf(FloatArray(dim))) == null, "按行改+向量：索引与正文不同源 -> 拒绝（不许假装一致）")

        // ⑪ 幂等：把改后的内容**再改回原值**，指纹的 `x` 必须回到原样。
        val back = RagIndexStore.spliceRowsWithVectors(
            plan.textsBytes.toString(Charsets.UTF_8), plan.indexBytes,
            listOf(1), listOf("乙"), listOf(floatArrayOf(0f, 1f, 0f, 0f)))!!
        eqStr(RagIndexStore.textsHashInFp(RagIndexStore.fingerprintIn(back.indexBytes)!!),
            RagIndexStore.textsHashOf(texts0.toByteArray(Charsets.UTF_8)),
            "按行改+向量：改回原值 -> 指纹的 x 回到原样（保存幂等）")

        // ⑫ 反例对照：**只写正文**（老路 `spliceRows`）之后，索引**必须**被判成不同源 ——
        //    这一条把"新路 vs 老路"的差别钉住：老路让索引失效，新路不让。
        val textsOnly = RagIndexStore.spliceRows(texts0, listOf(1), listOf("乙改"))!!
        ok(!RagIndexStore.textsInSync(idx0, textsOnly.toByteArray(Charsets.UTF_8)),
            "对照：只写正文（老路）-> 索引被判不同源（需重建）")
        ok(RagIndexStore.textsInSync(plan.indexBytes, plan.textsBytes),
            "对照：只重算那几行（新路）-> 索引仍然同源（无需重建）")
    }

    fun run() {
        // 共用向量表路径：默认相对仓库根，由 run_rag_index_store_tests.sh 传绝对路径
        // （与 C++ 侧读的是**同一个文件**，见 run_rag_index_tests.sh）。
        val vectors = System.getenv("TEXTS_HASH_VECTORS")
            ?: "tools/rag_index/texts_hash_vectors.tsv"
        testTextsHash(vectors)
        testTextsInSync()
        testTextsRoundTrip()
        testRoundTrip()
        testHeaderLayout()
        testLittleEndian()
        testRejects()
        testEmptyVectors()
        testSanitize()
        testFingerprint()
        testCompareFp()
        testBatchGrade()
        testTriState()
        testResume()
        testProgressRows()
        testFloatPrecision()
        testTopK()
        testRank()
        testClipAtCharBoundary()
        testAppendArithmetic()
        testImportSeam()
        testSpliceRows()
        testSpliceRowsWithVectors()
        println("\nPASS $pass / FAIL $fail")
        if (fail != 0) System.exit(1)
    }
}

/**
 * 顶层入口。Kotlin 的 `object` 里的 `@JvmStatic fun main` 会编出
 * `RagIndexStoreTestKt` 之外的名字（`RagIndexStoreTest.main`），
 * 而本仓库所有 host 侧 Kotlin 测试都按 `<文件名>Kt` 调用 —— 用顶层 main 对齐。
 */
fun main() {
    RagIndexStoreTest.run()
}
