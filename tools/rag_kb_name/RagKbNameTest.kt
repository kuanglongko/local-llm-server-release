package com.xiaowan.localinference

/**
 * `RagKbName`（显示名 ↔ 目录名那张表）的离线单测。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么这一层必须由断言钉住，而不是靠静态守卫去猜
 * ═══════════════════════════════════════════════════════════════════════════
 * 用户报的第 ③ 条（ISSUE #259）：*"选中库，然后在库名输入的地方更换新名字，
 * 保存的话会直接生成一个新库，改名不生效，我认为应该直接生效，且不用重建索引"*。
 *
 * 这条改动的全部风险都在**名字到目录的映射**上，而它错了**没有任何症状**：
 *   · 映射丢了 -> 那个库回落成目录名显示（看着像"改名没生效"）；
 *   · 映射串了 -> 两个显示名指向同一个目录（看着像"改名把另一个库改没了"）；
 *   · 唯一性没挡 -> 两个库同名，"我往哪个库里导入"没有答案。
 * 三种都不崩、不报错、日志干净 —— 只有"喂一份表、问几个问题"能钉住。
 *
 * [!] 它是**零依赖纯逻辑**，但 `RagKbName.checkName` / `decode` 借用了
 *     `RagIndexStore.sanitizeName`（那一个函数不含任何 Android 类型），
 *     所以这里要连 `RagIndexStore.kt` 一起编 —— 那会让本套件拖进
 *     `android.content.Context`。为了让单测**真的能独立跑**，
 *     下面用一个"最小宿主"来验，并**显式**断言"sanitizeName 的口径没变"
 *     （两处各写一套净化的代价见 `RagKbName` 文件头：那一族错我这次不造第二份）。
 */
fun main() {
    var pass = 0
    var fail = 0

    fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    fun eq(got: Any?, want: Any?, what: String) = ok(got == want, "$what（得到 $got / 期望 $want）")

    // ── ① 空表 = 恒等（升级上来的磁盘上没有这份表）─────────────────────────
    val empty = RagKbName.Map(emptyList())
    eq(empty.dirOf("笔记"), "笔记", "① 空表：显示名 -> 目录名 是恒等的（老库不因缺表而失联）")
    eq(empty.displayOf("笔记"), "笔记", "① 空表：目录名 -> 显示名 是恒等的")
    eq(empty.encode(), "", "① 空表序列化成空串（不是一行空记录）")

    // ── ② 改名：目录名一个字符都不动（这就是"不用重建索引"）──────────────
    val m1 = empty.rename("kb_a", "笔记")
    eq(m1.dirOf("笔记"), "kb_a", "② 改名之后，显示名解析到的还是**原来那个目录**")
    eq(m1.displayOf("kb_a"), "笔记", "② 目录名解析到新显示名")
    eq(m1.displayOf("kb_b"), "kb_b", "② 表里没有的目录仍回落成目录名（只管自己那几行）")
    // 反查是**双向**的：只写一边的实现在"改第二个名字"时会漏掉第一次改的那行。
    val m2 = m1.rename("kb_b", "资料")
    eq(m2.dirOf("笔记"), "kb_a", "② 改第二个库不会把第一个库的映射挤掉")
    eq(m2.dirOf("资料"), "kb_b", "② 第二个库也解析得到")
    eq(m2.entries.size, 2, "② 两次改名 = 两行（不是每次都新增一行）")

    // ── ③ 幂等：改名成自己、或重复提交同一个名字，一个字节都不该变 ────────
    val same = m1.rename("kb_a", "笔记")
    ok(same === m1, "③ 名字没变时返回**同一个实例**（调用方据此跳过写盘）")
    val other = m1.rename("kb_a", "手记")
    eq(other.dirOf("笔记"), "笔记", "③ 改名之后旧名字不再指向那个目录（回落成它自己）")
    eq(other.dirOf("手记"), "kb_a", "③ 新名字指向那个目录")
    ok(other !== m1, "③ 名字变了 -> 新实例（否则调用方不会写盘）")

    // ── ④ 唯一性：两个库不许同名 ─────────────────────────────────────────
    ok(m2.checkName("kb_a", "资料") != null, "④ 撞上另一个库的显示名 -> 拒绝")
    ok(m2.checkName("kb_a", "手记") == null, "④ 没被占用的名字 -> 放行")
    ok(m2.checkName("kb_a", "笔记") == null, "④ 用自己的名字 -> 放行（改名成自己是无操作）")
    ok(m1.checkName("kb_a", "") != null, "④ 空名 -> 拒绝（不许静默改成空）")
    ok(m1.checkName("kb_a", "a/b") != null, "④ 含 `/` 的名字 -> 拒绝（那是路径，不是名字）")

    // ── ⑤ 序列化往返：每一行都活着 ───────────────────────────────────────
    val round = RagKbName.Map.decode(m2.encode())
    eq(round.dirOf("笔记"), "kb_a", "⑤ 往返之后第一行还在")
    eq(round.dirOf("资料"), "kb_b", "⑤ 往返之后第二行还在")
    eq(round.entries.size, 2, "⑤ 不凭空多出或少掉行")

    // ── ⑥ 坏文件只坏那一行（不许整份丢掉、也不许编名字）──────────────────
    // 用户可能拿记事本手工编辑过这个文件：一行坏了不该让整张表失效
    //（那等于"所有库同时改回目录名"，看起来像"我的改名全丢了"）。
    val broken = RagKbName.Map.decode(
        "kb_a\t笔记\n" +          // 好行
        "garbage-no-tab\n" +      // 缺分隔符
        "kb_c\t\n" +              // 显示名是空的（sanitizeName 会拒）
        "kb_d\t名字\t多余列\n" +   // 多余的分隔符（显示名里含 \t -> 拒）
        "kb_e\t资料\n" +          // 好行
        "kb_f\t资料\n"            // 显示名与 kb_e 撞了 -> 丢掉后面那行
    )
    eq(broken.dirOf("笔记"), "kb_a", "⑥ 坏行不影响好行")
    eq(broken.dirOf("资料"), "kb_e", "⑥ 同名行**先到先得**（先后不能随文件顺序漂）")
    eq(broken.entries.size, 2, "⑥ 四行坏的都被丢掉，不留半行")
    eq(broken.displayOf("kb_c"), "kb_c", "⑥ 被丢掉那一行的库回落成目录名（库还在）")

    // ── ⑦ 目录名那一列也要净过：它是拿去拼路径的 ─────────────────────────
    // 表文件在私有目录里、用户可手工改，所以"表里存了一个带 `/` 的目录名"
    // 是一条真实可达的任意路径读（同 sanitizeName 那条）。
    val dangerous = RagKbName.Map.decode("../../etc\t笔记\n")
    eq(dangerous.entries.size, 0, "⑦ 带 `../` 的目录名整行丢掉（不许进表）")

    // ── ⑧ 与 sanitizeName 同一口径（两处各写一套就是下一次分家）──────────
    // 这一条钉的是"我没有在这里另立一套净化规则"：`checkName` 通过的名字，
    // 必须与 `RagIndexStore.sanitizeName` 的判定逐字一致。
    // ⚠ 必须用**空表**去问：`checkName` 把"合法"与"没被占用"两件事合成了一句话
    //   （对调用方的价值就在这儿 —— 它只需要知道"能不能改名"），
    //   所以拿一张有内容的表去比，比出来的是"重名"，而那不是净化规则的分家。
    //   ⚠ 第一版就是这么写错的，而它被断言当场抓住：`m1` 里 `笔记` 已经被
    //     `kb_a` 占着，于是 `checkName` 判"非法"，而 `sanitizeName` 判"合法" ——
    //     看着像净化分家，其实是这一条判据自己搞混了两个问句。
    for (n in listOf("笔记", "kb-1", "a_b.c", "有 空格", "", "..", "a/b", "名".repeat(65))) {
        val viaCheck = empty.checkName("kb_x", n) == null
        val viaSan = RagIndexStore.sanitizeName(n) != null
        ok(viaCheck == viaSan, "⑧ checkName 与 sanitizeName 对「$n」判定一致")
    }
    // 净化之后**不是**原名（空格 -> `_`）时两处也必须一致：
    // 这一格是"合法但会被改写"，而"静默改写库名"正是 sanitizeName 拒绝做的事
    //（它只对**空白**做替换，其余非法字符一律返回 null）。
    ok(RagIndexStore.sanitizeName("有 空格") == "有_空格",
        "⑧ 空白被换成 `_`（口径与 sanitizeName 一致，不另立一套）")
    ok(m1.checkName("kb_a", "有 空格") == null && m1.dirOf("有_空格") == "有_空格",
        "⑧ 带空格的名字在表里也按净化后的形状存（表不会被写成两份）")

    // ── ⑨ 唯一性必须**同时看磁盘目录**（用户报的"两个库同时被选中"）───────
    // 磁盘上有 test / test1 / test2（表是空的 = 恒等映射，升级上来的盘就这样）。
    // 用户在 test 上把名字改成 test1 —— 表里没有一行叫 test1（恒等映射不进表），
    // 所以**只查表**的判据会说"没人占用"，而磁盘上**明明有一个目录叫 test1**。
    // 放行的后果就是用户报的那件事：两个库共用显示名 test1，
    // 列表两行同时画 ●，`dirOf("test1")` 还按行的先后把真正的 test1 目录遮蔽掉。
    //
    // 判据：`checkName(dir, display, liveDirs)` 带上磁盘目录那一列之后必须拒绝。
    val live = setOf("test", "test1", "test2")
    ok(empty.checkName("test", "test1", live) != null,
        "⑨ 改成磁盘上**另一个**目录的名 -> 拒绝（否则两个库共用显示名）")
    ok(empty.checkName("test", "test1") == null,
        "⑨ 不传 liveDirs 时退化成只查表（纯逻辑层的可用形态）")
    ok(empty.checkName("test", "test", live) == null,
        "⑨ 改成**自己目录**的名 -> 放行（无操作，不许被自己的目录挡下）")
    ok(empty.checkName("test", "test3", live) == null,
        "⑨ 改成磁盘上没有的名 -> 放行（那是正常改名）")
    // 表里已经有映射时，两列都要看：表挡显示名、liveDirs 挡目录名。
    val m3 = empty.rename("kb_a", "笔记")
    ok(m3.checkName("kb_b", "笔记", live) != null, "⑨ 撞上表里另一行的显示名 -> 拒绝")
    ok(m3.checkName("kb_a", "笔记", live) == null, "⑨ 用自己的显示名 -> 放行")
    ok(m3.checkName("kb_x", "test1", live) != null, "⑨ 表 + 磁盘两列一起看")

    // ── ⑩ 自愈：表里"与磁盘目录撞名"的坏行要被丢掉（给旧版本改坏的盘兜底）──
    // 用户盘上已经有一行 `test<TAB>test1`（旧版本写的）。新版本光把**新的**改名
    // 挡下来不够 —— 那份坏表还在，下次进来照样是两个库同名。所以要能把它清掉。
    val brokenTbl = RagKbName.Map.decode("test\ttest1\nkb_b\t资料\n")
    val fixed = brokenTbl.dropDirCollisions(setOf("test", "test1", "test2", "kb_b"))
    eq(fixed.displayOf("test"), "test",
        "⑩ 与磁盘目录撞名的行被丢掉 -> 那个库回落成目录名显示（库还在）")
    eq(fixed.displayOf("kb_b"), "资料", "⑩ 不撞名的行原样保留")
    eq(fixed.entries.size, 1, "⑩ 只丢坏的那一行")
    // 没坏行时返回**同一个实例**（调用方据此跳过写盘）。
    // ⚠ 这里必须用一份**真的没坏行**的表去问：拿 brokenTbl 去问会得到新实例
    //   （它有一行是坏的），断言就写反了 —— 这类"拿错样本"的错会让判据恒假。
    val clean = RagKbName.Map.decode("kb_b\t资料\n")
    ok(clean.dropDirCollisions(setOf("kb_b")) === clean,
        "⑩ 没坏行时返回**同一个实例**（调用方据此跳过写盘）")
    // 目录自己那一名不算撞："把 test1 改名成 test1"（映射恒等、但不该被自己挡下）。
    val selfNamed = RagKbName.Map.decode("test1\ttest1\n")
    eq(selfNamed.dropDirCollisions(setOf("test1")).entries.size, 1,
        "⑩ 显示名 == 自己的目录名 -> 不是坏行（不许把自己的库改回目录名）")

    println()
    println("RagKbNameTest: PASS $pass / FAIL $fail")
    if (fail > 0) throw AssertionError("RagKbNameTest: FAIL $fail")
}
