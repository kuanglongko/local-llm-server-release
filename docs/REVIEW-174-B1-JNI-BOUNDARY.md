# Issue #174 / 模块乙-1：跨语言边界自检（收口）

本文件是 **Issue #174「代码自检」模块乙-1** 的收口结论。
它回答三件：[审的是什么]、[加了什么判据]、[这些判据能不能自己站住]。

> 议题背景与分模块方案的原始记录在 Issue #174 的评论里（本文件只做收口）。
> 本文件里的套件条数由 `tools/run_ci_wiring_guard.sh` 的判据⑤ 与
> `tools/suite-counts.json` 逐条钉住 —— 本文的数字和别的文档受同一把尺子约束。

---

## 一、一句话结论

**Kotlin 29 个 `external fun` ↔ C 侧 29 个 JNI 导出，此前只有 `nativeNewSampler`
一个被核过形参**（且是逐字锚一段字符串）；`offsetof` 用的是 `>= 14` 的**数量**判据。

新增 `tools/run_jni_boundary_guard.py`（11 条）+ 自测（12 条），把三处都变成**逐项**判据。

---

## 二、为什么单列这一格

这是**唯一"两端各自都合法、合起来静默错"**的地方：

- **名字对不上**（Kotlin 改了名、C 侧没跟）：Kotlin 编译过、App 装得上，只在**调到
  那个方法那一刻**抛 `UnsatisfiedLinkError` —— 现场看像"某个功能偶发崩溃"；
- **形参漂移**（个数/类型）：更隐蔽。JNI 按**位置**取值，少一个参数、类型写错，
  静默读到垃圾或默认值 —— 没有异常、没有日志；
- **结构体不同源**：`offsetof` 断言若是**数量**判据（`>= 14`），头与 `.so` 换了版本
  （删 2 个字段加 2 个）数量不变、断言照绿。

本仓库合入过 `nativeNewSampler` 形参漂移那一次 —— 说明这类漂移**真会发生**。

---

## 三、审到的规模（本机实测）

| 项 | 数量 |
|---|---|
| Kotlin `external fun` | **29** |
| C 侧 `Java_..._` 导出（`llama_jni.cpp` + `cpu_feat.c`） | **29** |
| 名字配对：缺导出 / 多声明 | **0 / 0** |
| 逐项核过的形参槽位 | **55** |
| `common_chat_templates_inputs` 字段 / `offsetof` 断言 | **16 / 16**（一一对上） |

> 注：`cpuCaps()` 的导出在 `cpu_feat.c`（独立 `libcpufeat.so`），不在 `llama_jni.cpp`——
> 只扫一个文件会把这条判成"缺导出"（本守卫第一版就踩过，故两侧都扫）。

---

## 四、加了什么（判据清单）

`tools/run_jni_boundary_guard.py`（11 条）：

| 判据 | 防的坏法 |
|---|---|
| 每个 `external fun` 都有 JNI 导出 | 改名/漏实现 → 调到才抛 `UnsatisfiedLinkError` |
| 每个 JNI 导出都有 Kotlin 声明 | 死导出（无人调） |
| 每个导出的形参**个数**与 Kotlin 一致（逐项） | 少参/多参 → JNI 按位置静默读错 |
| 每个形参**类型**与 Kotlin 一致（逐项 55 槽位） | `jint` 写成 `jlong` → 64 位机上高位垃圾 |
| 结构体**每个字段**都有 `offsetof` 断言（逐项） | 头加字段、断言没跟 → 头与 `.so` 不同源 |
| `offsetof` 断言没有指向**不存在的字段** | 头删字段、断言留着 → 编译期过、偏移静默错位 |

**怎么做出来的**：从 `LlmEngine.kt` 抽 `external fun` 形参序列，按
`String→jstring` / `Int→jint` / `Array<T>→jobjectArray` 等映射成"期望的 JNI 槽位"；
从 C 侧抽 `Java_..._<名>(...)` 的形参（剥掉 `JNIEnv*`/`jclass` 与形参名），逐槽位比。
结构体字段清单从 vendor 头（`chat.h`）按花括号深度切出来，与 `chat_abi.h` 的
`offsetof` 列表做**集合比对**。

---

## 五、判据能不能自己站住（双向自测）

`tools/run_jni_boundary_guard_tests.sh`（12 条）—— 拿 5 份**就地改坏的真源码**跑，
断言"该红的红、指得出是哪一条、还原后全绿"。桩全是真会写出来的坏法：

| 桩 | 坏法 | 现实对应 |
|---|---|---|
| ① | Kotlin 改名、C 侧没跟 | 调到才抛 `UnsatisfiedLinkError` |
| ② | C 侧形参**删掉一个** | JNI 按位置取值，静默读错 |
| ③ | 形参类型写错（`jint`→`jlong`） | 64 位机上高位垃圾 |
| ④ | 结构体加字段、没加 `offsetof` | 头与 `.so` 不同源却无人发现 |
| ⑤ | `offsetof` 指向已不存在的字段 | 头换版本后静默错位 |

---

## 六、明确没做的事

- **没动任何源码**：`app/` 下一个字节没改（本轮是**纯加判据**；三处核下来当前都一致）；
- **没比对 vendor `.so` 的 `.dynsym`**：方案里提到"没有判据比对 `.so` 的 `.dynsym`
  与源码里的调用"。本轮的判据是**源码 ↔ 源码**（Kotlin ↔ C 导出 ↔ 头）；
  `.so` ↔ 源码那一层需要 NDK 产物，CI 里**编 APK 之后**才有 —— 登记在此，属丙-2 发布链的邻域。
- **没做真机验证**：本机无 Android 设备；结论到"源码逐项比对"为止。
- **没扩到 `chat_abi.h` 的 16 条 `offsetof` 数值**：本守卫只判"字段齐全 / 无幻影"，
  不判"每条偏移的**具体数值**对不对"（那需要真 libc++ 布局，`run_abi_tests.sh` 的
  编译期 `static_assert` 已在守；两者互补，不重复）。

---

## 七、接入

- 守卫接进 `.cnb.yml`（ABI 守卫单测之后），纯 python3 标准库，不依赖工具链；
- 自测接进 `.cnb.yml`（string 布局判据单测之后）；
- 两份条数落进 `tools/suite-counts.json`，由 `run_ci_wiring_guard.sh` 判据①③⑤⑦ 约束。

| 套件 | 条数 |
|---|---|
| `tools/run_jni_boundary_guard.py` | **11 条**（三处逐项比对） |
| `tools/run_jni_boundary_guard_tests.sh` | **12 条**（上一份的自测） |
