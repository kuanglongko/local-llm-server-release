// stop_sequences.h — OpenAI `stop` / `stop_sequences` 的**匹配语义**（纯函数，无 JNI / llama 依赖）
//
// ═══════════════════════════════════════════════════════════════════════════
// 为什么把匹配逻辑单独拆一个头文件
// ═══════════════════════════════════════════════════════════════════════════
// 这套逻辑太容易"看着对、实际吞字"了，而它在真机上的表现形式是
// **输出莫名少了一截**（没有异常、没有日志），属于极难归因的一类故障。
// 拆出来之后宿主侧可以直接编译**同一份源码**跑断言
// （tools/stop_sampler_test.cpp / tools/run_stop_sampler_tests.sh），
// 不用 NDK、不用设备、不用模型 —— 而不是在测试里重抄一遍逻辑（重抄就等于没测）。
//
// 本文件只做两件事：判断「这个候选 token 文本会不会进到某条 stop 的匹配里」，
// 以及「被暂扣的前缀在词表里还有没有可能继续」。采样器外壳（apply/accept，
// 也就是"暂扣期间要不要 accept""命中怎么结束本轮"）留在 llama_jni.cpp，
// 因为它与 Session/链的耦合是真实的，测试不了也没有必要测。
#ifndef LOCAL_LLM_STOP_SEQUENCES_H
#define LOCAL_LLM_STOP_SEQUENCES_H

#include <algorithm>
#include <string>
#include <vector>

namespace stopseq {

/**
 * 匹配状态。字段含义与约束写在这里，避免调用方误解：
 *
 * - [generated] 已**放行**（会下发给客户端、也会进 KV）的输出；
 * - [withheld]  已被暂扣、等待继续匹配的尾部。它还没下发、也没进 KV。
 *               命中时**整段丢弃**；确认无法继续匹配时整体并入 [generated]；
 * - [hit]       已命中 stop，本轮生成应立刻结束。
 *
 * 不变式：`withheld` 一定是 [stops] 里某一条的真前缀（非空时）。
 * 破坏它的后果是吞掉本该输出的正文，所有改动都要保住这一条。
 *
 * 注意 `generated` 里可能**含有**某条 stop 的前缀 —— 一条 stop 完全可能从
 * `generated` 的中途开始、在 `withheld` 里收尾（例：stop=`</end>`，输出到
 * `答</e` 时 `答` 已放行、`</e` 待定）。此时若把 `</e` 判成"无关"而放行，
 * 下一个 token 到来时就再也接不成 stop 了。判定必须按**整串 `full()` 的后缀**
 * 来做，这正是 [relevant] 的 `exact=0` 那一支。
 */
struct MatchState {
    std::vector<std::string> stops;
    std::string generated;
    std::string withheld;
    bool hit = false;

    void reset() {
        generated.clear();
        withheld.clear();
        hit = false;
    }

    /** 当前待匹配的文本 = 已放行 + 暂扣。 */
    std::string full() const { return generated + withheld; }
};

/** 前缀判定（`pre` 是否等于 `s` 的开头）。 */
inline bool is_prefix_of(const std::string & s, const std::string & pre) {
    if (pre.size() > s.size()) return false;
    return std::equal(pre.begin(), pre.end(), s.begin());
}

/**
 * 候选文本 `next` 会不会"进入"某条 stop 的匹配？
 *
 * 判定对象是 **`full = generated + withheld + next` 这一整串**，返回值：
 *   false            -> 与所有 stop 都无关，正常放行；
 *   true, *exact=1   -> `full` 的**末尾正好是**某条 stop：命中，把末尾那段 stop 丢弃并结束；
 *   true, *exact=0   -> `full` 的**某个后缀**是某条 stop 的**真前缀**：还看不出来，先暂扣。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么"结尾"与"后缀"这两件事必须分开说清楚
 * ═══════════════════════════════════════════════════════════════════════════
 * 命中看的是**整个输出串的结尾**：`stop` 是"必须出现在输出**结尾**"的串（OpenAI 语义，
 * 也是 Kotlin 侧 `StopSequences.truncate` 的语义）。所以只有 `full.endswith(s)` 才算命中；
 * `s` 出现在 `full` 中间、后面还有别的字节时**不能**算命中 —— 那会把后面的正文一起吞掉。
 * 这正是旧实现的一处假命中（"ENDING" 配 stop "END"）。
 *
 * 暂扣看的是**任意后缀**，而不是"full 整体"。这一点是旧实现唯一做对、却极容易被
 * "顺手简化"掉的地方，所以单独写一条：`generated` 里的字节**已经下发给客户端了**
 * （见 stop_sampler_take_released 的 emitted 账），收不回来；但一条 stop 完全可能
 * **从 `generated` 的中途开始**，只在 `withheld + next` 里收尾。例如
 * stop = `</end>`、输出到 `答</e` —— `full = "答</e"` 整体当然既不以 `</end>` 结尾、
 * 也不是 `</end>` 的前缀，可它的**后缀** `</e` 是；如果这里判"无关"而把 `</e` 放行，
 * 下一个 token `nd>` 到来时就再也接不成 stop 了（正文里会多吐一个 `</end>`）。
 * 所以暂扣的判据是：**存在某个起点 i，使 full[i:] 是某条 stop 的真前缀**。
 *
 * 旧实现在这一支上的错法是：它枚举了"stop 能从哪个位置开始"，但随后要求匹配
 * **从 `next[0]` 起**（`next.compare(0, rem, ...)`）。于是 stop 起点落在 `next`
 * **内部**（偏移 > 0）时整条判据看不见 —— 而 `next` 是整个 token 的文本
 * （BPE 一个 piece 多字符，`</end>`、`abc\n` 都是常态）。表现为 stop 完全不生效、
 * 正文继续吐，且不报错。旧单测**每个用例的 stop 都恰好从 token 边界开始**，
 * 这类输入下两种口径恰好一致，所以它测的是"恰好不会出错"的那个子集。
 */
inline bool relevant(const MatchState & st, const std::string & next, bool * exact) {
    *exact = false;
    if (next.empty()) return false;
    const std::string full = st.full() + next;
    // 两趟扫描，顺序不能反：**先找完全命中，再找真前缀**。
    //
    // `stop: ["abc", "ab"]` 而输出正好到 "ab" 时，"ab" 已经是一条完整的 stop，
    // 同时又是 "abc" 的真前缀 —— 单趟扫描会先碰到 "abc" 判成"继续等"，
    // 于是 ab 这条 stop 要晚一个 token 才生效（多吐一个 token 的内容）。
    // 命中类的结果优先级高于"再等等"，这条不写清楚很容易被后人合并回一趟。
    for (const std::string & s : st.stops) {
        if (s.empty()) continue;
        if (full.size() < s.size()) continue;
        // 结尾比较：只认 `full` 的**末尾**，不认中间出现。
        if (full.compare(full.size() - s.size(), s.size(), s, 0, s.size()) == 0) {
            *exact = true;
            return true;
        }
    }
    for (const std::string & s : st.stops) {
        if (s.empty()) continue;
        // 找出"能构成某条 stop 真前缀"的那个起点 i：full[i:] 必须是 s 的真前缀。
        // 起点必须留一个字节出来（full.size() - i < s.size()），否则那是整条命中，
        // 已经在上一趟里处理过了。
        for (size_t i = 0; i < full.size(); i++) {
            const size_t tail = full.size() - i;
            if (tail >= s.size()) continue;
            if (full.compare(i, tail, s, 0, tail) == 0) return true;
        }
    }
    return false;
}

/**
 * 命中时"该从哪个下标把 `full` 切开"：返回末尾那条 stop 的起点。
 *
 * 必须只依赖**结尾匹配**：一条命中意味着 `full` 以某条 stop 结尾。同时有多条
 * 都能结尾时取**最靠前**的起点（等价于 Kotlin 侧 `truncate` 取首个命中点），
 * 这样两侧对同一段输出给出同一个切点。找不到时返回 `full.size()`（不切）。
 */
inline size_t hit_cut_index(const MatchState & st, const std::string & next) {
    const std::string full = st.full() + next;
    size_t cut = full.size();
    for (const std::string & s : st.stops) {
        if (s.empty() || s.size() > full.size()) continue;
        if (full.compare(full.size() - s.size(), s.size(), s, 0, s.size()) != 0) continue;
        const size_t at = full.size() - s.size();
        if (at < cut) cut = at;
    }
    return cut;
}

/**
 * 把 `full` 切成「已确定放行」与「仍待定的后缀」两段，**总是**保持不变式：
 * `withheld` 为空，或 `withheld` 是某条 stop 的真前缀。
 *
 * 为什么需要它、而不是只在暂扣分支里 `+=`：一条 stop 可能从 `generated` 的中途
 * 开始（见 [relevant] 的说明）。此时"该暂扣的起点"落在 `generated` 内部，
 * 必须把 `generated` 的尾巴**回退**到 `withheld` 里 —— 只做 `withheld += next`
 * 会把起点留在 `generated` 里，下一步的 `full` 就再也凑不出那条 stop 了。
 *
 * 暂扣长度取**最长**的那个后缀：越长代表匹配走得越远，能接上的可能越具体；
 * 取短的那个会在下一步把本该继续匹配的字节提前放行（等于漏拦）。
 */
inline void split_release_withhold(MatchState & st, const std::string & next) {
    const std::string full = st.full() + next;
    size_t keep = 0;                                  // 后缀里要留下多少字节
    for (const std::string & s : st.stops) {
        if (s.empty()) continue;
        for (size_t i = 0; i < full.size(); i++) {
            const size_t tail = full.size() - i;
            if (tail >= s.size()) continue;           // 整条命中不属于"待定"
            if (full.compare(i, tail, s, 0, tail) == 0 && tail > keep) keep = tail;
        }
    }
    st.withheld.assign(full, full.size() - keep, keep);
    st.generated.assign(full, 0, full.size() - keep);
}

/**
 * 状态机的**唯一**推进一步：喂入一个候选 token 的文本，返回它是否已被放行下发。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 为什么这一步必须放在头文件里（而不是留在 llama_jni.cpp 的 accept 里）
 * ═══════════════════════════════════════════════════════════════════════════
 * 因为它曾经被写错过，而且是**没有异常、没有日志**地写错：
 * 早退写成"暂扣非空就直接 return"，于是多 token 的 stop（如 `</end>` 拆成
 * `</e` + `nd>`）第一步暂扣之后，accept 每步都在第一行返回 ——
 * 既不再调用 [relevant]（永远走不到 exact，**stop 永不命中**），
 * 也不再做 continuation 自检（暂扣部分**永不放行**）。状态机死在那里。
 *
 * 而当时的测试是**自己重抄了一遍 accept 的决策顺序**（注释里还写着"刻意复刻"），
 * 恰好抄漏了那条早退 —— 于是线上卡死、测试全绿。这个教训值得写在这里：
 * 采样器外壳可以留在 llama_jni.cpp（它耦合 Session/链，测不了），
 * 但**状态机本身必须与测试共用同一份源码**，否则"复刻"迟早会分叉。
 *
 * 返回值语义：
 *   true  -> 该 token 的文本已放行，调用方应把它下发给客户端（并进 KV）；
 *   false -> 不下发：可能是被暂扣（等下一步），也可能是命中 stop（看 [hit]）。
 *
 * [has_prefix_byte] 见 [can_continue_fast]。`heads_ready=false` 时保守认为
 * "总能继续"，宁可晚一点命中也绝不吞正文。
 */
template <typename HasPrefixByteFn>
inline bool advance(MatchState & st, const std::string & next, HasPrefixByteFn has_prefix_byte,
                    bool heads_ready) {
    if (st.hit || next.empty()) return false;
    // 没有 stop 序列 = 本采样器不该拦任何东西：一律放行。
    // 注意**不能**在这里 `return false` —— 那会让该 token 被静默吞掉
    // （采样器只在返回 true 时下发），表现为"带 stop 的请求输出少一截"。
    // 线上不会挂这个采样器（stops 为空时不入链），但状态机自己必须是对的。
    if (st.stops.empty()) { st.generated += st.withheld + next; st.withheld.clear(); return true; }
    bool exact = false;
    if (!relevant(st, next, &exact)) {
        // 与所有 stop 都无关 -> 暂扣部分至此已被证明不是任何 stop 的后缀前缀，
        // 连同本 token 一起放行（withheld 的语义是"还没确定"，不是"丢弃"）。
        st.generated += st.withheld;
        st.withheld.clear();
        st.generated += next;
        return true;
    }
    if (exact) {
        // 命中：`full = generated + withheld + next` 的**结尾正好是**某条 stop。
        //
        // 丢弃范围必须精确到"末尾那段 stop"，**不是**"整个 token"，也不是
        // "withheld + 整个 token"：
        //   · 丢弃整个 token 会把 token 里 stop **之前**的正文一起吞掉
        //     （token 是 BPE piece，`答</end>` 这种"内容 + stop"同处一个 token 是常态）；
        //   · 丢掉 `withheld + next` 会把"被暂扣的、其实属于正文的前缀"一起吞掉，
        //     但它同样是正文（withheld 只是"还没确定"，不是"已丢弃"）。
        //
        // 正确做法：在 `full` 上从**末尾**把那条 stop 减掉，剩下的就是真实正文。
        // 它可能是本次 token 的一部分（`答</end>` -> 正文 `答`），也可能横跨
        // `withheld`（`</e` 暂扣 + `nd>` 收尾），两种都由这一次减法统一处理。
        //
        // **必须显式清空 withheld** —— 它是"待下发文本"的暂存区，命中后
        // 若留着，调用方按 generated + withheld 取正文时会把该丢的 stop 前缀
        // 又拼回输出里（正是"stop 串出现在结果里"的经典 bug）。
        // 注意 `full()` **不含 next**（它是"已放行 + 暂扣"），切点算的是
        // `full() + next`，所以先把整串取出来再切 —— 直接对 `full()` 切会把
        // 本 token 的正文整段丢掉（`abc\n` 配 stop `\n` 会切出空串）。
        const std::string full = st.full() + next;
        st.generated = full.substr(0, hit_cut_index(st, next));
        st.withheld.clear();
        st.hit = true;
        return false;
    }
    // 只看得到"某条 stop 的后缀前缀"：继续暂扣。
    //
    // 这里**不能**写成 `st.withheld += next`：一条 stop 可能从 `generated` 的中途
    // 开始（`答</e` 里 `</e` 才是那条 stop 的起点），起点必须能被回退进 `withheld`，
    // 否则下一步凑不出完整 stop。`split_release_withhold` 负责这次回退 + 重切。
    split_release_withhold(st, next);
    // 自检：词表里若没有能继续该前缀的 token，暂扣会变成永久吞字 —— 立刻放行。
    //
    // heads_ready=false 是**线上可能出现的中间态**（位图还没算完）：此时
    // has_prefix_byte 无意义，必须当"总能继续"处理 —— 保守、宁可晚命中也绝不吞字。
    const bool alive = !heads_ready || can_continue_fast(st, has_prefix_byte);
    if (!alive) {
        st.generated += st.withheld;
        st.withheld.clear();
        return true;                              // 原本就是正文，只是晚一个 token 下发
    }
    return false;
}

/**
 * 被暂扣的文本在词表里还有没有可能继续（采样器自检用）。
 *
 * 为什么要这一条：「暂扣」只有在**后续确实有 token 能继续该前缀**时才是安全的。
 * 如果词表里根本拼不出 continuation，暂扣就变成了永久吞字。宁可漏一次 stop、
 * 也不能吞正文，所以这里返回 false 时调用方必须**立刻把 withheld 放行**。
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * 性能：这里**不能**遍历整个词表
 * ═══════════════════════════════════════════════════════════════════════════
 * 朴素写法是"对全量词表的每个 token 调一次 token_to_piece 再判前缀"。
 * 词表 15 万级、且 `token_to_piece` 每次都要走一次 `llama_token_to_piece`
 * （可能堆分配），而暂扣的每一步都会调一次 —— 生成速度会被直接拖垮，
 * 而且这段代码在**每一个用户请求**上都会跑到。所以：
 *
 * 1. 只对**相关的 stop** 做判定：O(候选字节数)，与词表规模无关；
 * 2. 候选集从"全量 token"收窄成由调用方给出的**字节级续接**判定。
 *
 * 第 2 点是关键 —— 我们真正要问的是「`withheld` 后面还接得上吗」，
 * 而这件事在**字节层面**就能看出来：stop 的下一个字节是 c，
 * 那么必须存在某个 token 的 piece 以 c 开头。调用方因此只需要提供
 * "是否存在以某字节开头的 token piece" 这个 O(1) 查询（`has_prefix_byte`），
 * 它由采样器在**挂载时**扫一次词表算出（见 llama_jni.cpp），
 * 每步生成只剩下常数开销。
 *
 * 保守性不变：拿不准就返回 true。漏拦只多吐一点，吞正文才是不可接受的。
 */
template <typename HasPrefixByteFn>
inline bool can_continue_fast(const MatchState & st, HasPrefixByteFn has_prefix_byte) {
    if (st.withheld.empty()) return true;
    // 暂扣期间的不变式是「`withheld` 是某条 stop 的**真前缀**」（见
    // split_release_withhold：只有这种情形才会留下 withheld）。于是"能不能继续"
    // 就是问：`withheld` 还没对上的那一个字节，词表里有没有 token 以它开头。
    //
    // 直接用 `withheld`，**不要**去看 `generated` —— 与 [relevant] 的"后缀"判据
    // 保持同一件事：能给出续接字节的只有"待定的那一段"，`generated` 已经下发，
    // 拿它去算会得出与状态机不同的结论（这正是旧版"两处判据各写一套"的形态）。
    for (const std::string & s : st.stops) {
        if (s.empty()) continue;
        if (!is_prefix_of(s, st.withheld)) continue;      // 不是这条 stop 的前缀：与它无关
        if (st.withheld.size() >= s.size()) return false;  // 已经写完整条 stop，不存在"继续"
        const unsigned char need = (unsigned char) s[st.withheld.size()];
        if (has_prefix_byte(need)) return true;            // 有 token 能接上这个字节
    }
    // 所有相关 stop 的下一个字节都没有 token 能以此为开头 -> 确实接不上了
    return false;
}

}  // namespace stopseq

#endif  // LOCAL_LLM_STOP_SEQUENCES_H
