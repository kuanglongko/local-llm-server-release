package com.xiaowan.localinference

import org.json.JSONObject

/**
 * `RagProtocol`（远端注入的**协议层可见面**）的离线单测。
 *
 * 钉的是这个字段存在的**全部意义**：让"服务端偷偷改了 prompt"这件事
 * 能被**程序化发现**。它的失效形态与幻觉同形，所以判据必须硬：
 *   · 没注入却在响应里留字段 -> 恒出现的空值让"没开"与"开了没命中"不可分，
 *     正是要消除的那种同形；
 *   · 库名不转义就拼进 JSON -> 整份响应非法，客户端解析失败而错误指向"格式"；
 *   · 条数报错 -> "注入了 1 条"与"注入了 20 条（挤满 ctx）"不可分。
 */
object RagProtocolTest {

    private var pass = 0
    private var fail = 0

    private fun ok(cond: Boolean, what: String) {
        if (cond) { pass++; println("PASS  $what") } else { fail++; println("FAIL  $what") }
    }

    private fun eqStr(a: String?, b: String?, what: String) {
        if (a == b) { pass++; println("PASS  $what") }
        else { fail++; println("FAIL  $what\n     got=[$a]\n     want=[$b]") }
    }

    fun run(): Int {
        // ① 没注入 -> 空串（响应里一个字节都不多）
        eqStr(RagProtocol.responseField(null), "", "没注入 -> 空串（恒出现字段会让两种情形同形）")

        // ② 注入了 -> 含库名与条数，且是**合法 JSON 片段**
        val f = RagProtocol.responseField(RagProtocol.Injection("笔记A", 3))
        ok(f.startsWith(","), "字段带前导逗号（可直接拼进 JSON 对象）")
        val parsed = JSONObject("""{"x":1$f}""")
        eqStr(parsed.optJSONObject("local_rag")?.optString("kb"), "笔记A", "库名原样")
        ok(parsed.optJSONObject("local_rag")?.optInt("injected") == 3, "条数原样")

        // ③ 库名含引号/换行/反斜杠也不能破坏 JSON
        val nasty = "a\"b\\c\nd\te\u0001f"
        val g = RagProtocol.responseField(RagProtocol.Injection(nasty, 1))
        val p2 = JSONObject("""{"x":1$g}""")
        eqStr(p2.optJSONObject("local_rag")?.optString("kb"), nasty,
            "库名转义正确（不转义会让整份响应非法，而错误指向『格式』）")

        // ④ 库名空串也是合法输入（远端未配库时不该崩，也不该拼出非法 JSON）
        val h = RagProtocol.responseField(RagProtocol.Injection("", 0))
        JSONObject("""{"x":1$h}""")   // 能解析即可
        ok(true, "空库名 + 0 条 -> 仍是合法 JSON")

        // ⑤ kbJson 返回**带引号**的 JSON 串（裸串拼进 /health 就是非法 JSON）
        eqStr(RagProtocol.kbJson("库"), "\"库\"", "kbJson 带引号（裸串拼进 /health 会非法）")
        eqStr(RagProtocol.kbJson("a\"b"), "\"a\\\"b\"", "kbJson 转义内部引号")
        val hp = JSONObject("""{"rag_remote_kb":${RagProtocol.kbJson("x")}}""")
        eqStr(hp.optString("rag_remote_kb"), "x", "/health 里该字段可解析")

        println()
        println("RagProtocolTest: PASS $pass / FAIL $fail")
        return if (fail == 0) 0 else 1
    }
}
