package com.xiaowan.localinference

/**
 * 上下文预算的**纯逻辑**部分（Issue #330）：发请求之前先算「还装得下几条」。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么要有这个文件
 * ══════════════════════════════════════════════════════════════════════════
 * 用户报的现象是「客户端设的 Max Context 与服务端一致，可一到长度就生成失败」。
 * 根因不是某一处写错，而是**两边用了两把尺子**：
 *   · 客户端按 `n_ctx`（总量）裁；
 *   · 服务端**每格就是设置页那一格**，但并发时它被几路瓜分（ISSUE #426 第八回合：
 *     分的是"同时有几路在跑"，不是容量被除了一次）。
 * 于是 `parallelN=2` + `n_ctx=4096` 时，客户端以为有 4096、服务端每格只有 2048 ——
 * 客户端每轮都多送一倍，而服务端**从不检查**，撞上 `tokens >= n_ctx` 才失败，
 * 失败文案还是一句"prompt 过长或 decode 出错"。
 *
 * 本文件只回答三件事，不碰 HTTP、不碰 native：
 *   ① 这一轮的服务端容量是多少（[capacity]）；
 *   ② 按容量该丢哪些**消息**（[plan]）；
 *   ③ 预算结论怎么变成一句人话（[describe]）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么是「丢最老的一对」而不是「丢到刚好装下」
 * ══════════════════════════════════════════════════════════════════════════
 * 丢消息会让 KV 前缀的分叉点往前挪，上一轮的缓存用不上（`RagInject` 的注释里
 * 记着这件事：注入只动最后一条 user，就是为了让分叉点落在本轮提问处）。
 * 既然每次丢都要付一次全量 prefill，那就**一次丢到位**、并且丢成"每轮同一形态"：
 * 每轮按同一份预算裁，连续几轮之后前缀会稳定在"被裁过的那个起点"上，
 * 只在真正需要再丢一轮时才断一次。丢到刚好装下看起来更省，代价是**每轮都在断**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 估算口径（**宁可高估，不许低估** —— 这一条是本文件最要紧的约束）
 * ══════════════════════════════════════════════════════════════════════════
 * 宿主侧没有 tokenizer（native 才有），而这一段判据必须能在宿主单测里跑。
 * 所以这里按字符**分类**折算（[TOKENS_PER_CJK_CHAR] / [TOKENS_PER_ASCII_CHAR]）：
 * 中文 1 token/字、英文 ~0.28 token/字符。
 *
 * 第一版取 0.5 token/字符，注释写着"保守侧（估得多 = 多丢一点 = 宁短不错）"——
 * **那个方向是反的**：低估 prompt = 以为装得下 = 一条都不丢 = 渲染后进 native 超了
 * 几个 token —— native 那一刀（`ctxbudget.h` 的 `trim_start`）从**头部**砍掉最老的一段，
 * KV 前缀起点每轮往后挪一刀，复用掉到 system 段的长度（真机报告里那个 `152`）。
 * 而整条链路上**没有一处报错**：预算说"装得下"、prefill 说"OK"、复用说"152"。
 *
 * 一条中文 ≈ 1 token，0.5 就是**低估一半**；再加上渲染侧还会补的东西
 * （BOS / 模板自己的默认 system 段 / 生成后缀 `<|im_start|>assistant\n` /
 * 思考开关与软开关注入的字节 / 知识库注入块），窄带是必然踩到的。
 *
 * 但"宁可高估"**不等于**"一律乘 1.0"。整段文本乘 1.0 时，英文被高估 4~7 倍 ——
 * 那不是保守，是另一头的错：预算以为装不下、把历史丢了、把生成长度削到地板，
 * 而真 token 数离容量线还差几千枚（ISSUE #426 真机：`tokenize_need=-291` 的那一轮
 * 被报成「约 591 tok，生成空间只剩 2 tok」，于是每一轮的回答都被砍短）。
 *
 * 所以现在的口径是：
 *   · 按字符**分类**折算（CJK 1.0 / ASCII 0.28 / 其余 1.0，见下面三个常量）；
 *   · 每条消息按模板壳的真值上限估（[PER_MESSAGE_OVERHEAD]）；
 *   · 整段再补一笔渲染侧固定开销（[RENDER_OVERHEAD]），并让出本轮真正的生成后缀。
 * 多估的代价是"早丢一轮历史"；低估的代价是**每轮全量 prefill**。
 * 分类折算让"英文被高估"这一头也收住了 —— 剩下的偏差方向仍然只许偏多。
 *
 * ⚠ 真正的截断判据仍在 native（`ctxbudget.h` 的 `plan`，按**真 token**算）。
 *   这里只负责让"发过去的 prompt 一定装得下"，从而**不触发** native 那一刀 ——
 *   两边口径一致靠的是同一个 `kMinGenTokens` 与同一条"先削 gen 再削 prompt"，
 *   数值上允许有偏差，方向只许是"这边估得更多"。
 */
object ContextBudget {

    /**
     * 生成 token 的保底空间（与 `app/src/main/cpp/ctx_budget.h` 的
     * `ctxbudget::kMinGenTokens` **同值同义**）。
     *
     * 判据是「prompt 与生成共用一池位置，且最后要留位置给下一次采样」。
     * 写成 public const 是为了让守卫能拿它去和 C++ 那份对值（两边分叉过一次
     * 就会变成"Kotlin 以为留了 2、native 以为留了 0"）。
     */
    const val MIN_GEN_TOKENS = 2

    /**
     * [plan] 的 `systemTokens` 缺省值：`-1` = **不知道**（老调用点没传）。
     *
     * 判据是"谁真的量过"：知识库注入块的真实长度只有渲染它的人知道，
     * 拿一条长度为 0 的空消息冒充它，等于把那一块又从预算里抹掉（ISSUE #330）。
     */
    const val SYSTEM_TOKENS_UNKNOWN = -1

    /**
     * 越线那一刀的落点：丢完之后 `prompt + gen` 占水位线的**多少**（`%`）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这条线回答「丢到哪」，不回答「什么时候丢」（ISSUE #586 第三回合）
     * ══════════════════════════════════════════════════════════════════════════
     * 起判线是 `avail − gen`（水位线减掉这一轮的生成空间）：历史涨到那儿，`prompt + gen`
     * 正好顶到水位线，那一刻该动手。`max_tokens` 越大，动手越早 —— 这一条本来就对。
     *
     * 但它回答不了落点。「冷静下来之后这一轮该占多少」与「什么时候算越线」是两件事，
     * 拿前者的基数去写后者（`trigger / 2`）时，`max_tokens` 大（半格上下）的档上
     * 落点直接塌成 0 甚至负数 —— 而大 `max_tokens` 正是大 ctx 用户的唯一选法。
     *
     * 所以落点自己一条线：**占用比 × 水位线**，`gen` 在里面占位（`− wantGen`），
     * 不是额外的扣减。50% 意味着丢完合计在 `0.5 × avail` 上下，离起判线空出一大截 ——
     * `4096 / 85% / 1024` 那一档约七八轮，`32768 / 95% / 2048` 那一档几十轮。
     *
     * ⚠ 40 → 50 是 ISSUE #586 真机调的：40% 那几个档落点比用户预期低（`4096 / 90% / 1024`
     *   只剩一百多枚 prompt），历史留不住；50% 把落点抬回"半水位"，代价是下一次动手
     *   更早（余量从 1273 枚收到 1024 枚，`4096 / 85 / 1024` 那一档从 5~10 轮收到 4~8 轮）。
     *
     * ⚠ 与 native 的 `ctxbudget::kHalfRatioPct` **同值**（守卫钉着）：`halfWatermarkKeep`
     *   收这个比例当形参，两边算同一个落点。名字里的 `HALF` 是历史包袱（它一开始就是
     *   "半水位"= 50%），现在它是一个可调的比例，50 就是最早那个"丢到一半"的语义，
     *   40 与 50 的差别只是"丢得更狠一点、下一次动手更晚一点"。
     * ⚠ 调小它 = 历史留得更少、两次动手之间更长；调大它 = 留得更多、动手更频繁。
     *   这是唯一的一条取舍，别再从 `max_tokens` 那一侧绕回去。
     */
    const val HALF_RATIO_PCT = 50

    /**
     * 落点**越过**它的那一截，要留够「下一轮涨一轮也不越线」的余量（`%`，按**这条线的口径**算）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这条余量兜的是「整组丢」的粒度，且**不许跨轴**（ISSUE #586）
     * ══════════════════════════════════════════════════════════════════════════
     * 落点是一条线，而 [keptOf] 只能**整组**整组地丢 —— 一组就是一轮
     * （`user + assistant`，短对话 90 tok、长回答 600 tok）。于是"第一个装得下的档"
     * 总是**越过**那条线而不是落在线上；历史再涨一轮就又越线，"丢一次管几十轮"
     * 从来没兑现过。
     *
     * ⚠ **余量必须与它兜的那条线同口径**（`prompt + gen`，即 `avail`），不能按"半边"
     *   算：落点已经是 `半水位 − 意愿生成量`，而水位量的是 `prompt + gen` —— 在这条
     *   线上再砍一刀"半边"的百分之几，等于把生成那一侧又扣了一次。真机形态
     *   （`ctx 4096 / 水位 85 / max_tokens 1024`）：按 `avail/2 × 6%` 算只有 104 枚，
     *   而这一档一组就是 250 tok —— 余量连一组都不够，落点照旧退到只剩提问。
     *
     * ⚠ 也别按**绝对 token 数**写死：按水位线的比例算，换 `ctx` 时同比例收。
     *
     * ⚠ 它进的是**落点**，不是"装不装得下"（`fits` 那条线仍是 `avail`）：
     *   落点只回答"丢到哪才不用再丢"，与"这一轮能不能答"是两件事。
     * ⚠ 与 [PROMPT_EST_SAFETY_PCT] 不是同一笔：那一笔兜"估算的方差"，按**整段**算；
     *   这一笔兜"整组的粒度"，按**水位线**算。两笔都不进 `local_ctx` 的读数。
     */
    const val HALF_HEADROOM_PCT = 6

    /**
     * KV 复用门槛（与 `app/src/main/cpp/kv_prefix.h` 的 `kvprefix::kMinReuseTokens`
     * **同值同义**）。
     *
     * 判据是"公共前缀短于它就不值得复用"（KV 操作的固定开销比省下的 prefill 还大）。
     * 这一份**只读**：用它回答"丢掉的那一段够不够当可复用前缀"，不参与裁不裁的判断。
     */
    const val KV_MIN_REUSE_TOKENS = 16

    /**
     * 一个 **CJK / 全角** 字符折算多少 token。
     *
     * 实测 0.6~0.65 汉字一枚 token（BPE 词表把常用汉字两两合成一枚）。取 0.62 是
     * **站在实测上沿**，再叠 [PROMPT_EST_SAFETY_PCT] 那一笔整体余量。
     *
     * ⚠ 这一行与 [TOKENS_PER_ASCII_CHAR] 是**实测值**，所以它们只用于**报告**
     *   （`prompt_est`，与 native 的 `prompt_real` 同口径相比）。**判据**用的是
     *   [JUDGE_TOKENS_PER_CJK_CHAR] / [JUDGE_TOKENS_PER_ASCII_CHAR] —— 两张表分开是
     *   ISSUE #452 真机的结论：把实测值同时喂给判据之后，
     *   「真 3661 / 估 3114」配上「越线一档就丢掉一半历史」，用户看到的就是
     *   "裁剪之后只吐几个字"。详见 [JUDGE_TOKENS_PER_CJK_CHAR] 那段。
     *
     * 这里曾经是 1.0，理由写在别处："中文一个字至少 1 token"。那是**两个不同年代**的
     * 结论混在了一起：
     *   · 当初要防的是 0.5（把中文砍一半）—— 那个方向确实危险，因为低估会让 native
     *     每轮从头部砍一刀、复用归零；
     *   · 但 1.0 是另一头的错，而且它**恒定高估 1.5~1.6 倍**：真机 ISSUE #452 的读数
     *     是 `prompt_real 329 / prompt_est 3767`，预算按后者判"装不下"，把几轮历史丢掉，
     *     而 native 那边一路装得下。用户原话就是"估算超很多，白丢会话"。
     * 两头都错，正确的位置是实测值 —— 偏多的那一侧由固定开销与整体余量兜（它们不随
     * 长度漂），而不是靠把一个逐字常数抬高一倍。
     */
    const val TOKENS_PER_CJK_CHAR = 0.62

    /**
     * 一个 **ASCII / 半角** 字符折算多少 token（0.28 ≈ 每 3.6 字符一枚）。
     *
     * 英文的实测口径（OpenAI 的通用经验值：4 字符 ≈ 1 token；中文 ≈ 1.5 字符 ≈ 1 token）。
     * 取 0.28 而不是 0.25 是为了**站在高估那一侧**：算多了的代价是早丢一轮历史，
     * 算少了的代价是 native 每轮从头部砍一刀、KV 前缀复用归零（见文件头）。
     */
    const val TOKENS_PER_ASCII_CHAR = 0.28

    /**
     * 其余字符（拉丁扩展、西里尔、emoji 之类）折算多少 —— 与 CJK 同档。
     *
     * 判据是"宁可高估"：这些字符在 BPE 里很少便宜过 CJK。
     */
    const val TOKENS_PER_OTHER_CHAR = 1.0

    /**
     * **判据**用的 CJK 折算率 —— 与上面那张实测表分开，这个数只回答"这一轮装不装得下"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么判据不许用实测值（ISSUE #452 真机：裁剪之后只吐几个字）
     * ══════════════════════════════════════════════════════════════════════════
     * 上一版把 [TOKENS_PER_CJK_CHAR] 从 1.0 收到实测的 0.62，报告立刻变准了
     * （`真 3661 / 估 3114`），**但判据也跟着变松了** —— 而判据松的那一侧正在
     * native 的越线档上：真 token 一旦超过水位（85%），native 会按"丢到半水位"
     * 砍掉一大段历史，并把 `gen` 削到地板 64。
     *
     * 于是真机上出现的是这个形状（日志 某真机 session）：
     *
     *   装得下（prompt ≤ 3481）：`gen_planned 1024 / n_rem 1024`，实际生成 337 tok；
     *   真值越过 3481 那一轮：     `gen_planned 1024 / n_rem 64`，实际生成 **64 tok**；
     *   之后每一轮：               `丢最老 1862/1908/1915 tok`、`复用 0`、生成 64。
     *
     * 用户看到的就是"裁剪之后只吐几个字，重生成也只吐几个字"。而这一档本来该更晚
     * 才到 —— 判据按实测值算，等于**让估算提前放弃它本该保住的那几十上百枚余量**。
     *
     * 所以两张表分开，且各自只有一个用途：
     *   · 判据（`tok` / `tokAll` / `fits`）用**保守**这一张：宁可早丢一轮历史，
     *     也不许把"真 token 会越线"这件事漏给 native —— 那一刀的代价是半格历史
     *     加 gen 掉到地板；
     *   · 报告（`prompt_est`）用**实测**那一张：它要与 native 的 `prompt_real`
     *     并排读，差值是"估算准不准"，不是"判据松不松"。
     *
     * ⚠ 两张表的差值就是"判据主动留的那一道余量"，它随长度**线性**长 ——
     *   这正是你要的：越长的会话越需要提前一轮丢，而不是贴线滑到 native 那一刀上。
     * ⚠ 不许把两张表合成一张：合到实测值 -> native 先动手（这一条 ISSUE 就是它）；
     *   合到保守值 -> 报告永远偏多，"估算超很多白丢会话"那个读数又回来了。
     */
    const val JUDGE_TOKENS_PER_CJK_CHAR = 0.80

    /**
     * **判据**用的 ASCII 折算率（≈ 每 3 字符一枚）。理由与 [JUDGE_TOKENS_PER_CJK_CHAR] 同。
     *
     * 英文的实测值在 0.25~0.28 之间，而判据取 0.35：英文会话里"少算一枚"同样是
     * 把越线推给 native，代价一样是丢半格历史。
     */
    const val JUDGE_TOKENS_PER_ASCII_CHAR = 0.35

    /**
     * **判据**用的其余字符折算率（与判据 CJK 同档）。
     */
    const val JUDGE_TOKENS_PER_OTHER_CHAR = 1.0

    /**
     * 一条消息的**固定开销**：`<|im_start|>role\n` + `<|im_end|>\n` 这一层壳。
     *
     * 真值随模板在 5~10 token 之间浮动（有的模板还会插 `\n\n`、有的会带 `<|end|>`
     * 之类的单标记），按上限取。不带这一项时一串短消息会被估成 0 token，而 64 条
     * 短消息能实吃掉几百 token —— 那时估算说"装得下"，native 说"我砍了"。
     */
    const val PER_MESSAGE_OVERHEAD = 10

    /**
     * 整段 prompt 的**渲染侧固定开销**：BOS / 模板默认 system 段 / 收尾换行。
     *
     * 这一笔以前完全没有，于是"估算 N、渲染后 N+几十"，而 `plan()` 的判据是
     * `tokens + gen <= avail` —— 那几十 token 正好落在窄带里：预算说装得下、
     * native 那一刀照样落下来。取值按 Gemma/Qwen 一类模板的实测上沿给。
     */
    const val RENDER_OVERHEAD = 64

    /**
     * 生成后缀**不在消息里**，却真实占着 prompt 的位置。
     *
     * `applyChatTemplate(addAss = true)` 会在末尾补 `<|im_start|>assistant\n`
     * （部分模板还带 `<think>\n`），软开关再补一段空 think 块。其中模型**一定会吐**的那部分
     * （`<|im_start|>assistant` + 至少一个换行、外加一个收尾 token）按 32 估；
     * 若本轮生成后缀里含思考起始标记（由 [generationSuffixTokens] 从渲染侧真值取），
     * 取更大的一档 —— 那是"模型可能先想一段"的固定字节，与它想多长无关。
     */
    const val GEN_SUFFIX_BASE_TOKENS = 32

    /**
     * 生成后缀里**带思考起始标记**时额外要占的位置（`<think>\n` + 闭合段）。
     *
     * 只用于"装得下吗"的判断，不影响 [plan] 的 `maxTokens` 语义：
     * 它是 prompt 的一部分，不是可生成的额度。
     */
    const val GEN_SUFFIX_THINK_TOKENS = 24

    /**
     * 估算系数（`real / est`）的**下界** —— 预算最多按"估算值的 70%"去判。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这一格是什么（ISSUE #452 ⑤：把 native 量到的真 token 数回灌进估算）
     * ══════════════════════════════════════════════════════════════════════════
     * 估算口径按 [TOKENS_PER_CJK_CHAR] / [TOKENS_PER_ASCII_CHAR] 折算，方向只许**偏多**
     * （见文件头）。代价是**必然**偏高一点：真机读数 `prompt_real 2649 / prompt_est 4036`
     * 就是一头。于是"预算按估算说装不下"的那几轮里，有一截是估算虚出来的 ——
     * 白丢的历史收不回来，而 native 那边其实还装得下。
     *
     * 所以用 native 每轮量到的真值给估算**校准**一次：系数 = 近期真值 / 近期估算。
     * 但只有**一个**方向可用：
     *
     *   · 系数 < 1（估算偏高）-> 把判据往下调，少丢一点历史 —— 收益，且不改变
     *     谁先动手（仍然是"这边先裁、native 不补刀"）；
     *   · 系数 > 1（估算偏低）-> **一律不用**。低估的代价是 native 从头部砍一刀、
     *     KV 前缀复用归零（整条链路无一处报错，见文件头）。校准不能拿这条去换
     *     "少丢几轮"，所以上界被钳在 [PROMPT_CALIB_MAX] = 1.0。
     *
     * 钳位、滞回、防呆全在 [PromptCalibration] 里：回灌这件事本身就够危险，
     * 再多一个"调参旋钮"只会把它变成另一种不可解释。
     */
    const val PROMPT_CALIB_MIN = 0.70

    /** 系数上界 —— **必须**是 1.0（见 [PROMPT_CALIB_MIN] 的说明：不许往下调估算）。 */
    const val PROMPT_CALIB_MAX = 1.0

    /**
     * 一次校准至少要几个**新鲜的**样本（连续真实轮次）才算数。
     *
     * 1 个样本就改判据不行：`prompt_real` 是"丢头之前"的真 token 数，而它与估算的
     * 比值会被"这一轮刚好触发了丢弃/续接提示"之类的一次性变化带偏（那一轮估算里多
     * 一条消息、真值里也多，但比例并不代表常态）。3 个是"至少连着两轮以上同向"。
     */
    const val PROMPT_CALIB_MIN_SAMPLES = 3

    /** 系数动一次至少要挪多少（0.02 = 两个百分点）—— 挡掉"每轮微调"的抖。 */
    const val PROMPT_CALIB_STEP = 0.02

    /**
     * 系数**回升**那一条路：连续几份"估算偏低"的样本才认。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么必须有它（不加就是一条**单向棘轮**）
     * ══════════════════════════════════════════════════════════════════════════
     * 只向下的校准在真实会话里会踩到一个坏形态：
     *
     *   · 某一轮 `prompt_est` 偏小（短 prompt / 全是英文 / 数字与代码占比高）→
     *     `real/est ≈ 1.06` → 这一份不采用... 但**下一轮**它可能因为别的原因更低；
     *   · 关键在反方向：一旦系数被压到 0.70，而 `TOKENS_PER_CJK_CHAR` 又调准了
     *     （#452 从 1.0 改到 0.62），估算**本来就不再偏高** —— 这时 `real/est` 会
     *     稳定地 > 0.70（比如 0.95）。按旧规则这份样本既"不够近"（差 >1%）又
     *     "不许往上"，于是系数**永远卡在 0.70**，每轮都在低估、每轮都在贴着
     *     native 那一刀跑。这不是"保守"，这是把一个过期的偏差永久固化。
     *
     * 所以给一条有界、滞回、只朝"真值"走的回升路：连续这么[多]份样本都显示
     * 估算偏低（`real/est >= 当前系数 + [PROMPT_CALIB_UP_STEP]`），就往上抬一格。
     *
     * ⚠ 上界仍然是 [PROMPT_CALIB_MAX] = 1.0（[use] 那一处钳）。回升**不是**允许
     *   把估算调到真值以下 —— 它只是把"我们曾经调低、现在不再需要"的那一档收回来。
     * ⚠ 门槛比下降高（[PROMPT_CALIB_UP_SAMPLES] > [PROMPT_CALIB_MIN_SAMPLES]）：
     *   往上走会削弱"偏多"那条保险，所以证据要更扎实。而它慢一格也没关系 ——
     *   偏多的代价是早丢一轮历史，偏少的代价是复用归零，两者不对称。
     */
    const val PROMPT_CALIB_UP_SAMPLES = 6

    /**
     * 回升一次挪多少（0.05 = 五个百分点）。
     *
     * 比下降的 [PROMPT_CALIB_STEP] 大：升的方向是"收回一个我们已经确认过期的折扣"，
     * 一格一格挪会让一个被压到 0.70 的会话要几十轮才回得来 —— 而那几十轮里
     * 每一轮都在白丢历史。慢的代价是"早丢一轮"，快的代价被上界 1.0 与六份样本挡住。
     */
    const val PROMPT_CALIB_UP_STEP = 0.05

    /**
     * 真 token 数与估算值差到多少才**不算**"两把尺子对上了"（相对量，1%）。
     *
     * 不设它时，2649 与 2651 也会被当成一次需要校准的偏差 —— 而那是舍入，
     * 改判据只会让读数每轮都在动（用户看到的"丢几轮"逐轮抖动，正是 #330 修掉的那类）。
     */
    const val PROMPT_CALIB_EPS_PCT = 1

    /**
     * 「我用服务端量到的真 token 数校准估算」—— 一个有界的、只向下的系数。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这个对象自己不做 I/O
     * ══════════════════════════════════════════════════════════════════════════
     * 它是**判据的一级输入**（[plan] 的 `estimateScale`），所以必须能在宿主上单测
     * —— 与 [ContextBudget] 同一条纪律：判据与"存哪"分开。存与取由调用方负责
     * （`LlmEngine` 的两个词表各一份，[keyOf] 给出该用哪个键）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 四条纪律，少一条这个机制就该关掉
     * ══════════════════════════════════════════════════════════════════════════
     * ① **只向下**：系数 ∈ [0.70, 1.0]（[PROMPT_CALIB_MAX]）。≥ 1 的一律按 1.0 ——
     *    低估估算在下游的代价是"native 每轮砍头部、复用归零"，那条路没有校准能救。
     * ② **只认新鲜样本**：[observe] 的 `est` 必须是**这一轮真的用于判据的那个数**
     *    （带着当时的系数），否则 mutate(scale) 会自己乘自己、几轮之内跑到底。
     * ③ **防呆**：`est <= 0` / `real <= 0`（量不到）直接丢样本，不是"当 1.0 记"
     *    —— 后者会把"没量到"混进均值，表现是系数缓慢漂向 1，而没有人知道为什么。
     * ④ **滞回**：新系数与旧系数差不到 [PROMPT_CALIB_STEP] 就不动；样本不足
     *    [PROMPT_CALIB_MIN_SAMPLES] 也不动 —— 判据要稳，"每轮都在改判据"比不改更糟。
     * ⑤ **有界回升**：只向下的棘轮会把一个过期的偏差永久固化 —— 估算口径本身一旦
     *    调准（`TOKENS_PER_CJK_CHAR` 1.0 -> 0.62 就是一次），旧系数就再也不会回到
     *    1.0，于是每轮都在低估、每轮都贴着 native 那一刀跑。所以另给一条
     *    [rise] 的路：连续 [PROMPT_CALIB_UP_SAMPLES] 份样本都指向"估算偏低"才抬一格
     *    （[PROMPT_CALIB_UP_STEP]），上界仍是 [PROMPT_CALIB_MAX] = 1.0。
     */
    object PromptCalibration {

        /** 存盘用的整数上下界（×1000）。日志与 `/health` 都印这一个整数，免得两边各写一份格式。 */
        private const val SCALE_MIN_X1000 = (PROMPT_CALIB_MIN * 1000).toInt()          // 700
        private const val SCALE_JUDGE_MIN_X1000 = (PROMPT_CALIB_JUDGE_MIN * 1000).toInt()  // 550
        private const val SCALE_MAX_X1000 = (PROMPT_CALIB_MAX * 1000).toInt()          // 1000

        /**
         * 一份样本的比值钳到可用的区间（**只向下**）。
         *
         * 上界不是 1.0 而留一丁点余量是不需要的：`real/est > 1` 时我们在下游一律按 1.0 用，
         * 所以这里直接钳到 [PROMPT_CALIB_MAX] 更诚实 —— 存进去的数是"真会被用到的那个"。
         */
        fun clampRatio(ratio: Double): Double {
            if (ratio.isNaN() || ratio.isInfinite()) return PROMPT_CALIB_MAX
            if (ratio <= 0.0) return PROMPT_CALIB_MAX
            if (ratio < PROMPT_CALIB_MIN) return PROMPT_CALIB_MIN
            if (ratio > PROMPT_CALIB_MAX) return PROMPT_CALIB_MAX
            return ratio
        }

        /**
         * 每轮判据用的系数：`0`（未知）/ 越界值一律按 1.0（= 不校准）。
         *
         * 单独一个入口而不是让调用方自己 `coerceIn`：越界值来自磁盘 / 上一版代码，
         * 而"磁盘上多了个 1.4"这件事必须在**判据这一侧**就被吃掉 —— 否则下一个
         * 读代码的人会以为 `estimateScale` 的可信域是"调用方说多少就多少"。
         */
        fun use(scale: Double): Double =
            if (scale.isNaN() || scale <= 0.0) 1.0 else scale.coerceIn(PROMPT_CALIB_MIN, PROMPT_CALIB_MAX)

        /**
         * **判据**那一侧用的系数 —— 与 [use] 同一个数，但下界更低（[PROMPT_CALIB_JUDGE_MIN]）。
         *
         * 两个口径分开的理由见 [PROMPT_CALIB_JUDGE_MIN]：判据的余量本来就来自那张保守表
         * （0.80 vs 实测 0.62），它天生比报告高 29%；而报告那一侧只吃 [PROMPT_EST_SAFETY_PCT]
         * 一笔。所以同一个比值（`real / est`）在两边该落的区间不同 —— 混成一个数，
         * 要么判据永远修不掉"偏高"，要么 `/health` 上的 `prompt_est` 变成一把被压过的尺子。
         *
         * ⚠ 越界值一律在**这一处**吃掉：`estimateScale` 来自磁盘 / 上一版代码，
         *   它的可信域是这两个区间之一，不是"调用方说多少就多少"。
         */
        fun useJudge(scale: Double): Double =
            if (scale.isNaN() || scale <= 0.0) 1.0
            else scale.coerceIn(PROMPT_CALIB_JUDGE_MIN, PROMPT_CALIB_MAX)

        /**
         * 一份**样本**该不该算数（纪律 ③）。
         *
         * @param real native 量到的真 token 数（`prompt_real`；`<= 0` = 没量到）
         * @param est  这一轮**判据用的**估算值（`prompt_est`；`<= 0` = 没估）
         * @return true = 这份样本可以进均值
         */
        fun acceptable(real: Int, est: Int): Boolean = real > 0 && est > 0

        /**
         * 用一份新样本算出**新的**系数（已经钳位、已经滞回；`old` 是当前生效值）。
         *
         * 两个方向、两条门槛，彼此独立：
         *   · **下降**（估算偏高，`ratio < cur`）：[PROMPT_CALIB_MIN_SAMPLES] 份就动，
         *     直接落到 `ratio`（收益：少丢历史，风险：无 —— 判据只会更松一点）；
         *   · **回升**（估算偏低，`ratio > cur`）：见 [rise]，要
         *     [PROMPT_CALIB_UP_SAMPLES] 份、一次只挪 [PROMPT_CALIB_UP_STEP]，
         *     且上界仍是 [PROMPT_CALIB_MAX]。
         *
         * @param old     当前系数（`0` / 越界 -> 按 1.0 起算）
         * @param ratio   这一份样本的 `real / est`（未钳位也可以，这里会钳）
         * @param fresh   连续新鲜样本数（**含这一份**）；不足 [PROMPT_CALIB_MIN_SAMPLES] 时原样返回
         * @param freshUp 连续"估算偏低"样本数（**含这一份**）；不足 [PROMPT_CALIB_UP_SAMPLES] 时不回升
         * @return 该落地的系数（= 判据下一轮要用的那个数）
         */
        fun next(old: Double, ratio: Double, fresh: Int, freshUp: Int = 0): Double {
            val cur = use(old)
            if (ratio.isNaN() || ratio.isInfinite() || ratio <= 0.0) return cur
            // ⚠ 差得不够多就不动（滞回）：改判据的次数本身就是成本。
            val near = kotlin.math.abs(ratio - cur)
            if (near < PROMPT_CALIB_STEP) return cur
            // ⚠ 往下：只认"估算偏高"这一侧。`ratio > cur` 走 [rise]，那条路门槛更高。
            if (ratio < cur) {
                if (fresh < PROMPT_CALIB_MIN_SAMPLES) return cur
                return use(ratio)
            }
            return rise(cur, ratio, freshUp)
        }

        /**
         * 系数**回升**一格（估算偏低的那一侧）—— 只朝"真值"走，上界仍是 [PROMPT_CALIB_MAX]。
         *
         * 什么时候这一步是安全的：`ratio > cur` 意味着"按当前系数估出来的数比真值小"，
         * 即我们**正在低估**。而低估在下游的代价（native 从头部砍、复用归零）正是这一整套
         * 校准要防的东西 —— 只不过它当时选的是"宁可偏高"那一侧。一个被压到 0.70 的系数
         * 在口径调准之后会长期压低估算，**那同样是低估**，只是来路不同。
         *
         * 三条约束让它不越界：
         *   · 连续 [PROMPT_CALIB_UP_SAMPLES] 份（比下降多一倍）同向才算数；
         *   · 一次只挪 [PROMPT_CALIB_UP_STEP]，且**永远不超过 `ratio`**（绝不越过真值）；
         *   · 落值仍过 [use] —— 上界 1.0 在这一处钳死。
         *
         * @param cur     当前系数（已 [use] 归一）
         * @param ratio   这一份样本的 `real / est`（**未钳位**：钳到 1.0 就看不出低估有多深）
         * @param freshUp 连续"偏低"的样本数（含这一份）
         */
        fun rise(cur: Double, ratio: Double, freshUp: Int): Double {
            if (freshUp < PROMPT_CALIB_UP_SAMPLES) return cur
            if (ratio.isNaN() || ratio.isInfinite() || ratio <= cur) return cur
            val target = Math.min(ratio, cur + PROMPT_CALIB_UP_STEP)
            // 挪不到一格就不挪（滞回与下降同一个理由：改判据的次数本身就是成本）。
            if (target - cur < PROMPT_CALIB_STEP) return cur
            return use(target)
        }

        /** 这一份样本算不算"估算偏低"（= 系数该往上走的那一侧）。 */
        fun lowSide(cur: Double, real: Int, est: Int): Boolean {
            if (!acceptable(real, est)) return false
            val ratio = real.toDouble() / est.toDouble()
            return !ratio.isNaN() && !ratio.isInfinite() && ratio > use(cur) + PROMPT_CALIB_STEP
        }

        /**
         * 这一份样本与当前系数算不算"两把尺子对上了"（差不到 [PROMPT_CALIB_EPS_PCT]）。
         *
         * 对上了就**不动**，也不累计新鲜样本数 —— 它没有信息量（本来就该是常态）。
         */
        fun closeEnough(old: Double, real: Int, est: Int): Boolean {
            if (!acceptable(real, est)) return false
            val ratio = real.toDouble() / est.toDouble()
            val cur = use(old)
            return kotlin.math.abs(ratio - cur) * 100.0 < PROMPT_CALIB_EPS_PCT * cur
        }

        /**
         * 系数 → 存盘用的整数（×1000）。读数与日志都按它印，免得两处各写一份格式。
         *
         * ⚠ 下界取**判据**那一档（[PROMPT_CALIB_JUDGE_MIN]）：存的是同一个数、两个口径各按
         *   自己的下界读；按 0.70 钳会把证据确凿的 0.55 悄悄写成 0.70，而判据那边
         *   永远收不到它 —— 症状与"校准没接线"逐字相同。
         */
        fun toX1000(scale: Double): Int = Math.round(useJudge(scale) * 1000.0).toInt()
            .coerceIn(SCALE_JUDGE_MIN_X1000, SCALE_MAX_X1000)

        /** 存盘的整数 → 系数（报告口径的下界）。越界值（旧版本 / 手改坏了）按 [use] 的口径吃掉。 */
        fun fromX1000(x: Int): Double =
            if (x <= 0) 1.0 else use(x / 1000.0)

        /**
         * 这个词表该用哪一份系数 —— 必须是**词表自己的**那一份。
         *
         * 两份词表（对话 / 嵌入）的 token 数没有可比性，混用一个系数等于拿别人的
         * 尺子量自己的 prompt：而它不报错、不崩，只是判据悄悄偏了一截。
         *
         * @return 非空 = 这个键是**本词表**的；null = 不认识（调用方应当拒收样本，
         *   而不是跟着写 —— 写错键的系数会跟着那个词表一路错下去）
         */
        fun keyOf(current: String, msgVocab: String, embedVocab: String): String? = when {
            current.isEmpty() -> null
            current == msgVocab -> current      // 对话词表（主路径）
            current == embedVocab -> current    // 嵌入词表：它的样本也照收（彼此独立）
            else -> null
        }
    }

    /**
     * 这一轮的服务端容量：**每格**的 n_ctx（不是总量）。
     *
     * 三个入参都来自各自的唯一真值，不在这里重算：
     *   · `nCtxTotal`   = 用户设的上下文长度（[ServerSettings] / 加载参数）
     *   · `slotCount`   = `SlotScheduler.total`（**native 真值**，不是用户填的
     *     `parallelN` —— 两者在"第 2 份建失败"时会分岔）
     *
     * 除不尽时**回落总量**而不是静默取整：分支比"每份少几个位置"更严重的是
     * "客户端以为每份有 2048、实际只有 2047"，那种偏差会让每轮都差一个 token，
     * 而两边都不报错。`nCtxTotal` 在 `slotCount` 档下除不尽时按总量算 = 比实际
     * 容量**大**（多送），所以还要在 [plan] 里靠 `MIN_GEN_TOKENS` 让出余量 ——
     * 这一层不做"更聪明的猜"，只保证不会**低估**容量。
     */
    fun capacity(nCtxTotal: Int, slotCount: Int): Int {
        if (nCtxTotal <= 0) return 0
        val n = slotCount.coerceIn(1, SlotScheduler.SLOT_MAX)
        if (n <= 1) return nCtxTotal
        return if (nCtxTotal % n == 0) nCtxTotal / n else nCtxTotal
    }

    /**
     * 客户端送来的一段 prompt 若是**贴着上限的死尺寸窗口**，它会不会每轮都必裁一刀。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这一格必须存在（ISSUE #330 用户报的现象）
     * ══════════════════════════════════════════════════════════════════════════
     * 现象：*"客户端每次都塞 8192 token 上下文过来，服务端只有 4096，那是不是每次
     * 必裁、每次必破坏 KV 复用？"* 这不是"裁得有道理"那一类 —— 它有一个**判据**能
     * 在发请求之前就算出来，而那正是这里：
     *
     *   · 滑动窗口 = 客户端每轮都送**差不多同一段长度**的 prompt（贴着它自己的
     *     Max Context），而服务端每格比它小；
     *   · 于是我们从**头部**丢掉的 token 数每轮都 > 0，而上一轮的 KV 前缀是我们
     *     上一轮**已经丢过**的那一段 —— 本轮原样 prompt 与它的公共前缀断在这一刀上，
     *     命中的可能性几乎是零（`plan_reuse` 只报 `lcp = trimmed`，而它多半 < 门槛 16）。
     *   · 更糟的是这一刀每轮都落在**同一个位置**：窗口是固定尺寸的，多出来的永远
     *     是"最老的那几条"，于是每一轮都全量 prefill `ctx - 2` 个 token。
     *
     * 所以"会不会每轮必裁 + 每轮断缓存"这件事，**不需要看日志**：prompt 与容量
     * 摆在一起就能答。答出来之后要给的是**出路**，不是一句"装不下"：
     *
     *   ① 调大 n_ctx（`parallelN` 会按份切分，所以调它之前先看每格是多少）；
     *   ② 让客户端把 Max Context 降到 每格 - 生成长度 以下 —— 窗口一旦小于容量，
     *      就回到"只算新增的尾巴"那种正常形态；
     *   ③ 别用 `parallelN` 换并发：它会把这格再切一刀（见 README 的「并发形态」）。
     *
     * ⚠ 判据全部用**估算**（[estimate]）：宿主侧没有 tokenizer，这一步只负责
     *    "在发请求之前把明显装不下的那几轮去掉、并把'每轮都在断'这件事说出来"；
     *    真正那一刀由 native 按真 token 落（`ctxbudget.h` 的 `plan`）。
     *    ⚠ 所以这里**只报读数与出路，不改变 native 那一刀**（见 native 里
     *    `ctxbudget::sliding_window()` 的同族注释）。
     */
    class Window(
        /** 原样送来的 prompt 估算 token 数（未裁之前）。 */
        val promptTokens: Int,
        /** 这一格的容量（[capacity] 的落值）。 */
        val ctx: Int,
        /**
         * 这一轮的水位（%；`<= 0` = 默认档，归一走 [ContextWatermarkPct.normalize]）。
         *
         * ⚠ 这一格曾经与 [plan] 不同源：`plan` 早已按 `ctx × 水位` 判，而这里还在算
         *   `ctx - MIN_GEN_TOKENS`（整格）。于是同一个 prompt 在同一个设置下会得到两个
         *   结论 —— "预算说每轮都要裁、plan 说装得下"。现在只有单测在读它，但守卫里
         *   那条"满窗必裁"的锚点正是拿它当依据的：口径不同源，下一次谁拿它当结论就
         *   会拿错数。
         */
        val watermarkPct: Int = DEFAULT_WATERMARK_PCT,
        /**
         * **这一条 seq** 能排到的位置数（= native 的 `seq_ctx_limit`；`<= 0` = 没量到，
         * 按 [ctx] 判 —— 单 seq / 窗口类型检查那一档，与改动前逐字节相同）。
         *
         * ⚠ 与 [plan] 的 `positionCap` 同一把尺子（ISSUE #426 真机
         *   `decode rc!=0 at offset 2048`）：两路各一条 seq 时"这一格有多大"与
         *   "位置号能排到几"是两个数，而满窗判据正是在这两者之间下结论的。
         */
        val positionCap: Int = 0
    ) {
        /** 这一格**按水位**的可用空间（`min(容量 × 水位, 容量 - 1)`；0 = 容量未知）。 */
        val avail: Int get() {
            if (ctx <= 0) return 0
            val cap = if (positionCap > 0) Math.min(ctx, positionCap) else ctx
            val wm = ContextWatermarkPct.normalize(watermarkPct)
            val wmLine = (cap.toLong() * wm / 100L).toInt()
            return Math.min(wmLine, cap - 1)
        }

        /**
         * 这一轮大概要从头部丢多少 token（0 = 不丢）。
         *
         * 与 native 同一条削序（先削 gen、再削 prompt），所以它是"**gen 让到底
         * 之后**仍然装不下"的那一段 —— 只按 prompt 与 ctx 比会**高估**。
         *
         * 容量口径与 [plan] 同一个（水位线），不是整格：两处各用一把尺子的话，
         * 这一格算出来的"每轮都要裁"会和 [plan] 的结论打架，而两边都不报错。
         */
        val trimmed: Int get() {
            if (ctx <= 0) return 0
            return (promptTokens - avail).coerceAtLeast(0)
        }

        /** 会不会每轮都裁一刀（= 丢掉的部分 > 0）。 */
        val trimsEveryRound: Boolean get() = trimmed > 0

        /**
         * 丢掉的那一段**够不够长**：不足以构成"上一轮攒下的可复用前缀"。
         *
         * 门槛与 native 同一个数（`kvprefix::kMinReuseTokens = 16`）—— 两处各写一份
         * 迟早分岔，而分岔的症状是"客户端说会断、服务端说不会断"，两边都不报错。
         */
        fun breaksKvEveryRound(): Boolean = trimmed >= KV_MIN_REUSE_TOKENS

        /** 一句话读数 + 出路（与 [describe] 同一风格：能自救的信息，不是一句结论）。 */
        fun describe(): String {
            if (!trimsEveryRound) {
                return "上下文预算：prompt 约 $promptTokens tok，装得进每格 $ctx —— 不必裁，KV 前缀可连续"
            }
            val head = "上下文预算：prompt 约 $promptTokens tok > 每格 $ctx × 水位 " +
                "${ContextWatermarkPct.normalize(watermarkPct)}%（= $avail tok），" +
                "每轮都要丢最老约 $trimmed tok"
            return if (breaksKvEveryRound())
                "$head —— 这一刀每轮落在同一处，KV 前缀复用会**每轮都断**（只按新增尾巴算不可能）。" +
                    "出路：①调大 n_ctx；②让客户端把 Max Context 降到 $avail 以下；" +
                    "③把水位调低一档（更早开始丢、但每刀更短）；" +
                    "④别靠 parallelN 换并发（它把这格再切一刀）"
            else "$head（这一刀很短，复用的主要损失在别处）"
        }
    }

    /**
     * 知识库注入块里**每条正文**能占多少字符（0 = 不注入 / 不截不补）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么注入块必须是**定长**的（真机实测：开知识库时复用"波动"）
     * ══════════════════════════════════════════════════════════════════════════
     * 注入块拼在"本轮提问"之前，而 KV 前缀复用是**逐 token 比前缀**。检索结果
     * 天生变长（命中第 778 条还是第 597 条，正文长短差几十到几百 token），
     * 于是块一变长，公共前缀就断在**块的头一枚**上 —— 后面整段历史全量重算。
     * 用户看到的是"有时复用、有时不复用"，而它既不是缓存坏了，也不是对端裁剪。
     *
     * 定长之后：同一条命中 -> 逐字节相同 -> 前缀接得上；换了命中 -> 从块头分叉
     * （那时信息真的变了，本来就该重算）。
     *
     * 取值按**容量**给，不写死：块再重要也不该吃掉半格上下文。规则是
     * 「水位线以内的四分之一、按条数摊，且每条不超过 [INJECT_ITEM_CHARS_CAP]」——
     * 下限给 [INJECT_ITEM_CHARS_MIN]（太短的块连一句完整的话都装不下，检索就白做了）。
     *
     * ⚠ 分母是**水位线**而不是整格：注入块拼在本轮提问的正文里，和提问一起参与
     *   `plan` 的判据，也就是一起受 `ctx × 水位` 约束。按整格摊时块会占掉 25%，
     *   而水位只留出 15% 的余量 —— 块自己就把"水位留的那一截"吃穿，那一轮必然越线、
     *   丢历史。按水位线摊，块与提问都待在水位以内（默认 85% 的四分之一 ≈ 整格的 21%，
     *   与原来同一个体感）。
     *
     * ⚠ 默认值只是"没传时"的兜底：两条真实路径（App / HTTP）都显式传用户设的那一档，
     *   不传就等于"按默认 85% 定宽"，而用户设的是 70% 时块会比预期宽一截。
     *
     * @param ctxPerSlot 每格容量（token）；<=0 表示容量未知 -> 返回 0（退回原样拼）
     * @param items 这一轮准备注入几条（0 = 不注入，不占位置）
     * @param watermarkPct 用户设的水位（%）；非法值归一成 [DEFAULT_WATERMARK_PCT]
     */
    fun injectionChars(
        ctxPerSlot: Int,
        items: Int,
        watermarkPct: Int = DEFAULT_WATERMARK_PCT
    ): Int {
        if (ctxPerSlot <= 0 || items <= 0) return 0
        // 水位的分母：这一块只在**水位线以内**才有位置。按整格算时（水位 85%）
        // "四分之一"实际是"水位线以上的 29%"—— 而水位恰恰是"平时别贴着容量线跑"
        // 的那条线，注入块抢先吃掉越线那一截，等于让水位的第一轮少记几轮历史。
        val usable = (ctxPerSlot.toLong() * ContextWatermarkPct.normalize(watermarkPct) / 100L).toInt()
            .coerceAtLeast(0)
        val budget = usable / 4                           // 整块最多吃掉水位线内的四分之一
        return (budget / items).coerceIn(INJECT_ITEM_CHARS_MIN, INJECT_ITEM_CHARS_CAP)
    }

    /**
     * 注入块的**渲染宽度**：定了就不再随并发数变（ISSUE #330 第 6 块）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么 [injectionChars] 一个人不够（它是对的，但它答的是另一个问题）
     * ══════════════════════════════════════════════════════════════════════════
     * [injectionChars] 按「**当前**每格容量」算宽度，而每格容量随设置页那一格变
     *（第八回合起格数不再切分它，但用户自己改 ctx 时仍在变）。
     * 用户把 ctx 从 8192 改到 4096 之后重新加载模型，宽度就从"8192 的四分之一"
     * 变成"4096 的四分之一" —— 块里每一条都短了一截，于是**块之后的那一段
     * 每一轮都对不上**：前缀断在块头，历史全部重来。
     *
     * 这不是"宽度算错了"，是"一个跨轮不变的东西被一个会变的量决定了"。
     * 定长块只解决"同一批命中 → 同样字节"，解决不了"换了个并发数 → 同样字节"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 口径：宽度只在**新建对话**时定一次，并跟着这个会话走
     * ══════════════════════════════════════════════════════════════════════════
     * 会话内的容量变化（换 `parallelN`、换模型）不再挪宽度 —— 宁可块看起来"偏宽"
     * 或"偏窄"，也不要它每变一次就把前缀全废一遍。
     *
     * ⚠ 宽度不跟着会话走还有第二个后果：一次 4 路并发的会话把宽度定在每格 2048 的
     *   四分之一，之后改回 1 路（每格 8192）时那块仍然只有 2048 的四分之一 ——
     *   不理想，但**稳定**，而稳定性正是这个函数唯一的目的。
     *
     * @param now 现在按容量会算出的宽度（[injectionChars] 的落值；0 = 容量未知）
     * @param remembered 这个会话上次定的宽度（0 = 还没定过，或这个会话没记过）
     * @return 本轮该用的宽度；两个都不可用时返回 0（退回原样拼，与"没注入"同义）
     */
    fun sessionInjectionChars(now: Int, remembered: Int): Int =
        if (remembered > 0) remembered else now.coerceAtLeast(0)

    /** 单条注入正文的**下限**：低于它连一句完整的话都装不下，检索就白做了。 */
    const val INJECT_ITEM_CHARS_MIN = 120

    /**
     * 单条注入正文的**上限**：块再重要也不该吃掉半格上下文（它只是"附加信息"）。
     *
     * 两者都是**字符数**（中文 1 字 ≈ 1 token，与 [TOKENS_PER_CJK_CHAR] 同一个口径），不是 token。
     */
    const val INJECT_ITEM_CHARS_CAP = 800

    /**
     * 主动治理（Proactive）缺省值：**关闭**。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 这一格回答的是「服务端要不要替一个不知道自己多长的客户端兜底」
     * ══════════════════════════════════════════════════════════════════════════
     * 现在客户端自己会裁（按它自己的 Max Context 算），于是服务端这一层的每一个
     * 部件都在**帮倒忙**，而且每一条都有真机读数：
     *
     *   · 估算偏高 → 提前丢历史（`真 2076 / 估 2585`，离硬墙还有 1600 枚就开始丢）；
     *   · 越线就把 `gen` 削到地板 → 之后每一轮只吐几个字；
     *   · 丢了历史 → 本轮 prompt 成了上一轮的**后缀** → `canAlign` 差一枚自锁 →
     *     每轮全量 prefill（用户报的"截断之后每次都要全量"）。
     *
     * 所以默认改成：**只在真的装不下时才动手**（按整格 `availHard` 判），装得下就
     * 一枚不丢。水位、丢一半、估算校准这几样都还在（开关一开就回到今天的行为），
     * 只是默认不参与判据。
     *
     * ⚠ 关掉的是**主动治理**，不是**兜底**：位置线（每条 seq 的容量）、
     *   「装不下就削 gen / 丢历史 / 报 400」这几条必须在 —— App 之外还有 curl、
     *   SDK、第三方客户端，它们谁都不知道这一格有多大。
     * ⚠ 水位**保留**（用户点名要留）：「这一轮越过水位多少」仍然算、仍然报
     *   （`local_ctx.watermark` 与 `/health` 的 `drop_room`），只是不再拿它当丢弃的判据。
     */
    const val PROACTIVE_DEFAULT = false

    /**
     * 把开关做成一个**名字**而不是散在各处的布尔：判据里每一处"要不要主动动手"都读它，
     * 于是"哪一档在关掉之后仍然生效"只有一处可查（也才有守卫能钉）。
     *
     * @param on 应用层给的值（设置项）；`null` = 没给，落 [PROACTIVE_DEFAULT]
     */
    fun proactiveOf(on: Boolean?): Boolean = on ?: PROACTIVE_DEFAULT

    /**
     * 水位上限的缺省值（%）—— 与 [ContextWatermark.DEFAULT_PCT] **同值同义**。
     *
     * 为什么在这里再写一份常量而不是直接引用：本文件要能在宿主单测里**单独编译**，
     * 而 `ContextWatermark` 引了 `android.content.Context`（SharedPreferences）。
     * 两处各写一份的风险用守卫钉住（同值时判绿，分叉时判红）—— 与
     * [MIN_GEN_TOKENS] / `kMinGenTokens` 那一对同一种处理。
     */
    const val DEFAULT_WATERMARK_PCT = 85

    /**
     * 丢掉历史之后**不再往 prompt 里拼任何提示**（ISSUE #452 收口）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 曾经有过一句 `[对话续接] …`
     * ══════════════════════════════════════════════════════════════════════════
     * 先后试过两个落点，都不行，原因同一个：**我们写进 prompt 的东西，会改写下一轮的输入**。
     *
     *   · 拼在"第一条保留的 user 正文前"：那条 user 上一轮就是"本轮提问"，已经在 KV 里，
     *     等于把上一轮起点之后的 token 从第一枚起改掉 —— `kvlastchat::startFor` 那条
     *     "对齐上一轮起点"永远判失败，每次丢弃白烧一整轮 prefill；
     *   · 改挂首条 system 尾部：不再动被复用的消息，但 **HTTP 那条路的 system 是客户端
     *     每轮重新给的**，它不知道我们挂过什么 —— 于是下一轮的 system 与我们上一轮送进去
     *     的那条不同，"这一轮装得下"的估算跟着变、可复用起点被判死，**保留集每轮换一个
     *     起点**（6 组 → 7 组 → 7 组 → 8 组），越线之后再也稳不下来。那正是 #426 想消灭
     *     的形态，只是换了成因。
     *
     * 一句话：**任何"只在丢弃时出现"的注入，都会让下一轮的输入与上一轮不同** ——
     * 而 KV 前缀复用的全部前提就是"输入稳定"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 那"它不记得刚才"这件事告诉谁：客户端，不是模型
     * ══════════════════════════════════════════════════════════════════════════
     * 续接提示的收益在模型侧本来就很薄（一句固定话术挡不住答非所问），代价却是一整轮
     * 全量 prefill 或一轮一个起点。而"这一轮没有上下文"真正该知道的是**客户端** ——
     * 它有能力把提示显示给用户、或者把 Max Context 调小、或者干脆少送几轮。
     *
     * 所以这件事只从 [localCtxField] 的 `ask_only` 出去（见 [Plan.keptAskOnly]）：
     * 历史丢光了、这一轮只剩提问时它才是 `true`，其余一律 `false`。
     *
     * ⚠ 判据在守卫里是**反向**的：`RESUME_MARK` / `resumeShape` 里的挂提示代码一出现
     *   就该红（见 `check_ctx_budget_wiring.py` 那一节）。反着写是因为这条禁令最容易被
     *   下一轮"善意地"改回去 —— 它看起来像在帮模型。
     */

    /**
     * 丢完历史之后，把保留集**修成一条合法对话**（ISSUE #426 方案 C-1）。
     *
     * 只做一件事：**首条 system 之后不许出现"没有提问的 assistant 残句"**。
     * `keptOf` 保住首条 system 与本轮提问，但它不保证丢完之后紧接 system 的是一条 user ——
     * 丢掉的那一组可能正好拆在"assistant 之后、下一轮 user 之前"，于是模型看到
     * `system, assistant, …`。那与训练时的形状不同形，表现为"开始胡说"，
     * 而角色序列肉眼看着还正常。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 修形状只做"删"，不做"加"（ISSUE #452 收口）
     * ══════════════════════════════════════════════════════════════════════════
     * 以前这里还会挂一句 `[对话续接] …`（先挂 system 尾部、再退化成一条独立 user）。
     * 两种挂法都撤了：**任何只在丢弃时出现的内容，都会让下一轮的输入与上一轮不同**，
     * 而 KV 前缀复用的前提就是输入稳定。理由与读数后果见上面那条注释。
     *
     * 所以这个函数现在**一个字节都不新增**：`out` 只由 `kept` 里的条目构成，
     * 且顺序不变。"这一轮没有上下文"由 `local_ctx.ask_only` 告诉客户端。
     *
     * @param noSystemForce 首条 system 是被"让位"让掉的、而不是本来就没有。
     *   这一档的语义是「设定陪葬、提问保住」，所以那条 system 不能借修形状悄悄回来。
     */
    fun resumeShape(
        kept: List<Pair<String, String>>,
        noSystemForce: Boolean = false
    ): List<Pair<String, String>> {
        if (kept.isEmpty()) return kept
        val sysIdx = if (kept.first().first == "system") 0 else -1
        // system 是被让位让掉的那一档：它不在保留集里，也就没有"紧跟它的残句"可清。
        val head = if (sysIdx == 0 && !noSystemForce) 1 else 0
        var from = head
        while (from < kept.size && kept[from].first == "assistant") from++
        if (head == 0 && from == 0) return kept
        if (head == 0 && from == kept.size) return kept
        val out = ArrayList<Pair<String, String>>(kept.size - from + head)
        for (i in 0 until head) out.add(kept[i])
        for (i in from until kept.size) out.add(kept[i])
        return out
    }

    /**
     * 算出这一段 prompt 会不会每轮都裁（`ctx <= 0` = 容量未知，按"不裁"作答）。
     *
     * ⚠ 水位必须与 [plan] 同源（默认同值、归一同一份）：这一格的结论是"要不要去看
     *   客户端"的唯一依据，口径差一档就会得到两句互相矛盾的话，而两句都"看起来没错"。
     */
    fun window(
        promptTokens: Int,
        ctx: Int,
        watermarkPct: Int = DEFAULT_WATERMARK_PCT,
        positionCap: Int = 0
    ): Window = Window(promptTokens, ctx, watermarkPct, positionCap)

    /**
     * 一段正文的估算 token 数（**按字符分类折算**，不是整段乘一个常数）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么必须是分类的（ISSUE #426 真机：ctx 开大了反而丢了更多历史）
     * ══════════════════════════════════════════════════════════════════════════
     * 整段乘 1.0 时，4000 个英文字符被估成 4000 tok，而它渲染出来只有 1100 左右。
     * 于是**英文会话每一轮都会被判越线**：历史被丢、生成长度被削到地板
     * （真机读数：`丢掉最老 30 条 —— 约 3148 tok`、`生成空间只剩 2 tok`，
     * 同一轮的 `tokenize_need` 却是 2059）。
     *
     * 分类之后中文那侧一个字节都没变（仍 1.0），英文那侧回到实测口径。
     * 剩下的偏差方向仍然只许**偏多**（各档都站在实测上沿）。
     *
     * ⚠ 与 [promptTokensOf] 同一条尺子：那边的渲染固定开销照旧 +
     *   [RENDER_OVERHEAD] 与生成后缀，这里只负责"正文多少 token"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么中文不再按 1.0 整字算（ISSUE #452 真机：真 329 / 估 3767）
     * ══════════════════════════════════════════════════════════════════════════
     * 实测口径是"0.6~0.65 汉字一枚 token"（Qwen / Llama / gemma 那一族 BPE 词表
     * 已经把常用汉字两两合成一枚），按 1.0 算就是**恒定高估 1.5~1.6 倍** —— 而它是
     * 每一条消息、每一轮都乘的常数，误差不随长度摊薄，只随会话一起长。
     *
     * 与 ASCII 那一档的区别不是"要不要偏多"，而是**偏多的代价谁在付**：
     *   · ASCII 从 1.0 收到 0.28 之后，"偏多"那一侧仍由 [TOKENS_PER_ASCII_CHAR]
     *     的取值保证（0.28 站在 0.25 的上沿）；
     *   · 中文从 1.0 收到 0.62 之后，真正的"偏多"由 [RENDER_OVERHEAD]、
     *     [PER_MESSAGE_OVERHEAD] 与 [PROMPT_EST_SAFETY_PCT] 那一笔整体余量兜住 ——
     *     那三笔是**加**上去的，不随文本长度漂。
     *
     * 低估的代价仍然是"native 每轮从头部砍一刀、复用归零"，所以这里只收到实测值
     * 的上沿，并且整段估算再加一笔 [PROMPT_EST_SAFETY_PCT] 的余量（默认 6%）。
     * 收不收这一档的判断依据不是"估得准不准"，而是**判据落到哪一侧**：
     * 现在的中文会话是"估多了三千多 tok、白丢几轮历史"，而 native 那边一路装得下。
     */
    fun textTokens(text: String): Int = textTokensFor(text, report = true)

    /**
     * 同一条分类折算，但**可以选择那张表**（见 [JUDGE_TOKENS_PER_CJK_CHAR]）。
     *
     * @param report true = 报告口径（实测值，与 native 的 `prompt_real` 并排读）；
     *   false = 判据口径（保守值，"这一轮装不装得下"只认它）。
     *
     * ⚠ 两个口径只在这一处分开，别在调用点各乘一个系数 —— 那样"哪张表"就成了
     *   调用点的秘密，而它必须在单测里能一眼看出来。
     */
    fun textTokensFor(text: String, report: Boolean): Int {
        if (text.isEmpty()) return 0
        val perCjk = if (report) TOKENS_PER_CJK_CHAR else JUDGE_TOKENS_PER_CJK_CHAR
        val perAscii = if (report) TOKENS_PER_ASCII_CHAR else JUDGE_TOKENS_PER_ASCII_CHAR
        val perOther = if (report) TOKENS_PER_OTHER_CHAR else JUDGE_TOKENS_PER_OTHER_CHAR
        var cjk = 0
        var ascii = 0
        var other = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // 半角可见字符 + 空白 + 控制符：英文、代码、标点都在这一档。
                c.code < 0x80 -> ascii++
                // CJK 统一表意文字 / 假名 / 谚文 / 全角标点：一个字约一枚 token。
                c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF ||
                    c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF -> cjk++
                else -> other++
            }
            i++
        }
        // 三档各自向上取整：宁可多估一枚，也不要三笔小数互相抵消成少估。
        return Math.ceil(cjk * perCjk).toInt() +
            Math.ceil(ascii * perAscii).toInt() +
            Math.ceil(other * perOther).toInt()
    }

    /** 一条消息的估算 token 数（模板壳 + 角色名 + 正文，正文按分类折算）。报告口径。 */
    fun tokensOf(role: String, content: String): Int =
        PER_MESSAGE_OVERHEAD + textTokens(content) + textTokens(role)

    /**
     * 一条消息的**判据**估算（保守口径，见 [JUDGE_TOKENS_PER_CJK_CHAR]）。
     *
     * 它与 [tokensOf] 必须**同形**（壳 + 角色名 + 正文），差别只有那张折算表 ——
     * 少一项就会让"判据比报告小"，而那正是这一整条 ISSUE 的形状。
     */
    fun judgeTokensOf(role: String, content: String): Int =
        PER_MESSAGE_OVERHEAD + textTokensFor(content, report = false) +
            textTokensFor(role, report = false)

    /**
     * 整段估算的**安全余量**（%，只加不减）：`promptTokensOf` 与 [plan] 的判据都乘它。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么"分类折算"之后还需要一笔整体余量
     * ══════════════════════════════════════════════════════════════════════════
     * 逐字符折算只能是**平均口径**：同一段中文里，常用词被 BPE 合得狠、生僻字与
     * 代码块被切得碎，实测能差出 20%~40%。而漏算一枚的代价与多算一枚不对称
     * （漏算 = native 从头部砍一刀、KV 前缀复用归零、全链路无一处报错）。
     *
     * 所以口径分两层：
     *   · 逐字符那一层尽量站在实测上沿（[TOKENS_PER_CJK_CHAR] / [TOKENS_PER_ASCII_CHAR]）；
     *   · 整体再加这一笔固定百分比 —— 它**不随长度漂**，是"这类文本的方差"的余量，
     *     与"中文按 1.0 整字算"那种恒定高估 1.6 倍不是一回事。
     *
     * ⚠ 与 [PromptCalibration] 的分工：这一笔是**静态**的（同一份文本永远同一个数），
     *   校准是**动态**的（拿 native 量到的真值往下收）。两者都只作用在估算上，
     *   都不参与 native 判据 —— 那边按真 token 算，用不上它们。
     * ⚠ 与 [JUDGE_TOKENS_PER_CJK_CHAR] 的分工：那一张表管"随长度长的余量"
     *   （判据比实测保守 29%），这一笔管"不随长度长的方差"。两样都乘满会把余量
     *   乘两遍（20% 变 44%），而真机上那等于每轮多丢几组历史。
     */
    const val PROMPT_EST_SAFETY_PCT = 6

    /**
     * 上面那个百分比换算成的**绝对**枚数（加在估算上，不乘）。
     *
     * 在 [plan] 的判据口径里用，**整段只加一次**：`ceil(est × 系数) + 这一笔`。
     * 加绝对值而不是再乘一遍，是为了让"方差余量"与"长度余量"（判据那张表）
     * **可加不可乘** —— 乘起来是 28%，而单靠哪一样都够兜住。
     *
     * ⚠ **不许逐条加**（这一笔以前挂在 `scaled()` 上，而 `scaled()` 被 `tokAll`
     *   逐条调用）：它的量是"这一份文本里被切碎的那一点方差"，与**条数无关**。
     *   逐条加等于"消息越多、余量越大"—— 真机形态（ISSUE #488）是 7 条消息
     *   白拿 7 × 64 = 448 枚，十几条就是 900+，估算一路顶在水位线上，
     *   于是"聊到第 3 轮就动手"。整段一笔才是它想兜的那件事。
     *
     * ⚠ 20% + 64 那一版是 ISSUE #488 的"估算偏高"（B 档），实测偏高 29%：
     *   20% 与 64 枚在两三千枚的会话里同时乘满。这里按**可校准性**收：系数现在乘在
     *   总量上（见 [safetyOf]），而它够不着的那一截才需要余量兜 —— 留 6% 与 64 枚
     *   这一档，它兜的是"方差"，不是"长度"。
     */
    const val PROMPT_EST_SAFETY_TOKENS = 64

    /**
     * **判据**那一侧的估算系数下界 —— 比报告口径（[PROMPT_CALIB_MIN]）更低。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么判据要多一档（ISSUE #488：判据估 2518、真值 2159，第 3 轮就动手）
     * ══════════════════════════════════════════════════════════════════════════
     * 两个口径的余量**来源不同**，所以它们的合理区间也不同：
     *
     *   · 报告口径（`prompt_est`）的余量全在 `safetyOf` 那一笔里（[PROMPT_EST_SAFETY_PCT]
     *     乘一次），而它与真值并排读 —— 那笔余量就是"估算准不准"的读数，不该再被
     *     系数压一遍。所以报告那一侧的系数只到 [PROMPT_CALIB_MIN]；
     *   · 判据口径（`tok` / `tokAll`）的余量本来就来自那张保守表（判据表 0.80 vs
     *     实测 0.62）**加上** [PROMPT_EST_SAFETY_PCT]。同一份文本上，判据天生就比
     *     报告高一截 —— 而校准要修掉的正是这一截。
     *
     * 系数只到 0.70 时，`est × 0.70` 之后判据仍然**必然**高于真值（实测偏高 29%，
     * 0.70 只抵掉 30% 里的 30%）：真值 2159 时判据最低 2020（还高 361/17%），
     * 真值 3000 时至少高 1381。于是"估算偏高"在没越线的时候永远修不掉，而
     * "偏高没被修掉"正好就是"2457 就到了"。
     *
     * 0.55 是"判据表 0.80 × 0.55 = 0.44" —— 比实测 0.62 还低一档，但仍然
     * **不是**实测表：低估的代价（native 从头部砍、复用归零）由 [plan] 的另一条
     * 兜底（越线只丢历史、`gen` 不动）承担，而这条路只在 `proactive` 打开时才走。
     * 与报告口径的 0.70 分开，是为了让 `/health` 上 `prompt_est` 仍然是那把**没被
     * 校准压过的**尺子 —— 两个数一起才读得出"判据乘了多少"。
     */
    const val PROMPT_CALIB_JUDGE_MIN = 0.55

    /**
     * **判据**口径的估算相对**报告**口径的比值（%）：`判据表 / 实测表` ≈ `0.80 / 0.62`。
     *
     * 两个口径量的是同一份文本，差别只有那张折算表（外加 `safetyOf` 的余量，两边同值）。
     * 所以整段上 `判据 ≈ 报告 × 这一档`，而校准的样本分母必须按**判据**那边算：
     * 拿报告口径当分母，`real / est` 会一直说"估算偏低"，系数被按住只能往上 —
     * 于是"判据偏高"永远修不掉（见 [PROMPT_CALIB_JUDGE_MIN]）。
     *
     * 取 129 而不是逐轮现算：这一层拿不到 `messages`（校准是判据的输入，不是它的副本），
     * 而两张表都是常量 —— 比值也该是常量，否则它就成了第三个"口径"。
     * 两张表里任何一张改了，这一档必须跟着改（守卫钉住这个等式）。
     */
    // ⚠ 只能是 `val`：`Math.round` 是运行期调用，不是编译期常量，`const val` 直接编译不过
    //   （`e: Const 'val' initializer should be a constant value`）。语义不变 —— 两张表都是
    //   `const`，所以这里同样在类初始化时算一次就定死；守卫钉的等式照旧成立。
    val JUDGE_TO_REPORT_PCT =
        Math.round(JUDGE_TOKENS_PER_CJK_CHAR / TOKENS_PER_CJK_CHAR * 100.0).toInt()

    /**
     * 一段 prompt 的估算 token 数 = 消息总和 + 渲染侧固定开销 + 生成后缀。
     *
     * 三步分开写而不是并成一个常数，是为了让守卫能分别钉住：
     * 只把折算改回"整段乘 1.0"、或只把这两笔固定开销删掉，都是同一类故障
     * （**低估** -> native 每轮砍头部 -> 复用归零），而它们看起来都很无辜。
     *
     * @param generationSuffixTokens 本轮要补在末尾的生成后缀占多少（[GEN_SUFFIX_BASE_TOKENS] /
     *   带思考起始标记时 [GEN_SUFFIX_BASE_TOKENS] + [GEN_SUFFIX_THINK_TOKENS]）。
     *   缺省按带思考的那一档估：模板与模型千差万别，宁可当"它会先想一段"。
     */
    /** 一份 messages 的正文真字符数（`content.length` 之和；与 token 估算无关）。 */
    fun bodyCharsOf(messages: List<Pair<String, String>>): Int =
        messages.sumOf { it.second.length }

    /**
     * [Plan.droppedChars] 的量法：保留集与原件**按序对齐**，把没被留下的那几条正文长度加起来。
     *
     * 不用"原件总长 - 保留集总长"：那在"同一段正文出现两次"时也算得对，但在
     * "resumeShape 修形状又删掉一条"时会**多算**一条 —— 而多算的那一条会被中间层
     * 当成本轮撤销的正文，换算出来的字符数就比真实的那份大。逐步对齐是 O(n)，
     * 且它是**保留集里没有的那些**（不是"被改写的那些"，因为这一层只删不改）。
     */
    fun droppedCharsOf(all: List<Pair<String, String>>, kept: List<Pair<String, String>>): Int {
        var lost = 0
        var i = 0
        for (m in all) {
            // 用 `===`（同一份引用）而不是 `==`：`Pair` 是值语义，两段**内容相同**的
            // 历史（真的会有人连着问同一个问题）会在这条判据上逐字相等，
            // 而"没被留下"与"留下了"是两件事 —— 值比较会把丢掉的那条算成留下了。
            if (i < kept.size && kept[i] === m) { i++; continue }
            lost += m.second.length
        }
        return lost
    }

    fun promptTokensOf(
        messages: List<Pair<String, String>>,
        generationSuffixTokens: Int = GEN_SUFFIX_BASE_TOKENS + GEN_SUFFIX_THINK_TOKENS
    ): Int = safetyOf(RENDER_OVERHEAD + generationSuffixTokens.coerceAtLeast(0) + estimate(messages))

    /**
     * 给一段估算加上**安全余量**（[PROMPT_EST_SAFETY_PCT]；只加不减），再乘**估算系数**。
     *
     * 单独一个入口而不是在 [plan] 里乘一遍：估算的口径**只能有一处** ——
     * 判据（`tok` / `tokAll`）与报告（`prompt_est`）各乘一次，两边就会分叉，
     * 而分叉的症状是"判据说装得下、报出去说装不下"，谁也解释不了。
     *
     * ⚠ 验算系数必须乘在**乘过余量之后**的总量上（ISSUE #488 的 B 档）：
     *   以前它只乘在 `ceil(est × 系数) + 余量` 的 `est` 上，于是余量留在系数**外面** ——
     *   系数压到 0.70 时，真值 2159 的那一轮判据最低还有 2020（比真值高 17%），
     *   "估算偏高"在没越线的时候永远修不掉，而"修不掉"正好就是"2457 就到了"。
     *   乘在总量上之后，`est × 系数` 与真值同量纲：系数 = `real / est` 就是
     *   "判据 = 真值"，没有残留。
     *
     * ⚠ 它乘的是**整段**，所以调用方必须是"整段那一份"（[promptTokensOf]）。
     *   逐条与它无关：`[PROMPT_EST_SAFETY_TOKENS]` 那一笔在 [plan] 的判据口径里
     *   **整段加一次**，理由见那个常量的注释（逐条加 = 白送 N × 64 枚）。
     */
    fun safetyOf(est: Int, scale: Double = 1.0): Int {
        if (est <= 0) return est
        val k = useScale(scale)
        val padded = Math.ceil(est.toDouble() * (100 + PROMPT_EST_SAFETY_PCT) / 100.0).toInt()
        return if (k >= 1.0) padded else Math.ceil(padded.toDouble() * k).toInt()
    }

    /**
     * 把任意来源的系数（`0` / NaN / 越界）收成 [PROMPT_CALIB_MIN]..[PROMPT_CALIB_MAX]。
     *
     * 报告口径与判据口径共用它，**只有下界不同那一处**各写一个入口
     * （见 [PromptCalibration.use] 与 [PromptCalibration.useJudge]）：两个口径的余量
     * 来源不同，混成一个数就会让 `/health` 上的 `prompt_est` 变成"被校准压过的那把尺子"，
     * 而那个数正是用来读"估算准不准"的。
     */
    internal fun useScale(scale: Double): Double =
        if (scale.isNaN() || scale <= 0.0) 1.0 else scale.coerceIn(PROMPT_CALIB_MIN, PROMPT_CALIB_MAX)

    /**
     * 预算结论。
     *
     * `dropped` 是**丢掉的条数**（不是 token 数）：它是用户能对上号的那个数
     * （"我发了 8 条、它只当 4 条"），而 token 数是调试用的中间量。
     */
    class Plan(
        /** 真正该发出去的消息（保留顺序不变；首条 system 尽量保住）。 */
        val messages: List<Pair<String, String>>,
        /** 为装下而丢掉的条数（0 = 一条都没丢，与今天逐字节一致）。 */
        val dropped: Int,
        /** 丢完之后估算的 prompt token 数。 */
        val promptTokens: Int,
        /** 这一轮允许生成的 token 上限（≤ 调用方要的 maxTokens）。 */
        val maxTokens: Int,
        /** 这一格的容量（[capacity] 的落值；0 = 未知）。 */
        val ctx: Int,
        /**
         * **连本轮提问本身就塞不进这一格** —— 调用方必须报错，不许继续。
         *
         * ⚠ 它只有一个含义（ISSUE #426 之后收窄）：历史丢光了、生成长度让到
         * [MIN_GEN_TOKENS] 了，最后那条提问仍然装不下。以前它同时覆盖"还差几枚"
         * 那一档 —— 于是越聊越长的会话在某一轮起每轮都 400，而那几枚本来
         * [plan] 自己就能让掉。
         */
        val overflow: Boolean,
        /**
         * 这一轮是按**水位**丢的（越过水位线 -> 丢到「水位线 × [HALF_RATIO_PCT]」那条落点）。
         *
         * ⚠ 它必须**由命名实参**落值，不许位置传：出口那一行曾经是
         *   `Plan(kept, dropped, keptEstimate, gen, ctx, false, dropped > 0, genTrimmed,
         *         lostToWatermark, …)` —— 第 7 位吃掉了 [promptTrimmed]，第 8 位吃掉了
         *   [genTrimmed]，而这一格落回默认值 `false`。于是 `local_ctx.landing`（判据就是
         *   这一格）一次都没出现过，`describe` 那句「越过水位 …%」也从来没印过 ——
         *   这正是「你说的三个参数，我没看到，无论是日志还是探活」的根因。
         */
        val droppedToWatermark: Boolean = false,
        /**
         * 这一轮**丢过历史**（= `dropped > 0`）。
         *
         * 它存在的唯一理由是**别把它读成"装不下"**：丢掉最老的几轮之后这一轮
         * 照样能答（HTTP 200 + 少几轮记忆），而"装不下"是 400（一条都塞不进去）。
         * 两件事用同一个 `dropped` 表达过一次，结果就是客户端一边被 400，
         * 服务端一边已经把老轮次丢了 —— 见 native `ctxbudget::Plan::prompt_trimmed`。
         */
        val promptTrimmed: Boolean = false,
        /**
         * 为装下而**削掉的生成长度**（`要的 - maxTokens`；0 = 一枚都没削）。
         *
         * 与 native 的 `ctxbudget::Plan::gen_trimmed` 同一件事。它存在的理由：
         * 削到 500 与削到 2 在 `maxTokens` 上都是"这一轮的生成上限"，而后者意味着
         * 这一轮几乎没有回答 —— 归因时读的是同一个数，所以差额必须单独说得出。
         */
        val genTrimmed: Int = 0,
        /**
         * 这一轮**刚好越过水位线**（= 这一轮开始的时候还没超，是这句提问把它顶过去的）。
         *
         * 它与 [droppedToWatermark] 的差别是"第一次"与"持续"：连续几轮都在水位以上时，
         * 只有第一次丢、后面几轮 `dropped == 0`（历史已经短到装得下了）。
         */
        val crossedWatermark: Boolean = false,
        /** 这一轮用的水位（%; 0 = 水位未启用/容量未知）。 */
        val watermarkPct: Int = 0,
        /**
         * 这一轮**真正交给 prefill 的那一份**的估算 token 数 —— 它就是 [promptTokens]，
         * 只是为了"报出去的那一份"留一个显式的名字。
         *
         * ⚠ 它以前叫 `promptTokensReported`，语义是"裁之前"。名字和语义一起换掉了（ISSUE #452）：
         *   `/health` 上它要和 `prompt_real` 并排读，而后者是 native 数**当前这一段**
         *   prompt 的真 token 数 —— 一个是"丢之前"、一个是"丢之后"，差值随 `dropped`
         *   一起长。于是丢过历史的会话会印出"估多了 3438 tok"，把"我们刚丢了 30 条历史"
         *   读成"估算口径有问题"；更糟的是这同一个差值还被 `PromptCalibration` 当样本
         *   吃掉，系数被压到 0.70，下一轮丢得更多（用户原话：估算超很多、白丢会话）。
         *
         *   名字留着 `Before` 的时候，每一处读到它的人都要先读一段注释才知道它**不**是
         *   "之前" —— 而 `local_ctx` 的 `sent` 还在 `if (promptTokens > 0)` 上退回它，
         *   那个分支只有在 `promptTokens <= 0` 时才成立，于是"退回"这件事永远不会发生。
         *   现在两个名字同义、两个数同源，那个退回分支也一并删掉（见 [localCtxField]）。
         *
         *   "我送了多少"这件事由客户端自己知道（它就是要发的那一方）；服务端该回答的是
         *   "我喂给模型的这一段有多大"。丢掉的历史有多少看 [dropped]（条数）。
         */
        val promptTokensReported: Int = 0,
        /**
         * 估算与"发出去那一份"之间的差额（= [promptTokensReported] `-` [promptTokens]；
         * 两个数现在同源，所以正常是 0）。
         *
         * ⚠ 它**不再**是"丢了多少 token"（那是 `输入量 - 发出去的量`，服务端量不出来，
         *   而且它把两件相反的事糊成一个数）。丢掉的历史有多长，看 [dropped]（条数）
         *   与 [keptAskOnly] —— 条数是用户能对上号的那个（"我发了 8 条、它当 4 条"）。
         */
        val droppedTokens: Int = 0,
        /**
         * 历史丢光了、最后只剩这一轮提问（ISSUE #426 方案 C 的最坏一档）。
         *
         * 它与 [overflow] 不同：[overflow] 是"提问自己也装不下 -> 400"，
         * 这一档是"提问装得下、但**前面什么都没有了**"。
         *
         * ⚠ 判据是**保留集里一条历史都没有**（不只是"除 system 外只剩一条"）：
         *   多轮对话里若只留了 `system + user + assistant`（一轮），模型其实是有上下文
         *   的 —— 把它报成"没有上下文"会让客户端白白提示用户。只要留了一条
         *   `assistant`（或 `system` 之外的两条以上），这一格就是 `false`。
         *
         * 它就是 `local_ctx.ask_only`：**服务端唯一一处告诉客户端"这一轮没上下文"的
         * 出口**（ISSUE #452 收口后不再往 prompt 里拼续接提示，见 `resumeShape`）。
         */
        val keptAskOnly: Boolean = false,
        /**
         * 预算丢掉的那几段里，**撤销掉的字符数**（0 = 没丢，或丢掉的都是空正文）。
         *
         * 它只服务一件事：`local_ctx.prompt_prefix` 要报"整段 prompt 一共有多少字符"，
         * 而这个数 = 留下的那一份 + 被撤销的那几段。`droppedTokens` 是**估算 token**，
         * 与字符不是同一把尺子 —— 拿它去凑字符会把"丢了一轮三千字的英文问答"
         * 算成三千个字符，中间层据此算出的换算率就偏了一倍。
         *
         * 与 [resumeShape] 那条"删只删、不新增"同源：这里记的是**自己被撤销的正文**，
         * 不参与任何判据（`plan` 的结论与它无关），只给读数用。
         */
        val droppedChars: Int = 0,
        /**
         * 这一轮**丢之前**那份 messages 的正文真字符数（`content.length` 之和）。
         *
         * 它只服务 `local_ctx.prompt_prefix` 的换算率：`chars_start = tok_start × 字符/token`，
         * 而那个比值必须由**真字符数**除以**真 token 数**得到（见 [promptCharsPerTok]）。
         * 拿 `promptTokens`（估算口径：逐条固定开销 + 渲染开销 + 生成后缀，再乘一笔余量）
         * 去当分子，短会话上会偏出几倍 —— 真机就是这样"算出来 270、实际边界在一千多"，
         * 中间层一轮都没删。
         *
         * 与 [droppedChars] 同一条纪律：不参与任何判据，只给读数用。
         */
        val bodyChars: Int = 0,
        /**
         * 这一轮判据用的**估算系数**（`×1000`；1000 = 没校准，见 [PromptCalibration]）。
         *
         * 它必须跟结论一起报出去：`dropped` / `promptTokens` 是**乘过系数**之后的数，
         * 而读的人手里只有它才能把"真丢了这么多"和"估算被调低了"分开 ——
         * 否则同一个 `dropped` 在系数 1.0 与 0.7 下是两个不同的处境，读数上却一样。
         */
        val estimateScaleX1000: Int = 1000,
        /**
         * 保留集里**第一条消息在原列表里的下标**（`0` = 从第 0 条起喂，一条都没丢）。
         *
         * ══════════════════════════════════════════════════════════════════════
         * 为什么落点必须由这里说，调用方不许自己再算一遍
         * ══════════════════════════════════════════════════════════════════════
         * 「这一轮从第几条起发」是一个**已经算过**的事实：`kept` 就是按它切出来的。
         * 调用方（App 内聊天）要拿它当下一轮的起点，好让下一轮的 prompt 以
         * **上一轮真喂进去的那一段**开头 —— 那是 KV 复用唯一能接上的形状。
         *
         * 而在旁边再算一遍就是这个字段存在的原因：按组数推（`cut[m].first`）、
         * 按条数推、按 `dropped` 反推，三种算法各自都能在某个组数形状上**碰巧**
         * 得到同一个数，然后在别的形状上悄悄错一轮 —— 错的那一轮喂给模型的是
         * 错位的历史，而不崩、不报错、读数全部合法。
         *
         * ⚠ 它**不是** `dropped` 的补数：`resumeShape` 会删掉头部的残句 assistant，
         *   于是 `messages.size - dropped` 与它可能相差 1~2 条。两个数同源不同义。
         * ⚠ `overflow` 那一档是 `messages.size`（= 全丢），不是 0 —— 0 的读法是
         *   "一条都没丢"，正好反过来。
         */
        val keptFrom: Int = 0,
        /**
         * 这一次预算**用过的**位置线落值（= `min(ctx, positionCap)`；`<= 0` = 没判过）。
         *
         * 它回答的是"这一轮的结果是按哪条线算出来的"，而不是"这一格有多大"：
         * `ctx` 是整格，而位置号只能排到**这一条 seq** 的容量（`n_ctx / n_seq_max`）——
         * 改了 slot 数、或者换了模型，后者就动了，同一个 `keptFrom` 的落点也跟着动。
         *
         * ⚠ 所以起点标记要记的是**这一个数**，不是登记那一刻再去 `seqCtxPerSeq()` 取
         *   （账本作废之后那一格会报 0，于是"上一轮按 2048 判的"被记成"没判过"）。
         * ⚠ 它在 `plan` 出口统一落值 —— 包括 `overflow` 那一档：那一档同样按这条线
         *   判过（判成"装不下"），标记的作废判据不该因为这一档而失去基准。
         */
        val positionCap: Int = 0,
        /**
         * 进来时**已经被调用方前置截掉了几条**（0 = 没截）。
         *
         * 它与 [dropped] 必须分开（两条都压在 `dropped` 上就分不开了）：
         *   · `dropped` 是"**这一轮我又**丢了几条"；
         *   · 这一格是"**进来之前**就已经不在的那几条"。
         * 合成一个数时，「我截过、这一轮没再丢」与「真的不需要裁」逐字相同，
         * 而两者的处置相反：前者要退到"只在真装不下时让"，后者本来就一无所动。
         *
         * ⚠ 坐标是**非 system 的历史条数**，不是"整份 messages 少了几条"：
         *   App 侧前置截历史时**保住首条 system**（它是每轮都相同的那一段，丢了
         *   等于把 KV 前缀的第一段也换掉）—— 于是 `msgs = [system] + full.drop(skip)`，
         *   从 `msgs` 下标换回 `full` 下标要加的是 `skip - 1`，不是 `skip`。
         *   [promptFrom] 的 `keptFrom == 0` 那一档就是这条差一位的落点。
         */
        val trimmedByCaller: Int = 0
    ) {
        /** 这一轮传进来的 `messages` 条数（`promptFrom` 的钳位要用它，落值不额外存一遍）。 */
        private val messageCount: Int get() = keptCount + dropped

        /**
         * 保留集条数（`messages.size`）。单独记一格是为了让 `promptFrom` 不必回头去读
         * `messages` —— 那个 `List` 在 `overflow` 那一档是空表，而"空表"与"一条都没留"
         * 在两处读法不同（见 [overflowAllDropped]）。
         */
        private val keptCount: Int = messages.size

        /**
         * 下一轮**该从第几条起拼**（相对 `full`，即含 system 那一位的那份 messages）。
         *
         * ══════════════════════════════════════════════════════════════════════════
         * 为什么它必须是"落值"而不是"这一轮丢了几个"（ISSUE #577 Layer 4）
         * ══════════════════════════════════════════════════════════════════════════
         * 调用方（App 内聊天）拿它当下一轮的起点：下一轮的 prompt 以**上一轮真喂进去
         * 的那一段**开头，`lcp` 才可能长、KV 前缀才接得上。而这个数只由这里说一次 ——
         * 外面按 `dropped` 自己推一遍就是第二套坐标系。
         *
         * 它与 [keptFrom] 差着**进来之前就被前置截掉的那几条**（[trimmedByCaller]）：
         * `keptFrom` 是"保留集第一条在**这一轮传进来的** `messages` 里的下标"，
         * 而调用方下一轮拼的是**整份历史**，两者相差 `trimmedByCaller`。
         * **不**在 `(0, 传进来的 size)` 里：它不是这一轮的切片坐标，
         * 而是下一轮的拼装坐标 —— 别拿它去索引 `messages`。
         *
         * ⚠ `keptFrom == 0` 是"**这一轮一条都没丢**"的哨兵（不是"保留集第一条在 0"）：
         *   这一档下一轮仍要从**第 1 条**起拼（第 0 位是 system，它每轮都在），
         *   所以落值是 `trimmedByCaller + 1`，不是 `trimmedByCaller`。少加这个 1
         *   会让前置截过的会话在没丢的那一轮把起点写早一位 —— 下一轮多喂一条
         *   上一轮**根本没进 KV** 的消息，`lcp` 只数得到 system 段（每轮全量 prefill）。
         *
         * 落值恒在 `1..full.size-1`（`full = messages.size + trimmedByCaller`），
         * 这正是 `SessionStore.trimTagOf` 唯一认得的区间 —— 于是**不需要哨兵值**
         * 表达"历史整段丢光"：那一档由 [droppedAllHistory] 说，不占这个字段。
         * （以前用 `-2` 当哨兵写进 `SessionStore`，而读侧第一句就是
         *   `from <= 0 → return null` —— 那个哨兵永远读不回来。）
         */
        val promptFrom: Int
            get() {
                // 保留集为空（`overflow` / 一条都发不出去）：没有"起点"这回事。
                // 这一档 `keptFrom` 是 `messages.size`（= 0），不能拿去算起点。
                if (keptCount <= 0) return 0
                val full = messageCount + trimmedByCaller
                // `full <= 1`（只有 system、或者干脆没有消息）：没有"起点"这回事，
                // 钳出来的 1 会被读侧当成"从第 1 条起"—— 而下一轮 `skip = 1`
                // 会把唯一那一条丢掉。所以这一档落 0（= 没有标记）。
                if (full <= 1) return 0
                // `keptFrom == 0` = 这一轮没丢：下一轮从第 1 条（第一条历史，system
                // 占 0）起拼，再补上进来之前已经被截掉的历史条数。
                // `keptFrom > 0` = 真丢了：`keptFrom` 是保留集第一条在 `msgs` 里的
                // 下标，加回前置截掉的历史条数就是 `full` 坐标。
                val start = if (keptFrom > 0) keptFrom + trimmedByCaller
                            else trimmedByCaller + 1
                return start.coerceIn(1, full - 1)
            }

        /**
         * 这一轮**历史整段丢光**（模型只看到 system + 本轮提问，或者连 system 都让了位）。
         *
         * 判据只有一条：保留集里一个 `assistant` 都没有。它正是 [keptAskOnly] ——
         * 两个名字说的是同一件事（"这一轮没有上下文可言"），而这一格是给
         * "下一轮的主动治理"读的：历史已经丢光、再按水位丢一次丢的就是**本轮提问**。
         *
         * ⚠ 它**不**进 `promptFrom` 字段：那是"从第几条起拼"，而"丢光了"是
         *   "这一轮怎么丢的"。两件事塞进一个数，就是哨兵值那条路的起点 ——
         *   而哨兵值一定会在某一侧被当成合法值读（`from <= 0` 那一句就是）。
         */
        val droppedAllHistory: Boolean get() = keptAskOnly
    }

    /**
     * 把可裁候选切成**一轮一组**：一条 user 连同其后**所有**连续 assistant。
     *
     * 抽成独立函数是为了让单测能直接钉"切法"，而不必经过 [plan] 的容量算术 ——
     * 分组错了与容量算错了在 `dropped` 上是同形的，而处置完全相反。
     *
     * 两条不变量（守卫与单测都钉）：
     *   · 组内**不许**出现"assistant 后面又跟 user"：那说明切法把两轮并成了一组；
     *   · 候选里剥掉尾部 assistant 之后，剩下的**每一组都必须以 user 开头**：
     *     `system, assistant, …` 那种残句只在 `dropUpTo` 边界上出现，不该由分组产生。
     *
     * @param droppable 可裁候选的**原始下标**（升序、已排除首条 system 与本轮提问）
     */
    fun groupRounds(
        messages: List<Pair<String, String>>,
        droppable: List<Int>
    ): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var gi = 0
        // 开头的 assistant 全是**残句**（历史的第一条是 assistant = 它那条 user 早就不在了）。
        // 它们不构成一轮，也就不该占一个组名额 —— 让它们成组等于把"一轮"的额度
        // 记到半句话上：`minimalKeep` 数组数时多算一轮，`keptOf` 保下来也只会让
        // [resumeShape] 事后删掉（那一步仍然要做，但它已经是兜底，不该是常态）。
        while (gi < droppable.size && messages[droppable[gi]].first == "assistant") gi++
        while (gi < droppable.size) {
            val g = ArrayList<Int>(2)
            val first = droppable[gi]
            g.add(first); gi++
            // 紧邻（**原始下标 +1**）的候选、且角色是 assistant -> 同一轮。连续几条都收。
            // ⚠ 判"紧邻"用的是 `droppable[gi - 1] + 1`（上一个**已被收下**的下标），
            //   不是 `first + 1`：一条 user 后面跟两条 assistant 时，第二条的紧邻对象
            //   是第一条 assistant，拿 `first + 1` 去比会把它判成"不紧邻"并另起一组 ——
            //   那正是这轮要修的病（同一轮被切成两组，丢一半时砍在两句之间）。
            while (gi < droppable.size && droppable[gi] == droppable[gi - 1] + 1 &&
                messages[droppable[gi]].first == "assistant"
            ) { g.add(droppable[gi]); gi++ }
            groups.add(g)
        }
        return groups
    }

    /**
     * 按"保留末尾 `tail` 组可裁候选"构造消息集：首条 system（若有）+ 被保留的候选 +
     * 最后一条 user 及其后端。
     *
     * 语义就是 [plan] 的裁剪口径本身（保住 system 与本轮提问，从最老开始整组丢），
     * 抽出来是为了让**二分找保留条数**与最终产出用**同一份**构造 —— 两处各写一遍时
     * "预算多少"与"实际留下几条"会分叉，而分叉的症状是 native 又从头部砍了一刀。
     */
    private fun keptOf(
        messages: List<Pair<String, String>>,
        groups: List<List<Int>>,
        tail: Int
    ): List<Pair<String, String>> {
        val keepFlag = BooleanArray(messages.size) { true }
        val dropUpTo = (groups.size - tail).coerceIn(0, groups.size)
        for (gi in 0 until dropUpTo) for (i in groups[gi]) keepFlag[i] = false
        // 兜底：**一组都留不下**（`dropUpTo == groups.size`）时，首条 system 让位
        //（保角色设定 vs 保提问，只能二选一时选提问 —— 丢了提问就是答非所问，
        // 丢了设定只是换个语气）。
        //
        // ⚠ 判据是 `dropUpTo == groups.size`，不是 `tail <= 0`：后者在"只有一组候选"
        //   时也成立（`tail` 只可能是 0 或 1），于是**明明留下一组够装**的那一轮
        //   也被连 system 一起丢掉 —— 而丢弃是逐轮粘住的（每轮都算出同一个 `n`），
        //   表现为"越到后面越答非所问"。
        if (dropUpTo == groups.size && messages.size > 1) keepFlag[0] = false
        return messages.filterIndexed { i, _ -> keepFlag[i] }
    }

    /**
     * 越线那一刀的落点：**丢完之后 `prompt + gen` 该落在哪**（token 数）。
     *
     * 只有这一份。落点在源码里散成三处（[halfWatermarkTail] 的扫描、`overTrigger`
     * 兜底那一支、以及 `gen` 补回的上界）时，改一处漏两处是常态 —— 真机上表现为
     * 「落点按半水位丢了，紧接着 `gen` 又被补回水位线」，两处各自都对、合起来等于没丢。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 落点由**占用比**定，不由 `max_tokens` 定（ISSUE #586 第三回合）
     * ══════════════════════════════════════════════════════════════════════════
     * 这条线要回答的只有一句：「丢掉之后，这一格该用掉多少」。写成水位线的比例
     * （[HALF_RATIO_PCT]），`gen` 只是**在里面占位**，不是额外的扣减项 —— 于是
     * `max_tokens` 越大，留下的 prompt 越小，因为回答本来就要占位置。
     *
     * 曾经的两个基数都不成立：
     *   · `avail − gen`（触发线的一半）：`gen` 一见大就塌。`ctx 4096 / 水位 85 /
     *     max_tokens 1024` 落成 `−4` prompt、`8192 / 90 / 2048` 落成 172 —— 而
     *     "半格以上的 `max_tokens`"正是大 ctx 用户的唯一选法，等于专挑那一档变差；
     *   · `avail`（整条水位线的一半）：与 `max_tokens` 无关是对的，但**丢一次管几轮**
     *     这条不成立 —— 落完合计 `avail/2 + gen`，`4096 / 85% / 1024` 那一档离起判线
     *     只剩 692 枚，够三轮左右，不是"几十轮"。
     *
     * 现在：`目标合计 = avail × HALF_RATIO_PCT`，落点 prompt = 目标合计 − `wantGen`。
     * 占用比越小，历史留得越少、下一次动手越晚 —— 唯一的一条取舍，且是用户看得见的
     * 一个数（想留得多就调大它），不再藏在"`max_tokens` 会不会把落点吃掉"里。
     *
     * 与 native 的 `kvlongchat::halfWatermarkKeep` 是同一个量、同一个形状
     * （`keep = 目标合计 − gen`），比例由调用方传进去 —— 两边必须同值（[HALF_RATIO_PCT]
     * 与 `ctxbudget::kHalfRatioPct`），否则客户端与服务端在同一条路上落两个地方。
     * ⚠ 两边必须同号：`gen` 落在这边或者那边都可以，落在**两边各一次**就是
     *   丢出生效两次（落点凭空少 `gen`）。
     * ⚠ 定值上**故意比 native 紧一档**（`− HALF_HEADROOM_PCT`，ISSUE #586）：native
     *   那一刀按真 token 切片、粒度是 1 枚，而这边只能整组丢 —— 同一把尺子套在两种
     *   粒度上，松的那一侧就是"每轮都丢"。
     *
     * ⚠ `per` 只是**上界**（`min(目标合计, avail − per)`），不进目标（ISSUE #586）：
     *   它的来源是 `refreshRoundPer()`，量的是**最老那一组**多大，不是"下一轮会长多少"。
     *   把它加进落点，落点就随这份历史里老消息的篇幅漂（真机 1740 的线漂成 968）。
     * ⚠ 余量 [HALF_HEADROOM_PCT] 的基数取 `avail`（水位线，正是这些余量兜的那条线的
     *   口径），不按半边算：真机上那笔余量连一组都不够（104 vs 250）。
     */
    private fun halfWatermarkTarget(avail: Int, per: Int, wantGen: Int): Int {
        // 丢完之后 `prompt + gen` 的**合计目标**：占用比 × 水位线。
        // 上界 `avail − per`（下一轮涨一轮也不越线），以及不许超过 `avail` 本身
        //（占比永远 < 100，这一条是给"将来有人把它调到 100"兜底的）。
        val want = (avail.toLong() * HALF_RATIO_PCT / 100L).toInt()
        val roomNext = (avail - per).coerceAtLeast(0)
        val line = minOf(want, roomNext, avail).coerceAtLeast(0)
        // 越过这条线的那一截要**留够下一轮的余量**（见 [HALF_HEADROOM_PCT]）：
        // `keptOf` 只能整组丢，"第一个装得下的档"常常越过线一整组 —— 历史再涨一轮
        // 就又越线，于是"丢一次管几十轮"变成"每轮丢一次"。余量只在线**内侧**生效。
        //
        // ⚠ 基数取 `avail`（这条线自己的口径），不取 `line` 也不取 `avail / 2`：
        //   这一笔是"整组丢的粒度"，而整组多大只与**这份历史**有关、与占用比无关。
        //   按半边算时它连一组都不够（`4096×85% / 2 × 6% = 104 < 250`），落点照旧
        //   退到"只留提问"—— 那正是 ISSUE #586 报的那一屏。
        val headroom = (avail.toLong() * HALF_HEADROOM_PCT / 100L).toInt().coerceAtLeast(0)
        return (line - headroom).coerceAtLeast(0)
    }

    /**
     * 越线那一刀的落点：**丢完之后 `prompt + gen` 落在水位线的 [HALF_RATIO_PCT]%**，
     * 返回要保留的组数。
     *
     * ⚠ 「丢一次管几十轮」那句话在**小 ctx + 大 `max_tokens`** 那一档不成立（ISSUE #586）：
     *   `ctx 4096 / 水位 85 / max_tokens 1024` 的余量只有 `2457 − 1184 = 1273` 枚，
     *   按每轮 130~250 枚算是 5~10 轮。那句话以前印在 [describe] 里、也写在这几行注释里，
     *   现在两处都换成了算出来的数（[roundsUntilNextDrop]）—— 它仍是"比每轮丢一次好得多"
     *   的那一档，只是不该被读成"几十轮不用管"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么不能按**组数**对半（ISSUE #503 真机第十七回合）
     * ══════════════════════════════════════════════════════════════════════════
     * 以前这里给的是 `(groups.size + 1) / 2` —— 数的是**组**，不是 token。组数与
     * 每组多大无关，于是"丢一半"在 token 上根本不是一半：
     *
     * | 历史 | 组数 | 留 `(n+1)/2` 组 | 留下的估算 | 落点线 |
     * | --- | --- | --- | --- | --- |
     * | 13 轮 | 13 | 7 组 → 16 条 | 1612 tok | 1740 |
     * | 20 轮 | 20 | 10 组 → 22 条 | 2104 tok | 1740 |
     * | 40 轮 | 40 | 20 组 → 42 条 | 3744 tok | 1740 |
     *
     * 第三行那个 3744 直接越过水位线（`4096 × 0.85 = 3481`）—— 也就是说**丢完还越线**。
     * 而 `fitsTail(halfTail)` 只问"落点那一档装得下吗"，它答的是"装得下"（跟水位
     * 还差得远），于是调用方照落 —— 「丢一次之后隔一阵才再越线」就成了空话：
     * 历史越长的会话，丢一次之后剩得越多、下一轮越线越快。
     *
     * 真机读数正是它（`ctx 4096 / 水位 85% / max_tokens 1024`，26 条消息 = 13 轮）：
     *
     * ```
     * 第14轮 [ctx] 越过水位 85% —— 丢掉最老 12 条消息，约 1708 tok
     * 第15轮 [ctx] 越过水位 85% —— 丢掉最老 14 条消息，约 1704 tok
     * ```
     *
     * 两轮都落在"剩 16 条"上（`13 → 7 组 = 16 条`、`14 → 7 组 = 16 条`）—— 因为组数
     * 对半算出来的是同一个数，而历史涨了两条，于是**每一轮都再越线、每一轮都再丢**
     * 到同一个落点。这就是用户问的那句"既然上一轮丢了 12 条，那现在应该只剩 16 条，
     * 第15轮怎么又丢 14 条"。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 所以它必须与 native 是**同一把尺子**
     * ══════════════════════════════════════════════════════════════════════════
     * native 那一侧（`ctx_budget.h` 的 `toHalf` → `kvlongchat::halfWatermarkKeep`）
     * 算的是 `avail × 比例 − gen`，按**真 token**。同一条路（App 内聊天）上两把尺子
     * 本来就不该并存：一条说"留 16 条"，另一条按 token 说"留 1392 − gen" —— 谁先
     * 动手谁说了算，而两边的落点差着一千多枚。用户看到的读数就是"客户端丢了 14 条，
     * 服务端又按另一条线算了一遍"。
     *
     * 这里按 token 找：从 `halfTail`（组数对半，仍然当**起点**用）往下逐组多丢，
     * 取第一个落进 [HALF_RATIO_PCT] 那条线的档（线本身已按 [HALF_HEADROOM_PCT]
     * 往里挪过一档，因为 `keptOf` 只能整组丢、落点必然越过线一整组）。找不到就退回
     * `halfTail` —— 那是"占用比那条线太紧、一组都装不下"的窄格，落点本身没有意义，
     * 交给 `fitsTail` 判。
     *
     * ⚠ 栈上的 `keptEst` 用**判据口径**（与 `fits` 同一个数），不是报告口径：
     *   落点是判据的一部分，两处量不同的东西就会"按 1740 算、落到 2104"。
     * ⚠ 只往**多丢**那个方向找（`tail` 递减）：往少丢找会退化成"能留多少留多少"，
     *   而那正是 [minimalKeep] 注释里记过的第一次踩坑。
     */
    private fun halfWatermarkTail(
        groups: List<List<Int>>,
        messages: List<Pair<String, String>>,
        gen: Int,
        wantGen: Int,
        avail: Int,
        per: Int,
        estOf: (List<Pair<String, String>>) -> Int
    ): Int {
        if (groups.isEmpty()) return 0
        // ⚠ 目标里那笔 `gen` 必须**真的减掉**（ISSUE #503）：`estOf`（`keptEst`）算的是
        //   "留下的 prompt"，不含本轮的生成后缀；而落点的定义是 `prompt + gen`。
        //   以前这里直接拿 `prompt` 去比落点线，落完的真实合计是 `line + gen` ——
        //   注释与 native（`keep = 目标合计 − gen`）说同一件事，代码差着一整份 `gen`。
        //   真机形态：`max_tokens 1024` 下落在 2281 而不是 1608，起判线 2457 —— 每两轮掉一轮。
        // ⚠ 减的是**意愿值** `wantGen`，不是上面被让位削过的 `gen`（ISSUE #503 自检）：
        //   `gen` 走到这里已经被"让位"那一跳削到 `avail − tokens`（越线那一轮必削），
        //   拿它算落点 = 按"这一轮还剩多少"去留历史，历史被留住、回答被削短，
        //   补回时又被 `ceiling` 卡死 —— 最终 `gen` 钉在地板、prompt 卡在半格上沿。
        //   落点要回答的是"这一轮**想**答多长，历史该给它腾多少"，那个数是意愿值。
        // ⚠ 这里**不加 `per`**（ISSUE #586）：`per` 量的是"最老那一组多大"，而这一句
        //   要回答的是"丢完之后 `prompt + gen` 落在哪"。把 `per` 加回去，落点就随
        //   老消息的篇幅往上漂 —— 真机 `ctx 4096 / 水位 85 / max_tokens 1024` 那一档
        //   因此落在 968 而不是 `avail × 50%`，历史再多丢一整组（用户看到的正是 450 上下）。
        //   下一轮涨一轮也不越线这件事，改由 [halfWatermarkTarget] 里那笔固定余量兜
        //   （[HALF_HEADROOM_PCT]），它不随任何一段历史的篇幅漂。
        val target = halfWatermarkTarget(avail, per, wantGen) - wantGen
        // ⚠ 扫描从**全留**往下走，但落点的门只认 `target`（ISSUE #503 自检）：
        //   以前起点是 `(n+1)/2`（"至少丢一半"），于是历史越长起点越高 —— 14 轮从 7 组
        //   起扫、40 轮从 20 组起扫，而 `target` 是个**常数**，那条线上找到的档位
        //   永远贴着上限：prompt 停在 1689、`gen` 被挤到 51，随后钉在地板。
        //   真正该守的纪律是"落点由 token 定、组数只是起点"，不是"必须丢一半" ——
        //   `keptOf` 的每一档都是整组，从全留往下走等于"留下能装下的最多的那一档"，
        //   而"装下"的判据就是 `target`，不会退化成"贴着水位线"（那是 `avail`）。
        // ⚠ 扫描**不从 0 起**（ISSUE #586 后续）：`tail = 0` 在 [keptOf] 里是"连首条
        //   system 一起让位"，而它几乎总能落进 `target`（只剩本轮提问，几十枚）——
        //   于是一组 250 tok 的完整轮次，只因落点线比它小几十枚（`gen` 吃掉了目标里
        //   的一大截），整份历史连同设定一起被丢光。落点是**目标**，不是硬墙：
        //   连一组都放不进目标时，宁可越过目标一组，也要保住"设定 + 一轮对话 + 提问"。
        for (tail in groups.size downTo 1) {
            val kept = keptOf(messages, groups, tail)
            if (estOf(kept) <= target) return tail
        }
        // 一组也落不进目标线：只要它**装得下**（`prompt + 意愿生成 <= 水位线`），
        // 就留这一组 —— 越线一组远好过整份历史（含 system）全丢。
        // 连这一组都装不下时才返回 0，交给调用方那几跳（先让 gen、再只留提问）。
        if (estOf(keptOf(messages, groups, 1)) + wantGen <= avail) return 1
        return 0
    }

    /**
     * 在"越过水位就丢一半"里挑保留组数（`0` = 全丢也装不下）。
     *
     * 判据单调：保留得越少，估算 token 越少（组内至少一条消息，删消息只会变小）。
     * 单调性一旦被打破（比如将来有人给"保留的条数"加正向开销），二分就会给出比真值
     * 多的保留数 —— 表现是"预算说装得下、native 又砍一刀"。所以**不假设**单调：
     * 求出的候选若复核不过，就退回"只留本轮提问（`tail = 0`）"；
     * 那一档也装不下才返回 0，由调用方报 400。
     */
    private fun minimalKeep(
        groups: List<List<Int>>,
        gen: Int,
        avail: Int,
        cap: Int,
        fitsToks: (Int) -> List<Int>
    ): Pair<Int, Boolean> {
        // ⚠ 判每条消息的**累计**和，不是逐条比：`tokAll` 给的是"保留这一条要占多少"，
        //   而判据是"整段 prompt 装不装得下" —— 逐条比等价于说"每条单独装得下就算装得下"。
        fun fits(tail: Int) = fitsToks(tail).sum() + gen <= avail
        // ⚠ 没有可裁候选时**必须返回"全留"（0），不能返回 `0 to false` 那个 0** ——
        //   两个 0 长得一样、含义正相反：一个是"一组都不丢"（`keptOf(..., 0)` 会
        //   原样返回），另一个是"丢到只剩本轮提问"。这一档的现场是
        //   「system + 一条本轮提问」的请求（没有历史可丢），而两个 0 混淆时
        //   **system 会被丢掉**（它不在任何一组里，`keptOf` 的保位标志就把它算成丢了），
        //   表现为"它怎么不按设定说话了"，而读数上只是 `dropped: 1`（像丢了一轮历史）。
        if (groups.isEmpty()) return 0 to false   // 没有可裁候选：0 组留下 = 一组都不丢
        // "丢掉多少"= 全量减去这一档保留的 —— 两项都来自同一个 `fitsToks`，
        // 不另写一份求和（两份会在"首条 system 让位"那一档分叉）。
        val allToks = fitsToks(groups.size).sum()
        fun droppedTokens(tail: Int) = (allToks - fitsToks(tail).sum()).coerceAtLeast(0)
        if (!fits(0)) return 0 to false      // 只留本轮提问都装不下
        // 全留就装得下 -> 一组都不丢（逐字节兼容那一档）
        val n = groups.size
        if (fits(n)) return n to false
        // ── 越线了：**丢一半**，而不是"丢到刚好装下"（ISSUE #426 方案 B）───────
        //
        // 用户原话：「超限后，之后生成的内容直接都没关系了」。机制在这里：
        // "丢到刚好装下"会让 `dropped` 逐轮跳动 —— 每跳一次，KV 前缀起点就后移
        // 一次，模型每轮看到的历史都是新的一段。而**每轮都断**与**断一次**在
        // 日志上完全同形（`prompt_trimmed > 0`、`reuse > 0`），谁也看不出来。
        //
        // ⚠ 口径是「**丢到半水位那条 token 线**」，纯粹按组数对半是错的
        //   （ISSUE #503 真机第十七回合，本体抽成了 [halfWatermarkTail]）：
        //   · 写成"从 `half` 往下找第一个**装得下**的" -> 退化成"能留多少留多少"
        //     （装得下这条判据离水位还有一截），`prompt + gen` 仍贴着水位线，
        //     下一轮又要丢；
        //   · 以及"只按组数 `n -> ceil(n/2)`" -> 组数与每组多大无关，40 轮那一档
        //     丢完还剩 3744 tok，**直接越过水位线**（3481）—— 于是越长的会话越必然
        //     连着丢。真机 `丢最老 12 条` → `丢最老 14 条` 就是这个落点自己。
        //   判据只能是 token：丢完 `prompt + gen` 要落在 `avail × HALF_RATIO_PCT`。组数仍然是
        //   **起点**（不按组扫就没法保证整轮不裂），但落点由 token 定 —— 与 native
        //   的 `halfWatermarkKeep` 同一把尺子，否则同一条路上两个落点差着一千多枚。
        //
        // ⚠ 与 native 那一侧（`ctx_budget.h` 的 `toHalf` → `kvlongchat::halfWatermarkKeep`）
        //   **必须是同一个目标**：丢完之后 `prompt + gen` 落在 `avail × HALF_RATIO_PCT`。两边
        //   数得来的东西不同（这边数得出**组**、数不出真 token；那边只有真 token、
        //   没有组），所以"怎么找"仍是两份代码 —— 但"落到哪儿"只许一个答案。
        //   以前这边落到组数对半、那边落到 token 对半，同一条路上两个落点差着一千多枚，
        //   谁先动手谁说了算（真机：客户端丢 14 条之后服务端又按落点线算了一遍）。
        //   ⚠ 统一**只能**发生在目标上：把两边写成同一行代码是做不到的（数据结构不同，
        //     丢了半组就是孤儿 assistant），这一点在上面那条已经记过一次。
        // 两边都要落进水位以内，所以这里还要 `fits(tail)` 兜底 ——
        // 一组特别大时（比如一条 3000 字的长回答）对半丢可能仍装不下。
        //
        // ⚠ 半水位这一刀**要封顶**（`cap`，见 [dropCap]）：注入块拼在本轮提问的正文里，
        //   而历史很短时"对半"唯一能落的对象就是那一条 —— 丢掉它等于这一轮把用户的
        //   提问和刚检出的知识库内容一起忘掉。`cap` 是"可裁历史最多能花掉多少"，
        //   它不影响"装不下"这个事实（装不下就继续往下走），只决定**先丢谁**。
        //   负值 = 提问自己就装不下，没有可裁的量该由这里让（交给调用方那几跳）。
        val half = (n + 1) / 2
        // 递减 1 组：`tail = n - k` 时丢掉的量随 k 单调增，所以"丢掉的量 <= cap"
        // 等价于"k <= 某个上界" —— 从少丢往多丢扫，第一个同时满足 `fits` 与 `cap`
        // 的就是"丢得最少又不越过封顶"的那一档。
        for (k in 1..n) {
            val tail = n - k
            // 封顶之后连 `half` 都到不了：直接返回能留最多的那一档（丢得最少）。
            if (cap >= 0 && droppedTokens(tail) > cap) break
            if (tail <= half && fits(tail)) return tail to true
        }
        // 对半丢仍装不下（极端形态）：退回"从多往少找第一个装得下的"。
        // `fits` 不保证单调（`tail=0` 一档是"首条 system 让位"的退化形态，它的
        // token 数可能比 `tail=1` 还小），所以线性扫而不是二分；组数 ≤ 几十。
        for (tail in n - 1 downTo 1) if (fits(tail)) return tail to true
        // ⚠ `half = true`（"按水位丢了"）只在真的**有一档装得下**时返回（ISSUE #503）：
        //   以前这里无条件 `return 0 to true`，而 `0` 那一档在 `keptOf` 里是
        //   "**连首条 system 一起**让位"（`dropUpTo == groups.size` 那条兜底）。
        //   调用方拿到 `n = 0` 就 `dropped = messages.size`（全丢）—— 而这一档
        //   常常**一枚历史都不该丢**：缺口本来就是 `gen` 该让的，`gen` 让不动
        //   （已在地板）才会走到这里。真机形态：`gen 2 / tokens 3404 / avail 3481`，
        //   缺口只有 0，`fits(0)` 因 `2 + 2 > 3481` 之外的误算判否，于是"历史全丢、
        //   生成只剩 2"。判否时返 0 组 ⇒ 调用方不丢历史、也不改 `dropped`，
        //   缺口交给后面那几跳（先削 gen、再判 overflow），比"全丢"诚实得多。
        return 0 to false
    }

    /**
     * 半水位那一刀的**封顶**：可裁历史这一轮最多能花掉多少 token。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么需要这条封顶（知识库注入把"丢一半"变成了明文丢弃）
     * ══════════════════════════════════════════════════════════════════════════
     * 注入块拼在**最后一条 user 的正文之前**（`RagInject.attachBeforeLastUser`），
     * 所以"本轮提问"这一条消息 = 注入块 + 真正的提问 —— **它一条就占着注入块 + 提问。**
     * （续接提示已经不再拼进任何一条正文，见 [resumeShape]；这里保留这条封顶的理由
     * 不变，注入块本身就够大。）
     *
     * 丢掉它的那一轮，丢的不是"几轮历史"，而是用户当场问的那句话，和这一轮命中的
     * 知识库正文 —— 后者每轮重算、不落盘，这一轮不喂给模型就再也不会出现。
     * 而丢历史是本函数唯一的目的（问"刚才聊了什么" vs 问"知识库里这条说什么"，
     * 后者正是他打开知识库在做的事）。
     *
     * 原来的判据是纯 token 对半：`prompt + gen` 减半。本地历史很短时（比如开新会话
     * 刚聊两轮）这个减法唯一能落的对象就是那一大条提问 —— 于是"一次越线"变成
     * "这一轮把知识库和问题一起忘了"，而读数上只是 `dropped: 2`，与丢掉两轮历史
     * 逐字相同。
     *
     * ⚠ 只**限制**丢多少，不改变"全留装不下"这个事实：封顶之后若仍装不下，
     *   调用方那几跳（削 gen、只留本轮提问）照旧会接 —— 多丢的历史换不出这一轮
     *   能答，就没必要多丢。
     *
     * @param askTokens   本轮提问（含注入块与续接提示）那一条的 token 数
     * @param reserveTokens 除"可裁历史"之外**一个字节都不许再动**的那部分（首条 system、
     *                     渲染固定开销、生成后缀）
     * @param gen 这一轮的生成长度上限
     * @param avail 水位线（`ctx × 水位%`）
     * @param forcedOver 这一轮是被**起判线**（`availAt − gen`，ISSUE #488）推进来的：
     *   `gen` 已经在上一跳让到地板，`gen` 那一截不再是"可用的空间"，所以这一笔
     *   按地板扣而不是按当时的 `gen` 扣。
     * @param mustTokens 这一轮**必要的**丢弃量（＝ 按 [dropCap] 自己的口径算出来的
     *   缺口）。封顶不许把"装不下"变成"少丢" —— 见下面 `keptAtLeastOneRound`
     *   那一段（ISSUE #503 真机：越线之后每轮丢、而每轮只丢掉一组，prefill 每轮
     *   2000+）。`0` = 只按封顶判（与引入本参数之前逐字节相同）。
     * @return 丢弃量上限（token）；[UNLIMITED_DROP]（< 0）= 量不出来，不封顶
     */
    fun dropCap(askTokens: Int, reserveTokens: Int, gen: Int, avail: Int,
                forcedOver: Boolean = false, mustTokens: Int = 0): Int {
        // ⚠ 先判"量不出来"：`askTokens`/`avail` 为 0 是"容量未知"，不是"封顶 0"。
        if (askTokens <= 0) return UNLIMITED_DROP
        if (avail <= 0) return UNLIMITED_DROP
        // 可裁历史最多能把水位线以上的那一截全花掉 —— 那正是它存在的理由。
        // `ask + reserve + gen >= avail` 时一枚都花不掉，返回 0（负值专门留给
        // "量不出来"那一档：两件事的处置相反，不能共用同一个数）。
        //
        // ⚠ 这一笔是「**还有多少位置**给可裁历史」，所以它扣的是 `gen` —— 一旦 `gen`
        //   让到地板、甚至让成 0（见下面 `forcedOver`），同样多的缺口就落到了
        //   `ask + reserve` 头上。那时还按原 `gen` 扣，封顶会比真实缺口少 `gen - 地板`，
        //   于是封顶把每一档保留数都挡回去、`minimalKeep` 落到最坏那一档（只留提问）。
        //   真机形态：`ctx 4096 / 水位 85% / max_tokens 1024`，缺口几百枚，而 `gen`
        //   在上一跳已经被削到地板 —— 结果不是"丢几条历史"，是"历史全丢、生成只剩 2"。
        //   `forcedOver` = "这一轮是被起判线推进来的，`gen` 已经让到地板"。
        val askPlusReserve = askTokens.toLong() + reserveTokens.toLong()
        val need = askPlusReserve + (if (forcedOver) KvLongChat.GEN_FLOOR.toLong() else gen.toLong())
        val spare = avail.toLong() - need
        // ⚠ 「封顶」是**上限**，不是**答案**。以前它同时干两件事：既说"最多能丢多少"，
        //   又被 [minimalKeep] 当成"该丢多少"的边界去扫 —— 于是缺口比 `spare` 大的那一轮
        //   会**停在封顶那一档**：丢了一组历史、装不下的事一点没变，下一轮缺口更大、
        //   再丢一组。真机上就是「越线之后每轮 1800 tok 的 prefill、每轮丢 4 条」。
        //   所以缺口（`mustTokens`，同一口径算出来的）比封顶大的时候，封顶跟随缺口 ——
        //   封顶的唯一职责是不让"对半丢"砸到本轮提问上，它从来不该决定"这一轮丢不丢得够"。
        val raw = if (spare > 0L) spare else 0L
        val gap = if (mustTokens > 0) mustTokens.toLong() else 0L
        val cap = Math.max(raw, gap)
        return if (cap > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else cap.toInt()
    }

    /**
     * [dropCap] 的**退化档**：没有可裁的历史可花（`askTokens` / `avail` 量不出来）。
     *
     * 返回负值而不是 0，是为了让"量不出来"与"封顶恰好为 0"（历史还能丢、只是每一枚
     * 都得留给水位）在**返回值**上就分得开 —— 两者都写成 0 时，`min(0, 对半)` = 0
     * 会让一条本来装得下的请求白白多丢一刀历史。
     */
    const val UNLIMITED_DROP = -1

    /**
     * 算出这一轮该发出去的 messages 与生成长度。
     *
     * @param shrinkOnly **只做水位收紧、不做减半**（ISSUE #426 的 HTTP 那条路）。
     *
     * 为什么这一档必须存在：`plan` 的"丢一半"依赖一个前提 —— **我们丢掉的下一轮不会
     * 被原样送回来**。App 内聊天满足它（历史在本地，丢了就是丢了）；HTTP 不满足：
     * 对端每轮都贴着它自己的 Max Context 送整段历史（`Window` 那张表里写着"窗口是
     * 固定尺寸"），于是我们这一轮丢一半、下一轮它把历史全送回来 ——
     * **既丢不了、又多丢**：`dropped` 每轮都是同一个大数，而 KV 前缀起点每轮都在挪。
     *
     * 所以这条路只做"水位收紧"（丢到装得下为止，不多丢）。真正替它减半的是 native
     * `ctxbudget::plan` 里的 `toHalf`（`ctx_budget.h`，落值 `want = max(promptOver, toHalf)`）
     * —— 它面对的是**真 token** 数，且丢完之后 KV 账本就在服务端，下一轮不会被谁
     * "送回来"。两处都减半的话，同一轮会被砍两刀（多忘一轮历史）。
     *
     * ⚠ 减半**不**在 `kvlongchat::planKeep` 里：那条路已经不在产品路径上
     *   （`llama_jni.cpp` 收到 `budget.trimmed > 0` 后直接走 `kvlongchat::startFor`，
     *   不再重算保留量）。别去 `planKeep` 找一个不存在的减半逻辑，更别在那里补一套。
     *
     * @param positionCap **这一条 seq** 能排到的位置数（= native 的 `seq_ctx_limit`，
     *   取 `LlmEngine.seqCtxPerSeq(slot)`；`<= 0` = 没量到，按 `ctx` 判）。
     *
     * 为什么它不是"再多一个可选旋钮"：一格里 App 与 HTTP 各占一条 seq，库里每条
     * seq 的容量是 `n_ctx / n_seq_max` —— 比 `ctx` 小一半。客户端按 `ctx` 放行、
     * 服务端按每条 seq 排号时，这一轮**必然**在 prefill 里越界（ISSUE #426 真机
     * `decode rc!=0 at offset 2048`），而两边都认为自己没错：`plan` 说装得下、
     * `kv_slot` 里每一格读数都合法（`prompt_trimmed 0` / `spare 1014`）。
     * 所以这里收的不是"上限"，而是**同一把尺子**。
     *
     * @param promptTokensOverride 整段 prompt 的**真 token 数**（`<= 0` = 没量到）。
     *   只有裸补全那条路用得上：它的 `prompt` 是渲染好的原文，能直接 tokenize，
     *   而"知道真值还去按字符折算"是拿一把更差的尺子量同一件事。
     *   它只替换总量那一个数（`fits` / `promptTokens` / `droppedTokens`），
     *   "丢几条"仍按逐条折算决定 —— App 与 HTTP chat 两条路不传它（逐字节不变）。
     */
    fun plan(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        ctx: Int,
        generationSuffixTokens: Int? = null,
        systemTokens: Int = SYSTEM_TOKENS_UNKNOWN,
        watermarkPct: Int = DEFAULT_WATERMARK_PCT,
        shrinkOnly: Boolean = false,
        positionCap: Int = 0,
        promptTokensOverride: Int = -1,
        /**
         * 估算系数（`real / est`，[PromptCalibration]）—— 给判据用的那一个数。
         *
         * 只乘在**估算总量**上（`tok` / `tokAll` / `promptTokensReported`），不乘
         * `promptTokensOverride`（那是 native 量出来的真值，再乘一次就是把真值当估算）。
         *
         * `0` / 越界值按 1.0（= 不校准，与引入本参数之前逐字节相同）。
         *
         * ⚠ 为什么它必须走进 [plan] 而不是"在调用方把消息砍短一点"：判据不止一处
         *   （`fits` / 二分找保留组数 / 半水位 / 封顶），散在调用方就是几把尺子。
         */
        estimateScale: Double = PROMPT_CALIB_MAX,
        /**
         * 主动治理开关（默认 [PROACTIVE_DEFAULT] = **关**）。
         *
         * 关掉时只保留"装不下就让"的兜底：判据按**整格** `availHard`、水位只算不判；
         * 打开时逐字节回到水位 + 丢一半那一套（ISSUE #426 方案 B）。
         *
         * ⚠ 它**不**影响任何读数（`watermarkPct` / `drop_room` / `droppedToWatermark`
         *   照旧算、照旧报）：客户端要靠这几格判断"是不是该把自己的 Max Context 调小"。
         * ⚠ 它**不**影响位置线（`positionCap`）：那是"位置号只能排到几"，
         *   与"客户端知不知道自己多长"无关 —— 关掉它等于让 prefill 越界。
         */
        proactive: Boolean = PROACTIVE_DEFAULT,
        /**
         * 调用方**已经替我们删掉了最老的几轮**（ISSUE #452 第 3 步，中间层专用）。
         *
         * 它只描述"这一轮进来之前少了几轮"，**不再**决定这一轮要不要主动动手：
         * 中间层删掉的那几轮下一轮会被客户端重发，而中间层的账本会照服务端这一轮
         * 报回的 `local_rounds` 继续往后续删（见 `RelayTrim.apply` 里的
         * `carriedRounds + index`）。若这里因为"中间层删过"就整轮不治理，水位线
         * 在 relay 上等于从来没开过 —— 用户打开主动治理后看不到任何水位裁剪。
         *
         * ⚠ 它**不**改"装不装得下"的最终判据（`availHard` 那一档），也**不**改
         *   位置线 —— 那两件是安全底线，中间层的存在与否都该成立。
         * ⚠ `0` 是默认值，与"没接中间层"逐字节相同（默认路径上这个参数等于不存在）。
         */
        layoutDroppedRounds: Int = 0,
        /**
         * 调用方（App 内聊天）**上一轮自己前置截掉了最老的几轮**，这一份
         * `messages` 已经是"接着上一轮真喂进去的那一段"拼出来的（`> 0` = 截过）。
         *
         * 判据：`> 0` 时这一轮**不再按水位主动丢**，但"装不下"那一档（`availHard`）
         * 照旧 —— 那是安全底线。
         *
         * ⚠ 它**不是** `layoutDroppedRounds` 的别名，两者的处置也**不同**：
         *   · `layoutDroppedRounds` 是**中间层**替客户端删的（HTTP / relay 那条路，
         *     删的是对端送来的原件，那些轮次下一轮会**再送回来**）—— 中间层的
         *     累计账本（`carriedRounds`）下一轮会继续对齐，所以它**不**关掉主动治理；
         *   · 这一格是**本地**删的（App 内聊天，历史只有一份，删了就不会回来），
         *     再按水位丢一次会把这一轮刚拼好的那一段又砍掉。
         *   来路完全不同 —— 合成一个参数会让"谁删的"再也查不出来。
         *
         * `0` 是默认值，与"这条路没接标记"逐字节相同。
         */
        callerTrimmed: Int = 0,
        /**
         * 上一轮结束时**历史已经整段丢光**了（`Plan.droppedAllHistory`；ISSUE #577 Layer 4）。
         *
         * `true` 时这一轮**不再按水位主动丢**：历史已经没了，再按水位丢一次丢的对象
         * 只剩**本轮提问**（`keptOf` 的兜底会让首条 system 也让位）。而它的症状与
         * "正常丢老历史"逐字相同（`dropped: 1`），于是 `ask_only` 那一轮之后
         * 每一轮都在丢 —— 用户看到的是"它每轮都在忘事"。
         *
         * ⚠ 与 [callerTrimmed] / [layoutDroppedRounds] 不是同一个参数，尽管三条都
         *   退到"只在真装不下时让"：那两条说的是"这一轮**开头**少了几条"，
         *   这一条说的是"**已经没什么可少的了**"—— 判据完全不同
         *   （一个数条数、一个看保留集里有没有 `assistant`）。
         * ⚠ 它**不**改"装不下"那一档（`availHard`）：那是安全底线。
         * ⚠ 它是**上一轮**的事实，不是这一轮算出来的：`plan` 内部算出的
         *   `keptAskOnly` 是"这一轮丢完之后的结论"，拿它喂自己就是自己证明自己。
         */
        prevAllHistoryDropped: Boolean = false
    ): Plan {
        // 生成上限先按"这一格 + 估算的 prompt"钳一刀（ISSUE #330 长对话收口）。
        //
        // 以前 client 侧原样把 `max_tokens` 当成**用量**去和 avail 比，于是
        // `max_tokens=1024` + prompt 3300 就判"装不下"、开始丢整条消息 —— 而真正
        // 该让的是**生成上限**（它只是上限，不是合同）。native 那边同样吃过这个亏
        // （真机 gen 一路被吃到 194 → 2），两处一起改成"先让 gen、只让到地板"。
        //
        // ⚠ 这里只钳到 [KvLongChat.GEN_FLOOR]，不再往下压：压到 2 会让模型只吐
        //   一两个字，而 App 会把那条空回复落盘 —— 下一轮历史里就夹一条空 assistant。
        val wantGen = if (maxTokens > 0) maxTokens else 0
        val suffix = generationSuffixTokens
            ?: (GEN_SUFFIX_BASE_TOKENS + GEN_SUFFIX_THINK_TOKENS)
        // 头部固定段（system 段 + 注入块）的真实长度：已知时直接算成 token 数，
        // 未知时按"消息里写了什么"估（老行为）。
        //
        // ⚠ 为什么**不能**沿用"再给一条空消息"的老招（`listOf("system" to "") + msgs`）：
        //   空正文只值 [PER_MESSAGE_OVERHEAD] 那点壳，于是注入块（真机上 500+ 字）
        //   在预算里**一个 token 都不占** —— 这正是"预算说装得下、渲染后进 native
        //   超了几十上百 token、native 从头部砍一刀、复用归零"那条窄带的来源。
        //
        // ⚠ 也不能拿它去替换消息里的那条 system：`keptOf` 的返回集必须仍是**调用方
        //   发来的那几条消息**（调用方拿它当"真正要发出去的消息"）。所以这里只在
        //   **算 token** 时扣，不动消息表。
        //
        // ⚠ 估算系数（ISSUE #452 ⑤）只乘在**估算**上，且只乘一处 —— 就是这里。
        //   `tok` / `tokAll` 够到它的唯一入口是 [scaled]：散成两三处乘，先乘后加与
        //   先加后乘会差出几枚，而"几枚"正是那条窄带的宽度（判据一旦飘，丢几轮就逐轮抖）。
        // ⚠ 安全余量（[PROMPT_EST_SAFETY_PCT]）与系数走**同一个入口**（[scaledAll]）：
        //   两者都是"给估算加的那一道"，各写一份就会出现"判据按 A、报出去按 B"。
        //   但它们的**位置**不同：系数跟着每个数走，那一笔固定枚数**整段只加一次**
        //   （见下面 [scaled] / [scaledAll] 的分工）。
        // ⚠ 折算表用**判据**那一张（[JUDGE_TOKENS_PER_CJK_CHAR]），不是报告那张实测值：
        //   判据一旦跟着实测值走，真 token 越过水位的那些轮次就会**由 native 先动手** ——
        //   它那一刀是"丢到半水位 + gen 掉到地板"，比这边早些丢一轮贵得多。
        //   ISSUE #452 真机上「裁剪之后只吐几个字」就是这条：装得下时 gen 1024（实际吐
        //   337），真值一越过水位就 `n_rem 64`、之后每轮 64。见 [JUDGE_TOKENS_PER_CJK_CHAR]。
        // 两个口径各一个系数：**报告**那一侧下界 0.70（它的余量只在 `safetyOf` 那一笔里），
        // **判据**那一侧低到 0.55（它天生比报告高一截，校准要修掉的正是这一截）。
        // 见 [PROMPT_CALIB_JUDGE_MIN]。
        val reportScale = PromptCalibration.use(estimateScale)
        val judgeScale = PromptCalibration.useJudge(estimateScale)
        // 报出去的那个数（`est_scale`）是**判据**乘过的那个 —— 它要能对上 `sent` / `dropped`。
        val scaleX1000 = PromptCalibration.toX1000(judgeScale)
        // 一笔固定余量：`ceil` 之后加的是**绝对**枚数，不是"再乘一遍 20%"。乘两遍
        // （逐条一次、整段再一次）会让 20% 变成 44%，而这笔余量本来只是兜"生僻字/代码
        // 块被切碎"的方差 —— 它不该随长度线性放大两倍。
        //
        // ⚠ 而且它**整段只加一次**（ISSUE #488 真机）：这一笔以前写在 [scaled] 上，
        //   而 `scaled` 被 `tokAll` **逐条**调用 —— 于是"余量"变成了"每条 64 枚"，
        //   7 条消息白拿 448，十几条 900+，估算一路顶在水位线上（真机第 3 轮就动手）。
        //   它的量是"这段文本被切碎的那一点方差"，与条数无关。
        //
        //   所以拆成两个入口：
        //     · [scaled]    —— 只乘估算系数（逐条调用那头用它）；
        //     · [scaledAll] —— 乘系数 **再整段加一笔**（`tok` 与 `tokAll` 的合计）。
        fun scaled(est: Int): Int =
            if (est <= 0) est else Math.ceil(est.toDouble() * judgeScale).toInt()
        // ⚠ 两笔都在**系数之前**加/乘（`ceil(est × 系数) + 余量` 那一版把余量留在了系数外面，
        //   系数压到底也压不掉它 —— ISSUE #488 的 B 档）。余量是"方差"、系数是"口径偏差"，
        //   后者作用在**总量**上才对得上 `real / est` 这个比值。
        fun scaledAll(est: Int): Int =
            if (est <= 0) est else Math.ceil((est.toDouble() + PROMPT_EST_SAFETY_TOKENS) * judgeScale).toInt()
        // 判据口径的一段 prompt 估算（消息 + 渲染固定开销 + 生成后缀）。
        fun judgePromptTokens(msgs: List<Pair<String, String>>): Int =
            RENDER_OVERHEAD + suffix.coerceAtLeast(0) + msgs.sumOf { judgeTokensOf(it.first, it.second) }
        // 头部固定段的**修正量** = 「这条 SYSTEM 标出来的外部头部长度」 − 「消息里那条
        // system 自己已经算过的正文长度」。
        //
        // ⚠ 必须取差额，不能取全额（ISSUE #452 真机 `真 2076 / 估 2585`）：知识库注入块
        //   是拼在**消息正文**里的（`RagInject.attachBeforeLastUser` 拼在最后一条 user，
        //   拼在 system 上的那一档同样在消息里），`judgePromptTokens` 已经按字符数把它
        //   数过一遍。再按 `systemTokens` 全额补一次，同一段文本就占了两份位置 ——
        //   真机上多出来的那几百枚正好把水位顶穿，于是"离硬墙还有 1600 枚就开始丢历史"，
        //   而 `prompt_real` 一路装得下（用户原话：估算超很多，白丢会话）。
        //   差额这一条同时覆盖两种形态：
        //     · 注入块在消息里（常态）→ 差额 ≈ 0，只吃字符折算与真 token 的那点偏差；
        //     · 头部长度来自消息之外（老用法 / 对端自己拼的段）→ 差额就是那一段，
        //       与引入差额之前逐字节相同。
        // ⚠ 差值为负时**不许**扣（宁可多估）：消息里那条 system 比标称的长，可能只是
        //   标称值没跟上（`SYSTEM_TOKENS_UNKNOWN` 已在上面拦掉），扣下去就是低估 ——
        //   而低估在下游是 native 每轮从头部砍一刀、复用归零。
        fun systemFix(msgs: List<Pair<String, String>>): Int {
            if (systemTokens == SYSTEM_TOKENS_UNKNOWN) return 0
            if (msgs.isEmpty() || msgs.first().first != "system") return 0
            // ⚠ 消息表（`judgePromptTokens`）是**按条**累加的、每条都带
            //   `PER_MESSAGE_OVERHEAD`，所以两边都用**含壳**的口径相减；
            //   这里再减一次 `PER_MESSAGE_OVERHEAD` 就等于把那条壳从差额里抹掉，
            //   注入块那种量级上它就是十几枚的错账（真机 806 字：差 0 vs 差 10）。
            val systemInMsgs = judgeTokensOf("system", msgs.first().second)
            // ⚠ 差额为负时**不扣、但要补**（ISSUE #503 真机第三轮）：
            //   `systemTokens` 的来处是 `systemTokensOf`（`tokensOf` 那张**报告**口径），
            //   而消息那一头是判据口径。注入块在消息里时，标称值只是"渲染出来的
            //   system 正文"那一截，**不含注入块**（它拼在最后一条 user 里）——
            //   于是这里算出的差额就是**注入块本身**，必须补进判据。
            //
            //   差额为负说明"消息里那条 system 在判据表下更值钱"（两张表差 29%），
            //   那一截消息表已经数过，不重复补；但也不能拿它去扣 —— 扣下去是低估，
            //   而低估在下游就是 native 每轮从头部砍一刀。
            return systemTokens - systemInMsgs
        }
        fun tok(msgs: List<Pair<String, String>>): Int {
            val est = scaledAll(judgePromptTokens(msgs))
            val fix = systemFix(msgs)
            // 只补**差额**（见 [systemFix]）：差额为 0 时这一跳什么都不做，
            // 于是"消息里那条 system 就是全部头部固定段"的常态逐字节不变。
            // ⚠ 补的也是**整段一笔**（`scaledAll`，不是 `scaled`）：它是同一段头部
            //   文本的估算，余量那一笔跟着它一起走，否则"头部来自消息之外"那一档
            //   会比常态少 64 枚 —— 而两条路的合计都对不上 `tokAll` 就更糟。
            // ⚠ 差额**只补不扣**（见 [systemFix]）：负数那一档意味着"消息表在判据口径下
            //   已经数得比标称值多"，那一截不重复补，也绝不回头去扣（扣 = 低估 = native 砍头）。
            // ⚠ 补的是**系数后的一笔**（`ceil(fix × 系数)`），不是 `scaledAll(fix)`
            //   （它还多算了一次整段余量）—— 余量整段只该加一次，见 [scaledAll] 那段。
            return if (fix <= 0) est else est + scaled(fix)
        }
        // 逐条估出来的 token 表（不是总和）：判据要拿**每一条**去比，才能不假设单调。
        //
        // ⚠ 逐条那一头只乘系数（`scaled`），固定余量只落在**第 0 条**上 —— 它代表
        //   "这一整段"的那一笔，挂在哪一条都是同一件记账（合计与 [tok] 相等），
        //   挂第 0 条的好处是"只保留前 k 条"那一档也把它带上了（不会因为裁短而
        //   凭空长出一笔余量）。合计必须与 `tok(msgs)` 同口径，否则同一个保留集
        //   在两处判据里会差出一笔 64 —— 而 64 正是那条窄带的宽度。
        fun tokAll(msgs: List<Pair<String, String>>): List<Int> {
            val base = msgs.map { scaled(judgeTokensOf(it.first, it.second)) }.toMutableList()
            if (base.isEmpty()) return base
            // 余量那一笔挂第 0 条、头部差额也补在第 0 条（`systemFix` 只在首条是
            // system 时非 0）。**合计必须与 [tok] 逐枚相等**：同一个保留集在两处判据
            // （`fits` 用 `tokAll` 的和、`keptEst` 用 `tok`）里差出一笔，就会出
            // "逐条表说装得下、整段说装不下"（或反过来）—— 见下面那条 ⚠。
            //
            // ⚠ 补的是**差额**，不是把这个常数再写一遍（ISSUE #503 真机）：`tok` 走的是
            //   `scaledAll(整段) = ceil((整段 + 64) × 系数)`，而逐条是
            //   `Σ ceil(逐条 × 系数)` —— 两者**只差每一步向上取整**。以前这里直接
            //   `+ PROMPT_EST_SAFETY_TOKENS`，于是少乘一次系数（系数 1.0 时恰好相等，
            //   系数往下走一档就不等：0.85 / 0.70 / 0.55 分别少 12 / 21 / 31 枚）。
            //   真机形态：`tokAll` 的合计**系统性偏大** → `fits(tail)` 一路判否 →
            //   `minimalKeep` 落到"只留本轮提问"，`dropped = messages.size` 而缺口
            //   根本不存在（`gen` 与整段都还有空间）。报出去的 `promptTokens` 取自
            //   `tok`，所以那几轮连"我丢了历史"这一句都不会印（`dropped > 0` 那几条
            //   日志都以它为前提）—— 用户看到的就是"超了却没触发、日志里一个字没有"。
            base[0] = base[0] + (tok(msgs) - base.sum())
            return base
        }

        // ⚠ 从这里往下，`tok` / `tokAll` 给出的都是**判据口径**的估算：已乘估算系数
        //   （[PromptCalibration]）与安全余量（[PROMPT_EST_SAFETY_PCT]），且只在
        //   `scaled()` / `scaledAll()` 那一处乘。报告口径走 [safetyOf]，两者同源。
        // ⚠ `totalOf` 只是给"整段 prompt 的真 token 数"（`promptTokensOverride`）开一个
        //   入口：调用方手里有真值时，"这一轮装不装得下"必须按真值判（折算在英文上
        //   差 3~4 倍，低估就是把这一轮推给 native 从头部砍）。它只替换**总量**这一个数，
        //   "丢多少条"仍按逐条折算决定 —— 拿真值去反推逐条份额就是另一套尺子。
        //
        //   ⚠ 它**不下渗**到 `promptTokensReported` / `droppedTokens`（#452 之后的纪律）：
        //     真值只有"原样那一份"，裁过之后就没有真值可比了。用它当"裁之前"会把
        //     两个坐标系混进同一个差值里 —— 而那个差值正是 `local_ctx.dropped`。
        fun totalOf(msgs: List<Pair<String, String>>, est: Int): Int {
            if (promptTokensOverride <= 0) return est
            if (msgs.size != messages.size) return est   // 裁过了：真值只描述原样那一份
            return promptTokensOverride
        }
        // ⚠ `promptTokensOverride > 0`（整段 prompt 的**真 token 数**）时整份改用它：
        //   调用方量得到真值，就不该再拿字符折算去量同一件事（英文上两者差 3~4 倍，
        //   而低估会把这一轮推给 native 从头部砍）。它只替换「原始总量」这一个数 ——
        //   丢/削的**过程**仍按逐条折算走，所以 `droppedTokens` 仍是近似值，
        //   而"这一轮装不装得下"用的是真值。
        val tokensBefore = tok(messages)
        // 「这一份**真的要发出去**」的估算 —— 每个出口都必须落它（不是 `tokensBefore`）。
        //
        // ⚠ `tokensBefore` 只用来判"这一轮装不装得下"，**不报出去**：报给 `/health` 的
        //   `prompt_est` 必须是**真正发出去的那一份**。这一格以前直接落 `tokensBefore`
        //   （= 丢之前），于是丢过历史的轮次里 `prompt_est` 与 native 数出来的
        //   `prompt_real`（= 丢之后）描述的不是同一份东西，差值随 `dropped` 一起长：
        //   丢得越多、"估多了 N tok"越大，`est_scale` 被它压到 0.70，下一轮丢得更多。
        //   用户看到的就是那句"估算超很多，白丢会话"（ISSUE #452）。
        fun keptEst(msgs: List<Pair<String, String>>): Int = totalOf(msgs, tok(msgs))
        // 「**报出去**」的那一份估算 —— 与 `keptEst` 唯一的差别是折算表：
        // 这里走实测值（[TOKENS_PER_CJK_CHAR]），它要和 native 的 `prompt_real` 描述
        // 同一段文本、用同一条尺子，差值才回答得了"估算准不准"。
        // 判据那边走 [judgeTokensOf]（保守表）—— 两个数因此**不是同一个数**，而这是
        // 有意的：判据要比报告更早一点认输，否则越线那一刀会落在 native 手里（见
        // [JUDGE_TOKENS_PER_CJK_CHAR]）。`/health` 的 `prompt_est` 用这一份。
        fun reportedEst(msgs: List<Pair<String, String>>): Int {
            if (promptTokensOverride > 0 && msgs.size == messages.size) return promptTokensOverride
            // 余量与系数在 [safetyOf] 里**同一次**乘（余量在前、系数在总量上）——
            // 分两处乘就是 ISSUE #488 那条"系数压在余量之外、永远修不掉偏高"。
            // 真值那一侧不乘：真值本来就不是估算。
            // ⚠ 头部固定段的差额也要算进来（ISSUE #503 真机第三轮）：这一格是
            //   `Plan.promptTokens` / `local_ctx.sent` 的来处，也就是**报出去**的那个数。
            //   只按消息表算，注入块（拼在最后一条 user 里、`systemTokens` 却把它算在
            //   头部）就对它贡献 0 —— 真机同一轮 native 数出 1525 枚，而这一段报 318，
            //   读的人只能得出"裁剪量算错了"。
            //   `systemFix` 与判据那一侧**同一个函数**（差额=标称−消息里已数过的），
            //   两处各算一遍必然漂。
            val fix = systemFix(msgs)
            return safetyOf(
                RENDER_OVERHEAD + suffix.coerceAtLeast(0) + estimate(msgs) +
                    (if (fix > 0) fix else 0), reportScale)
        }
        if (ctx <= 0 || messages.isEmpty()) {
            // 容量未知 = 不裁（与引入本特性之前逐字节相同）。0 表示"没有可算的"。
            // ⚠ 这一档是唯一一处**真的"一条都没丢"**：原样发出去，起点就是第 0 条。
            //   空保留集那一档（[overflowAllDropped]）写的是 `messages.size`，
            //   方向正好相反 —— 两档连"一条没丢"与"全丢"都会读反。
            return Plan(messages, 0, keptEst(messages), wantGen, ctx, false,
                promptTokensReported = reportedEst(messages),
                estimateScaleX1000 = scaleX1000,
                keptFrom = 0, trimmedByCaller = callerTrimmed.coerceAtLeast(0))
        }
        // 生成空间先按容量钳一刀（与 native 的 `room = ctx - 1` 同一条口径）。
        // ⚠ `room` 的基数下面会换成**这一条 seq 的容量** `posCap`（见那段注释）：
        //   两路各一条 seq 之后，"能排到几号"不是整格的那个数。
        var room = ctx - 1
        // 这一格连一个位置都留不出（`ctx <= 1`）：一条都发不出去，`overflow` 那一档。
        // 报出去的估算仍是**原样那一份**（`keptEst(messages)`），不是 0：用户要的是
        // "我送了多大一段、这一格有多大"，而 0 tok 会把它读成"我什么都没送"。
        // 归一先算：水位是**判据的一级输入**，早退那两档也要把它原样报出去 ——
        // 报一个哨兵 0 会被客户端读成"用户把水位关掉了"（见 `describe` 那条 ⚠）。
        val wmPct = ContextWatermarkPct.normalize(watermarkPct)
        if (room <= 0) return overflowAllDropped(messages, keptEst(messages), wantGen, ctx,
            // 这一档还没算位置线（`ctx - 1` 就没了），按整格报 —— 它判的就是整格。
            watermarkPct = wmPct,
            posCap = ctx, estimateScaleX1000 = scaleX1000, trimmedByCaller = callerTrimmed)
        var gen = if (wantGen > room) room else wantGen
        // ── 水位上限（ISSUE #426 方案 B）────────────────────────────────────────
        //
        // `avail` 此前是 `ctx - MIN_GEN_TOKENS`（把整格吃满，只给采样留 2 枚）。
        // 那是"能装多少装多少"，代价是**每轮都贴着容量线跑**：历史每涨几百 token
        // 就得再丢一组，而每丢一次 KV 前缀起点就后移一次 —— 用户看到的是
        // "超限之后，之后生成的内容全都跟前面没关系了"。
        //
        // 现在把可用空间压到 `ctx × 水位`（默认 85%），并按 [ContextWatermark] 的
        // 说明**越过水位就丢到「水位线 × 占用比」那条落点**（[HALF_RATIO_PCT]）。
        // 于是丢掉的那一轮只断一次复用，之后要涨一段才再动 —— 不是每轮都断。
        //
        // ⚠ 这里的 `ctx` 是**这一格**（`S.n_ctx`），而"能排到几号"更紧的时候是
        //   这一条 seq 的容量（[positionCap]）—— 两路各一条 seq 时后者是前者的一半。
        //   真机 `decode rc!=0 at offset 2048` 就是拿了前者去放行、后者去排号。
        //
        // ⚠ 水位只**收紧**、不放大：`watermarkPct` 归一后落在 50..95，乘出来必然
        //   `< ctx`。取 0/非法值时归一成默认 85 —— 而不是"关掉水位"：一个读错的
        //   0 若被当成"不限"，用户会以为关掉了水位、实际每轮都在断。
        // 「这一轮要不要**主动**动手」—— 与"装不装得下"是两件事（见 [PROACTIVE_DEFAULT]）。
        //   · `proactive = true`：越过水位就丢一半（ISSUE #426 方案 B，今天的行为）；
        //   · `proactive = false`：只在真装不下（越过整格 `availHard`）时才让 ——
        //     装得下就一枚不丢，水位只算不判。
        // ⚠ 水位仍然是**一档更紧的目标线**，只是它不再自己触发丢弃：客户端一旦自己
        //   裁得准，这条线的每一个动作都是负收益（提前丢历史、gen 掉到地板）。
        // ⚠ 中间层已经前置删过整轮时**仍然可以按水位动手**（ISSUE #452 第 3 步收口）：
        //   以前这里读 `layoutDroppedRounds <= 0`，理由是"再丢一次会把它刚保留的那段
        //   删掉"。但 relay 的 `messages` 是**客户端原件减掉上一轮累计丢弃**之后的
        //   一份；它若仍然越过水位，再丢一轮正是水位该做的事，而不是"删两遍" ——
        //   中间层下一轮会把这一轮 `local_rounds` 的 `index` 加到 `carriedRounds`
        //   上（见 `RelayTrim.apply`），删的是同一段累计轮次，不会重复。
        //   旧判据的直接后果：用户打开主动治理后，HTTP relay 端点永远看不到水位裁剪。
        //
        // ⚠ `prevAllHistoryDropped` 也要进来（ISSUE #577 Layer 4）：上一轮已经把历史
        //   整段丢光时，这一轮再按水位丢，丢的对象就只剩**本轮提问** —— 而 `dropped: 1`
        //   与"正常丢了一轮老历史"逐字相同，读数上完全看不出来。
        val proactiveOn = proactive && callerTrimmed <= 0 && !prevAllHistoryDropped
        // ⚠ `availAt` 不抬到 [MIN_GEN_TOKENS] 之上：`ctx` 小到连保底空间都留不出时，
        //   这里算出 0 或 1 才是事实 —— 抬一下会让"这一格根本没法用"变成"1 tok 也能答"，
        //   然后 native 撞墙报一句谁也看不懂的话。水位只收紧，不负责兜底。
        // ⚠ 位置那一把尺子（这一条 seq 的容量）在这里就要接上：它比整格紧时，
        //   `room`（= ctx-1）与整格都太松，下面所有判据（`availAt` / `availHard`）
        //   都得按它收 —— 否则客户端放行的这一轮在 prefill 里必然越界
        //   （ISSUE #426 真机 `decode rc!=0 at offset 2048`）。
        //   0 / 负数 = 没量到（单 seq、未加载），退化成改动前的口径。
        val posCap = if (positionCap > 0) Math.min(ctx, positionCap) else ctx
        room = posCap - 1
        if (room <= 0) return overflowAllDropped(messages, keptEst(messages), wantGen, ctx,
            watermarkPct = wmPct, posCap = posCap,
            estimateScaleX1000 = scaleX1000, trimmedByCaller = callerTrimmed)
        if (gen > room) gen = room
        val availAt = (posCap.toLong() * wmPct / 100L).toInt().coerceAtMost(room)
        // 水位线以下那句"整格能让到哪"仍要用：水位是**目标**，不是硬墙 ——
        // 历史丢光了仍装不下时，最后那一档（只留本轮提问）按整格算，
        // 否则 `ctx=4096 + 水位 85%` 会让"3500 tok 的回答"这种合法请求被拒。
        //
        // ⚠ 两条线都要被**这一条 seq 的容量**收紧（ISSUE #426 真机
        //   `decode rc!=0 at offset 2048`）：两路各一条 seq 之后，"这一轮能用几个
        //   位置号"是 `n_ctx / n_seq_max`，不是整格 `n_ctx`。客户端按整格放行、
        //   服务端按每条 seq 排号时，这条请求**必然**在 prefill 里越界，
        //   而两边都认为自己没错（每一格读数都合法）。`positionCap <= 0` = 没量到
        //   （单 seq / 未加载），此时退化成改动前的口径。
        //   `- MIN_GEN_TOKENS` 那一步与 native 的 `avail_hard` 同一条（posCap 已在上面接好）。
        val availHard = posCap - MIN_GEN_TOKENS
        // 判据线：主动治理开着时是水位线，关掉时是整格（`availHard`）。
        // ⚠ `availAt` 那一格仍然要算：`drop_room` / `crossedWatermark` 与
        //   `local_ctx.watermark` 都从它来 —— 关掉的是"拿它当丢弃判据"，不是它本身。
        val avail = if (proactiveOn) availAt else availHard

        // ── 主动动手的那条线：**水位线 − 生成空间**（ISSUE #488）──────────────────
        //
        // 水位量的是 `prompt + gen`。所以「第一次装不下」发生在 `tokens` 涨到
        // `ctx × 水位 − gen` 的那一刻 —— `ctx 4096 / max_tokens 1024 / 水位 85%`
        // 就是 `3481 − 1024 = 2457`。真 prompt 涨到 2457 之前，这一轮**根本装得下**。
        //
        // 而下面那一跳原先的触发点是"`tokens` 已经不高于水位线"，且在同一跳里把
        // `gen` 削到"刚够"（1024 → 963）。两者叠起来，真机读到的是：
        // 真 prompt 2159 / 判据估 2518 的那一轮就丢了最老 6 条，两轮后又丢 4 条、
        // 再两轮 8 条、再两轮 10 条。用户原话：「4096×0.85−1024=2457，应该 2000 多
        // 才触发水位线啊，你看最开始几轮的对话」。
        //
        // 所以起判线**只决定动不动手**：`tokens` 越过它，说明 `tokens + gen` 已经
        // 越过水位（判据仍是 `fits`，见 [avail]）—— 两条线一起读才不漏。
        //
        // ⚠ 不动 [avail]：它同时是 `drop_room` / `crossedWatermark` / `local_ctx.watermark`
        //   的量具（"这一轮能用多少位置"）。减过 gen 的数是**起判线**，不是容量。
        // ⚠ `gen` 在这一步已经是 `min(wantGen, posCap - 1)`（按整格 / 这一条 seq 钳过），
        //   所以 `availTrigger` 落在 `[水位线 − 整格, 水位线]` 里，不会为负。
        val availTrigger = (availAt - gen).coerceAtLeast(0)

        // 可裁候选 = 除"首条 system"与"最后一条 user"之外的所有消息。
        //
        // ⚠ 首条 system 这一位是**结构**（角色序列的第一步），不是内容里的优先项：
        //   它被丢的时候，客户端为这一格准备的那份固定头部（`systemTokens`，真值是
        //   "渲染出来的 system 正文 + 注入块"）**不会跟着变** —— 于是全仓只有
        //   `keptOf` 那条兜底会丢它（"历史丢光仍装不下"），而下一跳马上又把提问
        //   自己顶到整格去判，随即 400。换句话说它一路走不到"装得下"的出口。
        //   两条路都这么走（App 内 / HTTP），所以 HTTP 上"把 system 丢了还照样答"
        //   并不存在；单测 (`ContextBudgetTest` 组 20) 钉的正是这个性质。
        val lastUserIdx = messages.indexOfLast { it.first == "user" }
        val keepFirstIdx = if (messages.first().first == "system") 0 else -1
        val droppable = (messages.indices).filter { it != keepFirstIdx && it != lastUserIdx }

        // 先把候选**分组**：一条 user 连同其后**所有**连续 assistant 算一轮（丢就整轮丢）。
        //
        // ⚠ 分组必须在**原始下标**上一次算完，且配对只在"相邻且都还没被别的规则排除"
        //   时才成立（见下面的 `droppable` 连续性判断）。以前跳过首条 system 之后仍按
        //   `droppable` 的相邻关系配对，会把 system 后面那条 user 与更晚的 assistant
        //   配成一对 —— 角色序列看着还是交替的，静默答非所问。
        //
        // ══════════════════════════════════════════════════════════════════════
        // 为什么是"一条 user + 其后**所有** assistant"，不是"user + 紧邻一条"
        // ══════════════════════════════════════════════════════════════════════
        // 旧写法只认紧邻的那一条 assistant，一条 user 后面若跟了两条（模型重生成之后
        // 上层把两条都留在历史里、或中间插了一条提示消息），配对就在第一条上断掉，
        // 后一条被当成**独立一组**。于是"从最老开始整组丢"砍下去时会砍在**同一轮的
        // 两段之间** —— 角色序列照样合法（`assistant, user, ...`），语义断在半句话中间，
        // 模型拿到的是"问了一句、答了半句"。
        //
        // ⚠ 这里只改**分组的切法**，不动"留几组"的判据：`minimalKeep` 数的仍然是组数
        //   （见那段注释里写死的「保留组数是跨轮该稳定的量」）。改成"每轮一组"只会让
        //   `n` 变小、不会让它变成另一个量纲 —— 而"从 `half` 往回找第一个装得下的"
        //   与"按 token 目标减半"两种写法在那里已经标成错过两次，不许借这次改回去。
        // ⚠ 也不许把 `tail = 0` 那一档的语义改掉：它仍然是"只留本轮提问、首条 system
        //   让位"（见 [keptOf] 的兜底），把这条与分组一起动会让"单轮请求丢设定"回来。
        val groups = groupRounds(messages, droppable)

        // 「最老那一组多大」—— 只当落点的**上界**用（`avail − per`），不当加数。
        //
        // ⚠ 它**不是**"下一轮会长多少"（ISSUE #586）：量法是 `keptOf(messages, groups,
        //   n-1)` 与全量的差，也就是**最老**那一组的篇幅。拿它当"下一轮增量"去压落点，
        //   落点就随这份历史里最老那条消息的长短漂 —— 而下一轮会长多少与它无关。
        //   下一轮别越线这件事由 [HALF_HEADROOM_PCT] 那笔固定余量兜（不随任何一段漂）。
        //
        // ⚠ 它必须定义在**这一层**：`if (!fits)` 那一支之外的几跳（落点兜底、
        //   越线未丢）也要读它。写在那一支里面时那几跳全部 `unresolved reference`
        //   —— 而 Kotlin 这一步编不过，整个 APK 就出不来。
        // ⚠ 一轮都还没丢过（`dropped == 0`）时没有可量的东西，取 0 = 退回"只减 gen"
        //   那一档：宁可少扣，也不能拿一个编出来的常数把落点往下压。
        // ⚠ 它只是落点的**上界**（`avail − per`），不进 `target`（ISSUE #586）。
        var kept: List<Pair<String, String>> = messages
        var dropped = 0
        var roundPer = 0
        fun refreshRoundPer() {
            roundPer = if (dropped > 0 && groups.isNotEmpty()) {
                val allNow = keptEst(messages)
                val firstKept = keptEst(keptOf(messages, groups, groups.size - 1))
                (allNow - firstKept).coerceAtLeast(0)
            } else 0
        }
        // 削掉的生成长度：从**进入本函数时的意愿值**起算（`gen` 在下面会被一路钳/削，
        // 拿它当基线就只剩"最后一步削了多少"，而用户看到的是"我设 1024 只拿到 64"）。
        var genTrimmed = 0
        // 这一轮的丢弃是**按水位丢一半**（而不是"丢到刚好装下"）：两个数在
        // `dropped > 0` 上完全同形，而下一轮的行为正相反（一个不再丢、一个继续丢）。
        var lostToWatermark = false
        val keepFlags = BooleanArray(messages.size) { true }
        // 保留集的**第一条在原列表里的下标**（[Plan.keptFrom]）。
        //
        // ⚠ 它只在**出口**那一段赋值一次。五种生产路径（原样 / `minimalKeep` /
        //   试丢全量 / 只留提问 / 兜底）各有各的切片，在各自那里各算一遍就等于
        //   有五个起点 —— 那是本仓库栽过的"两个坐标系"。
        var keptFrom = 0
        // 削序与 native（`ctxbudget::plan`）同一条：**先削 gen，再削 prompt**。
        //   · 削 gen   = 只是把"愿意接受多长"调小，对 KV 前缀零影响；
        //   · 削 prompt = 每轮砍在同一处，公共前缀就断在那，复用直接归零。
        // 所以只有"gen 已经让到保底、还是装不下"时才动消息。
        // ⚠ 这里的判据是 `tokens + gen > avail`，其中 `avail = ctx - MIN_GEN_TOKENS`。
        //   而 gen 的**地板**是 `GEN_FLOOR`（64，见 [KvLongChat]）：两者不是同一个数。
        //   核对「让完之后正好装得下」时要按**地板**比 —— 否则短注入块那一轮
        //   （`tokens + 64 == ctx - 2`）会被误判成"要让位"，从而多丢一条消息。
        //
        var tokens = totalOf(kept, tok(kept))
        // 两条线一起读：`tokens + gen` 是「装不下」（判据，[avail]），`tokens` 是
        // 「该动手了」（起判线，[availTrigger]）。后者只在主动治理那一档存在 —— 它
        // 关掉时判据是整格，本就是「装得下就一枚不丢」（ISSUE #452 第 2 步）。
        // ⚠ `overTrigger` **只决定要不要动手**，不参与"装不装得下"（ISSUE #503 真机第二轮）：
        //   以前它被并进 `fits`（判据里多带一个 `!overTrigger`），于是
        //   判据链最末端那几跳（收口丢一组、削 gen）全都按"装不下"走 —— 而
        //   `tokens + gen` 其实离水位线还有上千枚。真机读数：
        //     `[ctx] 越过水位 85% —— 丢最老 12 条消息，约 2059 tok ≤ 4096×85%，
        //      生成上限 64`
        //     `[s0][ctx] 本轮没丢（1525 tok 全进，生成上限 64）`
        //   同一个 prompt，客户端报 2059、native 数出 1525 —— 缺的那 534 枚不是估算
        //   误差，是客户端**按假的缺口**丢掉的历史（`keptOf` 只按条数落，条数一变
        //   真 token 就少一大截）。而 `tokens + gen` 无论按哪个数都没越线。
        //   所以"要不要丢"与"能不能装下"必须分开：前者看起判线（`overTrigger`），
        //   后者只看 `tokens + gen <= avail`。
        val overTrigger = proactiveOn && tokens > availTrigger
        val fits = tokens + gen <= avail
        if (!fits) {
            // ① 先把生成上限让到**地板**（不丢任何消息）。
            //
            // ⚠ 地板不是 `MIN_GEN_TOKENS`(2) 而是 [KvLongChat.GEN_FLOOR](64)：
            //   真机上"一超过上下文就开始胡说"就是 gen 掉到 2 的那几轮 —— 那几轮
            //   回答只有一两个字，App 把它当正常回答落盘，下一轮历史里就夹了一条
            //   空 assistant。一段历史被这样污染之后，模板渲出来的角色序列与模型
            //   训练时的形状不再同形，于是"开始胡说"，而它看起来像模型坏了。
            // 让到的落值 = min(gen, avail - tokens)，但**不许低于 [KvLongChat.GEN_FLOOR]**
            //（除非 prompt 本身已经占满了 avail —— 那时没有别的能让的东西）。
            // ⚠ 这里的 `room` 只剩**几枚**（`avail - tokens`）时不许再"让 gen"：
            //   那一让会从 `max_tokens` 一降到 `MIN_GEN_TOKENS`，而这**不是**用户的意思，
            //   也不是必要的意思 —— min(`gen`, `room`) 在 `room` 只剩 7 时给出 7（已够），
            //   `maxOf(room, ...)` 却在 `room < 地板` 时给出地板之下那个数。
            //   症状：3320 字那一档 gen 从 8 被削到 2（而不是停在 7），然后按"地板之下"
            //   再走一遍丢弃/文案，把"装得下"说成"回答会很短"。
            //   判据改成"能装就只让到 room；room 连保底都没有时才用保底与 room 的较小者"。
            //   ⚠ `room` 量的是"这一轮还能留多少"（水位线减掉 prompt），所以让到
            //   `room` 之后 `tokens + gen` 正好落在水位线上 —— 不多不少。
            val room = avail - tokens
            // ⚠ 让位**只让到 [KvLongChat.GEN_FLOOR]，绝不让到 2**（ISSUE #503 真机）。
            //
            // 以前这里按 `overTrigger` 分两支，`overTrigger` 那一支的目标是
            // `minOf(gen, maxOf(room, MIN_GEN_TOKENS))` —— 它把「水位线减掉 prompt」
            // 当成了地板，而水位线离装满还有 15%，于是 `room` 为负或只剩个位数时
            // `gen` 一步落到 2。那是 ISSUE #426/#330 修过一次的老坏法，换了个入口回来：
            //   · `gen` 落到 2 → 下面那条封顶判据 `forcedOver = gen <= GEN_FLOOR` 恒成立，
            //     `dropCap` 按地板算出的封顶又**大于**实际缺口；
            //   · 于是 `minimalKeep` 说"丢一半仍装不下"，落到最坏那一档；
            //   · `dropped > 0 && groups.isNotEmpty()` 那条分支更绝 —— 它直接返回
            //     `keptOf(..., 0)`，**连 `fits` 都不复核**。
            // 三个"装不下"叠在一起，`gen` 从此再不回升。用户看到的就是
            // "第一次直接挤满，然后生成 2，之后一直生成 2"。
            //
            // 现在的判据只有一条：`room` 够地板就"让到剩余空间"（`tokens + gen` 正好
            // 落在水位线上，一枚不多）；`room` 不够才停在地板，把缺口交给下面丢历史
            // 那一跳 —— 那一跳默认就会走（`fits` 已否），不需要靠"先把自己砍到 2"
            // 来触发。`overTrigger` 那一档于是与普通那一档同一条落值：它的缺口本来
            // 就由"丢到半水位"吸收，`gen` 在这一步多让只会让回答更短。
            // ⚠ 但「让到 `room`」这件事**不能**在越线那一轮做（ISSUE #488 真机）：
            //   起判线只量 `tokens`，于是越线的那一轮 `room`（= 水位线 − tokens）
            //   明明是**正的**、而且还不小（真机 `prompt 2159` 那一轮 `room` 上千），
            //   让下去之后这一轮**装得下**（`tokens + gen <= avail`）——
            //   后面每一条丢历史的判据都以"装不下"为门，于是**一条都不丢**。
            //   而 `overTrigger` 恒真，下一轮 `tokens` 更大、`room` 更小，再让一刀……
            //   `gen` 就这样一格一格被锉到地板，历史却一条没少 ——
            //   真机读数 `gen_cap 2 / gen_planned 2 / n_rem 2 / spare 3804` 就是它：
            //   288 tok 的 prompt 落在 4096 里，缺口一枚都没有，gen 已经锉到底。
            //   所以越线那一轮的目标是**地板**：`gen` 让位、缺口**留着**交给下面丢历史
            //   那一跳（它判的是"装不下"，而这里必须让 `tokens + gen > avail` 成立），
            //   丢到一半水位之后 `gen` 再由出口那一段按剩余空间补回来。
            //
            // ⚠ **越线那一轮也不许把 `gen` 削到地板**（ISSUE #503 真机第三轮）：
            //   上面那条注释里的写法是 `if (overTrigger) minOf(gen, GEN_FLOOR)` —— 它
            //   想的是"让位、把缺口留给丢历史那一跳"，代价却是**每一轮都白削 960 枚
            //   回答**，而丢历史那一跳常常一枚都不用丢（缺口本来就是它算出来的假象，
            //   见 `fits` 那一处）。真机读数：`[聊天] 生成长度 64（请求 1024，预算削 960）`
            //   连着两轮，而同一份 prompt native 只数出 1525 / 1358 枚。
            //   现在的落值与普通那一档相同：`room` 够地板就让到剩余空间，不够才停在地板。
            //   "越线就丢历史"这条判据在下面那条门（`tokens + gen > avail || overTrigger`），
            //   与 `gen` 让到多少无关 —— 它只看 `overTrigger`。
            val target = if (room >= KvLongChat.GEN_FLOOR) minOf(gen, room)
                         else minOf(gen, maxOf(room, MIN_GEN_TOKENS))
            val before = gen
            gen = minOf(gen, target)
            if (gen < before) {
                genTrimmed += before - gen
                // ⚠ 削了 gen **必须**在这里重算一次总量：`tokens` 是在判 `fits` 之前算的
                //   （那时 `gen` 还是原值），而它决定了"还要不要再丢历史"。
                //   不重算的话，一个"只多 5 枚"的请求会被当成"还差 13 枚"，
                //   于是丢掉历史里唯一那条 system（单轮请求没有可裁候选），
                //   而读数只是 `dropped: 1` —— 看起来像丢了一轮对话。
                // ⚠ 用 `keptEst`（按**当前** `gen` 折算生成后缀），不是 `tok`：后者含着
                //   "按意愿值估的生成后缀"，`gen` 让位之后同一个保留集会在两处判据里
                //   差出 `wantGen - gen` 枚 —— 真机就是靠这个差把 `dropped` 永远按在 0 上。
                tokens = keptEst(kept)
            }
            // 让完之后仍然装不下，才从**最老**开始丢，成对丢（见上面 ③）。
            //
            // ⚠ 这里是**一次丢到位**（`#330` 真机第二回合），不是"每轮只丢一组"：
            //   · 每组大小不等（一问一答 600 token，一轮 60 token 的短对话只值 60），
            //     "只丢一组"在容量附近会让 `dropped` 逐轮抖动 —— 每抖一次，KV 前缀
            //     起点就挪一次，那一轮必然全量 prefill。用户看到的是"复用忽然没了"，
            //     而代码里每一处都"按规则办事"。
            //   · 预算的职责是让本轮**一定装得下**（从而 native 一刀不动），不是
            //     "丢得刚刚好"。所以按**保留条数**二分，找能装下的最少保留集。
            // 顺序仍然是"从最老到最新"：保留集恒为原序的后缀（+首条 system），
            // 于是分叉点落在"被保留的第一轮"，装得下的轮次里它不动。
            //
            // 兜底两条：首条 system 与最后一条 user 若自己就装不下，就退化成
            // "只留这轮提问"（首条 system 让位）—— 那比"报错 400 什么都不给"好，
            // 但会丢掉角色设定，所以只在真的没别的办法时发生。
            //
            // ⚠ `tail = 0` 那一档**也是合法结果**（= 只留本轮提问 + 首条 system 让位），
            //   以前 `if (n > 0)` 把它当"没辙了"跳过，于是 kept 仍是全量、下一秒就落进
            //   下面的 `overflow` 报 400 —— 而客户端看到的是一句"约 N tok 塞不进 ctx M"，
            //   明明丢掉老轮次就能答。判据只该看"丢完之后装不装得下"。
            // 每组各自估一次（`monotone=false` 时不假设"留得越少 token 越少"），
            // 判据见 [minimalKeep] 里那段注释。
            // 逐条 token 表只算**一次**：`tokAll` 每调用一次就是一次逐条折算，
            // 而这里要按"哪一条属于哪一档"分组求和（组数 ≤ 几十，消息数 ≤ 几十）。
            val perMsg = tokAll(messages)
            val askIdx = messages.indexOfLast { it.first == "user" }
            val askTok = if (askIdx >= 0) perMsg.getOrElse(askIdx) { 0 } else 0
            val groupToks = groups.sumOf { g -> g.sumOf { perMsg.getOrElse(it) { 0 } } }
            val reserveTok = (perMsg.sum() - askTok - groupToks).coerceAtLeast(0)
            // 「下一轮历史会长多少」（ISSUE #503 第四态：「丢一次」变成「每轮丢一次」）──
            //
            // 落点只减 `gen` 时，落完是 `prompt + gen == half`，而下一轮历史长 `per` 枚：
            // 判据 `prompt + gen > avail − gen` 变成 `half + per > avail − gen` —— 只要
            // `gen > avail/2 − per`（`max_tokens` 比四分之一格大一点就会），**下一轮照样
            // 越线**，落点一个不动、再丢一次。用户读到的就是「每轮丢一点点」。
            //
            // `per` 不用新常数：本轮丢掉的那一组组 `keptOf` 前后差就是**已经发生**的
            // 一轮增量 —— 它比"上一轮的 `droppedTokens`"少一次记账，也比拿常数去猜
            // 更贴这一份历史（每组多大完全由用户怎么聊决定）。
            // `gen` 已被上一跳削到地板时（`forcedOver`，起判线那一路必到），封顶要
            // 按地板算 —— 见 [dropCap] 那段：按已经削过的 `gen` 扣，封顶会落在
            // `avail − ask − reserve − 地板` 附近，每一档保留数都被它挡回去，
            // `minimalKeep` 落到最坏那一档（只留提问、生成只剩 2）。
            // `mustTokens`：这一轮**按同一口径**算出来、装下所必需的丢弃量。封顶只该
            // 限制"多丢"（对半砸到本轮提问上），不许把"装不下"变成"少丢" —— 后者会让
            // 每一轮都少丢一组：越线之后每轮都丢、每轮都还差一大截，KV 前缀每轮断一次
            // （ISSUE #503 真机：`丢最老 18 条 / 复盘 2214 → 2107 → 1962`，prompt 2150 上下
            // 逐轮不动，而水位线是 3481；下一轮 `reuse 200 / prefill 2056`）。
            // 封顶的下限就是它 —— 于是"丢到装得下"永远兑现得了，而"别砸到提问上"仍然成立
            // （`ask` 不在可裁候选里，它与 `reserve` 在两边都扣过）。
            val mustDrop = (tokens + gen - avail).coerceAtLeast(0)
            val cap = dropCap(askTok, reserveTok, gen, avail,
                forcedOver = gen <= KvLongChat.GEN_FLOOR, mustTokens = mustDrop)
            //
            // ⚠ HTTP 那条路走 `shrinkOnly`（见 [Plan] 与 `plan` 的形参说明）：对端每轮都
            //   贴着它自己的 Max Context 送整段历史，我们这一轮丢一半、下一轮它又把
            //   历史全送回来 —— **既丢不了、又多丢**。那条路上真正减半的是 native
            //   `ctxbudget::plan` 的 `toHalf`（它面对的是真 token 数，不在 `planKeep` 里），
            //   所以这里只做水位收紧（丢到装得下）。
            // ⚠ 主动治理关掉时**也不走** [minimalKeep] 的"丢一半"：那一半是
            //   "丢一次稳几十轮"的账，而它的前提是"服务端替客户端管着水位"。客户端
            //   自己裁之后，多丢的那一半是**净损失**（丢掉的每一组都要付一次全量
            //   prefill，而且丢掉历史会让本轮 prompt 变成上一轮的后缀、`canAlign` 自锁）。
            //   所以这两档都只做一件事：丢到**刚好装得下**（同 [Plan] 的 `shrinkOnly`）。
            val fitsTail: (Int) -> Boolean = { tail ->
                tokAll(keptOf(messages, groups, tail)).sum() + gen <= avail
            }
            // 「水位把它顶过来的」（ISSUE #503 真机第三形态）：`fits` 为假可能是**两件
            // 事**之一 —— ① 这一轮真的装不下（缺口是实打实的），② 只是越过了起判线
            // 而 `gen` 已经让到地板（缺口一枚都没有）。两件事的处置相反：前者必须丢
            // 历史，后者丢历史纯属白丢。
            //
            // 判据只有一条：**半水位那一档装得下吗**。装得下就说明"丢一次能收口"，
            // 那一刀就该落；装不下说明这一档怎么丢都救不回来，走下面那条
            // "能丢多少算多少 / 剩给下面几跳"的路。
            val halfTail = halfWatermarkTail(groups, messages, gen, wantGen, avail, roundPer, ::keptEst)
            // ⚠ 落点按 `wantGen`（意愿值）算，复核也按 `wantGen`：这一跳问的是
            //   "**按这一轮的意愿**，落完之后还剩多少位置"，与"这一轮实际给多少"是
            //   两件事（越线那一轮 `gen` 已被让到地板）。用 `gen` 复核时，越线的每一轮
            //   都会判"落得太多"、掉进 `minimalKeep`，那里再按水位线封顶 -> 只留
            //   system + 提问（真机 `丢最老 37 条 / 约 145 tok / 生成上限 1024`）。
            val fitsAt = keptEst(keptOf(messages, groups, halfTail)) + wantGen
            val (n, half) = if (shrinkOnly || !proactiveOn) {
                var found = -1
                for (tail in groups.size downTo 1) if (fitsTail(tail)) { found = tail; break }
                (if (found >= 0) found else 0) to false
            } else if (groups.isNotEmpty() && fitsAt <= avail) {
                // ⚠ 落点**固定**在半水位：那是"丢一次稳几十轮"的前提（见 [minimalKeep]
                //   里「保留组数是跨轮该稳定的量」）。这一支不看 `cap` —— 封顶问的是
                //   "这一轮**最少**得丢多少"，而 `gen` 已在上一跳让到地板，那个数在
                //   "越线但装得下"的轮次上恒为 0，拿它当起点就会一条都不丢
                //   （`dropped == 0`，而 App 侧每句 `[ctx] …` 都以它为前提 —— 日志
                //   一个字没有，看起来就是「主动治理根本没跑」）。
                halfTail to true
            } else {
                minimalKeep(groups, gen, avail, cap) { tail ->
                    tokAll(keptOf(messages, groups, tail))
                }
            }
            // ⚠ **没有可裁候选时不许动构造**：`keptOf(..., 0)` 会把首条 system 一起
            //   让掉（它的下标不在任何一组里，`dropUpTo == groups.size` 那条兜底
            //   就把它判成"已丢"）。而这一档的现场是「system + 一条本轮提问」这样的
            //   **单轮请求** —— 它没有历史可丢，正确处置是削 gen（上面已经做了）。
            //   混淆的后果是"角色设定被静默删掉"，而读数只是 `dropped: 1`
            //   （看起来像丢了一轮历史，用户读到的是"它怎么不按设定说话了"）。
            if (groups.isNotEmpty()) {
                val cand = keptOf(messages, groups, n)
                kept = cand
                dropped = messages.size - cand.size
                // ⚠ `tokens` 必须按 **`gen` 让位之后**的口径重算一次（`keptEst`，不是 `tok`）：
                //   `tok` 里含着"生成后缀"那一笔，它是**按意愿值估的**；`gen` 已经让到地板时
                //   再算进去，同一个保留集会在两处判据里差出 `wantGen - GEN_FLOOR` 枚 ——
                //   真机 `ctx 4096 / max_tokens 1024 / prompt 1525` 那一轮差出 960 枚，
                //   于是 `dropped == 0` 而 `tokens + gen` 凭空越线，下一句又把它补齐：
                //   `[ctx] 本轮没丢（1525 tok 全进，生成上限 64）`。
                tokens = keptEst(cand)
                lostToWatermark = half && dropped > 0
                // 这一支丢完之后才有"一轮涨多少"可量 —— 下面几跳的落点要用它。
                refreshRoundPer()
            } else {
                // ⚠ 没有可裁候选（"system + 一条提问"的单轮请求）却仍要削 960 枚生成空间
                //   的那一档：此处的 `gen` 已经让到地板，而 `dropCap` 按地板算出的封顶
                //   通常大于缺口 —— 也就是说这一轮其实**装得下**（ISSUE #503）。
                //   把 `gen` 补回"扣掉水位之后还剩多少"，再走下面那条统一的判据：
                //   装得下就一枚不削，装不下自然归零走 400。
                val roomNow = avail - tokens
                if (roomNow >= KvLongChat.GEN_FLOOR && roomNow > gen) gen = minOf(wantGen, roomNow)
            }
        } else {
            // ⚠ 削 gen 之后（哪怕只削到"刚好装下"）也要**重算一次总量**。
            //   上面那一跳算 `tokens` 用的是**削之前**的 `avail - ...`（`tok` 的量法
            //   与 `fits` 判据同一套，但 `fits` 里比的是 `tokens + gen`），而 `gen`
            //   在这一跳可能已经从 8 被削到 7 —— 不重算的话后面每个判据都拿着旧的
            //   `gen` 去比，于是"只多 5 枚"被当成"还装不下"，一路走成丢历史。
            //   真机形态：单轮提问 + system 正好顶在水位上，`dropped: 1` 而丢掉的
            //   是**角色设定**那条 system（没有任何可裁候选时 `keptOf(..., 0)` 仍会
            //   把它的保位标志算成"已丢"）—— 用户看到的是"它怎么不按设定说话了"。
            //   同样用 `keptEst`：这一跳的用途正是"拿削过的 `gen` 复核缺口"。
            tokens = keptEst(kept)
        }

        // ── 还差几枚：**当轮就让到位**，而不是攒着去报 400（ISSUE #426 真机）────────
        //
        // ⚠ 这一段是「多轮对话越聊越长、之后每一轮都 400」的根因。上面两跳都带下限：
        //   ① 让 gen 到 [KvLongChat.GEN_FLOOR]、② 丢整组消息 —— 而它们**未必同时兑现**：
        //   - 走的全是 system / 本轮提问（都不是"可裁候选"），`minimalKeep` 返回 0，
        //     `kept` 一条都没少 —— 剩下的几枚缺口没有任何东西去接；
        //   - 让 gen 那一跳的下限是"`room >= GEN_FLOOR` 才能让到剩余空间"，
        //     否则目标值会在 `GEN_FLOOR` 与剩余空间之间来回钳，同样留一截缺口。
        //   旧判据只看 `tokens + gen > avail`，于是**剩下的往往只有几枚**也照样 400，
        //   而文案是"约 4100 tok 塞不进 ctx 4096（要给生成留 2 tok）"
        //   —— 用户看到的数字差得很小，小到他会以为是自己设错了 ctx。
        //
        //   让的次序在这里是"先削生成长度、再动消息"的**延续**：
        //   ① `gen` 让到 [MIN_GEN_TOKENS]（它只是上限，让多少都不影响前缀连续性）；
        //   ② 还不够（system + 本轮提问本身就超了）才丢**历史**；历史都丢光了
        //      仍然超 → 只留本轮提问（首条 system 让位：丢掉提问是答非所问，
        //      丢掉设定只是换个语气）。
        //   三跳都碰到底才判 `overflow` —— 它的含义从此只有一个：
        //   **单条本轮提问本身就塞不进这一格**。
        //
        // ⚠ 水位之后这里多了一层：上面那一跳用的 `avail` 是**水位线**，而水位是
        //   目标不是硬墙。历史丢光了仍装不下时，剩下的几枚按**整格**（`availHard`）
        //   让 —— 否则 `ctx=4096 + 水位 85%` 会把一条 3500 tok 的提问判成装不下，
        //   而它明明进得去。让的次序不变（先 gen、再提示本身）。
        // ── 丢完之后把保留集修成一条合法对话（ISSUE #426 方案 C-1）────────────────
        // 只删不加：首条 system 之后紧跟的 assistant 是"提问已经不在了"的残句，
        // 留着它模型看到的形状与训练时完全不同（表现为开始胡说，而角色序列肉眼正常）。
        //
        // ⚠ 这里**不许**顺手往 prompt 里塞任何东西（ISSUE #452 收口）：
        //   以前是那句 `[对话续接]`，挂 user 会让上一轮起点作废、挂 system 会让
        //   下一轮"客户端送回来的 system"与我们上一轮送的那条不同 —— 两条路的后果
        //   都是**每丢一次就多烧一整轮全量 prefill**，甚至保留集每轮换一个起点。
        //   "这一轮没有上下文"由 `local_ctx.ask_only` 说给客户端（见 [Plan.keptAskOnly]）。
        //   既然不新增内容，`tokens` 也就不必重算 —— 删只会让估算变小。
        if (dropped > 0) {
            val systemDropped = keepFirstIdx == 0 && kept.none { it.first == "system" }
            kept = resumeShape(kept, noSystemForce = systemDropped)
        }
        // 「还差几枚」= 判据（`tokens + gen > avail`）与主动治理的起判线（`overTrigger`）
        // 合成的那一条。⚠ 它们必须**一起**算在这里：只判前者时，起判线推进来的那一轮
        // 会让 `gen` 一直停在地板（缺口已经补完、但"该动手"仍成立），于是一次"越过
        // 2457"除了一次扔十几条历史，还白削 960 枚生成空间 —— 这正是 ISSUE #488
        // 在真机上表现为"触发一次水位，回答只剩几个字"的那一半。
        // 起判线那一半用「`gen` 还在地板以上」判：钳过之后 `gen` 未必等于意愿值
        // （小容量那一档开头就按整格钳过），拿意愿值判会漏掉那些轮次。
        // 落值是 `gen − 地板`：越线只该丢历史，不该白削这一轮的回答。
        // ⚠ 起判线那一半**只记账，不削 gen**（ISSUE #503）：`gen` 只是"愿意接受多长"，
        //   削它对前缀连续性零收益，而它削掉的每一枚都是用户直接看得见的回答。真机
        //   `[聊天] 生成长度 64（请求 1024，预算削 960）` 就是这里顺手削的 ——
        //   而那一轮 `tokens + gen` 离水位线还有上千枚。越线该做的事只有一件：
        //   丢历史（见下面"越线本身也是一条理由"那一段）。
        if (tokens + gen > avail) {
            val overGen = tokens + gen - avail
            val give = minOf(overGen, (gen - MIN_GEN_TOKENS).coerceAtLeast(0))
            gen -= give
            if (give > 0) genTrimmed += give
        }
        // ⚠ `groups.isEmpty()` 那一档**不许走这里**：没有可裁候选时
        //   `keptOf(..., 0)` 仍然把那条 system 的保位标志算成"已丢"（它的下标不在
        //   任何一组里），于是 `cand` 与 `messages` 只差一条 system —— 而 `dropped`
        //   变成 1、`gen` 已经在上一段被削到地板之下。真机上那一档是
        //   "一条单轮提问正好顶到水位"（`prompt + gen` 只多几枚），此时的正确处置是
        //   **削 gen**（已经做了），不是"丢掉历史里唯一那条 system" ——
        //   后者会把角色设定删掉，而读数是 `dropped: 1`（看起来像"丢了一轮历史"）。
        //
        // ── 越线本身也是一条理由（ISSUE #503）─────────────────────────────────
        // "装不下"与"越过水位"是两件事：前者这一轮**必须**丢（否则 native 会从头部
        // 砍），后者是主动治理**想**丢一次换几十轮安稳。以前这条判据被并进了 `fits`，
        // 于是 `overTrigger` 为真时判据链末端那几跳全按"装不下"走 —— 而它们只看
        // `tokens + gen`，那个数离水位线还有上千枚，于是**一枚都没丢**：
        //   `[ctx] 越过水位 85% —— 丢最老 12 条消息 / [s0][ctx] 本轮没丢（1525 tok 全进）`
        // 现在"越线"与"装不下"在这里并成同一条门（`tokens + gen > avail || overTrigger`）：
        // 只要还有可裁的历史，越线就照样走进"丢到半水位"那一档。`gen` 一枚都不削 ——
        // 它不是这一刀的对象（见上面"起判线那一半只记账"那一段）。
        //
        // ⚠ 这一跳必须**先于**下面那条"越线就丢到半水位"的兜底发生，且只在
        //   "真装不下"时把历史全丢掉（越线那一档**不许**走到这里 —— 它的落点是半水位）。
        //   所以判据仍然是 `tokens + gen > avail`，越线由下面 `else if` 单独接。
        // ⚠ 这一跳的现场只有**真装不下**（`tokens + gen > avail`）。
        //   越线（`overTrigger`）那一档不许走到这里：它的落点是半水位（下面那一跳），
        //   而"历史全丢"是最后手段。真机首轮 `丢最老 37 条 / 约 145 tok` 就是它 ——
        //   那一轮 `kept + gen` 离水位线还有两千多枚，一枚都不该丢。
        if (tokens + gen > avail && !overTrigger && dropped == 0 && groups.isNotEmpty()) {
            // 一跳都没丢过：把**历史**（可裁候选）全丢，看能不能装下。
            // ⚠ 这一跳是"**试一试**把历史全丢能不能装下"：用 [keptOf] 的默认口径
            //   （`tail = 0` 连 system 一起放上砧板）。装得下就用它，装不下就留给
            //   下面那一跳 —— 那里会按**整格**再让一次，并且**保住 system**。
            //   这两跳的分工是"谁先让步"：先试最省的（system 也让），真到要用时
            //   才用那条保设定的；反过来会让"本来 system 也让得动"的轮次白丢设定。
            val cand = keptOf(messages, groups, 0)
            val candTok = tok(cand)
            // ⚠ 门是 `availHard`（**整格**），不是 `avail`（水位线）：这一跳问的是
            //   "**丢掉历史之后能不能答**"，而"能答"只能由整格决定。拿水位线当门，
            //   一个 3400 tok 的 prompt 落在 4096 里（还剩几百枚给生成）也算"装不下"，
            //   于是把 system 一起让掉 —— 真机 `dropped: 1` 丢掉的就是角色设定那条 system
            //   （读数看起来像"丢了一轮历史"）。
            if (candTok + gen <= availHard) {
                kept = cand
                dropped = messages.size - cand.size
                tokens = candTok
                refreshRoundPer()
            }
        }
        // ── 缺口就到此为止：**从当前保留集里一组一组地丢到装得下**（ISSUE #503）──
        //
        // 上面每一跳都带一个"下限"：`gen` 只让到地板、消息只按"整组"或"对半"丢。
        // 三者**未必同时兑现**，于是真机上出现过这样一个死结：
        //   `tokens 3404 / gen 2 / avail 3481` —— 按「水位线 − 生成量」算缺口为 0，
        //   可 `fits` 只认"删掉一整组"，一路判否、一路往最坏那一档走，`gen` 再也
        //   不回升。用户看到的就是「第一次直接挤满，然后生成 2，之后一直生成 2」。
        //
        // 这一跳的作用是**把丢历史的量按 token 收口**：保住首条 system 与本轮提问，
        // 其余整组从最老的开始丢，每丢一组复核一次，装得下就停。
        // 与 [minimalKeep] 的"丢一半"分工不同 —— 走到这里说明减半那一刀没兑现
        // （封顶挡了 / `fits` 不是单调的 / `gen` 已经让到地板），此时再多丢就是白丢。
        //
        // ⚠ 它**不**替 `overflow` 兜底：一组都丢光了仍装不下，照旧落到下面那几跳
        //   （先削 gen、再只留提问、最后 400）。判据只有"装得下"这一条 ——
        //   谁先让步（丢历史还是削 gen）由上面那几跳决定，这里只管"够不够"。
        // ⚠ `gen` 被让到地板以下、而这一轮其实装得下（按 token 没缺口）的那一档，
        //   这里一枚历史都不该丢 —— 它要的是**把 gen 补回来**（见下面那一跳）。
        // ⚠ 它的门是**落点**，不是水位线（ISSUE #503 第四态）：上面刚落到的
        //   `target = 半水位 − 意愿值 − 余量` 比水位线**低一整条**，所以"落对了"的
        //   轮次在 `tokens + gen > avail` 这条门下会被再砍一遍 —— 一路砍到
        //   `tail = 0`（连首条 system 一起让位）。真机 `max_tokens 2048` 那一档：
        //   `gen` 吃掉大半格之后落点只剩几十枚 prompt，每一轮历史都丢光。
        //   改成比 `target` 之后，落对了就一枚不再丢；真的连落点都装不下（`gen`
        //   已被钳到地板仍超）才继续往下让，让到"落点装得下"为止。
        // ⚠ `settledFloor` 是**落点合计**（`prompt + gen` 该落在哪），[halfWatermarkTail]
        //   已经按 `− wantGen` 落过一次。这里照旧按 `wantGen` 取 —— 它是"落完之后还剩
        //   多少位置"的**目标**，不是"这一轮装不装得下"；后者由 `avail` 判（下一个条件）。
        //   拿 `gen`（可能已被让到地板）去改这个目标，会让"落点"跟着 `gen` 漂：
        //   `gen 2` 时目标变成 718，于是 2422 的合计被判"没落到位"、一路砍到只剩提问。
        val settledFloor = if (dropped > 0) {
            halfWatermarkTarget(avail, roundPer, wantGen) - wantGen
        } else avail
        // ⚠ 只有**主动治理**那一档才按落点继续收（`proactiveOn && !shrinkOnly`）：
        //   `shrinkOnly` / 主动治理关掉的那两档刚刚才按 `fitsTail` 丢到"刚好装得下"
        //   （见上面 `(n, half)` 那一支），它们与用户签的约就是"只丢到装得下"。
        //   再按半水位落点收一遍，等于把"刚好装得下"的历史又砍掉大半 —— 真机
        //   `shrinkOnly` 最坏那一档（gen=512）：三组都装得下，却被落点线一路砍到
        //   只剩一条 assistant（`[system, user, assistant, user]`），而它本该留下
        //   三对。落点是"丢一次管几十轮"的账，前提是服务端替客户端管着水位；
        //   这两档没有那个前提，多丢的每一组都是净损失（对端下一轮又会全送回来）。
        if (proactiveOn && !shrinkOnly && tokens + gen > settledFloor && groups.isNotEmpty()) {
            // ⚠ 扫描**不降到 0**（`downTo 1`）：[halfWatermarkTail] 已经定过一条规矩 ——
            //   连一组都放不进落点时，宁可越过落点一组，也要保住「设定 + 一轮对话」。
            //   这里降到 0 等于把那条规矩又推翻一次，而且推翻得毫无声息：`keptOf(…, 0)`
            //   连首条 system 一起让位，`dropped` 只是从 14 涨到 16。真机形态正是这一格
            //   （`max_tokens 1024` 时落点 prompt 只剩约 508 枚，而一组 300 字约 500 枚）：
            //   `halfWatermarkTail` 按"保一组"落到了 1 组，紧接着这一跳按 `gen 2` 的
            //   合计（`tokens + 2 > 508`）判"没落到位"，一路砍到只剩提问。
            //   `gen` 是**让位后的临时值**（越线那一轮必被削到 2/64），拿它当落点合计的
            //   量具，落点就随 `gen` 漂 —— 这正是上面那条注释警告过的事。
            for (keepGroups in (groups.size - 1) downTo 1) {
                // 保住首条 system（它在 `groups` 之外）与本轮提问，其余按整组丢。
                val gone = hashSetOf<Int>()
                for (gi in 0 until (groups.size - keepGroups)) gone.addAll(groups[gi])
                val cand = messages.filterIndexed { i, _ -> !gone.contains(i) }
                if (cand.size >= kept.size) continue      // 没比现在省 -> 不必往下试
                val candTok = tok(cand)
                // ⚠ 门槛是**落点**与**装得下**取严（ISSUE #503 第四态）：只按落点判会把
                //   已经装得下的那一档继续往下砍；只按 `avail` 判则落点白算 ——
                //   真机 `gen 1024 / prompt 1398` 那一屏，合计 2422 明明装得下（水位线
                //   3481），却因为"比落点 1740 还高"被一路砍到只剩 system + 提问。
                if (candTok + gen <= settledFloor && candTok + gen <= avail) {
                    kept = cand
                    dropped = messages.size - cand.size
                    tokens = candTok
                    break
                }
            }
        }
        // ── 越线了却一枚都没丢：**落到半水位**（ISSUE #503）─────────────────────
        //
        // 走到这里说明 `overTrigger` 为真、而上面按"装不下"判的那几跳全都没命中
        // （`tokens + gen` 其实还在水位线以内）。这就是真机上的那一屏：
        //   `[ctx] 越过水位 85% —— 丢最老 12 条消息，约 2059 tok ≤ 4096×85%，生成上限 64`
        //   `[s0][ctx] 本轮没丢（1525 tok 全进，生成上限 64）`
        // 两句话互相打脸：**上一句说丢了，这一句说没丢**。根因不是估算，而是
        // `keptOf` 只按**条数**落值 —— 上面每一跳都把 `n` 算成 `messages.size`
        // （= 一条都不丢）就算"装得下"，`dropped` 于是恒 0，而 `overTrigger` 一夜之间
        // 变成"只削 gen、不丢历史"的那条路。
        //
        // 所以这一跳单独兑现"越线丢一次"：**按条数对半**（`minimalKeep` 与它同一条
        // 口径），并要求丢完之后确实还在水位线以内 —— 兑现不了就不冒充"丢过"
        // （退回不丢，观感与改动前一致，不会凭空丢一半历史）。
        if (overTrigger && dropped == 0 && groups.isNotEmpty()) {
            // ⚠ 落点与上面那一支**同一个函数**：按组数对半只是起点，真正的落点是
            //   半水位那条 token 线（见 [halfWatermarkTail]）。两处各写一份时，
            //   "越线那一支按 token、兜底这一支按组数"会让同一条路有两个落点。
            val halfTail = halfWatermarkTail(groups, messages, gen, wantGen, avail, roundPer, ::keptEst)
            val selfCand = keptOf(messages, groups, halfTail)
            if (selfCand.size < messages.size) {
                // ⚠ 复核口径与 [halfWatermarkTail] 找落点时**同一个**（`keptEst`）：
                //   找的时候按 `keptEst` 说"这一档落在半水位以内"，复核却拿 `tok` 比
                //   `avail` —— 两个口径差着"生成后缀按意愿值还是按落值"那一笔
                //   （`wantGen − gen`，真机 960 枚）。差出来时这一档被判"装不下"，
                //   于是越线那一轮**一枚都不丢**，读数却是 `crossedWatermark` 为假、
                //   日志一个字不印，看起来像"主动治理根本没跑"。
                val selfTok = keptEst(selfCand)
                if (selfTok + gen <= avail) {
                    kept = selfCand
                    dropped = messages.size - selfCand.size
                    tokens = selfTok
                    lostToWatermark = true
                }
            }
        }
        // ⚠ 这一跳也有"没有可裁候选"的问题：一个**只有 system + 一条提问**的请求
        //   没有"历史"可丢，这里却会把 system 让掉（"丢掉设定只是换个语气"那条
        //   理由在**有多轮历史**时成立，在单轮请求里它等于"无故改人设"）。
        //   判据加 `groups.isNotEmpty()`：真正需要这一跳的是"历史丢光了仍装不下"，
        //   而没有候选就说明本条路上根本没有历史可丢。
        if (tokens + gen > avail && groups.isNotEmpty()) {
            // 历史丢光了还装不下：只留本轮提问（= 最后一条 user 及其后端）。
            // ⚠ 这一跳按**整格**（`availHard`）判，不按水位：水位是"平时跑在哪"，
            //   而"这一轮能不能答"只能由整格决定 —— 拿水位当硬墙就等于"水位设得低
            //   一些就多拒掉一批本来合法的请求"，而那句话用户听不懂，也改不动。
            // ⚠ **首条 system 留在这里**：这一跳的现场是
            //   "丢光历史仍越过水位"，而水位是**目标**不是硬墙 —— 越过它的那几枚
            //   下面还有一档按整格让。这里若把它一并丢掉，最坏那一档就成了
            //   「一条提问 + 无设定」（读数 `dropped` 与"正常丢了几轮历史"逐字相同），
            //   而真正"连提问都装不下"的判据在再下面一跳 —— 那里才该报 `overflow`。
            val lastUser = messages.indexOfLast { it.first == "user" }
            if (lastUser >= 0 && !(lastUser == 0 && messages.size == 1)) {
                val onlyAsk = messages.subList(lastUser, messages.size).toList()
                val sysHead = messages.firstOrNull()?.takeIf { it.first == "system" && lastUser > 0 }
                val trimmedSet = if (sysHead != null) listOf(sysHead) + onlyAsk else onlyAsk
                val askTok = tok(trimmedSet)
                if (askTok + gen <= availHard) {
                    kept = trimmedSet
                    dropped = messages.size - trimmedSet.size
                    tokens = askTok
                }
            }
        }
        // ⚠ 一组都留不下时 `keptOf(…, 0)` 会把首条 system 一起让掉。
        //   那一刀的前提是"连 system + 提问都装不下"；而只要它俩装得下，正确的落点
        //   是「设定 + 提问」两条（ISSUE #503 最坏那一档，shrinkOnly 与非主动治理
        //   同样适用）。下面「历史丢光了还装不下」那一跳要求 `tokens + gen > avail`，
        //   而只剩提问往往已经装得下 —— 于是那一跳永远轮不到，system 就被静默丢掉了
        //   （读数只是 `dropped: 1`，看起来像"丢了一轮历史"）。
        //   这里在拿到 `kept` 之后补一次，与那一跳同一个处置、同一条判据。
        //   判据是**结构**（保留集里没有首条 system），不依赖 `n` —— 它可能来自
        //   几跳里的任何一跳（`minimalKeep` / 落点兜底 / `shrinkOnly`）。
        if (groups.isNotEmpty() && kept.isNotEmpty() &&
            messages.firstOrNull()?.first == "system" && kept.first().first != "system") {
            val withSys = listOf(messages.first()) + kept
            val withSysTok = tok(withSys)
            if (withSysTok + gen <= avail) {
                kept = withSys
                dropped = messages.size - withSys.size
                tokens = withSysTok
            }
        }
        // ── 缺口补完了：把让位时削掉的 `gen` 按剩余空间补回来（ISSUE #503 真机）──────
        //
        // 上面把 `gen` 让到地板、再丢历史，那是**手段**（先把上限让掉、再丢），
        // 不是结论。一旦历史已经丢到装得下，`gen` 就该回到"这一轮还剩多少位置"：
        // `gen` 停在地板以下、而 `avail - tokens` 明明还有几百上千枚，唯一的下场就是
        // 用户看到的那句「之后一直生成 2」—— 下一轮历史照样装不下，`gen` 又落到 2。
        //
        // ⚠ 这一跳以前**只写在 `groups.isEmpty()` 那一支里**（单轮请求），于是
        //   有历史的会话恰恰补不回来。真机链条是这么闭环的：
        //     ① 让位那一跳按 `min(gen, avail − tokens)` 落值 —— 当缺口大到一个地板
        //        都留不下时，`gen` 直接落到 [MIN_GEN_TOKENS]（2）；
        //     ② 丢历史那一跳（`minimalKeep` 的"对半"）按 `gen` 判 `fits`，而
        //        `gen = 2` 时它几乎必然判否 —— 于是再丢一次、`gen` 仍是 2；
        //     ③ 下一轮历史又涨回来，①~② 重演。用户读到的就是
        //        「第一次直接挤满，然后生成 2，之后一直生成 2」。
        //   而真机上那一刻 `spare` 还剩三千多 —— 缺口根本不是"没位置"，是"位置
        //   被谁吃掉了"：让位那一跳把**上限**让给了水位线，之后就没人还回来。
        //
        // ⚠ 方向恒为"补回"：只往上、且上界是调用方意愿值（`wantGen`）与剩余空间，
        //   一倍都不许放大。
        // ⚠ 只在**剩余空间不低于地板**时补：连地板都放不下的那一档，补上去也立刻
        //   会被下一步削回来，反而把 `gen_floor_missed` 搅浑（那一档确实注定很短）。
        // ⚠ 补回的门槛**不是**「`gen` 掉到地板以下」（ISSUE #503 真机第四轮）：
        //   上面每一跳都会把 `gen` 削到**地板**（64），而地板并不小于地板 ——
        //   于是这一跳永远不触发，`gen` 就停在 64，哪怕 `avail - tokens` 还有一千多枚。
        //   真机读数连着两轮：`[聊天] 生成长度 64（请求 1024，预算削 960）`、
        //   而同一轮的 `[s0][ctx] 本轮没丢（1525 tok 全进）` —— prompt 才 1525，
        //   `avail` 3481，`gen` 完全该是 1024。
        //   判据改回它的本意：**这一轮还剩多少位置就给多少**（上界是意愿值），
        //   条件是剩余空间够地板。`gen` 已经等于 `min(wantGen, roomNow)` 时这一跳什么都不做。
        //
        // ⚠ 补回**要有上界**，而且那个上界不是 `avail`（ISSUE #503 真机第十九回合）──
        //   这是"丢了一次、下一轮又丢"剩下的那一半。真机实测链条（`ctx 4096 / 水位 85 /
        //   max_tokens 1024`，历史 13 轮）：
        //     ① 越线那一轮丢到落点线（`prompt + gen` ≈ 1392），历史只剩 7 轮；
        //     ② 紧接着这一跳把 `gen` 补回 **1024** —— 合计 1392 变成 **2416**；
        //     ③ 下一轮历史涨一轮（约 130 tok）→ 合计 2546，而**起判线是 2457** —— 又越线。
        //   `[ctx] 丢最老 12 条` → 下一轮 `丢最老 14 条` 就是这么来的：落点没错，
        //   是落完立刻被这次补回填回去了 —— 落点腾出来的位置白腾。
        //   所以上界取 `target − tokens`：补完之后 `prompt + gen` 仍然落在落点线上。
        //   丢了历史那一轮，`gen` 不欠用户的（丢历史不是"少说话"的理由），但它也不该
        //   把历史腾出来的位置整段吃掉 —— 那正是把"丢一次"变成"每轮丢"的那只手。
        //   ⚠ 没丢过历史（`dropped == 0`）那一轮照旧用 `avail`：那是**装不下**让的位，
        //     它本来就该把这一轮的位置用满（真机 `本轮没丢（1525 tok 全进，生成上限 64）`
        //     那一屏就是它没补回来 —— 那个 bug 不要拿这次的上界再犯一遍）。
        //
        // ⚠ 落点线减去 prompt 之后**不够地板**的那一档不能"因此不补"：不补的话
        //   `gen` 停在被让位那一跳削到的地板以下（甚至 [MIN_GEN_TOKENS]），而这一轮
        //   其实还剩几十枚位置 —— 于是又是「之后一直生成 2」。补到地板、并允许越过
        //   落点线那一截（只越过 64 枚，离水位线还有一大截）：
        //     · 上界 `target − tokens` 够地板 -> 就补到那儿（合计正好落在落点线）；
        //     · 不够地板 -> 补到地板（这一轮的合计落在 `half + (64 − 44)` ≈ 1760）。
        //   两条都满足这次的目的：**别把历史腾出来的位置整段吃掉**。
        run {
            // ⚠ 上界就是落点合计（`target`），**不再额外减一次 `gen`**：
            //   [halfWatermarkTarget] 给的是 `prompt + gen` 的目标，落点那笔 `− gen`
            //   在 [halfWatermarkTail] 里减过。这里再减一次 = 拿落值当上限去补，
            //   补一次、下一轮落点更低、再补更低 —— `gen` 会一轮一轮自我压缩
            //   （按模型扫过：1024 → 807 → 303 → 51）。
            //   取 `target − tokens`：补回来的 `gen` 与落点同源，涨一轮 `per`
            //   刚好摸到起判线（不等号取严，不进）。
            //
            // ⚠ `ceiling` 必须与 [halfWatermarkTail] 的 `target` **同一个式子**：
            //   落点说"合计落在 `target − wantGen`"，补回说"`gen` 最多补到同一个
            //   合计"，两处同源才不会出现"补回又把落点填回去、下一轮再丢"。
            //   ⚠ 以前两处都多写了 `+ per`（ISSUE #586）：`per` 是"最老那一组多大"，
            //     而落点要回答的是"这一轮该落在哪"—— 加上它之后落点随老消息篇幅漂，
            //     历史再多丢一整组，然后补回又把这个漂过的目标原样补回去。
            //     现在两处都是同一个函数，谁也不再加 `per`。
            val ceiling = if (dropped > 0) {
                halfWatermarkTarget(avail, roundPer, wantGen) - tokens
            } else avail - tokens
            val roomNow = minOf(avail - tokens, maxOf(ceiling, KvLongChat.GEN_FLOOR))
            if (roomNow >= KvLongChat.GEN_FLOOR) {
                val back = minOf(wantGen, roomNow)
                if (back > gen) {
                    gen = back
                    // 补回来的不解成"没削过"：`genTrimmed` 记的是这一轮**净**削了多少。
                    // 净削 = `意愿值 − 落值`（与 `Plan.genTrimmed` 的语义一致）。
                    genTrimmed = wantGen - gen
                }
            }
        }
        // ── `gen` 的落值不再按落点收紧（ISSUE #503 第四态）──────────────────────
        //
        // 这里曾经想加一跳「把 `gen` 钳到落点」，理由是"用户可以把它设得比半格还大，
        // 于是一刀落到 1372 枚的 prompt 立刻被 2048 枚 `gen` 吃回去"。自检时扫了一遍
        // 才看清**这一跳不该存在**：
        //
        //   · `target` 是**落完那一刻**的合计目标，不是此后每一轮的合法上限。下一轮历史
        //     长 `per`，合法合计本来就该涨到 `target + per`；
        //   · 每轮都拿 `target − tokens` 钳，等于每轮重算一遍落点 —— 历史长一截、`gen`
        //     被压低一截，最后钉在地板上再也不回升（1024 → 807 → 303 → 64，
        //     `ctx 4096 / max_tokens 1024 / 15 轮` 就是这么塌下去的）；
        //   · "落完被吃回去"这件事**已经由落点那一刀处理**：`target = min(half, avail − per)`
        //     里的 `per` 就是留给下一轮的空间，落点在减掉 `gen` 之后才拿去做上限。
        //     也就是说 `prompt + gen ≤ target` 是那一刀的承诺，而"下一轮不用再丢"
        //     由 `target ≤ avail − per` 保证 —— 两者都不需要出口这里再钳一次。
        //
        // 结论：这一跳留白。真要防"`max_tokens` 大于整格"，上面 [avail] 那一段开头的
        // `gen = min(gen, posCap − 1)` 已经钳过；剩下的越界由下面 `availForVerdict` 接。

        // 到这一步已经是"只留本轮提问"：判据换成整格 —— 水位不该让一条合法提问被拒。
        val availForVerdict = if (tokens + gen > avail) availHard else avail
        if (tokens + gen > availForVerdict) {
            // 最后一步：连"只留提问"都塞不进去 —— 那才是真的装不下（调用方回 400）。
            // 报之前**先把生成上限让到位**：`max_tokens` 是调用方的意愿，不是保证。
            // 不让的话，同一个请求会既装不下又报一个比整格还大的 gen —— 两个错叠在
            // 一起，用户按"调小 max_tokens"改反而蒙对，按 ctx 改却没用。
            gen = minOf(gen, availForVerdict.coerceAtLeast(MIN_GEN_TOKENS))
            // 这一档**一条都发不出去**（400），所以报出去的是原样那一份
            // （`keptEst(messages)`）而不是已经丢到只剩提问的那个 `tokens` ——
            // 后者会让 400 的文案里那个"约 N tok"比用户真正送的少一大截。
            val allEst = reportedEst(messages)
            // ⚠ `dropped` 用这里的局部值（最终保留集与原件差了几条），不是
            //   `messages.size`：这一档的 `dropped` 在读数上要回答"差几条装下"，
            //   而 `keptFrom` 与它无关 —— 后者恒为"全丢"。
            return overflowAllDropped(messages, allEst, gen, ctx,
                dropped = dropped, watermarkPct = wmPct, posCap = posCap,
                estimateScaleX1000 = scaleX1000, trimmedByCaller = callerTrimmed)
        }
        // 「这一轮是水位把它顶过来的」= 这一轮丢过历史、且丢完之后的长度**回到了水位以内**
        // （丢一半的自然结果）。它和 `droppedToWatermark` 不重复：连续几轮都在水位线上时，
        // 只有第一轮 `dropped > 0`，后面几轮的 `crossedWatermark` 为 false —— 那正是
        // "丢一次、之后几十轮不动"这件事在读数上的样子。
        // ⚠ 仍按**水位线**判（`availAt`），即使治理关掉、判据线是整格：
        //   这一格回答的是"我是不是刚好越过水位"，客户端要靠它决定要不要调小
        //   Max Context —— 换成整格判会让它在"早就越过水位"的那几轮全报 false。
        val crossed = dropped > 0 && tokens + gen <= availAt
        // 「只剩本轮提问」= 历史丢光了：保留集里一条 `assistant` 都没有（除 system 外
        // 只剩本轮提问那一条）。它回答的是一个**对端看不见**的处境（模型这一轮没有
        // 上下文可言），而 `dropped` 只说"丢了几条"——两条与四十条在读数上一样。
        //
        // ⚠ 判据是 `none { assistant }`，不是"除 system 外只剩一条"：后者在
        //   保留 `system + user + assistant`（一轮完整对话）时也算成立，于是
        //   `ask_only` 会被报在一个**有上下文**的轮次上 —— 客户端照着它提示用户
        //   "这轮没上下文"，而模型其实读得到上一轮。这是 ISSUE #452 里
        //   "ask_only 是唯一出口"之后必须收紧的那一格。
        // ── 「这一轮从第几条起发」= 保留集第一条在**原列表**里的下标 ──────────────
        //
        // 调用方（App 内聊天）拿它当下一轮的起点：下一轮的 prompt 以**上一轮真喂进去
        // 的那一段**开头，`lcp` 才可能长、KV 前缀才接得上。所以这个数只能由**这里**
        // 说一次 —— 见 [Plan.keptFrom] 上"别在旁边再算一遍"那段。
        //
        // 定位法只有一句：**从尾部向前逐条对齐**，停下时 `i + 1` 就是"保留集第一条在
        // `messages` 里的下标"。三种情形都落在这一句上：
        //   · 一条都没丢 → 对齐走到 `i = -1` → `0`；
        //   · 丢了几条   → 对齐断在中间，`i + 1` 是保留的第一条（`kept[j]`）的位置；
        //   · 头部残句被 `resumeShape` 删掉 → 对齐停在 system 那一位，`i + 1` 仍然是
        //     保留的第一条历史的位置（被删掉的残句不算进起点）。
        //
        // ⚠ 别改用 `messages.size - kept.size`：`resumeShape` 删掉头部残句之后
        //   `kept` 比"那一段后缀"短，减法会把起点算小 —— 而算小的方向是"多喂一条"，
        //   喂进去的那条**上一轮根本没进 KV**，错位、不报错、只答非所问。
        //   这是本字段存在的全部理由。
        // ⚠ 也别用 `i - j`：`j` 前面那些是 `kept` **自己的** system，不是被丢的历史。
        if (kept.isNotEmpty()) {
            var i = messages.size - 1
            var j = kept.size - 1
            while (i >= 0 && j >= 0 && messages[i] == kept[j]) { i--; j-- }
            keptFrom = (i + 1).coerceIn(0, messages.size)
        }
        val keptAskOnly = dropped > 0 && kept.none { it.first == "assistant" }
        // 「送出去多少」= 保留集那一份的估算（`kept`，不是 `messages`）：
        // 两个数成对读才有意义 —— `local_ctx.sent / dropped` 与 `/health` 的
        // `prompt_est / prompt_real` 描述的都是**这一轮真正交给 prefill 的那一段**。
        // 以前 `sent` 报的是丢之前的全量，而 `kept` 来自 native（已经是 `kept` 这一份），
        // 于是 `dropped = sent - kept` 里混进了"我们丢掉的历史"，与"估算偏高"同形。
        val keptEstimate = reportedEst(kept)
        // ── 报出去的那个数必须与"这一轮真的发出去的估算"同源（ISSUE #503）──
        //
        // `tokens` 是**判据**的口径（`tok` / `keptEst`，含"按意愿值估的生成后缀"），
        // 它随每一跳的目的变；而 `Plan.promptTokens` 只描述**这一份 prompt 本身**。
        // 两个数各写一遍，真机上就出现同一轮里 "约 2059 tok（发出去这一段）" 与
        // native 数出的 `1525 tok` 差 534 枚 —— 用户读到的是"裁剪量算错了"。
        // 所以出口这里统一落 `keptEstimate`：它就是 native 会数到的那一段的估算。
        // ⚠ 若把 `tokens` 直接报出去，`droppedTokens`（= kept − 判据口径）也会跟着长，
        //   而那一格答的是"估算 vs 真值"，不是"判据口径差"。
        // ⚠ 前六位之外**一格也不许退回位置实参**：这一行曾经是
        //   `Plan(kept, dropped, keptEstimate, gen, ctx, false, dropped > 0, genTrimmed,
        //         lostToWatermark, crossed, wmPct, …)` —— 第 7 位吃掉 `promptTrimmed`、
        //   第 8 位吃掉 `genTrimmed`，`droppedToWatermark` 直接落回默认 `false`。
        //   后果不是"少报一格"：
        //   `local_ctx.landing`（判据就是它）一次都不出现，`describe` 那句
        //   「越过水位 …%」也从来没印过，而**守卫全程 PASS** —— 它锚的是"这个字段有没有
        //   声明"，不是"有没有人给它赋过值"。现在每一格都写名字，漏一个就编译不过。
        return Plan(kept, dropped, keptEstimate, gen, ctx, false,
            droppedToWatermark = lostToWatermark,
            promptTrimmed = dropped > 0,
            genTrimmed = genTrimmed,
            crossedWatermark = crossed,
            watermarkPct = wmPct,
            promptTokensReported = keptEstimate,
            droppedTokens = 0,
            keptAskOnly = keptAskOnly,
            estimateScaleX1000 = scaleX1000,
            // 丢掉的正文有多少**字符**：保留集与原件按序比一遍（见 `Plan.droppedChars`）。
            // 只在这一轮真丢过时算 —— 不丢时它是 0，而 0 与"没算"同值同义。
            droppedChars = if (dropped > 0) droppedCharsOf(messages, kept) else 0,
            // 整段正文的真字符数（换算率的分子，见 `Plan.bodyChars`）。
            // 只在 relay 那条路上被读 —— 直连的调用方拿到也不做任何事。
            bodyChars = bodyCharsOf(messages),
            // 「这一轮从第几条起发」—— 上面按最终 `kept` 尾对齐定位过一次。
            keptFrom = keptFrom,
            // 「这一次是按哪条线判的」—— 起点标记要记住它，下一轮才判得出"位置线变了"。
            positionCap = posCap,
            // 「进来时已经被前置截掉几条」：原样带出，不参与任何落值。
            // ⚠ 只**带出**、不在这里重算 —— 这一轮 `messages` 已经是被截过的那一份，
            //   条数早就不在了，算不出来，也不该猜。
            trimmedByCaller = callerTrimmed.coerceAtLeast(0))
    }

    /**
     * 本轮生成后缀占多少 token —— 只回答"要不要算上思考那一段"，不猜模板形状。
     *
     * 三档，各自对应一个真实形态：
     *   · `thinkingOn` 且模板**不带** `enable_thinking`（软开关不适用，后缀里没思考标记）
     *     -> 只有 `<|im_start|>assistant\n`；
     *   · 软开关注入了空 think 块（`softInjected`）-> 后缀里多了一整段闭合块；
     *   · 其余（思考开、或模板自己带 `enable_thinking`）-> 按"带思考标记"估。
     *
     * ⚠ 高估的那一档最多多算二十几个 token，低估的那一档会让 native 每轮砍掉头部
     *   一段 —— 两者代价不对称，所以拿不准时一律往高里算（见文件头）。
     */
    /**
     * 「**保留集为空**」那一档的唯一出口：`overflow` / 一条都发不出去。
     *
     * ⚠ 它存在的理由只有一条：`keptFrom` 在这一档必须写 `messages.size`（**全丢**），
     *   而 0 的读法是"一条都没丢"—— 正好相反。以前三处（`ctx <= 1` / `posCap <= 1` /
     *   最后那道 `overflow`）各写一遍，写得对不对只能靠人盯；现在只在这里写一次，
     *   调用方按需补自己的额外字段（水位 / bodyChars / dropped 的底数）。
     *
     * ⚠ 它**不**校验"保留集真的空了"：这一档由调用方判（`room <= 0` 或
     *   `tokens + gen > availForVerdict`）。在这里再判一遍就是第二套判据。
     *
     * @param allEst 报出去的估算：**原样那一份**（不是丢到只剩提问的那个数）——
     *   400 的文案里那个"约 N tok"要回答的是"用户送了多大一段"。
     */
    private fun overflowAllDropped(
        messages: List<Pair<String, String>>,
        allEst: Int,
        gen: Int,
        ctx: Int,
        dropped: Int = messages.size,
        // ⚠ 水位与位置线必须是**命名实参**，不许落默认值（ISSUE #586）：
        //   它们都是 `Int`，调用点写错顺序时 Kotlin 一声不响 —— 而这里的默认值
        //   被读出去就是 `local_ctx.watermark: 0`（客户端以为自己把水位关了）。
        watermarkPct: Int,
        posCap: Int,
        // 两格原先读的是 [plan] 里的**局部量**（`judgeScale` 折算出的那个系数、
        // 调用方报的 `callerTrimmed`）。这个函数挪到成员位上以后它们不再可见 ——
        // 由调用方按当时的值传进来，语义一字不动。
        estimateScaleX1000: Int,
        trimmedByCaller: Int
    ): Plan {
        val est = allEst.coerceAtLeast(0)
        return Plan(emptyList(), dropped, est, gen, ctx, true,
            watermarkPct = watermarkPct,
            promptTokensReported = est,
            droppedTokens = est,
            estimateScaleX1000 = estimateScaleX1000,
            bodyChars = bodyCharsOf(messages),
            // 一条都发不出去 = 保留集为空 = 起点在**末尾**（`messages.size`），不是 0。
            // 写 0 会被读成"一条都没丢"，正好反过来（见 [Plan.keptFrom]）。
            keptFrom = messages.size,
            // 这一档同样按位置线判过（判成"装不下"）—— 起点标记的作废判据要有基准。
            positionCap = posCap,
            trimmedByCaller = trimmedByCaller.coerceAtLeast(0))
    }

    fun generationSuffixTokens(thinkingOn: Boolean, softInjected: Boolean): Int =
        if (!thinkingOn && !softInjected) GEN_SUFFIX_BASE_TOKENS
        else GEN_SUFFIX_BASE_TOKENS + GEN_SUFFIX_THINK_TOKENS

    /**
     * 本轮**头部固定段**（首条 system 正文 + 知识库注入块）占多少 token —— 给 [plan]
     * 的 `systemTokens` 用。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么必须由调用方在**注入之后**量一遍（ISSUE #330 四轮误判之后）
     * ══════════════════════════════════════════════════════════════════════════
     * 预算量的是"客户端发来的 messages"，而知识库注入块是在预算**之后**才拼进
     * 最后一条 user 的正文的（`RagInject.attachBeforeLastUser`）。于是那段块
     * （真机 513~1000+ 字）在预算里**一个 token 都不占**：
     *
     *   · 预算说"装得下、一条不丢"；
     *   · 渲染后进 native 的 prompt 比 `avail` 多出那块的长度；
     *   · native 按真 token 判 `prompt + gen > ctx - 2`，从**头部**砍一刀
     *     （`ctxbudget::trim_start`）；
     *   · KV 前缀起点每轮往后挪一刀 —— 复用只剩 system 段那几个 token。
     *
     * 而它**是否触发**取决于块的长度：命中长条文的那轮超、命中短条文的那轮没超。
     * 这就是"复用忽好忽坏"的全部机制。
     *
     * 所以量法只有一个：把**拼好的那条 system 正文**（用户设定 + 注入块）交出来，
     * 按与消息同一条尺子（[tokensOf]）折算。调用方拿到的就是"头部固定段占多少"。
     *
     * @param system 拼好的首条 system 正文（可为空串 —— 用户没设 system）
     * @return 估算 token 数；空串返回 [SYSTEM_TOKENS_UNKNOWN]
     *
     * ⚠ 空串返回 [SYSTEM_TOKENS_UNKNOWN] 而不是 `0`：调用方把它的结果**直接**当
     *   `plan(systemTokens = …)` 传，那里 `<= 0` 一律落回"不知道"——两种写法各写一遍
     *   就会漂移（一处当"已知 0"、一处当"不知道"），而漂移的后果是注入块又被抹掉。
     *
     * ⚠ 它标的是**头部固定段的归属**，不是"额外要加多少 token"（ISSUE #452 第 1 步）：
     *   `plan` 拿它和"消息里那条 system 自己已经算过的正文长度"取**差额**，只补超出
     *   消息之外的那部分（见 `plan` 里的 `systemFix`）。注入块拼在消息正文里时差额
     *   接近 0 —— 那一段不会被算两遍。以前这里是"全额补一次"，真机上正好多出几百枚
     *   虚账，把水位顶穿、白丢历史。
     */
    fun systemTokensOf(system: String): Int =
        if (system.isEmpty()) SYSTEM_TOKENS_UNKNOWN else tokensOf("system", system)

    /**
     * 一段**消息**的估算 token 数（[tokensOf] 的和）。
     *
     * ⚠ 它**不是** prompt 的估算 —— 渲染侧的固定开销与生成后缀不在消息里。
     *   判断"装得下吗"请用 [promptTokensOf]；直接拿它去和 `avail` 比，
     *   就是第一版那条窄带的来源。
     */
    fun estimate(messages: List<Pair<String, String>>): Int =
        messages.sumOf { tokensOf(it.first, it.second) }

    /**
     * 本轮响应里的 `local_ctx` 字段片段（**含前导逗号**；一个数都没量到时是空串）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这件事必须由服务端说（ISSUE #426：客户端一直是全量发送）
     * ══════════════════════════════════════════════════════════════════════════
     * 客户端每轮把**整段历史**送上来，服务端按容量裁掉最老的一段再喂模型。
     * 而客户端能看到的唯一一个数 `usage.prompt_tokens` 是**裁之后**的真值
     * （native 落的 `contextUsed`）—— 于是"我发了两千字、它回我 291"这件事
     * 在客户端那边看起来就像"服务端把它砍短了"，而它其实只是**丢掉了历史**。
     *
     * 所以三个数必须一起给：
     *   · `sent`    —— 本轮**交给 prefill 的那一段**我们估了多少（裁之后）；
     *   · `kept`    —— 同一段的真 token 数（native 数的，与 `usage.prompt_tokens` 同源）；
     *   · `dropped` —— `sent - kept`，也就是**估算比真值多估了多少**
     *                 （`messages` = 这一轮丢掉的条数，那才是"忘了多少"）。
     *
     * ⚠ `dropped` **不是**"丢掉了多少 token 的历史"（ISSUE #452）：
     *   `sent` 以前报的是裁之前那一份，于是这个差值里混着"我们丢掉的历史"，
     *   而它与"估算偏高"同形 —— 客户端按它调 Max Context 只会越调越糟，
     *   服务端那份估算系数还会拿它当样本把自己压到 0.70、下一轮丢得更多。
     * 再配上 `ask_only`（历史丢光了）与 `gen`（生成上限还剩多少），
     * 「为什么这一轮答得短 / 为什么它不记得刚才」当场可答，不必翻服务端日志。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * `ask_only` 是**唯一**一处"这一轮没上下文"的出口（ISSUE #452 收口）
     * ══════════════════════════════════════════════════════════════════════════
     * 以前服务端还会往 prompt 里拼一句 `[对话续接] …` 让模型自己知道。那句已经撤了 ——
     * **只在丢弃时出现的注入会改掉下一轮的输入**（客户端把历史送回来时不含它），
     * 代价是每丢一次多烧一整轮 prefill，甚至保留集每轮换一个起点（见 `resumeShape`）。
     *
     * 所以客户端该做的就是读这一格：
     *   · `ask_only: true` —— 这一轮模型只看到"设定 + 你的提问"，前面什么都没有。
     *     该提示用户的提示用户，该把 Max Context 调小的调小；
     *   · `ask_only: false` + `messages > 0` —— 丢了几条老消息，但上下文还在，
     *     **不必**提示用户"失忆了"。
     *
     * ⚠ 字段只在"动过手"时出现，所以缺省值**不可当作 `false` 的等价物**：
     *   字段不存在 = 这一轮一个字节都没动（`dropped == 0`），那更不是 `ask_only`。
     *
     * ⚠ 只在**动过手**（丢过历史或削过生成）时返回字段；一个字节都没动时是空串 ——
     *   与 `logprobs` / `local_rag` 同一条规矩：字段不存在 = 与引入本特性之前逐字节相同。
     *
     * @param keptReal 裁完之后**真 token** 数（native 的 `tokenize` 数；`<= 0` = 没量到，
     *   那时退回估算值 —— 一个估算数比一个空位更有用，而它与 `sent` 同源，差值仍成立）。
     * @param nativeTrimmed native 那一刀砍掉的真 token 数（`<= 0` = 没砍 / 没量到）。
     *   ══════════════════════════════════════════════════════════════════════
     *   为什么客户端需要这一格（ISSUE #452 第 4 步）
     *   ══════════════════════════════════════════════════════════════════════
     *   `proactive` 默认关之后，Kotlin 只在**越过整格**时才动手，于是 `messages` /
     *   `dropped` 在正常轮次恒 0 —— 而 native 那一刀按**真 token** 判，仍可能动手
     *   （估算偏少、或对端自己送超了）。它一动，客户端送的历史就**没有全部进模型**，
     *   而响应体里此前没有任何一个数说得出来：`usage.prompt_tokens` 是裁**之后**的数，
     *   与"对端本来就只送了这么点"逐字相同。
     *
     *   客户端自己裁得准不准，唯一能对照的就是这一格 —— 它是"服务端又替你砍了一刀"
     *   的唯一出口，`/health` 那几格客户端读不到。
     * @param genCap 这一轮**实际给**的生成长度（native 预算的落值；`<= 0` = 没量到）。
     *   `proactive` 关掉后它与 `max_tokens` 不再同值，而 `finish_reason=length` 时
     *   "我设了 1024 怎么只出 64 个字"只有这一格答得出来。
     * @param reuseGeom 复用的**几何**四格（ISSUE #452 第 0 步）：
     *   `int[4] = {lcp, reuse, start, ledger}`，取自 `LlmEngine.kvReuseGeomOf`。
     *   `reuse <= 0 && ledger <= 0` = 没量到（native 那一轮还没写账本），整组不出现。
     *
     *   ══════════════════════════════════════════════════════════════════════
     *   为什么裁剪方需要它（这条链此前断在出口上）
     *   ══════════════════════════════════════════════════════════════════════
     *   `lcp` / `reuse` / `start` / 账本长度 native 一直都在算，但它们只落在
     *   `/health` 与日志里 —— 而**裁剪方**（客户端、以及将来的中间层）唯一读得到的
     *   是响应体，那里一个都没有。于是"这一轮到底复用了多少、起点在哪、公共前缀断在
     *   哪一枚"只能翻服务端日志才看得见，裁剪只能按 `ctx - max_tokens` 猜。
     *
     *   四个数**成对读**才有意义：`reuse + start` 在通常那一档等于 `ledger`，而
     *   ⑦（跳过起点复用）那一档不等 —— 那正是"起点被跳过"唯一读得出来的地方。
     *   只报 `reuse` 时，两千多枚的复用会被读成 152（那是起点）。
     *
     *   ⚠ 它们**不参与任何判据**，只是把已经量到的数说出来（与 `native_trimmed` 同一条纪律）。
     */
    fun localCtxField(p: Plan, keptReal: Int, nativeTrimmed: Int = 0, genCap: Int = 0,
                      reuseGeom: IntArray = EMPTY_GEOM, rounds: String = "",
                      prefix: String = ""): String {
        // 一个字节都没动：不出现这个字段（逐字节兼容那一档）。
        //
        if (p.ctx <= 0) return ""
        // 只在"什么都没动**也**没量到真值"时省略。`keptReal > 0` 这一条是
        // ISSUE #452 真机（`est_scale` 恒 1000）补的：这个字段以前只在动手那一轮出现，
        // 而"没动手"的轮次恰恰是校准样本唯一该攒的地方 —— 反了：
        //   · 动手的轮次里 `dropped / genTrimmed / ask_only` 至少一个为真，样本进得去；
        //   · 一次没动手的长会话，`local_ctx` 一轮都不出现，`real / est` 一份样本都
        //     攒不到，3 份门槛永远到不了 —— 校准整条链路看起来接好了，其实空转。
        //   报出真值不等于"改动行为"：判据一个字节都没变，只是把已经量到的数说出来。
        // ⚠ 有一件事比"省略更干净"重要：**这一轮真把历史裁掉过，就必须有一个字段
        //   说得出来**。`dropped > 0` 而这一格整段消失时，中间层那一层静默退化 ——
        //   它记下的"上一轮没丢过历史"与"服务端这一轮确实丢了"在读数上完全同形，
        //   于是 `relay_forwarded` 恒 0、`kv_reuse_tokens` 只剩 system，而每一格
        //   读数都合法（ISSUE #452 真机：服务端日志里那几轮一直在丢，relay 那边
        //   一次都没删过）。所以 `p.dropped > 0` 也进这一条判据。
        if (p.dropped <= 0 && p.genTrimmed <= 0 && !p.overflow && !p.keptAskOnly &&
            keptReal <= 0) return ""
        val kept = if (keptReal > 0) keptReal else p.promptTokens
        // ⚠ 两个数现在**同一份**（都是裁完之后那一份 prompt 的估算），差值只剩
        //   "估算 vs 真 token"这一件事。以前 `sent` 报的是丢之前的全量，于是
        //   `dropped` 里混着"我们丢掉的历史" —— 而它与"估算偏高"在读数上同形，
        //   客户端按它调 Max Context 只会越调越糟（ISSUE #452）。
        //   丢掉的历史有多少，看 `messages`（条数）与 `ask_only`。
        // 两个名字同值同义（见 [Plan.promptTokensReported]），所以这里**不再**写
        // "`promptTokens` 取不到就退回 `promptTokensReported`"那种分支 —— 那个分支
        // 只在 `promptTokens <= 0` 时成立，而它俩由同一个表达式算出来，永远不会分叉。
        // 「动手那几格」= `sent` / `kept` / `messages` / `ask_only` ……
        // 收尾帧与首帧（[coordHeadOf] 也读它）读**同一个**函数：两处各拼一次，
        // 就会出现"头里说丢了 4 条、收尾说丢了 5 条"，而两个数各自都合法。
        val real = realCtxField(p, kept)
        // 裁掉了历史（`messages > 0`）就必须带一条**坐标**（`rounds` 或 `prefix`）——
        // 否则这一段说的全是"我给谁留了多少"，而读者（中间层）要的"该删哪几轮"
        // 一个字都没有。`dropped > 0` 这条判据在 `localCtxField` 的入口已经放行了，
        // 缺坐标是上游接线漏了（`rounds` / `prefix` 的调用方），不是这一格的自由选择。
        // ⚠ 顺序与首帧（[coordHeadOf]）一致：坐标（`rounds` / 复用几何）在前，
        //   起喂点（`prompt_prefix`）在最后。同一个键在两处的值必然相同 —— 两处读的是
        //   同一批形参，且都只做一次拼接。
        // ⚠ 返回值是**自成一体的**片段：`,"local_ctx":{…}` 的右花括号在这里补齐。
        //   以前它把右边那一个留给调用方的响应体（"外层那个 `}` 本就在那里"），
        //   而调用方拼的是 `…"usage":{…}$ctxField}` —— 那一个 `}` 只够关掉
        //   `local_ctx`，最外层对象反而少了一个，整条响应**永远**不是合法 JSON。
        //   现在它还要单独放进 `X-Local-Ctx` 响应头（`substring(indexOf("{"))`），
        //   更必须能独立解析；正文那边已经不用它，不会再出现"两个 `}` 打架"。
        return ""","local_ctx":{""" + real.substring(1) +
            restCtxField(p, nativeTrimmed, genCap, reuseGeom) + rounds + prefix + "}"
    }

    /**
     * `local_ctx` 里"**要到收尾才有真值**"的那几格（含前导逗号）。
     *
     * 它们不进口径更窄的 [coordHeadOf]（首帧 / 响应头那一份）：`gen_cap` 是这一轮
     * **实际**能用多少生成位置、`native_trimmed` 是 native 那一刀砍了多少 —— 两者在
     * prefill 之后还会变，首帧报一遍、收尾再报一遍就是两个读数。
     */
    private fun restCtxField(p: Plan, nativeTrimmed: Int, genCap: Int, reuseGeom: IntArray): String =
        // ⚠ 逗号归属只有一条规矩：**除第一格外，每一格都自己带前导逗号**。
        //   此前 `gen_cap` 末尾留了一个逗号，而 `genRoomField` 也自带前导逗号 ——
        //   两个都在时拼出 `512,,`；两者之间任何一格省略时又会拼出
        //   `"gen_room":9628"est_scale"`（缺逗号）。整段因此不是合法 JSON，
        //   而它只在**某些档位**才坏（有 gen_room / 没 gen_room 各一种），
        //   单看源码里那行字面量完全看不出来。
        ""","native_trimmed":${nativeTrimmed.coerceAtLeast(0)}""" +
            ""","gen_cap":${if (genCap > 0) genCap else p.maxTokens}""" +
            // 这一轮**丢完之后还剩多少位置给回答**（ISSUE #586）：客户端拿
            // `finish_reason=length` 回来问"为什么话说到一半就断"时，`gen` 与 `gen_cap`
            // 都只是"上限"，答不了这件事。这一格是减出来的余量 ——
            // `ctx × 水位 − 本轮发出去的 prompt − gen`（`< 0` 归 0；`ctx <= 0` 时不出现）。
            // ⚠ 它**不参与任何判据**，与 `spare`（离整格还有多远）是两个口径：
            //   水位才是这一轮的目标线，`spare` 描述的是硬墙。
            genRoomField(p) +
            // 这一轮判据用的估算系数（×1000）。`sent` / `dropped` 都是**乘过它**之后的数，
            // 没有它就没法把"真丢了这么多"与"估算被调低了"分开 —— 而两者处置相反
            // （前者调 ctx，后者什么都不用做，几轮之后系数自己会回去）。
            ""","est_scale":${p.estimateScaleX1000}""" +
            // 复用的几何（ISSUE #452 第 0 步）：`lcp` / `reuse` / `start` / `ledger`。
            // 四格**成组出现或成组不出现** —— 只给其中两格时，读的人拿到的是一个
            // 从未同时成立过的组合（"起点 170、账本 0 枚"），比缺这几格更误导。
            reuseGeomField(reuseGeom)

    /**
     * `local_ctx.gen_room` —— 这一轮**丢完之后还剩多少位置给回答**（含前导逗号；量不出来时是空串）。
     *
     * 与 [roundsUntilNextDrop] 同一份算术，差别只有最后一步：那边除"一轮多大"得到轮数，
     * 这边直接给出枚数。两个数必须同源 —— 分开算就会出现"日志说还有 7 轮、
     * 而这一轮只剩 200 枚位置"这种自己打自己的读数。
     *
     * ⚠ `gen` 与 `gen_cap` 都只是**上限**（一个是宿主预算给的、一个是 native 那一刀之后的），
     *   而"这一轮实际最多能吐多少"是 `min(上限, 位置)`。第三个数此前没有任何一格说得出来，
     *   于是"我设了 1024，为什么它只说了半句"只能靠 `spare` 手算（而 `spare` 量的是
     *   离**整格**还有多远，不是离水位线）。
     */
    private fun genRoomField(p: Plan): String {
        if (p.ctx <= 0) return ""
        val avail = ContextWatermark.limitTokens(p.watermarkPct, p.ctx)
        if (avail <= 0) return ""
        val room = avail - p.promptTokens - p.maxTokens
        return ""","gen_room":${room.coerceAtLeast(0)}""" + landingField(p)
    }

    /**
     * 三格落点读数的**人话版**（App 内聊天的日志用；HTTP 那条路从 `local_ctx.landing` 出去）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这件事不能只在响应体里说（ISSUE #586 真机第四次）
     * ══════════════════════════════════════════════════════════════════════════
     * App 内聊天**不经过 HTTP**，它读不到 `local_ctx`；`[ctx]` 那行此前只说"丢了几条、
     * 发出去多大"，于是用户问「落点线是多少、`max_tokens` 吃掉多少、还剩多少余量」
     * 时，屏幕上和日志里都没有一个数能对上 —— 他只能拿 `avail × 50% − max_tokens`
     * 自己算，算出来和读数差着一整份余量，然后判定"你算错了"。
     *
     * 三格与 `local_ctx.landing` **同一个来处**（[landingField] / [roundsUntilNextDrop]）：
     * 两处各算一遍就会出现"探活说 2064、日志说 1040"，而两个数各自合法。
     */
    fun landingSummary(p: Plan): String {
        // ⚠ 判据是"这一轮**真丢过**"（`dropped > 0`），不是 `droppedToWatermark`：
        //   后者只在"按水位主动治理"那一档为真，而默认 `PROACTIVE_DEFAULT = false`，
        //   加上贴线丢到刚好装下 / `minimalKeep` 封顶 / overflow 几档都不置它 ——
        //   于是这三格在最常见的裁剪形状下一次都不出现（ISSUE #586 真机）。
        //   落点线是"丢完之后该落在哪"，只要丢过就该报，和水位有没有参与判据无关。
        if (p.dropped <= 0) return ""
        val avail = ContextWatermark.limitTokens(p.watermarkPct, p.ctx)
        if (avail <= 0) return ""
        val line = halfWatermarkTarget(avail, per = 0, wantGen = 0)
        val prompt = (line - p.maxTokens).coerceAtLeast(0)
        return "｜落点线 $line（合计）／`max_tokens` 占 ${p.maxTokens}／该给历史 $prompt" +
            "／离下次动手还空 ${(avail - line).coerceAtLeast(0)}"
    }

    /**
     * `local_ctx.landing` —— 这一轮**真正把历史丢到了哪**（`prompt + gen` 的合计；含前导逗号）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么光有 `kept` 那一格不够（ISSUE #586 真机第三次）
     * ══════════════════════════════════════════════════════════════════════════
     * 真机读到的是「水位 90%、`max_tokens 1024`，历史只剩 100 多」。三个数摊开一对，
     * 账其实是对的，而**没有一格把这件事说出来**：
     *
     * ```
     * 落点线（`prompt + gen` 的合计目标）  1409   ← 历史该停在哪
     * 池里已经先被 `max_tokens` 占了       1024   ← 它比整条落点线还大
     * 于是历史最多只剩                     385
     * ```
     *
     * 同一套设置下把 `max_tokens` 降到 512，历史就回到 897。**`max_tokens` 越大、
     * 留下历史越少** —— 这不是 bug，是「`gen` 在落点线里占位」这条口径的直接后果
     * （见 [halfWatermarkTarget]），可它此前只写在注释里，用户拿 `avail × 50% − gen`
     * 一类算式去对账时，永远算不出自己屏幕上那个数。
     *
     * `ideal` 是只按公式算出来的那一份（`line − max_tokens`），`real` 是这一轮
     * 实际的合计 —— 两个数一起给，`ideal <= 0` 就是「这一轮根本不是按历史定的落点，
     * 是 `max_tokens` 把落点吃穿了」（`4096 / 90% / 1024` 这一档就是 `1622 − 1024 = 598`）。
     *
     * ⚠ `headroom` 量的是「落完到下一次动手之间，历史还能涨多少」——
     *   起判线 `avail − gen` 减落点线 `line`，`gen` 在两头各出现一次并**约掉**：
     *   `= avail − line`。所以它是这几格里**唯一与 `max_tokens` 无关**的读数。
     *   曾经在这里写 `avail − max_tokens − line`（ISSUE #586 真机第四次）：
     *   `4096 / 90% / 1024` 报出 `3686 − 1024 − 1622 = 1040`，而真值是 **2064** ——
     *   差的那一整份 `max_tokens` 会让同屏「够再聊约 N 轮」与 `headroom` 互相打脸，
     *   而两个数各自看起来都合法。这一格与 [roundsUntilNextDrop] 的 `gap` 同源。
     */
    private fun landingField(p: Plan): String {
        // 与 [landingSummary] 同一条判据：**丢过就报**（含水位没参与的那几档）。
        if (p.dropped <= 0) return ""
        val avail = ContextWatermark.limitTokens(p.watermarkPct, p.ctx)
        if (avail <= 0) return ""
        // 与落点**同一处定义**（`per = 0` / `wantGen = 0`），不在这里另算一份基数。
        val line = halfWatermarkTarget(avail, per = 0, wantGen = 0)
        val ideal = line - p.maxTokens
        val real = (p.promptTokens + p.maxTokens).coerceAtLeast(0)
        return ""","landing":{"line":$line,"ideal":$ideal,"real":$real,""" +
            """"headroom":${(avail - line).coerceAtLeast(0)}}"""
    }

    /**
     * `local_ctx` 里"这一轮动手了几格"（含**前导逗号**）。
     *
     * 收尾帧（[localCtxField]）与首帧（[coordHeadOf]）都读这一份：
     * 两处各拼一次，就会出现"头里说丢了 4 条、收尾说丢了 5 条"，而两个数各自都合法。
     *
     * @param kept 这一轮**真**留下的 prompt token 数（native 量的；量不到时是估算值）
     */
    private fun realCtxField(p: Plan, kept: Int): String {
        val sent = p.promptTokens
        val droppedTok = (sent - kept).coerceAtLeast(0)
        return ""","sent":$sent,"kept":$kept,"dropped":$droppedTok,""" +
            """"messages":${p.dropped},"ctx":${p.ctx},"gen":${p.maxTokens},""" +
            """"watermark":${p.watermarkPct},"overflow":${p.overflow},"ask_only":${p.keptAskOnly}"""
    }

    /**
     * [localCtxField] 里的复用几何四格（含前导逗号；没量到时是空串）。
     *
     * 判据只有一条：`reuse <= 0 && ledger <= 0` = native 这一轮还没写账本
     * （冷启动 / 上一轮失败）。那时报 0 与"没命中"同形，所以整组省略 ——
     * 与 `local_ctx` 那条"字段不存在 = 与引入本特性之前逐字节相同"的规矩一致。
     */
    fun reuseGeomField(g: IntArray): String {
        if (g.size < 4) return ""
        val lcp = g[0]
        val reuse = g[1]
        val start = g[2]
        val ledger = g[3]
        if (reuse <= 0 && ledger <= 0) return ""
        return ""","lcp":$lcp,"reuse":$reuse,"start":$start,"ledger":$ledger"""
    }

    /** 没量到几何时的占位（[reuseGeomField] 见到它就返回空串）。 */
    val EMPTY_GEOM = IntArray(0)

    /**
     * `local_ctx.prompt_prefix` —— 服务端这一轮**真正喂进去的那一份**，在整段 prompt 里
     * 从第几个字符开始（ISSUE #452「拿回真流式」）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它替掉的是「中间层必须先读到整条响应」那件事
     * ══════════════════════════════════════════════════════════════════════════
     * 中间层此前拿不到本轮的轮次坐标（`rounds` 只挂在收尾帧上），于是只能把整条响应缓冲
     * 下来、抽完坐标再转给客户端 —— 首帧延迟 = 整轮生成时长，真流式没了。
     *
     * 而中间层真正要的只有一件事：**服务端留下的那一份，开头落在客户端原文的哪里**。
     * 整段 prompt 就是客户端那份 messages 渲染出来的，所以客户端原文里
     * **每条消息的累计字符数**就是消息边界在这条 token 序列上的坐标 —— 中间层算得出来。
     * 那就只剩一个未知量：起喂那枚的偏移，用**字符**表示。
     *
     * 两条换算都成立才有值，否则给 `-1`（中间层据此不切、原样直发）：
     *   · `charsPerTok`：这是**服务端自己量出来的换算率**（预估字符数 ÷ 真 token 数）。
     *     在 `ContextBudget` 那套"ASCII 0.28 / 其余 1.0"的折算里，整段 prompt 的平均
     *     字符数就是 `promptTokens` 这个估算值 —— 所以 `偏移 / 率` 得到的是**同一个
     *     预估口径**下的 token 位，与推算 `tok_start` 用的是同一条尺子，误差同向抵消。
     *     缺了它，`tok_start * 平均字节` 会把一个 ASCII 开头、中文结尾的会话切错半轮。
     *   · 别处算不出这两个数，也不该算出第三份：换算只有这一处。
     *
     * @param origin [LlmEngine.turnOriginOf] 那三格（`tok_start / tok_end / dropped_tok`）
     * @param promptTokens 估算口径那一份 token 数（`Plan.promptTokens`）
     * @param droppedChars 预算在丢弃处**额外撤销**的字符数（`Plan.droppedChars`）：
     *   `> 0` = Kotlin 预算动过手，换算率退回 `promptTokens / tokEnd`；
     *   `0` = 只有 native 动过（或都没动），换算率用**真字符数 / 真 token 数**。
     * @param budgetChars 这一轮**丢之前**那份 messages 的正文真字符数（`Plan.bodyChars`）
     * @param tailRounds 从起喂点起到最后还有几轮（`RoundGeometryField.tailRoundsOf`；
     *   `<= 0` = 报不出来 / 不需要）。中间层据此把"吸附到的那条消息"沿轮边界核一遍，
     *   见 `RelayTrim.byPrefix`。
     */
    fun promptPrefixField(origin: IntArray, promptTokens: Int, droppedChars: Int,
                          budgetChars: Int = 0, tailRounds: Int = -1): String {
        if (origin.size < 3) return ""
        val tokStart = origin[0]
        val tokEnd = origin[1]
        // `tok_end <= 0` = native 这一轮没量到（冷启动 / 失败路径 / 未加载）：
        // 不报。报一个 0 会被读成"整段都在"，而那是**另一种**成立的事实。
        if (tokEnd <= 0) return ""
        if (tokStart < 0 || tokStart > tokEnd) return ""
        if (promptTokens <= 0) return ""
        // ⚠ 这里**没有**"起喂点 == 0 就不报"那一档（上一版有，已撤）。
        //
        //   `tok_start == 0 && droppedChars == 0` 说的是"服务端这一轮一枚都没动"，
        //   而它恰好是**中间层最需要知道的那一件事**的答案：客户端原文与服务端喂进去的
        //   那一份对齐在 0 —— 该删 0 轮。省略它等于让中间层把"对齐在 0"读成"没有坐标"，
        //   而两者的处置相反（一个照旧删/不删，一个退化成直连）。
        //   ISSUE #452 真机就是这一档：`local_ctx` 里 `dropped == 0`（Kotlin 没动手、
        //   native 也没砍），于是中间层一轮都没删过 —— `relay_forwarded` 恒 0。
        //   多出来的那几十字节买的是"这一层的输入永远是完整的"。
        // `chars_start` = **起喂点**在客户端原文里的字符位（不是整段多长）。
        //
        // 中间层只需要这一个数：它在自己手里那份 messages 上累加 `content.length`，
        // 找到累计到 `chars_start` 的那一条 —— 那就是服务端留下的第一条，它前面的全删。
        // 报"整段多长"是没用的（中间层本来就量得到自己的那份），而报"比例"要多一次
        // 除法、还要假设两边的口径同比例，多一个假设就多一种切错半轮的方式。
        //
        // 换算用 `promptCharsPerTok(...)`，分子是**真字符数**（`budgetChars`）、分母是
        // 同一份文本的真 token 数（`tokEnd`）—— 两个数说的是同一件事，比值就是这一轮
        // 自己量到的字符/token 密度。中间层手里那把尺子也是真字符，两边同口径。
        //
        // ⚠ 上一版拿 `promptTokens`（估算口径：逐条固定开销 + 渲染开销 + 生成后缀，
        //   再乘一笔余量）当分子。短会话上那些固定开销压过正文几十倍，换算率被算成
        //   1.18 字符/token，于是 `chars_start` 恰好等于 `tok_start` 本身；中间层拿它去比
        //   客户端消息边界（system 就一千多字符），比哪条都不落 → 每轮原样转发，
        //   而 `relay_reason` 还说得挺诚恳（INV-#452 真机第二场）。
        val cpq = promptCharsPerTok(promptTokens, tokEnd, budgetChars, droppedChars)
        // ══════════════════════════════════════════════════════════════════════
        // 在**保留集**的坐标系里换算，再补回"被我们撤销掉的那几段"
        // ══════════════════════════════════════════════════════════════════════
        // `tok_start` 是 native 在这一轮**真正喂进去的那一段**里的偏移（保留集），
        // 而中间层要的是"落在客户端原文的第几个字符"——两者差着**被撤销的那几段**。
        //
        // `droppedChars > 0` 正是"Kotlin 预算自己丢过东西"那一档，而它在旧写法里
        // 同时还有两个后果：
        //   · `promptCharsPerTok` 退回 `promptTokens / tokEnd`（估算口径），换算率偏小；
        //   · `charsStart` 少了 `droppedChars` 那一截 —— 起喂点报得比实际**靠后**，
        //     于是 relay 按它删的时候**少删一轮**（少删的表现是"服务端每轮再丢一次"，
        //     而这一层看起来一切正常）。
        // ISSUE #450 第十四次现场：relay 端点一按停止就"没有输出"，而直连同一份对话
        // 正常 —— 两个端点在**同一份历史**上算出的 `chars_start` 差着一整段，
        // relay 据此切在半句上，`RelayTrim` 判"不落在任何一条消息开头"而原样转发，
        // 于是服务端这一轮收到的仍是全量（prefill 变长、更容易被客户端判超时）。
        //
        // 补回来之后，两个端点报的是同一个坐标：**客户端原文里的字符位**。
        // 换算仍然只用真字符 / 真 token（`bodyChars` 是丢之前那份的真字符数）——
        // 它本来就是"原文那份"的密度，与补回来之后的坐标系同源。
        val keptStart = (tokStart.toLong() * cpq / 1000L).toInt()
        val charsStart = keptStart + droppedChars.coerceAtLeast(0)
        // `tail_rounds`：从起喂点起到最后还剩几轮 —— 中间层据此把"吸附到的那条消息"
        // 沿轮边界核一遍（见 `RelayTrim.byPrefix`）。`<= 0` = 报不出来，不报。
        val tail = if (tailRounds > 0) ",\"tail_rounds\":$tailRounds" else ""
        return PREFIX_OPEN + "{" + "\"tok_start\":$tokStart,\"tok_end\":$tokEnd," +
            "\"chars_start\":$charsStart,\"cpq_x1000\":$cpq$tail}"
    }

    /** `local_ctx` 里那一格的名字（含前导逗号）：两处拼接都读这一个常量。 */
    private const val PREFIX_OPEN = ""","prompt_prefix":"""

    private const val KEPT_ROUNDS_HEAD = ""","kept_rounds":1"""

    private const val KEPT_CTX_ROUNDS_HEAD = ""","kept_ctx_rounds":"""

    private const val KEPT_CTX_DROPPED_HEAD = ""","kept_ctx_dropped":"""

    /**
     * 首帧 / 响应头那一份 `local_ctx`（`,"local_ctx":{...}` 的对象体；空串 = 没有坐标）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 它和收尾帧那一份是**同一批数**，不是另拼的一份
     * ══════════════════════════════════════════════════════════════════════════
     * 上一版把 `prefix` / `rounds` / 复用几何重新拼了一遍，于是"头里说丢了 4 条、
     * 收尾说 5 条"这类漂移必然出现一次；更要紧的是它**漏了 `messages`**（服务端
     * 动手的那一格）—— 服务端真丢了历史、却没接上前缀时（冷启动 / 换会话 /
     * 裁剪后起点挪了），复用几何是空的，首帧于是一个字都不说：
     * 中间层下一轮退化成直连，`relay_forwarded` 恒 0（INV-#452 真机）。
     *
     * 所以这里只做一件事：**收尾帧那几格里，凡是"这一轮开头就已经确定"的，原样搬过来**。
     *   · `sent` / `kept` / `messages` / `ctx` / `ask_only` —— [realCtxField] 一次拼成；
     *   · `rounds` / `prompt_prefix` / 复用几何 —— 同上；
     * 收尾才有真值的那几格（`gen_cap` / `native_trimmed` / `est_scale` / `gen`）不进：
     * 它们在 prefill 之后还会变，首帧报一遍、收尾再报一遍就是两个读数。
     *
     * 所以首帧与收尾帧**分工**：首帧只说坐标（中间层的输入），收尾帧说全部（人看的）。
     * 同一个键在两处都出现时值必须相同 —— 这里硬保证：入参就是收尾帧那几个值。
     *
     * `keptCtxRounds` / `keptCtxDropped` = 这一轮服务端**留下**几轮、又**丢掉**几轮。
     * 两者相加是"服务端这一轮一共见到几轮" —— 中间层下一轮拿它与"我上一轮删了几轮"
     * 相加，才是能与自己手里的轮数相比的那个数（见 `RelayTrim.Prev.prevRoundCount`：
     * 只报 `rounds` 里出现过的轮数，永远差着中间层删掉的那几轮，于是每轮都判
     * "历史被改过，原样转发"）。
     *
     * @param kept 这一轮**真**留下的 prompt token 数（与 [localCtxField] 的 `keptReal` 同一个数）
     * @param prefix 起喂点那一格（首帧 / 头一律传空串：那时它还是估算，见调用处）
     */
    fun coordHeadOf(p: Plan, kept: Int, rounds: String, prefix: String, reuseGeom: IntArray,
                    keptCtxRounds: Int = 0, keptCtxDropped: Int = 0): String {
        val real = realCtxField(p, kept)
        val geom = reuseGeomField(reuseGeom)
        // `kept_rounds`：复用几何有值 = 服务端这一轮确实接上了前一轮的缓存，也就是
        // "上一轮留下的那几轮仍然在里面" —— [RelayTrim] 能用的最小输入，且它在
        // **生成还没开始**时就已经是定值。
        //
        // ⚠ 判据里**不许**再夹一个 `lcp > 0`：`kept_rounds` 挂的是"服务端这一轮留下了
        //   几轮"（`kept_ctx_rounds`），而 `lcp` 说的是"复用了多少 **token**"。两个量纲。
        //   短会话上服务端完全可能"轮次对得上、一枚都没复用"，那时按 `lcp` 判等于把
        //   一份有效坐标丢掉 —— 而丢掉的表现正是 `relay_forwarded` 恒 0。
        val keptFlag = if (geom.isEmpty()) "" else KEPT_ROUNDS_HEAD
        // 服务端这一轮**留下**几轮、又**丢掉**几轮（只在报了 `kept_rounds` 时一起下发）：
        // 中间层下一轮要拿它与"我上一轮删了几轮"相加，得到"上一轮服务端一共见到几轮"
        // —— 那才是可以与本轮客户端轮数相比的那个数（见 `RelayTrim.Prev.prevRoundCount`）。
        val coordRounds = if (keptFlag.isEmpty()) "" else
            KEPT_CTX_ROUNDS_HEAD + keptCtxRounds.coerceAtLeast(0) +
                KEPT_CTX_DROPPED_HEAD + keptCtxDropped
        // 与 [localCtxField] 同一个形状：`,"local_ctx":{...}`（含前导逗号）。
        // 只给对象体是不行的 —— 拿它去 `indexOf('{')` 会退到 `prompt_prefix` 自己那个
        // 花括号上，于是账本解析到 `{"local_ctx":{"tok_start":…}`，而它**看起来像 JSON**。
        return ""","local_ctx":{""" + real.substring(1) + geom + rounds + keptFlag + coordRounds +
            prefix + "}"
    }

    /**
     * 「整段正文的真字符数 / 真 token 数」（×1000；`<= 0` = 量不到）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 分子必须是**真字符数**（ISSUE #452 真机：中间层一次都没删）
     * ══════════════════════════════════════════════════════════════════════════
     * 中间层要把"起喂偏移"（token 位）换回**客户端原文里的字符位**，而它手里那把尺子
     * 是 `messages[i].content.length` —— 真字符。所以这个比值的分子也只能是真字符。
     *
     * 上一版的分子是 `promptTokens`，那是**估算口径**的一堆东西：逐条消息的固定开销
     * （[PER_MESSAGE_OVERHEAD]）、渲染开销（[RENDER_OVERHEAD]）、生成后缀，最后再乘一笔
     * [PROMPT_EST_SAFETY_PCT] 的余量。短会话上光这些固定开销就压过正文几千倍：
     * 真机读数 `起喂点字符 270`（两侧都是 270，因为它就是 `tok_start` × 约 1.0），
     * 而客户端 system 一条就一千多字符 —— 于是"起喂点不落在任何一条消息的开头"，
     * 每轮原样转发，`relay_forwarded` 恒 0，而 `relay_reason` 那句话说得很诚恳。
     *
     * 分子换成 [bodyChars]（`Plan.bodyChars`：这一轮**丢之前**那份 messages 的正文真字符数）
     * 之后，比值就是**这一轮自己量到的**字符/token 密度：纯中文 ≈ 1.0、纯英文 ≈ 3.6、
     * 中英混排落在中间。两边同一轮、同一份文本，所以"开头英文、结尾中文"那种会话
     * 也不会切错半轮；而它**不随会话变长而漂**（固定开销、估算余量、校准系数都不进分子）。
     *
     * ⚠ `droppedChars > 0`（Kotlin 预算自己动过手）时退回 `promptTokens / tokEnd`：
     *   `bodyChars` 是丢之前的正文，而 `tok_start` 是丢之后那一段的起点，那时两者不同源。
     *   这一档偏差的方向是**多留几轮**（安全侧），可以接受。
     *
     * @param bodyChars 整段正文的真字符数（[bodyChars]；`<= 0` = 没量到）
     * @param droppedChars 预算自己撤销掉的字符数（`> 0` = 走上面那条回退）
     */
    fun promptCharsPerTok(promptTokens: Int, tokEnd: Int, bodyChars: Int = 0,
                          droppedChars: Int = 0): Int {
        if (tokEnd <= 0) return 0
        // 真字符 / 真 token：这两个数才是同一个口径（同一份文本、同一次渲染）。
        if (bodyChars > 0 && droppedChars <= 0) {
            return (bodyChars.toLong() * 1000L / tokEnd).toInt().coerceIn(250, 8000)
        }
        if (promptTokens <= 0) return 0
        // 下限 250（= 0.25 字符/token）：再低已是"纯 ASCII 极端档"，而它会让
        // `偏移 × 率` 缩得过分小 —— 中间层据此多留几轮是安全的，少留才是坏事。
        return (promptTokens * 1000L / tokEnd).toInt().coerceIn(250, 8000)
    }

    /**
     * 「丢完之后，还要涨几轮才又越线」—— 只给 [describe] 那句承诺当数（不参与任何判据）。
     *
     * ══════════════════════════════════════════════════════════════════════════
     * 为什么这句字数得出来（而"几十轮"那句话是错的）
     * ══════════════════════════════════════════════════════════════════════════
     * 落点与起判线都是同一批常数的函数，两者之间那段距离是**定值**：
     *
     *   · 落点合计 = `avail × HALF_RATIO_PCT − avail × HALF_HEADROOM_PCT`（`prompt + gen`）；
     *   · 起判线   = `avail − gen`（`availTrigger`，越过它才动手）。
     *
     * 于是余量 = `起判线 − 落点合计 = avail × (1 − 占用比 + 余量比) − gen`。
     * ⚠ 这里的 `gen` 是**意愿值**（`max_tokens`），不是 `Plan.maxTokens`：后者在越线
     *   那一轮可能已经被让到地板，拿它算出来的余量会长出一整截 —— 而那一截是
     *   "这一轮少说了多少"，不是"这一轮多留了多少历史"。
     *
     * 一轮涨多少取 `promptTokens ÷ 保留集里的轮数`（[RoundGeometry] 与预算**同一把**
     * 切法，两边各切一次就会数出两个轮数）。
     *
     * ⚠ 除数是**判据那一份的量**（`promptTokens`，含渲染开销与生成后缀），不是正文：
     *   拿正文除会少算掉 `PER_MESSAGE_OVERHEAD` 与那 96 枚固定开销，余量被读大好几轮。
     *
     * ⚠ 它**只是文案里的一个数**，不参与任何判据：真按它去丢/去留，就成了"落点跟着
     *   估算漂"的第三套尺子（见 [halfWatermarkTail] 那两段 ⚠）。
     *
     * ⚠ 它量的是"**能不能撑到下一轮**"，不是"下一次动手在几轮之后"：会话涨得快
     *   （每轮几百枚）时前者会小于后者，而两句问的不是一件事 —— 别拿它去描述
     *   "多久失忆一次"。
     * ⚠ 不许再写"好几十轮"这种没人算过的绝对话（ISSUE #586 真机）：
     *   `ctx 4096 / 水位 85 / max_tokens 1024` 那一档余量只有 925 枚，
     *   按每轮 130~250 枚算是 **4~7 轮**，而日志当时印的是"好几十轮" ——
     *   用户拿它一对账，只会得出"它没按说的做"。
     */
    fun roundsUntilNextDrop(p: Plan): Int {
        if (p.ctx <= 0 || p.maxTokens <= 0) return 0
        val avail = ContextWatermark.limitTokens(p.watermarkPct, p.ctx)
        if (avail <= 0) return 0
        // 落点合计：占用比 × 水位线，再减去余量 —— **直接问 [halfWatermarkTarget] 要**
        // （`per = 0` 关掉"下一轮那一组"那道闸，`wantGen = 0` 不吃生成量，剩下的就是落点线）：
        // 与落点同一处定义。这里另写一遍时，比例一改"日志说还有 7 轮、实际 3 轮"
        // 就只会表现为"它说话不算话"，而没有任何一格读数对得上（ISSUE #586）。
        val landing = halfWatermarkTarget(avail, per = 0, wantGen = 0)
        val trigger = avail - p.maxTokens
        // ⚠ 余量的量具是 **prompt**，不是合计（ISSUE #586 真机第三次）：
        //   `trigger` 是"prompt 涨到哪儿就该动手"（`avail − gen`），落点线说的是
        //   "`prompt + gen` 该停在哪儿"。两者相减，`gen` 会**抵掉一次** —— 真机
        //   `4096 / 90% / 1024`：`2662 − 1409 = 1253`，而历史真正能用的只有
        //   `落点线 − gen = 385` 枚，涨两轮就摸线。报"还剩 4 轮"是在替用户做错账。
        //   这里改成"历史到起判线还有多远"：`avail − gen（起判线） − (落点线 − gen)
        //   = avail − 落点线`，与 `max_tokens` 无关 —— 这才是「丢一次管几轮」那个问题
        //   真正的答案，而落点线本身的形状仍由 [halfWatermarkTarget] 一处说话。
        val gap = (avail - landing).coerceAtLeast(0)
        // 一轮多大 = 这一轮发出去那一段 ÷ 它里面的轮数（`RoundGeometry` 与预算**同一把**
        // 切法，两边各切一次就会数出两个轮数）。
        // 只剩 system + 本轮提问（0 轮）时给 0 —— 调用方据此不印这个数，而不是印一个假数。
        val rounds = RoundGeometry.of(p.messages).count { it.kept }
        if (rounds <= 0 || p.promptTokens <= 0) return 0
        val per = (p.promptTokens / rounds).coerceAtLeast(1)
        return gap / per
    }

    /**
     * 一句话原因（响应字段与日志共用，不在这里另写一份文案）。
     *
     * 三档互斥，而且**第三档不许被前两档吞掉**：`dropped > 0` 是"我丢了老轮次、
     * 这一轮照样答"，`overflow` 才是"一条都塞不进去、回 400"。真机上这两件事
     * 曾被同一个数表达，于是客户端超长对话一直收到 400，而服务端其实已经在裁了。
     */
    /**
     * 「估算被校准过」那一句 —— 只在系数真的动过（< 1.0）时出现在文案里。
     *
     * 为什么不并进每一档文案：系数 1.0 是常态，印出来每行都多一截噪音；而系数
     * 动过时它**必须**在，否则同一个 `dropped` 在系数 1.0 与 0.7 下读起来一样，
     * 而那两件事的处置相反（一个该调 ctx，一个什么都不用做）。
     */
    private fun scaleNote(p: Plan): String =
        if (p.estimateScaleX1000 >= 1000) "" else
            "（估算按真 token ×${p.estimateScaleX1000 / 1000.0} 校准过 —— " +
                "少丢的那几轮是因为估算原来偏高，不是容量变了）"

    /**
     * 「`max_tokens` 已经把落点线吃掉多少」那一句 —— 只在它真的吃穿了落点线时才出现。
     *
     * 真机形态：`ctx 4096 / 水位 90% / max_tokens 1024` 的落点线是 `3686 × 50% = 1843`，
     * 减掉 6% 余量后 1409 —— 而 `max_tokens` 自己就要 1024，历史只剩 385。
     * 用户算的是 `avail × 50% − gen`，读出 819，屏幕上却是 100 多，于是判定"算错了"。
     * 数字没错，缺的是这句话：**落点里 `gen` 是先占位的**，它越大历史越小。
     *
     * ⚠ 只在这条线被吃穿一半以上时说（`gen × 2 > line`）：否则每一轮都会多一截噪音，
     *   而"还能留几轮"那半句已经由 [roundsUntilNextDrop] 说了。
     */
    private fun genEatsLandingNote(p: Plan): String {
        if (!p.droppedToWatermark) return ""
        val avail = ContextWatermark.limitTokens(p.watermarkPct, p.ctx)
        if (avail <= 0) return ""
        val line = halfWatermarkTarget(avail, per = 0, wantGen = 0)
        if (line <= 0 || p.maxTokens * 2 <= line) return ""
        return "。注意：落点线是「prompt + gen」的合计 ${line} tok，`max_tokens` 那 " +
            "${p.maxTokens} tok 先占位，历史只剩 ${(line - p.maxTokens).coerceAtLeast(0)} tok —— " +
            "想留更多历史就调小 `max_tokens`，调水位没用（它也在这条线里）"
    }

    fun describe(p: Plan): String = when {
        p.overflow ->
            "上下文装不下：约 ${p.promptTokens} tok 的 prompt 塞不进 ctx ${p.ctx}" +
                "（要给生成留 ${MIN_GEN_TOKENS} tok）—— 请调大上下文长度或减少历史消息"
        // 「丢完了、但生成空间被削到几乎没有」单独一档（ISSUE #426）：那一轮的回答
        // 只有一两个字，而它读起来与"装得下 / 丢了几条"完全同形。`maxTokens` 一个
        // 数看不出这件事（削到 64 与削到 2 都是"生成上限"），差额必须自己说。
        // ⚠ 这一档也要把「丢了 N 条」说出来：它常与"越线丢一半 + gen 让到地板"同时发生，
        //   而只说"回答会很短"会把**丢了历史**这件事整个藏起来 —— 用户下一次调参
        //   （调 max_tokens）就调错了方向。
        p.maxTokens < KvLongChat.GEN_FLOOR ->
            "上下文预算：装得下，但生成空间只剩 ${p.maxTokens} tok（本轮回答会很短）" +
                (if (p.dropped > 0) "；已丢掉最老 ${p.dropped} 条消息" else "") +
                " —— 约 ${p.promptTokens} tok，ctx ${p.ctx}；调大上下文长度或减少历史消息"
        // 按落点丢（ISSUE #426 方案 B）单独一档：它与"丢到刚好装下"在 `dropped > 0`
        // 上完全同形，而**下一轮的行为相反** —— 一个隔几轮才再丢、一个几乎必然再丢。
        // 用户看到的正是这两者的差别（"之后的内容全没关系了" vs "只失忆一次"）。
        // ⚠ `watermarkPct > 0` 这一半不能省：容量未知那一档（`ctx <= 0`）的哨兵值是 0，
        //   没有这一半就会印出「越过水位 0%」这种自相矛盾的话（0% 根本不是可选档位）。
        p.dropped > 0 && p.droppedToWatermark && p.watermarkPct > 0 ->
            "上下文预算：越过水位 ${p.watermarkPct}% —— 丢掉最老 ${p.dropped} 条消息" +
                "（本轮照常回答），约 ${p.promptTokens} tok ≤ ${p.ctx}×${p.watermarkPct}%，" +
                "生成上限 ${p.maxTokens} —— " + roundsUntilNextDrop(p).let {
                    if (it > 0) "历史离下一次越线还空约 $it 轮的量，这中间 KV 前缀复用不会断"
                    else "历史还能再涨一截才又越线，这中间 KV 前缀复用不会断"
                } + genEatsLandingNote(p) + scaleNote(p)
        p.dropped > 0 ->
            "上下文预算：丢掉最老 ${p.dropped} 条消息（记忆变短，本轮照常回答），" +
                "约 ${p.promptTokens} tok，生成上限 ${p.maxTokens} —— 想保住历史请调大上下文长度" +
                "（可到 ${p.ctx}）或减少历史消息" + scaleNote(p)
        // 容量未知那一档（`ctx <= 0`）不许报"水位 0%"：那是"水位没参与判据"的
        // 哨兵值，写成百分数会被读成"水位设成了 0"，而 0% 根本不是可选值。
        p.ctx <= 0 ->
            "上下文预算：容量未知（未加载），不裁 —— 约 ${p.promptTokens} tok，" +
                "生成上限 ${p.maxTokens}"
        else -> "上下文预算：装得下，约 ${p.promptTokens} tok（水位 ${p.watermarkPct}%），" +
            "生成上限 ${p.maxTokens}" + scaleNote(p)
    }
}

/**
 * 水位百分数的**纯逻辑**归一（不带 Android 依赖，与 [ContextBudget] 一起编单测）。
 *
 * 为什么与 [ContextWatermark] 分成两个对象：那一份要读 `SharedPreferences`（Android），
 * 而水位是预算判据的**一级输入** —— 判据必须能在宿主上跑。所以"存哪"与"怎么归一"
 * 分开，归一这一份**只有一份**（[ContextWatermark.normalize] 转调它，不自己再钳一遍）。
 *
 * ⚠ 默认值 [ContextBudget.DEFAULT_WATERMARK_PCT] 与 [ContextWatermark.DEFAULT_PCT]
 *   必须同值，由守卫钉住。
 */
object ContextWatermarkPct {

    /**
     * 与 [ContextWatermark.LADDER] 同值同序（五档）。
     *
     * 95 那一档是 ISSUE #586 用户点名加的：「加大 ctx 的情况下，水位只到 0.9，有点浪费」。
     * 这一档的代价（每次丢得更狠、长回答更容易撞墙）由 [ContextWatermark.describe] 说清。
     */
    val LADDER = listOf(70, 80, 85, 90, 95)

    const val MIN_PCT = 50
    const val MAX_PCT = 95

    /**
     * 归一：先钳到 `50..95`，再**吸附到最近的档位**。
     *
     * 吸附的理由见 [ContextWatermark.normalize] 的注释：档位之外的数（83）在手感上
     * 与 85 没有区别，而它会让界面上出现"五档都不亮、当前值却是 83"这种说不清的状态。
     */
    fun normalize(value: Int): Int {
        // ⚠ `<= 0` 是"没设过/读错了"，不是"一个很小的值"：先钳到 50 再吸附会落到
        //   70 那一档 —— 于是"没设过的人"与"刻意调成 70 的人"在读数上同形，
        //   而默认值明明是 85。所以这一档必须先单独放行到默认值。
        if (value <= 0) return ContextBudget.DEFAULT_WATERMARK_PCT
        val v = value.coerceIn(MIN_PCT, MAX_PCT)
        return LADDER.minByOrNull { Math.abs(it - v) } ?: ContextBudget.DEFAULT_WATERMARK_PCT
    }
}
