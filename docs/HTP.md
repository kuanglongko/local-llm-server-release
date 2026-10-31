# Hexagon NPU（HTP）使用与排查

面向使用者的一页说明。这里只写通用结论与开启条件，不列单台机型的吞吐与耗时数据，
以免被当成所有机型的通用承诺。

## 这是什么

高通 SoC 上的 Hexagon NPU 后端（llama.cpp 的 `ggml-hexagon`），由上游预编译库提供。
App 里它是一个**实验性开关，默认关闭**。关掉时行为与不带 NPU 的机型完全一致。

## 开启条件（四个都要满足）

1. SoC 是**高通**（天玑/麒麟/Exynos 没有 HTP 后端，只有 OpenCL/CPU）。
2. 加载到 `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so`（要求 i8mm + `libOpenCL.so`）。
3. 系统能取到 FastRPC：`libcdsprpc.so`。
4. 设置里打开 HTP 开关 → **完全退出并重启 App**（native 只在启动时加载一次）。

任一不满足都会静默回退，不报错、不影响使用。

## 怎么确认 HTP 真的在算

看日志（App 日志页，或导出会话日志）：

| 日志线索 | 含义 |
|---|---|
| `native 变体=hexagon` | 选中了带 HTP 的库（不等于 HTP 已启用） |
| `高通=true i8mm=true dotprod=true OpenCL=true` | 四个条件里 CPU/系统侧的判定结果 |
| `htp=on` | 开关状态与重启是否生效 |
| `using device ...` / 每层设备归属打点 | 权重实际落在哪个设备（HTP / OpenCL / CPU） |
| HTP 段库加载与 graph execute 计数 | NPU 真在跑算子，而不是"加载了但一层没接" |

只有 `using device` 里出现 HTP 且层数 > 0，才算真的在用 NPU。

## 该不该开

**不一定更快**（跨机型会有差异，以下是判定方向而非保证）：

- 小模型（约 ≤4B）**CPU 往往更优**，开 NPU 可能反而变慢。
- NPU 的价值集中在两处：
  1. **长 prompt 的 prefill**（首次吞入大量 token 时优势明显）；
  2. **把权重从 GPU 显存挪到 NPU**——OpenCL 档显存吃紧、大 ctx 分配失败时，这是腾挪手段。
- 生成（decode）阶段多数场景仍是带宽受限，NPU 帮不上太多。

所以默认建议：只在"prompt 很长"或"显存不够"时开。

## 关于「NPU 配额档」

设置里三档：**自动（推荐）/ NPU 优先 85% / NPU 尽量 95%**。它改的是
`llama_model_params.tensor_split`，即权重在各设备间的体积分配比例。

- **自动**：由引擎决定，份额固定在约 2/3。
- **人工档**：能把份额推到 ~90% 以上，说明 2/3 只是默认结果、不是硬上限。
- 它**不是性能旋钮**：人工档不比自动档更快，个别情况更慢。唯一用途是把权重从
  GPU 显存挪到 NPU，用于显存吃紧时排查。**建议保持「自动」。**
- 切档后**下次加载模型即生效**，不需要重启 App（重启只针对 HTP 总开关）。
- 配额档不删除任何设备，NPU 拒收的层仍回落到 GPU/CPU。

## 已知边界

- **单核**：算子只落在 `HTP0`，多核 `ndev` 当前未生效，别指望多核叠加。
- **算子/量化覆盖不全**：不支持的层静默回落 CPU，速度会介于纯 CPU 与纯 NPU 之间。
- **大 ctx + GPU 层**：`gpuLayers > 0` 且 `n_ctx > 32768` 时 OpenCL compute buffer 可能分配失败，
  会自动回退纯 CPU，同时把配额复位为「自动」。
- 首次 prefill 可能几十秒，期间只有日志在动，没有进度条。
- 闪退定位见下节。

## 闪退取证（崩溃探针）

native 闪退发生在 `abort()` / `SIGSEGV` 之后，JVM 侧拿不到任何遗言
（`UncaughtExceptionHandler` 覆盖不到 native abort，`llama_log_set` 缓冲里的日志也会丢）。
所以取证不能只靠 Kotlin 日志，要靠 native 侧自己直写文件。

**打开**：设置页 →「崩溃取证（闪退排查）」→ 打开「崩溃取证模式」→ **完全退出 App 再启动**。

**取证据**：复现闪退后重开 App → 日志页 →「导出崩溃探针」，得到 `probe-<时间戳>.txt`。
这一份已含排查所需的全部内容：native 探针**本代**与**上一代**（`probe-native.log.prev`
—— 信号现场在上一代里，见下）、Kotlin 本次与上次会话全文、设备/后端/变体/Boot ID。

**为什么必须两代都带**：崩溃现场必然落在 `.prev`。用户复现闪退后进程就没了，
**他得重开 App 才能点这个按钮**；而重开是一次冷启动，滚动发生在 `nativeProbeInit`
打开文件**之前** —— 于是 `probe-native.log` 是**这一次开机**的，信号现场
（`!!! ===== SIGNAL n =====` / `>> 函数名` 没有配对的 `<<`）一个字都不在里面。
此前那一份只能靠用户自己进文件管理器翻 `Android/data/.../logs/`（Android 11+ 上
那正是普通用户最难碰到的地方），或者靠 `session-*.log` 那半份 Kotlin 侧叙述倒推。

**两份日志的分工**（`0.9.x` 修正常量「取证模式吞日志」后）：取证模式开着时，
`probe-native.log` = native 全量（`llama_log_set` 的 sink 直写，含 GGUF 解析 / tensor 装载）
+ stderr 遗言（`ggml_abort` 那类只往 fd 2 打的行）+ 逐 token 原始流 + 信号现场；
`session-*.log` 同时保留 Kotlin 侧全量且**不折叠**（进程没崩时便于一次读完）。
两份合起来才是全量 —— 此前取证模式下 Kotlin 侧主动不写会话文件、且 `probe_log_sink`
是空函数，于是"加载模型"那段只剩 `>> loadModel` 骨架，看着像"什么都没干"。

**怎么读**：

| 现象 | 含义 |
|---|---|
| `>> 函数名` 没有配对的 `<< 函数名` | 崩在这个 JNI 入口内部 |
| `[parse]` / `[tools]` 最后一条 `>>>` 没有配对 `<<<` | 崩在库里那一步（渲染 / 解析） |
| `!!! ===== SIGNAL n =====` | native 收到了信号，紧跟其后的行就是现场 |
| 只有 `[boot]` 开头的内容 | 崩点落在 Kotlin → native 之间，native 业务还没开始跑 |
| 一条 `[boot]` 都没有 | native 库根本没跑起来（`loadLibrary` 失败或拿的是旧包） |
| `UnsatisfiedLinkError: nativeProbeInit` | `.so` 与 `.kt` 不是同一次构建（覆盖安装 / native 未重编） |

**文件的重置与保留**（Issue #238 日志一轮）：

- `probe-native.log` **每代一份**：每次冷启动（`nativeProbeInit` 打开文件前）把上一份滚成
  `probe-native.log.prev`，本代从零写。只留两代 —— 用 `rename` 而不是 `unlink`，
  因为崩前那几行在前面，删掉等于把上次那座现场扔掉。滚动失败**不阻断**取证，只留一行 `LOGW`。
- `session-*.log` 保留最近 `LogFileStore.KEEP`（8）个会话，排序口径是**文件名里的时间戳**
  （`session-yyyyMMdd-HHmmss.log`）。此前用的是 `lastModified()`，而本次会话文件在筛"上一次"
  之前就已创建、mtime 立刻是 now —— 于是本次永远排第 1，`drop(KEEP-1)` 恰好删掉
  **上一次会话**。那句"保留最近 8 个会话，够回溯"从写下那天起就没成立过。
- **目前没有体积上限**，这是有意的：上限值要先有实测数据（单轮写多少字节 × 想保留几轮现场），
  在有数据之前拍一个圆整数写死，等于用一个新的没有依据的数去替一个没有数。
  所以探针文件会一直长；嫌占地方就关开关或直接删文件（下次冷启动照建）。

**探针自身的兜底**（`0.9.113`，模块 K 修复后）：

- handler 安装**幂等**：`nativeProbeInit(on=true)` 在本进程里可以被调到不止一次
  （init 失败后用户再点「加载」/ 服务再起一轮），第二次安装不再覆盖 `g_old_*`。
  之前每次覆盖会把转发链指向探针自己 —— 症状正是「`!!! ===== SIGNAL` 一行都没有」，
  与「根本没崩 signal」**同形**；
- 装了**备用信号栈**（64KB），`SA_ONSTACK` 才真的生效 ——
  爆栈型 SIGSEGV（渲染 / PEG 解析的深递归）现在也能留下现场。日志里可直接读
  `signal handlers installed (..., altstack=yes|no)`；
- 驱动段**不再改写信号语义**：原 disposition 是 `SIG_IGN` 时，"忽略"就是忽略
  （之前会被升级成 `exit(128+sig)`）。

探针不问「Kotlin 有没有成功调到 nativeProbeInit」。
native 在库加载时就自行自举（只读系统属性与包名推导目录），因此即使上面那一跳失败，
`probe-native.log` 里也会有 `[boot]` 内容；要指定落盘目录（受控，仅调试用）：
`setprop debug.localinference.probe.dir /data/local/tmp`。

关闭探针时探针函数第一行即返回，零开销，正常使用不受影响。

**注意**：探针**不改任何业务逻辑**，只加观测。它不会让闪退消失，而是让下一次闪退可归因。
