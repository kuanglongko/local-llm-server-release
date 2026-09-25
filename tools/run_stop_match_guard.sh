#!/bin/sh
# 「stop 匹配判据必须按**整串**判定」的源码级守卫。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么必须有这一条（而不是靠单测）
# ═══════════════════════════════════════════════════════════════════════════
# G-1 是一个**没有异常、没有日志、HTTP 200** 的静默故障，两个方向同时存在：
#
#   漏判：`relevant` 枚举"stop 能从输出的哪个位置开始"，却要求匹配**从 `next[0]` 起**
#         （`next.compare(0, rem, ...)`）。于是 stop 起点落在 **token 内部**（偏移 > 0）
#         时整条判据看不见 —— 而 token 是 BPE piece，`</end>` / `abc\n` 都是常态。
#         表现：stop 完全不生效，正文继续吐给客户端（stream=true 时**收不回来**）。
#
#   假命中：`len(next) >= rem` 只比了前 rem 字节，却把**整个 token** 吞掉。
#         stop 不在结尾（"ENDING" 配 stop "END"）也被判成命中，token 里 stop
#         之后的内容一并丢失。表现：正文少一截，没有日志。
#
# 之所以长期全绿：旧单测**每个用例的 stop 都恰好从 token 边界开始**，这类输入下
# 两种口径恰好一致 —— 也就是说，它测的正是"恰好不会出错"的那个子集。
#
# 所以钉的是**结构关系**，不是"某个函数名出现过"：
#   ① 命中判据必须是**整串的结尾匹配**（`full` 以 stop 结尾），不许退化成
#      "只看 next 的前几字节"；
#   ② 暂扣判据必须是**整串的某个后缀是 stop 的真前缀**，不许退化成"full 整体是前缀"
#      （那样 `答</e` 会被判无关而放行，跨 token 的 stop 再也接不成）；
#   ③ 命中的丢弃范围必须精确到"末尾那段 stop"，不许整个 token 一并丢；
#   ④ 判据本体只允许一处（`stop_sequences.h`），且 `can_continue_fast` 与
#      `relevant` 必须建立在同一件事上（"待定那段是不是某条 stop 的前缀"）。
#
# 运行：bash tools/run_stop_match_guard.sh
set -e
cd "$(dirname "$0")/.."
SS=app/src/main/cpp/stop_sequences.h

ok=0; bad=0
c() { if eval "$2"; then echo "PASS  $1"; ok=$((ok+1)); else echo "FAIL  $1"; bad=$((bad+1)); fi; }

c "stop_sequences.h 存在" "[ -f \$SS ]"

# ── 1) 命中判据：必须按整串的**结尾**比对 ────────────────────────────────
# 结尾比较的写法只有一种形态：下标 = full.size() - s.size()。
c "relevant 里命中判据取整串结尾（full.size() - s.size()）" \
  "grep -q 'full.compare(full.size() - s.size(), s.size(), s, 0, s.size())' \$SS"
c "命中判据建立在 full = generated + withheld + next 之上" \
  "grep -q 'st.full() + next' \$SS"
# 旧的"只看 next 前几字节"写法必须绝迹：它正是漏判的根因。
c "旧的「next.compare(0, rem, ...)」写法已绝迹（漏判根因）" \
  "! grep -q 'next.compare(0, rem, s, have, rem)' \$SS"
c "旧的「next.compare(0, next.size(), ...)」写法已绝迹" \
  "! grep -q 'next.compare(0, next.size(), s, have, next.size())' \$SS"
# 旧的"枚举 cur 的各个后缀"写法也必须绝迹：那是"停错方向"的一半。
c "旧的「对 cur 枚举后缀长度 have」写法已绝迹" \
  "! grep -qE 'size_t have = \(cur.size\(\) < s.size\(\)' \$SS"
c "不再出现对 cur 尾部的 k = cur.size() - have 锚点枚举" \
  "! grep -q 'const size_t k = cur.size() - have;' \$SS"

# ── 2) 暂扣判据：**某个后缀**是 stop 的真前缀（不是 full 整体） ───────────
# 这是"stop 起点落在 generated 内部"能成立的全部原因，单独钉住。
# 必须锚在 **relevant 的函数体**里：这段比对在 split_release_withhold 里也有，
# 只 grep 全文的话，把 relevant 那一处改回旧写法（"full 整体是前缀"）也不会红 ——
# 那是本守卫最该抓住的一条。
c "relevant 体内的暂扣判据按后缀扫描（full[i:] 是 s 的真前缀）" \
  "python3 - \$SS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('inline bool relevant(')
j = s.index('\n}\n', i)
body = s[i:j]
ok = ('full.compare(i, tail, s, 0, tail)' in body) and ('full.compare(0, tail, s, 0, tail)' not in body)
sys.exit(0 if ok else 1)
PY"
c "relevant 体内暂扣判据要求 tail < s.size()（真前缀，不是整条命中）" \
  "python3 - \$SS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('inline bool relevant(')
j = s.index('\n}\n', i)
sys.exit(0 if 'if (tail >= s.size()) continue;' in s[i:j] else 1)
PY"
c "split_release_withhold 负责把 generated 的尾巴回退进 withheld" \
  "grep -q 'inline void split_release_withhold' \$SS"
c "advance 用 split_release_withhold 推进暂扣（不是裸 withheld += next）" \
  "grep -q 'split_release_withhold(st, next);' \$SS"
c "旧的裸累加写法已绝迹（withheld += next 单独成句）" \
  "! grep -qE '^[[:space:]]*st.withheld \\+= next;' \$SS"

# ── 3) 命中丢弃范围：只丢末尾那段 stop，不丢整个 token ──────────────────
c "命中用 hit_cut_index 算切点（只减掉末尾那条 stop）" \
  "grep -q 'inline size_t hit_cut_index' \$SS"
c "advance 命中分支按 hit_cut_index 切，而不是清空 withheld" \
  "grep -q 'st.generated = full.substr(0, hit_cut_index(st, next));' \$SS"
# advance 命中分支里不得只设置 hit 而不重建 generated（那样整个 token 被吞）。
c "命中分支在**同一个函数体**里既设置 hit 又重建 generated" \
  "python3 - \$SS <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('inline bool advance(MatchState & st')
j = s.index('\n}\n', i)
body = s[i:j]
k = body.index('if (exact) {')
m = body.index('st.hit = true;', k)
seg = body[k:m]
ok = ('hit_cut_index(st, next)' in seg) and ('full.substr' in seg)
sys.exit(0 if ok else 1)
PY"

# ── 4) 判据本体唯一，且两处判据建立在同一件事上 ──────────────────────────
N=$(grep -c 'inline bool relevant(' "$SS")
c "relevant 定义点唯一（实际 $N 处）" "[ \"\$N\" = \"1\" ]"
# can_continue_fast 必须按 withheld 是"某条 stop 的真前缀"来判，不许拿 generated 去算。
c "can_continue_fast 按 withheld 与 stop 的前缀关系判" \
  "grep -q 'is_prefix_of(s, st.withheld)' \$SS"
c "can_continue_fast 不再拿 generated 去枚举后缀（旧写法绝迹）" \
  "! grep -q 'const std::string cur = st.full();' \$SS"
c "can_continue_fast 取续接字节用 withheld.size() 下标" \
  "grep -q 's\[st.withheld.size()\]' \$SS"

# ── 4.5) 与 nativeStep 的 emitted 账必须写明耦合 ─────────────────────────
# split_release_withhold 会让 generated **变短**，而 emitted 是"已下发"的账。
# 两者关系不清，后人很容易把钳制当成"正常路径"而漏掉这段耦合。
JNI=app/src/main/cpp/llama_jni.cpp
c "llama_jni.cpp 写明 split_release_withhold 与 emitted 的耦合" \
  "grep -q 'split_release_withhold' \$JNI"
c "llama_jni.cpp 写明回退不得越过已下发界线" \
  "grep -q '回退不得越过' \$JNI || grep -q '已下发.界线' \$JNI"
c "llama_jni.cpp 保持 emitted 钳制（异常兜底）" \
  "grep -q 'if (c->emitted > g.size()) c->emitted = g.size();' \$JNI"
c "契约里指到了钉它的行为测（run_stop_match_tests.sh）" \
  "grep -q 'run_stop_match_tests.sh' \$JNI"

# ── 5) 契约注释在位（防止后人"顺手简化"回旧写法） ───────────────────────
c "写明 stop 必须出现在输出结尾（命中只认结尾）" \
  "grep -q '必须出现在输出\*\*结尾\*\*' \$SS"
c "写明"后缀"这一支为什么必须存在（generated 收不回来）" \
  "grep -q '收不回来' \$SS"
c "写明旧实现漏判的具体形态（锚点只能落在 next\[0\]）" \
  "grep -q 'next.compare(0, rem' \$SS"
c "写明旧实现假命中的具体形态（ENDING / END）" \
  "grep -q 'ENDING' \$SS"
c "MatchState 的 withheld 不变式写明「generated 里可能含 stop 的前缀」" \
  "grep -q 'generated\` 里可能\*\*含有\*\*某条 stop 的前缀' \$SS"

# ── 6) 宿主测必须真的构造"起点在 token 内部"这一类输入 ──────────────────
# 只钉"测试文件里有 G-1 字样"是存在性断言（恒真）。这里钉的是**多字符 token**：
# 全部用例都是单字符 token 时，两种口径恰好一致 —— 那正是旧单测全绿的原因。
TEST=tools/stop_sampler_test.cpp
c "宿主测存在" "[ -f \$TEST ]"
c "宿主测构造了「stop 在 token 内部结尾」的多字符 token 用例" \
  "grep -q 'G-1a：stop 在 token 内部结尾' \$TEST"
c "宿主测构造了「stop 在 token 内部」的用例" \
  "grep -q 'G-1a：stop 在 token 内部（x\`\`\` 配 stop' \$TEST || grep -q 'G-1a' \$TEST"
c "宿主测构造了「stop 不在结尾」的假命中用例" \
  "grep -q 'G-1b：stop 不在结尾' \$TEST"
c "宿主测构造了「起点在已放行区内」的用例" \
  "grep -q '起点在已放行区内' \$TEST"
# token 粒度必须是**整个字符串**（不能用逐字符喂来掩盖"起点在 token 内部"）。
# run 与 run_stream 两条路径都要按 pieces 迭代 —— 只钉一处的话，另一处退回逐字符
# 就没人管，而 stream 侧恰恰是"前缀泄漏"最容易露出来的地方。
c "宿主测的 run 按整个 token 文本喂状态机" \
  "python3 - \$TEST <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('static StepResult run(const std::vector<std::string> & stops,')
j = s.index('\n}\n', i)
sys.exit(0 if 'for (const std::string & p : pieces)' in s[i:j] else 1)
PY"
c "宿主测的 run_stream 也按整个 token 文本喂状态机" \
  "python3 - \$TEST <<'PY'
import sys
s = open(sys.argv[1], encoding='utf-8').read()
i = s.index('static StepResult run_stream(const std::vector<std::string> & stops,')
j = s.index('\n}\n', i)
sys.exit(0 if 'for (const std::string & p : pieces)' in s[i:j] else 1)
PY"
c "宿主测没有把 pieces 逐字符喂进状态机（掩盖 token 内部起点）" \
  "! grep -q 'for (char c : p' \$TEST"

if [ "$bad" -eq 0 ]; then
    echo "=== stop 匹配判据守卫 全部通过（$ok 条）==="
    exit 0
else
    echo "=== stop 匹配判据守卫 $bad 条失败（共 $ok 条）==="
    exit 1
fi
