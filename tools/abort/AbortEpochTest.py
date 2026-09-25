#!/usr/bin/env python3
# 「取消（abort）的归属与账本作废」的宿主侧语义复刻测试（模块 E：E-1 / E-2 / E-3 / E-5）。
#
# ═══════════════════════════════════════════════════════════════════════════
# 为什么是"复刻"而不是直接编译 llama_jni.cpp
# ═══════════════════════════════════════════════════════════════════════════
# `llama_jni.cpp` 依赖 llama.h / ggml 的真实实现与 NDK，宿主上编不过（本 Runner 里
# 连 g++ 都没有）。而本轮这四条**没有一条会让别的测试变红**：
#   · E-1b  错停对象：症状是"别人的请求被掐断"，没有任何异常或日志；
#   · E-2   脏账本被复用：症状是"答非所问"，接口 200、不报错；
#   · E-3   读数撒谎：只在"prefill 被打断"的那一瞬；
#   · E-5   两种处境同形：只有同时读 kv_valid 与 kv_rounds 才分得出来。
# 所以这里把 **nativeAbort / startCompletion / step 的控制流逐字复刻**成可运行程序，
# 用**真状态机**（不是打桩）去构造这几种交错，每条断言都能报出它抓的是什么。
#
# 复刻与实现逐条对应（写在 `Native` 里），并由 `run_llama_jni_abort_guard.sh`
# 的静态断言保证实现里真有这几条判据（复刻/实现脱钩时那里会红）。
#
# 运行：python3 tools/abort/AbortEpochTest.py
fail = 0
def ck(name, cond):
    global fail
    if cond:
        print(f"PASS  {name}")
    else:
        print(f"FAIL  {name}")
        fail += 1


class Native:
    """llama_jni.cpp 的 Session + nativeAbort + startCompletion 语义复刻。

    逐条对应：
      · round_epoch / abort_epoch / abort  <-> Session::{round_epoch, abort_epoch, abort}
      · kv_valid / last_tokens / last_reuse / last_prefill  <-> Session 同名字段
      · kv_rounds / n_used / gen_in_ledger  <-> Session 同名字段
      · abort(epoch)                        <-> nativeAbort(roundEpoch)
      · start_completion(cur)               <-> nativeStartCompletion
      · step()                              <-> nativeStep
    """

    K_MIN_REUSE = 16  # 与 kv_prefix.h 的 kMinReuseTokens 同值

    def __init__(self):
        self.round_epoch = 0
        self.abort_epoch = 0
        self.abort = False
        self.ctx_ok = True
        self.kv = []              # 模拟 KV 里的 token 位置（la llama_memory）
        self.kv_valid = False
        self.last_tokens = []
        self.last_reuse = 0
        self.last_prefill = 0
        self.kv_rounds = 0
        self.n_used = 0
        self.gen_in_ledger = 0
        self.trace = []

    # ---- kv_invalidate（与实现同名同语义；注意**不**动 kv_rounds）----
    def kv_invalidate(self, why):
        if self.kv_valid:
            self.trace.append(f"kv_invalidate: {why}")
        self.kv_valid = False
        self.last_tokens = []
        self.last_reuse = 0
        self.last_prefill = 0
        # 关键：不清 kv_rounds（E-5）

    # ---- nativeAbort(roundEpoch) ----
    def abort_round(self, round_epoch):
        cur = self.round_epoch
        if round_epoch == 0 or round_epoch != cur:
            self.trace.append(f"abort 被忽略：归属 {round_epoch} != 当前轮次 {cur}")
            return False
        self.abort_epoch = cur
        self.abort = True
        # E-2 的核心一笔：作废账本（残留不作复用候选）
        self.kv_invalidate("本轮被 abort（残留不作复用候选）")
        self.trace.append(f"abort 轮次 {cur}")
        return True

    # ---- plan_reuse（kv_prefix.h 的保守近似：只按最长公共前缀与门槛）----
    def plan(self, cur):
        if not self.kv_valid or not self.last_tokens or not cur:
            return 0
        lcp = 0
        for a, b in zip(self.last_tokens, cur):
            if a != b:
                break
            lcp += 1
        if lcp < self.K_MIN_REUSE:
            return 0
        reuse = min(lcp, len(cur) - 1)
        return reuse if reuse >= self.K_MIN_REUSE else 0

    # ---- startCompletion ----
    def start_completion(self, cur, max_tokens=64, abort_during_prefill_at=None,
                         decode_fail_at=None):
        # 起手：先领编号，再清 abort（顺序与实现一致）
        self.round_epoch += 1
        self.abort = False
        self.abort_epoch = 0
        self.gen_in_ledger = 0

        reuse = self.plan(cur)
        if reuse > 0:
            # 命中：KV 截到 reuse
            del self.kv[reuse:]
            self.n_used = reuse
            self.last_reuse = reuse
        else:
            self.kv = []            # seq_rm(0,-1,-1) —— 残留的真正出口
            self.n_used = 0

        # abort（取消）判定：缓存应用段之后、任何 decode 之前
        if self.abort:
            self.kv_invalidate("本轮开始即被 abort")
            return False

        # prefill
        done = 0
        off = reuse
        while off < len(cur):
            if abort_during_prefill_at is not None and done >= abort_during_prefill_at:
                self.abort = True
            if self.abort:
                # E-3：**保留** n_used 实况，不再写死 0
                self.trace.append(f"prefill 被中断，KV 里 {self.n_used} tok")
                self.kv_invalidate("prefill 被中断")
                return False
            n = min(8, len(cur) - off)
            if decode_fail_at is not None and done >= decode_fail_at:
                self.kv_invalidate("prefill decode 失败")
                return False
            self.kv.extend(cur[off:off + n])
            self.n_used += n
            done += n
            off += n

        self.last_prefill = len(cur) - reuse
        self.last_reuse = reuse
        self.kv_rounds += 1        # E-5：跑完 prefill 记一轮
        self.last_tokens = list(cur)
        self.kv_valid = True
        return True

    # ---- step ----
    def step(self, tok):
        if not self.ctx_ok or self.abort:
            return None
        self.kv.append(tok)
        self.n_used += 1
        if self.kv_valid:
            self.last_tokens.append(tok)
            self.gen_in_ledger += 1
        return f"<{tok}>"


PROMPT = list(range(40))          # 40 tok 的 prompt（>= 门槛，便于命中复用）


def new_native(cls=Native):
    n = cls()
    assert n.start_completion(PROMPT)
    return n


print("=" * 70)
print("E-1b 取消的归属：迟到的取消不得停掉下一轮")
print("=" * 70)
n = new_native()
stale_epoch = n.round_epoch          # 第一轮的编号
# 第一轮结束（正常收尾）：KV 与账本都有效
for t in (100, 101, 102):
    n.step(t)
# 第二轮开始
assert n.start_completion(PROMPT + [100, 101, 102, 200])
cur2 = n.round_epoch
print(f"  第一轮 epoch={stale_epoch}，第二轮 epoch={cur2}（已自增）")
ck("编号在两轮之间自增（否则归属无从判定）", cur2 > stale_epoch)
applied = n.abort_round(stale_epoch)
ck("带**旧**编号的取消不生效（这是个 bug 修复点：旧写法无条件置位）", applied is False)
ck("新轮次没有被误停（abort 仍为 false）", n.abort is False)
ck("被忽略的取消留了痕（否则'取消没反应'会被归因成网络问题）",
   any("abort 被忽略" in x for x in n.trace))
# 同一个编号再来一次：本轮自己的取消必须生效
ck("带**当前**编号的取消生效", n.abort_round(cur2) is True)
ck("无归属信息（epoch=0）一律不生效（宁漏停，不错停）", n.abort_round(0) is False)

print()
print("=" * 70)
print("E-1b（反例）旧写法：无条件置位 -> 停错对象")
print("=" * 70)


class LegacyNative(Native):
    def abort_round(self, round_epoch):
        # 旧实现：nativeAbort() 无条件 S.abort = true（不带编号）
        self.abort = True
        return True


leg = new_native(LegacyNative)
for t in (100, 101, 102):
    leg.step(t)
leg.start_completion(PROMPT + [100, 101, 102, 200])
leg_round = leg.round_epoch
applied_legacy = leg.abort_round(leg_round - 1)   # 一个"上一轮"的取消
ck("旧写法下迟到的取消**会**生效（即停错对象 —— 这就是被修的缺陷）",
   applied_legacy is True and leg.abort is True)
ck("（对照）新写法下同一个调用不生效", n.abort_round(stale_epoch) is False)

print()
print("=" * 70)
print("E-2 被打断的那一轮不得留下可复用的脏账本")
print("=" * 70)
cases = [
    ("生成期取消（abort 后仍有残留）", dict(abort_after_steps=3)),
    ("prefill 期取消", dict(abort_during=1)),
    ("prefill decode 失败", dict(decode_fail=1)),
]
for name, kw in cases:
    n = new_native()
    for t in (100, 101, 102):
        n.step(t)
    if "abort_after_steps" in kw:
        n.abort_round(n.round_epoch)          # 生成期取消
        for _ in range(kw["abort_after_steps"]):
            if n.step(999) is None:
                break
    elif "abort_during" in kw:
        n.start_completion(PROMPT + [100, 101, 102] + list(range(500, 560)),
                           abort_during_prefill_at=kw["abort_during"])
    else:
        n.start_completion(PROMPT + [100, 101, 102] + list(range(500, 560)),
                           decode_fail_at=kw["decode_fail"])
    ck(f"{name}：账本已作废（kv_valid=false）", n.kv_valid is False)
    ck(f"{name}：账本内容已清（last_tokens 为空）", n.last_tokens == [])
    # 下一轮：不得命中复用，且必须真正把 KV 清掉
    kv_before = len(n.kv)
    hit = n.plan(PROMPT + [100, 101, 102, 700])
    ck(f"{name}：下一轮不得命中复用（残留不是候选）", hit == 0)
    n.start_completion(PROMPT + [100, 101, 102, 700])
    ck(f"{name}：下一轮开始把 KV 全清（才是残留的真正出口）",
       n.trace[-1:] != [] and (kv_before == 0 or len(n.kv) == len(PROMPT + [100, 101, 102, 700])))
    ck(f"{name}：下一轮 prefill 是全量，不是复用", n.last_reuse == 0)

print()
print("=" * 70)
print("E-2（反例）旧写法：abort 只置标志、不作废账本")
print("=" * 70)


class LegacyAbort(Native):
    def abort_round(self, round_epoch):
        self.abort = True        # 旧实现：不判归属、不作废账本
        return True


lg = new_native(LegacyAbort)
for t in (100, 101, 102):
    lg.step(t)
lg.abort_round(lg.round_epoch)                 # 旧实现：只置标志
print(f"  旧实现 abort 后：kv_valid={lg.kv_valid} 账本 {len(lg.last_tokens)} tok"
      f"（新实现这里已是 false / 0 tok）")
lg.start_completion(PROMPT + [100, 101, 102, 700])
print(f"  旧实现下一轮：last_reuse={lg.last_reuse}（>0 = 复用了被打断那一轮的残留）")
ck("（对照）旧写法下被打断的那一轮残留**会被当有效历史复用**",
   lg.last_reuse > 0)
n2 = new_native()
for t in (100, 101, 102):
    n2.step(t)
n2.abort_round(n2.round_epoch)
n2.start_completion(PROMPT + [100, 101, 102, 700])
ck("新写法下同一个序列不复用（账本已作废）", n2.last_reuse == 0)

print()
print("=" * 70)
print("E-3 prefill 中断：读数与 KV 实况一致（不写死 0）")
print("=" * 70)
n = new_native()
ok = n.start_completion(PROMPT + list(range(500, 560)), abort_during_prefill_at=2)
ck("prefill 确实被中断（返回失败）", ok is False)
ck("KV 里确有内容（半截 prompt）", len(n.kv) > 0)
ck("S.n_used 与 KV 里的 token 数一致（旧写法在这里报 0）", n.n_used == len(n.kv))
ck("n_used 不为 0（旧写法的症状是 0，且与 kv_valid=false 同时出现会把人读反）",
   n.n_used > 0)
ck("账本同时被作废（两件事分开：作废账本 vs 保留读数）", n.kv_valid is False)

print()


class LegacyZeroNUsed(Native):
    def start_completion(self, cur, **kw):
        r = Native.start_completion(self, cur, **kw)
        if not r and self.abort:
            self.n_used = 0     # 旧写法
        return r


z = LegacyZeroNUsed()
z.start_completion(PROMPT + list(range(500, 560)), abort_during_prefill_at=2)
ck("（对照）旧写法下 KV 有内容而 n_used 报 0", len(z.kv) > 0 and z.n_used == 0)

print()
print("=" * 70)
print("E-5 「没跑过」与「跑过但账本无效」必须可分")
print("=" * 70)
cold = Native()
ck("冷启动：kv_rounds=0 且 kv_valid=false",
   cold.kv_rounds == 0 and cold.kv_valid is False)
ran = new_native()
for t in (100, 101, 102):
    ran.step(t)
ran.abort_round(ran.round_epoch)
ck("跑过后被 abort：kv_rounds>0 且 kv_valid=false",
   ran.kv_rounds > 0 and ran.kv_valid is False)
ck("两者的 (kv_valid, kv_rounds) 组合不同 —— 只看 kv_valid 会读成同一件事",
   (cold.kv_valid, cold.kv_rounds) != (ran.kv_valid, ran.kv_rounds))
ck("跑完一整轮且未被取消：kv_rounds>0 且 kv_valid=true（第三种处境）",
   new_native().kv_valid is True)

print()
print("=" * 70)
print("E-4 可观测面：已进账本未下发可对账")
print("=" * 70)
n = new_native()
delivered = 0
buffer_content = True      # 带 tools 的流式：正文整段缓冲，生成期不下发
for t in (100, 101, 102, 103):
    piece = n.step(t)
    if piece is not None and not buffer_content:
        delivered += 1
n.abort_round(n.round_epoch)   # 取消：本轮已进账本的 4 个 token 一个都没下发
ck("已进账本的 token 数 > 已下发步数（差额可查：客户端拼回的历史会短一截）",
   n.gen_in_ledger > delivered)
ck("账本计数只统计真正进过 KV 的 token（4 步 = 4 个）", n.gen_in_ledger == 4)
ck("差额恰好等于被缓冲未下发的那 4 个（可对账的量化口径）",
   n.gen_in_ledger - delivered == 4)

print()
print("=" * 70)
if fail == 0:
    print("=== abort 归属/账本语义 全部通过 ===")
else:
    print(f"=== abort 归属/账本语义 {fail} 条失败 ===")
raise SystemExit(1 if fail else 0)
