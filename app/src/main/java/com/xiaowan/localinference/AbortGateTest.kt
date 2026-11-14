package com.xiaowan.localinference

/**
 * [AbortGate] 的宿主单测：**注册那条链真的通，而且调度器不必认识引擎**。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么这份单测是「一条编译断言 + 一组行为断言」
 * ══════════════════════════════════════════════════════════════════════════
 * 这条缺陷（ISSUE #452 第 4 步）在**编译期**就断了：`SlotScheduler.kt` 写了一句
 * `LlmEngine.requestAbort(i)`，而三份宿主套件只编纯逻辑文件。它的失效形态是
 * 「没有 kotlinc 的 CI 上永远绿，有 kotlinc 的机器上一编就红」。
 *
 * 编译那一半由**这份套件本身编得过**来证明（`run_slot_tests.sh` 里 `SlotScheduler.kt`
 * 与它同批编译，`LlmEngine.kt` 不在清单里）；`run_kt_test_runner_guard.py` 另有一条
 * 纯源码级判据钉住「不许再写回去」。这里补的是**行为**那一半：
 *
 *   · 没注册时退回置位（不崩、不吞）；
 *   · 注册之后，取消**每一格都打给注册的那一条**（不是只打 slot 0）；
 *   · 重复注册是覆盖，不是追加。
 */
/**
 * 计数用 `internal` 而不是 `private`：这份文件与 `tools/slot/SlotSchedulerTest.kt`
 * 在 `run_slot_tests.sh` 里**同批编译**（同一套 classpath、同一个 OUT）。等宽私有的
 * `ck` / `fail` 放进同一个包命名空间就是「冲突、或者对方的 private 挡住对方」——
 * 编译期报一句 `cannot access 'fun ck': it is private in file`，而那行报在**测试**
 * 文件上，看起来像"某个用例写错了"。名字也分开（`gateCk`），免得读者以为是同一个钩子。
 */
private var gatePassed = 0
private var gateFailed = 0

private fun gateCk(name: String, cond: Boolean, detail: String = "") {
    if (cond) {
        gatePassed++
        println("  ok   $name")
    } else {
        gateFailed++
        println("  FAIL $name" + if (detail.isNotEmpty()) "（$detail）" else "")
    }
}

fun main() {
    println("AbortGateTest")

    // ── 1. 没注册：退回置位，不崩 ────────────────────────────────────────────
    gateCk("未注册时 installed == false", !AbortGate.installed)
    gateCk(
        "未注册时 cancel 退回置位：表里没轮次 -> null（不抛）",
        AbortGate.cancel(0) == null,
    )
    // ⚠ 没引擎时**不记账**（ISSUE #450 自检）：这一跳送不到 native，记下的账没有兑现
    //   的对象，只会在这一格的下一轮上炸开 —— 而那一轮与这次让路毫无关系。
    //
    // 账本现在按**身份**记（见 `RequestCancel` 文件头），而闸门这条路本来就没有身份
    // （`AbortGate.cancel(slot)` 只给了格子）—— 于是"不记账"是结构上成立的，
    // 这里钉的是"调用前后账本一笔都没多"。
    gateCk(
        "未注册时打空不留下欠账（送不到 native 的取消不许记在账上）",
        run {
            val before = RequestCancel.claimCount
            AbortGate.cancel(2)
            RequestCancel.claimCount == before
        },
    )

    // ── 2. 注册之后：每一格都打给注册的那一条 ────────────────────────────────
    val hits = mutableListOf<Int>()
    AbortGate.install { slot ->
        hits.add(slot)
        null
    }
    gateCk("注册之后 installed == true", AbortGate.installed)
    AbortGate.cancel(0)
    AbortGate.cancel(3)
    gateCk("两格各自都打到了注册的那一条", hits == listOf(0, 3), "实得 $hits")

    // ── 3. 覆盖注册：链永远只指向最后一个 ────────────────────────────────────
    val late = mutableListOf<Int>()
    AbortGate.install { slot ->
        late.add(slot)
        null
    }
    AbortGate.cancel(1)
    gateCk("覆盖注册之后只打新的一条", late == listOf(1) && hits.size == 2, "新 $late / 旧 $hits")

    // ── 4. 返回值原样透出（调用方要拿它判"有没有停到一轮"）───────────────────
    val t = RequestCancel.enterSlot(0, owner = "abort-gate-test")
    var seen: RequestCancel.Token? = null
    AbortGate.install { slot ->
        seen = RequestCancel.cancelSlot(slot)
        seen
    }
    val got = AbortGate.cancel(0)
    gateCk("返回值就是注册那一条给的（不吞）", got === seen && got !== null)
    if (t != null) RequestCancel.leave(t)

    println("\n${if (gateFailed == 0) "PASS" else "FAIL"} $gatePassed / $gateFailed")
    if (gateFailed != 0) kotlin.system.exitProcess(1)
}
