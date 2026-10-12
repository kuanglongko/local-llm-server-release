package com.xiaowan.localinference

/**
 * `RagProgress`（建索引进度那一行的**纯逻辑**读数）的离线单测。
 *
 * 钉的是这个对象存在的**全部意义**：把"我已经等了多久 / 走到第几条 / 还剩多少"
 * 变成**用户一眼能读**的一句话，且**不报它量不准的数**。它的失效形态全是
 * "读数本身变成噪声或谎言"：
 *   · 位宽抖动（`1:5` vs `1:50`）-> 一行每 250 ms 抖一次，读起来像"闪"不像"走"；
 *   · 分母不知道还报占比（`0/0 条（NaN%）`）-> 一个没有意义的数看着像读数；
 *   · 第一秒内报速率 -> 分母极小，"3000 条/分"这种噪声看着像真的；
 *   · 报了"预计剩余" -> 块与块耗时差一个数量级，推出来的剩余时间**假精确**；
 *   · 墙钟做差 -> NTP 跳变/用户改时间把"已耗时"拉歪（本仓库 G1 判据那一族）。
 * 都不抛异常、不崩溃，只有断言能钉住。
 */
object RagProgressTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    fun run(): Int {
        // ① 已耗时：分秒定长零填充（位宽稳定是这条行能挂几十分钟的前提）
        ok(RagProgress.elapsed(0L) == "0:00", "0 ms -> 0:00（不是空串/负数）")
        ok(RagProgress.elapsed(5_000L) == "0:05", "5 秒 -> 0:05（秒位补零）")
        ok(RagProgress.elapsed(65_000L) == "1:05", "65 秒 -> 1:05（**不是** 1:5）")
        ok(RagProgress.elapsed(59_000L) == "0:59", "59 秒 -> 0:59")
        ok(RagProgress.elapsed(60_000L) == "1:00", "60 秒 -> 1:00（进位边界）")
        ok(RagProgress.elapsed(3_599_000L) == "59:59", "3599 秒 -> 59:59（小时档下沿）")
        ok(RagProgress.elapsed(3_600_000L) == "1:00:00", "3600 秒 -> 1:00:00（小时档上沿）")
        ok(RagProgress.elapsed(3_661_000L) == "1:01:01", "3661 秒 -> 1:01:01（时/分/秒都进位）")
        ok(RagProgress.elapsed(10_000_000L) == "2:46:40", "10000 秒 -> 2:46:40（小时档）")
        // 位宽：分钟位在小时档下也**补零**（`1:01:01` 不是 `1:1:1`）
        ok(!RagProgress.elapsed(3_661_000L).contains(":1:"), "小时档的分钟位补零（不是 1:1:1）")
        // 负数/墙钟回跳：按 0 处理，不许出现 `-1:59`
        ok(RagProgress.elapsed(-5_000L) == "0:00", "负数 -> 0:00（墙钟回跳不报负时长）")
        ok(!RagProgress.elapsed(-1L).contains("-"), "任何输入都不出现负号")

        // ② 占比：分母不知道就不报；一位小数（前几条才不会全显示 0%）
        ok(RagProgress.percent(0, 0) == null, "total=0 -> 不报占比（分母不知道就不猜）")
        ok(RagProgress.percent(5, -3) == null, "total<0 -> 不报占比")
        ok(RagProgress.percent(0, 210) == "0.0%", "0/210 -> 0.0%")
        ok(RagProgress.percent(37, 210) == "17.6%", "37/210 -> 17.6%（一位小数）")
        ok(RagProgress.percent(210, 210) == "100.0%", "满 -> 100.0%（不超 100）")
        ok(RagProgress.percent(300, 210) == "100.0%", "done > total 也钳在 100.0%")
        ok(RagProgress.percent(-1, 210) == "0.0%", "done < 0 钳在 0.0%")
        // ⚠ 小数位**必须**有：210 条时取整会让前 4 条全显示 0%，而用户要判断的
        //    正是"它到底开始动了没有"
        ok(RagProgress.percent(1, 210) != "0%", "1/210 不许被取整成 0%（那是『没动』）")
        ok(RagProgress.percent(1, 210) == "0.5%", "1/210 -> 0.5%")

        // ③ 速率：只在"已经量得出速度"之后报（前两个闸各自堵一个假读数）
        ok(RagProgress.perMinute(0, 10_000L) == null, "0 条 -> 不报速率（没有分子）")
        ok(RagProgress.perMinute(3, 500L) == null, "不到 1 秒 -> 不报速率（分母极小 = 噪声）")
        ok(RagProgress.perMinute(3, 1_000L) == "180", "3 条 / 1 秒 -> 180 条/分")
        ok(RagProgress.perMinute(60, 60_000L) == "60", "60 条 / 1 分 -> 60 条/分")
        // 速率是整数（小数位在"条/分"这个量级上没有意义）
        ok(RagProgress.perMinute(7, 3_000L) == "140", "7 条 / 3 秒 -> 140（取整，不带小数）")

        // ④ 整行：四个数都在，且字段顺序固定（便于 grep 与扫读）
        val l = RagProgress.line(37, 210, 83_000L)
        ok(l.contains("37/210 条"), "行里有 已编/总数（实际：$l）")
        ok(l.contains("17.6%"), "行里有占比")
        ok(l.contains("1:23"), "行里有已耗时（83000 ms -> 1:23）")
        ok(l.contains("26"), "行里有速率（37 条 / 83 秒 ≈ 26 条/分）")
        ok(l.indexOf("37/210") < l.indexOf("1:23"), "条数在已耗时**之前**（顺序固定）")
        ok(l.indexOf("1:23") < l.indexOf("26 条/分"), "已耗时在速率之前")

        // ⑤ 整行在开头那一条上**不报假数**：分母/速率缺席时那些字段直接不出现
        val first = RagProgress.line(0, 210, 120L)
        ok(first.startsWith("0/210 条"), "第一条：条数照样报（实际：$first）")
        ok(first.contains("0.0%"), "第一条：占比报 0.0%")
        ok(!first.contains("条/分"), "第一条：**不报**速率（120 ms 时它是个假数）")
        ok(!first.contains("NaN"), "任何输入都不出现 NaN")
        val zero = RagProgress.line(0, 0, 0L)
        ok(!zero.contains("%"), "total=0 时整行里不出现占比字段")
        ok(zero.contains("0/0 条"), "total=0 时条数那一段仍在（不出现空串）")

        // ⑥ 前缀是**唯一的**一处定义（收发两边同源；此前那份副本导致判据恒假）
        ok(RagProgress.PREFIX == "[知识库] 正在编码：", "前缀的字面值没漂（收发两边同源）")
        ok(RagProgress.line(1, 2, 1_000L) == RagProgress.line(1, 2, 1_000L),
            "同一输入 -> 同一输出（纯函数，无隐藏状态）")

        println()
        println("RagProgressTest: PASS $pass / FAIL $fail")
        return if (fail == 0) 0 else 1
    }
}

fun main() {
    kotlin.system.exitProcess(RagProgressTest.run())
}
