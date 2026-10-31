package com.xiaowan.localinference

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 「存活探测」：从 App 内**真发一次 HTTP 请求**打自己的 `/health`，再解析响应。
 *
 * 为什么不做成「读内存里的 HttpApi.isRunning / LlmEngine.hasModel」：
 * 那些标志位只说明对象自认为在跑，说明不了**端口真的可达**。现场踩过的两次
 * 假活态都骗过了标志位：
 *   1. 冻结策略下 accept 线程已死，但端口仍在 LISTEN —— 自检标志位全绿，客户端全超时；
 *   2. 绑定地址从回环改成 0.0.0.0（或反过来）后，本机 127.0.0.1 不再可达。
 * 用户点「存活探测」要的就是「现在到底连不连得上」，所以这里走完整链路：
 * DNS 解析 → TCP 连接 → 发请求 → 收响应 → 解析 JSON → 判定 status。
 *
 * 请求与解析都做成不依赖 Android 的纯函数（[buildRequest] / [parse] / [verdict]），
 * 好让判定规则能在宿主侧离线单测——真机上只能靠"看着对不对"发现的那种错，
 * 正是这个文件要避免的。
 */
object HealthCheck {

    /**
     * `/health` 响应的结构化视图。
     * 全部字段可空：探测的价值恰恰在于服务器可能只回了半截（{@code status} 都没有）
     * 或者根本不是 JSON（连到了别的进程的 8080），那时必须如实报"响应异常"，
     * 不能为了凑齐字段而给默认值——默认值会把"没读到"伪装成"读到了且正常"。
     */
    data class Result(
        /** HTTP 状态码，未收到响应行为 null。 */
        val httpCode: Int?,
        /**
         * 原始响应体**原文**（未截断）；非 200 时也保留，便于把服务器的错误信息带出来。
         *
         * 这里是完整的，截断只发生在 [format] 的回显上 —— 两者不能混为一谈：
         * 判定用的是完整原文（[jsonParsed] 就是对它解析的结果），
         * 报告里给用户复制的才是预览，且预览必然标注"已截断"。
         */
        val body: String,
        /** /health 的 status 字段；缺失即为 null。 */
        val status: String?,
        val modelLoaded: Boolean?,
        val modelDesc: String?,
        val ctxUsed: Int?,
        /**
         * 上下文**总量**（`/health` 的 `ctx_size`；用户设在设置页的那个数）。
         *
         * ⚠ 多 slot 之后它与"每一格能用多少"是**两个数**（每格 = 总量 ÷ slot 数，
         * 见 [ctxPerSlot]）。报告里必须把两格并排说出来 —— 只报一个数的后果是
         * `ctx=8192 / parallelN=2` 显示成 `上下文：0/4096`，用户据此以为自己填的
         * 8192 没生效（"被写死成 8192"那条真机反馈）。
         */
        val ctxSize: Int?,
        /**
         * **实际生效**的 ctx 总量（`/health` 的 `ctx_actual`；0 = 未加载，`null` = 服务端没报）。
         *
         * 与 [ctxSize]（**请求的**总量 = 用户填在设置页的那个数）分两个字段，理由一条：
         * 它们不相等时结论完全不同（ISSUE #392 真机）——
         *   · 相等 —— 设置生效了，没问题；
         *   · `ctx_actual < ctx_size` —— 平台把每份钳小了（或第 2 份建失败），
         *     "我填的值没生效"要看这一格，而不是去改设置页那个已经填对的数。
         * 以前只有一个数，而它是"实际每格 × 份数"—— 用户填 16128、`parallelN=2`
         * 时会被报成 32256：一个从没被填过的数。
         */
        val ctxActual: Int? = null,
        val busy: Boolean?,
        val port: Int?,
        /**
         * 「最近一次被后台冻结了多久」（毫秒；`null` = 服务端没报这个字段）。
         *
         * 来自 `/health` 的 `frozen_ms`。它是**冻结唯一的可观测面**：
         * 被冻期间连接不断、写不抛异常、日志不写，解冻之后"探活成功"与
         * "从没被冻过"读数完全一样 —— 没有这个数，"后台到底有没有被限制"
         * 就只能靠用户的体感描述（Issue #159）。
         */
        val frozenMs: Long? = null,
        /** 累计检出被冻的次数（`/health` 的 `frozen_count`）。 */
        val frozenCount: Int? = null,
        /** 累计被冻的毫秒数（`/health` 的 `frozen_total_ms`）。 */
        val frozenTotalMs: Long? = null,
        /** 是否欠着一次"空闲后重启监听"（`/health` 的 `restart_pending`）。 */
        val restartPending: Boolean? = null,
        /**
         * 嵌入**合批**的六个读数（ISSUE #269 第 1 步）。`null` = 服务端没报
         * （旧版本 / 不是本服务）—— 与上面同一条口径：缺字段要报缺，不能给默认值。
         *
         * 为什么它们要进探活报告：合批是**纯加速**，生效与不生效在协议上
         * 完全看不出来。只在 App 内日志里报的话，远端客户端与监控就永远
         * 读不到——而日志会滚、要人先动手翻，于是它极容易在回归里
         * 静默失效而无人发现（与 `kv_cache` 那三个字段同一个病）。
         */
        val embedBatchBatches: Long? = null,
        val embedBatchEntries: Long? = null,
        val embedBatchFallbackBatches: Long? = null,
        val embedBatchFallbackEntries: Long? = null,
        val embedBatchMs: Long? = null,
        /** 当前生效的批大小（设置值）；`null` = 服务端没报。 */
        val embedBatchSize: Int? = null,
        /**
         * **建当前库的索引时用的**批大小（写在那份索引的指纹里，`g` 那一格）。
         * `null` = 服务端没报；`-1` = 当前库没有索引 / 读不出来。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么它必须与 [embedBatchSize] **并排**出现
         * ═══════════════════════════════════════════════════════════════════
         * `g` 是索引指纹的**必比字段**：用户把批大小从 8 改成 1 之后，磁盘上那份
         * 索引（`g=8`）与当前配置（`g=1`）对不上，整个库**静默不可检索**。
         * 而此前 `/health` 里**只有当前配置那一个数** —— 于是"我改了它，库为什么
         * 废了"在读数上没有任何地方能回答（两个数里只有一个在外面）。
         *
         * 两个数摆在一起，判据才是可判的：
         *   · 两者相等 -> 批大小不是库不可用的原因，去别处查；
         *   · 两者不同 -> **就是它**：改回来（或重建索引）。
         * 这正是本仓库"纯加速/纯配置的东西必须有可观测面"那条口径
         *（见 `kv_cache` / `embed_batch_*` 的同族注释）。
         */
        val embedBatchSizeIndex: Int? = null,
        /**
         * [embedBatchSizeIndex] 读的是**哪个库**（当前选中的那个）。
         *
         * 为什么连库名一起报：`g` 是**每份索引自己**开头写的，没有"全局的那个"。
         * 只报一个 `-1` 而不说读的是谁，用户分不清是"这个库没索引"还是
         * "服务端读的是另一个库" —— 那正是"两个真值源分岔时没有任何现象指向它"。
         */
        val embedBatchSizeIndexKb: String? = null,
        /**
         * 嵌入模型**每条文本**的上下文预算（设置值，0.9.212）。
         *
         * 它是"内存占用为啥高"的那一格：KV / output / compute buffer 三项都按
         * 「它 × 批上限」分配，而 [embedBatchSize] 那格**不影响内存**。
         * 只报批大小时，用户把它从 8 调到 1 会以为内存该降了 —— 而不会降。
         */
        val embedCtxPerSeq: Int? = null,
        /**
         * **生效中**的嵌入每条预算（`/health` 的 `embed_ctx_per_seq_live`，= 库的
         * `n_ctx_seq`）；0 = 嵌入模型没加载，null = 服务端没报这个字段。
         *
         * ⚠ 与 [embedCtxPerSeq]（设置值）是两个数：native 把设置值钳到
         * `kEmbedCtxMax / kEmbedBatchRows`（0.9.241 起 512）。只报设置值时，
         * "填 1024、实际按 512 跑"与"填 1024、真的用上"在报告里逐字相同。
         */
        val embedCtxPerSeqLive: Int? = null,
        /**
         * 嵌入**每批条数上限**（设置值，= native 的 `n_seq_max`，0.9.242）。
         *
         * 它是"省内存"的**第二格**（第一格是 [embedCtxPerSeq]）：`n_ctx = 每条预算 × 它`，
         * 而 KV / output buffer 按 `n_ctx` 分配。与 [embedBatchSize]（循环里一次塞几条）
         * **不是同一件事** —— 那一格不改 ctx 容量、一 MB 都不省。
         */
        val embedSeqMax: Int? = null,
        /**
         * **生效中**的每批条数上限（`/health` 的 `embed_seq_max_live`，= `E.seq_max`）；
         * 0 = 嵌入模型没加载。
         *
         * ⚠ 与 [embedSeqMax] 是两个数：native 会把设置值钳到 `kEmbedBatchRowsCap`，
         * 而"库这一份 ctx 真拿到几个 seq"只有加载后才知道。合批能塞几条认的是它。
         */
        val embedSeqMaxLive: Int? = null,
        /**
         * 嵌入**两道门**（设置值，`n_batch` / `n_ubatch` 会被设成同一个数，0.9.242）。
         *
         * 它省的是 **compute buffer**（按整批 token 数切内部缓冲与计算图），
         * **不是** KV —— 后者看 [embedCtxPerSeq] × [embedSeqMax]。两格在报告里
         * 必须分开写，否则用户会为了省 KV 去调这一格（方向错，且一点都不降）。
         */
        val embedGate: Int? = null,
        /** **生效中**的两道门（`/health` 的 `embed_gate_live`，库的实数取 min）；0 = 没加载。 */
        val embedGateLive: Int? = null,
        /**
         * **生效中**的 ctx 总格数（`/health` 的 `embed_ctx_total_live`，= 库的 `n_ctx`）；
         * 0 = 没加载。
         *
         * ⚠ 这是"内存到底降了多少"的**唯一**直接读数 —— 另三格都只是它的输入。
         */
        val embedCtxTotalLive: Int? = null,
        /**
         * `kv_slots` 这一格的**形状**（`null` = 服务端没报 `kv_slots`；空集 = 报了但每格都完整）。
         *
         * #384 真机：`/health` 的 `kv_slots[i]` 只剩下半截属性，
         * `{i,loaded,used,valid,reuse,prefill,rounds,lcp}` 这八个字段**一个都没发**。
         * 而缺字段在这里从来不报"缺"—— `optInt(k, MIN_VALUE)` 静默给默认值，
         * 于是"这一格没跑过（真有 0 轮）"与"服务端根本没发这个字段"读数完全相同。
         *
         * 所以形状本身要成为一格读数：**缺了哪几个 key**，报告里当场点名。
         * 缺的判据挂在**契约**上（那八个 key 是 `kv_slots` 的定义，见 HttpApi 里那段），
         * 不是挂在"某个数是不是 0"上 —— 后者是数据，前者才是 bug。
         */
        val kvSlotMissingKeys: List<String>? = null,
        /**
         * 上一轮**我们自己从头部丢掉的 prompt token 数**（`/health` 的 `prompt_trimmed`，
         * 取 `kv_slots` 里**有账本的那一格**；`null` = 服务端没报这一格）。
         *
         * 它是"KV 复用为什么是 0"唯一能自证的那个数：丢掉的那一段就是断点
         *（`lcp ≈ prompt_trimmed`），所以 `prompt_trimmed >= 16` 时复用**必然**没了，
         * 而那不是模型/门槛的问题，是我们这一刀。0 = 与复用无关，去别处查。
         *
         * ⚠ 与 [genTrimmed] 分开报：只削**生成上限**对 KV 零影响，只削 prompt 才断缓存。
         *   两者合成一个数时，"它只是话变短了"会被读成"历史被裁过"，处置正好相反。
         */
        val promptTrimmed: Int? = null,
        /** 上一轮为装下而削掉的**生成长度**（`/health` 的 `gen_trimmed`；0 = 没削）。 */
        val genTrimmed: Int? = null,
        /**
         * 上一轮**丢完之后还剩多少余量**（`/health` 的 `spare`；0 = 尾巴顶在**整格**边界上）。
         *
         * ⚠ 它是"离整格还有多远"，不是"离水位还有多远"。水位 85% 的那一轮
         *   `spare` 可能还有几百枚，而真正决定"下一轮会不会再丢"的是 [dropRoom] ——
         *   只报 `spare` 时"还有余量"与"下一轮又要丢"同时为真，读的人只会得出前者。
         *   所以报告里那句话已经换成 [dropRoom]（见「上下文预算」那一段）。
         */
        val spare: Int? = null,
        /**
         * 上一轮用的是**哪一档水位**（`/health` 的 `ctx_watermark`；`null` = 没报）。
         *
         * 它是"多久丢一次"的唯一解释变量：同一个 `drop_room`，在 70% 与 90% 两档下
         * 说的完全是两件事（前者是"历史上限本来就短"，后者是"刚丢完一大截"）。
         */
        val ctxWatermark: Int? = null,
        /**
         * 上一轮**丢完之后离水位还剩多少**（`/health` 的 `drop_room`；0 = 贴着水位）。
         *
         * 判据不是"丢没丢"：`prompt_trimmed > 0` 在「丢了一次」与「每轮都在丢」
         * 两种处境下**都成立**，而这两件事的处置完全相反（一次丢到位 = 正常；
         * 每轮都丢 = 起点永远接不上、`reuse` 每轮有值但每轮都白算）。
         * 只有这一格说得清是哪一种：
         *   · `dropRoom > 0` -> 下一轮历史还能长这么多枚才需要再丢一次，起点可以不动；
         *   · `dropRoom == 0` -> 已经贴在水位上，**下一轮必然再丢一次**。
         *
         * `null` = 服务端没报这一格（旧版本只有 `wm_dropped`，而那一格与
         * `prompt_trimmed` 共线）。
         */
        val dropRoom: Int? = null,
        /**
         * 上一轮**比"只丢缺口"多丢了多少**（`/health` 的 `drop_overshoot`）。
         *
         * 它回答"这一刀丢得值不值"：多丢的每一枚都是主动忘掉的记忆，换来的是下一轮
         * 不用马上再丢。没有这一格时，`prompt_trimmed` 一大就只能猜是"容量不够"还是
         * "水位在主动收紧" —— 而两者的处置正好相反（调 ctx vs 调水位一档）。
         */
        val dropOvershoot: Int? = null,
        /**
         * 生成**为什么停**的三笔账（`/health` 的 `gen_planned` / `n_rem` / `stop_wall`）。
         *
         * 实际能生成 `min(n_rem, stop_wall)` 枚，谁最小谁就是那一轮断掉的原因：
         *   · `genPlanned` —— 调用方自己只要这么多（`max_tokens`）；
         *   · `genRem`     —— 预算削过（看 `gen_cap` / `gen_trimmed`）；
         *   · `genWall`    —— 位置不够（`n_ctx - 2 - n_used`，与预算无关）。
         * 只报 `gen_cap` 时这三件事逐字相同 —— 真机那条「请求 1024、实际给 99、
         * KV 还空着 1618 格」就是被它盖住的（`spare > 0` 与"只给 99 个位置"同时成立）。
         */
        val genPlanned: Int? = null,
        val genRem: Int? = null,
        val genWall: Int? = null,
        /**
         * `spare - stop_wall` —— 位置**是不是被 prompt 吃掉了**。
         *
         * ⚠ 这一格**不再从 `/health` 读**，而是由 [spare] 与 [genWall] 在这里相减
         * 派生（#392 收口：它本来就是那两个数的减法，而那两个数在报告里就在同一屏上）。
         * 派生规则：任一侧是 `null`（服务端没报 / 旧版本）就保持 `null` ——
         * 不拿默认值凑一个差额，这是这份文件的通例。
         *
         * `spare`（预算那边的余量）与 `stopWall`（位置还剩几枚）各自都合法，
         * 但在同一份读数里它们会算出**互相矛盾**的两句话，而差额才是答案：
         *
         * 真机：`spare 490` / `stop_wall 1514` / `used 2646` / `ctx 4096`
         *   · 由 `spare`：`4094 - 490 = 3604` 的位置被用掉了；
         *   · 由 `stop_wall`：`4094 - 1514 = 2580` 被用掉了（= `ledger_prompt`）。
         * 差出来的 **1024 正是这一轮请求的生成长度** —— `spare` 已经把 gen 算进去、
         * `stop_wall` 还没有。所以那一轮 **不是 prompt 太长**，别去调 ctx。
         *
         * 判据只用差额，**不用** `spare + gen == stop_wall` 这个等式：两格的坐标系
         * 不同（一个在丢头之前的真 token 上算、一个在 prefill 之后算），等式在
         * 真机上差 1024 —— 那正是上面那条缝。
         */
        val posGap: Int? = null,
        /**
         * 上一轮**那一刀落在哪一截**的那句话（`kv_slots[].fork_why`；空串 = 没有分叉可说）。
         *
         * 判据只有一份（native 的 `kvprefix::fork_where`），这里只取来显示 ——
         * 探活报告以前只报 `prompt_trimmed`（"是不是我们砍的"），回答不了
         * "**断点在哪一截**"：落在上一轮回复段开头（续写差异，改服务端参数没用）、
         * 落在 prompt 段中间（有东西每轮都在变，是 bug）、本轮比上一轮短（历史被裁过）。
         * 三类处置完全相反，而它们只在日志里，用户得连电脑看日志才能读到。
         *
         * ⚠ **不按"本轮 prompt 长度"推落点**：那句话曾经拿 `fork_at` 与「本轮 prompt」
         *   比，于是真机上"上轮 prompt 1371 + 生成 497 / 本轮 661 / 公共前缀 152"
         *   那场被读成"落在 prompt 段中间 ⇒ 有东西每轮在变"（方向反了 —— 真相是
         *   上一轮那段历史本轮没被带回来）。落点只能按**上一轮坐标系**判（native
         *   的 `fork_prev`，句子里带出来的那个数），这里只显示、不重算。
         */
        val forkWhy: String? = null,
        /**
         * 那句话的**可信度**（`kv_slots[].head_same` / `head_cmp` / `fork_in_head`）。
         *
         * `forkWhy` 里「回复没被带回来，改服务端参数没用」只有在**断点之前逐 token
         * 相等**时才成立。真机上这句话出现在"开头就已经不一样"的场次里，方向正好相反
         * （用户会去改客户端）。所以结论必须与依据一起显示 —— 否则它只是一句无法自证的话。
         */
        val headSame: Int? = null,
        /** 实际逐枚比了几枚（0 = 上一轮没存开头，只报位置、不猜落点）。 */
        val headCmp: Int? = null,
        /**
         * 断点落在被比那段的**哪一侧**（三档，判据在 native，这里只显示）：
         * `1` = 落在段内（被逐字节证实）、`0` = 判不了、`2` = 段全等且断点压在段尾。
         *
         * 为什么不是布尔：`0/1` 把"整段相等、断点在更后面"与"整段相等、断点**正好**
         * 在边界"并成一档，报告于是对后者说"已逐字节证实"，而它只证实了"这一段相同"
         * 那一半。真机 gemma4 的 144/144 正是这一档。
         */
        val forkInHead: Int? = null,
        /**
         * 本轮新算的**起点**（`/health` 的 `kv_slots[].start`；= 复用长度，0 = 从头）。
         *
         * 单看它没有任何意义（每轮都会是个合理的数），它的全部价值在**与
         * [startPrev] 相减**：差值 > 0 就是"已进 KV 的前缀被永久丢掉了一段"。
         */
        val start: Int? = null,
        /** 上一轮的新算起点（`kv_slots[].start_prev`；-1 = 上一轮没跑过，没有可比基线）。 */
        val startPrev: Int? = null,
        /**
         * 本轮**真正新算**的起点（`kv_slots[].start_new`；= 跳过的枚数，0 = 从第 0 枚算）。
         *
         * 与 [start] 是同一件事的两端：旧判据里"命中"要求本轮**以**上一轮那段开头，
         * 于是两者恒等、报一个就够。加上"跳过起点复用"（开头一样、只在里面从第 k 枚
         * 分开时，跳过前 k 枚继续用后面那段）之后，`start` 变成"复用了几枚"（可能两千多），
         * 而用户唯一能对上的数是"这一轮从第几枚开始算" —— 少了这一格，读数比现象
         * 大一个数量级，谁都对不上。
         */
        val startNew: Int? = null,
        /**
         * 这一轮的起点有没有**接上上一轮**（`kv_slots[].aligned`，判据在 native 的
         * `reusetrace::decision`）：`1` = 起点正好落在上一轮起点上（之前攒下的前缀可用）、
         * `0` = 这一轮重新决定了起点（接不上）。没丢头的轮次也是 `0`。
         *
         * ⚠ 这里**不报"为什么没接上"**。曾经有一版（`rediscover` 五档）从四个数里
         * 推成因（我们砍的 / 在漂 / 前面那段变了 / 模板在变），真机上那几档互相覆盖 ——
         * 它先后把"我们自己砍的"说成"对端的历史变了"、把"开头就变了"说成"回复没被
         * 带回来"，用户照着改了五轮客户端，一次也没对。读数能被数据直接回答；成因不能。
         */
        val aligned: Int? = null,
        /**
         * 想接上、但**额度不允许**而让掉的 token 数（`kv_slots[].align_reject`；
         * `0` = 接上了 / 没想接）。
         *
         * 它与 [promptTrimmed] **不共线**：那个说"prompt 超了多少"（容量问题），
         * 这个说"为了这一轮的回答放弃了多少本来可复用的前缀"（额度问题）——
         * 后者可以靠调大每格容量 / 调小 max_tokens 直接消掉。
         */
        val alignReject: Int? = null,
        /** 本轮注入块的字符数（`kv_slots[].inject_chars`；0 = 本轮没注入）。 */
        val injectChars: Int? = null,
        /**
         * 断点两侧的**几何**（`kv_slots[].diff_extra` / `diff_extra_len` /
         * `diff_extra_prev` / `diff_prev_head_at`）—— 四格全是**下标**，没有一格是推断。
         *
         * 前面那些格只回答"断在第几枚"，回答不了用户真正在问的那一句
         * 「它明明可以复用，为什么断在 152」：**没说是谁的字节变了**。
         * 于是每一次排查都走向服务端的参数（调 ctx、调 max_tokens、调 parallelN）。
         *
         *   · [diffExtra] / [diffExtraLen] —— 本轮 prompt 里夹带的额外内容（注入块等）
         *     落在 token `[first, first+len)`；`first = -1` = 本轮没夹带；
         *   · [diffExtraPrev] —— 同一段内容在**上一轮**的起点（`-1` = 上一轮没有）；
         *   · [diffPrevHeadAt] —— 上一轮 prompt 的**开头这一段**在本轮 prompt 里的首次
         *     出现下标：`-1` = 一处都找不到（历史换了一份）、`> 0` = 被挪到了后面、
         *     `= 0` = 还在最前面（那就不是"客户端换了历史"）；
         *   · [prevPrefixInCur] —— 上一轮**整段**账本在本轮逐枚相同几枚（`-1` = 没法量）。
         *     上一格在"本轮是上一轮的前缀"时**必然**报 `-1`（上一轮开头那 ≤512 枚在
         *     本轮一枚也放不下），只看它就会把"我们自己扔了开头"读成"两轮喂的不是
         *     同一份对话"（ISSUE #503）。这一格等于本轮 prompt 的长度时，那句话反过来读。
         */
        val diffExtra: Int? = null,
        val diffExtraLen: Int? = null,
        val diffExtraPrev: Int? = null,
        val diffPrevHeadAt: Int? = null,
        val prevPrefixInCur: Int? = null,
        /**
         * 那次比对**真的比过**上一轮账本开头多少枚（`kv_slots[].prev_head_span`）。
         *
         * 与 [headCmp] 分工，这是 ISSUE #503 真机第二轮把结论读反的那一格：
         * `head_cmp` 是"上一轮开头那 ≤512 枚在本轮找得到几次"的**步数** —— 上一轮开头
         * 整段还在本轮里时，第一次尝试就命中、循环当场 break，于是它等于**首次出现的
         * 位置**（真机 `head_cmp 200` / `473`，都是正常值）。拿它当"比过多少内容"，
         * 就会把"整段都比过了"读成"只比了一小截"，结论正好相反。
         * 覆盖范围只有一个来源：那一次比对真的比了多少枚。
         */
        val prevHeadSpan: Int? = null,
        /**
         * 本轮从第几枚起喂（`kv_slots[].tok_start`，**整段 prompt** 的坐标系 = 丢头之前）。
         *
         * 只在与 [startPrev] 并排时才读得出东西：相等 = 接上了上一轮；不等 = 起点又挪了。
         * 后者正是「水位线之后每轮全量 prefill」那条自锁 —— 单看任何一个都正常。
         */
        val tokStart: Int? = null,
        /**
         * 断点那一枚在**两侧**分别是什么（`kv_slots[].fork_prev_tok` / `fork_cur_tok`，
         * `-1` = 那一侧没有这一枚）。与上面那四格**同一格**取值。
         *
         * 上半截几何回答"断点落在哪个区间"，这两格回答"断点上那两枚 token 的 id 是什么"。
         * 真机上那句 `lcp 301 / reuse 301 / 两侧长度都是 2757` 里，`fork_why` 只能给出
         * 「两轮喂进去的不是同一份对话」—— 正确，但帮不上忙：它指不出**是谁的那一枚变了**。
         */
        val forkPrevTok: Int? = null,
        val forkCurTok: Int? = null,
        /**
         * 上一轮账本里 `[0, lcp)` 这段，本轮 prompt 里**还找得到几枚**
         * （`kv_slots[].elided_len`；`-1` = 没法量）。
         *
         * 它是"内容变了"与"这一轮少喂了一段"的**唯一**分界线：
         *   · 等于 `lcp` —— 那一段照旧在，断点在它之后（查断点之后的内容）；
         *   · 小于 `lcp` —— 上一轮那段在本轮**被换掉了**（客户端回传的历史变了，
         *     或者我们自己那一刀砍在了这里）。
         * 两种处置完全相反，而只有 `lcp` 的时候它们在读数上逐字相同。
         */
        val elidedLen: Int? = null,
        /**
         * 上一轮账本**存下来的开头**有多长（`kv_slots[].head_ledger_len`；0 = 没存）。
         *
         * 与 [headCmp] 成对读：`headCmp < headLedgerLen` = 这一轮没能把上一轮存的开头
         * **整段比完**（本轮 prompt 更短），那时报告里"断点在更后面"这句话不成立 ——
         * 它会把"没比过"说成"比过且相同"，而这正是 #330 栽过两次的那个坑。
         */
        val headLedgerLen: Int? = null,
        /**
         * native tokenize 出来的**真** token 数与客户端**估算**值（`prompt_real` / `prompt_est`）。
         *
         * 两个数各自都成立，**差值**才是那条窄带的宽度：预算按估算说"装得下、一条不丢"，
         * native 按真值说"超了"、从头部砍一刀 —— 而在此之前这条窄带在探活上完全看不见
         *（客户端报 `prompt_trimmed: 0`，native 没报同一个数，两边永远对不上）。
         */
        val promptReal: Int? = null,
        val promptEst: Int? = null,
        /**
         * 客户端这一轮判据用的**估算系数**（×1000；`/health` 的 `est_scale`；1000 = 未校准）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么 `prompt_est` 旁边必须多这一格（ISSUE #452 ⑤）
         * ══════════════════════════════════════════════════════════════════════
         * 校准之后 `prompt_est` 是**乘过系数**的数，于是同一个 `prompt_real 2649`
         * 可能配 `prompt_est 2825`（系数 1.0）或 `prompt_est 1977`（系数 0.7）——
         * 两行的差值一正一负，而差值的解读（"高估白丢历史" vs "低估砍了一刀"）
         * 正好相反。没有这一格，读的人只能看见"估算忽然变准了"，然后把一个
         * **我们自己改过的量**当成"估算口径修好了"的证据。
         */
        val estScale: Int? = null,
        /**
         * 每格容量（`/health` 的 `ctx_per_slot`；0 = 未加载，`null` = 服务端没报）。
         *
         * 「客户端要压到多少才不裁」这个问题只有它答得了：`ctx_per_slot - max_tokens`。
         * 用 `ctx_size`（总量）算会得到一个比实际大 `slots_total` 倍的数 ——
         * 那正是 #330 里「客户端已经压到 3070、服务端仍每轮被裁」的来源。
         */
        val ctxPerSlot: Int? = null,
        /**
         * **请求的**每格容量（`/health` 的 `ctx_per_slot_requested`；0 = 没加载）。
         *
         * 与 [ctxPerSlot] 不等 = 设置没照做（平台钳过 / 建份失败）。以前这两件事
         * 只有 [ctxSize] 与 [ctxActual] 这一对能答，而那一对是**总量**口径 ——
         * 份数一变，总量仍然可能相等，差值就消失了（真机：填 8192 两格、
         * 探活 12288 三格 4096，总量与每格全都"对不上"，却没有任何一对差值成立）。
         */
        val ctxPerSlotRequested: Int? = null,
        /**
         * **请求的** slot 数（`/health` 的 `slots_requested`；0 = 没加载 / 旧服务端）。
         *
         * 与 [slotsTotal] 不等 = 除不尽回落 / 某一份建失败 / 上限钳位。
         * 只说边界的 `slots_total` 答不了"为什么不是我要的那个数"，而那句话
         * 决定了用户接下来该改设置页还是该查内存。
         */
        val slotsRequested: Int? = null,
        /**
         * 顶上那几个 `kv_*` 读数取自哪一格（`/health` 的 `slot_in_use`；-1 = 冷启动）。
         *
         * ⚠ 语义改过（ISSUE #452 真机）：它以前**恒 0**（= `kv_slot`，§9.2.10 的语义归属），
         * 于是只用 HTTP 时那一屏是 `slot_in_use: 0 / kv_rounds: 0`，而同一份响应里
         * `kv_slots[1].rounds: 6` —— 两个数各自都合法，合起来是"缓存一次没生效"这句假话。
         * 现在它报的是"最近跑过 prefill 的那一格"，且顶上那五个数**按它取**：
         * `kv_slots[slot_in_use]` 与顶层必然逐格相等（冷启动仍退回 0）。
         */
        val slotInUse: Int? = null,
        /**
         * 上一轮真正进 KV 的那一格的容量（`/health` 的 `kv_last_ctx`；0 = 没有账本）。
         *
         * 与 [ctxPerSlot] 不同 = 「我以为的容量」与「它实际用的容量」分叉了。
         * 这是"算漏了、native 砍了"之外唯一能解释"读数明明装得下却被裁"的数。
         */
        val lastCtx: Int? = null,
        /**
         * **这一条 seq** 能排到的位置数（`/health` 的 `seq_ctx`，取有账本那一格；0 = 没量到）。
         *
         * ══════════════════════════════════════════════════════════════════
         * 为什么它与 [ctxPerSlot] / [lastCtx] 不是同一个数
         * ══════════════════════════════════════════════════════════════════
         * 一格里 App 与 HTTP **各占一条 seq**，而库里每条 seq 的容量是
         * `n_ctx / n_seq_max` —— 不是 `n_ctx`。真机那一份就是这一档：
         * `ctx_size 8192 / ctx_per_slot 4096`，而位置号只能排到 2047。
         * 于是 `prompt 2056` 的那一轮预算说装得下、prefill 说越界：
         * `[diag] FAIL: prefill decode rc!=0 at offset 2048` ——
         * 而每一格读数都合法（`prompt_trimmed 0` / `spare 1014` / `gen_cap 1024`，
         * 因为 `spare` 量的是整格）。
         *
         * 这一格把"两把尺子"变成并排两个数：它与 [ctxPerSlot] 不等 = 这一轮的
         * 容量被 seq 切过，客户端该压到多少要看**它**，不是整格。
         *
         * `null` = **没有这一格**：服务端没报（旧版本），或账本已作废 / 冷启动
         * （服务端报 0）。两者都不能拿默认值凑 —— 拿 `ctxPerSlot` 凑会得到
         * "两条线相等"这个从未被验证过的结论，于是那句越界告警**永远不出现**
         * （ISSUE #452 真机：那一屏报的是 `seq_ctx: 2`，连"没跑过"都看不出来）。
         */
        val seqCtx: Int? = null,
        /**
         * 本次加载开出的 slot 数（`/health` 的 `slots_total`）。
         *
         * 它只服务一个判据：**`ctx_size` 是否等于 `ctx_per_slot × slots_total`**。
         * 不相等就说明 `n_ctx` 在到达 native 之前被按份切了两次（ISSUE #330 真机：
         * 填 16000 + 2 路 → 探活 8192 → 每格 4096），那时"客户端该压到多少"
         * 没有正确答案，必须在报告里当场点出来而不是让用户自己乘。
         */
        val slotsTotal: Int? = null,
        /**
         * **嵌入模型**那份是否驻留（`/health` 的 `embed_loaded`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么探活报告要报"向量模型"这一格
         * ═══════════════════════════════════════════════════════════════════
         * 对话模型加载与否由 [modelLoaded] 那一行回答，可**嵌入模型是另一份**：
         * 双常驻的四种组合（只有对话 / 只有嵌入 / 都有 / 都没有）在 `model_loaded`
         * 上**长得一模一样**。而缺嵌入模型时，检索必然全空 —— 用户看到"知识库
         * 一条都搜不到"时，`model_loaded=true` 会把他引去查知识库，其实是
         * 嵌入那份没加载（`/v1/embeddings` 直接 503）。
         *
         * `null` = 服务端没报这个字段（旧版本），**不是** false。
         */
        val embedLoaded: Boolean? = null,
        /**
         * 嵌入模型的短描述（`/health` 的 `embed_model`；空串 = 未加载）。
         *
         * 与 [modelDesc] **分开**：`embed_model` 取的是**嵌入**那一份的描述，
         * 不是对话那份的复制品。两者可能不同名（比如对话用 4B、嵌入用 0.6B），
         * 只报一个时会让人以为"嵌入用的就是对话那个模型"。
         */
        val embedModelDesc: String? = null,
        /**
         * 嵌入向量维度（`/health` 的 `embed_dim`；0 = 未加载）。
         *
         * 它是**索引指纹的一部分**：维度变了说明换了嵌入模型，旧索引必须作废
         * （同 [embedBatchSizeIndex] 那条"指纹字段必须有可观测面"的口径）。
         * 与 [embedModelDesc] 并排时，"换了嵌入模型"才判得出来 —— 只看名字
         * 可能两份描述一样、而维度其实变了（或反过来）。
         */
        val embedDim: Int? = null,
        /**
         * 模型声明的**池化方式**（`/health` 的 `embed_pooling`；0=NONE/1=MEAN/2=CLS/
         * 3=LAST/-1=取不到）。
         *
         * 它是[embedDim]回答不了的那一半：CLS / LAST / MEAN 三者对同一份文本、
         * 同一个维度会给出**完全不同**的向量。而它已经是索引指纹的一部分
         * （`FpInput.pooling`）—— 也就是说"索引说池化方式变了"这句话此前在探活上
         * 一个数都没有，只能翻日志。与 [embedDim] 并排才判得出"换了嵌入模型"。
         */
        val embedPooling: Int? = null,
        /**
         * 输出侧维度（`/health` 的 `embed_out_dim`；`llama_model_n_embd_out`）。
         *
         * 与 [embedDim]（读取用的步长 `n_embd`）**不是同一个数**，差值才是根因：
         * 库按输出侧维度分配 output buffer，而读取按 `n_embd` 步长走 —— 两者不等时
         * "读一行"读到的是相邻 buffer（含未初始化的 NaN）。症状是"某些条归一化
         * 失败、且时好时坏"，任何"缺字段"判据都抓不住它：两个数各自都合法。
         */
        val embedOutDim: Int? = null,
        /**
         * 当前库**建索引时**用的块宽（`/health` 的 `embed_chunk_index`；-1 = 没索引）。
         *
         * 与 [embedBatchSizeIndex] 同一族：块宽是索引指纹的必比字段，改一次它
         * 整份索引就对不上、库静默不可检索。只有"当前配置"那一个数时，
         * "我改了块宽，为什么库不能用了"无从回答。
         */
        val embedChunkIndex: Int? = null,
        /**
         * 本会话**实际生效**的远端注入块宽（`/health` 的 `rag_inject_chars_set`）。
         *
         * 与 [injectChars] 分开：那是"**上一轮**那个块有多宽"（随命中的条目正文
         * 长短而变），这是"本会话定下来的宽度"（定了就不随并发数变）。前缀断在
         * 块头这件事的判据是后者 —— 拿前者去比，会因为"命中的条目不一样"而每轮
         * 都"变了"，与"宽度被重新算过"在读数上一模一样。
         */
        val ragInjectCharsSet: Int? = null,
        /**
         * 「向量逐位比对」的读数（ISSUE #269 第 5 步）。
         *
         * [embedParityVerdict] 是结论档（`identical`/`close`/`different`/`missing`，
         * 空串或 null = 还没跑过），[embedParityRows] 参与条数、[embedParityIdentical]
         * 其中逐位相同的条数。三个数回答的是**唯一决定"改批大小要不要重建索引"**
         * 的那条断言 —— 只在日志里说时用户要先动手翻日志，而结论是跨版本要对的一条。
         */
        val embedParityRows: Int? = null,
        val embedParityIdentical: Int? = null,
        val embedParityVerdict: String? = null,
        /**
         * 三档结论里"有 **几条**"真的不同。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"有不同"这一档还必须报**条数**
         * ═══════════════════════════════════════════════════════════════════
         * 真机读数（2026-10-09）是 `different`：**6 条样本里只有 1 条**不同。
         * 而"1/6"与"6/6"指向**完全相反**的结论：
         *
         *   · 6/6 -> 合批**普遍**改变了语义，批大小这条路整体不可信；
         *   · 1/6 -> 更像**某几条**踩到了某个边界（不等长合批的 padding、
         *     或超长那条的截断点），而其余条目其实是逐位相同的。
         *
         * 只有 [embedParityVerdict]（一档字符串）与 [embedParityIdentical]（相同条数）
         * 时，这两个数**说得出比例，说不出是哪一半** —— 于是"要不要收窄结论"只能靠猜，
         * 而本仓库的规矩是"判据要能落在具体一格上"。这里先给出**计数**（最小的一步）；
         * "是哪一条"要样本带名字，是后面的步。
         *
         * ⚠ `null` = 服务端没报这个字段（旧版本），**不是** 0：0 是"跑过了、没有不同"，
         * 与"没测过"方向相反（同 [embedParityRows] 的口径）。
         */
        val embedParityDifferent: Int? = null,
        /**
         * 「同路径重复」对照做没做（`embed_parity_repeat_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"有没有测"必须与"测出来几条不同"分开报
         * ═══════════════════════════════════════════════════════════════════
         * 真机首轮那条不同是 `firstDiffDim = 0`、`maxAbsDiff = 4.5e-03` —— 一条
         * 1024 维句向量的第 0 维差了四个数量级。这有两种互斥的根因：
         *
         *   · **批布局**改了语义（那是 `g` 进不进指纹的问题）；
         *   · **编码本身不确定**（同一条路连着编两次也不一样）—— 那批大小是次要问题。
         *
         * 分开这两件事的唯一读数是"同一条路重复编两次比不比得上"。而
         * `repeatNotIdentical == 0` **同时**是"全绿"与"根本没测"的读数 ——
         * 两者行动方向相反（后者该去测，前者才能收窄结论）。所以"测没测"
         * 必须单独一格（`false` = 没测，**不是**"全绿"）。
         */
        val embedParityRepeatMeasured: Boolean? = null,
        /**
         * 「同路径重复」里不是逐位相同的条数（`embed_parity_repeat_not_identical`）。
         *
         * ⚠ 读它之前**先看** [embedParityRepeatMeasured]：没测时它也是 0。
         */
        val embedParityRepeatNotIdentical: Int? = null,
        /**
         * 「**合批**那条路自己重复一遍」对照做没做（`embed_parity_repeat_batched_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么「逐条重复」那一格还不够
         * ═══════════════════════════════════════════════════════════════════
         * [embedParityRepeatMeasured] 只重复了**逐条**那条路。而"合批是纯加速
         * 旋钮"这个结论要成立，还差一格：**合批那条路自己**重复时是不是也逐位相同。
         *
         *   · 逐条自重复全绿、合批自重复**就有不同** -> **合批自身不确定**
         *     （同一批、同一台机、同一次前向都复现不了自己）—— 那时"跨路不同"
         *     里混着这一份，先说"差异来自批布局"就是把两个来源焊在一起；
         *   · 合批自重复也全绿 -> 两个"不确定性"都被排掉，只剩**跨路**差异
         *     （那才是批布局），这时"要不要收窄"才真的能谈。
         *
         * `false` = 没测（**不是** "全绿"）—— 与 [embedParityRepeatMeasured] 同一条理由。
         */
        val embedParityRepeatBatchedMeasured: Boolean? = null,
        /**
         * 「合批自己重复」里不是逐位相同的条数（`embed_parity_repeat_batched_not_identical`）。
         *
         * ⚠ 读它之前**先看** [embedParityRepeatBatchedMeasured]：没测时它也是 0。
         */
        val embedParityRepeatBatchedNotIdentical: Int? = null,
        /**
         * 上面那组逐位比对的读数**取自哪一组样本**（`embed_parity_group`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么"取自哪一组"必须报出来（而不是省成一个隐含前提）
         * ═══════════════════════════════════════════════════════════════════
         * 两组样本回答的是**两个不同的问题**，方向相反：
         *
         *   · **等长**组把"长度齐不齐"拿掉了，它全同只说明"批布局本身不改语义"
         *     —— 构造上偏稳；
         *   · **长短悬殊**组是唯一能改变答案的那组（含超长 / 截断）。
         *
         * 真机现场（PR #301）把它暴露出来了：报告里一行「逐位相同 4/4 条 ——
         * 整批逐位相同，批大小是纯加速旋钮（可考虑沿用）」取自**等长**组，而
         * 同一份日志里长短悬殊是「真的不同 1 条、`4.510e-03`」。两个读数同时
         * 成立，可读者拿到的结论恰好是**用构造上偏稳的那组去回答"会不会变"**
         * —— 而这格存在的全部理由就是判「`g` 能不能按库放行」。
         *
         * 于是报告里必须写清"这组读数取自哪一组"，且**当它取自等长组时不许
         * 把结论说成"批大小是纯加速旋钮"**（那会被读成长短悬殊也稳）。
         * `null` = 服务端没报这个字段（旧版本）：不猜，只按原来的说法报数字。
         */
        val embedParityGroup: String? = null,
        /**
         * 「**换批大小重建索引后，检索结果还是不是同一些行**」对照做没做
         * （`embed_retrieve_measured`）。
         *
         * ═══════════════════════════════════════════════════════════════════
         * 为什么这一格与上面那些**向量**读数不是同一件事
         * ═══════════════════════════════════════════════════════════════════
         * [embedParityDifferent] 那几个报的是**向量**（逐条编 vs 合批编差几个分量）。
         * 而用户真正要问的是**另一句**："换批大小重建索引后，我搜出来的还是不是同几条"。
         * 这两句之间**没有蕴含关系**：
         *
         *   · 向量差 4e-03 **不等于**答案会变 —— 那点差落在第几名上，是要量的；
         *   · 向量逐位相同也**不等于**答案一样（索引里还有正文行序、切分、分数比较）。
         *
         * 所以这一格是**独立**的一格：同一批文本按两种批大小各编一套向量、
         * 同一组查询互查，比"命中的行下标集合"。没有它时，"向量有差异"会被
         * 顺手读成"检索结果也会不一样" —— 一个**推出来的**结论，而它恰好是
         * "`g` 要不要按库放行"那条决定的全部依据。
         *
         * `false` = 没测（**不是** "答案一样"）—— 与 [embedParityRepeatMeasured] 同源：
         * 没测时 [embedRetrieveAnswerChanged] 也是 0，而"没测"该去测。
         */
        val embedRetrieveMeasured: Boolean? = null,
        /**
         * 参与对照、且**两侧都搜到了东西**的查询条数（`embed_retrieve_queries`）。
         *
         * ⚠ 两侧都搜不到的查询**不算**（那是"没量到"，把它算成"一致"是把
         * 没有读数洗成一个结论）。
         */
        val embedRetrieveQueries: Int? = null,
        /**
         * 命中**集合**变了的查询条数（`embed_retrieve_answer_changed`）。
         *
         * >0 = 换批大小换了**答案** -> `g` 不能按库放行（哪怕向量只差 4e-03）。
         *
         * ⚠ 读它之前**先看** [embedRetrieveMeasured]：没测时它也是 0。
         */
        val embedRetrieveAnswerChanged: Int? = null,
        /**
         * 命中集合相同、只是**名次**变了的查询条数（`embed_retrieve_order_changed`）。
         *
         * 与 [embedRetrieveAnswerChanged] 分开：名次变 = 打平处换序（可能是
         * "索引里混了两种语义"的早期信号，但不是"答案变了"）—— 两者处置不同。
         */
        val embedRetrieveOrderChanged: Int? = null,
        /** 对照比的是哪两种批大小（`embed_retrieve_batch_base` / `_other`；0 = 没测）。 */
        val embedRetrieveBatchBase: Int? = null,
        val embedRetrieveBatchOther: Int? = null,
        /** 检索对照取前几名（`embed_retrieve_topk`；0 = 没测）。 */
        val embedRetrieveTopk: Int? = null,
        /** 连接/读超时等技术性失败的原因（未收到 HTTP 响应行时才有值）。 */
        val transportError: String?,
        val elapsedMs: Long,
        /**
         * 响应体是否是合法 JSON。HTTP 200 但正文不是 JSON，说明**应答的不是本服务**
         * （常见：同一端口被别的进程占了、或中间有反代/门户页回了 200 的 HTML）。
         * 这种情况必须算「不可达」，不能因为看见了 200 就报存活。
         */
        val jsonParsed: Boolean = false,
        /**
         * 服务端**自己报**的拼装错误（`/health` 回 `{"error":{"message":"health json
         * malformed: ..."}}` 时那一句；`null` = 没报）。
         *
         * ⚠ 这一格不能只塞进 [transportError]：那条路要求"正文不是 JSON"，而这份
         * 自报错的**外壳本身是合法 JSON** —— 于是它走的是 `jsonParsed=true` 那一路，
         * 判定成「可达、状态不完整」，报告里一个字都不提"服务端拼错了"。
         * 真机上用户只能看到 `status` 为空，方向整个跑到客户端参数上（#392 第二回）。
         * 所以单列一格，报告里单说一行。
         */
        val serverSelfError: String? = null,
    )

    /** 探测默认超时：本机回环上的 /health 是毫秒级，给到 3s 已经非常宽松。 */
    const val DEFAULT_TIMEOUT_MS = 3000

    /**
     * 报告里回显正文的上限（字符）。
     *
     * 做成常量而不是写死的字面量：**截断必须被报告自己说明**（见 [format]），
     * 而"是否截断"的判据与 [format] 用的是同一个数 —— 两处各写一个数，
     * 改了一处就会出现"实际截了却不说"或"没截却说截了"。
     *
     * 值取 4096：/health 的正文约 **221 字符**，典型上限（512/1024）都在它之上；
     * 而"灌爆结论区"的真实来源是一整页 HTML（连到了别的进程），那是几十 KB 量级，
     * 4 KB 一样拦得住。上限存在的理由是拦 HTML，不是省几行字，
     * 所以它必须**明显大于本服务自己的响应**——否则常态响应一进来就被切，
     * 而切出来的残片正好落在一堆合法 JSON 上（本函数的警告语就挂在里面的
     * "响应体不是合法 JSON"），读者会把它当成解析器的结论去反推服务端。
     */
    const val BODY_PREVIEW_CHARS = 4096

    /**
     * 组装探测用的 HTTP 请求报文。
     *
     * 用 **HTTP/1.0** 而不是 1.1：服务器（HttpApi）按 `Connection: close` 处理，
     * 1.0 的默认语义就是短连接，读端能靠 EOF 判定结束，不必依赖 Content-Length
     * 解析正确——探测自己差一点反而会得出错误结论。
     */
    fun buildRequest(port: Int, path: String = "/health"): String =
        "GET $path HTTP/1.0\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Accept: application/json\r\n" +
            "Connection: close\r\n\r\n"

    /**
     * 解析响应：状态行 → body → /health 的 JSON 字段。
     * 任何一步出问题都不抛异常，只留下 null 字段与 [Result.transportError] 说明原因。
     */
    fun parse(raw: String, elapsedMs: Long, transportError: String? = null): Result {
        if (raw.isBlank()) {
            return Result(httpCode = null, body = "", status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError ?: "未收到任何响应数据", elapsedMs = elapsedMs)
        }
        // 状态行：`HTTP/1.1 200 OK`。拿不到就不是合法 HTTP 响应，
        // 但仍把原文留在 body 里——"连上了但对方不是 HTTP 服务"是常见误配。
        val lineEnd = raw.indexOf("\r\n").let { if (it >= 0) it else raw.indexOf('\n') }
        if (lineEnd <= 0) {
            return Result(httpCode = null, body = raw.trim(), status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError ?: "响应不含 HTTP 状态行", elapsedMs = elapsedMs)
        }
        val statusLine = raw.substring(0, lineEnd).trim()
        val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
        if (code == null) {
            return Result(httpCode = null, body = raw.trim(), status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = "状态行无法解析: ${statusLine.take(80)}", elapsedMs = elapsedMs)
        }
        val bodyStart = findBodyStart(raw, lineEnd)
        val body = if (bodyStart >= raw.length) "" else raw.substring(bodyStart).trim()

        // 非 200：本服务的错误响应体是 {"error":{...}}，与 /health 是两种形状，
        // 所以不在这里取 status / model_loaded 字段。但**仍然要判定正文是不是 JSON**——
        // 这正是"服务确实应答了（503/404）"与"端口上根本不是本服务"的分水岭。
        if (code != 200) {
            val isJson = try { org.json.JSONObject(body); true } catch (_: Throwable) { false }
            return Result(
                httpCode = code, body = body, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportError, elapsedMs = elapsedMs, jsonParsed = isJson,
            )
        }
        return parseHealthBody(code, body, elapsedMs, transportError)
    }

    /** 找到响应头结束、正文开始的位置；\r\n\r\n 优先，容错裸 \n\n。 */
    private fun findBodyStart(raw: String, searchFrom: Int): Int {
        val crlf = raw.indexOf("\r\n\r\n", searchFrom)
        if (crlf >= 0) return crlf + 4
        val lf = raw.indexOf("\n\n", searchFrom)
        if (lf >= 0) return lf + 2
        return raw.length
    }

    private fun parseHealthBody(code: Int, body: String, elapsedMs: Long, err: String?): Result {
        val j = try {
            org.json.JSONObject(body)
        } catch (_: Throwable) {
            // ⚠ 「正文不是合法 JSON」有**两个方向**，措辞必须分开（#384 真机）：
            //   · 端口上不是本服务（别人的 HTML / 反代门户页）-> 判不可达，原文就是证据；
            //   · **本服务自己说正文坏了**：`/health` 拼完会做一次 `JSONObject` 自检，
            //     失败时回**200** + 一条 `health json malformed: ...`（#384 贴回来的
            //     正是这一条）。这时服务是活的、病在服务端，"不可达"会把排查方向
            //     整个带偏 —— 而报告末尾那句"故判定为不可达"又是斩钉截铁的语气。
            // 判据用**正文自己**说没说这句话，不在这里另编一套：
            // 它就在服务端那一处 `errJson(...)` 里，两处措辞分叉的那天开始，
            // 这里就会把"服务端报错"重新说成"连错了服务"。
            val selfReport = body.contains("health json malformed")
            val why = if (selfReport)
                "响应体不是合法 JSON —— 且正文说这是服务端自己拼错了（服务可达，病在服务端）"
            else
                "响应体不是合法 JSON"
            return Result(httpCode = code, body = body, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = (err?.plus("; ") ?: "") + why,
                elapsedMs = elapsedMs)
        }
        // 逐字段 opt：/health 是跨版本接口，缺字段要报缺、不能当默认值。
        // 用 isNull 判断，避免 JSONObject.NULL 被 optString 变成 "null" 这种脏值。
        fun str(k: String): String? = if (j.isNull(k)) null else j.optString(k, "")?.ifEmpty { null }
        fun int(k: String): Int? = if (j.isNull(k)) null else j.optInt(k, Int.MIN_VALUE)
            .let { if (it == Int.MIN_VALUE) null else it }
        fun bool(k: String): Boolean? = if (j.isNull(k)) null else j.optBoolean(k, false)
        // long 与 int 分开：冻结毫秒数在大周期上不会溢出 Int，但"累计"会随时间线性增长，
        // 用 Int 会得到一个静默回绕的负数 —— 那是"数字看着有、其实在说谎"，比不报更糟。
        fun long(k: String): Long? = if (j.isNull(k)) null else j.optLong(k, Long.MIN_VALUE)
            .let { if (it == Long.MIN_VALUE) null else it }
        // 服务端自报的拼装错误（`errJson` 那条路）。只认**带那句措辞**的 error 体，
        // 别的 `{"error":...}` 是这一次请求的业务错误，不是"服务端拼错了"。
        val selfErr = j.optJSONObject("error")?.optString("message")?.takeIf {
            it.contains("health json malformed")
        }
        return Result(
            jsonParsed = true,
            serverSelfError = selfErr,
            httpCode = code,
            body = body,
            status = str("status"),
            modelLoaded = bool("model_loaded"),
            modelDesc = str("model"),
            ctxUsed = int("ctx_used"),
            ctxSize = int("ctx_size"),
            ctxActual = int("ctx_actual"),
            busy = bool("busy"),
            port = int("port"),
            frozenMs = long("frozen_ms"),
            frozenCount = int("frozen_count"),
            frozenTotalMs = long("frozen_total_ms"),
            restartPending = bool("restart_pending"),
            // 合批六个读数。⚠ 前五个用 `long` 而**不是** `int`：它们是**累计**量，
            // 随时间线性增长，用 Int 会得到一个静默回绕的负数 —— 而那是"数字看着有、
            // 其实在说谎"，比不报更糟（与 `frozen_total_ms` 同一条理由）。
            embedBatchBatches = long("embed_batch_batches"),
            embedBatchEntries = long("embed_batch_entries"),
            embedBatchFallbackBatches = long("embed_batch_fallback_batches"),
            embedBatchFallbackEntries = long("embed_batch_fallback_entries"),
            embedBatchMs = long("embed_batch_ms"),
            embedBatchSize = int("embed_batch_size"),
            embedBatchSizeIndex = int("embed_batch_size_index"),
            embedBatchSizeIndexKb = str("embed_batch_size_index_kb"),
            embedCtxPerSeq = int("embed_ctx_per_seq"),
            embedCtxPerSeqLive = int("embed_ctx_per_seq_live"),
            embedSeqMax = int("embed_seq_max"),
            embedSeqMaxLive = int("embed_seq_max_live"),
            embedGate = int("embed_gate"),
            embedGateLive = int("embed_gate_live"),
            // ⚠ `embed_ctx_total` / 四个 `_set` 布尔已按 #392 从 `/health` 删掉，
            // 这里同步不再解析 —— 留着读法只会让"缺字段"报个不停，而它们本来就没信息量：
            // 前者恒等于编译期常量，后者问的是"用户碰过没有"，后者由 `_live` 回答。
            embedCtxTotalLive = int("embed_ctx_total_live"),
            // 上一轮那一刀（预算落值）。取的是 `kv_slots` 里"有账本的那一格"——
            // 只用 HTTP 时请求落在 slot 1，读顶层会恒为 0（与 kv_reuse_tokens 同一个坑）。
            kvSlotMissingKeys = slotMissingKeys(j),
            promptTrimmed = kvSlotMax(j, "prompt_trimmed"),
            genTrimmed = kvSlotMax(j, "gen_trimmed"),
            // `spare` 与上面两格同一族（都是"上一轮预算的落值"），取法也同一格。
            spare = kvSlotMax(j, "spare"),
            // 水位两格（ISSUE #426）：`drop_room` 才是「丢一次 vs 每轮都丢」的分界线
            //（`spare` 量的是整格，水位 85% 那轮它还有几百枚而 `drop_room` 已是 0）；
            // `drop_overshoot` 回答"多丢的那一截换回了什么"。
            // ⚠ 走 `kvSlotMax` 会让"多格时取最大"落在**最松**的那一路（余量最大的那格看起来
            //   最健康），而这里要的是"最需要看的那一刀" —— 与 `prompt_trimmed` 同一取舍：
            //   0 与 0 之间取谁都一样，非 0 时取大的那个才是贴着水位的证据。
            ctxWatermark = kvSlotMax(j, "ctx_watermark"),
            dropRoom = kvSlotMax(j, "drop_room"),
            dropOvershoot = kvSlotMax(j, "drop_overshoot"),
            // 「这一轮为什么停」的三笔账（`gen_planned / n_rem / stop_wall`）。
            // 与 `prompt_trimmed` 同一族（上一轮的事实落值，与 `fork_why` 无关）——
            // 所以走 `kvSlotMax`：**命中**的轮次里 `fork_why` 是空串，走结论族那一趟
            // 会恒为 null，而"它本来还能说多少字"最该被看的时候正是那些轮次。
            genPlanned = kvSlotMax(j, "gen_planned"),
            genRem = kvSlotMax(j, "n_rem"),
            genWall = kvSlotMax(j, "stop_wall"),
            // `pos_gap`（`spare - stop_wall`）不在这里读：服务端按 #392 已不再单发一格
            // （它本来就是上面两格的减法，而那两个数在报告里就在同一屏上）。
            // 派生挪到 `Result` 构造**之后**统一做（见 `withDerived`）。
            // 断点落在哪一截。取 `kv_slots` 里**第一格非空**的那句（`kvSlotMax` 是取最大，
            // 而这里要的是"有账本的那一路说的话"，所以单独走一趟）。
            //
            // ⚠ 依据三格与 `fork_why` **同一格**（`slotWithWhy`），不许各取各的：
            //   原来它们走 `kvSlotMax`（取最大），而 `fork_why` 走"第一格非空" ——
            //   只用 HTTP、请求落在 slot 1 时，`slot 0` 的 `fork_why` 和 `head_cmp`
            //   来自**上一轮的另一个槽**，于是报告拼出一句"（上一轮没存开头：
            //   只报位置，落点未证实）"，而 slot 1 明明是逐字节证实的 —— 读数在
            //   撒谎，方向还正好是"别信这句"。ISSUE #330 真机四次误判，两次是它。
            forkWhy = kvSlotWithWhy(j, "fork_why"),
            headSame = kvSlotWithWhyInt(j, "head_same"),
            headCmp = kvSlotWithWhyInt(j, "head_cmp"),
            forkInHead = kvSlotWithWhyInt(j, "fork_in_head"),
            // 「起点有没有漂」与「估算 vs 真 token」（ISSUE #330 第 4 块）。与上面那几格
            // **同一格**（`slotWithWhy`），理由相同：两格各取各的会拼出"slot 0 的结论 +
            // slot 1 的依据"这种从未同时成立过的组合，而它看起来完全正常。
            start = kvSlotWithWhyInt(j, "start"),
            startPrev = kvSlotWithWhyInt(j, "start_prev"),
            // 与上面几格**同一格**取值（理由同上）：它和 `start` 是一对，跨格拼就成
            // "复用了 A 格的枚数、新算起点取 B 格"这种从未同时成立过的组合。
            startNew = kvSlotWithWhyInt(j, "start_new"),
            aligned = kvSlotWithWhyInt(j, "aligned"),
            alignReject = kvSlotWithWhyInt(j, "align_reject"),
            injectChars = kvSlotWithWhyInt(j, "inject_chars"),
            // 断点几何：与 `fork_why` **同一格**（`slotWithWhy`），理由同上面那几格 ——
            // 结论与它的依据一旦取自两个槽，就会拼出一句从未同时成立过的话。
            diffExtra = kvSlotWithWhyInt(j, "diff_extra"),
            diffExtraLen = kvSlotWithWhyInt(j, "diff_extra_len"),
            diffExtraPrev = kvSlotWithWhyInt(j, "diff_extra_prev"),
            diffPrevHeadAt = kvSlotWithWhyInt(j, "diff_prev_head_at"),
            prevPrefixInCur = kvSlotWithWhyInt(j, "prev_prefix_in_cur"),
            prevHeadSpan = kvSlotWithWhyInt(j, "prev_head_span"),
            tokStart = kvSlotWithWhyInt(j, "tok_start"),
            // 断点那两枚 token 与「上一轮那段在本轮还找得到几枚」（ISSUE #452 真机）。
            // 与 `fork_why` / 几何四格**同一格**取值（`slotWithWhy`）：它们是同一句话的
            // 依据，"结论取 A 格、依据取 B 格"会拼出从未同时成立过的组合。
            forkPrevTok = kvSlotWithWhyInt(j, "fork_prev_tok"),
            forkCurTok = kvSlotWithWhyInt(j, "fork_cur_tok"),
            elidedLen = kvSlotWithWhyInt(j, "elided_len"),
            headLedgerLen = kvSlotWithWhyInt(j, "head_ledger_len"),
            // ⚠ 这两格**不能**走 `kvSlotWithWhyInt`：那个取值函数要求该格有 `fork_why`
            //   （它是"断点结论"那一族的锚）。而 `prompt_real` / `prompt_est` 在**命中**
            //   的轮次里也有值、`fork_why` 却是空串 —— 走同一个取值函数就会恒为 null，
            //   于是这两格在"复用正常"的场次里永远不出现，而它们最该被看的时候正是那些场次
            //   （"没命中是因为我们砍了一刀"与"没命中是因为本来就不同"都发生在命中率掉下去时，
            //   但更早一步问的是"估算准不准"）。取"第一格有值的那一格"。
            promptReal = kvSlotFirstInt(j, "prompt_real"),
            promptEst = kvSlotFirstInt(j, "prompt_est"),
            estScale = kvSlotFirstInt(j, "est_scale"),
            // 每格容量与「读哪一格」（ISSUE #330 真机复盘第三回合）。三个都取顶层：
            // 它们本身就是"跨 slot 的结论"（每格多大 / 哪一格在跑 / 那一格多大），
            // 按格再取一遍只会得到 N 份相同的数。
            ctxPerSlot = int("ctx_per_slot"),
            // 请求的每格 / 请求的份数（ISSUE #426 第七回合）：与上面那两个实际值
            // 成对读。只报实际值时，「我填 8192 + 2 格，探活 12288 + 3 格」这件事
            // 在报告里没有任何一处能指认 —— 因为每个数各自都是"某个量"的真值。
            ctxPerSlotRequested = int("ctx_per_slot_requested"),
            slotsRequested = int("slots_requested"),
            slotsTotal = int("slots_total"),
            slotInUse = int("slot_in_use"),
            lastCtx = int("kv_last_ctx"),
            // 预算按**哪条线**判的（这一条 seq 的容量）。取 `kv_slots` 里**有账本
            // 那一格**的 `seq_ctx`（同一个取口：只用 HTTP 时请求落在 slot 1，
            // 读顶层会得到 0 —— 与 `prompt_trimmed` 那几格是同一个坑）。
            //
            // ⚠ 取的是**账本有效**那一格（ISSUE #452 真机）：`kvSlotFirstInt` 会取
            //   "第一个有值的格子"，而冷启动 / 账本作废那一格以前报的是 Session 初值 2，
            //   于是 `ctx: 4096` 配 `seq_ctx: 2` 同屏。0 = 没有这一格 —— 报告据此说
            //   "没跑过 / 账本已作废"，不再拿一个像容量的假数去解释容量。
            seqCtx = kvSlotFirstInt(j, "seq_ctx")?.takeIf { it > 0 },
            // 向量模型那三格（对话那份由 model_loaded/model 回答，这里回答**嵌入**那份）：
            // 缺嵌入模型时检索必然全空，而它在 model_loaded 上看不出来。
            embedLoaded = bool("embed_loaded"),
            embedModelDesc = str("embed_model"),
            embedDim = int("embed_dim"),
            embedPooling = int("embed_pooling"),
            embedOutDim = int("embed_out_dim"),
            embedChunkIndex = int("embed_chunk_index"),
            ragInjectCharsSet = int("rag_inject_chars_set"),
            // 逐位比对读数（ISSUE #269 第 5 步）。`rows/identical` 用 `int` 而不是
            // `long`：它们**不是**累计量（每次对照覆盖），条数量级在个位到几十，
            // 不存在回绕问题（与合批那五个累计量不同）。
            embedParityRows = int("embed_parity_rows"),
            embedParityIdentical = int("embed_parity_identical"),
            embedParityVerdict = str("embed_parity_verdict"),
            embedParityDifferent = int("embed_parity_different"),
            embedParityRepeatMeasured = bool("embed_parity_repeat_measured"),
            embedParityRepeatNotIdentical = int("embed_parity_repeat_not_identical"),
            embedParityRepeatBatchedMeasured = bool("embed_parity_repeat_batched_measured"),
            embedParityRepeatBatchedNotIdentical = int("embed_parity_repeat_batched_not_identical"),
            embedParityGroup = str("embed_parity_group"),
            // 检索对照（与上面那组**配对**的另一半 —— 向量那几格答不了"答案变没变"）。
            embedRetrieveMeasured = bool("embed_retrieve_measured"),
            embedRetrieveQueries = int("embed_retrieve_queries"),
            embedRetrieveAnswerChanged = int("embed_retrieve_answer_changed"),
            embedRetrieveOrderChanged = int("embed_retrieve_order_changed"),
            embedRetrieveBatchBase = int("embed_retrieve_batch_base"),
            embedRetrieveBatchOther = int("embed_retrieve_batch_other"),
            embedRetrieveTopk = int("embed_retrieve_topk"),
            transportError = err,
            elapsedMs = elapsedMs,
        ).withDerived()
    }

    /**
     * 把**派生读数**从已有那几格算出来（#392 收口时立的口径）。
     *
     * 只有一条：`posGap = spare - stopWall`。
     * 它以前是服务端单发的一格（`/health` 的 `pos_gap`），可它就是这两个数的减法 ——
     * 而这两个数在报告里就在同一屏上。**派生量最坏的地方是它长得像结论**：
     * 读的人会拿它当"位置被谁吃了"的答案，而它只是另两个数相减。
     *
     * 两侧任一格是 `null`（服务端没报 / 旧版本）就保持 `null` ——
     * 不拿默认值凑一个差额，这是这份文件的通例（`null` = 没报，不用默认值冒充）。
     */
    private fun Result.withDerived(): Result {
        if (posGap != null) return this
        val s = spare ?: return this
        val w = genWall ?: return this
        return copy(posGap = s - w)
    }

    /**
     * `kv_slots` 每格**缺了哪几个契约字段**（`null` = 服务端压根没报这个数组）。
     *
     * 契约来自 `/health` 的读法注释：每格是
     * `{i,loaded,used,valid,reuse,prefill,rounds,lcp}` + 账本/归属那半截。
     * #384 真机只发了后半截，而**缺字段在别处从不报缺**（`optInt` 静默给默认值），
     * 于是"这一格 0 轮"与"服务端没发这一格"读数完全相同 —— 用户查了半天参数。
     *
     * ⚠ 原来只查**第一格**，理由是"多格之间形状必然一致（同一个 `mapIndexed` 拼出来的）"。
     *   那个理由在**同一版本**内成立，跨版本不成立 —— 而这里判的正是"服务端这一版漏了
     *   哪几格"。更直接的坑：§11.7 起只用 HTTP 时跑的是 slot 1，只看 slot 0 等于
     *   没看真正在跑的那一路。所以逐格查、按格号报（缺法相同的格合一句）。
     * 空数组（`[]` = 没加载模型）不算缺；某一格不是对象（形状坏了）也要报。
     */
    private fun slotMissingKeys(j: org.json.JSONObject): List<String>? {
        val arr = j.optJSONArray("kv_slots") ?: return null
        val miss = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) { miss.add("[${i}] 不是 JSON 对象"); continue }
            miss.addAll(KV_SLOT_CONTRACT.filter { !o.has(it) }.map { "[$i] $it" })
        }
        return miss
    }

    /**
     * `kv_slots` 每一格的**契约字段**（`/health` 的按 slot 读数）。
     *
     * ⚠ 与 HttpApi 里那段注释是同一份定义：那边承诺"每格是这八个 + 属性"，
     * 这边据此判"发没发"。两处一旦分叉，报告会说"不缺"而读的人照样取不到值 ——
     * 所以这句判据由 `tools/run_health_json_guard.py` 与源码那一侧一起钉。
     */
    // 末两格是**归属**读数（`kvAttributionOf` 的 `last_gen_cap` / `last_ctx`）：
    // 只有它们能回答这一轮为什么被掐断（配合 `finish_reason=length` 读）。
    // 缺了不会报错，只会让那句「只给了你 N 个生成位置」永远说不出来。
    // 末三格是「这一轮**为什么停**」的三笔账（`LlmEngine.genStopOf`）：
    //   · `gen_planned` 调用方要的 / `n_rem` 预算给的 / `stop_wall` 位置还够多少；
    //   · 实际能生成 `min(n_rem, stop_wall)` 枚，谁最小谁就是那一轮断掉的原因。
    // 只报 `gen_cap` 时三种处境（只要这么多 / 预算削的 / 位置不够）逐字相同 ——
    // 真机那条「请求 1024、实际 99、KV 还空着 1618 格」就是被它盖住的。
    // 缺了不会报错，只会让那句「它本来还能说多少字」永远说不出来。
    // 末两格是**位置那条线**（ISSUE #426 真机 `decode rc!=0 at offset 2048`）：
    //   · `ctx`     = 这一格的 `n_ctx`（整格）；
    //   · `seq_ctx` = **这一条 seq** 能排到的位置数（`n_ctx / n_seq_max`）。
    // 两者不等就说明这一轮的容量被 seq 切过 —— 而"预算按整格放行、prefill 按每条
    // seq 排号"这类冲突，只有这两个数摆在一起才解得开。
    // #392 第五回合：这份清单原来只列 14 格，而上面 `parseHealthBody` 实际读了 30+ 格 ——
    // 于是"报告说 kv_slots 不缺字段"这件事**本身不可信**：真缺了也不会报缺（与 #384 同形，
    // 只是那次是发端漏了，这次是清单没跟上）。清单必须与读法逐格对齐，
    // 且由 `tools/run_health_json_guard.py` 反向钉住（读了的每一格都得在清单里 + 发得出）。
    private val KV_SLOT_CONTRACT =
        listOf("i", "loaded", "used", "valid", "reuse", "prefill", "rounds", "lcp",
            "gen_cap", "ctx", "seq_ctx", "gen_planned", "n_rem", "stop_wall",
            "prompt_trimmed", "gen_trimmed", "spare", "ctx_watermark", "drop_room",
            "drop_overshoot", "fork_why", "head_same", "head_cmp", "fork_in_head",
            "start", "start_prev", "start_new", "aligned", "align_reject", "inject_chars",
            "prompt_real", "prompt_est", "est_scale",
            "diff_extra", "diff_extra_len", "diff_extra_prev", "diff_prev_head_at",
            // ISSUE #503：上一轮整段在本轮逐枚相同几枚（几何第五格）。少了它，
            // "我们自己扔了开头"与"对端换了一份对话"在报告里同形 —— 处置正相反。
            "prev_prefix_in_cur",
            // ISSUE #503 真机第二轮：覆盖范围（真比过的枚数，不是步数 `head_cmp`）与本轮起点。
            // 少前者，"整段都比过"会被读成"只比了一小截"；少后者，起点每轮往后滑看不出来。
            "prev_head_span", "tok_start",
            "fork_prev_tok", "fork_cur_tok", "elided_len", "head_ledger_len")

    /**
     * `kv_slots` 里某一格的最大值（`/health` 的按 slot 读数）。
     *
     * 为什么取**最大**而不是 `[0]`：顶层那五个 `kv_*` 恒定读 slot 0，而只用 HTTP 时
     * 请求落在 slot 1（slot 0 保留给 App 内聊天）—— 读 `[0]` 会把"这一轮确实裁过"
     * 报成 0，方向正好反（与 `kv_reuse_tokens` 同一个坑，见 HttpApi 里那段读法）。
     * 预算落值是"**上一轮**那一刀"，多格时取最大即"最需要看的那一刀"。
     *
     * `null` = 服务端没报这个字段（旧版本）—— 与上面逐字段 opt 同一条口径：
     * 缺字段要报缺，不能给默认值。
     */
    private fun kvSlotMax(j: org.json.JSONObject, key: String): Int? {
        val arr = j.optJSONArray("kv_slots") ?: return null
        var best: Int? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has(key) || o.isNull(key)) continue
            val v = o.optInt(key, Int.MIN_VALUE)
            if (v == Int.MIN_VALUE) continue
            if (best == null || v > best) best = v
        }
        return best
    }

    /**
     * `kv_slots` 里**第一格有 `fork_why`** 的那一格的某个字段。
     *
     * `fork_why` / `head_same` / `head_cmp` / `fork_in_head` 是**同一次未命中**的
     * 结论与依据，必须出自同一格：一个按"第一格非空"取、另几个按"最大"取，就必然
     * 出现"结论来自 slot 1、依据来自 slot 0"—— 报告于是说"落点未证实"，而真相是
     * 逐字节证实过。这一格只回答"那句话的依据是什么"，不回答"哪一格在跑"
     *（后者看 `kv_slots[i].rounds`）。
     */
    private fun slotWithWhy(j: org.json.JSONObject): org.json.JSONObject? {
        val arr = j.optJSONArray("kv_slots") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val why = o.optString("fork_why", "")
            if (why.isEmpty()) continue
            return o
        }
        return null
    }

    /** `slotWithWhy` 的字符串取值（`fork_why` 本身就是字符串）。 */
    private fun kvSlotWithWhy(j: org.json.JSONObject, key: String): String? {
        val o = slotWithWhy(j) ?: return null
        val v = o.optString(key, "")
        return v.ifEmpty { null }
    }

    private fun kvSlotWithWhyInt(j: org.json.JSONObject, key: String): Int? {
        val o = slotWithWhy(j) ?: return null
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.optInt(key, Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) null else v
    }

    /**
     * `kv_slots` 里**第一格有值**的那个整数字段（`null` = 服务端没报 / 全为空）。
     *
     * 与 [kvSlotWithWhyInt] 的区别只在**判据**：那个要求该格有 `fork_why`（结论族），
     * 这个只要求字段存在且非零（读数族）。混用会让"读数族"在没结论的轮次里恒空 ——
     * 而那一半场次恰好是"一切正常、估算准不准还看不出来"的时候。
     */
    private fun kvSlotFirstInt(j: org.json.JSONObject, key: String): Int? {
        val arr = j.optJSONArray("kv_slots") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has(key) || o.isNull(key)) continue
            val v = o.optInt(key, 0)
            if (v > 0) return v
        }
        return null
    }

    /** `kv_slots` 里**第一格非空**的那个字符串字段（`null` = 服务端没报 / 全为空）。 */
    private fun kvSlotFirstStr(j: org.json.JSONObject, key: String): String? {
        val arr = j.optJSONArray("kv_slots") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.has(key) || o.isNull(key)) continue
            val v = o.optString(key, "")
            if (v.isNotEmpty()) return v
        }
        return null
    }

    /** 判定结果，供 UI 一句话回显与着色。 */
    enum class Verdict { ALIVE, DEGRADED, UNREACHABLE }

    /**
     * 判定规则（这是本文件的核心，也是单测钉得最死的部分）：
     *
     * 第一道门：**应答的到底是不是本服务**。判据是"正文能否解析成 JSON"，不是 HTTP 状态码
     * ——一个门户页/反代/别的进程完全可能回 200 的 HTML，把它算成"可达"正是误判的开头。
     * 过不了这道门一律 [Verdict.UNREACHABLE]（连接被拒 / 超时 / 连到了别的东西）。
     *
     * 过了第一道门，就是本服务的响应体，再分两档：
     * - [Verdict.ALIVE]：`status=ok` **且** 模型已加载 —— 现在就能推理
     * - [Verdict.DEGRADED]：其余全部情况。包括 `status` 非 `ok`、
     *   非 200（本服务的错误体是 `{"error":{...}}`，证明它确实应答了、只是这请求没成功）、
     *   以及 **`status=ok` 但模型未加载**（服务活着，生成类请求拿 503。
     *   用户问的是"服务器状态"，一个不能推理的服务不该被显示成全绿）
     */
    fun verdict(r: Result): Verdict = when {
        // 服务端自报拼装错误（`health json malformed`）：**服务活着，但这份状态不可用**。
        // 归 DEGRADED 而不是 UNREACHABLE —— 后者会让用户去查网络与端口，而病在这份响应体。
        // 也不归 ALIVE：`status` 拿不到时"能用"这句话没有依据。
        r.serverSelfError != null -> Verdict.DEGRADED
        // 第一道门是"对方回的到底是不是本服务"：正文不是 JSON 就不是，
        // 哪怕它回了 200。判定顺序不能颠倒——先看 200 就会把别人的 HTML 当存活。
        !r.jsonParsed -> Verdict.UNREACHABLE
        // 到这里可以确定是本服务的响应体（/health 的 {"status":...}
        // 或错误分支的 {"error":...}），再按 status 分档。
        r.status?.lowercase() != "ok" -> Verdict.DEGRADED
        r.httpCode != 200 -> Verdict.DEGRADED
        r.modelLoaded != true -> Verdict.DEGRADED
        else -> Verdict.ALIVE
    }

    /**
     * 多行、可直接贴给别人的探测报告；参数里的 host/port 由调用方按实际绑定给出。
     *
     * [showRawEmbedProbe] 默认 **false**：`embed_parity_*` / `embed_retrieve_*`
     * 那十几个字段是**排查批大小问题时才需要的原始读数**，日常探活里它们是噪音
     * ——用户想知道的是"服务活没活、模型装没装、库认的批大小是多少"，而
     * "逐位相同 3/4 条、命中集合变了 0 条、取自长短悬殊那组"这一大坨**看完也不知道
     * 该做什么**。所以它们**不进报告正文**（正文只留 [embedBatchSizeIndex] 那类
     * "能直接指导下一步"的结论）。
     *
     * ⚠ **不是丢弃**：完整读数由 [rawEmbedProbeLines] 照旧打进 App 日志
     * （`[探测]` 那几行），要排查时翻日志就有；`/health` 响应体本身也没改
     * （旧客户端/监控拿到的还是全量字段）。这里只改"探活报告给用户看什么"。
     *
     * ⚠ 做成**形参**而不是常量开关：判据与调用方要能各自决定，而
     * "以后想临时打开看一眼"不该改源码。
     */
    fun format(r: Result, host: String, port: Int, showRawEmbedProbe: Boolean = false): String {
        val url = "http://$host:$port/health"
        val sb = StringBuilder()
        when (val v = verdict(r)) {
            Verdict.ALIVE -> sb.append("✅ 存活：服务可达，模型已加载")
            Verdict.DEGRADED -> sb.append("⚠ 服务可达，但状态不完整")
            Verdict.UNREACHABLE -> sb.append("❌ 不可达：探测失败")
        }
        sb.append("\n探测 $url")
        sb.append("（${r.elapsedMs} ms）")

        // 服务端**自己**说拼错了 —— 这句排在所有读数之前。
        //
        // `/health` 拼完会做一次 `JSONObject(json)` 自检，失败时回 200 +
        // `{"error":{"message":"health json malformed: <原因>；位置 N 附近：..."}}`。
        // 那份外壳是合法 JSON，判定走的是「可达、状态不完整」—— 而报告此前**一个字都不提**
        // 服务端拼错了，于是用户只看到"status 没报、模型没报"，方向整个跑到客户端参数上
        //（#392：连着两轮都在调 ctx 与历史回传）。
        //
        // ⚠ 位置在 [transportError] **之前**：下面所有读数都建立在这份响应体之上，
        //   而这份响应体已经作废了。先读坏消息，后面的空字段才不会被当成新线索。
        r.serverSelfError?.let {
            sb.append("\n❌ /health 是**服务端自己拼错了**（服务可达，病在这份响应体）：")
                .append(it)
                .append("\n   这不是客户端参数问题 —— 下面所有读数为空的字段都是它的后果。")
        }
        r.transportError?.let { sb.append("\n原因：$it") }
        r.httpCode?.let { sb.append("\nHTTP $it") }
        r.status?.let { sb.append(" ｜ status=$it") }
        r.port?.let { p ->
            sb.append("\n端口（服务自报）：$p")
            if (p != port) sb.append("  ⚠ 与当前探测端口 $port 不一致")
        }
        r.modelLoaded?.let { loaded ->
            if (loaded) {
                sb.append("\n模型已加载：${r.modelDesc ?: "(未报名称)"}")
                // ⚠ `ctx_used` 读的是 **slot 0**（`LlmEngine.contextUsed()`，§9.2.10
                //   「只增不改」），而 `ctx_size` 是**总量** —— 这一行是全报告里唯一
                //   "总量 ÷ 总量"形状的读数。不标口径的话，只用 HTTP（请求落在 slot 1）
                //   时它会恒为 0，被读成"缓存一点没用上"，而真相是读错了路
                //   （按 slot 的读数在下面 `kv_slots` 那几行）。字段本身不动。
                if (r.ctxSize != null) {
                    // ⚠ 这一格的来处变过（ISSUE #452 真机）：`ctx_used` 以前读死 slot 0，
                    //   而只用 HTTP 时请求落在 slot 1 —— 于是它恒 0，被读成"缓存一点没用上"，
                    //   与同一屏的 `topSlotRounds`（那时也是 0）互相印证，方向整个反了。
                    //   现在服务端按"最近跑过 prefill 的那一格"取，这里跟着报**是哪一格** ——
                    //   不报格号的话，读的人还是只能猜"这个 0 是真的还是在读错路"。
                    val cu = r.slotInUse ?: -1
                    sb.append("\n上下文（").append(if (cu >= 0) "slot $cu" else "slot 0").append("）：")
                        .append(r.ctxUsed ?: 0).append("/").append(r.ctxSize)
                }
                // 每格容量（ISSUE #330）：总量 `ctx_size` 只答得了"池子多大"，
                // 答不了"每一路能用多少"。客户端要压到多少才不裁，只有这一格能算：
                // `ctx_per_slot - max_tokens`。两个数并排摆出来，"我按总量调参、
                // 每格却只有一半"这条路当场就断了。
                // ⚠ 变量名不能叫 `cps`：下面「断点/每格容量」那段还有一个同名的，
                //   同一作用域里重名会让后来改这一行的人以为改的是另一处。
                //
                // ⚠ 「总量」与「每格」必须**并排**说，且都要标清是哪个：`ctx_size` 是
                //   用户填的那个数（总量，改它有效），`ctx_per_slot` 是每一路能用的
                //   （总量 ÷ slot 数，改它无效 —— 它跟着 parallelN 变）。以前这里只在
                //   `perSlot != ctxSize` 时才提"按份切分"，而那正是用户以为自己填的
                //   8192 "被写死"的那一刻：他看到 4096，回头把设置页的 8192 改成 4096，
                //   总量就真掉一半。所以两格无条件都报，词也要分清。
                // ⚠ ISSUE #426 第八回合之后**没有「按份切分」这件事了**：设置页那一格
                //   就是每格容量，格数只决定开几格。还留着"总量已按份切分"那句话的
                //   后果不是文案旧 —— 它会让人去改设置页的总量，而那个数现在就是每格。
                val perSlot = r.ctxPerSlot ?: 0
                if (perSlot > 0) {
                    if (perSlot != r.ctxSize) {
                        sb.append("（每格 $perSlot —— 设置页填的 ${r.ctxSize} 与实际开出的每格不等；")
                            .append("客户端 Max Context 要降到 ").append(perSlot)
                            .append(" − 生成长度 以下才不会被我们裁）")
                    } else {
                        sb.append("（每格 $perSlot —— 客户端 Max Context 要降到 ")
                            .append(perSlot).append(" − 生成长度 以下才不会被我们裁）")
                    }
                }
                // 「我填的那个数到底生效了没有」—— 判据在 `ctx_size`（请求值）与
                // `ctx_actual`（实际值）**之间**，不在"总量 ÷ 份数"上（ISSUE #392）。
                //
                // 以前这里判的是 `ctx_size ≠ ctx_per_slot × slots_total`，而那两个数
                // 同源（`ctx_size` 本来就是"每格 × 份数"算出来的）—— 于是这条告警
                // **永远对得上**（恒假）。真机上 #392 那份 `ctx_size: 16128` /
                // `ctx_per_slot: 16128` / `slots_total: 1` 同时成立，正是这个原因：
                // `ctx_per_slot` 读 native 的**每格**、`ctx_size` 乘的是同一份。
                //
                // 现在两个数各报各的（请求 / 实际），差值才是"设置没照做"：
                // 差整整一份的倍数 = 「按份切了两次」（每份只剩 1/N）；差一点 = 平台钳位。
                // 两者处置相反：前者去查切分，后者改设置页没用。旧服务端没报 `ctx_actual`
                // 时这一条不判（`null` = 没报，不拿默认值凑）。
                //
                // 位置那条线（ISSUE #426 真机 `decode rc!=0 at offset 2048`）：
                // 一格里 App 与 HTTP 各一条 seq，而每条 seq 的容量是
                // `n_ctx / n_seq_max` —— 比「每格」再小一半。预算按每格放行、
                // prefill 按每条 seq 排号时，现场只剩一行像是"KV 坏了"的日志
                // （`at offset 2048 (n=8)`），而每一格读数都合法
                // （`prompt_trimmed 0` / `spare 1014` / `gen_cap 1024`）。
                // 所以只要它与每格不等就当场说清：客户端该压到多少要看**它**。
                val seqCap = r.seqCtx ?: 0
                if (seqCap > 0 && perSlot > 0 && seqCap != perSlot) {
                    sb.append("\n⚠ 每条 seq 的位置数 ").append(seqCap)
                        .append("（= 每格 ").append(perSlot)
                        .append(" ÷ 该格里的 seq 数）—— 位置号只能排到 ")
                        .append(seqCap - 1)
                        .append("；客户端按每格 ").append(perSlot)
                        .append(" 送整段 prompt 时会撞上它（日志 `prefill decode rc!=0 at offset ")
                        .append(seqCap).append("`）。Max Context 要按这一个数压")
                }
                val slots = r.slotsTotal ?: 0
                // 份数那一对（ISSUE #426 第七回合）：只报实际份数时，「parallelN 填 2、
                // 探活 3 格」这件事在报告里没有任何一处能指认。
                // ⚠ 来源只剩两种（第八回合起整除不再参与判决）：上限钳位 / 某份 ctx 建失败。
                val slotsReq = r.slotsRequested ?: 0
                if (slotsReq > 0 && slots > 0 && slotsReq != slots) {
                    sb.append("\n⚠ 请求 ").append(slotsReq).append(" 格，实际开出 ")
                        .append(slots).append(" 格")
                    sb.append("（上限钳位 / 某几份 ctx 建失败）")
                    sb.append("—— 每格容量随之变成 ").append(perSlot)
                        .append("；这不是设置页填错了，别照着这些数往下调")
                }
                val req = r.ctxSize ?: 0
                val act = r.ctxActual
                if (act != null && act > 0 && req > 0 && act != req) {
                    sb.append("\n⚠ 填的总量 ").append(req).append("，实际生效 ").append(act)
                    if (slots > 1 && act == req / slots) {
                        sb.append("（= 填的数 ÷ ").append(slots)
                            .append(" 路）—— 「按份切了两次」：每份只拿到总量的一份。")
                            .append("这正是 v0.9.3xx 之前那个 bug")
                    } else {
                        sb.append("—— 平台把每份钳小了（或第 2 份建失败）；")
                            .append("设置页填的数没问题，别按这个读数去改它")
                    }
                }
            } else {
                sb.append("\n模型未加载：/v1/models 为空，生成类请求会返回 503")
            }
        }
        // 向量模型（嵌入那份）——与对话模型**分开**报。
        // 双常驻的四种组合在 `model_loaded` 上长得一模一样，而缺嵌入模型时
        // 检索必然全空（用户看到"一条都搜不到"会被引去查知识库）。
        // ⚠ 只在服务端**报了**这三个字段时才出这一行：`null` = 旧版本没报，
        //   那时多挂一行"向量模型：未报"是噪音，而噪音会被跳过。
        r.embedLoaded?.let { loaded ->
            sb.append("\n向量模型")
            if (loaded) {
                sb.append("已加载：").append(r.embedModelDesc ?: "(未报名称)")
                r.embedDim?.let { d -> if (d > 0) sb.append("（dim=$d）") }
            } else {
                sb.append("未加载：检索必然全空（/v1/embeddings 返回 503）；" +
                    "与对话模型是**两份**，模型已加载不代表它能用")
            }
        }
        r.busy?.let { b -> if (b) sb.append("\n生成进行中（busy=true）") }
        // `kv_slots` 的形状 —— **缺字段就先说缺**，别让后面那几行读数去解释一份残缺数据。
        //
        // #384 真机：整格缺了 `{i,loaded,used,valid,reuse,prefill,rounds,lcp}` 八个字段，
        // 而报告照样一条条往下读（`optInt` 静默给默认值），于是"这一格 0 轮"与
        // "服务端没发这一格"在报告里一模一样 —— 用户去查参数，查了一整轮。
        // 缺字段是**判据**问题（代码没发），不是数据问题，所以它排在这些读数之前。
        r.kvSlotMissingKeys?.let { miss ->
            if (miss.isNotEmpty()) {
                sb.append("\n⚠ /health 的 kv_slots 缺字段：").append(miss.joinToString(" / "))
                    .append(" —— 服务端拼这一段时漏了（不是这一格真的为 0）。")
                    .append("下面所有按 slot 的读数都缺这几格，先别拿它调参，把这一行贴回来")
            }
        }
        // 上一轮那一刀：**只在真的裁过时回显**（0 说明这一轮与复用无关，不必占一行）。
        // 它回答的是"复用为什么没了"里唯一能自证的那一半：我们丢掉的 prompt 就是断点，
        // 所以 `prompt_trimmed >= 16` 时复用**必然**没了（`kv_lcp_tokens` 会约等于它）。
        // 与 `gen_trimmed` 分开说：只削生成上限对 KV 零影响，两者合成一个数时
        // "话变短了"会被读成"历史被裁过"，而处置正好相反（调 max_tokens vs 调 n_ctx）。
        val pt = r.promptTrimmed ?: 0
        val gt = r.genTrimmed ?: 0
        if (pt > 0 || gt > 0) {
            sb.append("\n上下文预算：上一轮")
            if (pt > 0) sb.append("丢最老 ").append(pt).append(" tok")
            if (pt > 0 && gt > 0) sb.append("，")
            if (gt > 0) sb.append("生成长度削到比请求值少 ").append(gt).append(" tok")
            if (pt > 0) {
                sb.append(" —— 丢掉的那一段就是 KV 前缀的断点")
                if (pt >= ContextBudget.KV_MIN_REUSE_TOKENS)
                    sb.append("：只要客户端每轮都送这么长，复用会**每轮都断**，" +
                        "服务端调参救不了（要让每格装得下整段 prompt，或让客户端把 Max Context 降下来）")
            } else {
                sb.append(" —— prompt 一个字节没动，KV 前缀仍然连续（这条与复用无关）")
            }
            // 余量：这一格把 `prompt_trimmed > 0` 里**两种相反处境**分开。
            //
            // 「丢了一次、之后一路接得上」和「每轮都丢一小段、起点永远接不上」
            // 在 `prompt_trimmed` 上都 > 0，处置完全相反：前者是设计（一次丢到位），
            // 后者是"每格装不下整段 prompt"的必然结果。判据是**离水位还剩多少**：
            // `drop_room` 为 0 = 已经贴在水位上，下一轮历史再长一枚就得再丢一枚。
            //
            // ⚠ 这里原来读的是 `spare`（离**整格**还多远）—— 而水位 85% 的那一轮
            //   `spare` 可能还有几百枚、`drop_room` 已经是 0：于是报告说"还有余量"，
            //   用户下一轮照样看到历史被丢，"每轮都丢"这件事一个字都没说出口。
            //   两格都报，但**结论只由 `drop_room` 下**；`spare` 只作为"离墙多远"的旁证。
            val dr = r.dropRoom
            val sp = r.spare ?: -1
            val dover = r.dropOvershoot ?: 0
            if (dover > 0) {
                sb.append("（其中 ").append(dover)
                    .append(" tok 是刻意多丢的：换来后面几十轮不必再丢）")
            }
            if (pt > 0 && dr == 0) {
                sb.append("。⚠ 丢完之后**已经贴在水位上**：下一轮历史只要再长，就必然再丢一次，" +
                    "起点于是每轮往后挪一步 —— 复用每轮都有值，但每轮都接不上。" +
                    "两条路：把水位调低一档（更早忘事、但丢得少），或把每格调大到装得下整段 prompt")
            } else if (pt > 0 && dr != null && dr > 0) {
                sb.append("。丢完之后离水位（").append(r.ctxWatermark ?: 0).append("%）还有 ")
                    .append(dr).append(" tok —— 下一轮历史长到这里才需要再丢一次")
            } else if (pt > 0 && sp == 0) {
                // 旧服务端（只报 `spare`）：照旧说得清"贴边"，但只敢说整格那一句。
                sb.append("。⚠ 丢完之后**一枚余量都没剩**（整格）：下一轮历史只要再长，就必然再丢一次")
            } else if (pt > 0 && sp > 0) {
                sb.append("。丢完之后整格还留 ").append(sp).append(" tok 余量")
            }
        }
        // 上一轮那一刀**落在哪一截**（ISSUE #330）：日志里已经有这句话，但探活报告是
        // 用户真正在读的那一面。以前这里只有 `prompt_trimmed`（"是不是我们砍的"），
        // 回答不了"断点在哪" —— 而三个落点的处置完全相反（改客户端 / 改 n_ctx / 查 bug）。
        //
        // ⚠ 结论必须与**依据**一起显示：`head_cmp == 0` 表示上一轮没存开头，
        //   这时那句话只是"位置"，**不许**替用户下结论（真机上正是这句话方向错了，
        //   把"开头就已经不一样"说成了"回复没被带回来"）。
        //
        // ⚠ 报告只说**读数**（开头同形 / 没比过 / 断在第几枚），成因不在这里断言 ——
        //   真机上"落在上一轮 prompt 段中间"这一句曾被展开成"有东西每轮都在变"，而
        //   真相是我们自己从头部砍了一刀（用户原话："服务端 4096、客户端超 4096 就
        //   立刻不可复用"）。方向说反了，用户按那句话调客户端只会白花时间。
        //   起点到底接没接上，由下面「KV 起点」那一行说（那是可判定的读数）。
        // 「这一轮接上了没有、为接上让掉了多少」—— 两个**读数**（ISSUE #330 第 1、3 块）。
        //
        // ⚠ 这里**不推断成因**。曾经有一版（`rediscover` 五档）从 (起点, 上一轮起点,
        //   丢头数, 开头是否同形) 里推"起点为什么动了"，真机上那几档互相覆盖 ——
        //   它先后把"我们自己从头部砍了一刀"说成"对端的历史变了"、把"开头就变了"
        //   说成"回复没被带回来"，用户照着改了五轮客户端，一次也没对。
        //   读数能被数据直接回答，成因不能；不能的就别给它一个名字。
        val aligned = r.aligned ?: 0
        val st = r.start ?: 0
        val sp = r.startPrev ?: -1
        // 新算起点与复用枚数是**同一件事的两端**（相加 = 整段 prompt）：
        //   · `sn > 0` -> "跳过起点复用"（开头那段每轮在变，后面那大段没变）；
        //   · `sn == 0` -> 从第 0 枚算起（旧判据下这就是"命中了前缀"）。
        // 两格并排报：只报 `start` 时"复用 2000 多"读起来像"整段都命中"，
        // 而真实起点可能是 152；只报 `sn` 又看不出省了多少。
        val sn = r.startNew ?: 0
        // ⚠ 门是「服务端**报了**这几格」，不是「值非零」，也不是「无条件」—— 三档都试过：
        //   · `st > 0 || sn > 0 || sp > 0` —— **复用 0 枚**的那一轮（全量重算，正是用户
        //     来问"为什么没复用"的那一轮）整行不出现，下面那条 `st == 0 && sn == 0`
        //     分支永远走不到（门与分支自相矛盾）；
        //   · 无条件 —— 旧服务端（不报 `start` / `start_new`）与**冷启动**（一格都没跑过）
        //     时也会念一句"从第 0 tok 开始新算 + 复用 0 tok（一枚都没复用…）"：那是把
        //     "服务端没报"说成"真的一枚没复用"，而两者的处置相反（去升级服务端 /
        //     去查为什么没命中）。本文件其它每一格都守 `null` = 没报这条口径。
        run {
            if (r.startNew == null && r.start == null) return@run
            sb.append("\nKV 起点：从第 ").append(sn).append(" tok 开始新算 + 复用 ")
                .append(st).append(" tok")
            if (st == 0 && sn == 0) {
                sb.append("（一枚都没复用 —— 这一轮是从第 0 枚全量重算的）")
            } else if (sn > 0 && st > 0) {
                sb.append("（跳过起点复用：开头那 ").append(sn)
                    .append(" tok 每轮都在变，后面那一大段没变 —— 省的是这一段）")
            }
            if (sp >= 0) sb.append("（上一轮第 ").append(sp).append(" tok）")
            if (aligned == 1) {
                sb.append(" —— 接上了上一轮，之前攒下的前缀可用")
            } else if (sp > 0) {
                sb.append(" —— 本轮重新决定了起点，之前攒下的前缀接不上")
                val ar = r.alignReject ?: 0
                if (ar > 0) sb.append("（想接而额度不够，让掉了 ").append(ar).append(" tok）")
            }
        }
        // 注入块宽度：它一变，本轮提问那一段就全变、前缀断在块头。以前只在一条
        // uiLog 里，探活上看不到，于是"块宽跟着并发数变"这件事一直没人发现。
        val ic = r.injectChars ?: 0
        // 本会话定下来的宽度与"本轮那个块"分开说：前者变了才是"宽度被重新算过"
        //（前缀断在块头的真凶），后者随命中的条目正文长短而变，每轮都不同 ——
        // 拿它当判据，每轮都会"变了"，与"宽度被重新计算"在读数上一模一样。
        val iw = r.ragInjectCharsSet ?: 0
        val ik = r.embedChunkIndex ?: -1
        if (ic > 0 || iw > 0) {
            sb.append("\n知识库注入块：").append(ic).append(" 字（本轮）")
            if (iw > 0) sb.append("，本会话宽度 ").append(iw).append(" 字")
            // 库认的块宽：它与当前配置不等 = 这份索引对不上（静默不可检索）。
            // 以前只有一个数 —— "我改了块宽，为什么库不能用了"只能靠猜。
            if (ik > 0) sb.append("；建索引时块宽 ").append(ik).append(" 字")
        }
        // 断点两侧的**几何**（ISSUE #330 第 7 块）—— 这一行是"谁的字节变了"的直接回答。
        //
        // 下面 `KV 断点` 那一行说的是**结论**（落在哪一截），而结论在真机上被证明
        // 可以被读反（"我们自己砍的"曾被说成"对端的历史变了"）。这一行只报**区间**：
        // 断点落在哪儿、那段夹带内容在哪儿、上一轮的历史这一轮还在不在最前面。
        // 三件事都能被数据直接回答，不需要任何推断 —— 而它们合起来就把成因分完了。
        run {
            val xf = r.diffExtra ?: -1
            val xl = r.diffExtraLen ?: 0
            val xp = r.diffExtraPrev ?: -1
            val ph = r.diffPrevHeadAt
            // 没有分叉可说时整段不出现（`fork_why` 为空 = 命中 / 冷启动 / 整串都在前缀里）——
            // 报一行"判不出来"只会把真正需要看的那几行挤下去。
            if ((r.forkWhy ?: "").isNotEmpty()) {
                sb.append("\n断点两侧：")
                var said = false
                if (xf >= 0 && xl > 0) {
                    sb.append("本轮 prompt 的 [").append(xf).append(", ").append(xf + xl)
                        .append(") 是本轮新拼进去的那段内容（").append(xl).append(" tok）")
                    if (xp < 0) sb.append("，上一轮它定位不出来（长度或位置变了）")
                    else if (xp != xf) sb.append("，上一轮它落在第 ").append(xp).append(" tok（位置每轮在变）")
                    else sb.append("，上一轮它也在同一位置（变的是这段内容本身）")
                    said = true
                }
                // 上一轮**整段**在本轮还在不在（ISSUE #503）。它必须先于 `ph` 判：
                // `ph == -1` 有两个完全相反的真相 ——「对端换了一份对话」与
                // 「本轮就是上一轮的前缀，我们自己把开头扔了」。只看 `ph` 时两者同值，
                // 而报告只说得出一句"两轮喂的不是同一份对话"，处置正好指反。
                val ppc = r.prevPrefixInCur ?: -1
                val pr = r.promptReal ?: 0
                if (ph != null) {
                    if (said) sb.append("；")
                    sb.append("上一轮 prompt 的开头这一段在本轮")
                    when {
                        ph < 0 && ppc == 0 -> sb.append("**从第 0 枚起就不同**")
                        ph < 0 -> sb.append("一处都找不到")
                        ph == 0 -> sb.append("还在最前面（不是历史被换掉）")
                        else -> sb.append("的第 ").append(ph).append(" tok 才出现（被挪到了后面）")
                    }
                    said = true
                }
                if (ppc >= 0) {
                    if (said) sb.append("；")
                    sb.append("上一轮整段账本在本轮从第 0 枚起比到 ").append(ppc).append(" tok")
                    if (pr > 0 && ppc == pr) {
                        // 本轮整段都在上一轮里 = 可验证的事实（逐枚比过，不是推断）。
                        sb.append("（= 本轮整段）—— 本轮就是上一轮的开头一截，一枚都没被换：")
                            .append("变的是我们喂进去的起点。查 prompt_trimmed / start / ")
                            .append("start_prev / spare，改客户端回传的历史没用")
                    } else if (ppc > 0) {
                        sb.append(" —— 断点之前那段逐枚相同，断在它之后")
                    }
                    said = true
                }
                // 起点：内容没变、变的是"从第几枚喂起"（ISSUE #503 真机第二轮）。
                // 它是「每轮全量 prefill」那条自锁在读数上的样子 —— 单独看 `start` 或
                // `start_prev` 都正常，只有两格一减才看得见。
                val tsTok = r.tokStart ?: 0
                val spPrev = r.startPrev ?: -1
                if (tsTok > 0 && spPrev >= 0 && tsTok != spPrev) {
                    if (said) sb.append("；")
                    sb.append("起点挪了：本轮从第 ").append(tsTok).append(" tok 起喂、上一轮从第 ")
                        .append(spPrev).append(" tok 起喂 —— 每轮往后滑一截，KV 里攒下的那段就")
                        .append("永远接不上（每轮全量 prefill）。查 drop_room / spare / prompt_trimmed")
                    said = true
                }
                if (!said) sb.append("没有可指认的差异区间（两侧都是本会话自己的内容）")
                // 断点上那两枚 token 与「上一轮那段在本轮还找得到几枚」（ISSUE #452）。
                // 上面那句说的是"落在哪个区间"，这一行说的是"断点上到底是什么"。
                // 真机现场：`lcp 301 / 两侧长度都是 2757 / 上一轮的开头一处都找不到` ——
                // 几何四格齐了，结论仍然只有一句"两轮喂的不是同一份对话"，
                // 而这一行会把"谁的哪一枚变了 / 上一轮那 301 枚在这一轮还在不在"摆出来。
                val fpt = r.forkPrevTok?.takeIf { it >= 0 }
                val fct = r.forkCurTok?.takeIf { it >= 0 }
                val el = r.elidedLen ?: -1
                val hll = r.headLedgerLen ?: 0
                val cmpNow = r.headCmp ?: 0
                if (fpt != null || fct != null || el >= 0 || hll > 0) {
                    sb.append("\n断点两侧的 token：")
                    sb.append("上一轮那一枚 ").append(fpt?.toString() ?: "（没有这一枚）")
                        .append(" / 本轮 ").append(fct?.toString() ?: "（本轮更短，没有这一枚）")
                    if (el >= 0) {
                        sb.append("｜上一轮开头那段在本轮")
                        when {
                            el == 0 -> sb.append("**一枚都不在了**（这一轮从第 0 枚起就不是上一轮那份内容）")
                            cmpNow > 0 && el < cmpNow ->
                                sb.append("只对上 ").append(el).append(" / ").append(cmpNow)
                                    .append(" 枚 —— 上一轮那段被换掉了：查客户端回传的历史，")
                                    .append("或我们自己那一刀砍在了这里")
                            else -> sb.append("照旧在（").append(el).append(" 枚）")
                        }
                    }
                    if (hll > 0 && cmpNow > 0 && cmpNow < hll) {
                        sb.append("｜上一轮存的开头 ").append(hll).append(" tok 只比了 ").append(cmpNow)
                            .append(" tok（本轮 prompt 更短）—— 「断点在更后面」这句话不成立，")
                            .append("它只说明没比完")
                    }
                    said = true
                }
        }
        }
        // 「这一轮为什么停」——三笔账并排，`min(n_rem, stop_wall)` 就是实际能生成的枚数。
        //
        // 真机那条：请求 1024、实际只给了 99、KV 还空着 1618 格。只报 `gen_cap`
        // （= 1024，预算一分没削）时，这三种处境在 `/health` 上**逐字相同** ——
        // 用户拿着 `finish_reason=length` 回来问"为什么话说到一半就断"，
        // 而报告每一格都答"没问题"。三格一起读才回答得出"是谁拦的"。
        run {
            val gp = r.genPlanned ?: -1
            val gr = r.genRem ?: -1
            val gw = r.genWall ?: -1
            if (gp > 0 && gr >= 0 && gw >= 0) {
                // ⚠ `gr` 与 `gw` **不许并排比大小**：`n_rem` 在位置上限落定之后已经被
                //   收到 `min(预算, 位置)` 上（见 native 的 `kv_last_n_rem`），所以
                //   这两个数比大小恒为「计数上限更小」，那一支永远不可能成立 —— 那行会印出「预算给 512 /
                //   位置还够 1514」→ 「这一轮实际最多 512」，随后再说一句"位置不够"。
                //   两句话互相顶，而它们出自同一份读数。
                //
                //   分开报：`gr` 是**计数上限**（削完之后），`gw` 是**位置上限**（没跟别人
                //   比过），谁先到看两者与 `gp` 的关系，不拿它们相减。
                val real = minOf(gr, gw)
                sb.append("\n生成长度：请求 ").append(gp).append(" / 计数上限 ").append(gr)
                    .append(" / 位置还够 ").append(gw).append(" tok —— 这一轮实际最多 ")
                    .append(real).append(" tok")
                // 「有没有把 gen 削到那个数」必须由 `gen_trimmed` 回答，不能拿 `gr` 当尺子：
                // `gr` 已经被位置收过，它变小并不能说明预算动过手。
                val gtrim = r.genTrimmed ?: 0
                // 位置是不是被 prompt 吃掉了：差额 ≈ 生成长度 -> 只是"还没用"，
                // 远大于生成长度才是"被别的东西占了"（见 `posGap` 的说明）。
                val gap = r.posGap ?: 0
                val gapIsGen = gap > 0 && gap <= maxOf(gp, 1) + 2
                when {
                    gtrim > 0 -> sb.append("（预算削过：prompt 与生成长度共用 ctx 这一池位置，" +
                        "先让的是生成长度，削掉了 ").append(gtrim).append(" tok —— " +
                        "把每格调大或把 max_tokens 调小，这一行才会变）")
                    // 位置最先到：与预算无关，调 max_tokens 也没用。
                    gw <= real && gw < gp -> sb.append("（位置不够：prompt 与上一轮已进 KV 的内容")
                        .append("占掉了大半容量，剩下的位置只够这么多。" +
                            "这一轮与 max_tokens 无关 —— 要么把每格调大，要么让 prompt 短一点）")
                    else -> sb.append("（调用方自己只要这么多）")
                }
                if (gap > 0) {
                    sb.append("｜位置多占 ").append(gap).append(" tok")
                    if (gapIsGen) {
                        sb.append("（≈ 这一轮请求的生成长度：它被预留了、还没用掉 —— " +
                            "**不是** prompt 占了这些位置，别按这个数去调 ctx）")
                    } else {
                        sb.append("（远大于生成长度：有东西在 prompt 之后又占了位置 —— " +
                            "看 ledger_prompt 与 prompt_real 差多少）")
                    }
                }
            }
        }
        // 「估算 vs 真 token」—— 这条窄带以前在探活上**完全看不见**：客户端按估算说
        // "装得下、一条不丢"，native 按真值说"超了"、从头部砍一刀，两边的读数各自
        // 都合法，只有差值能说明它们不是同一把尺子。两格都是 0（冷启动 / 老版本没报）
        // 时不出这一行 —— 那时报一个 0/0 只会被读成"估算准得很"。
        val pr = r.promptReal ?: 0
        val pe = r.promptEst ?: 0
        if (pr > 0 && pe > 0) {
            sb.append("\n上下文估算：本轮 prompt 真 ").append(pr).append(" tok / 估算 ").append(pe)
            // ⚠ 两个方向都要说清，且**不许把"高估"读成"没事"**：
            //   · 估少（`pr > pe`）—— 预算按估算说"装得下、一条不丢"，native 拿到真值
            //     才发现超了、从**头部**砍一刀 → KV 前缀断在被砍的那一处，复用归零；
            //   · 估多（`pe > pr`）—— 预算**白丢**了几轮历史（这一轮照答，但用户少了记忆），
            //     而 native 那边什么都没发生。它以前只报一句"估算没有低估，这条窄带不在
            //     本轮"，读起来像一切都好 —— 而真机上那次的读数是
            //     `prompt_real 2649 / prompt_est 4036`，高估了一千多枚。
            val gap = pr - pe
            if (gap > 0) {
                sb.append("（估少了 ").append(gap)
                    .append(" tok —— 预算以为装得下、native 却要砍的时候，差的就是这么多）")
            } else if (gap < 0) {
                sb.append("（估多了 ").append(-gap)
                    .append(" tok —— 预算按这个数白丢了几轮历史，而 native 那边其实还装得下；")
                    .append("差得多就是估算口径本身有问题，不是上下文不够）")
            } else {
                sb.append("（两把尺子这一轮对上了）")
            }
            // 校准系数必须与上面那两个数**同一行**读：`prompt_est` 是乘过它的数，
            // 没有它，上面那句"估多了/估少了"会把"我们自己调的系数"读成"估算口径变了"
            // （ISSUE #452 ⑤）。未校准（1000）时不印 —— 那是常态，印出来只是噪音。
            val es = r.estScale ?: 0
            if (es in 1..999) {
                sb.append("｜估算系数 ").append(es).append("/1000（按真 token 往下校准：")
                    .append("估算原来偏高，白丢的那几轮历史因此少丢了）")
            }
        }
        r.forkWhy?.let { why ->
            if (why.isNotEmpty()) {
                sb.append("\nKV 断点：").append(why)
                val cmp = r.headCmp ?: 0
                val fih = r.forkInHead ?: 0
                when {
                    cmp <= 0 -> sb.append("（上一轮没存开头：只报位置，落点未证实）")
                    // 断点落在被比的这段**之内**：这一句才是"逐字节证实"。
                    fih == 1 -> sb.append("（前 ").append(cmp).append(" tok 已逐字节证实）")
                    // 这段**全等**、断点正压在段尾（native 的 fork_in_head=2）：
                    // 之前它与上面并成一档（`headSame == 1` 也算"已证实"），于是
                    // 报告说"前 144 tok 已逐字节证实"，而用户读到的正是"断点在 144"。
                    // 证实的只有"前 144 枚相同"这一半，"断点在哪"是另一件事。
                    fih == 2 -> sb.append("（前 ").append(cmp)
                        .append(" tok 逐 token 相同，断点正压在这段末尾 —— 再往后没比过）")
                    // `fih == 0` 且 `head_same == 1`：被比的这一段**整段全等**，断点在更
                    // 后面。以前它与"就已经不一样"并成一档，于是整段比过、逐枚相同的
                    // 场次反而被写成"落点结论不成立" —— 同一行里前后两句还互相顶
                    //（"前 512 tok 就已经不一样" vs "开头 512 tok 与本轮逐 token 相同"）。
                    // 判据用的是 native 已经落账的两格，不在这里另判一次。
                    fih == 0 && (r.headSame ?: 0) == 1 -> sb.append("（前 ").append(cmp)
                        .append(" tok 整段逐 token 相同，断点在更后面）")
                    else -> sb.append("（前 ").append(cmp)
                        .append(" tok 就已经不一样：落点结论不成立，先查 system 段/模板有没有每轮在变）")
                }
                // 「中间」不再自己下结论：那句话在真机上把"我们自己砍了一刀"说成了
                // "对端的历史变了"，方向正好反。现在只报**读数**（开头同形 / 没比过），
                // 起点接没接上由上一行说 —— 那才是可判定的事实。
                if ((r.headSame ?: 0) == 1 && why.contains("中间")) {
                    sb.append("｜开头 ").append(cmp).append(" tok 与本轮逐 token 相同")
                        .append("（system 段没变，变的那一段在它之后）")
                }
            }
        }
        // 「复用 152」那类读数到底该看哪一格 / 该不该信（ISSUE #330 真机复盘第三回合）。
        // 两件事都要能当场说清，否则每一轮讨论都停在"这是 bug 还是数学"：
        //   · slot_in_use —— 顶上那几个 kv_* 读的是哪一格。⚠ 它以前恒 0（与 `kv_slot`
        //     同源），于是只用 HTTP 那一屏是 `slot_in_use: 0 / kv_rounds: 0` 配
        //     `kv_slots[1].rounds: 6` —— "缓存一次没生效"这句假话就是这么拼出来的。
        //     现在它报"最近跑过 prefill 的那一格"，顶上五个数按它取（ISSUE #452 真机）；
        //   · kv_last_ctx —— 上一轮**真正**进 KV 的那一格多大。它与 ctx_per_slot 不同，
        //     就说明"我以为的容量"与"它实际用的容量"分叉了 —— 那时客户端按哪个数压都不对。
        val inUse = r.slotInUse ?: -1
        if (inUse >= 0) {
            sb.append("\nKV 账本读的是 slot ").append(inUse)
                .append("（最近跑过 prefill 的那一格；顶上的 kv_* 也按它取，与 kv_slots[")
                .append(inUse).append("] 逐格相等）")
        }
        val lc = r.lastCtx ?: 0
        val cpsNow = r.ctxPerSlot ?: 0
        if (lc > 0 && cpsNow > 0 && lc != cpsNow) {
            sb.append("\n⚠ 上一轮实际用的每格容量是 ").append(lc)
                .append("，与现在报的 ").append(cpsNow)
                .append(" 不一致 —— 客户端按哪一个数压都会有一半轮次被裁，先确认是不是改过 parallelN/上下文长度")
        } else if (lc > 0 && cpsNow > 0) {
            // 一致时也要说一句：`ctx_size`（总量）与它是**两个数**，这行字防止
            // 「我填的 8192 怎么显示成 4096」再被读成"设置没生效"。
            sb.append("\n上一轮实际用的也是每格 ").append(lc)
                .append("（每格 = 设置页填的那个数本身；并发数只决定开几格，不切容量）")
        }
        // 后台冻结：只有真的检出过才回显（0 次不提，免得每次探测都挂一行无信息的字）。
        // 这一行的存在意义就是"别再靠猜"：用户看到的"后台被限制了"到底有没有发生、
        // 发生了多久，全由它给出。`restart_pending` 一并报 —— 它是"服务本该死而复生、
        // 但为了不掐断在跑的流而故意欠着"的唯一读数。
        val fc = r.frozenCount ?: 0
        if (fc > 0) {
            sb.append("\n后台冻结：上次 ").append((r.frozenMs ?: 0) / 1000)
                .append("s，累计 ").append(fc).append(" 次 / ")
                .append((r.frozenTotalMs ?: 0) / 1000).append("s")
        }
        if (r.restartPending == true) {
            sb.append("\n监听欠一次重启（等本轮生成结束，避免掐断在跑的流）")
        }
        // 嵌入合批（ISSUE #269）：**只在有读数时回显**（0 提不提，免得每次探活都挂一行
        // 无信息的字）。它是合批生效与否**唯一的对外读数** —— 没有它，
        // "合批到底有没有在工作"只能翻 App 内日志猜。
        val bb = r.embedBatchBatches ?: 0L
        val be = r.embedBatchEntries ?: 0L
        val fb = r.embedBatchFallbackBatches ?: 0L
        val fe = r.embedBatchFallbackEntries ?: 0L
        if (be > 0L || fe > 0L) {
            val total = be + fe
            val pct = if (total > 0L) (be * 100 / total).toInt() else 0
            sb.append("\n嵌入合批：").append(be).append("/").append(total)
                .append(" 条（").append(pct).append("%，").append(bb).append(" 批")
                .append(if (bb > 0L) "，共 " + (r.embedBatchMs ?: 0L) + " ms" else "")
                .append("）")
            if (fb > 0L) {
                sb.append("；回落逐条 ").append(fe).append(" 条（").append(fb).append(" 批）")
                if (bb == 0L) {
                    sb.append(" ⚠ 合批**一次都没生效**（这个模型声明的 pooling " +
                        "可能是 MEAN/CLS/LAST 那三档，它们不合批）")
                }
            }
        }
        r.embedBatchSize?.let { n -> sb.append("\n当前批大小：").append(n)
            .append(if (n <= 1) "（关合批）" else "") }
        // 建索引时用的批大小（ISSUE #269 第 5 步）：与"当前批大小"并排，两个数
        // 一起才判得出"库为什么不可用"。⚠ 只在**库里的那个数与当前不同**时才多说
        // 一句 —— 两者相等时再念一遍只是噪音，而噪音会让真正要紧的那句被跳过。
        r.embedBatchSizeIndex?.let { idx ->
            val kb = r.embedBatchSizeIndexKb.orEmpty()
            if (idx < 0) {
                sb.append("\n建索引时用的批大小：读不到")
                    .append(if (kb.isNotEmpty()) "（当前库「$kb」没有索引或索引读不出来）" else "（没选中知识库）")
            } else {
                sb.append("\n建索引时用的批大小：").append(idx)
                    .append(if (kb.isNotEmpty()) "（库「$kb」）" else "")
                val now = r.embedBatchSize
                if (now != null && now != idx) {
                    sb.append("\n⚠ 与当前批大小（").append(now)
                        .append("）不同 —— 批大小进索引指纹，这一个数不一致就会让整库判成" +
                            "「索引不可用」：把批大小改回 ").append(idx).append("，或重建索引")
                }
            }
        }
        // 嵌入 ctx 那一格（0.9.212）：内存的**唯一**旋钮在它身上 —— 批大小只决定
        // "一次塞几条"，不改 ctx 容量。两行并排摆出来，"内存怎么降"才有落点。
        r.embedCtxPerSeq?.let { n ->
            sb.append("\n嵌入每条预算：").append(n)
                .append("（ctx 总量 = 它 × ").append(EmbedLimits.kEmbedBatchRows)
                .append("；KV/output/compute buffer 都按这个总量分配 —— 内存高看这一格）")
            // 设置值 vs 生效值：填 1024 实际按 512 跑是完全正常的（上限 2048 ÷ 4 条），
            // 而只报设置值时那种情形与"真的用上了"在报告里长得一模一样。
            r.embedCtxPerSeqLive?.let { live ->
                if (live > 0 && live != n) {
                    sb.append("\n⚠ 但**生效的**是 ").append(live)
                        .append("：上限 ").append(EmbedLimits.kEmbedCtxRowsCap)
                        .append(" ÷ 每批 ").append(EmbedLimits.kEmbedBatchRows)
                        .append(" 条 —— 填更大的值会被钳到这里，改它没用")
                }
            }
        }
        // ── 0.9.242：省内存的另外两格（每批条数上限 / 两道门）──────────────
        // 与上面那一对同一形态（配置值 + 生效值），理由也相同。三格分开写而不是
        // 合成一句"内存档位"，因为它们省的是**三份不同的 buffer**：
        //   · ctx 总量 → KV + output（那份不可协商）；
        //   · 条数     → 上面那个总量的乘数（n_ctx = 每条预算 × 条数）；
        //   · 两道门   → compute（按整批 token 数切）；
        // 合成一句时，"我把门调小了一半，为什么 RSS 只降了一点点"没有任何落点。
        r.embedSeqMax?.let { n ->
            sb.append("\n嵌入每批条数上限：").append(n)
                .append("（= n_seq_max；ctx 总量 = 每条预算 × 它，KV/output 按这个总量分配）")
            r.embedSeqMaxLive?.let { live ->
                if (live > 0 && live != n) {
                    sb.append("\n⚠ 但**生效的**是 ").append(live)
                        .append("（库这一份 ctx 实际拿到的 seq 数）—— 合批能塞几条认的是它")
                }
            }
        }
        r.embedGate?.let { n ->
            sb.append("\n嵌入两道门：").append(n)
                .append("（n_batch / n_ubatch 同源；省的是 compute buffer，**不是** KV）")
            r.embedGateLive?.let { live ->
                if (live > 0 && live != n) {
                    sb.append("\n⚠ 但**生效的**是 ").append(live)
                        .append("（库的实数取 min：非因果模型只认 n_ubatch 那扇）")
                }
            }
        }
        // ⚠ 这一行是"内存到底降了多少"的**唯一**直接读数 —— 另外三格都只是它的输入。
        // 用户把条数从 4 调到 1 却发现"没怎么降"时，答案就在这里：
        // `n_ctx = 每条预算 × 条数` 还会被「嵌入 ctx 总量」那一格（默认 2048）顶住。
        r.embedCtxTotalLive?.let { live ->
            if (live > 0) {
                sb.append("\n嵌入 ctx 总格数（生效）：").append(live)
                    .append("（KV + output buffer 按它分配 —— 这是『内存降了多少』的直接读数）")
            }
        }
        // ⚠ 从这里往下到「检索对照」那一段，全是 `embed_parity_*` / `embed_retrieve_*`
        // 的**原始读数** —— 默认**不进报告正文**（理由见 format 的 kdoc：
        // 日常探活里它们看完也不知道该做什么）。完整读数由 [rawEmbedProbeLines]
        // 打进日志；要临时看回报告，把 [showRawEmbedProbe] 传 true。
        if (showRawEmbedProbe) {
        // 逐位比对结论（ISSUE #269 第 5 步）。**只在跑过时**才说（`rows<=0` 或
        // 结论为空 = 还没跑过，那时报一句"没跑过"是噪音，而噪音会被跳过）。
        // 措辞必须与结论档**一一对应**：把 close 说成"相同"会让用户据此沿用 ——
        // 而沿用这条路上索引里正是两种向量（见 EmbedParity 对象头）。
        val pv = r.embedParityVerdict.orEmpty()
        if (pv.isNotEmpty() && (r.embedParityRows ?: 0) > 0) {
            val n = r.embedParityRows ?: 0
            val idn = r.embedParityIdentical ?: 0
            val dfn = r.embedParityDifferent
            sb.append("\n向量逐位比对：逐位相同 ").append(idn).append("/").append(n).append(" 条")
            // `different` 的**条数**要报出来（`null` = 服务端没报这个字段，与 0 分开写）：
            // "1/6 不同"与"6/6 不同"给出的下一步相反的（前者去查那条样本，
            // 后者说明合批整体不可信）—— 只报相同条数时这两个读数长得一样。
            dfn?.let { sb.append("、真的不同 ").append(it).append(" 条") }
            // 取自哪一组**必须说**：等长组按构造必然稳，长短悬殊组才答"会不会变"。
            // 不写这一句时，"逐位相同 4/4 —— 批大小是纯加速旋钮"会被读成
            // "长短悬殊也稳"（真机现场正是如此 —— 见 [Result.embedParityGroup]）。
            // 服务端没报这个字段（旧版本）时不猜，只按原来的说法报数字。
            val grp = r.embedParityGroup.orEmpty()
            if (grp.isNotEmpty()) sb.append("（取自").append(grp).append("那组）")
            // ── 判据（#392：这 15 格原样留着，缺的是判据）──────────────────
            // ① 组名缺失时**必须自己说出来**。等长组按构造必然稳、长短悬殊组才答
            //    "换批大小会不会改答案" —— 两组的同一串数字含义相反。只报数字不报
            //    来源时，"逐位相同 4/4" 会被读成后者也稳。所以这里不是"没报就不说"，
            //    而是**明说没法归因**：不猜组，也不让读者以为这句是长短悬殊组的结论。
            if (grp.isEmpty()) {
                sb.append("（⚠ 服务端没报取自哪一组 —— 等长组按构造必然稳，" +
                    "上面这串数字**不能**用来回答「换批大小会不会改答案」）")
            }
            // ② `identical + different == rows` 是这组读数的算术自证：
            //    rows 是参与条数、identical 与 different 是互斥的两类。对不上只有两种
            //    可能 —— 有条目缺失（既不相同也不同）或服务端算错。两种都要求"先别用
            //    这组结论"，所以判据放在结论**之前**，而不是等读者自己拿计算器核对。
            if (dfn != null && idn + dfn != n) {
                sb.append("（⚠ 读数自相矛盾：逐位相同 ").append(idn)
                    .append(" + 真的不同 ").append(dfn)
                    .append(" ≠ 参与条数 ").append(n)
                    .append(" —— 中间那 ").append(n - idn - dfn)
                    .append(" 条既不相同也不同（缺失？），这组结论先别用）")
            }
            sb.append(" —— ")
            // ⚠ `identical` 这一档的措辞**跟着组走**：等长组全同时只能说
            //   "这一组（构造上偏稳）没测出差异"，**不能**说"批大小是纯加速旋钮"
            //   —— 那是长短悬殊组的结论，而它恰恰是这格要回答的那句。落进
            //   `/health` 的若仍是等长组（旧版本），这句话就是**用必然成立的那组
            //   回答问题**（本仓库反复记的那族）。
            sb.append(when (pv) {
                "identical" -> parityIdenticalWording(grp)
                "close" -> "不是逐位相同（浮点级差异），**不能**据此沿用"
                "different" -> if (dfn == null) {
                    "有条目**真的不同**，批大小不能用纯加速来解释（沿用会把两种语义拼起来）"
                } else if (n > 0 && dfn >= n) {
                    "**每一条**都真的不同 —— 合批**普遍**改变了语义，批大小这条路整体不可信"
                } else if (dfn > 0) {
                    "只有 $dfn/$n 条真的不同（不是整批）—— 更像**某几条**踩到了边界" +
                        "（不等长合批的 padding / 超长那条的截断点），先查这几条再决定要不要收窄结论"
                } else {
                    "结论说有不同的条目，但不同的条数是 0（读数自相矛盾，先看服务端怎么算的）"
                }
                "missing" -> "有条目缺失，补齐后再判（缺失不是相同也不是不同）"
                else -> pv
            })
            // 「同路径重复」那两句 —— 它们把"差异来自哪里"分成**三种**下一步。
            // 分岔点有两处，且处置各不同：
            //   · 逐条那条路自己重复就有不同 -> **先别谈批大小**（编码本身不确定）；
            //   · 逐条全绿、但**合批**自己重复就有不同 -> 一样先别谈批大小
            //     （合批自身不确定，与"批布局改语义"是两件事）；
            //   · 两条路各自都全绿 -> 差异来自**批布局**（这才轮到"批大小能不能放行"）。
            // 没测过（`...Measured == false/null`）时**不能**说"全绿"：
            // `notIdentical == 0` 在没测时也是 0，把它读成全绿会把"没测过"说成
            // "验过了、没问题"（与 `repeatMeasured` 分开报同一条理由）。
            when (r.embedParityRepeatMeasured) {
                true -> {
                    val rn = r.embedParityRepeatNotIdentical ?: 0
                    if (rn > 0) {
                        sb.append("；⚠ 同路径重复（逐条）就有 $rn 条不是逐位相同 —— " +
                            "**先别谈批大小**（连同一条路自己都不能复现自己，是编码本身不确定）")
                    } else {
                        // 逐条自重复全绿 -> 再看**合批**那条路自己重复的结果。
                        // 这一格不读时，"合批自身不确定"会被当成"批布局改语义"。
                        when (r.embedParityRepeatBatchedMeasured) {
                            true -> {
                                val bn = r.embedParityRepeatBatchedNotIdentical ?: 0
                                sb.append(if (bn == 0) {
                                    "（同路径重复全绿：逐条、合批**两条路各自都复现得了自己**" +
                                        " —— 差异来自**批布局**，不是编码本身不确定）"
                                } else {
                                    "；⚠ 逐条重复全绿，但**合批**那条路自己重复就有 $bn 条不是逐位相同" +
                                        " —— 那是**合批自身不确定**，**先别谈批大小**（先查合批那条路）"
                                })
                            }
                            false -> sb.append("（逐条重复全绿，但**没做**合批那条路的重复对照 —— " +
                                "「合批自身不确定」与「批布局改语义」在这里分不开）")
                            // null = 服务端没报（旧版本）：不猜，只说逐条那半句
                            else -> sb.append("（同路径重复（逐条）全绿；" +
                                "合批那条路的重复对照这次没有读数）")
                        }
                    }
                }
                false -> sb.append("（本次**没做**同路径重复对照 —— " +
                    "差异来自批布局还是编码本身不确定，这里分不开）")
                // null = 服务端没报这个字段（旧版本）：什么都不说，不猜
                else -> {}
            }
        }
        // 「检索对照」那一句（ISSUE #269 第 5 步，与上面那组**配对**的另一半）。
        // ⚠ 它**必须**与"向量逐位比对"分开说：向量不同**不蕴含**答案不同，
        //   而"顺手把前者读成后者"正是这一格要挡住的推断（见 EmbedRetrieveParity
        //   对象头）。所以这里只报**量出来的**那件事：命中集合变没变。
        if (r.embedRetrieveMeasured == true && (r.embedRetrieveQueries ?: 0) > 0) {
            val qn = r.embedRetrieveQueries ?: 0
            val changed = r.embedRetrieveAnswerChanged ?: 0
            val order = r.embedRetrieveOrderChanged ?: 0
            // ⚠ 这里的三个 `?: 0` 是**只给措辞用**的默认值，判据另算：
            //   批号与 topK 是这组读数的**坐标系**——不知道比的是哪两种批大小、
            //   取前几名，那个"命中集合变了 3 条"就无法复核（换 k 本来就会换集合）。
            //   所以缺失时不说 0，明说"没报"。
            val bB = r.embedRetrieveBatchBase
            val oB = r.embedRetrieveBatchOther
            val k = r.embedRetrieveTopk
            val frame = if (bB != null && oB != null && k != null && bB > 0 && oB > 0 && k > 0) {
                "跳批大小 $bB vs $oB，topK=$k"
            } else {
                "⚠ 比的是哪两种批大小 / 取前几名没报全（base=${bB ?: "未报"} " +
                    "other=${oB ?: "未报"} topK=${k ?: "未报"}）—— 这组对照无法复核"
            }
            sb.append("\n检索对照（").append(frame).append("）：$qn 条查询")
            // ③ 三种计数必须落在查询条数之内：多出来说明它们不是从同一趟里数的。
            if (changed > qn || order > qn) {
                sb.append("（⚠ 读数自相矛盾：集合变了 $changed / 名次变了 $order 超过查询条数 $qn）")
            }
            sb.append(when {
                changed > 0 -> "里有 $changed 条**命中集合变了** —— 换批大小换了**答案**，" +
                    "`g` 不能按库放行（注意：这与「向量是否逐位相同」是两件事）"
                order > 0 -> "命中集合全同，但 $order 条的名次变了（打平处换序 —— " +
                    "与「要不要放行 `g`」不是同一件事）"
                else -> "命中集合与名次都逐位相同 —— 这批文本上，换批大小没换答案" +
                    "（**这是量出来的**，不是从「向量是否有差异」推出来的）"
            })
        } else if (r.embedRetrieveMeasured == false) {
            sb.append("\n检索对照：本次**没测**（没测时「命中没变」也是 0 条，读之前先看这一格）")
        }
        }
        // 只有 status=ok 却没带 model_loaded 才算异常；status 本来就非 ok 时上面已说明过了
        if (r.httpCode == 200 && r.status?.lowercase() == "ok" && r.modelLoaded == null) {
            sb.append("\n⚠ 响应里没有 model_loaded 字段，无法确认模型状态")
        }
        if (r.body.isNotBlank()) renderBody(sb, r)
        return sb.toString()
    }

    /**
     * `embed_parity_*` / `embed_retrieve_*` 那十几个字段的**完整读数**，一行一条。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么它们从"报告正文"搬到"日志"
     * ═══════════════════════════════════════════════════════════════════════
     * 这些字段是 0.9.223 起为排查「批大小 `g` 能不能按库放行」逐格补上的：
     * 参与条数、逐位相同条数、真的不同条数、取自哪一组、同路径重复（逐条 / 合批）
     * 做没做、命中集合变了没变、名次变了没变、两侧批大小、topK……
     *
     * 它们**每一格都有理由**，但那个理由是**排查场景**的：用户日常打一次探活，
     * 要的是"服务活没活 / 模型装没装 / 库认的批大小是多少"这种**一句话能指导
     * 下一步**的信息。把十几格原始读数铺在它下面，结果是**信息量大、可用信息少**
     * ——报告长了三倍，而多出来的部分看完不知道该做什么（用户的反馈原话：
     * *"信息太乱了，对用户也没实际用处"*）。
     *
     * ⚠ 所以要分开的**不是"报不报"**，是"**报给谁看**"：
     *   · 报告正文（用户）—— 只留能直接决定下一步的结论；
     *   · 日志（排查者）—— **完整**读数照旧，一格不少；
     *   · `/health` 响应体（监控 / 旧客户端）—— **一个字段都没动**。
     *
     * 「完整信息不丢」这条是硬要求：`/health` 有十几个字段、报告里只剩几行，
     * 若中间没有一处如实落下来，那些字段就成了"写在服务端、谁也没读过"的摆设
     * （本仓库反复记录的那族：算出来没人读 == 没算）。日志就是那个落点。
     *
     * ⚠ 落 [Result.embedParityGroup] 那类**来源**字段要一并落下来：只报数字时
     * "逐位相同 4/4"在两组样本下**字面一样、含义相反**（见 [parityIdenticalWording]）。
     */
    fun rawEmbedProbeLines(r: Result): List<String> {
        val out = ArrayList<String>()
        // 逐位比对那一组。`rows` 为 0 或结论为空 = 还没跑过，那一格就不落
        // （落一行"没跑过"是噪音，而噪音会被跳过）。
        val pv = r.embedParityVerdict.orEmpty()
        if (pv.isNotEmpty() && (r.embedParityRows ?: 0) > 0) {
            out.add("[探测] embed_parity 结论=$pv 条数=${r.embedParityRows} " +
                "逐位相同=${r.embedParityIdentical ?: 0} 真的不同=${r.embedParityDifferent ?: 0} " +
                "取自=${r.embedParityGroup.orEmpty().ifEmpty { "(未报)" }}")
            // 「测没测」必须与"几条不同"分开落：0 在没测时也是 0，两件事方向相反。
            out.add("[探测] embed_parity 重复对照：逐条 做没做=${r.embedParityRepeatMeasured ?: "未报"}" +
                " 不是逐位相同=${r.embedParityRepeatNotIdentical ?: 0}；" +
                "合批 做没做=${r.embedParityRepeatBatchedMeasured ?: "未报"}" +
                " 不是逐位相同=${r.embedParityRepeatBatchedNotIdentical ?: 0}")
        }
        // 检索对照那一组（与上面配对的那一半 —— 向量那几格答不了"答案变没变"）。
        if (r.embedRetrieveMeasured != null) {
            out.add("[探测] embed_retrieve 测没测=${r.embedRetrieveMeasured} " +
                "查询=${r.embedRetrieveQueries ?: 0} 集合变了=${r.embedRetrieveAnswerChanged ?: 0} " +
                "名次变了=${r.embedRetrieveOrderChanged ?: 0} " +
                "批大小=${r.embedRetrieveBatchBase ?: 0} vs ${r.embedRetrieveBatchOther ?: 0} " +
                "topK=${r.embedRetrieveTopk ?: 0}")
        }
        return out
    }

    /**
     * 「逐位相同」这一档的措辞 —— 它**跟着取样的那一组走**（[Result.embedParityGroup]）。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么同一档读数要用两句不同的话
     * ═══════════════════════════════════════════════════════════════════════
     * 两组样本回答**两个不同的问题**，且方向相反：
     *
     *   · **等长**组把"长度齐不齐"拿掉了，它全同只说明"批布局本身不改语义"
     *     —— 构造上偏稳；
     *   · **长短悬殊**组是唯一能改变答案的那组（含超长 / 截断）。
     *
     * 同一句"整批逐位相同，批大小是纯加速旋钮"跟在两组后面时，**字面一样、
     * 含义相反**：前者是"这一组（必然稳的）没测出差异"，后者才是"换批大小
     * 不改向量"。真机现场（PR #301）正是拿等长组那句去答"长短悬殊会不会变"
     * —— 而这格存在的全部理由就是判「`g` 能不能按库放行」。
     *
     * 落进 `/health` 的**本该**是长短悬殊那组（见 `probeEmbedBatchParity`）；
     * 这里仍要分句，是因为旧版本的服务端可能落的是等长组（`group` 会说），
     * 而报告不能替它把话说满。组名缺失（旧版本没这个字段）时给一句**最弱**的
     * 说法：只说这一批逐位相同，不承诺"纯加速旋钮"。
     */
    private fun parityIdenticalWording(group: String): String = when (group) {
        EmbedLimits.kLongShortLabel ->
            "整批逐位相同，批大小是**纯加速**旋钮（可考虑沿用）"
        EmbedLimits.kSameLenLabel ->
            "**这一组（构造上偏稳）**整批逐位相同 —— 它只说明批布局本身不改语义，" +
                "**答不了**「长短悬殊那批换批大小会不会改答案」（那组才是放行 `g` 的依据）"
        else ->
            "这一批逐位相同（没报取自哪一组 —— 不能据此推出「长短悬殊那批也一样」）"
    }

    /**
     * 「响应原文」那一行 —— 方案 1：**不再回显整份正文**，只说它去哪儿看。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么"报告里那一大坨"不是字段多，是这一行
     * ═══════════════════════════════════════════════════════════════════════
     * 用户贴回的探活报告里有 51 个字段，看着像"探活塞了太多东西"。但把
     * [format] 的正文段从头数一遍会发现：`embed_parity_*` / `embed_retrieve_*`
     * 那十几格**早在 0.9.231 就不进报告了**，真正把 51 个字段一字不落带进来的
     * 是**末尾这一行** —— `BODY_PREVIEW_CHARS` 是 4096，而真机那份 `/health`
     * 正文 1249 字符，**根本没触发截断**，等于整份原件回显。
     *
     * 所以"信息太乱"与"`/health` 有几个字段"是**两件事**：删字段删的是
     * "服务端怎么回"（那会把监控 / 旧客户端 / 判据一起砸掉），改这一行改的才是
     * "报告给用户看什么"。
     *
     * ═══════════════════════════════════════════════════════════════════════
     * 为什么不是"把正文删掉"
     * ═══════════════════════════════════════════════════════════════════════
     * 因为**给出去的证据必须能被独立复核**：用户点一次探活，就是为了拿一份
     * 能贴给别人看的东西。全绿时省掉原文，等于把"凭什么说它活着"藏起来。
     * 所以这里**照旧回显**，但只在**异常**时回显：
     *
     *   · 判定为 [Verdict.ALIVE]（全绿）—— 不贴原文。完整响应在日志里
     *     （`[探测]` 那几行 + `/health` 响应体本身），报告只写去哪儿看；
     *   · 其余情况（DEGRADED / UNREACHABLE，以及**正文不是合法 JSON**）——
     *     **照旧回显**（含截断规则）：这时原文本身就是"到底哪里不对"的唯一证据，
     *     把它省掉，用户就只能拿着"不可达"三个字来问。
     *
     * ⚠ 截断规则不变（[BODY_PREVIEW_CHARS]）：**截断必须自证**。默默 `take(N)`
     * 的后果不是"少显示一段"，而是**证据变成伪证** —— 残片天然非法 JSON，
     * 而结论区里就挂着"响应体不是合法 JSON"，读者只能把报告自己造的截断
     * 读成服务端的错。
     */
    private fun renderBody(sb: StringBuilder, r: Result) {
        val flat = r.body.replace("\n", " ").replace("\r", " ")
        // 全绿且正文是本服务的合法 JSON -> 不复述原件（那一坨 51 个字段对
        // "接下来该做什么"没有任何指向），只留一句去哪儿看。
        if (verdict(r) == Verdict.ALIVE && r.jsonParsed) {
            sb.append("\n（完整响应不在此复述：日志里搜「[探测]」，或直接 GET " +
                "/health —— 上面几行是它的结论）")
            return
        }
        sb.append("\n响应原文：${flat.take(BODY_PREVIEW_CHARS)}")
        if (flat.length > BODY_PREVIEW_CHARS) {
            sb.append("…（已截断，完整 ${flat.length} 字符；截断处不是响应的问题）")
            return
        }
        // 在整段正文里再点一次它与结论的关系：结论在最上面，正文在下面，
        // 两段嵌在一起，报告又会被整段复制出去，不点明就会各读各的。
        //
        // ⚠ 200 + 正文不是 JSON 时**不许**在这里下"不可达"的结论（#384 真机）：
        //   服务端的 `/health` 自己会做一次 `JSONObject(手拼结果)` 自检，失败时
        //   回的**就是** 200 + 一条 `health json malformed: ...`。也就是说，
        //   "正文不是合法 JSON"的两种来源方向相反：端口上根本不是本服务（该判不可达），
        //   与本服务在报自己拼错了（服务可达、病在服务端）。措辞已按 [verdict] 分档，
        //   这里只复述那一档，不另立一套 —— 两处各写一套必然漂移。
        if (r.jsonParsed) {
            sb.append("\n（以上正文与判定一致：合法 JSON / 状态码，结论不来自这段预览）")
        } else {
            sb.append("\n（以上正文即服务器所回原文，未被截断；")
                .append(when (verdict(r)) {
                    Verdict.UNREACHABLE -> "它不是合法 JSON，故判定为不可达"
                    else -> "它不是合法 JSON —— 但服务确实回了 HTTP 200，" +
                        "故判定为「可达、状态不完整」，不是不可达"
                })
                .append("）")
        }
    }

    /**
     * 真发请求。阻塞式，调用方负责放到后台线程。
     * 连接与读都设 [timeoutMs]，避免"探测按钮转圈不停"这种更糟的体验。
     */
    fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        path: String = "/health",
    ): Result {
        val t0 = System.nanoTime()  // elapsedMs 只作差，单调钟（墙钟跳变会算出负耗时）
        var raw = ""
        var readTimedOut = false
        try {
            Socket().use { s ->
                // 连接与读分开设超时：连不上通常是"端口没开"（立刻 refused），
                // 而读到一半卡住才是"假活态"，两者提示语不同，超时值也该区别对待。
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply {
                    write(buildRequest(port, path).toByteArray(Charsets.UTF_8))
                    flush()
                }
                raw = readAll(s.getInputStream(), timedOut = { readTimedOut = true })
                // 读超时（连上了但对方一个字节都没回）不能和"连接被拒"混为一谈：
                // 前者正是要区分的假活态（端口在、没人 accept），后者是端口根本没开。
                // readAll 为了不让探测自己卡死会吞掉超时，所以在这里补回这个事实。
                if (raw.isEmpty() && readTimedOut) {
                    return Result(httpCode = null, body = "", status = null, modelLoaded = null,
                        modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                        transportError = "读取超时（连接已建立但 ${timeoutMs} ms 内无任何响应字节，" +
                            "端口在监听却无人 accept —— 典型的假活态）",
                        elapsedMs = (System.nanoTime() - t0) / 1_000_000L)
                }
            }
        } catch (e: Throwable) {
            val ms = (System.nanoTime() - t0) / 1_000_000L
            return Result(httpCode = null, body = raw, status = null, modelLoaded = null,
                modelDesc = null, ctxUsed = null, ctxSize = null, busy = null, port = null,
                transportError = transportReason(e), elapsedMs = ms)
        }
        return parse(raw, (System.nanoTime() - t0) / 1_000_000L)
    }

    /** 把异常翻译成用户能据以行动的短句；直接抛 e.message 常见是 null，等于什么都没说。 */
    private fun transportReason(e: Throwable): String = when (e) {
        is java.net.ConnectException -> "连接被拒绝（端口未监听或绑定地址不是本机回环）"
        is java.net.SocketTimeoutException -> "连接或读取超时（端口在但无人应答，属假活态）"
        is java.net.UnknownHostException -> "地址无法解析：${e.message}"
        is java.net.NoRouteToHostException -> "路由不可达（IP 或子网不对）"
        is java.io.IOException -> "网络异常：${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * 读到 EOF 或读满上限为止。
     * 上限不是省事，是护栏：/health 只有几十字节，若对方一直吐数据（连到的不是本服务），
     * 这里必须能收手，否则探测自己会变成一个新的卡死点。
     */
    private fun readAll(
        ins: InputStream,
        cap: Int = 64 * 1024,
        timedOut: (() -> Unit)? = null,
    ): String {
        val buf = ByteArray(4096)
        val out = java.io.ByteArrayOutputStream()
        while (out.size() < cap) {
            val n = try { ins.read(buf) } catch (_: java.net.SocketTimeoutException) {
                timedOut?.invoke(); break
            }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
