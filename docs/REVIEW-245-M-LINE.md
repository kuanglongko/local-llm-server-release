# ISSUE #245 · M 线**复查**（回 m 线复查）

> 起点：`c6274eb`（C6 收口），本轮只审 **M 线（RAG / 嵌入）**。
> 上一轮我报过"M1 一条真缺陷，M2~M8 没抓到恶性/一般级问题"——
> **那份结论是错的**，错法与上一轮同一处：读的是守卫的**结论**（"全绿"），
> 没读守卫的**判据**，也没把套件**真跑**起来。

## 一句话结论

**M 线抓到 1 条产品侧真缺陷（恶性·静默丢数据）+ 2 条断言长期恒红（从引入那天起）。**

- 产品侧：多行 `.jsonl` 被 `JSONObject` 悄然吃成**一条** —— 选进 3 条、导入 1 条，
  且 `skipped == 0`、`error == null`，没有任何提示。
- 判据侧：`run_rag_archive_tests.sh` 2 条断言、`run_rag_index_store_tests.sh` 2 条
  断言**从引入提交起就没绿过**；其中 `run_rag_index_store_tests.sh` 的
  "阈值 0.9999 全挡"那一格**从未进入全挡分支**。

## 为什么上一轮没看见

两件事叠在一起：

1. **工具链那半**：`run_rag_archive_tests.sh` / `run_rag_index_store_tests.sh`
   都要 `kotlinc`。当时环境没有，脚本按 `REQUIRED=0` 打一行
   `SKIP 缺少 kotlinc` 就 `exit 0` 走了 —— 我把 `exit 0` 读成"过了"。
   （上一轮 C 线已经栽在同一个坑里，我把那次的教训写进了小结，
   **但没有回头把 M 线那几份也补跑**。所以这次先把工具链装齐再动手。）
2. **判据那半**：`run_rag_archive_guard.sh` / `run_rag_index_guard.sh` 是纯 sh，
   确实跑了、确实绿。但它们锚的是**结构关系**和**字面写法**，
   而这两条缺陷恰好都在"结构没变、字面还在"的缝里。

## M-1（恶性 · 产品侧）多行 JSONL 被吃成一条，静默丢 N-1 条

`RagArchive.decodeObjectOrLines`：

```kotlin
val root = runCatching { JSONObject(text) }.getOrNull()
...
if (root != null) {
    if (root.has("t") && root.has("s")) {        // ← 就是这里
        val one = entryOf(root)
        if (one != null) return Parsed(listOf(one.first), "", one.second, null)
```

`org.json.JSONObject` 不是严格解析器：它从第一个 `{` 开始吃，遇到配平的 `}`
就收工，**后面的字节全部忽略**。于是

```
{"t":"甲","s":"x"}
{"t":"乙","s":"x"}
{"t":"丙","s":"x"}
```

这份**三行 JSONL** 能 parse 成一个合法的单条对象 `{"t":"甲","s":"x"}`，
`has("t") && has("s")` 双双为真 —— 函数认了它，返回**一条**。

**为什么这是静默错**：`Parsed.error == null`、`skipped == 0`，
于是确认框逐字说"文件里有 **1** 条正文"，Toast 说"已导入 **1** 条"。
用户选进来的 3 条里，后两条**无声消失**，而界面上每一处都在说成功。

**触发面**：`.jsonl` 是这个 App 自己的落盘格式（`texts.jsonl`），
导入入口的文案里也写着"（.json / .jsonl）"——
也就是说，一份**手写的多行 jsonl 笔记**、或从别处导出的 jsonl，
走这条路就会丢。这不是理论边界。

**修法**：把根对象当**候选**，先数整份文本的非空行数；
只有 `nonBlank <= 1` 时才允许它充当"单行 JSONL"。
多行文本一律走 `decodeLines` —— 那才是它真实的形状，
且逐行失败的行会**被计数**（不再静默吞）。

```kotlin
val nonBlank = text.lines().count { it.isNotBlank() }
if (nonBlank <= 1 && root.has("t") && root.has("s")) { ... }
if (nonBlank > 1) return decodeLines(text)
```

**端到端自证**（真 `RagArchive.decode`，非复刻）：

```
输入：3 行 jsonl
输出：ok=true entries=3 skipped=0 error=null
      - 第一条 / 粘贴-test
      - 第二条 /
      - 第三条 / note.md
对照：单行 JSONL 仍能读（ok=true entries=1）
对照：别的 App 备份仍被拒（format = 「local-llm-chat」）
```

## M-2（判据侧 · 一般）4 条断言长期恒红

| 套件 | 断言 | 真相 |
|---|---|---|
| `run_rag_archive_tests.sh` | `每条只有 t 与 s 两个键` | 期望 `"t":"a","s":"b"` 相邻。`JSONObject` 内部是 `HashMap`，实测输出 `{"s":"b","t":"a"}` —— **顺序本来就不保证**，按相邻性锚必然假 |
| 同上 | `单行 JSONL 缺 s：被拒且计数` | 单行走的是 `fail(msg)`，`skipped = 0`（跳过计数只有**逐行**那条路才有）。期望 `skipped == 1` 不成立 |
| `run_rag_index_store_tests.sh` | `rank：阈值 0.9999 全挡 -> 空` | `0.9999f` 的精确值是 0.99989998…，**严格小于** 1.0，`1.0f >= 0.9999f` 为真 —— row0 照样命中，**这一格从没进过全挡分支** |
| 同上 | `rank：全挡时 above = 0` | 同因，实测 `above = 1` |

**验证它们"从引入起就是红的"**：把工作区 checkout 到引入提交，同样跑一遍 ——
`5441f6d` 与 `2fc8f81` 上，同样的 4 条红。**不是本轮改出来的。**

**这两条为什么能藏这么久**：`run_rag_archive_tests.sh` 与
`run_rag_index_store_tests.sh` 都**不是 CI 必跑项**（需要 kotlinc）。
而 `run_rag_index_store_tests.sh` 那条 `rank` 恒红旁边，恰好夹着一条
**恒真**的 `best 仍是 1.0` —— 一条真断言把两条红衬成了"看起来在测"。

**修法**：
- `t/s` 那条改成锚**键集相等**（`keys() == {t, s}`）+ 取值断言 —— 与顺序、相邻性无关；
- `缺 s` 那条改成锚**"不许静默"**：被拒 + 文案里指得出缺哪个键；
- `rank` 那条把阈值改成 `1.00001f`（相邻 `topK` 那一格用的正是它，
  而 `topK` 就是 `rank(...).hits` —— **同一份实现，两处阈值不一致本身就是痕迹**），
  并补一格"阈值恰好等于最高分 -> 保留（语义是 `>=`）"守住边界；
- 新增 `tools/rag_index/check_rank_full_block.py`：判据比的是**用例数据与阈值的数值关系**
  （`阈值 > 矩阵最高分` 且 `期望 hits == 0`），不是 grep `1.00001f` ——
  换一个同样正确的阈值（1.5f）也过，而 `0.9999f` 改个变量名绕不过去。

## 判据侧：这轮是**真跑**

工具链自己装齐（`g++` / OpenJDK 17 / `kotlinc` 2.0.21 / `android.jar` 35 / `json.jar`），
然后实跑，不是看结论：

```
run_rag_index_store_tests       180/0   (原 177 / FAIL 2)
run_rag_index_tests             162/0
run_rag_inject_tests             49/0
run_rag_archive_tests          全部通过  (原 2 条红)
run_rag_remote_tests              9/0
run_embed_text_tests            243/0
run_rag_index_guard              67/0   (原 65)
run_rag_archive_guard            59/0   (原 57)
run_rag_index_guard_tests        80/0   (原 76)
run_rag_archive_guard_tests      37/0   (原 33)
run_ci_wiring_guard              18/0
run_kv_cache_tests               52/0
run_probe_syntax_tests          PASS（装了 g++ 后才真跑）
run_probe_syntax_stub_tests        3/0
```

`suite-counts.json` 已同步，`--check-total` 一致：**184** 个套件。

### 顺带修掉的一处文档漂移

`run_ci_wiring_guard` 抓到 5 处"文档写的 N 条 ≠ 清单条数"：
`run_kv_cache_tests` 44→**52**、`run_rag_index_tests` 90→**162**、
`run_rag_index_store_tests` 157→**180**、`run_rag_index_guard` 65→**67**、
`run_rag_index_guard_tests` 76→**80**。README 与 HTP-STATUS 两处都已改成实测值。
（这不是本轮引入的 —— `--write` 刷清单一照就显形。）

### 新增判据与自测桩

- `run_rag_archive_guard.sh` ② 段：把原来锚**字面写法**的两条
  （`grep '既不是信封…'`、`grep 'if (root.has("t") && root.has("s"))'`）
  换成**行为契约**三条（行数前提 / 多行按行解析 / 行数从文本自身数）。
  理由：字面锚在"源码形状要改"时必然撞红，而**改的方向恰好是把静默错修掉**——
  守卫那时反而成了阻力。
- 桩⑯/⑰（archive）：打掉"行数前提"，守卫变红并点名。
- 桩㊱/㊲（index）：阈值退回 `0.9999f`、期望 hits 写成 1，各自变红并点名。

## 能力边界（如实说）

- 真机上**导入 UI 的交互路径**未验（本轮验的是 `decode` 的纯逻辑端到端 +
  守卫的静态结构），要一次跑机确认"选文件 → 确认框条数 → Toast"三处
  现在都报 3。
- 嵌入路径 pooling 四档、base64 小端仍只在宿主复刻上验过，
  本轮未重新反汇编真机 vendor `.so`。
- 本轮**没有**逐行重读 M2~M8 的全部 9000 行 diff —— 读的是
  数据流上的关键判据（`rank`/`select`/`render`/三态出口/信封/行数口径）。
  `RagInject` / `EmbeddingsApi` / `RagProtocol` 三处的判据读下来是自洽的，
  但这不等于"整条线没有别的问题"。
