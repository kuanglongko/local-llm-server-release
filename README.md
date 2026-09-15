# Local LLM Server（小万本地推理）

Android 手机上**完全离线**的 GGUF 大模型推理 App：在应用进程内用 llama.cpp 跑推理，
一边提供图形界面聊天，一边对局域网提供 OpenAI 兼容 HTTP 接口。模型与对话记录只留在本机。

包名 `com.xiaowan.localinference` · 最低 Android 8.0（API 26）· 仅 `arm64-v8a`

## 能做什么

- **图形界面**：模型库（列表/别名/卸载）、多会话聊天、参数区、本地服务开关、日志与导出
- **HTTP 接口**：`/health`、`/v1/models`、`/v1/chat/completions`（支持 `stream`）、`/v1/completions`
- **可调参数**：`n_ctx`、线程数、`parallel`、`batch/ubatch`、top_k / top_p / min_p、
  repeat / frequency / presence penalty、Flash Attention、KV cache 量化（`type_k`/`type_v`）、mmap 开关、禁用思考块
- **推理后端**：CPU（按指令集选 4 个预编译变体）+ OpenCL GPU + **Hexagon NPU（HTP，实验性，默认关闭）**
- **可取证**：Java 异常落 `crash_last.txt`；native stderr 实时并入会话日志，崩前最后几行不丢
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
| GET | `/health` | 存活探测 |
| GET | `/v1/models` | 全库模型列表；主 id 用**别名**（Ollama 风格短名），文件名在 aliases 里 |
| POST | `/v1/chat/completions` | 对话补全，认 `messages`（含 `system`）与 `stream` |
| POST | `/v1/completions` | 文本补全，认 `prompt` |

除模型名与输入文本外的请求字段一律忽略——线程、上下文、采样参数在 App 里定。
**接口无鉴权**：开了局域网访问，同网段任何人都能调用，请在可信网络使用或及时关闭。

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
| 参数/端口/别名 | `SharedPreferences`（经 `ModelStore`） |

## 构建

- CI：`.github/workflows/build.yml`（push 到 `main` 或手动触发）。Java 17 → `setup-android` →
  NDK `27.0.12077973` + CMake `3.22.1` → Gradle `8.10.2` → `:app:assembleDebug`，artifact `apk-debug`。
- 本地：Android Studio 或自备 Gradle 8.10.2（仓库不含 wrapper）。`compileSdk 35` / `minSdk 26` /
  `targetSdk 28` / AGP 8.7.3 / Kotlin 2.0.21 / C++17 / `ANDROID_STL=c++_static`。
- 没有 NDK 也能做真实类型检查：`bash scripts/kt_check.sh`（12 个 `.kt`，用缓存的 kotlinc + android-35 桩）。
- 原生层为每个变体各编一个 JNI 壳 `libllmjni_<tag>.so`（4 个变体 SONAME 互不相同，单壳无法同时满足）。

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
