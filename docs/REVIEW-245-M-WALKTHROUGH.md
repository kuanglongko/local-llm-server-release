# Issue #245 · M2~M8 逐行走查（第三轮）

> 上一轮（`docs/REVIEW-245-M-LINE.md`）在末尾留了一句：
> "本轮**没有**逐行重读 M2~M8 的全部 9000 行 diff … 这不等于整条线没有别的问题。"
> 用户回的是「走一遍吧」。这一份就是那一遍的结果。

## 一句话结论

**走出来了 7 条**，其中 **2 条是产品侧真缺陷**（代理对截断 / 条目列表删错行），
**2 条是产品侧潜伏缺陷**（NaN 占 top-k / `local_rag` 库名与条数不同源），
**1 条口径分岔**，**1 条判据缺口**（集合看不见重复前缀），**1 条注释粘贴重复**。
全部已修，判据与自测桩已进守卫。

## 犯法点复盘（第三轮了，得说清）

前两轮的教训我写的是"读守卫的**结论**，没读**判据**"。这一轮**没有**再犯那个 ——
工具链是装齐的，凡是宣告"全绿"的套件都贴得出实跑输出。

但这轮暴露了**同一族的第三张脸**：我读的是**数据流上的关键函数**，
而不是**每一个落盘的出口**。具体说 —— 我盯着 `rank` / `select` / `render` /
三态出口 / 信封 / 行数口径这些"判定"读了很久，却漏了**三处 `substring` 截断**
和**一处 UI 行号**：它们不在"判定"里，在"落盘前的最后一米"。
而恰恰是那一米，能把内存里"看着对"的东西写成磁盘上"已经烂掉"的东西。

所以这轮补的规矩是：**逐行走查要按「落盘出口」数，不只按「判定函数」数。**
一个值从内存到磁盘，中间每一次 `substring` / `toByteArray` / `put` 都是一站。

---

## 逐条账

### M5-1（产品侧 · 真缺陷）导入时按 code unit 截断，劈开代理对 —— 内容不可逆损坏

`RagArchive.entryOf`：

```kotlin
val text = if (t.length > MAX_TEXT_CHARS) {
    extra = 1
    t.substring(0, MAX_TEXT_CHARS)     // ← Kotlin 下标是 UTF-16 code unit
} else t
```

`MAX_TEXT_CHARS` 是 **code unit 数**，而一个 emoji（非 BMP 字符）占**两个**。
切点落在代理对中间时，留下的是一个**孤立高位代理**（U+D83D）。

这个值在内存里**测不出来**：

| 检查 | 结果 |
|---|---|
| `JSONObject` 往返 | **通过** |
| 字符串 `==` | **通过** |
| `.length` | **正常**（10 == 10） |
| `toByteArray(UTF_8)` 再读回 | **不一致**（U+D83D → `?`，U+FFFD） |

实测（`a`×9 + `😀`，上限 10）：

```
内存   code=97,97,97,97,97,97,97,97,97,55357
落盘   code=97,97,97,97,97,97,97,97,97,63
```

用户看到的是"已导入 1 条"，而落进 `texts.jsonl` 的内容**少了一个字符且不可逆**。
更坏的是它**自洽**：`textsHashOf` 算的就是落盘后的字节，`load` 读的也是那些字节
—— 没有任何一处会报错。

**为什么之前的判据全绿**：`RagArchiveTest` 只断言 `.length` 与字符串相等，
两者对孤立代理都是"通过"。**判据必须过字节**，这条已经写进新测试的注释。

### M4-1（产品侧 · 真缺陷）追加一条时同一处截断

`LlmEngine.addEntry`：

```kotlin
val clipped = if (truncated) trimmed.substring(0, cp.targetChars) else trimmed
```

同一形态。而**分块路径早就修过了** —— `chunk()` 里那句
`var hard = alignUtf8(text, end)`，`alignUtf8` 的注释里记着两个真踩过的坑
（越界、按字节的 `0xC0` 判据误伤 `文` U+6587）。也就是说：
**同一个仓库里，一条路修了、另一条路没修**，而两条路写的是同一件事。

（`ChatArchive` 的 `MAX_CONTENT_CHARS` 是第三处，同形态，一并修了 ——
它在 C5 范围，属顺手。）

**修法**：新增 `TextBoundary.clipAtCharBoundary`，三处共用。

### 为什么单独开一个 `TextBoundary.kt`（而不是挂进 `RagIndexStore`）

第一版我把它挂在 `RagIndexStore` 上，结果 `run_chat_archive_tests.sh` 直接挂：
`unresolved reference: RagIndexStore`。

原因是这份测试**只编 `ChatArchive` + `SessionStore` + `SessionQuota`**
（`ChatArchive` 的头注释写着"纯逻辑，不碰 Context / 文件系统"）。
挂在 `RagIndexStore` 上等于把一个**纯逻辑**文件拖进 Android 栈
（`Context` / `File` / `android.util.Log`）。

这不是"源列表补一行"能解决的 —— 那会让纯逻辑文件开始依赖 Android。
所以它自成一个**零依赖**（连 `org.json` 都不用）的小文件：谁都能引，且引它
**不会**多出任何依赖。

> 顺带：那三份脚本的源列表现在都显式带上了 `TextBoundary.kt`，
> 并加了一条守卫判据钉住 —— `run_chat_archive_tests.sh` 的注释里
> 恰好早写着"漏了它整套以 unresolved reference 结束"这条教训。

### M3-1（产品侧 · 潜伏）`rank` 的 NaN 没被丢掉，会**占掉一个 top-k 名额**

```kotlin
if (minScore > 0f && !(score >= minScore)) continue   // ← NaN 只在这一句里被顺带挡住
passed.add(SearchHit(r, score))
```

NaN **只在 `minScore > 0f` 时**被挡住。而调用方可以传 `minScore <= 0`
表示"不按分数过滤"（`RagInject.select` 与 `rag_index.h::select_injections`
都把这个口子写在注释里）—— 那时整句短路，NaN 进 `passed`：

- `compareByDescending` 把 NaN 排在**最前**（Kotlin 的 Float 比较里 NaN 大于一切）；
- 于是它**占掉一个 top-k 名额**，把一条真及格的命中挤出去；
- 排序全对、只是少一条，接口 200、日志干净。

而 `RagIndexStore` 的注释写着"NaN 分数…**也不进 passed**"、
`RagInject.select` 写着"NaN 的分数**一律丢掉**" —— **两处承诺，代码都没做到**。

**为什么之前的判据没抓到**：`RagIndexStoreTest` 里真有一格 `nanMix`（`minScore = 0f`），
但它只断言了 `best`：

```kotlin
ok(!r.best.isNaN() && r.best == 0.5f, "rank：NaN 行不影响 best（best 取真实的 0.5）")
```

`best` 恰好仍是对的（NaN 不参与 best 比较），所以这一条**恒真**，正好把
"hits 里混进了 NaN"盖住 —— 与上一轮 `rank` 全挡那一格是**同一个形态**：
一条恒真断言夹在真断言中间。

**修法**：`if (!score.isFinite()) continue`，排在阈值比较**之前**，与阈值开不开无关。
新增 `check_rank_nan_guard.py` 比的是**顺序关系**（`isFinite` 必须在
`passed.add` 与 `minScore > 0f` 之前），不是"文件里出现过 isFinite"。

### M6-1（产品侧 · 潜伏）`local_rag` 的库名与条数**不同源**

`ragFieldOf(count)` 到**响应时**才 `remoteKb(prefs)` 现读库名，而条数是
**注入那一刻**写的：

```kotlin
val ragField = ragFieldOf(lastRagInjected.get())   // 库名现读
private fun ragFieldOf(count: Int) = RagProtocol.responseField(
    if (count > 0) RagProtocol.Injection(RagInject.remoteKb(ragPrefs()), count) else null)
```

长生成可以几十秒。用户在生成期间把「远端库名」从 A 改成 B：

```
本轮实际：拿 A 检索、注入 3 条
响应里写：kb:"B", injected:3        ← 一对从未同时成立过的事实
```

调用方据此判断"哪个库在工作"会得出错的结论。字段合法、JSON 合法、只是不真。

这正是本仓库反复要消灭的"分成两次读，中间恰好变了一次"那一族
（`RagIndexStore.readState` 的注释里就有同款）。**而 `RagBlock` 已经带着
`rag.kb`** —— 检索当时真正用的那个库名就摆在手边，被丢掉了。

**修法**：注入成功那一刻把库名与条数**一起**写下（`lastRagKb = rag.kb`
紧挨着 `lastRagInjected.set(rag.count)`），响应时只读这两个共享值。

### M7-1（产品侧 · 真缺陷）条目列表的行号是"压紧后的下标"，点删除会**删错一行**

```
列表「删」→ confirmDeleteEntry(i) → doDeleteEntry → removeEntry(name, index)
                                                      ↑ 体内 lines[index]（正文行号）
```

而列表数据来自 `readEntries` —— 它把空行/坏行**跳过并压紧**：

```kotlin
refreshRagRows: RagIndexStore.readEntries(this, name).map { it.text }   // 压紧
renderRagRows:  ragRowsShown = rows.mapIndexed { i, t -> i to t }        // i = 压紧后的位置
```

正文里只要出现一个空行或坏行，`i` 就不再是行号。实测（正文 = 甲 / 坏行 / 乙）：

| 列表显示 | 点「删」传 index | removeEntry 实际删的 |
|---|---|---|
| 1. 甲 | 0 | `lines[0]` = 甲 ✅ |
| 2. 乙 | 1 | `lines[1]` = **坏行** ❌ |

- **空行**会被 `removeEntry` 的 `lines[index].isEmpty()` 挡住（表现为"点了没反应"）；
- **坏行（非空但非法 JSON）挡不住** —— 它真的被删掉，而"乙"还在。

更坏的是此后**索引与正文整体错位一格**（索引里没有那个坏行），
检索命中的下标与注入的正文说的不再是一件事（分数对、内容错）。

`renderRagRows` 的注释其实写着"序号是正文文件里的行号，
而用错了不会报错：删掉的是隔壁那一行" —— **注释是对的，实现反了**。

**修法**：`refreshRagRows` 改用 `readEntriesAligned`（保留 `null` 占位，
下标恒等于行号 —— 注入侧早就在用它）；渲染时空段与坏行不给「删」按钮
（坏行显式标红提示"索引未采用它"），但**行号 `i` 原样保留**。

### M2-1（口径分岔）"库里已有 N 条"与索引侧**不是同一个数**

```kotlin
fun entriesCountOf(...) = texts.split('\n').count { it.isNotEmpty() }   // 数非空行
fun rowsToCount(rows) = if (rows > 0) rows - 1 else 0                    // 段数 - 1
```

在**自产文件**上两者恰好相等（`encodeTexts` 每行以 `\n` 收尾），
所以只有在"文件被外部改过"时才分家 —— 而 `importEntries` 写的正是
`磁盘上已有正文 + encodeTexts(新增)`，其中"已有正文"是**从磁盘原样读回来的**。

实测 8 种形状里 **6 种不等**（末尾无换行、中间有空行、多个连续空行、开头空行…）。
后果：确认框说"已有 3 条 → 导入后 5 条"，而落盘按段数算 ——
一处对不上，两处都不可信。

**修法**：`entriesCountOf` 与索引侧共用 `rowsToCount(rowsOf(...))`。
新增 `check_entries_count.py` 正面钉住"用的是这两个函数，不是数非空行"。

### M8-1（判据缺口）`check_fp_fields.py` 只比**集合**，看不见**重复前缀**

`fpFields()` 有一句：

```kotlin
if (k in out) continue     // 重复前缀取第一个，后面的静默丢掉
```

而 `check_fp_fields.py` 用 `{x for x in out}` —— **集合**。于是"某个前缀写了
两遍"这个事实在集合里**完全看不见**（`{'k'}` 与 `{'k','k'}` 相等）。

本次实查：`RagIndexStore.fingerprintOf` 里 `sb.append("k")` 上方的 6 行注释
被**粘贴了两遍**（`e04ea3c` 引入）。这次只是**注释**里的重复（`strip_comments`
会剥掉），**无功能影响** —— 但同一种粘贴动作落在 `sb.append` 那一行上，
就是"那个字段不参与指纹比对"（= 那一类变化不再作废索引，旧向量被继续用）。

**修法**：`cpp_fields` / `kt_fields` 改回**有序列表**，新增"前缀不得重复"判据
（顺序敏感）。桩㊸ 打一个**真重复**的 `sb.append("k")`，守卫变红并点名。

同时清掉那段重复注释。

### M2-2（吹毛求疵）重复的注释块

`fingerprintOf` 里 `k` 字段的说明写了**两遍**（逐字相同）。已删一份。

---

## 判据侧：全部实跑（贴得出输出）

```
run_rag_index_guard               79/0   (原 67)
run_rag_index_guard_tests         94/0   (原 80)
run_rag_index_store_tests        221/0   (原 180)
run_rag_index_tests              162/0
run_rag_archive_guard             59/0
run_rag_archive_guard_tests       37/0
run_rag_archive_tests          全部通过
run_rag_inject_guard              17/0
run_rag_inject_guard_tests        32/0
run_rag_inject_tests              49/0
run_rag_remote_guard              18/0   (原 15)
run_rag_remote_guard_tests        32/0   (原 26)
run_rag_remote_tests               9/0
run_rag_edit_guard                19/0   (原 17)
run_rag_edit_guard_tests          50/0   (原 38)
run_rag_entry_guard               22/0
run_rag_entry_guard_tests         47/0
run_chat_archive_guard            45/0
run_chat_archive_guard_tests      27/0
run_chat_archive_tests          all passed
run_ci_wiring_guard               18/0
run_pure_logic_guard              34/0
run_ui_io_guard                   41/0
run_kt_test_source_guard           4/0
--check-total             184 一致
```

**全量 Kotlin 编译**（`$SRC/*.kt`，kotlinc 2.0.21 + android.jar 35 + json.jar）：
**0 error**。C++ `rag_index.h` 单独编 + 跑通。

新增自测桩 **14 个**（㊳~㊹ + 远端 ⑬~⑮ + edit ⑫~⑭），每个都验证
"守卫变红、且指得出是哪一条"。

## 顺带修掉的文档漂移（`run_ci_wiring_guard` 抓到）

`run_rag_edit_guard` 17→**19**、`run_rag_edit_guard_tests` 38→**50**、
`run_rag_index_guard` 67→**79**、`run_rag_index_guard_tests` 80→**94**、
`run_rag_index_store_tests` 180→**221**。README 与 HTP-STATUS 两处都已改成实测值。

## 能力边界（如实说）

- **真机上没跑过**。本轮验的全是宿主侧：纯逻辑复刻（喂真数据、过 UTF-8 字节）、
  全量 Kotlin 编译、守卫静态结构。**"选了文件 → 导入 → 列表里点删"这条真机
  交互路径未验** —— 尤其是 M7-1 修完后"坏行那一栏不给删按钮"在真机上的观感。
- 嵌入路径 pooling 四档、base64 小端仍只在宿主复刻上验过
  （本轮把 base64 与 Python `struct.pack('<f')` 逐字节对拍过，**一致**；
  但真机 vendor `.so` 的布局未重新反汇编）。
- 本轮走的是**数据流 + 落盘出口**（每个 `substring` / `toByteArray` / `put` /
  行号构造点）。仍然**没有**逐行读完 9000 行 —— 按"改动面清单"扫的。
  没扫到的地方就明说没扫到。

## 下一轮如果我还会漏，最可能漏在哪

按这三轮的形态，下一个最可能的是**"读的那一侧"**：本轮盯着"写前最后一米"
（截断 / 落盘），而读回来的那一侧（`decodeTextRow` / `fingerprintIn` /
`readEntriesAligned` 的边界）只做了回忆式复核，没做同强度的穷举。
若要继续，建议按 **"读回来的每一个边界"** 再走一遍。
