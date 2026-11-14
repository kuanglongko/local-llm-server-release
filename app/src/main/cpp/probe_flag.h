// ============================================================================
//  probe_flag.h —— 探针开关判据（纯逻辑，宿主可编）
// ============================================================================
// 为什么单独拆一份（与 probe_util.h / stop_sequences.h 同样的理由）：
// 本文件在宿主上**能真编**，所以跑的是与真机编进去的同一份代码，
// 而不是「另写一份近似去验证另一份近似」—— 判据漂移正是这样发生的。
//
// 两条判据各自钉一个历史故障：
//
//   ① probe_flag_off —— 自举开关此前是死代码（kProbeFlagProps 定义了、永不被读）。
//      用户在设置页关掉探针，native 侧照样全量落盘并把日志 sink 换成空操作。
//      判据**故意只接受「明确的否」**：见 kProbeOffValues 的注释。
//
//   ② probe_bootstrap_write —— 自举阶段只记不写。
//      旧写法在自举时直接落盘、随即清空自举缓冲，于是「回灌到正式文件」
//      永远只写 0 字节 —— 一个不可能成立的组合，而 Kotlin 侧的排查指令
//      （「probe-native.log 里应该有一条 [boot]」）正建立在这个空回灌上。
//      现在自举缓冲的唯一归宿是第一次 nativeProbeInit，且缓冲消费只能发生一次。
// ============================================================================
#pragma once

#include <cstddef>

// ── ① 显式关闭判据 ──────────────────────────────────────────────────────────
// vals 为候选值（来自 kProbeFlagProps 逐个 getprop 的原始文本），n 为候选个数，
// offVals / offN 为「视为关闭」的取值集合（不区分大小写）。
// 返回 true = 用户显式要求关闭；false = 没表态（**不是**「要求打开」）。
// 注意与 probe_prop_has() 的区别：那个问「属性在不在」，这个问「属性说了什么」——
// 开关必须看**值**，只看「在不在」的话 `setprop ...probe.on 0` 会被读成开启。
static bool probe_flag_off(const char * const * vals, size_t n,
                           const char * const * offVals, size_t offN) {
    for (size_t i = 0; i < n; i++) {
        const char * v = vals[i];
        if (!v) continue;
        // 值本身不原地改写（调用方给的是 kProbeFlagProps 读出来的缓冲，可能还有别处要用），
        // 逐字符比时统一转小写。
        for (size_t k = 0; k < offN; k++) {
            const char * o = offVals[k];
            if (!o) continue;
            // 逐字符不区分大小写比较。两个约束：
            //   · v 的**尾部空白**忽略（getprop 经过 pipe 回来时常带换行）；
            //   · v 的**中间不得有空白** —— 值是一个词，不是句子。
            //     （曾经写成"遇到空白就 break 当相等"，于是 `n one` 也被判成 `none`。）
            size_t j = 0;          // o 的游标
            size_t t = 0;          // v 的游标
            bool same = true;
            for (; o[j]; j++, t++) {
                if (!v[t]) { same = false; break; }                 // v 比 o 短
                char a = v[t], b = o[j];
                if (a == ' ' || a == '\t' || a == '\r' || a == '\n') { same = false; break; }
                if (a >= 'A' && a <= 'Z') a = (char) (a - 'A' + 'a');
                if (b >= 'A' && b <= 'Z') b = (char) (b - 'A' + 'a');
                if (a != b) { same = false; break; }
            }
            // o 比完了，且 v 的剩余部分只能有空白 → 相等
            if (!same) continue;
            bool tail_blank = true;
            for (; v[t]; t++) {
                char c = v[t];
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') { tail_blank = false; break; }
            }
            if (tail_blank) return true;
        }
    }
    return false;
}

// ── ② 自举事件缓冲：只记不写，且只能被消费一次 ──────────────────────────────
// 语义（三态，不能合并且不能更省）：
//   · log 为空 / len == 0     → 没有可交代的事件（Nothing）
//   · log 非空 / tries >= cap → 已经交代过了（Consumed），**不再重复交代**
//   · log 非空 / tries < cap  → 可交代（Writable），返回需要写入的字节数
// "只消费一次" 不是洁癖：自举事件里含「目录来源」「文件路径」，重复落盘
// 会让同一段现场出现两遍，而排障者按行数推断「自举跑了几次」就会读错。
static size_t probe_bootstrap_write(char * log, size_t len, int * tries, int cap) {
    if (!tries) return 0;
    if (len == 0) return 0;          // Nothing：没有早期事件
    if (*tries >= cap) return 0;     // Consumed：已经交代过
    (*tries)++;
    (void) log;
    return len;
}

// 自举阶段允许读的开关属性条数（kProbeFlagProps 的长度）。
// 写成常量而不是 sizeof，是为了让「读了几条」这件事在守卫里可断言。
static const size_t kProbeFlagPropCount = 2;
