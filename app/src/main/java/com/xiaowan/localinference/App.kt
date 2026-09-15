package com.xiaowan.localinference

import android.app.Application

/**
 * 崩溃兜底：任何未捕获异常先落盘，便于侧载安装后无 adb 时定位闪退原因；
 * 随后仍交回默认处理器终止进程。
 *
 * 补强（原先的 ring buffer 只在内存，native 崩溃即全丢）：
 * - 崩溃时把整个日志 ring buffer 一起写进 crash 文件：只留异常栈看不到崩前
 * native 在干什么（HTP 那类闪退尤其如此）；
 * - 同时往外部私有目录也写一份，避免只在 filesDir 里、用户取不到；
 * - 注册前台 Activity 计数，正常退出时给会话日志打结束标记。
 *
 * 说明：`android:name=".App"` 一直就在 manifest 里，本处理器
 * 一直是生效的，不是死代码。但它**覆盖不到 native abort**（SIGABRT 由 libc 直接终止
 * 进程，不经过 JVM）——那部分靠 [LogFileStore] 实时落盘 + [StderrPump] 抓死前输出。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 尽早开文件，让 native 初始化阶段的日志也能落盘
        LogFileStore.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val dump = buildString {
                    appendLine("thread=${t.name}")
                    appendLine("time=${java.util.Date()}")
                    appendLine("---- stack ----")
                    appendLine(sw)
                    appendLine("---- recent engine logs (${LlmEngine.recentLogs().size} lines) ----")
                    appendLine(LlmEngine.recentLogs().joinToString("\n"))
                }
                // 私有 filesDir 保底 + 外部目录便于用户直接取
                runCatching {
                    java.io.File(filesDir, "crash_last.txt").writeText(dump)
                }
                runCatching {
                    getExternalFilesDir(null)?.let { dir ->
                        java.io.File(dir, "crash_last.txt").writeText(dump)
                    }
                }
                // 崩溃日志也追加进当次会话日志文件（若已建立）
                runCatching { LogFileStore.append("[crash] ${e.javaClass.name}: ${e.message}") }
            }
            prev?.uncaughtException(t, e)
        }

        // 进程存活期间用户「滑掉任务」会走 onDestroy 但不一定走 onTerminate
        // （后者在部分 ROM 上基本不会被调用），所以用前台 Activity 计数判定正常退出，
        // 归零即写会话结束标记，供下次启动时回溯"上一次是不是崩的"。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) {
                started++
            }
            override fun onActivityStopped(a: android.app.Activity) {
                if (--started <= 0) { started = 0; LogFileStore.markCleanExit() }
            }
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }
}
