package com.xiaowan.localinference

/**
 * 加载失败的**归类**：把一条 native / 状态的错误文案，判断成「是不是资源不够」。
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 为什么需要单独一层，而不是在 registry 里写 `msg.contains("内存")`
 * ══════════════════════════════════════════════════════════════════════════
 * 「资源不够」与「其它加载错误」的**处置相反**：
 *
 *   · 资源不够 —— 内存里还躺着别的空闲模型，先按 LRU 驱逐它们、再重试一次，
 *     往往就能装上；重试仍失败才是有意义的失败；
 *   · 其它错误（文件损坏 / 不支持的量化 / 参数非法）—— 驱逐别的模型**不会有任何
 *     帮助**，只会白白把用户正在热的模型踢掉，然后仍然失败。
 *
 * 把两者混成一句「加载失败」，用户看到的现象是「这个模型一加载，别的模型就没了，
 * 它也还是装不上」；而服务端日志里没有任何一句说得出「我们试过腾内存」。
 *
 * 归类**只吃文案**（native 目前只回一段字符串），所以关键词表必须宽到能覆盖
 * 真实的分配失败措辞，又必须窄到不会把「模型路径不存在」误判成 OOM。这里刻意
 * 不匹配泛化的 `alloc`/`memory` 单词：它们会命中 `memory map file` 这类
 * 非 OOM 语境。宁可漏判成普通错误（不驱逐、不乱踢模型），也不要误判成 OOM
 * 去把别人正在用的模型赶走。
 *
 * 本类型**不依赖 Android / JNI**：宿主侧单测直接编它。
 */
object LoadFailure {

    /**
     * 资源耗尽的关键词。大小写不敏感。
     *
     * 覆盖三层真实来源：
     *   ① C++ 标准库 / ggml 的分配失败：`std::bad_alloc`、`cannot allocate memory`；
     *   ② llama.cpp 的装载失败：`failed to allocate`、`not enough memory`、
     *      `ggml_backend_alloc`、`mmap failed`；
     *   ③ 我们自己在 `ModelLifecycleCore` 包出来的中文文案：`内存不足` 等。
     */
    private val RESOURCE_REGEX = Regex(
        "(?i)(" +
            "out of memory|outofmemoryerror|\\boom\\b|bad_alloc|enomem|" +
            "cannot allocate|failed to allocate|allocation failed|alloc failed|" +
            "not enough memory|no memory|no space left|" +
            "ggml_backend_alloc|mmap failed|" +
            "内存不足|内存不够|内存溢出|分配失败|无法分配" +
            ")"
    )

    /** true = 这条错误更像是「装不下」，值得先腾内存再重试。 */
    fun looksLikeResourceExhaustion(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return RESOURCE_REGEX.containsMatchIn(message)
    }
}
