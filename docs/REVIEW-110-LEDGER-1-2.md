# Issue #110 增量：账目① / 账目② 收口

本文件是 **Issue #110「代码审查」增量审查第 3 项「账目」** 里那两笔账的收口结论。
它回答三件：**[账是什么]、[补了什么尺子]、[这些尺子能不能自己站住]**。

> 议题背景、账目原样与逐项判据的原始记录在 Issue #110 的评论里（本文件只做收口）。
> 本文件里的套件条数由 `tools/run_ci_wiring_guard.sh` 的判据⑤ 与
> `tools/suite-counts.json` 逐条钉住 —— 本文的数字和别的文档受同一把尺子约束。

---

## 一、一句话结论

两笔账都是**「数字 / 清单没有尺子」同一族**，只是落在不同的写法上：

- 账目①：**「N 条」有尺子（判据⑤），「N 个套件」没有。** 于是 `§5.2` 写着
  83 个套件、磁盘实际 124，而判据⑤ 全绿。补**判据⑧** + 清单的 `suite_total`，
  与「N 条」**共用同一把尺子**（同一份 `suite-counts.json`）。
- 账目②：**`DROP_EXACT` 是硬编码清单，此前没有任何东西守它。** 把 4 个名字里
  任何一个删掉都不会红 —— 而"小结被误发进发布包"正是这条规则存在的全部理由。
  补 4 条判据（26 → 30）。

---

## 二、账目①：「N 个套件」接上清单

### 2.1 账是什么

判据⑤ 只认表格里写成 `` | `tools/run_<名字>.sh` | **N 条** `` 的行 —— 它核**逐套件**的条数。
文档里的**总数**（「83 个套件」「42 → 81 个」）`DOC_ROW` 正则**匹配不到**：

```
「N 条」   —— 有尺子（判据⑤，对 suite-counts.json 的 totals）
「N 个套件」—— 没有尺子（DOC_ROW 匹配不到 → 全绿）
```

实测（HEAD）：`§5.2` 声称 **83**，磁盘 **124**。41 个套件落了、目录一个都没动。
**这不是新漂移，是同一族漂移在总数这一维上原样复现。**

### 2.2 `REVIEW-110-SUMMARY.md` 的 `42 → 81` 不是漏更新

`git ls-tree a2e3b72 tools/ | grep -c run_` = **81** —— 落盘当天磁盘上正是 81 个。
它是全套文档里唯一一个「落盘即真」的数，只是没人回头改。

改成**标明时刻**的写法：历史值照原样留着，当前值只在 HTP-STATUS §5.2 给一处。
**历史叙述不核** —— 它标的是过去某一刻的真值，本就该保持不变。

### 2.3 补的尺子

| 位置 | 内容 |
|---|---|
| `tools/count_suite_totals.py --write` | 除 `totals` 外另落 `suite_total`（= 目录里离线套件数，与 `suite_scripts()` 同一个 glob） |
| `tools/count_suite_totals.py --check-total` | 核 `suite_total` 与磁盘一致（静态、秒级，已接进 `.cnb.yml`） |
| `tools/run_ci_wiring_guard.sh` **判据⑧** | 文档里写的 `**现 N 个**` 必须等于清单的 `suite_total`（15 → 18 条） |
| 自测桩⑪ / ⑪b | 文档改坏 / 清单改坏，**两侧各打一根**（32 → 36 条） |

判据⑧ 与判据⑤ **同一把尺子**：都取 `tools/suite-counts.json`。
另加一条防"两把尺子"：清单的 `suite_total` 必须等于磁盘上的套件个数。

---

## 三、账目②：`DROP_EXACT` 补 4 条断言

### 3.1 账是什么

`scripts/release.py` 的
`DROP_EXACT = {CHANGELOG.md, docs/RELEASE-WORKFLOW.md, docs/REVIEW-110-SUMMARY.md, HTP-STATUS.md}`
是"只存在于日常线、不随发布分发"的**硬编码清单**。扩它那次（`00946a5`）只动了
`release.py` 自己，守卫里 `grep DROP` = **0** —— **删掉任何一个名字都不会红。**

### 3.2 补的判据（锚结构关系，不是存在性）

| 判据 | 抓的形态 |
|---|---|
| `DROP_EXACT` 是一个可解析的**集合字面量** | 把它写进判据文案 / 注释就恒真的那种 |
| 含全部 4 个开发期文档（按名字） | 少一项（桩⑬ = SUMMARY 那一项） |
| `is_dropped()` **真的读** `DROP_EXACT` | 定义了却不用 = 整份清单空转（桩⑮） |
| 每一项**真在磁盘上** | 拼错一个字符 = 规则恒不命中（桩⑭） |

第 4 条最隐蔽：清单"看着齐全"（项数对、名字像），但拼错的那一项**恒不命中**，
发布器照旧报"已清洗"。存在性断言抓不到，只有"每一项真在磁盘上"能抓。

> 只对 `DROP_EXACT` 判"存在"，**不对 `DROP_PREFIX`**：后者是**前缀**，其中
> `scripts/release-overrides/` **刻意不存在**（覆盖层通路从未被跑过），
> 按"必须存在"判会误红。

---

## 四、双向验证（不是"写完就绿"）

| 对象 | 真源码 | 打桩 |
|---|---|---|
| `tools/run_ci_wiring_guard.sh` | PASS **18** / FAIL 0 | 桩⑪ / ⑪b 各红，点名判据⑧ |
| `tools/run_ci_wiring_guard_tests.sh` | PASS **36** / FAIL 0 | — |
| `tools/run_release_hygiene_guard.sh` | PASS **30** / FAIL 0 | 桩⑬ / ⑭ / ⑮ 各红并点名 |
| `tools/run_release_hygiene_guard_tests.sh` | PASS **34** / FAIL 0 | — |

桩全部取自**真正的旧写法 / 最小改动**，两侧各打一根：只查文档，会在"清单自己也漂"
时全绿；只查清单，会在"文档也漂"时全绿。判据⑤ 的桩⑧/⑧b 是同一规矩。

`tools/run_assertion_anchor_guard.py`（甲-1）在干净树上 **PASS 8 / FAIL 0**。

---

## 五、明确没做的事

- **没做真机验证**（无设备，且本轮未改任何 `.kt`/`.cpp`）。
- **未改 `HTP-STATUS §5.2` 表格里任何逐套件的「N 条」** —— 那是判据⑤ 的活，
  它现在核得动。
- `count_suite_totals.py --check --static-only` 本机跑**超过 10 分钟**
  （89 个快套件，其中 `run_schema_sampler_guard.sh` 等本身就不快），本轮
  **未等它跑完**；`suite_total` 与 `--check-total` 是**秒级静态**的，不依赖工具链，
  已实跑通过。
- **未动 `DROP_PREFIX` 里的 `scripts/release-overrides/`** —— 详见 §3.2 的注。

`Ref: #110`
