# Issue #174 / 模块乙-4：线程与资源生命周期自检（收口）

本文件是 **Issue #174「代码自检」模块乙-4** 的收口结论。
它回答三件：[审的是什么]、[加了什么判据]、[这些判据能不能自己站住]。

> 议题背景与分模块方案的原始记录在 Issue #174 的评论里（本文件只做收口）。
> 本文件里的套件条数由 `tools/run_ci_wiring_guard.sh` 的判据⑤ 与
> `tools/suite-counts.json` 逐条钉住 —— 本文的数字和别的文档受同一把尺子约束。

---

## 一、一句话结论

线程与资源的失效方向是"**只涨不跌**"，而它**与正确顺序同形** —— 三条此前没有判据：
`beginCancelable` 摘除的**位置**、基础设施线程的 **daemon 标记**、`ioExecutor` 的
**shutdown 时序**。新增 `tools/run_resource_lifecycle_guard.sh`（17 条）+ 自测（10 条）。

---

## 二、审计范围与既有覆盖

方案里 乙-4 点名的三块，**两块已被前几轮覆盖**，本模块补的是剩下的一块：

| 方案点名 | 现状 |
|---|---|
| 连接/资源**计数泄漏**（只涨不跌） | ✅ 已覆盖：`run_http_lifecycle_guard.sh` 判"计数与登记表同源增减""超限拒绝必须关 socket"；`run_conn_cap_tests.sh` 复刻测试 |
| 流式路径**取消归属**的摘除 | ❌ **本轮补**：`beginCancelable`/`endCancelable` 此前只在注释里被提过，**零判据** |
| 线程池 / 执行器**生命周期** | ❌ **本轮补**：`isDaemon` 与 `ioExecutor.shutdownNow` 此前**零判据** |

---

## 三、为什么这三条必须单列

它们的坏法都是"零症状"且**与正确顺序同形**：

- **摘除不在 `finally` 里**：正常路径照常摘得掉、异常路径漏掉 —— 留下一个
  "永远在跑"的僵尸轮次；之后所有 `/v1/abort` 都打在不存在的对象上。
  HTTP 照常 200，用户只会觉得"取消不好使了"。
- **基础设施线程不是 daemon**：Service 停止后 JVM 被非守护线程吊住不退出，
  进程"看起来还活着"；而"文件里有 `isDaemon`"照样为真（漏标的是**那一个**）。
- **`ioExecutor` 不 shutdown**：它是每次 `onCreate` 现建的，不关就随每次重建各留
  一个线程，且任务 lambda 捕获 `this`，等于可观测的 Activity 泄漏。

---

## 四、加了什么（判据清单）

`tools/run_resource_lifecycle_guard.sh`（17 条）：

| 判据 | 防的坏法 |
|---|---|
| `beginCancelable` / `endCancelable` **数量配对**（逐文件） | 少一个 end → 僵尸轮次 |
| 每个 `endCancelable` 都**身处 `finally` 块内**（逐处，走 python） | 配对但不在 finally → 异常路径漏摘 |
| HTTP 连接 / listener / watchdog / stderr 泵 / 健康探测线程**逐处**标 daemon | 漏标一个 → 进程不退 |
| HttpApi 每个 `Thread` 创建点都标 daemon（逐处核，防裸 `start`） | 同上 |
| `ioExecutor` 每次现建 / `onDestroy` 里 `shutdownNow` / **排在 `super.onDestroy()` 之前** | 每次重建留线程 / 与已拆视图抢资源 |

**逐处判据怎么做的**：`endCancelable` 的"在不在 finally 里"与 `Thread` 的
"标没标 daemon"都走 python helper（`tools/resource_lifecycle/check_*.py`）——
awk 在嵌套花括号/跨 200 行 lambda 上不可靠（本守卫第一版用 awk **假红了 2 条**）。

---

## 五、判据能不能自己站住（双向自测）

`tools/run_resource_lifecycle_guard_tests.sh`（10 条）—— 拿 4 份**就地改坏的真源码**跑，
断言"该红的红、指得出是哪一条、还原后全绿"：

| 桩 | 坏法 | 后果 |
|---|---|---|
| ① | 摘除**整块删掉**（离开 finally） | 异常路径漏摘 → 僵尸轮次 |
| ② | `http-conn` 线程漏标 daemon | Service 停后进程不退 |
| ③ | `ioExecutor.shutdownNow()` **整行删掉** | 每次重建留一个线程 |
| ④ | shutdown 排到 `super.onDestroy()` 之后 | 与已拆视图抢资源 |

**一个坑记下来**：桩①③ 第一版写成"**注释掉**那一行"，结果是**假绿** ——
注释里仍有 `endCancelable` / `shutdownNow` 字样，而本守卫的配对/存在判据按文本数
（`grep -c`），注释照样算。真实的不摘除/不关闭是**整行不存在**，所以桩也改成
**删掉或挪走**。这条对别的守卫同样适用：**用注释做桩，测不出存在性判据的漏洞**。

---

## 六、明确没做的事

- **没动被审源码**：`HttpApi.kt` / `EngineActivity.kt` / `StderrPump.kt` 一个字节没改
  （本轮是**纯加判据**；三处核下来当前都正确）。
- **没要求 `InferenceService.kt` 的线程标 daemon**：那 3 处是"延迟 400ms 更新通知"的
  工作线程，跑完即退；**非守护在这里是有意的**（守护化反而可能在 Service 收尾前被
  JVM 掐掉通知）。守卫只钉"**基础设施**线程必须 daemon"这一条，不搞"所有线程都 daemon"
  的一刀切 —— 后者会逼人写出错误的代码。
- **没做真机验证**：本机无 Android 设备；结论到"源码结构 + 逐处归属"为止。
- **连接数/计数泄漏那一族未重复覆盖**：`run_http_lifecycle_guard.sh` 已在守。

---

## 七、接入

- 守卫接进 `.cnb.yml`（请求边界守卫自测之后），纯 sh + python3 标准库，不依赖工具链；
- 自测紧随其后接进 `.cnb.yml`；
- 两份条数落进 `tools/suite-counts.json`，由 `run_ci_wiring_guard.sh` 判据①③⑤⑦ 约束。

| 套件 | 条数 |
|---|---|
| `tools/run_resource_lifecycle_guard.sh` | **17 条**（生命周期三条） |
| `tools/run_resource_lifecycle_guard_tests.sh` | **10 条**（上一份的自测） |
