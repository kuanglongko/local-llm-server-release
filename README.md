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

## 功能

- **图形界面**：模型库（列表 / 别名 / 卸载）、多会话聊天（气泡、单条删除、重新生成、导入导出、角色卡）、参数设置、本地服务开关与存活探测、日志筛选与导出。
- **HTTP 接口**：`/health`、`/v1/models`、`/v1/models/{id}`、`/v1/chat/completions`、`/v1/completions`、`/v1/abort`、`/v1/embeddings`；`GET /` 是自带测试页。
- **结构化输出**：支持 `response_format` 的 `json_object` / `json_schema`，用 GBNF grammar 在生成循环里逐 token 约束。
- **KV 前缀复用（prompt cache）**：多轮对话与带 `tools` 的固定段不再每轮从零 prefill，只计算新增部分。
- **嵌入与知识库（RAG）**：对话模型与嵌入模型可双常驻；文档分块、编码、持久化，带模型与正文指纹，正文变化即提示重建。
- **可调参数**：`n_ctx`、线程数、`parallelN`（**并发 slot 数**，默认 1）、`batch/ubatch`、采样与惩罚参数、Flash Attention、KV cache 量化、权重重排、mmap、默认关闭思考。
- **推理后端**：CPU（按指令集选 4 个预编译变体）+ OpenCL GPU + Hexagon NPU（HTP，实验性，默认关闭）。
- **可取证**：Java 异常落 `crash_last.txt`；native stderr 并入会话日志；可选崩溃取证模式，把 native 全量日志与信号现场直接写文件。
- **可恢复**：设置页底部「还原默认设置」一次性恢复整页参数，操作前会弹确认框。

## 快速开始

1. 从 Releases 安装 APK（当前只提供 debug 构建；仓库不含模型权重）。设置页顶部会显示版本号与构建号，报障时直接引用。
2. 把 `.gguf` 放进 `Android/data/com.xiaowan.localinference/files/models/`，在 App 里「添加模型」时先选类型（对话 / 向量），再选中并加载。该目录是应用私有外部目录，不需要存储权限。
3. 在「设置」里调整推理参数。App 内参数优先，并覆盖请求里的同名字段。
4. 给其他设备使用：打开「局域网访问」，记下端口与通知里显示的 IP；不打开时只绑定 `127.0.0.1`。
5. 可选：在「设置 → 本地服务 → 接口鉴权」生成 token，填进客户端。默认不开启。

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
| GET | `/` | 自带测试页，同源内嵌 HTML，可在浏览器里探测 `/health`、查看模型、流式聊天 |
| OPTIONS | 任意路径 | CORS 预检，统一返回 `204` |
| GET | `/health` | 存活探测，含 KV 复用与后台冻结痕迹字段 |
| GET | `/v1/models` | 模型列表；主 id 使用别名，文件名在 `aliases` 中 |
| GET | `/v1/models/{id}` | 单个模型；当前加载的模型带 `profile` 档案 |
| POST | `/v1/chat/completions` | 对话补全，支持 `messages`、`stream`、`tools`、`stop`、`response_format` |
| POST | `/v1/relay/chat/completions` | 中间层入口，请求形状与 `/v1/chat/completions` 相同，自动裁掉服务端已丢弃的轮次 |
| POST | `/v1/completions` | 文本补全，支持 `prompt`、`stop`、`response_format` |
| POST | `/v1/abort` | 取消当前生成，无 body、无参数 |
| POST | `/v1/embeddings` | 文本向量；需要先加载嵌入模型，不支持 `dimensions` |

**请求约定**

- 采样参数缺省时使用与 App 参数区一致的默认值，也支持 OpenAI 的 `seed`；显式传入的非法值返回 `400`。
- 请求体上限 4 MiB（超限 `413`）；请求行与单个头行上限 8192 字节（超限 `400`）。
- 生成端点接受 `stop` / `stop_sequences`；`prompt` 与 `messages` 的超限请求明确拒绝，不静默截断。
- `n` 只支持 1，其他取值返回 `400`。
- `finish_reason` 支持 `stop`、`length`、`tool_calls`、`cancelled`。
- 生成端点的诊断读数走 `X-Local-Ctx` 响应头，正文保持 OpenAI 形状。

工具调用支持 `tools` / `function calling`：

`tool_choice`（`auto`/`required`/`none`）与 `parallel_tool_calls` 均生效。

### 鉴权（可选，默认不开）

在设置页「本地服务 → 接口鉴权」生成 token 后，生成端点要求：

```http
Authorization: Bearer <token>
```

以下路径一律免鉴权：`OPTIONS`、`GET /health`、`GET /v1/models`、`GET /v1/models/{id}`、`GET /`。
路径按“落在之下”匹配，`/healthz`、`/v1/models-evil` 不会被误豁免；其余 `/v1/*`（含未知路径）需要凭据。

不带或带错 token 返回 `401`，错误正文只说明原因，不回显 token。鉴权默认不开；token 走明文 HTTP，
挡不住同网段能直连的设备，**不是访问控制**，请在可信网络使用。

### 并发与上下文

**并发形态：`parallelN` 个 slot（默认 1）**。`parallelN = 1` 时只有一份 ctx、一条 seq，HTTP 与 App 内聊天共用，
请只在**一侧**使用；两路同时跑会互相顶掉 KV 前缀。`parallelN > 1` 时最多 N 个并发请求，第 N+1 个返回 `503`，不排队。

- `n_ctx` 就**是每一格的容量**，格数只决定开几格；`parallelN=2` + `n_ctx=4096` 表示两格各 4096。
- **`N` 个 slot ≠ HTTP 拿 `N` 个**：slot 0 保留给 App 内聊天，HTTP 只拿 N-1 个；只用 HTTP 时也会留一格空着。
- **每路会变慢**：多路抢同一份内存带宽，单路速度会下降，并发买的是总吞吐。
- 别用 `parallelN` 换 HTTP 路数：加出来的那格会先被保留位吃掉，只多占内存。
- 每格只开一条 seq（`n_seq_max = 1`），界面报多少 `ctx_per_slot`，模型就能用到多少位置。

建议保持 1；确实需要 App 与 HTTP 同时使用时，再调到 2 以上并重新加载模型。

### Relay 中间层（`/v1/relay/chat/completions`）

这是本项目独有的可选入口：把客户端的 `base_url` 从 `/v1` 换成 `/v1/relay` 就接上了，
请求形状与 `/v1/chat/completions` **完全相同**，不需要改任何字段。

- 它是**同进程内的旁路入口**，不是第二个服务：同一个 HTTP 循环、同一份 slot 调度与 KV 账本。
- 它会读取这条会话上一轮的 `local_ctx` 坐标，在客户端发来的完整 `messages` 上**先删掉服务端已经丢弃的轮次**，
  再把结果交给与直连完全相同的处理路径；坐标对不上就原样转发。
- 前置删过的轮次会作为 `layoutDroppedRounds` 交给上下文预算，避免按水位再丢一遍。
  因此客户端可以始终发送完整历史，由服务端维持 KV 前缀复用。
- 服务端把本轮坐标放进 `X-Relay-Coord` 响应头（流式同时写首帧）；记账只由成功响应更新，
  `400` / `503` 不会覆盖上一轮坐标。
- 请求体非法、缺少 `messages`、坐标不可用等失败一律**退化为直连**，不会额外返回 `502`。
- 会话键取 `X-Relay-Session`（缺省为整台设备一份）。`/health` 的 `relay_sessions`、`relay_forwarded`、
  `relay_reason` 分别表示账本会话数、最近一次前置删了几轮、以及原因。
- 流式响应在 prefill 期间会发送带外心跳；响应缓冲上限 4 MiB，超过则放弃记账、照旧转发。

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

- 嵌入模型与对话模型各自持有独立 Session，可同时加载；向量按模型声明的池化方式取值并归一化。
- 知识库按「分块 → 编码 → 落盘」建立索引，索引与正文分开保存；模型、切块参数或正文变化会使指纹失效并提示重建。
- App 内「知识库」页支持回填正文、保存正文、建索引 / 重建索引、追加、删除单条、导出与导入。
- 远端接口 `/v1/embeddings` 需要先加载嵌入模型；`/v1/chat/completions` 不认请求侧的 `knowledge_base` 字段，远端知识库由 App 内开关控制。

### 角色卡

角色卡只做一件事：给会话的 `system` 起一个名字，方便复用。卡片就是那段 `system` 文本，没有占位符替换、备用问候或示例消息。
会话保存的是内容快照，不是卡片 id；改卡不会影响已经挂卡的会话，切卡会作废 prompt 缓存。
卡片库整体重写，单文件路径见下方「数据存放位置」。

### 后台保活与前台服务

**服务自身的前台服务类型**声明为 `dataSync|specialUse`，其中 `specialUse` 用于准确说明常驻推理的用途。

`oom_score_adj` 由「进程是不是 FGS」决定：FGS 能提高进程优先级，但**换不到冻结豁免** —— 冻结判据只看内存压力与厂商策略。
声明 `specialUse` 的收益是语义完备、不吃将来的时间额度，而不是承诺「不再被冻」；部分国产 ROM 仍需手动加白名单。

### 崩溃取证

native 的 `abort`（SIGABRT）由 libc 直接终止进程，不经过 JVM，`UncaughtExceptionHandler` 抓不到。排查步骤：

1. 在调试页打开「崩溃取证模式」，完全退出并重启 App（探针在 native 库加载前挂载，必须冷启动）。
2. 复现请求。
3. 重开 App，在调试页「导出崩溃探针」，从公共「下载」目录取 `probe-<时间戳>.txt`。

探针在 `JNI_OnLoad` 自举，自举阶段**只记不写**：不建文件、不落盘，只进 logcat。
调试页的「关」也会显式告知 native，开关的唯一事实来源是调试页。

## 数据存放位置

| 内容 | 路径 |
|---|---|
| GGUF 模型 | `<getExternalFilesDir(null)>/models/*.gguf`（外部存储不可用时回落到 `<filesDir>/models`） |
| 会话记录 | `<filesDir>/<SessionStore 目录>/s_<id>.json`，当前会话 id 存于 `cur` |
| 角色卡 | `<filesDir>/cards.json`，一个 JSON 数组装整个卡片库；会话里保存挂卡时的内容快照 |
| 运行日志 | `<filesDir>/logs/session-<启动时间>.log`，保留最近 8 个会话 |
| 导出日志 | 调试页导出到公共「下载」目录（API 29+ 走 MediaStore） |
| 聊天记录导出 | 会话管理「导出全部」或长按会话「导出这一条」，输出 `chat[-all][-<id>]-<时间>.json` |
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

第 4 档不可省：部分老款 SoC 连 dotprod 都没有。判定只看内核导出的 CPU 特性，只保守不冒进；加载结果写在日志首行。

Hexagon NPU（HTP）默认关闭。开启方式：设置里打开 HTP → 完全退出并重启 App → 加载模型后确认日志显示 HTP 生效。
缺任一条件会静默回退，不影响使用。说明与排查见 **[docs/HTP.md](docs/HTP.md)**。

> NPU 上不一定比 CPU 快。小模型（约 ≤4B）通常 CPU 更优；NPU 的价值主要在长 prompt 的 prefill 与腾挪显存。

## 已知限制

- 仅支持 `arm64-v8a`；当前只提供 debug 构建，release 还需要补签名与 minify 配置。
- **并发 slot 数**：`parallelN` 默认 1，只有一份 ctx，请只在 App 或 HTTP 一侧使用；`> 1` 时每路会变慢，`n_ctx` 按份切分，slot 0 保留给 App。
- 结构化输出受模型模板能力约束，转换不出 GBNF 时会降级为无约束并留日志。
- 「思考开 + `json_schema`」的语义冲突尚未处理；默认关闭思考，常态下不会遇到。
- `gpuLayers > 0` 且 `n_ctx > 32768` 时，OpenCL compute buffer 可能分配失败，失败会自动回退纯 CPU。
- HTP 对部分算子 / 量化不支持时会静默回退 CPU；NPU 算子只落在单核。
- 长 prompt 首轮 prefill 可能达到几十秒，期间只有日志在动；之后多轮走 KV 前缀复用，换模型或改 system 后回到首轮代价。
- CORS 与鉴权都不是访问控制：白名单只约束浏览器，token 挡不住同网段直连设备。
- 对话模型仍是一次一个；嵌入模型可与对话模型双常驻。换对话模型需要卸载重载。
- 知识库的「保存正文」后检索会停用，直到重建索引；追加只支持末尾，删除只能整行。
- `/v1/embeddings` 单并发执行，与检索共用 `embedLock`，但不会占生成路径的 `genLock`。
- mmap、权重重排与内存回收的具体行为见日志中的 `[mmap*]`、`[repack*]`、`[内存]` 行。

## 构建

**本地构建**：Android Studio 或自备 Gradle 8.10.2

`compileSdk 35` / `minSdk 26` / `targetSdk 28` / AGP 8.7.3 / Kotlin 2.0.21 / C++17 / `ANDROID_STL=c++_static`。
仓库不含 Gradle wrapper；`settings.gradle.kts` 把国内镜像排在前面，官方源作兜底。

推送影响 APK 的改动后，GitHub Actions 会按 `.github/workflows/build.yml` 构建 debug APK，
并作为 `app-debug-apk` artifact 上传；也可在 Actions 页面手动触发。仓库不分发任何模型权重。

## 目录结构

```
app/src/main/java/    Kotlin 界面、HTTP 服务、推理与 RAG 逻辑
app/src/main/cpp/     JNI、llama.cpp 适配与 native 探针
app/src/main/assets/  自带测试页等内嵌资源
docs/                 使用说明（HTP）
vendor/               预编译推理库与头文件
```

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
