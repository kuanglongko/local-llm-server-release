# ISSUE #245 增量审查 · 双列清点表

范围：`0976137`（RAG 阶段 0）.. `HEAD`（`c6274eb`），47 个提交，158 个文件。

⚠ 上一版把会话线四行标成"本轮不审"、清点表里的 `session_*` 也整片划到"不审"。
本轮补审（见 `REVIEW-245-C2.md`），下面那几行的模块线已指到实处。
清点口径：**行 = 改动文件**，第二列 = 归到哪条模块线。指不到模块的就是漏项。

## A. 源码（26）

| 文件 | 模块线 |
|---|---|
| `app/src/main/cpp/embed_text.h` | M1 |
| `app/src/main/cpp/llama_jni.cpp` | M1 / M2 / M6 |
| `app/src/main/cpp/rag_index.h` | M2 |
| `app/src/main/cpp/kv_prefix.h` | M3 |
| `app/src/main/cpp/gguf_precheck.h` | M1 旁支（加载前置检查） |
| `LogFileStore.kt` / `LogExport.kt` | M1 旁支（探针日志） |
| `RagIndexStore.kt` | M2 |
| `RagInject.kt` | M3 |
| `RagArchive.kt` | M5 |
| `RagProtocol.kt` / `EmbeddingsApi.kt` / `HttpApi.kt` | M6 |
| `LlmEngine.kt` | M1~M6（编码/索引/检索/注入的装配面） |
| `EngineActivity.kt` / `ModelStore.kt` | M7 |
| `SessionStore.kt` | C1 / C2（已审，见 REVIEW-245-C2） |
| `SessionQuota.kt` | C3（已审 —— 抓到"顶到上限建不出空会话"） |
| `SessionCleanup.kt` | C4（已审） |
| `ChatArchive.kt` | C5（已审） |
| `.cnb.yml` / `CHANGELOG.md` / `HTP-STATUS.md` / `README.md` / `docs/HTP.md` / `app/build.gradle.kts` | M8 |

## B. tools（132）

| 套件目录/前缀 | 模块线 |
|---|---|
| `rag_index/` `run_rag_index*` | M2 |
| `rag_inject/` `run_rag_inject*` | M3 |
| `rag_edit/` `run_rag_edit*` | M4 |
| `rag_archive/` `run_rag_archive*` | M5 |
| `rag_remote/` `run_rag_remote*` | M6 |
| `rag_entry/` `run_rag_entry*` | M7 |
| `embed_robust/` `embed_entry/` `embed_params/` `run_embed_*` | M1 |
| `gguf_precheck*` `mmap_release*` `log_rotation*` `probe*` | M1 旁支 |
| `kv_prefix_test.cpp` `run_kv_cache*` | M3 |
| `session_*` `chat_archive*` `run_session_*` | C1~C5（**已审**：17 份套件实跑，4 条真缺陷已修） |
| `suite-counts.json` | M8 |

**结论**：158 个文件全部指得到模块线，本轮 M1~M8 范围内**无漏项**。

⚠ **第四轮（M 线复查）追加**：清点表只说"每个文件都归到了某个模块"，
**不代表"每个模块都真读过"** —— 上一版把"归属完整"当成了"审过了"，
于是 M2（`RagArchive.kt` 落在 M5 的档口、`RagIndexStore.rank` 落在 M2）
那两条真缺陷漏了过去。补记：

| 文件 / 判据 | 模块线 | 复查结果 |
|---|---|---|
| `RagArchive.kt` `decodeObjectOrLines` | M5 | **1 条恶性**：多行 JSONL 只剩 1 条（静默丢 N-1 条），见 `REVIEW-245-M-LINE.md` |
| `RagIndexStore.rank` 的测试格 | M2 | **2 条断言恒红**：`0.9999f` 当"全挡"阈值，那一格从没进过全挡分支 |
| `RagArchive` 的 2 条断言 | M5 | **2 条断言恒红**：按 key 相邻性锚 + 单行路径期望 `skipped == 1` |
