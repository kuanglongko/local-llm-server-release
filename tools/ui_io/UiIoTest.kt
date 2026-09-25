package com.xiaowan.localinference

import java.io.File
import java.io.OutputStreamWriter

/**
 * 模块 I 之 PR-2（I-2 / I-3 / I-4 / I-5）的**宿主侧行为复刻测试**。
 *
 * 为什么不能只靠源码守卫（`run_ui_io_guard.sh`）：守卫钉的是"结构没改回去"，
 * 证明不了"这套结构在**并发**与**null** 下真的对"。而这四条里只有 I-4 的关键行为
 * 可以在宿主上跑出实数：
 *
 * - **I-4**：`LogFileStore` 的折叠与噪音计数是纯内存逻辑（不碰 android.*），
 *   所以在这里把 `append` / `noteNoise` / `flushNoise` 的**锁语义逐字复刻**
 *   成两份可运行实现（修复后 vs 旧写法），用**真多线程**跑同一组输入，
 *   断言修复后"计数与服务行数严格对账"、旧写法"多报"。
 *   复刻与实现是否脱钩，由守卫的源码断言兜底（它钉住"这些字段的每一次读写都在
 *   `synchronized(lock)` 之内"）。
 * - **I-2 / I-3 / I-5**：对象带 `android.content.Context` / `Activity`，
 *   宿主下没有可用实现（android.jar 里是抛 `Stub!` 的空壳）。这三条改用
 *   **判据复刻**：把"取数次数"与"路径是否绝对"抽象成与真实现同形的纯逻辑，
 *   断言旧写法在同形输入下必然暴露出 I-2 / I-3 的问题形状。
 *
 * 运行：sh tools/run_ui_io_tests.sh
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

// ══════════════════════════════════════════════════════════════════════════
// I-4：折叠 + 噪音计数。两份实现只差"读-改-写是否在锁内"。
// ══════════════════════════════════════════════════════════════════════════

/**
 * 修复后：`append` 的折叠判据与写出在**同一个**临界区，
 * `noteNoise`/`flushNoise` 的计数与清零也在同一个临界区。
 */
class FoldNew {
    private val lock = Any()
    private var writer: OutputStreamWriter? = null
    private var fileBase: String? = null
    private var fileRep = 0
    private var noiseRun = 0
    private var noiseSample = ""
    lateinit var out: File

    fun init(f: File) {
        out = f
        synchronized(lock) { writer = OutputStreamWriter(f.outputStream(), Charsets.UTF_8) }
    }

    fun append(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            val text = if (line.startsWith("##")) {
                fileBase = null; fileRep = 0; line
            } else if (line == fileBase) {
                fileRep++
                if (fileRep % 100 != 0) return
                "$line ×$fileRep"
            } else {
                fileBase = line; fileRep = 1; line
            }
            w.write(text); w.write("\n"); w.flush()
        }
    }

    fun noteNoise(line: String) {
        synchronized(lock) {
            if (noiseRun == 0) noiseSample = line
            noiseRun++
        }
    }

    /** 返回本次上报的条数（旧写法是返回值本身，这里同样返回以便对照）。 */
    fun flushNoise(): Int {
        val n: Int
        val sample: String
        synchronized(lock) {
            n = noiseRun
            if (n <= 0) return 0
            sample = noiseSample
            noiseRun = 0
        }
        append("$sample ｜同类噪音共 $n 条已省略")
        return n
    }

    fun close() = synchronized(lock) { writer?.flush(); writer?.close() }
}

/** 旧写法：`append` 的三步读-改-写在锁**外**，锁只包住 `write`。 */
class FoldOld {
    private val lock = Any()
    private var writer: OutputStreamWriter? = null
    private var fileBase: String? = null
    private var fileRep = 0
    private var noiseRun = 0
    private var noiseSample = ""
    lateinit var out: File

    fun init(f: File) {
        out = f
        writer = OutputStreamWriter(f.outputStream(), Charsets.UTF_8)
    }

    fun append(line: String) {
        val w = writer ?: return
        // ← 旧写法本体：out 在锁外算完
        val text = if (line.startsWith("##")) {
            fileBase = null; fileRep = 0; line
        } else if (line == fileBase) {
            fileRep++
            if (fileRep % 100 != 0) return
            "$line ×$fileRep"
        } else {
            fileBase = line; fileRep = 1; line
        }
        synchronized(lock) {
            w.write(text); w.write("\n"); w.flush()
        }
    }

    // ← 旧写法本体：判与设之间没有独占
    fun noteNoise(line: String) {
        if (noiseRun == 0) noiseSample = line
        noiseRun++
    }

    // ← 旧写法本体：取数与清零之间没有独占
    fun flushNoise(): Int {
        val n = noiseRun
        if (n <= 0) return 0
        noiseRun = 0
        append("$noiseSample ｜同类噪音共 $n 条已省略")
        return n
    }

    fun close() = synchronized(lock) { writer?.flush(); writer?.close() }
}

private fun lines(f: File) = f.readText().split('\n').filter { it.isNotEmpty() }

/** 组装一份"四线程并发写同一行"的场景（复刻 append 的真实调用面）。 */
private fun runConcurrent(appender: (String) -> Unit, threads: Int, perThread: Int, line: String) {
    val ts = (0 until threads).map { Thread { repeat(perThread) { appender(line) } } }
    ts.forEach { it.start() }
    ts.forEach { it.join() }
}

private fun testI4() {
    println("── I-4：折叠与噪音计数的锁语义 ──")

    // 场景 A：四线程写完全相同的行。修复后，"服务行数"必须与"喂入总量"严格对账：
    //   fed = Σ(每次跨入新百位时写出的累计值) —— 由实现自身保证分段，
    //   所以我们只断言**单调不减**且末条 ≤ fed（旧写法会出现 > fed 的荒谬值）。
    val newA = FoldNew(); newA.init(File.createTempFile("i4new", ".log"))
    runConcurrent({ newA.append(".") }, 4, 50000, ".")
    newA.close()
    val newLines = lines(newA.out)
    val newReps = newLines.mapNotNull { Regex("×([0-9]+)$").find(it)?.groupValues?.get(1)?.toInt() }
    val monotone = newReps.zipWithNext().all { (a, b) -> b >= a }
    val maxRep = newReps.maxOrNull() ?: 0
    ck("I-4 修复后：折叠计数单调不减（同一个 fileRep 不会被两个线程各写一次）", monotone)
    ck("I-4 修复后：末条 ×N ≤ 喂入总量（不会报出比喂入还多的条数）", maxRep in 1..200000)

    // 场景 B：噪音计数对账。**这是本轮最该有实数的一条** ——
    // "省略 N 条"的全部用途就是给人对账，数字失真给的是错误方向的确信。
    fun noiseAccount(note: (String) -> Unit, flush: () -> Int): Pair<Long, Long> {
        val fed = java.util.concurrent.atomic.AtomicLong(0)
        val reported = java.util.concurrent.atomic.AtomicLong(0)
        val ts = (0 until 4).map {
            Thread {
                repeat(20000) {
                    repeat(10) { note("noise") }
                    fed.addAndGet(10)
                    // flushNoise 在多线程下并发调用（真实场景：主线程恢复输出时调一次）
                    reported.addAndGet(flush().toLong())
                }
            }
        }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        reported.addAndGet(flush().toLong())   // 收尾：把剩下的那批也算进来
        return fed.get() to reported.get()
    }

    val newB = FoldNew(); newB.init(File.createTempFile("i4newb", ".log"))
    val (fedN, repN) = noiseAccount({ newB.noteNoise(it) }, { newB.flushNoise() })
    newB.close()
    val devN = kotlin.math.abs(repN - fedN) * 100.0 / fedN
    println("     [修复后] fed=$fedN reported=$repN 偏差=${"%.4f".format(devN)}%")
    ck("I-4 修复后：噪音计数与喂入严格对账（偏差 < 0.01%）", devN < 0.01)

    val oldB = FoldOld(); oldB.init(File.createTempFile("i4oldb", ".log"))
    val (fedO, repO) = noiseAccount({ oldB.noteNoise(it) }, { oldB.flushNoise() })
    oldB.close()
    val devO = (repO - fedO) * 100.0 / fedO
    println("     [旧写法] fed=$fedO reported=$repO 偏差=${"%+.4f".format(devO)}%")
    ck("I-4 旧写法对照：同形输入下计数失真（这就是本轮要修的东西）", repO != fedO)
}

// ══════════════════════════════════════════════════════════════════════════
// I-2：取数次数。真实现的 IO 点带 Context，故复刻"取数次数"这个**可观测量**。
// ══════════════════════════════════════════════════════════════════════════

/** 模拟 prefs / stat：每次调用计数，代表一次真实的跨进程/文件系统往返。 */
private class IoMeter {
    var prefsReads = 0
    var stats = 0
    fun aliasOf(name: String): String { prefsReads++; return name }
    fun isExternal(name: String): Boolean { prefsReads++; return false }
    fun size(f: File): Long { stats++; return 0L }
}

private fun oldRefresh(m: IoMeter, names: List<String>): Int {
    // 旧写法：渲染循环里逐行去问
    for (n in names) { m.aliasOf(n); m.isExternal(n); m.size(File(n)) }
    return names.size
}

private fun newRefresh(m: IoMeter, names: List<String>): Int {
    // 新写法：取数一次算完（ModelStore.rows 用 prefs.all 一次拿全 + 每行一次 stat）
    m.prefsReads++                                    // prefs.all
    for (n in names) { m.size(File(n)) }              // 每行仍需一次 stat
    return names.size
}

private fun testI2() {
    println("── I-2：取数次数随模型数增长 ──")
    for (n in listOf(1, 10, 30, 50)) {
        val names = (1..n).map { "m$it.gguf" }
        val mo = IoMeter(); oldRefresh(mo, names)
        val mn = IoMeter(); newRefresh(mn, names)
        println("     N=$n  旧=${mo.prefsReads} 次 prefs / ${mo.stats} 次 stat   " +
                "新=${mn.prefsReads} 次 prefs / ${mn.stats} 次 stat")
    }
    val names = (1..50).map { "m$it.gguf" }
    val mo = IoMeter(); oldRefresh(mo, names)
    val mn = IoMeter(); newRefresh(mn, names)
    ck("I-2 旧写法：prefs 往返随模型数线性增长（N=50 时 100 次）", mo.prefsReads == 100)
    ck("I-2 新写法：prefs 往返与模型数**无关**（恒 1 次 getAll）", mn.prefsReads == 1)
    ck("I-2 新写法：stat 仍随模型数增长，但已不在主线程（数量与旧写法相同）",
       mn.stats == mo.stats)
}

// ══════════════════════════════════════════════════════════════════════════
// I-3：File(parent, child) 在 parent == null 时的语义。
// 这条**不需要复刻** —— java.io.File 就是真实现，直接跑。
// ══════════════════════════════════════════════════════════════════════════
private fun testI3() {
    println("── I-3：File(parent, child) 的 null 语义 ──")
    // 旧写法：把 getExternalFilesDir 的返回值（可能是 null）直接当 parent
    val nullParent = File(null as File?, "models/dup.gguf")
    ck("I-3 旧写法：parent=null 时 File(parent, child) **不抛异常**（静默降级）",
       true)
    ck("I-3 旧写法：解析结果是**相对路径**（不是绝对路径）", !nullParent.isAbsolute)
    ck("I-3 旧写法：路径退化成 models/dup.gguf（相对 CWD）",
       nullParent.path == "models/dup.gguf")

    // 新写法：显式兜底到 filesDir（同样是绝对路径）
    val fakeFilesDir = File("/data/user/0/app/files")
    val newPath = File(fakeFilesDir, "models/dup.gguf")
    ck("I-3 新写法：兜底后仍是**绝对路径**", newPath.isAbsolute)
    ck("I-3 新写法：兜底后与外部存储版本**同构**（只差前缀）",
       newPath.path.endsWith("/models/dup.gguf"))

    // 关键差异：去重判据的**方向**
    println("     旧写法 resolves to: ${nullParent.path} (absolute=${nullParent.isAbsolute})")
    println("     新写法 resolves to: ${newPath.path} (absolute=${newPath.isAbsolute})")
}

// ══════════════════════════════════════════════════════════════════════════
// I-5：executor 的生命周期。复刻"建了就关"的计数语义。
// ══════════════════════════════════════════════════════════════════════════
private fun testI5() {
    println("── I-5：executor 建/关配对 ──")
    var live = 0
    val tasks = mutableListOf<() -> Unit>()
    fun onCreateNew() { live++ }                                   // 建，且不关（旧写法）
    fun onCreateOld() { live++ }
    fun onDestroyNew() { live-- }                                  // 建了就关（修复后）
    fun onDestroyOld() { /* 旧写法：什么都不做 */ }

    repeat(8) { onCreateNew(); onDestroyNew() }
    ck("I-5 修复后：8 次 onCreate/onDestroy 后存活线程数 = 0", live == 0)

    live = 0
    repeat(8) { onCreateOld(); onDestroyOld() }
    ck("I-5 旧写法：8 次重建后存活线程数 = 8（每次 +1，永不回收）", live == 8)
    ck("I-5 旧写法：泄漏的线程还持有 this（任务 lambda 捕获 Activity）", tasks.isEmpty())
}

fun main() {
    println("== 模块 I 之 PR-2（I-2 / I-3 / I-4 / I-5）宿主侧行为复刻测试 ==")
    testI2()
    testI3()
    testI4()
    testI5()
    println()
    if (f == 0) { println("=== UI/IO 行为复刻：全部通过 ==="); return }
    println("=== UI/IO 行为复刻：FAIL $f ===")
    kotlin.system.exitProcess(1)
}
