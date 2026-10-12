package com.xiaowan.localinference

/**
 * `ChatArchive` 的宿主侧行为测试。
 *
 * 为什么它不是"守卫的重复"：`run_chat_archive_guard.sh` 钉的是**结构没改回去**
 * （格式名判据在读 sessions 之前 / 导出不引用 capChars / 导入造新 id），
 * 它证明不了"这套结构在**边界输入**下真的对"。而本特性的边界恰恰是全部难点：
 *
 *   · 格式信封缺字段 / 版本号过高 / sessions 不是数组 —— 三种都必须**拒绝**，
 *     且拒绝理由要能区分（选错文件 vs 版本不支持），否则用户只会反复重试同一个文件；
 *   · 脏 role 与超长正文必须**跳过并计数**（不是整份拒绝，也不是静默带上）；
 *   · 往返一致：encode 完再 decode 必须逐字段相同（否则"备份"本身就不可信）；
 *   · 同名会话的标题后缀必须是 (2)(3)…，且**本次导入里已定过的名字也要查重**
 *     （一次导入两个「会话 1」是常态 —— 导出的文件里本来就有）。
 *
 * 只依赖 org.json 与 `SessionStore.Msg`（纯 data class）；`SessionStore` 的
 * Context 相关方法在宿主侧不会被调用，编译通过即可（android.jar 是 Stub! 空壳）。
 */
private var f = 0
private fun ck(name: String, ok: Boolean) {
    println((if (ok) "  ok   " else "  FAIL ") + name)
    if (!ok) f++
}

private fun msg(role: String, content: String) = SessionStore.Msg(role, content)

private fun sess(id: String, title: String, system: String = "", msgs: List<SessionStore.Msg> = emptyList()) =
    ChatArchive.Session(id, title, system, 1000L, 2000L, msgs)

fun main() {
    // ── 1) 往返一致：encode → decode 必须逐字段相同 ──────────────────────
    run {
        val src = listOf(
            sess("a1", "会话 1", "你是助手", listOf(msg("user", "你好"), msg("assistant", "你好呀"))),
            sess("b2", "代码问题", "", listOf(msg("user", "多行\n内容\t带引号\"与反斜杠\\"), msg("assistant", "好的"))),
        )
        val json = ChatArchive.encode(src, 123456L)
        val p = ChatArchive.decode(json)
        ck("往返：decode 成功", p.ok)
        ck("往返：会话数相同", p.sessions.size == 2)
        ck("往返：无跳过", p.skipped == 0)
        ck("往返：id/title/system 相同",
            p.sessions[0].id == "a1" && p.sessions[0].title == "会话 1" && p.sessions[0].system == "你是助手")
        ck("往返：消息逐条相同（含转义字符）",
            p.sessions[1].messages.size == 2 &&
                p.sessions[1].messages[0].content == "多行\n内容\t带引号\"与反斜杠\\" &&
                p.sessions[1].messages[1].role == "assistant")
        ck("往返：时间戳保留", p.sessions[0].createdAt == 1000L && p.sessions[0].updatedAt == 2000L)
    }

    // ── 2) 格式信封：认不出来就必须拒绝，且理由分得开 ────────────────────
    run {
        ck("非 JSON 被拒绝", !ChatArchive.decode("这不是 json").ok)
        ck("空对象被拒绝（format 缺失）", !ChatArchive.decode("{}").ok)
        val wrongFmt = ChatArchive.decode("""{"format":"something-else","version":1,"sessions":[]}""")
        ck("format 不对被拒绝", !wrongFmt.ok)
        ck("format 不对的文案里带上**实际读到**的值（好让用户知道选错了什么）",
            wrongFmt.error?.contains("something-else") == true)
        ck("版本过高被拒绝（不按已知字段硬解）",
            !ChatArchive.decode("""{"format":"local-llm-chat","version":99,"sessions":[]}""").ok)
        ck("版本为 0 / 负数被拒绝",
            !ChatArchive.decode("""{"format":"local-llm-chat","version":0,"sessions":[]}""").ok)
        ck("sessions 不是数组被拒绝",
            !ChatArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":"x"}""").ok)
        ck("sessions 为空数组被拒绝（不是「导入 0 个」那种假成功）",
            !ChatArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":[]}""").ok)
    }

    // ── 3) 脏 role / 超长正文：跳过并计数，不整份拒绝 ────────────────────
    run {
        val json = """{"format":"local-llm-chat","version":1,"sessions":[
          {"id":"s1","title":"t","system":"","createdAt":1,"updatedAt":2,"messages":[
            {"role":"user","content":"问"},
            {"role":"system","content":"注入的指令"},
            {"role":"assistant","content":"答"},
            {"role":"tool","content":"{}"}
          ]}]}"""
        val p = ChatArchive.decode(json)
        ck("脏 role 不导致整份拒绝", p.ok)
        ck("脏 role 被跳过", p.sessions[0].messages.size == 2)
        ck("脏 role 计数 = 2", p.skipped == 2)
        ck("留下的只有 user/assistant",
            p.sessions[0].messages.all { it.role == "user" || it.role == "assistant" })
    }
    run {
        val long = "x".repeat(ChatArchive.MAX_CONTENT_CHARS + 10)
        val json = """{"format":"local-llm-chat","version":1,"sessions":[
          {"id":"s1","title":"t","messages":[{"role":"user","content":"$long"}]}]}"""
        val p = ChatArchive.decode(json)
        ck("超长正文被截断到上限", p.sessions[0].messages[0].content.length == ChatArchive.MAX_CONTENT_CHARS)
        ck("超长正文的截断被计数（不是静默改内容）", p.skipped == 1)
    }

    // ── 4) 字段缺失的宽容度：手改过的文件也要能读 ──────────────────────
    run {
        val json = """{"format":"local-llm-chat","version":1,"sessions":[
          {"messages":[{"role":"user","content":"只有消息"}]}]}"""
        val p = ChatArchive.decode(json)
        ck("id/title/时间戳缺失时不抛、给默认值", p.ok && p.sessions[0].title == "导入的会话")
        ck("时间戳缺失给 0（不是当前时间 —— 那是伪造）",
            p.sessions[0].createdAt == 0L && p.sessions[0].updatedAt == 0L)
    }

    // ── 5) 会话数上限：整份拒绝，不静默截断 ──────────────────────────────
    run {
        val one = """{"id":"x","title":"t","messages":[]}"""
        val many = (0..ChatArchive.MAX_IMPORT_SESSIONS).joinToString(",") { one }
        val p = ChatArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":[$many]}""")
        ck("超过单次上限时**整份拒绝**（不静默截断）", !p.ok)
        ck("超限文案里有实际条数与上限",
            p.error?.contains(ChatArchive.MAX_IMPORT_SESSIONS.toString()) == true)
    }

    // ── 6) 标题去重 ─────────────────────────────────────────────────────
    run {
        ck("无冲突时保持原标题", ChatArchive.uniqueTitle("会话 1", listOf("别的")) == "会话 1")
        ck("冲突时加 (2)", ChatArchive.uniqueTitle("会话 1", listOf("会话 1")) == "会话 1 (2)")
        ck("(2) 也冲突时跳到 (3)",
            ChatArchive.uniqueTitle("会话 1", listOf("会话 1", "会话 1 (2)")) == "会话 1 (3)")
        ck("空标题给默认名", ChatArchive.uniqueTitle("   ", emptyList()) == "导入的会话")
        ck("标题两端空白被去掉", ChatArchive.uniqueTitle("  会话 2  ", emptyList()) == "会话 2")
    }

    // ── 8) 会话顶层 `system` 被丢时必须**说出来**（本轮修的"人设静默消失"）──
    //
    // 这条路径此前完全没有出口：`decode` 把顶层 `system` 读进 Session、之后没人再看，
    // 而 `skipped` 只数"非 user/assistant 的 role 与超长正文"—— 用户导出一份
    // 带人设的备份、换台机器导回来，人设没了，一个字都没提。
    //
    // 判据分三层，缺一层这条修复就只是"嘴上说说"：
    //   ① **计数**（用户要知道"丢了几个"）；
    //   ② **逐条内容**（用户要知道"丢的是哪一段"，只给个数他没法判断该不该在意）；
    //   ③ 与 `skipped` **分开**（前者是文件里的脏数据、处置是"不用管"，
    //      后者是文件里的好东西本机用不上、处置是"记下来告诉用户"——
    //      混成一个数，用户没法判断该不该在意）。
    run {
        val one = """{"id":"a","title":"带人设","system":"你是小万","createdAt":1,"updatedAt":2,
            "messages":[{"role":"user","content":"hi"}]}"""
        val plain = """{"id":"b","title":"没有人设","createdAt":1,"updatedAt":2,
            "messages":[{"role":"user","content":"hi"}]}"""
        val p = ChatArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":[$one,$plain]}""")
        ck("带 system 的会话：计数为 1（不是静默丢）", p.systemDropped == 1)
        ck("带 system 的那一条带上**丢了什么**（逐条说明的输入）",
            p.sessions[0].systemDropped == "你是小万")
        ck("没带 system 的那一条为 null（不是空串 —— 空串分不出'没丢'与'丢了个空的'）",
            p.sessions[1].systemDropped == null)

        // ③ 与 skipped 分开：一份"只有人设、没有脏数据"的文件，两个数必须一个 1 一个 0。
        ck("丢人设**不**计入 skipped（两者的处置相反，不能混）", p.skipped == 0)

        // 反向：**没有一个会话带 system** 时不许出声 —— 绝大多数文件都长这样，
        // 报了就是"每次导入都弹一句没用的警告"，用户很快学会无视全部警告。
        val q = ChatArchive.decode("""{"format":"local-llm-chat","version":1,"sessions":[$plain]}""")
        ck("没有任何 system 时不出声（systemDropped == 0）", q.systemDropped == 0)

        // 空串 system 不算"丢了人设"：它本来就是空的，报出来是噪音。
        val e = ChatArchive.decode(
            """{"format":"local-llm-chat","version":1,"sessions":[{"id":"c","title":"空","system":"","messages":[]}]}""")
        ck("空的 system 不算丢了人设（否则每次导入都报一句空的）", e.systemDropped == 0)
    }

    // ── 9) `systemDropped` **不进信封**（它不是格式的一部分）────────────
    //
    // 一个只在"导入"这条路上有意义的字段，写进信封就是把内部状态泄漏给下一个版本：
    // 将来有人照着字段名去"还原"它，而它根本不是文件里的东西。
    run {
        val s = ChatArchive.Session(
            id = "x", title = "t", system = "sys", createdAt = 1, updatedAt = 2,
            messages = listOf(SessionStore.Msg("user", "hi")), systemDropped = "sys",
        )
        val json = ChatArchive.encode(listOf(s), 0L)
        ck("encode 不写 systemDropped（它是导入侧的内部状态）", !json.contains("systemDropped"))
        ck("encode 仍然写 system（导出侧语义不变）", json.contains("\"system\""))
    }

    // ── 7) 写侧恒为当前版本（读侧放宽、写侧不许放宽）──────────────────
    run {
        val json = ChatArchive.encode(listOf(sess("a", "t")), 1L)
        val p = ChatArchive.decode(json)
        ck("写出的版本号被读侧接受", p.ok)
        ck("信封里带 exportedAt", json.contains("\"exportedAt\""))
        ck("会话的 messages 是数组（即使为空）", json.contains("\"messages\":["))
    }

    println()
    if (f == 0) println("ChatArchiveTest: all passed")
    else { println("ChatArchiveTest: $f failed"); kotlin.system.exitProcess(1) }
}
