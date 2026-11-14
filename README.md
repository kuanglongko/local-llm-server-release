# Local LLM Server（小万本地推理）

Android 手机上**完全离线**的 GGUF 大模型推理 App：在应用进程内用 llama.cpp 跑推理，
一边提供图形界面聊天，一边对局域网提供 OpenAI 兼容 HTTP 接口。模型、会话与知识库只留在本机。

| 项目 | 说明 |
|---|---|
| 包名 | `com.xiaowan.localinference` |
| 系统要求 | Android 8.0+（API 26），targetSdk 28 |
| ABI | 仅 `arm64-v8a` |
| 构建 | Kotlin + JNI / C++17 |
| 运行时依赖 | 仅 Android framework 与 `org.json`，无账号、无遥测 |
| 许可证 | MIT |

## 主要特性

- **应用内聊天**：与 HTTP 共用同一份模型与上下文；流式输出、随时停止、重新生成、单条删除、多会话与导入导出，支持角色卡与知识库。
- **图形界面**：模型库（列表 / 别名 / 常驻标记 / 加载 / 卸载）、参数设置、服务开关、日志筛选与导出、一键还原默认。
- **OpenAI 兼容 HTTP 接口**：对话、文本补全、模型列表、向量、中止与健康探测，支持流式输出、`tools`、`stop`、`seed`、`response_format`。
- **Anthropic 兼容**：提供 `/v1/messages`，Claude 官方 SDK 与任何 Anthropic 协议客户端改 `base_url` 即可接入，支持流式、工具调用与思考预算。
- **结构化输出**：`json_object` / `json_schema` 在生成时逐 token 约束，输出可直接被程序解析。
- **KV 前缀复用**：多轮对话与固定 system / tools 段只计算新增部分，长会话不必每轮重新 prefill。
- **上下文水位治理**：每格按水位预留生成空间，水位可选 70% / 80% / 85% / 90% / 95%（默认 85%）；开启主动治理后提前丢最老轮次并落在稳定起点，KV 前缀仍可复用。
- **投机解码（默认关）**：可选 MTP、外部 draft 模型或 NGram 自投机，草稿数可调；请求里的 `speculative` 字段可在请求期关闭或压小草稿数。默认贪心请求不投机，保证与关闭时逐字一致。
- **嵌入与知识库（RAG）**：对话模型与嵌入模型可同时加载；文档分块、编码、保存，正文变化时提示重建索引。
- **按需加载模型（默认关）**：打开后第一个请求才加载模型，空闲超时自动释放；App 与 HTTP 共用同一份模型生命周期。可另行允许请求按名字指定模型。
- **多模型池（默认关）**：同一进程内同时驻留多份对话模型，按请求里的 `model` 路由；支持常驻标记、空闲释放与内存紧张时自动清理，切换模型不必整份重载。
- **悬浮球（默认关）**：在其他应用上层显示服务状态（已停止 / 空闲 / 模型名 / 推理中），可拖动、单击打开 App、长按复制服务地址；灭屏自动移除，不轮询。
- **干净的正文**：屏蔽模型声明为不可采样的控制 token，工具调用标记与思考标签照常保留。
- **模板自动适配**：识别旧版 Gemma 4 等模板并在内存中升级为官方写法，旧 GGUF 不用重打包。
- **可调参数**：`n_ctx`、线程数、`parallelN`（**并发 slot 数**，默认 1）、`batch/ubatch`、采样与惩罚参数、Flash Attention、KV cache 量化、权重重排、mmap、默认关闭思考。
- **推理后端**（进程内推理）：CPU（按指令集选 4 个预编译变体）+ OpenCL GPU + Hexagon NPU（HTP，实验性，默认关闭）。
- **可取证 / 可恢复**：Java 异常与 native 崩溃现场落盘；设置页可一键还原默认参数。

## 快速开始

1. 从 Releases 安装 APK（当前只提供 debug 构建；仓库不含模型权重）。
2. 把 `.gguf` 放进 `Android/data/com.xiaowan.localinference/files/models/`，在 App 里「添加模型」时选类型（对话 / 向量），再选中并加载。
3. 在「设置」里调整推理参数；App 内参数优先，并覆盖请求里的同名字段。
4. 给其他设备使用：打开「局域网访问」，记下端口与通知里的 IP；不打开时只绑定 `127.0.0.1`。
5. 可选：在「设置 → 本地服务 → 接口鉴权」生成 token 并填进客户端。鉴权默认不开启。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m",
       "messages":[{"role":"user","content":"用一句话解释 KV cache"}],
       "stream":true}'
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 自带测试页，可在浏览器里探测 `/health`、查看模型、流式聊天 |
| OPTIONS | 任意路径 | CORS 预检，统一返回 `204` |
| GET | `/health` | 存活探测，含 KV 复用与后台冻结痕迹字段 |
| GET | `/v1/models` | 模型列表（未加载时也列出模型库） |
| GET | `/v1/models/{id}` | 单个模型；当前加载的模型带 `profile` 档案 |
| POST | `/v1/chat/completions` | 对话补全，支持 `messages`、`stream`、`tools`、`stop`、`response_format` |
| POST | `/v1/messages` | Anthropic Messages API 兼容端点，支持流式、`tools`、`tool_choice`、`thinking` |
| POST | `/v1/relay/chat/completions` | 中间层入口，请求形状与对话补全相同 |
| POST | `/v1/completions` | 文本补全，支持 `prompt`、`stop`、`response_format` |
| POST | `/v1/abort` | 取消当前生成，无 body、无参数 |
| POST | `/v1/embeddings` | 文本向量；需要先加载嵌入模型，不支持 `dimensions` |

**请求约定**

- 采样参数缺省时使用与 App 参数区一致的默认值，也支持 OpenAI 的 `seed`；显式传入的非法值返回 `400`。
- 思考控制：`enable_thinking` 是显式开关；`reasoning_budget` 取 `-1` / `0` / 正整数；`reasoning_effort` 是档位建议；`reasoning_format` 决定思考段是否切到 `reasoning_content`。默认关闭思考，非法取值返回 `400`。App 设置页可保存全局默认，请求字段优先。
- 请求体上限 4 MiB（超限 `413`）；请求行与单个头行上限 8192 字节（超限 `400`）。
- 生成端点接受 `stop` / `stop_sequences`；`prompt` 与 `messages` 超限时明确拒绝，不静默截断。
- `max_tokens` / `max_completion_tokens` 支持 1~262144，两者同义、同时给出时必须相等；真正生成长度按当前上下文自动调整，编码 agent 默认的 32000 / 128000 可直接用。
- 投机解码由加载期配置决定类型（MTP / 外部 draft / NGram）；请求里的 `speculative` 可取 `true` / `false` 或 `{"enabled":bool,"n_max":N}`，只能关闭或压小草稿数，不能改类型。
- `n` 只支持 1，其他取值返回 `400`。
- `finish_reason` 支持 `stop`、`length`、`tool_calls`、`cancelled`。
- 诊断读数走 `X-Local-Ctx` 响应头，正文保持 OpenAI 形状。
- `tool_choice`（`auto`/`required`/`none`）与 `parallel_tool_calls` 均生效。

### 鉴权（可选，默认不开）

在设置页「本地服务 → 接口鉴权」生成 token 后，生成端点要求：

```http
Authorization: Bearer <token>
```

以下路径一律免鉴权：`OPTIONS`、`GET /health`、`GET /v1/models`、`GET /v1/models/{id}`、`GET /`。
路径按“落在之下”匹配，`/healthz`、`/v1/models-evil` 不会被误豁免；其余 `/v1/*` 需要凭据。

不带或带错 token 返回 `401`，错误正文只说明原因，不回显 token。token 走明文 HTTP，
挡不住同网段能直连的设备，**不是访问控制**，请在可信网络使用。
Anthropic 客户端习惯的 `x-api-key: <token>` 与 `Authorization: Bearer <token>` 都接受，两者同时存在时以 `Authorization` 为准。

### Anthropic Messages 兼容（`/v1/messages`）

Claude 官方 SDK（`anthropic`、`@anthropic-ai/sdk`）以及任何按 Anthropic 协议写的客户端，把 `base_url` 指到本服务即可使用，无需改代码。

- 支持流式与非流式，流式按 Anthropic 标准事件序列输出（`message_start` → `content_block_*` → `message_delta` → `message_stop`）。
- `messages` / `system` / `max_tokens` / `temperature` / `top_p` / `top_k` / `stop_sequences` / `stream` / `model` 均生效。
- `tools` / `tool_choice` / `parallel_tool_calls` 完整支持，响应侧返回 `tool_use`，结束原因为 `tool_use`。
- `thinking` 支持 `enabled` + `budget_tokens`；思考内容不对外回放，只返回正文。
- 图片、文档等非文本 content block 明确返回 `400`，不静默丢弃。
- 请求头 `anthropic-version` 收下但不校验（本服务只有一个版本），响应头回写 `anthropic-version` 与 `request-id`。

### 并发与上下文

**并发形态：`parallelN` 个 slot（默认 1）**。`parallelN = 1` 时只有一份 ctx、一条 seq，HTTP 与 App 内聊天共用，
请只在**一侧**使用；两路同时跑会互相顶掉 KV 前缀。`parallelN > 1` 时最多 N 个并发请求，第 N+1 个返回 `503`，不排队。

- `n_ctx` 就**是每一格的容量**，格数只决定开几格；`parallelN=2` + `n_ctx=4096` 表示两格各 4096。
- **`N` 个 slot ≠ HTTP 拿 `N` 个**：slot 0 保留给 App 内聊天，HTTP 只拿 N-1 个。
- **每路会变慢**：多路抢同一份内存带宽，并发买的是总吞吐。
- `parallelN` 是同一模型的并发数；多模型池是不同模型同时驻留，两者都会增加内存占用。

建议保持 1；确实需要 App 与 HTTP 同时使用时，再调到 2 以上并重新加载模型。

**上下文水位治理**默认只做兜底：装得下就不丢历史，只有真的越过每格容量才裁剪。打开「主动治理」后会在水位线提前丢掉最老的轮次，并落在下一次动手前仍有余量的位置，避免每轮都断 KV 前缀。水位按 70% / 80% / 85% / 90% / 95% 五档选择，默认 85%。

### 多模型池（默认关）

设置页打开「多模型」后，服务模式按 **多模型 > 按需 > 手动** 判决，`/health` 的 `model_access_mode` 会写明当前生效的是哪一种。

- **路由**：请求里的 `model` 按 `/v1/models` 的别名或文件名匹配（默认开启）；可设置默认模型与兜底模型，名字不存在时明确失败。
- **驻留与释放**：默认最多 2 份（可设 1~8），每份独立占用 ctx 与 KV；「常驻」模型不参与空闲释放，其余在空闲超时或内存紧张时按 LRU 清理，至少保留一份。
- **加载与卸载**：手动加载直接进池，下一轮对话直接复用；池内某一份可单独卸载，容量不足时等待 LRU 腾位置后重试。

### Relay 中间层（`/v1/relay/chat/completions`）

把客户端的 `base_url` 从 `/v1` 换成 `/v1/relay` 即可接入，请求形状与 `/v1/chat/completions` 完全相同。
它复用同一套 slot 调度与 KV 账本，会自动裁掉服务端已经丢弃的轮次，客户端可以始终发送完整历史；
异常时退化为直连，不会额外返回 `502`。会话键取 `X-Relay-Session`。

### 浏览器 / CORS

默认只允许显式白名单里的源，**不用 `Access-Control-Allow-Origin: *`**，避免任意网页直接调用本机服务。
`OPTIONS` 预检统一返回 `204`；`GET /` 是自带测试页，与 API 同源，所以页面本身不需要跨域。

CORS 只决定浏览器是否把响应交给页面，**CORS 不是访问控制**；任何绕过浏览器的客户端都不受它约束。

### 自带测试页（`GET /`）

浏览器打开 `http://<手机IP>:<端口>/` 即可使用。页面由 `WebChatPage.kt` 生成，是**纯函数产物**，可离线断言。

设置区使用原生 `<details>`，**默认收起，四组**：思考链、生成与采样、重复与惩罚、结构化输出。
思考链三态 —— **跟随服务端默认** / 开 / 关；选择「跟随」时不下发该字段。

多轮对话有三条硬判据：只有成功的回复才进历史；点「新对话」清空气泡；后续请求真的带上历史。

### 知识库与嵌入

- 嵌入模型与对话模型可同时加载；知识库按「分块 → 编码 → 落盘」建立索引，正文或参数变化时提示重建。
- App 内「知识库」页支持保存正文、重建索引、追加、删除、导出与导入；`/v1/embeddings` 需要先加载嵌入模型。

### 角色卡

角色卡给会话的 `system` 起一个名字，方便复用；卡片就是那段 `system` 文本。
会话保存内容快照，改卡不影响已挂卡的会话；切卡会作废 prompt 缓存。

### 后台保活与前台服务

**服务自身的前台服务类型**声明为 `dataSync|specialUse`，其中 `specialUse` 用于准确说明常驻推理的用途。

`oom_score_adj` 由「进程是不是 FGS」决定：FGS 能提高进程优先级，但**换不到冻结豁免** —— 冻结判据只看内存压力与厂商策略。
声明 `specialUse` 的收益是语义完备、不吃将来的时间额度，而不是承诺「不再被冻」；部分国产 ROM 仍需手动加白名单。
服务通知使用静音的高优先级渠道；这条路不保证免冻，真要压住冻结仍需 OEM 白名单或 wake lock。

### 崩溃取证

native 的 `abort`（SIGABRT）由 libc 直接终止进程，`UncaughtExceptionHandler` 抓不到。排查步骤：

1. 在调试页打开「崩溃取证模式」，完全退出并重启 App（探针必须在冷启动时挂载）。
2. 复现请求。
3. 重开 App，在调试页「导出崩溃探针」，从公共「下载」目录取 `probe-<时间戳>.txt`。

探针在 `JNI_OnLoad` 自举，自举阶段**只记不写**：不建文件、不落盘，只进 logcat。
调试页的「关」也会显式告知 native，开关的唯一事实来源是调试页。

## 数据存放位置

| 内容 | 路径 |
|---|---|
| GGUF 模型 | `<getExternalFilesDir(null)>/models/*.gguf`（外部存储不可用时回落到 `<filesDir>/models`） |
| 会话记录 | `<filesDir>/<SessionStore 目录>/s_<id>.json`，当前会话 id 存于 `cur` |
| 角色卡 | `<filesDir>/cards.json`，一个 JSON 数组装整个卡片库 |
| 运行日志 | `<filesDir>/logs/session-<启动时间>.log`，保留最近 8 个会话 |
| 导出日志 | 调试页导出到公共「下载」目录（API 29+ 走 MediaStore） |
| 聊天记录导出 | 会话管理「导出全部」或长按会话「导出这一条」 |
| 崩溃现场 | `<getExternalFilesDir(null)>/crash_last.txt` |
| native 崩溃取证 | `<filesDir>/logs/probe-native.log`，另有上一代 `probe-native.log.prev` |
| 参数 / 端口 / 别名 | `SharedPreferences` |

## 支持的后端与机型

推理核心使用 [Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn) 的预编译产物（`vendor/`，未做二进制改写）。
启动时逐级尝试，任一失败即降级：

| 顺序 | 条件 | 后端 |
|---|---|---|
| 1 | 高通 SoC + i8mm + `libOpenCL.so` | CPU + OpenCL GPU + Hexagon NPU |
| 2 | i8mm | 纯 CPU |
| 3 | dotprod | 纯 CPU |
| 4 | 基线 | 纯 CPU |

Hexagon NPU（HTP）默认关闭。开启方式：设置里打开 HTP → 完全退出并重启 App → 加载模型后确认日志显示 HTP 生效。
缺任一条件会静默回退，不影响使用。说明与排查见 **[docs/HTP.md](docs/HTP.md)**。

> NPU 上不一定比 CPU 快。小模型（约 ≤4B）通常 CPU 更优；NPU 的价值主要在长 prompt 的 prefill。

## 已知限制

- 仅支持 `arm64-v8a`；当前只提供 debug 构建，release 还需要补签名与 minify 配置。
- **并发 slot 数**：`parallelN` 默认 1，只有一份 ctx，请只在 App 或 HTTP 任一侧使用；`> 1` 时每路会变慢，slot 0 保留给 App。
- 结构化输出受模型模板能力约束，转换不出 GBNF 时会降级为无约束并留日志。
- 「思考开 + `json_schema`」的语义冲突尚未处理；默认关闭思考，常态下不会遇到。
- `gpuLayers > 0` 且 `n_ctx > 32768` 时，OpenCL compute buffer 可能分配失败，失败会自动回退纯 CPU。
- HTP 对部分算子 / 量化不支持时会静默回退 CPU；NPU 算子只落在单核。
- 长 prompt 首轮 prefill 可能达到几十秒；之后多轮走 KV 前缀复用，换模型或改 system 后回到首轮代价。
- CORS 与鉴权都不是访问控制：白名单只约束浏览器，token 挡不住同网段直连设备。
- 单模型路径下一次只驻留一个对话模型；多模型池打开后可同时驻留多份，内存占用按份数叠加。
- 知识库的「保存正文」后检索会停用，直到重建索引；追加只支持末尾，删除只能整行。
- `/v1/embeddings` 单并发执行，不会占用生成路径。

## 构建与测试

APK 分发：`.github/workflows/build.yml`（GitHub Actions）。push 到 `main` 且改动影响 APK 时构建，也可手动触发。

`paths-ignore` 是 GitHub workflow 自己的行为：纯文档改动不会触发 APK 构建。

**本地构建**：Android Studio 或自备 Gradle 8.10.2

`compileSdk 35` / `minSdk 26` / `targetSdk 28` / AGP 8.7.3 / Kotlin 2.0.21 / C++17 / `ANDROID_STL=c++_static`。
仓库不含 Gradle wrapper；`settings.gradle.kts` 把国内镜像排在前面，官方源作兜底。

## 致谢

- [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) 与 The ggml authors：推理引擎、GGUF 格式与 Hexagon NPU 后端。
- [Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn)：Android arm64 预编译库、头文件与 NPU DSP skeleton。
- Qualcomm：Hexagon DSP / HTP 硬件与 FastRPC 运行时。
- Android Open Source Project、Kotlin、Android Gradle Plugin、LLVM/libc++、GitHub Actions，以及各开源模型社区。

本项目与上述组织无隶属或背书关系。本仓库不分发任何模型权重，用户需自行遵守模型许可证。

## 许可证

- 项目代码：**MIT**，见 [LICENSE](LICENSE)。
- 随包分发的第三方二进制与头文件：见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
- 模型权重受各自许可证约束，不由本项目的 MIT 覆盖。

版本以 git tag 表示，里程碑说明见 `git tag -n` 或 Releases 页。
