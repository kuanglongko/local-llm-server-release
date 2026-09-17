# Local LLM Server（小万本地推理）

Android 手机上**完全离线**的 GGUF 大模型推理 App：在应用进程内用 llama.cpp 跑推理，
一边提供图形界面聊天，一边对局域网提供 OpenAI 兼容 HTTP 接口。模型与对话记录只留在本机。

包名 `com.xiaowan.localinference` · 最低 Android 8.0（API 26）· 仅 `arm64-v8a`

## 能做什么

- **图形界面**：模型库（列表/别名/卸载）、多会话聊天、参数区、本地服务开关与**存活探测**、日志与导出
- **HTTP 接口**：`/health`、`/v1/models`、`/v1/chat/completions`（支持 `stream`）、`/v1/completions`
- **可调参数**：`n_ctx`、线程数、`parallel`、`batch/ubatch`、top_k / top_p / min_p、
  repeat / frequency / presence penalty、Flash Attention、KV cache 量化（`type_k`/`type_v`）、mmap 开关、禁用思考块
- **推理后端**：CPU（按指令集选 4 个预编译变体）+ OpenCL GPU + **Hexagon NPU（HTP，实验性，默认关闭）**
- **可取证**：Java 异常落 `crash_last.txt`；native stderr 实时并入会话日志，崩前最后几行不丢；
  可选「崩溃探针」把 native 日志与信号现场**不经 JVM** 直写文件，native 闪退也能导出完整现场
- Kotlin 侧**零第三方运行时依赖**（只用 Android framework 与 `org.json`），无账号、无遥测

## 快速开始

1. 安装 APK（Releases 提供 debug 构建；本仓库不含模型权重）。装好后 App 设置页顶部会显示当前构建的版本号与构建号，报障时直接引用它。
2. 把 `.gguf` 放进 `Android/data/com.xiaowan.localinference/files/models/`，在 App 里选中 → **加载**。
   该目录属应用私有外部目录，**不需要授予存储权限**（应用只声明 INTERNET / 前台服务 / 通知）。
3. 要用推理参数以外的默认值，在「设置」里调；参数**以 App 为准**，会覆盖请求里的同名字段。
4. 给别的设备用：打开「局域网访问」（否则只绑 `127.0.0.1`），记下端口与通知里显示的 IP。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m",
       "messages":[{"role":"user","content":"用一句话解释 KV cache"}],"stream":true}'
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活探测（App 内「存活探测」按钮即真发此请求） |
| GET | `/v1/models` | 全库模型列表；主 id 用**别名**（Ollama 风格短名），文件名在 aliases 里 |
| POST | `/v1/chat/completions` | 对话补全，认 `messages`（含 `system`）、`stream`、`tools`（工具调用） |
| POST | `/v1/completions` | 文本补全，认 `prompt` |

**接口无鉴权**：开了局域网访问，同网段任何人都能调用，请在可信网络使用或及时关闭。

采样参数由请求体给出，缺省时用与 App 内参数区一致的一组默认值
（`temperature=0.8`、`top_p=0.95`、`min_p=0`、`top_k=0`、`repeat_penalty=1`、
`repeat_last_n=64`、`frequency_penalty=0`、`presence_penalty=0`、`max_tokens=512`）。
也认 OpenAI 的 `seed`，传固定值即可复现同一段输出。
显式给出的取值必须落在合法范围内（如 `top_p` 需在 `(0,1]`、`repeat_penalty >= 1`），
否则返回 `400` 并说明原因——不会静默按默认值跑。
线程、上下文、`batch/ubatch`、KV cache 等**加载期**参数仍在 App 里定，接口不认。

### App 内「存活探测」

设置页「启动服务」旁的**存活探测**按钮会从 App 内真发一次 `GET /health`，把结论常驻显示在
按钮下方（可长按复制），并同步一份到聊天页状态栏与运行日志。三档结论：

| 结论 | 含义 |
|---|---|
| ✅ 存活 | 端口可达、`status=ok`、模型已加载 —— 现在就能推理 |
| ⚠ 可达但状态不完整 | 端口通了，但 `status` 非 `ok`、或**模型未加载**（生成类请求会返回 503）。不该被当成"能用" |
| ❌ 不可达 | 连接被拒 / 读超时 / 应答的不是本服务（正文不是 JSON）。附失败原因与响应原文 |

它**不看** `HttpApi.isRunning` 这类进程内标志位就下结论：那些标志位只说明对象自认为在跑，
说明不了端口真可达——现场踩过的两次假活态（后台冻结后 accept 线程已死但端口仍 LISTEN、
绑定地址改成 `0.0.0.0` 后回环不再可达）都能骗过标志位。所以探测走完整链路：
TCP 连接 → 发请求 → 收响应 → 解析 JSON → 判定，默认 3 秒超时。

探测规则由 `HealthCheck`（纯函数 `buildRequest` / `parse` / `verdict`）实现，
离线单测 `tools/run_health_tests.sh` 钉住，含真开临时端口跑一遍的用例。

### 工具调用（tools / function calling）

`/v1/chat/completions` 认 OpenAI 的 `tools`：

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m",
       "messages":[{"role":"user","content":"北京现在天气怎么样"}],
       "tools":[{"type":"function","function":{
          "name":"get_weather",
          "description":"查询指定城市天气",
          "parameters":{"type":"object",
            "properties":{"city":{"type":"string"}},
            "required":["city"]}}}]}'
```

命中时 `finish_reason` 为 `tool_calls`，`message.tool_calls[].function.arguments` 是 JSON 字符串；
`stream=true` 时按 OpenAI 约定以增量块下发（每块含 `index/id/type/function`）。

- `tools`、`tool_choice`（`auto`/`required`/`none`）、`parallel_tool_calls` 均生效；
  `tool_choice` 写成对象（指定具体函数）时按 `auto` 处理。
- 工具调用语法**由模型模板决定**（Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等）：
  工具定义经 `common_chat_templates_apply` 渲染进 prompt，模型输出经 `common_chat_parse`
  按模板归一，Kotlin 侧不做正则猜测。
- 模型模板不支持工具调用、或渲染失败时**回落到普通对话**，不会让请求整体失败；
  `tools` 不是数组（如写成对象）才返回 `400`。
- **稳定性约定**：工具调用的整条链路（模板渲染、解析、拼装）任何一步失败都只降级
  （回落纯文本 / 返回 `null`），绝不把 C++ 异常抛过 JNI 帧——
  那属于 UB，会让进程直接 `std::terminate`，表现为「客户端一调工具，服务端 App 闪退」。
  调用前还会先确认模板确实生成了解析器（`cp.parser` 非空），
  拦掉 abort 一类 `catch` 抓不到的路径；模型输出不匹配工具语法按纯文本正常返回。
- 带 `tools` 的流式请求会**整段生成完再下发**（工具语法与正文同处一条流，
  边生成边发会把 `<tool_call>` 之类的标记当正文吐出去），因此首字延迟等于整段生成时间。

## 支持的后端与机型

推理核心不自建，直接用 [Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn) v1.12.2 的
预编译产物（`vendor/cui-llama.rn-v1.12.2/`，含 `SHA256SUMS` 与来源说明，未做任何二进制改写）。
启动时 `LlmEngine.loadNative()` 逐个尝试，任一失败即降级：

| 顺序 | SONAME | 需要条件 | 后端能力 |
|---|---|---|---|
| 1 | `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so` | **高通** SoC **且** i8mm **且** 有 `libOpenCL.so` | CPU + OpenCL GPU + Hexagon NPU |
| 2 | `librnllama_v8_2_dotprod_i8mm.so` | i8mm | 纯 CPU |
| 3 | `librnllama_v8_2_dotprod.so` | dotprod | 纯 CPU |
| 4 | `librnllama.so` | 无（基线） | 纯 CPU |

第 4 档不可省：骁龙 865/870（Cortex-A77）连 dotprod 都没有，缺基线包会直接闪退。
判定用 `getauxval(AT_HWCAP/AT_HWCAP2)`（`app/src/main/cpp/cpu_feat.c` → `libcpufeat.so`），
不读 `/proc/cpuinfo`（旧内核不导出 i8mm，dotprod 常显示成 `asimddp`）。
**只保守不冒进**：内核漏报 i8mm 就退回 dotprod 档——反过来会对 CPU 发未支持指令，直接 `SIGILL`。
加载结果写在日志首行，可核对：`native 变体=hexagon CPU=i8mm+dotprod+asimd 高通=… i8mm=… dotprod=… OpenCL=…`

## Hexagon NPU（可选，默认关）

开启三步：设置里打开 HTP → **重启 App** → 加载时确认日志显示 HTP 生效。缺任一条件都会静默回退，不影响使用。
要点与排查见 **[docs/HTP.md](docs/HTP.md)**。一句话结论：

> NPU 上**不一定**比 CPU 快。小模型（约 ≤4B）CPU 往往更优；NPU 的价值在于长 prompt 的 prefill
> 与把权重从 GPU 显存挪开。UI 里的「NPU 配额档」不是性能旋钮，只用于腾挪显存/排查，建议保持「自动」。

## 已知限制

- 仅 `arm64-v8a`；只提供 **debug** 构建（release 需先解决签名与 minify 配置）。
- `gpuLayers > 0` 且 `n_ctx > 32768` 时 OpenCL compute buffer 可能分配失败；失败会**自动回退纯 CPU**（配额同时复位为「自动」）。
- HTP 后端对部分算子/量化不支持 → 静默回退 CPU；算子只落在 HTP0 单核（多核 `ndev` 未生效）。
- 长 prompt 首次 prefill 可能几十秒级，期间界面只有日志在动，没有百分比进度。
- 同一时刻只服务一个已加载模型；换模型走卸载重载，内存若未归还，重启 App 是最快恢复手段。
- 前台服务用于保活，部分国产 ROM 仍会杀后台，需要自行加白名单。

## 数据存放位置

| 内容 | 路径 |
|---|---|
| GGUF 模型 | `<getExternalFilesDir(null)>/models/*.gguf` |
| 会话记录 | `<filesDir>/<SessionStore 目录>/s_<id>.json`（当前会话 id 存于 `cur`） |
| 运行日志 | `<filesDir>/logs/session-<启动时间>.log`，保留最近 8 个会话；连续重复行折叠为 `xxx ×N` |
| 导出日志 | 日志页「导出本次 / 上次会话」→ 公共「下载」目录（API 29+ 走 MediaStore，无需权限） |
| 崩溃现场 | `<getExternalFilesDir(null)>/crash_last.txt` |
| native 崩溃探针 | `filesDir/logs/probe-native.log`（设置页「崩溃探针」打开后由 native 直写） |
| 参数/端口/别名 | `SharedPreferences`（经 `ModelStore`） |

## 构建

- CI：`.github/workflows/build.yml`（push 到 `main` **且改动影响 APK** 时构建，也可手动触发）。
  纯文档与工具类改动（`**.md`、`docs/**`、`scripts/**`、`tools/**`、`.gitignore`、`LICENSE`）
  由 `paths-ignore` 排除，不触发构建；`.github/**` 未被排除，改 workflow 自身会实跑一次。
  链路：Java 17 → `setup-android` → NDK `27.0.12077973` + CMake `3.22.1` → Gradle `8.10.2` →
  `:app:assembleDebug`，artifact `apk-debug`。
- 本地：Android Studio 或自备 Gradle 8.10.2（仓库不含 wrapper）。`compileSdk 35` / `minSdk 26` /
  `targetSdk 28` / AGP 8.7.3 / Kotlin 2.0.21 / C++17 / `ANDROID_STL=c++_static`。
- 没有 NDK 也能做真实类型检查：`bash scripts/kt_check.sh`（15 个 `.kt`，用缓存的 kotlinc + android-35 桩）。
- 不装机、不下载模型也能跑的离线单测：
  `tools/run_sampling_tests.sh`（采样参数默认值/校验/链顺序）、
  `tools/run_tool_call_tests.sh`（工具调用的请求解析与 OpenAI 线上响应形状，
  含 `arguments` 内嵌 JSON 的转义、并行调用的 index/id、模板一致性与解析失败兜底）、
  `tools/run_chat_buffer_tests.sh`（模板渲染缓冲区的边界语义，用 guard page 抓越界读）、
  `tools/run_probe_tests.sh`（崩溃探针的硬约束：fd 直写、handler 递归保护与链式转发、
  自举不依赖 JVM 调用、入参 `on=false` 时仍能真正关掉、探针关闭时零开销）、
  `tools/run_utf8_tests.sh`（UTF-8 安全解码，含 fuzz）。
- 原生层为每个变体各编一个 JNI 壳 `libllmjni_<tag>.so`（4 个变体 SONAME 互不相同，单壳无法同时满足）。

## 排查 native 闪退（崩溃探针）

native 侧的 `abort`（SIGABRT）由 libc 直接终止进程，**不经过 JVM**，所以
`UncaughtExceptionHandler` 抓不到它；而走 `llama_log_set` 的日志在 abort 前也可能丢。
表现就是「客户端一调工具，服务端进程闪退又自动恢复，日志里什么都没有」。

探针就是为了在这种情况下留下现场，用法三步：

1. 设置页 →「崩溃取证（探针）」打开「崩溃探针」，**完全退出并重启 App**（探针在 native
   库加载前挂载，必须冷启动才生效）。
2. 复现那次请求（客户端带 `tools` 调一次）。
3. 重开 App → 日志页 →「导出崩溃探针」→ 公共「下载」目录拿到 `probe-<时间戳>.txt`。

导出的文件是三段合一：native 探针原文（含信号现场与全量原生日志）、Kotlin 侧本次会话全文、
上次会话全文，末尾附设备/后端/Boot ID 等环境。

探针下 native 侧做的事（都不改变业务逻辑）：

- 所有日志与逐 token 输出**不经 JVM**，直接 `write` 进 probe fd，abort 瞬间也不丢；
- 同时为 `SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE` 装 handler，记录信号号、故障地址、
  触发时刻，然后**链式转发给原 handler**（不吞信号、不改原行为）；
- 每个 JNI 入口成对打 `>> 函数名` / `<< 函数名`，谁进去没出来即崩点；工具渲染与解析
  路径上还有逐步骤埋点（模板长度、tools 长度、`templates_apply` 结果、`common_chat_parse`
  的进入/返回），并会提示「这一行后面没有 `<<<` 就是崩点」。

探针关闭时 `jp()` 第一行即返回，开销为零，不影响正常使用。

### 探针自身的兜底：不押在「Kotlin 有没有调通 nativeProbeInit」上

此前探针只有 `nativeProbeInit` 一条开启路径，而这**恰好是最容易失败的一条**：
`nativeProbeInit` 必须先把 `libllmjni_<tag>.so` load 进来才调得到，一旦这一步失败
（覆盖安装没换 `.so`、CMake 判 up-to-date 跳过 native 重编、变体名对不上），
native 侧就全程静默 —— 而崩点正在 native 侧。日志里只留下
`UnsatisfiedLinkError: nativeProbeInit` 一行，然后整段会话一条 native 行都没有。

现在改成**两个方向都通**：

- `JNI_OnLoad` 里 native 自己先自举一次：只读系统属性与包名推导目录，**不依赖任何 JNI 调用**，
  所以加载期故障也能留下痕迹；JNI 入口这次调用随后认领它，把目录换成 Kotlin 传的
  `filesDir/logs`，早期事件一并回灌到正式文件；
- 每次 `loadLibrary` 成功都会在 logcat 打一行 `JNI_OnLoad 已进入`，
  用来区分「`.so` 没换新」与「符号真不在库里」。

自举目录可经系统属性指定（调试用）：`setprop debug.localinference.probe.dir /data/local/tmp`；
写不出去时也会退到 `/data/local/tmp` 并在 logcat 报明原因，不静默。

## 致谢

本项目站在这些工作的肩膀上，衷心感谢：

- **[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)** 与 **The ggml authors** —— 推理引擎、GGUF 格式、
  GGML 后端框架与 Hexagon NPU 后端本体。本项目的 NPU 能力完全来自上游，未自研算子。
- **[Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn)**（Jhen-Jie Hong，MIT）—— 我们直接采用的
  Android arm64 预编译 `librnllama_*` 库与头文件（`app/src/main/cpp/include/`），以及 NPU 侧的
  `libggml-htp-*.so` DSP skeleton。省掉了自建交叉编译链与 Hexagon SDK 构建，全部为上游 MIT 产物。
- **Qualcomm** —— Hexagon DSP / HTP 硬件与 FastRPC 运行时（`libcdsprpc.so`，由设备系统提供，本包不分发），
  以及构建上述 skeleton 所需的 Hexagon SDK 工具链。
- **The Android Open Source Project**、**Kotlin**、**Android Gradle Plugin**、**LLVM/libc++**、**GitHub Actions** —— 平台与工具链。
- 各开源模型社区（Qwen/阿里、Gemma/Google、Llama/Meta、Hugging Face 上的量化贡献者等）。
  **本仓库不分发任何模型权重**，用户自备模型并自行遵守其许可证。

本项目与上述组织无隶属或背书关系。

## 许可证

- 本项目代码：**MIT**（见 [LICENSE](LICENSE)）。
- 随包分发的第三方二进制/头文件的许可证原文与出处：见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
- 模型权重受各自许可证约束，不由本项目的 MIT 覆盖。

版本以 git tag 表示，里程碑说明见 `git tag -n` 或 Releases 页。
