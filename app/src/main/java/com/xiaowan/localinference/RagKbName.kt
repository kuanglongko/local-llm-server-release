package com.xiaowan.localinference

/**
 * 知识库的**显示名 ↔ 磁盘目录名**映射（ISSUE #259）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要这一层
 * ══════════════════════════════════════════════════════════════════════════
 * 用户的第 ③ 条诉求：*"现在选中库，然后在库名输入的地方更换新名字，保存的话会
 * 直接生成一个新库，改名不生效，我认为应该直接生效，且不用重建索引"*。
 *
 * 旧行为是他描述的那样，而且**不是 bug、是设计**：库名就是目录名，
 * `RagIndexStore.dirOf(name)` 直接拿它拼路径。于是"改个名字"唯一能被实现的形态
 * 就是"换一个目录" —— 新目录里什么都没有，看起来就是"新生成一个库"。
 *
 * 改成"改名 = 直接生效且不重建"需要一个**映射**：目录名（磁盘身份）保持不变，
 * 显示名另存一份。索引里的向量与 `texts.jsonl` 的正文都**一个字节都不用动**，
 * 因为改名只改那张表里的一行 —— 这正是"不用重建索引"能成立的原因
 *（重建索引是在算向量，而改名与向量无关）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 三条不变式（每一条对应一种静默错）
 * ══════════════════════════════════════════════════════════════════════════
 * ① **显示名全局唯一**。两个库同名时，"我往哪个库里导入"这个问句没有答案 ——
 *    而屏幕上两行长得一样、状态行只显示一个名字，症状是"内容进了另一个库"
 *    （与 ISSUE #259 那条死路同族：库里文件都在，界面说"成了"，只是不是你要的那个）。
 *    `sanitizeName` 不去重（它是"不做隐式改名"的判据），所以唯一性必须在这里挡。
 * ② **改名不改变目录名**。`dirOf` 拿到的必须是原来那个目录 —— 变了就是
 *    "新生成一个库、旧库还在"（用户报的那条）。
 * ③ **空表 = 老行为**（显示名 == 目录名）。升级上来的磁盘上没有这份表，
 *    此时映射必须恰好是恒等，否则每个老库都会变成"找不到"。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是纯逻辑、零依赖
 * ══════════════════════════════════════════════════════════════════════════
 * 这三条判据全部可以只靠"喂一份表、问几个问题"来钉（`RagKbNameTest`），
 * 不需要 Context / 磁盘 / 嵌入模型。写在 `EngineActivity` 里的话，
 * 它们就只能靠静态守卫去猜 —— 而这一族的错法（取错名字）**在屏幕上没有症状**，
 * 正是最该由单测钉住的那一类（同 `TextBoundary` / `RagUiCap` 的取舍）。
 *
 * [!] 这里的"表"是**磁盘目录名 -> 显示名**。反查（显示名 -> 目录名）在
 *     [dirOf] 里线性走一遍：库的数量是几十这个量级（上限见 `RagUiCap.MAX_LIBS`），
 *     而每次都建一份反向 Map 会在"改名之后忘了重建"时取到旧值 —— 那是这一族
 *     最典型的一处静默错，不值得为几十次比较去换。
 */
object RagKbName {

    /** 表项分隔符。`\t` 与 `\n` 都被 [RagIndexStore.sanitizeName] 挡在库名之外。 */
    private const val SEP = '\t'

    /**
     * 一份映射表：`dirName`（磁盘身份，永不变）-> `display`（用户看到的名字）。
     *
     * 用 `LinkedHashMap` 的语义（插入序）而非 `HashMap`：列表渲染顺序稳定，
     * 而"每次进页面库的顺序都在变"会被读成"我找不到刚才那个库了"。
     */
    data class Map(val entries: List<Pair<String, String>>) {

        /** 显示名 -> 目录名。查不到时**回落成它自己**（老库 = 恒等映射）。 */
        fun dirOf(display: String): String {
            for ((d, s) in entries) if (s == display) return d
            return display
        }

        /** 目录名 -> 显示名。查不到时**回落成它自己**（老库 = 恒等映射）。 */
        fun displayOf(dir: String): String {
            for ((d, s) in entries) if (d == dir) return s
            return dir
        }

        /**
         * 改一个库的显示名。**目录名不变** —— 这就是"改名不重建索引"的全部内容。
         *
         * [!] 名字没变时返回 `this`（同一个实例）：调用方据此判"这次不用写盘"
         *     （[EngineActivity.commitRagKbName] 失焦那条高频路径上，
         *     每次点空白都写一遍 preferences 是没必要的 IO）。
         */
        fun rename(dir: String, display: String): Map {
            if (displayOf(dir) == display) return this
            val next = entries.toMutableList()
            val i = next.indexOfFirst { it.first == dir }
            if (i >= 0) next[i] = dir to display else next.add(dir to display)
            return Map(next)
        }

        /**
         * 一个显示名**能不能**给 `dir` 这个库用。`null` = 能用；非 null = 拒绝原因。
         *
         * 三条拒绝里前两条是用户输入问题（与 `sanitizeName` 同一套说法），
         * 第三条是唯一性 —— 它必须在**提交**这一跳挡，而不是让两个库同名共存。
         *
         * ══════════════════════════════════════════════════════════════════
         * [!] 唯一性有**两个来源**：表里那一列，和磁盘上的目录名
         * ══════════════════════════════════════════════════════════════════
         * 只查表是**不够**的，而这一条正是一处真实故障的根子（用户报的
         * "点任意一个库都同时选中两个、名字一起变"）：
         *
         *   磁盘上有 `test` / `test1` / `test2` 三个库，表是**空的**（升级上来的
         *   用户从没改过名 —— 空表 = 恒等映射，这正是不变式 ③）。
         *   用户在 `test` 上把名字改成 `test1`：拿**表**去问，表里没有一行叫
         *   `test1`（它的映射是恒等，压根不在表里），于是判"没人占用" -> 放行。
         *   而磁盘上**明明有一个目录叫 `test1`** —— 它是另一个库。
         *   改名写下的那一行 `test<TAB>test1` 于是与目录 `test1` 撞名：
         *     · `displayOf("test")` 与 `displayOf("test1")` **都是** `test1`
         *       -> 列表里两行同名，选中判据 `name == cur` 对两行同时成立
         *       （两行都画 `●` = "同时选中了两个库"）；
         *     · `dirOf("test1")` 按行的先后命中 `test` -> 目录 `test1` 被遮蔽，
         *       点哪一行都落到 `test` 上（"随着点击不断切换"）。
         *   两处症状是一件事：**两个库共用一个显示名**。
         *
         * 所以判据必须同时看两列：表里的显示名那一列，以及磁盘上的目录名
         * 那一列（`liveDirs`）。"目录名"也是**别人正用着的名字** —— 它虽然没有
         * 表行（恒等映射不进表），但在列表里、在库名框里，它就是一个名字。
         *
         * [!] `liveDirs` 给空集（默认）时退化成"只查表" —— 那是**纯逻辑层**
         *     的可用形态（`RagKbNameTest` 与 `sanitizeName` 那条口径比较用它）。
         *     真正要拍板的调用点（`RagIndexStore.rename` / `EngineActivity`）
         *     必须传磁盘上真实的目录名集合，否则这条判据就会漏掉上面那种撞名。
         *
         * [!] `dir` 自己可以"占用"那个名字（改名成自己 = 无操作），否则连
         *     "重新提交一遍同一个名字"（失焦那条高频路径）都会被拦。
         *     `dir` 自己那个目录也不许算成"被别人占着"（同一个理由）。
         */
        fun checkName(dir: String, display: String, liveDirs: Set<String> = emptySet()): String? {
            val safe = RagIndexStore.sanitizeName(display)
                ?: return "库名非法：只允许字母/数字/中文/-/_/.，且非空、≤64 字"
            for ((d, s) in entries) {
                if (s == safe && d != dir) return "已经有另一个库叫「$safe」了 —— 库名不能重复"
            }
            // 磁盘上真有一个**别的**目录叫这个名 -> 它也是一个正在用这个名字的库。
            // ⚠ 判据是 `safe in liveDirs && safe != dir`：撞上的那个目录**不是**
            //   当前库自己的目录。少了后一半，"把 test1 改名成 test1"（无操作）
            //   会被自己的目录挡下。
            if (safe != dir && safe in liveDirs) {
                return "已经有另一个库叫「$safe」了 —— 库名不能重复"
            }
            return null
        }

        /**
         * 丢掉那些**显示名与某个活着的目录撞名**的行（自愈，ISSUE #259）。
         *
         * [liveDirs] = 磁盘上真实存在的目录名集合。一行 `dir -> display` 是坏的，
         * 当且仅当 `display` 也是**另一个**目录的名字（`display != dir` 且
         * `display in liveDirs`）—— 那种行会让两个库共用显示名（列表两行同时
         * 选中、`dirOf` 把真目录遮蔽掉）。
         *
         * 与 [checkName] 的 `liveDirs` 那一支**同一条判据**（一个是"能不能写进去"，
         * 一个是"写坏了的要不要清掉"）—— 分家的话就会出现"挡得住新的坏名字、
         * 清不掉旧的坏名字"。
         *
         * [!] 纯逻辑（只喂一份表 + 一个集合），所以坏行判据可以离线钉住
         *     （`RagKbNameTest` ⑩）；真正要扫目录的那一层在
         *     `RagIndexStore.repairNames`。
         */
        fun dropDirCollisions(liveDirs: Set<String>): Map {
            val kept = ArrayList<Pair<String, String>>()
            val seen = HashSet<String>()
            for ((dir, display) in entries) {
                if (display != dir && display in liveDirs) continue
                if (!seen.add(display)) continue
                kept.add(dir to display)
            }
            return if (kept.size == entries.size) this else Map(kept)
        }

        /**
         * 这份表覆盖的目录名（= 我们**知道**的那些库）。
         *
         * 用途只有一个：判断"用户提交的这个名字，是不是我认得的一个库"。
         * 不能拿它当"磁盘上有哪些库"（那是 `RagIndexStore.list` 的事）——
         * 表里可以有一个已经被删掉的库（那种行会在下一次保存时被清掉）。
         */
        fun dirs(): List<String> = entries.map { it.first }

        /** 序列化：每行 `dir\tdisplay`。空表写成空串（= 老行为）。 */
        fun encode(): String = entries.joinToString("\n") { it.first + SEP + it.second }

        companion object {
            /**
             * 解析 [encode] 的输出。**任何一行坏了就整行丢掉**，而不是抛异常：
             * 这份文件在私有目录里，用户可能手工改过；一行坏了不该让整个 App
             * 打不开知识库页（那时他会以为"我的库全没了"）。
             *
             * [!] 丢掉一行 = 那个库回落成恒等映射（显示名 == 目录名）。
             *     这是**唯一**安全的失败方向：库还在、内容还在，只是名字变回目录名。
             *     反过来（编一个默认名）会让用户看到一批从没起过的名字。
             */
            fun decode(raw: String): Map {
                val out = ArrayList<Pair<String, String>>()
                val seen = HashSet<String>()
                for (line in raw.split('\n')) {
                    if (line.isEmpty()) continue
                    val i = line.indexOf(SEP)
                    if (i <= 0) continue
                    val dir = line.substring(0, i)
                    val display = line.substring(i + 1)
                    // 目录名也要能被 sanitizeName 接受：这一列是拿去拼路径的，
                    // 而"表里存了一个带 `/` 的目录名"就是任意路径读（同 sanitizeName 那条）。
                    if (RagIndexStore.sanitizeName(dir) != dir) continue
                    if (RagIndexStore.sanitizeName(display) != display) continue
                    // 显示名唯一：后面的同名行丢掉（先到先得），否则反查会随文件顺序漂。
                    if (!seen.add(display)) continue
                    out.add(dir to display)
                }
                return Map(out)
            }
        }
    }
}
