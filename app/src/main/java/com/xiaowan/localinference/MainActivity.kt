package com.xiaowan.localinference

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 入口：直接进 EngineActivity（进程内推理 + 模型库 + OpenAI 兼容本地服务）。
 * 说明：HTTP server 能力以进程内方式提供（HttpApi，默认 127.0.0.1:8080），
 * 不依赖在应用数据目录 exec 外部二进制（部分 OEM 的 W^X 策略禁止），故 targetSdk<=28 已非强制；
 * 当前仍有意保持 28（成熟行为优先，理由见 build.gradle.kts 注释）。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, EngineActivity::class.java))
        finish()
    }
}
