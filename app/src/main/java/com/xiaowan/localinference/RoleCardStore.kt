package com.xiaowan.localinference

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色卡**库的持久化**（[RoleCard] 的落盘那一半）。
 *
 * 存储布局（`ctx.filesDir`）：
 * - `cards.json` —— 一个 JSON 数组：`[{id,name,system,createdAt,updatedAt}, …]`
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是一个**文件**而不是"一张卡一个文件"
 * ══════════════════════════════════════════════════════════════════════════
 * 会话是"一个文件一个"（`s_<id>.json`），因为会话会**各自长大**、且列表要按
 * `updatedAt` 排序取数。卡片不一样：它永远是**一次读全量**（下拉列表要的就是
 * 全部名字），单张最大 24k。一个文件 = 一次读、一次写、一份原子性，
 * 没有"读到一半发现第 37 张坏的"这种中间态。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么写盘走"临时文件 + rename"，而会话那边不这么做
 * ══════════════════════════════════════════════════════════════════════════
 * 会话的写点是**追加一条**（`append`），丢了最坏是少一轮对话；而卡片库是
 * **整体重写**（改一张卡就是重写整个文件），写一半断电 = 整个卡片库变成
 * 半截 JSON —— 下次读出来是 [RoleCard.CardFault.UNREADABLE]，**全部卡片一起没**。
 * 用户的损失面完全不是一个量级，所以这里值得多写四行：先写 `.tmp`、
 * 再 `renameTo` 覆盖（同目录内的 rename 在同一文件系统上是原子的）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么读盘"尽力而为"而写盘"要么全成要么全不成"
 * ══════════════════════════════════════════════════════════════════════════
 * 读：一张坏卡不该让整个库读不出来（同 [ChatArchive] 对脏 role 的口径 ——
 * 500 条里有一条脏就全丢是更坏的失败方向）。坏卡**跳过并计数**，
 * 计数由 [RoleCard.Loaded.dropped] 带出去，调用方负责说。
 * 写：一次写进去的是**整个库**，半截写入就是毁数据，所以它必须是全成/全不成。
 */
object RoleCardStore {

    private const val TAG = "RoleCardStore"
    private const val FILE = "cards.json"
    private const val TMP = "cards.json.tmp"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    /**
     * 读整个卡片库。
     *
     * 三种"读到的份数"分得开（见 [RoleCard.CardFault]）：
     * 文件不在 = [RoleCard.CardFault.NONE]（常态，不出声）；
     * 读不出来 = [RoleCard.CardFault.UNREADABLE]（**当作空库**，但要出声 ——
     * 否则用户看到的是"我的卡片全没了"，而真相是"这个文件坏了"，
     * 两者的处置不同：一个是重写一批，一个是回头去看有没有备份）；
     * 顶到上限 = [RoleCard.CardFault.OVER_LIMIT]（超出的没读进来，如实报）。
     *
     * ⚠ **先按 `updatedAt` 倒序、再截断**：卡片库是"最近改的在前面"，
     *   截断必须砍掉**最老**的那些。反过来（按文件里的原始顺序截断）会让
     *   "超出上限的那批"取决于历史写入顺序 —— 用户看到的是一批随机丢掉的卡片。
     */
    fun load(ctx: Context): RoleCard.Loaded {
        val f = file(ctx)
        if (!f.exists()) return RoleCard.Loaded(emptyList(), RoleCard.CardFault.NONE)
        val text = runCatching { f.readText() }.getOrNull()
        if (text == null) {
            Log.w(TAG, "卡片库读取失败（$FILE）：按空库继续，卡片未被删除")
            return RoleCard.Loaded(emptyList(), RoleCard.CardFault.UNREADABLE)
        }
        val parsed = parse(text)
        // 截断那一句在这里打：`parse` 是纯逻辑（宿主可跑），`Log` 只在带 Context
        // 的这条路径上 —— 见 `parse` 里那段注释。
        if (parsed.fault == RoleCard.CardFault.OVER_LIMIT) {
            Log.w(TAG, "卡片库超过上限 ${RoleCard.MAX_CARDS}：只读进最近的那些")
        }
        return parsed
    }

    /**
     * 解析卡片库正文（**纯逻辑**，与文件系统无关，可在宿主上穷举）。
     *
     * 单独抽出来是为了让"哪些卡该被丢掉"这件事能在测试里被穷举 ——
     * 读盘那一层只负责把文件读成字符串，判据全在这里。
     */
    fun parse(text: String): RoleCard.Loaded {
        val arr = runCatching { JSONArray(text) }.getOrNull()
            ?: return RoleCard.Loaded(emptyList(), RoleCard.CardFault.UNREADABLE)
        var dropped = 0
        val out = mutableListOf<RoleCard.Card>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { dropped++; continue }
            val id = o.optString("id", "")
            val name = RoleCard.normalizeName(o.optString("name", ""))
            val sys = RoleCard.normalizeSystem(o.optString("system", ""))
            // 缺 id / 缺名字 / 正文超长的卡**读进来也用不了**（下拉里是一行空白，
            // 或者一存就把整个库顶爆）。跳过并计数，不整份拒绝 —— 同上。
            // ⚠ 判据与 `validate` **同源**（都问 RoleCard），不在这里另写一遍：
            // 两处各写一份，改上限时就会漂移（存得进、读不出，或反过来）。
            if (RoleCard.validate(name, sys) != null) { dropped++; continue }
            out.add(
                RoleCard.Card(
                    id = id.ifEmpty { "recovered-${i.toString(36)}" },
                    name = name,
                    system = sys,
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
            )
        }
        // 最近的排前面；`updatedAt` 缺失（0）的排最后 —— 与 `SessionStore.list` 同口径。
        val sorted = out.sortedByDescending { it.updatedAt }
        if (sorted.size > RoleCard.MAX_CARDS) {
            // ⚠ 截断这件事**不只在日志里说**：`CardFault.OVER_LIMIT` 随结果返回，
            //   调用方必须在界面上说出来。日志是给排查用的，而"我的卡片少了一批"
            //   是用户当场就能看见的事（同本仓库对"静默截断"的一贯口径）。
            //
            // ⚠ 这里刻意**不打日志**：`parse` 要能在宿主上被当纯逻辑跑
            //   （`android.util.Log` 在宿主下是抛 `Stub!` 的空壳，一调就崩，
            //   于是"读盘尽力而为"这条最该被穷举的判据反而测不了）。
            //   打日志的职责留在 `load`（它本来就带 Context、本来就在真机上跑）。
            return RoleCard.Loaded(sorted.take(RoleCard.MAX_CARDS), RoleCard.CardFault.OVER_LIMIT, dropped)
        }
        return RoleCard.Loaded(sorted, RoleCard.CardFault.NONE, dropped)
    }

    /** 序列化（纯逻辑）。字段顺序固定，便于人看与 diff。 */
    fun encode(cards: List<RoleCard.Card>): String {
        val arr = JSONArray()
        for (c in cards) {
            arr.put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("system", c.system)
                    .put("createdAt", c.createdAt).put("updatedAt", c.updatedAt)
            )
        }
        return arr.toString()
    }

    /**
     * 整体覆盖写。**全成或全不成** —— 失败时旧文件一个字节都不动。
     *
     * 先写 `.tmp` 再 `renameTo`：`renameTo` 在同一文件系统上是原子的，
     * 所以任何时刻磁盘上的 `cards.json` 都是一份**完整的** JSON。
     * 直接 `writeText` 则会在"写了一半"留下半截文件，而那等价于**整个卡片库消失**。
     *
     * @return 成功与否。调用方必须处理 false（不能假装存上了）。
     */
    fun save(ctx: Context, cards: List<RoleCard.Card>): Boolean {
        val tmp = File(ctx.filesDir, TMP)
        return runCatching {
            tmp.writeText(encode(cards))
            // 先删目标再改名：`renameTo` 在目标已存在时的行为**各平台不一致**
            // （有的覆盖、有的返回 false），删掉之后就只剩"改到空名字"一条路径。
            // ⚠ 这里有一个**极短**的窗口：删除成功、改名还没做完时断电 =
            //   卡片库没了（而 .tmp 是完整的）。代价与"半截 JSON"相比是可接受的
            //   （半截 JSON 是**每次**都会读成空，而这是个断电窗口），
            //   且 `.tmp` 留在盘上，出问题时能人工救回来 —— 所以不删它。
            file(ctx).delete()
            tmp.renameTo(file(ctx))
        }.getOrElse { e ->
            Log.w(TAG, "卡片库写入失败（旧文件未动）：${e.message}")
            false
        }
    }

    /**
     * 存一张**新卡**。顶到上限时返回 null（并**不动**磁盘）。
     *
     * 判据是 [RoleCard.canAdd]，与"再加一张会不会超"同一个函数 ——
     * 不在这里另写一个 `>= MAX_CARDS`：两处各写一份，改上限时就会漂移
     * （界面说还能加、这里拒了，或者反过来）。
     */
    fun add(ctx: Context, name: String, system: String): RoleCard.Card? {
        val loaded = load(ctx)
        if (!RoleCard.canAdd(loaded.cards.size)) return null
        val card = RoleCard.newCard(name, system, System.currentTimeMillis())
        if (!save(ctx, loaded.cards + card)) return null
        return card
    }

    /**
     * 按 id 改一张卡（名字 / 正文）。找不到、或改完不合法、或写盘失败都返回 false。
     *
     * ⚠ **改卡不碰任何会话的快照** —— 这是 V1 的核心口径（见 [RoleCard.effectiveSystem]）。
     *   后果是"改了卡片，用了它的会话不跟着变"，而这件事**是可见的**
     *   （[RoleCard.Match.STALE] 会在聊天页常驻显示）。反过来（改了卡片顺手
     *   去改所有会话的快照）就是"改一处、动 N 个会话"，而用户按下"保存"时
     *   并不知道自己在改那些会话 —— 不可恢复，且没有任何提示面。
     */
    fun update(ctx: Context, id: String, name: String, system: String): Boolean {
        if (RoleCard.validate(name, system) != null) return false
        val loaded = load(ctx)
        if (loaded.cards.none { it.id == id }) return false
        val next = loaded.cards.map {
            if (it.id != id) it
            else it.copy(
                name = RoleCard.normalizeName(name),
                system = RoleCard.normalizeSystem(system),
                updatedAt = System.currentTimeMillis(),
            )
        }
        return save(ctx, next)
    }

    /**
     * 删一张卡。
     *
     * ⚠ **不检查有没有会话在用它**，也**不动那些会话** —— 刻意如此：
     *   会话存的是快照，删卡之后它照旧能聊（见文件头）。弹一句
     *   "有 N 个会话正在用这张卡"然后让用户决定，是 V2 的事；
     *   V1 只保证"删了不掉内容"，并把"现在用的是快照"这件事显示出来。
     */
    fun delete(ctx: Context, id: String): Boolean {
        val loaded = load(ctx)
        val next = loaded.cards.filterNot { it.id == id }
        if (next.size == loaded.cards.size) return false
        return save(ctx, next)
    }

    /** 按 id 取一张卡（找不到返回 null）。 */
    fun get(ctx: Context, id: String): RoleCard.Card? =
        load(ctx).cards.firstOrNull { it.id == id }
}
