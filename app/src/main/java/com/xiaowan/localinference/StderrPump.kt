package com.xiaowan.localinference

import android.system.Os
import java.io.FileDescriptor

/**
 * 把进程 stderr 接到日志管线。
 *
 * 动机：native 崩溃前最关键的那句话（ggml/hexagon 的 `ggml_abort: ...`、llama.cpp 直写
 * stderr 的诊断行）**不进 logcat、也不走我们的日志回调**，只写 fd 2。侧载用户没有 adb，
 * 于是崩溃原因完全看不到——侧载用户没有 adb，只能靠落盘文件回溯。
 *
 * 做法：`pipe()` 后把 fd 2 重定向到写端，守护线程持续读、按行喂给 [sink]。
 * 安全性说明：
 * - 读端一直在排空，pipe 缓冲不会写满，因此不会把 native 线程阻塞在 write(2) 上；
 * - 只重定向 fd 2，不动 stdout，避免污染任何依赖 stdout 的子进程输出；
 * - 整段包在 runCatching 里，失败就退回原行为（stderr 仍指向默认），不影响推理功能；
 * - 必须在 native init 之前调用，才可能接住初始化阶段的报错。
 */
object StderrPump {

    private const val STDERR_FD = 2

    @Volatile private var started = false

    fun start(sink: (String) -> Unit) {
        if (started) return
        started = true
        val ok = runCatching {
            val fds: Array<FileDescriptor> = Os.pipe()
            val rd = fds[0]
            val wr = fds[1]
            Os.dup2(wr, STDERR_FD)
            Os.close(wr) // fd 2 已持有引用，关掉本副本，写端全部退出后读端能正常收到 EOF
            Thread {
                val buf = ByteArray(8192)
                val pending = StringBuilder()
                while (true) {
                    val n = runCatching { Os.read(rd, buf, 0, buf.size) }.getOrDefault(-1)
                    if (n <= 0) break
                    pending.append(String(buf, 0, n, Charsets.UTF_8))
                    while (true) {
                        val i = pending.indexOf('\n')
                        if (i < 0) break
                        val line = pending.substring(0, i).trimEnd('\r')
                        pending.delete(0, i + 1)
                        // sink 里含文件写；万一抛异常也必须继续排空 pipe，
                        // 否则缓冲写满后 native 线程会永久阻塞在 write(2) 上（变成卡死，比崩溃更糟）
                        if (line.isNotEmpty()) runCatching { sink(line) }
                    }
                    // 没有换行的超长残留（例如崩溃时写了一半）也别憋着
                    if (pending.length > 4096) {
                        val s = pending.toString()
                        pending.setLength(0)
                        runCatching { sink(s) }
                    }
                }
            }.apply { isDaemon = true; name = "stderr-pump"; start() }
        }.isSuccess
        if (!ok) started = false // 允许后续再试一次；失败时 stderr 保持原样
    }
}
