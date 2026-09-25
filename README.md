# Local LLM Server（小万本地推理）

Android 手机上**完全离线**的 GGUF 大模型推理 App：在应用进程内用 llama.cpp 跑推理，
一边提供图形界面聊天，一边对局域网提供 OpenAI 兼容 HTTP 接口。模型与对话记录只留在本机。

包名 `com.xiaowan.localinference` · 最低 Android 8.0（API 26）· 仅 `arm64-v8a`

## 能做什么

- **图形界面**：模型库（列表/别名/卸载）、多会话聊天、参数区、本地服务开关与**存活探测**、日志与导出
- **HTTP 接口**：`/health`、`/v1/models`、`/v1/chat/completions`（支持 `stream`、`tools`）、`/v1/completions`、`/v1/abort`；`GET /` 是**自带测试页**（同源内嵌 HTML，浏览器直接打开就能聊）
- **结构化输出**：`response_format` 的 `json_object` / `json_schema`，走 GBNF grammar 在生成循环里逐 token 约束，不是生成完再校验
- **KV 前缀复用（prompt cache）**：多轮对话与带 `tools` 的固定段不再每轮从零 prefill，只算新增的尾巴
- **可调参数**：`n_ctx`、线程数、`parallel`（**当前不产生并发**）、`batch/ubatch`、采样与惩罚参数、Flash Attention、KV cache 量化、权重重排（`repack`）、mmap 开关、默认关闭思考
- **推理后端**：CPU（按指令集选 4 个预编译变体）+ OpenCL GPU + Hexagon NPU（HTP，实验性，默认关闭）
- **可取证**：Java 异常落 `crash_last.txt`；native stderr 实时并入会话日志；可选「崩溃探针」把 native 日志与信号现场不经 JVM 直写文件
- Kotlin 侧**零第三方运行时依赖**（只用 Android framework 与 `org.json`），无账号、无遥测

## 快速开始

1. 安装 APK（Releases 提供 debug 构建；本仓库不含模型权重）。设置页顶部会显示当前构建的版本号与构建号，报障时直接引用它。
2. 把 `.gguf` 放进 `Android/data/com.xiaowan.localinference/files/models/`，在 App 里选中 → **加载**。该目录属应用私有外部目录，**不需要授予存储权限**。
3. 要用推理参数以外的默认值，在「设置」里调；参数**以 App 为准**，会覆盖请求里的同名字段。
4. 给别的设备用：打开「局域网访问」（否则只绑 `127.0.0.1`），记下端口与通知里显示的 IP。
5. （可选）要限制"谁能调生成端点"：设置页「本地服务」分组里的「接口鉴权」一栏点「生成 token」，按「复制」把 token 填进你的客户端（第三方 UI 填它的「API Key」框即可）。不改这一步就是默认不开鉴权。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"qwen2.5-1.5b-instruct-q4_k_m",
       "messages":[{"role":"user","content":"用一句话解释 KV cache"}],"stream":true}'
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | **自带测试页**（同源，内嵌 HTML，零外部依赖）：浏览器直接打开即可探测 `/health`、看模型列表、流式多轮聊天，带折叠设置区（思考链 / 生成与采样 / 重复与惩罚 / 结构化输出） |
| OPTIONS | 任意路径 | CORS 预检，统一回 `204`，见「浏览器 / CORS」 |
| GET | `/health` | 存活探测（App 内「存活探测」按钮即真发此请求） |
| GET | `/v1/models` | 全库模型列表；主 id 用**别名**（Ollama 风格短名），文件名在 aliases 里 |
| POST | `/v1/chat/completions` | 对话补全，认 `messages`（含 `system`）、`stream`、`tools`、`stop`、`response_format` |
| POST | `/v1/completions` | 文本补全，认 `prompt`、`stop`、`response_format` |
| POST | `/v1/abort` | 取消当前生成（无 body、无参数） |

采样参数由请求体给出，缺省时用与 App 内参数区一致的一组默认值
（`temperature=0.8`、`top_p=0.95`、`min_p=0`、`top_k=0`、`repeat_penalty=1`、
`repeat_last_n=64`、`frequency_penalty=0`、`presence_penalty=0`、`max_tokens=512`），
也认 OpenAI 的 `seed`。显式给出的取值必须落在合法范围内，否则返回 `400` 并说明原因。
线程、上下文、`batch/ubatch`、KV cache 等**加载期**参数仍在 App 里定，接口不认。

**超限请求"拒绝"而不是"截断"**：请求体上限 4 MiB（超限 `413`）、请求行与单个头行上限
8192 字节（超限 `400`）。

**并发形态：单并发**，第二个请求直接 `503`、不排队（生成循环持引擎锁，App 内聊天占用时同理）。
设置页 `parallelN` **不改变这一点** —— 它只是把 `n_seq_max` 传给库，服务端没有多 slot 调度，
反而会按份数瓜分 `n_ctx`（`parallelN=2` + `n_ctx=4096` → 每路实际可用约 2048），**建议保持 1**。
真正的多 slot 调度评估后**已搁置**：手机端是单用户场景，收益与改动量不成比例（见 Issue #40）。

**「服务在跑」与「用户想要服务在跑」是两个不同的标志**：`isRunning` 报实际状态（端口能不能连上），
`isDesiredRunning` 报用户意图。看门狗自愈只看后者，于是 listener 意外死亡会被拉起、
用户主动停服不会被偷偷拉起来。

### 鉴权（可选，默认不开）

在设置页「本地服务」分组里的「接口鉴权」一栏生成一个 token 之后，
只有**生成端点**（`/v1/chat/completions`、`/v1/completions`、`/v1/abort`）
要求 `Authorization: Bearer <token>`：

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"你好"}]}'
```

不带 token / 带错 token 一律 `401`（错误正文说明是「没带」还是「不匹配」，但绝不回显 token）。

以下路径**一律免鉴权**：`OPTIONS`（浏览器自动发预检，拦了等于 CORS 白做）、`GET /health`
（存活探针与自己人打的都是它）、`GET /v1/models`（只读清单，第三方 UI 的模型下拉框靠它填充）、
`GET /`（在地址栏里打开，没地方填 header）。路径匹配是"落在之下"而不是"以此开头"：
`/healthz`、`/v1/models-evil` **不会**被当成豁免路径；除这两条精确豁免外，其余 `/v1/*`
（含未知路径）一律要凭据。

**它不是访问控制**：token 只挡住没带凭据的调用方，挡不住同网段能直连的设备（token 走明文 HTTP，
威胁模型是"可信局域网"）。**默认不开**，不改这一步行为与升级前完全一致。

### 停止序列（`stop` / `stop_sequences`）

两个端点都认 OpenAI 的 `stop`（字符串或数组）与别名 `stop_sequences`，两个字段都给时**取并集**。
命中后停止序列本身不会出现在响应里（与 OpenAI 一致）。拦截在**生成循环里逐 token 判**，
所以 `stream=true` 时也不会把越界内容先发出去，并正确处理「stop 串跨多个 token」。
取值**语义错**才 `400`（如**空串**）；"条数多""单条太长"属于性能边界，只截断 + 落日志。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"只输出一句话，然后写 END"}],
       "stop":["END"],"stream":true}'
```

### 请求取消（客户端断连 / `POST /v1/abort`）

客户端中途放弃时，服务端**立刻停**，不再白跑满 `max_tokens`。三条路径都收敛到「当前正在生成的那一轮」：
流式客户端断连（生成期写探测发现）、非流式（写响应时抛异常这条迟到路径发现）、
以及显式调用 `/v1/abort`：

```bash
curl -s http://<手机IP>:<端口>/v1/abort -X POST
# -> {"aborted":true,"generating":true}   真的停到了某一轮
# -> {"aborted":false,"generating":false} 当时没有轮次在跑（不是失败）
```

- `/v1/abort` 不带 `request_id`：本服务任何时刻最多只有一轮在跑，**没有可选项**。
- `aborted` 区分「停到了」与「本来就没有」，两者都返回 `200`（取消不该因竞态而失败）。
- 取消**带归属**：只有「当前轮次」的取消才生效，否则一个迟到的 socket 超时会把此时真正在跑的**另一轮**停掉。
- 停止后 `finish_reason` 报 `cancelled`，不退化成 `stop` —— 调用方要能区分
  「模型说完了」与「我自己不要了」。
- `/health` 的 `generating` 字段是它唯一的可观测面（含 App 内聊天）。
- **「停止」是对这一轮说的，不是对整个进程说的**：取消是一次性状态迁移，
  App 内与测试页两侧都必须做到「一轮结束即复位」，否则会出现「按过一次停止之后就再也生成不了」。

### KV 前缀复用（prompt cache）

多轮对话**协议上无状态**：每轮都要重发整段历史，带 `tools` 时还要重发工具定义。本版做了前缀复用 ——
本轮 prompt 与上一轮进过 KV 的 token 序列共享前缀时，直接留用那段 KV，只 prefill 新增的尾巴。
复用是纯加速，协议上看不出来，只能靠 `/health` 的四个字段读：`kv_cache_valid`、`kv_reuse_tokens`、
`kv_prefill_tokens`、`kv_rounds`。

```bash
# 第 2 轮：接着问，历史全部命中
curl -s http://<手机IP>:<端口>/health
# -> "kv_cache_valid":true,"kv_reuse_tokens":<上一轮全部>,"kv_prefill_tokens":<只剩新增那几句>,"kv_rounds":2
```

复用的判据保守到刻意：逐 token 比前缀、只能复用开头那一段、不足 16 token 不复用、
账本无效（换模型 / 卸载 / 取消 / 上一轮出错 / prompt 超长）一律不复用。
猜错的代价是模型读到**别人的**历史 —— 答非所问，但接口 200、不报错。宁可慢一轮，不可错一轮。

App 设置页有「丢弃 prompt 缓存」按钮：换了 system 提示词之后旧前缀本来就匹配不上，
但那几 MB KV 会一直占着显存，等于可用上下文凭空变少。

### 结构化输出（`response_format` / JSON Schema）

`json_object`（要合法 JSON、不限结构）与 `json_schema`（按给定 JSON Schema 约束输出），
约束走 **GBNF grammar 采样器**，在生成循环里逐个 token 生效。不带 `response_format`
的请求行为与引入本特性之前逐字节相同。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "messages":[{"role":"user","content":"把张三、30 岁、北京整理成 JSON"}],
    "response_format":{"type":"json_schema","json_schema":{
      "name":"person","strict":true,
      "schema":{"type":"object",
                "properties":{"name":{"type":"string"},
                              "age":{"type":"integer"},
                              "city":{"type":"string"}},
                "required":["name","age","city"]}}}
  }'
```

- `type` 拼错 / 类型不对 / 包装缺字段 / schema 不是对象 / schema 超过 64 KiB → **`400`**。
- 库能接受但转换不出 GBNF（模型模板不支持等）→ **`200` + 降级成无约束**，并在 App 日志写明原因。
  这是刻意的：**"schema 写得不完美"不等于"服务不可用"**，这台服务跑在手机上，
  因为一个可选的约束字段把请求打成 500 不划算。代价是降级**静默**，所以日志一定留痕。
- 要求了结构化输出时，服务端在下发前把模型自由写出的 Markdown 代码围栏剥掉：
  **剥了还得是 JSON，剥不了就原样下发**。⚠ 只对非流式与缓冲流式（带 `tools`）生效；
  `stream=true` 且不带 `tools` 时正文边生成边下发，那一路上客户端仍需自行剥围栏。

**「思考开 + `json_schema`」是一处已知的语义冲突**：模板要求先写思考段，而 grammar 要求
生成前缀之后**直接**是 JSON。默认关闭思考，常态下不会遇到；要用结构化输出时建议保持思考关闭。

### 自带测试页（`GET /`）

浏览器直接打开 `http://<手机IP>:<端口>/` 就能用，**同源、零外部依赖**（不引任何 CDN，
离线场景也打得开）。页面本体是 `WebChatPage.kt` 的**纯函数产物**，所以页面结构能被宿主侧
**离线断言**（`tools/run_web_chat_tests.sh`），不必等真机上打开浏览器才发现少了一个 `id`。
折叠设置区（原生 `<details>`，**默认收起，四组**）：

- **思考链**（`enable_thinking`）：三态 —— **跟随服务端默认 / 开 / 关**。「跟随」= **不下发该字段**。
- **生成与采样**：`temperature`、`top_p`、`top_k`、`min_p`、`max_tokens`、`seed`。
- **重复与惩罚**：`repeat_penalty`、`repeat_last_n`、`frequency_penalty`、`presence_penalty`。
- **结构化输出**（`response_format`）：**不要求 / `json_object` / `json_schema`**。
  选「不要求」= **不下发该字段**，所以页面上的默认行为与服务端旧版一致。

字段名与服务端请求侧逐字一致，**留空的项不下发**，由服务端取默认值。填了非法值则**整个请求不发出**
（那会让用户明确选了 `json_schema` 却拿到**无约束**生成）。页面顶部按 `model_loaded` 提示是否已加载模型。

**多轮连贯对话**：服务端是无状态的，连贯性由页面自己维护历史。两条硬判据：
**只有成功的回复才进历史**、**「新对话」同时清历史与已渲染的气泡**。
页面自带的 token 输入框留空则一个 `Authorization` 头都不发。

### 浏览器 / CORS（白名单式）

服务端每个响应都带 CORS 头、`OPTIONS` 统一回 `204`。默认放行**回环来源**
（`http://localhost:*`、`http://127.0.0.1:*`、`http://[::1]:*`）、**`Origin: null`**
（`file://` 页面 / sandboxed iframe）与**本机 IP** 的来源。其余来源一律不回 CORS 头。
要放行局域网内另一台机器上的前端（例如 PC 上的 Open WebUI），
在设置页「CORS」那一栏按行填来源，如 `http://192.168.1.10:3000`，改完立刻生效。

**刻意不做、也不要自己加上去的两件事：**

- **不用 `Access-Control-Allow-Origin: *`**。本接口**无鉴权**，现状的攻击面是「局域网内直连」；
  一旦回 `*`，攻击面立刻变成「你在浏览器里打开的任何一个网页都能调这台手机的模型」，
  一个恶意页面可以静默拉满算力与电量。所以白名单默认集极窄，且只回**原样回显**的命中来源。
- **不发 `Access-Control-Allow-Credentials`**。本服务不发 Cookie、不用 HTTP 认证，
  开了只会让"某个来源被放行"的后果变严重，收益为零。

白名单外的来源**回 204 而不是 403**：头缺失本身就是拒绝，而 403 会让"来源被拒"与"服务坏了"
在客户端看起来一模一样。来源被拒时服务端日志会有 `CORS 拒绝来源: …` 一行。

**两件容易混的事，它们是独立的：**

| 你要的效果 | 靠什么 | 需要 CORS 吗 |
|---|---|---|
| 打开 `http://<手机IP>:<端口>/` 直接聊 | `GET /` 自带测试页（**同源**，支持多轮） | **不需要**。同源页面根本不查 CORS |
| 让跑在**别的源**上的前端（Open WebUI 等）调这台手机 | CORS 白名单 | 需要，且要把它填进白名单 |
| 让**没带凭据的调用方**调不动生成端点 | 鉴权（`Authorization: Bearer`） | 不需要 |

所以「CORS 修好了」并不等于「服务端自带网页就能用了」—— 后者靠的是 `GET /` 这条路由。
另有一条硬约束：`OPTIONS` **必须豁免鉴权**（预检是浏览器自动发的，不带 `Authorization`，
拦了它等于 CORS 白做）。

### App 内「存活探测」

设置页「启动服务」旁的**存活探测**按钮会从 App 内真发一次 `GET /health`，把结论常驻显示在
按钮下方（可长按复制），并同步一份到聊天页状态栏与运行日志。三档结论：

| 结论 | 含义 |
|---|---|
| ✅ 存活 | 端口可达、`status=ok`、模型已加载 —— 现在就能推理 |
| ⚠ 可达但状态不完整 | 端口通了，但 `status` 非 `ok`、或**模型未加载**（生成类请求会返回 503） |
| ❌ 不可达 | 连接被拒 / 读超时 / 应答的不是本服务（正文不是 JSON） |

它**不看** `HttpApi.isRunning` 这类进程内标志位：那些只说明对象自认为在跑，说明不了端口真可达。
所以探测走完整链路：TCP 连接 → 发请求 → 收响应 → 解析 JSON → 判定。

### 工具调用（tools / function calling）

`/v1/chat/completions` 认 OpenAI 的 `tools`，命中时 `finish_reason` 为 `tool_calls`，
`message.tool_calls[].function.arguments` 是 JSON 字符串；`stream=true` 时按 OpenAI 约定
以增量块下发。`tool_choice`（`auto`/`required`/`none`）与 `parallel_tool_calls` 均生效。

- 工具调用语法**由模型模板决定**（Qwen 的 `<tool_call>`、Llama 的 `[TOOL_CALLS]` 等），
  工具定义经模板渲染进 prompt，模型输出按模板归一，Kotlin 侧不做正则猜测。
- 模型模板不支持工具调用、或渲染失败时**回落到普通对话**，不会让请求整体失败；
  `tools` 不是数组（如写成对象）才返回 `400`。
- 带 `tools` 的流式请求会**整段生成完再下发**（否则 `<tool_call>` 之类的标记会被当正文吐出去），
  因此首字延迟等于整段生成时间。

```bash
curl -s http://<手机IP>:<端口>/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"北京现在天气怎么样"}],
       "tools":[{"type":"function","function":{
          "name":"get_weather","description":"查询指定城市天气",
          "parameters":{"type":"object",
            "properties":{"city":{"type":"string"}},"required":["city"]}}}]}'
```

### 默认关闭思考

设置页的「默认关闭思考」勾选框在**「生成与采样」分组内** —— 它决定的是**生成本身**。
有两条生效路径：**模板自带 `enable_thinking` 变量时交给模板自己关**（模板知道自己换行怎么写）；
**模板里没有这个变量、库关不掉时，才由软开关兜底**。
请求侧覆盖优先级：`enable_thinking` > `chat_template_kwargs.enable_thinking` >
`reasoning_effort`（`none` 视为关）> 全局默认。App 内聊天与 HTTP 接口**共用同一份实现**。

### 输出的思考段 / 正文分流

生成结果按 `<think>` 标签切成两路：思考段进 `reasoning_content`，正文进 `content`，
HTTP 的流式与非流式共用同一份实现。判据是「后缀里有一个**未闭合**的 `<think>`」，
而不是「后缀里出现过 `<think>`」：判成"已开"之后，模型吐的 `</think>` 会被当裸标签
静默剥离，整段回答留在 `reasoning_content` 里。嵌套 `<think>` 与裸 `</think>` 一律剥离。

## 支持的后端与机型

推理核心不自建，直接用 [Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn) 的
预编译产物（`vendor/`，未做任何二进制改写）。启动时逐个尝试，任一失败即降级：

| 顺序 | 需要条件 | 后端能力 |
|---|---|---|
| 1 | **高通** SoC **且** i8mm **且** 有 `libOpenCL.so` | CPU + OpenCL GPU + Hexagon NPU |
| 2 | i8mm | 纯 CPU |
| 3 | dotprod | 纯 CPU |
| 4 | 无（基线） | 纯 CPU |

第 4 档不可省：骁龙 865/870（Cortex-A77）连 dotprod 都没有，缺基线包会直接闪退。
判定只看内核导出的 CPU 特性，**只保守不冒进**（漏报就退回低档）。加载结果写在日志首行，可核对。

## Hexagon NPU（可选，默认关）

开启三步：设置里打开 HTP → **重启 App** → 加载时确认日志显示 HTP 生效。
缺任一条件都会静默回退，不影响使用。要点与排查见 **[docs/HTP.md](docs/HTP.md)**。一句话结论：

> NPU 上**不一定**比 CPU 快。小模型（约 ≤4B）CPU 往往更优；NPU 的价值在于长 prompt 的 prefill
> 与把权重从 GPU 显存挪开。UI 里的「NPU 配额档」不是性能旋钮，只用于腾挪显存/排查，建议保持「自动」。

## 已知限制

- 仅 `arm64-v8a`；只提供 **debug** 构建（release 需先解决签名与 minify 配置）。
- **单并发**：同一时刻只跑一轮生成，第二个请求直接 `503`、不排队；`parallelN` 填 >1 只会按份数瓜分 `n_ctx`，建议保持 1。
- 结构化输出受模型模板能力约束，转换不出 GBNF 时降级成无约束（日志留痕），不是静默也不是 500。
- **「思考开 + `json_schema`」的语义冲突尚未处理**：默认关闭思考，常态下不会遇到。
- `gpuLayers > 0` 且 `n_ctx > 32768` 时 OpenCL compute buffer 可能分配失败；失败会**自动回退纯 CPU**。
- HTP 后端对部分算子/量化不支持 → 静默回退 CPU；算子只落在 HTP0 单核。
- 长 prompt **首轮** prefill 可能几十秒级，期间界面只有日志在动，没有百分比进度。之后多轮走 KV 前缀复用，只算新增的尾巴。换模型 / 改 system 提示词后回到首轮代价。
- **CORS 不是访问控制**：白名单只决定"浏览器肯不肯把这个响应交给页面"，任何绕过浏览器的客户端（`curl` / 脚本 / 别的 App）从来不受 CORS 约束。
- **鉴权也不是访问控制**：token 挡住的是"没带凭据的调用方"，**挡不住同网段能直连的设备**（token 走明文 HTTP）。默认不开，请在可信网络使用。
- 同一时刻只服务一个已加载模型；换模型走卸载重载。内存若未归还，重启 App 是最快恢复手段。
- **mmap 与 GPU/NPU 不互斥**：CPU 层走内存映射（RSS 只随被读到的页增长），
  GPU/NPU 层本来就要拷进显存，占的是显存不是 RAM。同一份权重可以"CPU 的层 mmap、OpenCL 的层进 VRAM"。
  改动后需重新加载模型生效，实际档位与权重落点记在日志 `[加载]` / `[mmap诊断]` / `[mmap池]` 几行。
- **mmap 省内存是"加载后归还"实现的**：上游预编译库会把整份权重预读进内存（`MAP_POPULATE`），
  本仓库在加载完成后把那些干净页还给内核，因此日志里能看到 `[mmap释放] RSS A MB → B MB`。
  稳态下权重按需缺页读入，首轮推理可能略有回读开销。
- **mmap 在共享存储上能否省内存，分机型**：模型放在 `/storage/emulated/0/…` 时，
  能否共享取决于该挂载是否走 **FUSE passthrough**（Android 11+，看内核与 ROM），native 侧探不出来。
  判据看同一轮的 `[mmap释放]` 读数：**RSS 真落下来即共享**。
  「复制到应用内」换不到省内存（私有目录也在同一挂载下），别为此白搬 4~8 GB。
- **权重重排（repack）是另一个占内存的默认值**：q4_K/q6_K 会被重排成 `q4_K_8x8`/`q6_K_8x8`
  **另存一份匿名内存**，随 CPU 层数线性放大。**这不是量化**：盘上的权重一个字节没变、精度不变，
  repack 只是同一份权重的第二份内存排布，**不落盘、只在内存**。
  设置页「加载与性能」分组里三档单选：**没设过（跟随库默认 = 开）/ 关 / 开**（改后需重新加载）。
  **默认保持开启**。日志里 `[repack]`（判定）与 `[repack结果]`（真落给库的值 + 加载增量 RSS）**成对读**，
  两行对不上 = 中间某一步断了。想量它值多少：选「关」重新加载，与「没设过」那次比读数与生成速度。
  ⚠ 这份是匿名页，`[mmap释放]` 碰不到它 —— mmap 开开关关都不会让这份内存变化。
- **`free` ≠ RSS 下降**：CPU 侧分配器会缓存刚释放的堆段，于是 repack 拷贝、KV、compute buffer
  虽然都 `free` 了，**应用列表里显示的「占用」照样不降**，看起来就像"关掉 repack 根本没省"。
  本仓库在「卸载模型」这一步主动把空闲堆段还给内核（日志 `[内存] 卸载 RSS A MB → B MB`，
  必要时补一行 `[内存] 归还空闲堆段 …`），只在卸载后空闲这一刻做。
- **退出 App 会主动释放模型**：`EngineActivity` 在真退出（不是旋转/多窗口重建）且 HTTP 服务**没在跑**时
  自动释放 —— 服务在跑还释放的话，`/v1/models` 会当场变 503，那种情况由「停止服务」负责。
- **推理期也有 RSS 读数**：一轮生成收尾会打 `[内存] 推理期 RSS 峰值 … MB`，与 `[mmap释放]` **成对读**。
  三行都读 **VmRSS**，与任务管理器里看到的「占用」是同一个数。
- 前台服务用于保活，部分国产 ROM 仍会杀后台，需要自行加白名单。

## 数据存放位置

| 内容 | 路径 |
|---|---|
| GGUF 模型 | `<getExternalFilesDir(null)>/models/*.gguf`（外部存储不可用时回落到 `<filesDir>/models`） |
| 会话记录 | `<filesDir>/<SessionStore 目录>/s_<id>.json`（当前会话 id 存于 `cur`） |
| 运行日志 | `<filesDir>/logs/session-<启动时间>.log`，保留最近 8 个会话 |
| 导出日志 | 日志页「导出本次 / 上次会话」→ 公共「下载」目录（API 29+ 走 MediaStore，无需权限） |
| 崩溃现场 | `<getExternalFilesDir(null)>/crash_last.txt` |
| native 崩溃探针 | `filesDir/logs/probe-native.log`（设置页「崩溃探针」打开后由 native 直写） |
| 参数/端口/别名 | `SharedPreferences`（经 `ModelStore`） |

## 构建

**质量门禁：`.cnb.yml`（CNB 流水线）** —— 仓库里唯一的**质量**门禁，一把跑完 `tools/` 下的
全部离线套件（各个模块守卫、守卫自测、行为复刻对照）。改任何源码、守卫或文档，都在这里被拦。

**APK 分发链路：`.github/workflows/build.yml`（GitHub Actions）** —— 与上一条是**两条**职责不同的流水线。
只做构建，**不跑任何**
`tools/run_*`。push 到 `main` 且改动影响 APK 时构建，也可手动触发；
纯文档与工具类改动（`**.md`、`docs/**`、`scripts/**`、`tools/**`、`.gitignore`、`LICENSE`）
由 `paths-ignore` 排除，不触发构建。注意这条 `paths-ignore` 是 **GitHub workflow 自己的**行为，
与 `.cnb.yml` 无关 —— 改 `tools/**` 不会触发 APK 构建，但 `.cnb.yml` 那侧照跑。

**本地构建**：Android Studio 或自备 Gradle 8.10.2（仓库不含 wrapper）。`compileSdk 35` /
`minSdk 26` / `targetSdk 28` / AGP 8.7.3 / Kotlin 2.0.21 / C++17 / `ANDROID_STL=c++_static`。
`settings.gradle.kts` 把国内镜像排在最前、官方源只作兜底。

**离线单测**：不装机、不下载模型也能跑的单测都在 `tools/run_*.sh`，覆盖采样与停止序列语义、
请求取消、KV 前缀复用、思考开关与正文分流、结构化输出的解析与接线、CORS 与鉴权、
崩溃探针、mmap 开关接线等；没有 NDK 也能做真实类型检查：`bash scripts/kt_check.sh`。
`tools/run_static_order_guard.sh` 会编 `llama_jni.cpp` 本体，挡住**静态 grep 看不见**的那一类
（函数先用后定义、签名与前置声明不一致、改了签名漏改调用点）。

## 排查 native 闪退（崩溃探针）

native 侧的 `abort`（SIGABRT）由 libc 直接终止进程，**不经过 JVM**，所以
`UncaughtExceptionHandler` 抓不到它。表现就是「客户端一调工具，服务端进程闪退又自动恢复，
日志里什么都没有」。用法三步：

1. 设置页 →「崩溃取证（探针）」打开「崩溃探针」，**完全退出并重启 App**（探针在 native
   库加载前挂载，必须冷启动才生效）。
2. 复现那次请求（客户端带 `tools` 调一次）。
3. 重开 App → 日志页 →「导出崩溃探针」→ 公共「下载」目录拿到 `probe-<时间戳>.txt`。

导出的文件是三段合一：native 探针原文、Kotlin 侧本次会话全文、上次会话全文，末尾附设备/后端/Boot ID。
探针关闭时开销为零。开关有唯一的事实来源（设置页），native 侧在 `JNI_OnLoad` 里还会先自举一次，
自举阶段**只记不写**（不打开文件、不落盘），所以探针没启用时自举记的事件**不建文件、不落盘**、
只进 logcat，`probe-native.log` 里没有 `[boot]` 行是正常现象。
Kotlin 侧**「关」也会显式告知 native** —— 否则用户明明关了探针，原生日志却全被丢进一个空 sink、
同时还在持续写盘。

## 致谢

本项目站在这些工作的肩膀上，衷心感谢：

- **[ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)** 与 **The ggml authors** ——
  推理引擎、GGUF 格式、GGML 后端框架与 Hexagon NPU 后端本体。本项目的 NPU 能力完全来自上游，未自研算子。
- **[Vali-98/cui-llama.rn](https://github.com/Vali-98/cui-llama.rn)**（Jhen-Jie Hong，MIT）——
  我们直接采用的 Android arm64 预编译 `librnllama_*` 库与头文件，以及 NPU 侧的 DSP skeleton。
- **Qualcomm** —— Hexagon DSP / HTP 硬件与 FastRPC 运行时（`libcdsprpc.so`，由设备系统提供，本包不分发）。
- **The Android Open Source Project**、**Kotlin**、**Android Gradle Plugin**、**LLVM/libc++**、**GitHub Actions** —— 平台与工具链。
- 各开源模型社区（Qwen/阿里、Gemma/Google、Llama/Meta、Hugging Face 上的量化贡献者等）。
  **本仓库不分发任何模型权重**，用户自备模型并自行遵守其许可证。

本项目与上述组织无隶属或背书关系。

## 许可证

- 本项目代码：**MIT**（见 [LICENSE](LICENSE)）。
- 随包分发的第三方二进制/头文件的许可证原文与出处：见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
- 模型权重受各自许可证约束，不由本项目的 MIT 覆盖。

版本以 git tag 表示，里程碑说明见 `git tag -n` 或 Releases 页。
