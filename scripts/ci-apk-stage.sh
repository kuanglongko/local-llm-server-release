set -e
echo "=== sha ==="; git rev-parse HEAD

if ! command -v gradle >/dev/null; then
  curl -sSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.10.2-bin.zip
  unzip -q /tmp/gradle.zip -d /opt
  export PATH="/opt/gradle-8.10.2/bin:$PATH"
fi
gradle --version | head -3

echo "=== vendor 库 STL 阵营扫描（ABI 前提）==="
python3 tools/check_abi.py vendor/cui-llama.rn-v1.12.2/arm64-v8a/librnllama.so

echo "=== ABI 守卫单测 ==="; sh tools/run_abi_tests.sh
echo "=== string 布局判据单测 ==="; sh tools/run_string_layout_tests.sh
# 跨语言边界守卫（Issue #174 / 模块乙-1）：`external fun` ↔ JNI 导出 ↔
# 结构体 offsetof，三处**逐项**对上（不是数量）。
# 这是唯一"两端各自都合法、合起来静默错"的地方：改名 -> 调到才抛
# UnsatisfiedLinkError；形参漂移 -> JNI 按位置取值静默读垃圾；
# offsetof 只判数量 -> 头与 .so 不同源也照绿。
# 纯 python3 标准库，不依赖工具链。
echo "=== JNI 边界守卫（逐项比对形参与结构体字段）==="; python3 tools/run_jni_boundary_guard.py
# 上面那份守卫的自测：5 份就地改坏的真源码（改名 / 形参少一个 /
# 形参类型写错 / 结构体加字段没加 offsetof / offsetof 指向幻影字段），
# 断言"该红的红、指得出是哪一条、还原后全绿"。
echo "=== JNI 边界守卫自测（对着打桩源码跑）==="; sh tools/run_jni_boundary_guard_tests.sh
# 上面那份守卫的自测：4 份就地改坏的真源码（位常量写错 / 取值来源写反 /
# Kotlin 槽位对调 / sink 裸调致排空中断），断言"该红的红、指得出是哪一条、
# 还原后全绿"。纯 sh + python3，不依赖编译器（编译那步 REQUIRED=0 可退化）。
echo "=== cpu_feat / StderrPump 守卫自测 ==="; sh tools/run_cpu_feat_guard_tests.sh
# `cpu_feat.c`（变体选择）+ `StderrPump.kt`（native stderr 接日志）守卫
# （Issue #174 / 模块丙-1：此前这两处**零判据、零单测**）。
# `cpu_feat` 判错一位 = 静默选到慢变体（体感慢，说不清）；`StderrPump`
# 排空一断 = native 线程永久阻塞在 write(2)（卡死比崩溃更难查）。
# 含宿主侧行为单测（合成 hwcap 覆盖四种组合），需 g++。
echo "=== cpu_feat / StderrPump 守卫 ==="; sh tools/run_cpu_feat_guard.sh
# 模板渲染缓冲区的**边界语义**单测（guard page 钉住越界读）。
# 与上一行同为 g++ 宿主单测：踩中它的症状是进程随机被杀，
# 事后极难归因到"一个长度参数少加了 1"，只能在宿主侧用 guard page 提前拦。
# 为什么必须进 CI：这一份此前**只在磁盘上存在**、从未被接进来，
# 而 README/HTP-STATUS 写着"全通过" —— 不跑的测试与没有这份测试等价，
# 且更坏：它让文档里的数字变成假证据（同 run_cors_live_tests.sh 那条）。
echo "=== 模板缓冲边界单测 ==="; sh tools/run_chat_buffer_tests.sh
# 「附件到期清扫」（ISSUE #310）的三件套。用户问"上传的附件和贴出来的 APK
# 能占多少、在哪儿自己删"，并定了寿命 3 天。平台在 Issue/PR 评论附件上
# **没有 ttl 字段**（上传表单只有 name / size / content_type），所以"3 天"
# 落在一条每日定时任务上：`scripts/prune-issue-assets.sh` 列记录过滤后删。
#
#   · 行为测试 —— 拿本机假平台（tools/prune_assets/fake_api.py）跑真 HTTP，
#     钉住"边界那天算不算"「不该删的一条都不碰」「dry-run 真没删」
#     「403 / 连不上必须整体失败而不是写成共 0 条」「分页要停得住」。
#     这几条的错法**全在输出文本上分不开**（清单一样、退出码一样），
#     只有数"实际发了哪些请求"才判得出 —— 所以必须是行为测试，不能静态。
#   · 接线守卫 —— 钉住"定时任务真的接上了、带 --apply、寿命写着 3、
#     放行集不含 release/commit"。这四条的失效全是静默的：
#     任务没接 = 与没做这件事同形；没带 --apply = 每天列清单而日志全绿。
#   · 上面那个守卫的自测 —— 拿 9 份打桩源码树跑它，断言该红的红、指得出是哪条。
echo "=== 附件事务清理行为测试 ==="; sh tools/run_prune_assets_tests.sh
echo "=== 到期清扫接线守卫 ==="; sh tools/run_prune_assets_guard.sh
echo "=== 到期清扫接线守卫自测（对着打桩源码跑）==="; sh tools/run_prune_assets_guard_tests.sh
# 「文档宣称通过的离线测试必须真的在 CI 里跑」守卫。
# 本轮在 main 上撞到两处同一形态的既有问题：run_cors_live_tests.sh
# 与 run_chat_buffer_tests.sh 都被 README/HTP-STATUS 写着"全通过"，
# 但两个都**从未接进 CI** —— 前者因此在 main 上根本编不过，没人发现。
# 一份不跑的测试与"没有这份测试"等价，且更坏：文档里的数字成了假证据。
# 这个守卫不测断言对不对（那是各脚本自己的事），只测
# "文档宣称的覆盖"与"CI 实际执行"是否对得上。纯静态断言。
# 另含判据⑥：CI 真的会调用的每个 `.sh` 本体都过 `sh -n` ——
# 本体语法错会让它的全部断言一行都不执行，而 CI 那一行照旧"跑了"，
# 与"接了没跑"同族，只是断在更前面一环。纯静态，不需要工具链。
echo "=== CI 接线守卫 ==="; sh tools/run_ci_wiring_guard.sh
# 「按需只编译出包」那条入口（ISSUE #303）。用户连问了几轮"直接编译拿包"，
# 而每一轮走的都是**临时**流水线（现传一份 config、不落盘）—— 包拿得到，
# 但没有任何东西记得它。正式入口落成 `api_trigger_apk_debug`，这个守卫钉
# 那条入口与既有 stage **逐字同源**。
#   · 为什么这条判据必须存在：入口"存在"却漂成另一个东西（多个参数、
#     换一份脚本体、换个没 NDK 的镜像）的后果**不是报错**，是两条流水线
#     都绿而编出来的不是同一份东西 —— 与本仓库反复吃过的
#     "函数在、用错位置"同一形态。
#   · 为什么和既有那条**并存**而不是替换：这一条只加入口，既有
#     `api_trigger_abicheck2` 一个字没动，240 份套件的质量门禁照旧由它跑。
#     判据⑥ 反向钉这一点（只加门、不许把出包那段从既有 stage 里拆走）。
echo "=== 按需出包入口守卫（ISSUE #303）==="; sh tools/run_apk_debug_entry_guard.sh
# 上面那份守卫的自测：拿**副本目录**（`CNB_ROOT`）打 10 处桩着跑，
# 断言该红的红、指得出是哪一条、还原后全绿。桩①（入口被注释掉、注释里名字
# 还在）钉的是"剥注释真的剥掉"；桩⑥b（正文说明注释里也写着
# `gradle :app:assembleDebug`，而真正那行被删）钉的是"判据不许锚字符串出现过"
# —— 这两根都是本守卫第一版**真栽过**的地方。
echo "=== 按需出包入口守卫自测（对着副本目录跑）==="; sh tools/run_apk_debug_entry_guard_tests.sh
# 判据⑤ 的**另一半**：文档条数对齐的清单（tools/suite-counts.json）
# 必须仍等于实跑条数。接线守卫只做**静态比对**（文档 vs 清单），
# 不自己跑套件 —— 分钟级操作不该压在它身上。所以清单与实跑的
# 偏离要在这一步守：只核对"不依赖工具链"的快套件。
# 纯 python3 标准库。
#
# ⚠ 那行注释里原先写着「30~40s」，而实测在 8 核 runner 上是 **10 分钟量级**
#（单是 run_rag_index_guard_tests.sh 就 ~113s，它每根桩都要重跑一遍守卫）。
# 于是这一步必然撞上 CNB runner 的**静默超时**：任务超过(10m0s)没有任何输出
# 就被强制关闭。用户看到的报错是"任务…没有任何输出而被强制关闭"，
# 而它前面那行 `=== 套件条数清单 vs 实测 ===` 之后一个字都没有 ——
# 排障方向会直接指向"套件太慢"，而真正的原因有两层：
#   ① `count_suite_totals.py` 把几百项的进度全憋在最后一起打（已改成逐项 flush）；
#   ② 这个预算本身写错了，10 分钟不够。
# 所以这里：进度行照打（新增，见该脚本），并把本步单独放宽到 20 分钟上限 ——
# 它核对的是**全部快套件**，是这一步的存在意义，砍掉它 = 判据⑤ 只剩一半。
echo "=== 套件条数清单 vs 实测（判据⑤ 的另一半）==="
python3 tools/count_suite_totals.py --check --static-only
# 判据⑧ 的**另一半**：清单里的 suite_total 必须等于目录里真实的套件个数。
# 接线守卫只做**静态比对**（文档写的「现 N 个」vs 清单的 suite_total），
# 而「清单的 suite_total 本身对不对」要在这一步守 —— 否则清单自己一漂，
# 文档跟着漂，判据⑧ 仍然全绿（判据⑤ 也踩过同一个坑：只查一边会在
# 「两边一起漂」时全绿）。纯静态、秒级，只数 glob。
echo "=== 套件总数 vs 磁盘（判据⑧ 的另一半）==="; python3 tools/count_suite_totals.py --check-total
# 守卫自身的自测：拿 6 份**打桩文件**（CI 里删掉 cors_live 的调用 /
# 磁盘上多一个没接 CI 的脚本 / README 引用不存在的脚本 /
# 扫描集合被清空 / REQUIRED=0 写了却不读 / 被 CI 调用的脚本本体语法坏掉）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 为什么必须有：本守卫是靠**算集合差**判定的，正则写歪或扫描范围写错
# 都会让三个集合一起变空 —— 三条 `[ -z ]` 全绿，守卫看起来永远健康。
# 桩④专门钉这一点；桩⑥ / ⑥b 钉判据⑥（`sh -n` 语法自证）本身不失效。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== CI 接线守卫自测（对着打桩文件跑）==="; sh tools/run_ci_wiring_guard_tests.sh
# 「发布器的清洗规则」与「发布器的卫生门禁」必须自洽，且 README 不得把
# **唯一的质量门禁**写成 APK 构建链路（模块 J 的 J-3 / J-4）。
#   · J-3 规则与门禁是两套各自维护的东西（规则按 old→new 精确匹配、
#     门禁按正则兜底）。两者打架时**此前只在发布那一刻**暴露，而且报出来的
#     是"发布失败"而不是"这里有一条规则该补"—— 排障方向全错。
#     实测：README 正文有三处日常线版本号，规则只覆盖一条，
#     套用清洗后门禁照样报 3 处脏，每次发布都被挡。
#   · J-4 README「构建」一节把 CI 写成 `.github/workflows/build.yml`，
#     而它全文 0 次引用 `tools/run_*`；真跑全部离线套件的是 `.cnb.yml`，
#     README 里 `grep -c 'cnb.yml'` = 0。照 README 读的人会以为"CI 只编 APK"。
# 判据全部锚**结构关系**（谁在谁之前、谁调用谁、红线有没有真参与判定），
# 不锚存在性 —— "函数在、用错了位置"这一族已在本项目反复出现过。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 发布器自洽守卫（J-3 / J-4）==="; sh tools/run_release_hygiene_guard.sh
# 上面那份守卫的自测：13 份桩全部取自**本轮真正的旧写法**，
# 落在 tools/release_hygiene/stubs/*.py（不是内联字符串 —— 内联会被
# heredoc 的引号层数与 `sh` 的展开吃掉反斜杠，而桩里最需要原样的源码字面量）。
# 每一根都断言"该红的红、指得出是哪一条、还原后全绿"。
echo "=== 发布器自洽守卫自测（对着打桩文件跑）==="; sh tools/run_release_hygiene_guard_tests.sh
# **发布规则自身**的守卫（Issue #174 / 模块丙-2）：每条 release-rules 的
# old/new 必须还指得着源码。规则是"精确文本匹配"，old 不再出现时 replace
# 什么也不做、不报错不告警 —— 规则死了而发布照跑，该清洗的开发痕迹直接进
# 发布线。守卫把"活跃 / 已应用 / 失效"三态分开，只对**失效**报红。
# 本轮它当场抓到 1 条失效规则（源码又改过一遍，规则再也命不中）。
# 纯 python3 标准库，不依赖工具链。
echo "=== 发布规则守卫（规则必须还指得着源码）==="; python3 tools/run_release_rules_guard.py
# 上面那份守卫的自测：3 份就地改坏的真数据（old/new 都不在源码里 /
# 规则 path 悬空 / 基线登记被删），断言"该红的红、指得出是哪一条、还原后全绿"。
echo "=== 发布规则守卫自测 ==="; sh tools/run_release_rules_guard_tests.sh
echo "=== 探针单测 ==="; sh tools/run_probe_tests.sh
# 探针开关与自举交付的结构级守卫（模块 D：D-1 / D-2 / D-4）。
#   · D-1 用户关掉探针，native 照样全量落盘并把 llama 日志 sink 换成空函数 ——
#     原生日志（加载失败、采样告警）整段消失，用户也不知道有文件。成因是自举
#     无条件打开文件并置 g_probe_on，而 Kotlin 侧「关」那次调用根本不发生。
#   · D-2 `kProbeFlagProps`（受控自举开关）是死代码：注释写着"JNI_OnLoad 时读取"，
#     全仓库 0 处读取。它本身只是死代码，但它正是 D-1 的证据。
#   · D-4 `probe_bootstrap_flush()` 恒写 0 字节（自举当场落盘并清零缓冲），
#     而排障指令恰恰是"文件里应该有一条 [boot]" —— 给出的是错误方向的确信。
# 三条共同形态是"存在但无效"，所以判据钉在**函数体结构**上
# （谁置位 g_probe_on / 自举里有没有落盘 / 调用点会不会被执行到），
# 而不是"grep 某个标识符出现过"。
echo "=== 探针开关/自举守卫（模块 D）==="; sh tools/run_probe_switch_guard.sh
echo "=== 探针开关/自举守卫自测（对着打桩源码跑）==="; sh tools/run_probe_switch_guard_tests.sh
# 「日志重置与保留窗口」（Issue #238 日志一轮）。
#   · R-1 探针文件**没有重置**：native 的 fd 是 O_APPEND，从装好到卸载只有
#     一个文件、只增不减，而且三样东西一起灌（native 全量 + stderr 遗言 +
#     逐 token 原始流）。修法是每次冷启动把它滚成 `probe-native.log.prev`，
#     用 rename 不是 unlink —— 崩前那几行在前面，删掉等于扔掉上次现场。
#   · R-2 「保留最近 8 个会话」**把上一次会话删掉**：本次文件在筛 prev 之前
#     就已创建、mtime 立刻是 now，于是它永远排第 1，`drop(KEEP-1)` 恰好落到
#     上一次会话上 —— 而"导出上次会话"正是为它准备的。排序口径改成文件名里的
#     时间戳（文件名里本来就带着）。
# ⚠ 本守卫**刻意不管体积上限**：上限值要先有实测数据，不拍圆整数写死。
echo "=== 日志重置/保留窗口守卫 ==="; sh tools/run_log_rotation_guard.sh
echo "=== 日志重置/保留窗口守卫自测（对着打桩源码跑）==="; sh tools/run_log_rotation_guard_tests.sh
# 设置页的 mmap 开关必须真的落到 llama_model_params.load_mode（模块 M）。
#   · M-1 从 0.7（a6832e9）起 `useMmap` 就透传到 JNI，而 native **从未读过它**；
#     0.9.6（3b8e2a5）只补了"关"这一支：`if (!useMmap) mp.load_mode = NONE;`，
#     "开"交给 llama_model_default_params() —— 于是用户设 1 或 0 内存都不变，
#     设 1 根本没省内存（issue #132 的真机现象）。
#   · M-2 这一族**一半有效**：关那一支真的会写 LLAMA_LOAD_MODE_NONE，
#     所以"随手调 0 看一眼常量被写进去"能得假绿；而稳态下 AUTO 本来就落 mmap，
#     两支表现一样，只有读 /proc/<pid>/maps 才分得出来。
# 所以判据钉**两支都显式落值**（三目）+ **赋值恰好一处**，
# 不钉"LLAMA_LOAD_MODE_NONE 出现过" —— 那正是旧写法的恒真绿区。
echo "=== mmap 开关守卫（模块 M）==="; sh tools/run_mmap_guard.sh
echo "=== mmap 开关守卫自测（对着打桩源码跑）==="; sh tools/run_mmap_guard_tests.sh
# 「mmap 真省内存」的第二半（模块 M2，issue #132 第二轮）。
#   · M 只钉住「useMmap 有没有显式写进 load_mode」，它现在是绿的、日志也读得到
#     MMAP —— 但用户实测内存纹丝不动。因为 load_mode 只决定"映射建不建"，
#     挡不住上游 `load_tensors()` 里硬编码的 prefetch：
#     `init_mappings(true, …)` → `llama_mmap(file, /*prefetch=*/-1, …)`（形参 size_t，
#     -1 即 SIZE_MAX）→ `MAP_POPULATE` + 全文件 `POSIX_MADV_WILLNEED`，
#     于是 mmap 时内核就把整个 GGUF 灌进物理内存，RSS 照样 ≈ 模型大小。
#   · 本仓库硬约束不改 vendor 的 .so，所以修复放在**加载之后**：自己
#     **从 `/proc/self/maps` 找出库那份映射的区间，对那些地址**
#     `madvise(MADV_DONTNEED)`。
#   ⚠ 第一版在这里写错了做法，而守护**当时是绿的**：它是"自己再 mmap
#     同一个文件 + 对它 DONTNEED（号称同一份 page cache、库那侧一并放掉）"。
#     `MADV_DONTNEED` 只清**调用它的那个 VMA** 的页表项，库那份是**另一个
#     VMA** —— `madvise` 返回 0、日志照打"已归还"，RSS 一页没降。
# 判据钉**函数体结构**（走 maps 找库映射、不再自己另建 mmap、只对可读块下手、
# 真实归还量进日志）+ **调用门控**（只 useMmap 为真时、且在加载成功之后）+
# **可观测**（RSS 前后 + 文件映射驻留前后对比）+ **FUSE 提示**。
# 判据刻意**不锚"存在 mmap 调用"** —— 那正是第一版错法满足的条件（假绿）。
echo "=== mmap 真省内存守卫（模块 M2）==="; sh tools/run_mmap_release_guard.sh
echo "=== mmap 真省内存守卫自测（对着打桩源码跑）==="; sh tools/run_mmap_release_guard_tests.sh
#   ⚠ ISSUE #274 又是同一族、方向相反的一次：卸载后归还空闲堆段的实现
#     曾经"顺手"从 /proc/self/maps 挑**最大的 rw-p 匿名段**整段 DONTNEED，
#     而 bionic 的 scudo 主堆里活对象与空闲块是混着的 —— 活对象（含
#     chunk 头）被按页清零，下一次 free() 读到全 0 的头就 abort
#     （[stderr] Invalid ... passed to free）。用户看到的正是"加载对话模型闪退"。
#     判据因此钉两件事，且**必须同时**成立：
#       ① 归还函数体内**不得**再有 madvise / MADV_DONTNEED / maps 扫描 / rw-p
#          （不许绕过分配器去猜哪段空闲）；
#       ② release_populated_pages 那条**按路径**认出的文件映射 DONTNEED
#          **必须留着** —— 页是干净的、丢了自己会缺页读回，删它是过度修复。
echo "=== 归还空闲堆段守卫（ISSUE #274）==="; sh tools/run_reclaim_heap_guard.sh
echo "=== 归还空闲堆段守卫自测 ==="; sh tools/run_reclaim_heap_guard_tests.sh
# 模块 M3：mmap 的权重**落点**。M/M2 分别钉住"开关落值"与"预填充页归还"，
# 都绿了仍然可能与真机对不上 —— 因为上游只有一个判据：
# 默认缓存类型能不能把映射地址包成缓冲。这取决于哪些设备进了设备池，
# 而能力位藏在预编译的 .so 里，只能运行时读。守卫钉的就是"读能力位、
# 且结论进日志，且日志在加载之前"。
# 0.9.124 在本模块再加两条同族的：权重重排（repack）。q4_K/q6_K 会被
# 重排成 q4_K_8x8/q6_K_8x8 **另存一份匿名内存**（日志里的 CPU_REPACK），
# 与映射里的原始权重并存 —— 真机 22 层那轮 1137 MiB，随 CPU 层数放大。
# 它的开关 `use_extra_bufts` 本仓库此前从未显式设过（同 load_mode 的病），
# 所以守卫要求它**显式落值**、且档位在加载**前**进日志（否则加载后
# 多出来的那份拷贝认不出是什么）。默认值不变。
# 0.9.125 又改了一处：**判定通道**。上一版把档位交给系统属性
# lm_extra_bufts，真机 root + setprop 之后日志仍是"库默认"、
# CPU_REPACK 一分没少 —— 而属性链失效（没设过 / 读回空 / 被 sepolicy
# 拦掉 / ROM 无 getprop / App 域不许 fork）时**与"没设过"同形**，白跑一轮。
# 现在改走**仓库自己的设置**（设置页 repack 项 → nativeSetRepack，
# -1 = 没设过），属性降为覆盖；三级来源与原始读数进同一条日志。
# 守卫同时钉"推理期要有 RSS 读数"：此前唯一的 RSS 探针在 loadModel 里，
# 所有读数都是加载完成态，"用起来之后多少"从来没人量到过。
# 0.9.127 再收三处同形的病：
#   ① **命名**：JNI 入口与库内部字段同名（nativeSetExtraBufts / use_extra_bufts），
#      用户对着日志找不着设置项。主名改 nativeSetRepack，旧名留作 ABI 别名
#      （跨版本覆盖安装 .so 与 APK 错配时的兜底，命中即有日志）。
#   ② **判定 ≠ 落值**：上一版只有加载**前**的判定行。"参数跨 JNI 有没有到 /
#      有没有被覆盖 / 库有没有认"这几步里任何一步断开，都与"设置页没写进去"同形。
#      新增加载**后**的 `[repack结果]`：落给库的值 + 加载增量 RSS。
#   ③ **UI 看不见**：上一版是留空即默认的输入框，"没设过"在界面上不可见、
#      也没有"回到默认"的入口。改成三档单选（没设过 / 关 / 开）。
# 守卫为此加 4 条：落值记账、结果行在加载**后**、增量由采样算出（写死即红）、
# 旧名别名仍在且与新名写同一个变量。
# 0.9.130 用户报「修复日志显示错误」：`[repack结果] 落给库 use_extra_bufts=-1`
# 恒为哨兵值 —— 档位其实到了库（CPU_REPACK 已消失），是**报落值那行把自己的
# 读数冲掉了**：`log_extra_bufts_diag()` 第一句 `g_extra_bufts_applied = -1;`
# （当重置用）发生在真正落值之后，加载后再读恒为 -1。这是"判定/落值/自证挤在
# 同一个变量上"的又一次现形。重置挪到**落值之前**，判定日志函数体不再碰这一位。
# 守卫改钉**归属与顺序**（不锚"存在某次赋值"——那正是被冲掉的写法）：哨兵重置
# 若有，必须早于 `mp.use_extra_bufts =` 赋值；diag 内不得出现落值位；结果行必须
# 读出落值位。38 → 41 条，自测 23 → 26。
echo "=== mmap 权重落点守卫（模块 M3）==="; sh tools/run_mmap_device_guard.sh
echo "=== mmap 权重落点守卫自测（对着打桩源码跑）==="; sh tools/run_mmap_device_guard_tests.sh
# 模块 M4（*反转*）：mmap 与 GPU/NPU **不互斥**，设备池显式"全池参与"。
# 0.9.121 曾把 mmap=1 的池钉到 CPU 唯一一台，那是**过度修复**：
# 上游 `load_tensors()` 的分组是**按层**的（`get_layer_buft_list`），
# `n_gpu_layers=0` 时 OpenCL 组压根不存在；`0<k<n_layer` 时天然两组 ——
# CPU 组走映射、OpenCL 组进 VRAM（后者本该如此）。钉池的代价是
# **用户一要 mmap 就强制失去全部加速**，白付。
# 本模块把池改回全池并**显式写 nullptr**（库默认恰好也是 NULL，所以
# "不写"与"写"行为等价、语义不等价 —— 同 da30046 那条"开关只落一半"）。
# 守卫钉的是"值是不是 nullptr、赋值在不在 useMmap 的分支里"
# （旧假绿是"没真改 mp.devices"，新假绿是"改了但值/归属不对"）。
echo "=== mmap 设备池守卫（模块 M4）==="; sh tools/run_mmap_pin_guard.sh
echo "=== mmap 设备池守卫自测（对着打桩源码跑）==="; sh tools/run_mmap_pin_guard_tests.sh
# 模块 M5：**设置页控件被撑成一大片空白**。
# 0.9.127 装机后用户报「新的 apk 安装后，repack 没法调节，是一大片空白」。
# repack 那三档单选是 `RadioGroup.VERTICAL`，子按钮却拿 `g.addView(rb, lp(0, 1))`
# —— 而 `lp(w, weight)` 是给**横向行**写的（宽 0dp + weight）。weight 分的是
# 父容器**主轴**的剩余空间：横向分宽、纵向分**高**。于是三个按钮各被拉成整屏
# 高的空块，视觉上就是三片空白。同一文件上一层的 NPU 配额组用的是
# `LinearLayout.LayoutParams(-1, -2)`，**正确样板就在眼前**，这一处抄漏了。
# 这一族的假绿特别多：「单选组存在」「有 addView 调用」「文件里出现过
# MATCH_PARENT」全都恒真 —— 问题恰恰在那句调用的参数上。所以判据按
# 「父容器方向 × 子项 LayoutParams」对账，并扫全文件同类误用；
# 扫描逻辑抽成 tools/repack_lp/scan_vertical_lp.py，另配纯 python3 单测
# （含"字符串里的 // 不得吃掉后续代码"那条回归 —— 判据第一版正是这样假绿的）。
echo "=== repack 布局守卫（模块 M5）==="; sh tools/run_repack_lp_guard.sh
echo "=== repack 布局守卫自测（对着打桩源码跑）==="; sh tools/run_repack_lp_guard_tests.sh
echo "=== repack 布局扫描器单测（纯 python3）==="; python3 tools/repack_lp/scan_vertical_lp_test.py
# 模块 M6：**档位通道断了，而两端各自都能自证**。
# 0.9.125~0.9.127 装机后用户报「有设置项、也选了『关』，日志却仍写
# `来源：库默认（本仓库未设过）`」。根因是**两个键**：设置页把档位写进
# `engine_params` 的 `"repack"` 一格（还原也从同一格读，所以 UI 上显示
# 「已选中」），而加载时交给 native 的值来自 `ModelStore.extraBufts()`，
# 它读的是**另一个 prefs 键** `use_extra_bufts` —— 而全仓没有任何一处写它
# （`setExtraBufts()` 定义出来就从没被调用）。于是恒为 null → 恒传 -1
# → 日志永远"未设过"，与用户装哪版、勾哪档无关。
# 这一族的假绿：「存在 extraBufts()」恒真（它一直在，就是它读错了键）、
# 「存在 nativeSetRepack」恒真（native 侧本来就没坏）。所以判据**横向对账**
# ——把"设置页写的键"与"extraBufts 读的键"比出相等，并钉"没有第二个键"、
# "读到的值真的进了下发链"。反向桩逐条打上面每种坏形态（含 0.9.127 原样）。
echo "=== repack 通道守卫（模块 M6）==="; sh tools/run_repack_channel_guard.sh
echo "=== repack 通道守卫自测（对着打桩源码跑）==="; sh tools/run_repack_channel_guard_tests.sh
# 探针开关判据（显式关闭）与自举缓冲（只记不写）的**宿主单测**：
# 直接 include 仓库里的 probe_flag.h（真机编进去的同一份），不是复刻品。
# 为什么必须真跑而不是只静态断言：这两条都是"错了不崩、不报错"的形态 ——
# 开关读错 → 用户关不掉；交付写错 → [boot] 永远不出现在文件里。
echo "=== 探针开关/自举缓冲单测（probe_flag.h）==="; sh tools/run_probe_flag_tests.sh
# GGUF 预读前的文件头判据（Issue #238）：`nativeProbeGguf` 会把**整份**
# GGUF 元数据（含 151936 条词表字符串）交给库去 malloc，而它跑在
# `Thread { ... }.start()` 的裸 Java 线程上 —— native 段错误没有任何
# Java 层 try/catch 接得住。真机 tombstone_03 正是
# 崩在这一轮 malloc 风暴里（arena_run_heap_remove+888，fault addr=0x8，
# 寄存器里 151936 = Qwen3 词表出现两次）。
#   · 源码守卫钉**可达性**：守卫调用真的在函数体里、出现在解析之前、
#     失败时提前 return、且没有被 `false &&` 这类短路常量废掉 ——
#     "函数写好了但没人调"是这类修复最常见的失效形态（本仓库 mmap 那轮
#     就是"正确实现被提前 return 变死代码"，所有锚存在性判据全绿）。
#   · 宿主单测跑的是**真机编进去的同一份** gguf_precheck.h，不是复刻品。
echo "=== GGUF 预读文件头守卫（Issue #238）==="; sh tools/run_gguf_precheck_guard.sh
# 上面那份守卫的**双向自测**：它带一条负向断言（`false && !gguf_header_precheck`
# 绝不该算通过），而负向断言"能不能被打脸"只有打桩自测能证明 ——
# 判据写得再宽，在"当前树里 pattern 零命中"时也照样绿。
# `tools/run_assertion_anchor_guard.py` 的判据②专门查这件事：带负向断言的守卫
# 必须有一份自测。纯 sh + python3，不依赖编译器。
echo "=== GGUF 预读文件头守卫自测（对着打桩源码跑）==="; sh tools/run_gguf_precheck_guard_tests.sh
echo "=== GGUF 预读文件头判据单测（gguf_precheck.h）==="; sh tools/run_gguf_precheck_tests.sh
# 探针开关/自举那一段的**宿主语法检查**：从 llama_jni.cpp 逐字抽出
# （probe_bootstrap / probe_bootstrap_flush / probe_bootstrap_recall /
# nativeProbeInit / probe_getprop），连真头 probe_flag.h 一起编过。
# 静态守卫抓的是"结构关系"，抓不到"签名/字段名写错"；宿主单测跑的是
# probe_flag.h 的纯函数，而这两段**调用方**在宿主上编不过（jni.h/llama.h）。
# 这段恰是本轮改动最密集处，所以给它一条"推 CI 前就红"的通路。
echo "=== 探针开关/自举语法检查（抽取单元）==="; sh tools/run_probe_syntax_tests.sh
# 上面那份语法检查的自测：把 llama_jni.cpp 改回 ccf0557 的**原始字节**
# （probe_rotate_prev 里用从未定义的 `LOGW`），断言它必须红、且失败信息
# 指得出"LOGW was not declared"。
# 为什么必须进 CI：这次真机编译失败正是穿过全套守卫进来的 ——
#   结构守卫锚的是"rename 在 open 之前"这类关系，LOGW 看着像 LOGI 的近亲；
#   桩头另抄了一份 LOGI/LOGE，源码多一个宏这边照样自洽；
#   probe_rotate_prev 又不在抽取单元里 ——"单元之外"等于"没人管"。
# 缺 C++ 工具链时本脚本 skip 而非失败（与 run_probe_syntax_tests.sh 同）。
echo "=== 探针语法检查自测（对着 ccf0557 的打桩源码跑）==="; sh tools/run_probe_syntax_stub_tests.sh
# 崩溃探针「信号 handler 链 + 预读文件身份」的结构守卫（模块 K：K-1 / K-2 / K-3 / K-4）。
#   · K-1 `probe_install_signals()` 被调第二次就无条件覆盖 g_old_* ——
#     而 nativeProbeInit(on=true) 在本进程里不是一次性的（init 失败后用户
#     再点加载 / 服务再起一轮）。第二次装完 g_old_segv 里是**探针自己**，
#     转发链成环 -> `!!! ===== SIGNAL` 一行都留不下，与「根本没崩」同形；
#   · K-2 SA_ONSTACK 设了而全仓库 0 处 sigaltstack —— 它是个 no-op，
#     handler 仍跑在那个**已经爆掉的栈**上（渲染 / PEG 解析的深递归正会这样崩）；
#   · K-3 原 disposition 是 SIG_IGN 时，旧写法把「忽略」升级成 _exit(128+sig)，
#     与文件头「不改变原有行为」直接冲突；
#   · K-4 `probeGguf` 内存缓存只按 path 命中，同一路径被就地替换
#     （重新下载完成 / 续传落盘 / adb push）后旧判定继续写进 `[HTP判定]`，
#     而 `peekGguf` 的持久缓存本来就是 size+mtime 双校验 —— 两处口径分叉。
# 判据钉**结构关系**（清 g_old_* 之前有没有比过闸门 / SA_ONSTACK 是否有
# sigaltstack 兜底 / 命中判据是不是文件身份），"存在性"断言挡不住它们
# —— 旧的 `grep -q 'g_in_handler'` 就恒真于自链状态（正是 K-1）。
echo "=== 探针信号链守卫（模块 K）==="; sh tools/run_probe_signal_guard.sh
# 守卫自身的自测：拿 9 份打桩源码（删闸门 / 闸门顺序写反 / 退回只有 SA_ONSTACK /
# 备用栈排在 sa_flags 之后 / SIG_IGN 退回 _exit / 删递归保护 _exit /
# probeGguf 退回只按路径 / peekGguf 判据分叉）跑上面那个守卫，断言
# "该红的红、该绿的绿、且失败时点得出是哪一条"。桩全部来自本轮真正的旧写法。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 探针信号链守卫自测（对着打桩源码跑）==="; sh tools/run_probe_signal_guard_tests.sh
# 行为测：从真源码**逐字抽取** probe_signal / probe_install_alt_stack /
# probe_install_signals，用**子进程 + 真信号**验证"装两次后现场还留得下"、
# "SIG_IGN 语义没被升级"、"备用栈真的挂上了"。并对**旧版源码**编第二份
# 做反例对照（旧写法必须在同一组输入上给出不同结果，否则说明判据没打在洞上）。
# 需要宿主 g++，缺则 SKIP。
echo "=== 探针信号链行为测试（无 C++ 工具链则 SKIP）==="
REQUIRED=0 sh tools/run_probe_signal_tests.sh
# K-4 的行为复刻：同一路径被就地替换（size 变 / 只有 mtime 变）后，
# 缓存**必须**失效并重新读 header；文件一字未动时必须命中。
# 稳态下两种判据逐字节相同，只有构造"内容已换"才分得出来。纯 python3。
echo "=== 预读文件身份行为复刻测试（纯 python3）==="
python3 tools/probe_signal/probe_identity_test.py
echo "=== 工具解析单测（PEG load）==="; sh tools/run_parse_parser_tests.sh
# 前缀对齐：钉住"输入有没有按 PEG 根节点要求对齐"这条判定。
# 它是 tool_calls 恒为 0 那类故障的收口条件，产物是**字符串** ——
# 错了不崩不报错，只会静默解析不出工具调用。宿主下用真字节比对，
# 跑的与真机是**同一份** probe_util.h（不靠 review 肉眼保证）。
echo "=== PEG 根节点前缀对齐单测 ==="; sh tools/run_root_literal_probe_tests.sh
# 日志页显示：钉住"数据在、界面看不见"这类故障（0.9.70 真机报「加载后不显示
# 层数与显存」）。纯静态断言，只读源码，不依赖任何工具链。
echo "=== 日志页显示单测 ==="; sh tools/run_log_page_tests.sh
# 日志页**筛选**（Issue #168 第 1 项 + 多选/关键字）：标签多选 + 任意关键字。
# 为什么单靠上面那份不够：它钉的是"数据在、界面看不见"（贴底与渲染窗口），
# 而筛选这件的坏法是**取数口径错了却看不出** —— 在渲染出的 120 行上筛时，
# 某标签的行整段滚出窗口就呈现空白页，用户会去查"为什么没打日志"；
# 筛渗进导出/崩溃现场时，导出的日志会少几段且没有任何提示，
# 而崩溃现场只有一次机会。多选还多两条：空集合被当成"放行 0 行"、
# chip 点击写成换档（单选残留）—— 症状同样是一张看不出是 bug 的空白页。
# 两份都是纯静态，只读源码。
echo "=== 日志页筛选守卫（多选 + 关键字，含自测）==="
sh tools/run_log_filter_guard.sh
sh tools/run_log_filter_guard_tests.sh
# 日志页**正文与说明行必须同一帧自洽**的行为测（第二轮 D-3）。
# 静态守卫查不出"写与不写**在什么条件下**分叉"：过滤态此前是
# `if (filtered) { if (justSwitchedTab) logTv.text = tail }` —— 一个二选一
# （要么实时清掉选择、要么冻结在旧快照），而说明行每次重算，于是
# 稳态一来新行页面就说假话（正文 2 行、说明"命中 3 行"；最坏是
# 命中 0->正 时正文不重写，用户看到"命中 3 行"、下面一行都没有）。
# 这里从**落盘源码**抽出那段分流判据、翻译成 JS 后逐字跑，并对旧写法做反向对照。
echo "=== 日志页正文/说明行自洽行为测 ==="
sh tools/run_log_page_render_tests.sh
# 模型档案（GGUF 元数据）+ `GET /v1/models/{id}`（Issue #168 第 2 项）。
# 为什么必须进 CI：这个特性**不参与生成**，坏了不崩、不报错、不变慢，
# 坏法是**读数错了却看不出来** —— 而它存在的全部意义就是给一个可信的数：
# `llama_model_meta_val_str` 的返回长度可以 ≥ buffer（静默截断，与真值同形）、
# `uint64` 落进 `jlong` 会变成**负数**、两个 `uint64` 返回值写反不报错、
# 单查路由排在列表之后会被前缀吞掉（回一份列表当模型对象，客户端只报
# 「字段缺失」，与「模型不存在」同形）、两处字段名分叉时两边都是合法 JSON。
# 两份都是纯静态，只读源码。
echo "=== 模型档案守卫（含自测）==="
sh tools/run_models_profile_guard.sh
sh tools/run_models_profile_guard_tests.sh
# 协议字段补齐（Issue #168 第 3 项：`max_completion_tokens` / `logit_bias` /
# `logprobs`）。为什么必须进 CI：三个字段的坏法**全是静默的** ——
# HTTP 200、响应体合法、没有异常、没有日志，只是调用方设的值没生效（或只生效
# 一半）：别名与本体各算一份 maxTokens（换个字段名就越界/就不生效）、
# 两个平行数组不同源（偏置整条不挂而请求照 200）、偏置采样器挂在选择器之后
# （正偏置再也拉不回被砍掉的 token）、`logprobs` 字段无条件出现（改掉所有既有
# 客户端的响应形状）、`top_logprobs` 的绑定判据漏掉、`describe()` 写成
# `$p.maxTokens`（日志里那行读数变成 toString —— 本轮自测当场抓到过一次）。
# 守卫两份纯静态；单测要 kotlinc/JDK/android.jar/json.jar，缺失时
# REQUIRED=0 退化为 SKIP。
echo "=== 协议字段守卫（含自测）==="
sh tools/run_protocol_fields_guard.sh
sh tools/run_protocol_fields_guard_tests.sh
REQUIRED=0 sh tools/run_protocol_fields_tests.sh
# finish_reason 的四档（length 是本轮补的那一格）：守卫 + 自测纯静态，
# 单测复用协议字段那份工具链（FinishReason.kt 无 android import）。
# 为什么必须单独有一条：单测只证明 `FinishReason.of` 这个纯函数算得对，
# 证明不了"两个端点都改到了" —— 只改 handleChat 的话 /v1/completions
# 照旧在被截断时报 stop，而单测全绿。守卫按**逐端点各一次**计数钉住它。
echo "=== finish_reason 守卫（含自测）==="
sh tools/run_finish_reason_guard.sh
sh tools/run_finish_reason_guard_tests.sh
echo "=== 并发加载防护单测 ==="; sh tools/run_garbled_output_tests.sh
# new_string_utf8_safe 的返回值分档：空走 NewStringUTF("")、非空走 NewString。
# 为什么必须进 CI：这条判据此前不在流水线里，而它的失效形态正好是
# "Kotlin 侧把非空 String 的返回值用 `?: ""` 当可空处理" —— NDK 编译不报、
# Kotlin 编译才报，本地只跑单测不会碰到；反过来改了头文件的分档也不会有人发现。
# 纯 C++，不依赖工具链。
echo "=== UTF-8 安全解码单测 ==="; sh tools/run_utf8_tests.sh
# stop 序列匹配语义。与 llama_jni.cpp 共用同一份 stop_sequences.h，
# 专钉两类没有异常、没有日志的故障：漏拦（stop 之后的垃圾被吐出去）
# 与多拦（正文被吞一截）。纯 C++，不依赖工具链。
echo "=== stop 序列匹配单测 ==="; sh tools/run_stop_sampler_tests.sh
# stop 匹配语义的**行为对照测**（模块 G 的 G-1）。上面那份单测的用例
# 每个 stop 都恰好从 token 边界开始 —— 那正是"恰好不会出错"的子集：
# 旧实现在"stop 起点落在 token 内部"与"stop 不在结尾"两类上分别是
# 漏判（stop 完全不生效）与假命中（正文被整段吞掉），而单测全绿。
# 这里拿 20 万例随机 stop × 随机分片去撞：真实现必须与独立参考实现
# 逐字节一致，且旧实现必须**确实**不一致（反例对照，防恒真）。
# 需要宿主 g++，缺则 SKIP。
echo "=== stop 匹配行为对照测（无 C++ 工具链则 SKIP）==="
REQUIRED=0 sh tools/run_stop_match_tests.sh
# G-1 的源码级守卫：钉**结构关系**而不是存在性 —— 命中必须是整串的
# 结尾匹配、暂扣必须按**整串后缀**、命中丢弃范围必须精确到末尾那段
# stop、两处判据（relevant / can_continue_fast）必须建立在同一件事上。
# 纯静态断言，不依赖任何工具链。
echo "=== stop 匹配判据守卫（模块 G）==="
sh tools/run_stop_match_guard.sh
# 上面那个守卫的自测：拿 8 份**打桩源码树**（命中退回"只看 next 前几字节"/
# 暂扣退化成"整串前缀"/命中丢整个 token/can_continue_fast 拿 generated 去算/
# bare withheld += next/宿主测逐字符喂（run 与 run_stream 各一条）/
# 删掉"起点在已放行区内"用例/删掉"stop 不在结尾"用例）跑守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自本轮真正的旧写法。纯 sh + python3 标准库。
echo "=== stop 匹配判据守卫自测（对着打桩源码跑）==="
sh tools/run_stop_match_guard_tests.sh
# nativeStep 只允许 accept 一次。`llama_sampler_sample` 内部已经 accept，
# 若外壳再补一次，stop 采样器每步被喂两遍同一 token，generated 记两遍，
# 增量下发就成了「X + X」—— 真机 2026-09-19 抓到 `你好你好！我！我...`，
# **只在带 stop 时出现**（无状态采样器重复 accept 无害，所以不带 stop 全绿）。
# 单测抓不到：状态机本身没错，错在"被喂了两遍"，那是外壳的事。
# 纯静态源码断言，不依赖任何工具链。
echo "=== llama_jni accept 守卫 ==="; sh tools/run_llama_jni_accept_guard.sh
# 取消（abort）必须带**归属**、且不得留下可复用的脏账本（模块 E 的
# E-1 / E-1b / E-2 / E-3 / E-5）。四条全是**不会让别的测试变红**的失效：
#   · E-1  `S.abort` 是裸 bool，跨线程置位/读取是 UB（"取消"有时永远看不见）；
#   · E-1b `nativeAbort` 不带轮次编号 -> 迟到的取消会停掉**下一轮**
#          （Kotlin 侧 RequestCancel 做了归属，最后一跳此前把它丢了）；
#   · E-2  abort 只置标志、不作废账本 -> 被打断那轮的 KV 残留被当有效历史
#          复用 -> 答非所问，但接口 200、不报错；
#   · E-3  prefill 中断把 n_used 写死 0 -> /health 的 ctx_used 撒谎，
#          且与 kv_cache_valid=false 同时出现时会把方向读反；
#   · E-5  kv_valid=false 同时覆盖"从未跑过"与"跑过但作废"，两者同形。
# 判据钉在**结构关系**上（是不是原子 / 清状态前比没比归属 / 分支里有没有
# 作废账本 / 读数有没有被写死），纯静态断言，不依赖工具链。
echo "=== llama_jni abort 归属/账本守卫 ==="; sh tools/run_llama_jni_abort_guard.sh
# 上面那个守卫的自测：拿 11 份**打桩源码树**（退回裸 bool / 退回裸赋值 /
# 丢掉归属判定 / 不作废账本 / 写死 n_used / 顺手清零轮次计数 / stats 退回
# 两元 / Kotlin 不绑归属 / 探测不送 native / /health 少报字段 / token 丢
# nativeEpoch）跑，断言"该红的红、该绿的绿、失败时点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，纯 sh + python3，不依赖工具链。
echo "=== abort 守卫自测（对着打桩源码跑）==="; sh tools/run_llama_jni_abort_guard_tests.sh
# 宿主侧**复刻**测试：把 nativeAbort / startCompletion / step 的控制流
# 逐字复刻成可运行程序，用真状态机去构造这回交错（迟到的取消 / prefill
# 中途取消 / decode 失败 / 生成期取消），每条都报出它抓的是什么。
# 为什么必须是可运行的：这四条的失效**只在交错时出现**，稳态下 100% 正常，
# 对着好实现跑一万次都发现不了。只要 python3 标准库。
echo "=== abort 归属/账本复刻测试 ==="; sh tools/run_llama_jni_abort_tests.sh
# 验收脚本 tools/acceptance_stop.py 的自测：拿四份**打桩实现**（正确 /
# 静默忽略 stop / 暂扣后不放行 / 提示词回显）跑验收脚本，断言"该绿的绿、
# 该红的红"。为什么必须在 CI 里跑：验收脚本本身要装机才能跑，而它的判据
# 全是否定式的（"客户端没看到 stop 串"），恒真也全绿 —— 只对着好实现跑
# 一万次都发现不了。判据还有一个对偶的失效模式是**假红**：把正确实现判成
# 坏的（真机上模型把提示词抄进输出，stop 串在输出里出现两次，命中点用
# `find` 定位就会踩到），所以第 4 份桩专门钉这一条。
# 纯 python3 标准库，不依赖工具链。
echo "=== stop 验收脚本自测（对着打桩实现）==="; sh tools/run_acceptance_stop_tests.sh
# 取消验收脚本 tools/acceptance_cancel.py 的自测：拿五份**打桩实现**跑
# 它，断言"该绿的绿、该红的红"。与上一行同一个理由 —— 取消的判据全是
# 否定式（"没跑满 max_tokens"、"提前收尾了"），一个完全不实现取消的
# 服务端只要模型短答就全绿。五份桩：语义正确（必须全绿）/ `/v1/abort`
# 假成功（必红）/ 探测整个没接上（必红）/ **裸写 0x00 污染 chunked 分帧**
# （`stream-integrity` 必红 —— 那是 0.9.75 真机"输出一小段就断"的复现）/
# 对照组不成立（降级 WARN，**不许报绿**）。纯 python3 标准库，不依赖工具链。
echo "=== 取消验收脚本自测（对着打桩实现）==="; sh tools/run_cancel_acceptance_tests.sh
# 原始 SSE 取证脚本 tools/acceptance_sse.py 的自测：拿七份**打桩
# SSE 实现**跑它，断言"该报有的报有、该报无的报无"。与上面同一个理由 ——
# 它的三问全是存在性判据（有没有裸标签/重复/乱码），恒真恒假都能骗过
# "对着好实现跑一遍"。其中 splitutf8 变体是本轮补的：它造的是**整包
# 解码仍然合法**、只有帧边界切在 UTF-8 字符内部的那种故障 —— 只看
# "整包能不能解码"必然漏掉，所以必须有桩把它钉住。
# 纯 python3 标准库，不依赖工具链。
echo "=== SSE 取证脚本自测（对着打桩实现）==="; sh tools/run_sse_forensics_tests.sh

# --- 宿主侧 Kotlin 单测（存活探测）---
# run_health_tests.sh 需要 kotlinc + JDK + android.jar + json.jar，
# 都放在 /workspace/.omnibot/toolchain。镜像里没有，这里按需拉一份。
# 拉不到就 REQUIRED=0 退化为 SKIP（打印原因），不因此拖垮 AFK 编包 ——
# 与「单测失败必须拦住」区分开：缺工具链是环境问题，不是代码问题。
echo "=== 存活探测单测（无工具链则 SKIP）==="
TC=/workspace/.omnibot/toolchain
mkdir -p "$TC"
if [ ! -x "$TC/kotlinc/bin/kotlinc" ]; then
  curl -fsSL -o /tmp/kotlinc.zip \
    https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip \
    && unzip -q -o /tmp/kotlinc.zip -d "$TC" \
    || echo "!! kotlinc 拉取失败，稍后退化为 SKIP"
fi
if ! ls -d "$TC"/jdk-*/bin/java >/dev/null 2>&1; then
  curl -fsSL -o /tmp/jdk.tgz \
    https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.12%2B7/OpenJDK17U-jdk_x64_linux_hotspot_17.0.12_7.tar.gz \
    && tar -xzf /tmp/jdk.tgz -C "$TC" \
    || echo "!! JDK 拉取失败，稍后退化为 SKIP"
fi
if [ ! -f "$TC/android-35/android.jar" ] && [ -f /opt/android-sdk/platforms/android-35/android.jar ]; then
  mkdir -p "$TC/android-35"
  cp /opt/android-sdk/platforms/android-35/android.jar "$TC/android-35/android.jar"
fi
# json.jar 是**必需**的：org.json 的真实现。android.jar 里的 org.json 是
# 抛 `Stub!` 的空壳，没有 json.jar 时依赖它的套件只能 SKIP（见下面各行的
# REQUIRED=0）—— 而 run_health_tests/stop/cancel/thinking/tool_call/sampling
# 这几份**只编 Kotlin、不碰 native**，正是最容易在本地被跳过的一批。
# 所以这里失败不再"稍后退化为 SKIP"，直接拦下。
#
# 为什么要加镜像回退：2026-09-21 一次真实构建里，repo1.maven.org 对我们
# 返回 HTTP 429（限流），`curl -fsSL` 重试 46 秒后放弃，随后
# run_thinking_tests.sh 因缺 json.jar 直接 exit 2 —— 整轮 APK 构建就这么
# 停在了**下载一个 78KB 的 jar** 上。限流是暂时的，镜像切换是廉价的。
if [ ! -f /tmp/json.jar ]; then
  for u in \
    https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar \
    https://maven.aliyun.com/repository/public/org/json/json/20240303/json-20240303.jar \
    https://mirrors.cloud.tencent.com/nexus/repository/maven-public/org/json/json/20240303/json-20240303.jar; do
    if curl -fsSL --retry 2 --retry-delay 3 -o /tmp/json.jar "$u"; then
      echo "json.jar 已就绪：$u"
      break
    fi
    echo "!! 拉取失败，换下一个源：$u"
  done
fi
[ -f /tmp/json.jar ] || { echo "!! json.jar 全部源均拉取失败 —— 它是必需依赖，不能退化"; exit 1; }
REQUIRED=0 sh tools/run_health_tests.sh
# `/health` 响应体必须是**合法 JSON** 的守卫。为什么必须有：
# `healthJson()` 手工拼串，最容易犯的错是 Kotlin 原始字符串**行尾引号写多一个**
#（`,"""` 写成 `,"""' + '"' +`）—— 拼出来的 JSON 里会凭空多一对 `""`（如 `""ctx_used"`）。
# 后果极隐蔽：服务照常 200、生成接口正常，只有依赖 /health 的探活/监控把它判成
# 「服务器不可达」，使用者看到的是纯粹的误报（Issue #77 真机现场）。
# 守卫按 Kotlin 词法**真的还原**拼接结果再用 json 解析，行尾引号多/少都会被拦下。
# 纯 python3 标准库，不依赖工具链，故不退化。
echo "=== /health JSON 合法性守卫 ==="; python3 tools/run_health_json_guard.py
# 探活报告的**瘦身**守卫：`embed_parity_*` / `embed_retrieve_*` 那十几个字段
# （0.9.223 起为排查「批大小 `g` 能不能按库放行」逐格补上）不再铺在报告正文里
# —— 用户的原话是"信息太乱了，对用户也没实际用处"：它们**看完不知道该做什么**，
# 而日常探活要的是"活没活 / 模型装没装 / 库认的批大小是多少"这种一句话能指导
# 下一步的信息。要分开的**不是"报不报"**、是"报给谁看"：报告正文（用户）只留
# 结论；日志（排查者）完整读数一格不少；`/health` 响应体（监控/旧客户端）
# 一个字段没动。
# 这一族有两个方向相反、而都会被读成"改好了"的坏法：
#   · **铺回报告**（开关默认值改 true，或某个调用点自己传 true）—— 报告又长回去；
#   · **删干净却没人接住**（format 里那几段整段删）—— 报告当然干净了，可那十几个
#     字段从此**没有任何落点**（`/health` 有、日志没有）："算出来没人读 == 没算"。
# 单测只能钉住一半（它喂的是一份手写的 Result），所以判据锚源码结构关系。
# 纯 python3 标准库，不依赖工具链，故不退化。
echo "=== 探活报告瘦身守卫 ==="; sh tools/run_health_probe_hide_guard.sh
# 上面那个守卫的自测：拿 6 份打桩源码（开关默认改 true / 调用点自己传 true /
# 那两段整段删掉 / 日志那一行没接上 / 完整读数漏一格 / 完整读数出口被删）
# 跑它，断言"该红的红、且点得出是哪一条"。纯 sh + python3 标准库。
echo "=== 探活报告瘦身守卫自测（对着打桩源码跑）==="; sh tools/run_health_probe_hide_guard_tests.sh
# **判据自身**的守卫（Issue #174 / 模块甲-1）：每条 `grep` 断言的 pattern
# 必须真的能命中源码 —— 零命中意味着它"从没问过问题"（正向位置恒红后被 `|| true` 抹掉，
# 负向位置恒绿）。它把每个守卫**真跑一遍**（记账 shim 换掉 grep），拿到真实 pattern 再核
# （静态读源码会多算一层转义，误报过 46 条假"零命中"）。
# 本轮它当场抓到 3 处"死支"（`||` 后面的空判据，全靠兜底撑着绿）。
# 纯 python3 标准库 + sh，不依赖工具链。
# 它要对 40 份守卫各真跑一遍 + 每条判据查命中表，优化后本机约 80s
#（此前 ~310s：每条断言各做一次全仓 grep，同一批 pattern 重复上万次）。
# 给它一个总时限（默认 600s，实测数倍余量）：挂住时明确红，而不是拖死这一步。
echo "=== 判据锚定守卫（断言必须能命中）==="; ASSERTION_ANCHOR_TIMEOUT=600 python3 tools/run_assertion_anchor_guard.py
# 全部 HTTP 响应体的**合法性**守卫（Issue #174 / 模块乙-3）。
# /health 那份守卫证明过"手拼 JSON 的行尾引号多一个"是真事故（Issue #77）；
# 这一份把同一套 Kotlin 词法**扩到其余每个响应体**：非流式 chat/completions、
# SSE 两种 chunk 构造器、usageJson / errJson / handleAbort。
# 手拼响应体的坏法全是静默的（编得过、200 照回、客户端 JSON.parse 才抛），
# 且 API 从 3 个长到 7 个后没有任何一处核过"发出去的字节是不是合法 JSON"。
# 纯 python3 标准库，不依赖工具链，故不退化。
echo "=== HTTP 响应体合法性守卫 ==="; python3 tools/run_http_body_guard.py
# stop 请求侧解析/校验（空串、类型、上限）与非流式兜底截断。
# 依赖与上一行相同，故同样按 REQUIRED=0 退化。
echo "=== stop 请求侧单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_stop_tests.sh
# 请求取消：归属判定（取消只打当前轮次）+ 断连写探测的三态判据，
# 另加"判据与两处入口一致"的源码级断言。依赖与上面两行相同，同样退化。
#
# 为什么必须钉：漏判（客户端走了还继续跑）只是白烧 CPU、误判（客户端在
# 却掐了生成）表现为"回答说到一半停住"—— 两者都不抛异常、不打日志，
# 只有断言能钉住。写探测的三态表是**真连一对 socket 跑出来的**
#（"对端正常关闭后写还成不成功、读得到什么"只有真连才知道）。
echo "=== 请求取消单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_cancel_tests.sh
# 思考开关 + 输出分流。为什么必须进 CI：这套判据全是"句子形状"级
# （软开关注没注进去、思考段有没有漏进正文、裸 </think> 会不会漏给
# 客户端），错了不抛异常、不打日志，只表现为客户端 view 里多一层折叠。
# 2026-09-18/19 连续两轮真机反馈都出自这里，而它此前不在 CI 里跑。
echo "=== 思考开关 + 输出分流单测（无工具链则 SKIP）==="
# ⚠ `REQUIRED=0` 这个前缀是**契约**：它写着"缺 json.jar 就 SKIP"，
#   而脚本必须真的求值它（判据④ 会逐脚本核对）。0.9.152 那轮把前缀
#   删掉过一次，于是这一行变成了"缺 jar 就硬 exit 2"——整轮 APK 构建
#   会停在下载一个 78KB 的 jar 上，而 .cnb.yml 上什么都看不出来。
REQUIRED=0 sh tools/run_thinking_tests.sh
# 「思考模式下正文不得被吞」的源码级守卫。为什么单靠上面那套不够：
# 判据本身的**语义**错了（把「后缀里出现过 <think>」当成「思考段已开」），
# 单测只要跟着错误语义写就照样全绿 —— 2026-09-19 真机“思考模式下客户端
# 只收到很短一段”正是这样漏过去的。这里钉的是语义与唯一来源：
#   · 只有「未闭合的 <think>」才算已开；
#   · 判据只能来自渲染侧，宿主不得再扫字符串近似；
#   · 两条渲染路径必须走同一个标注出口。
# 纯静态断言，不依赖任何工具链。
echo "=== 思考路由守卫（正文不得被吞）==="
sh tools/run_think_routing_guard.sh
# 守卫自身的自测：拿五份**打桩源码树**（判据不看闭合标签 / 宿主复活扫串近似 /
# 只改一条路径 / 软开关后忘翻标注 / 只标注一条渲染路径）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 为什么必须有：守卫的判据全是 grep 存在性断言，它有两个根深蒂固的自欺 ——
# pattern 写成到处都有的文本（恒真，改了判据照样绿），或与源码差一个空格
# （恒假，很快就有人把 exit 1 改成 || true）。在 CI 里"跑一遍看它绿"
# 发现不了，只能对着已知该红的桩跑。桩是"就地改真源码 + 改完还原"，
# 不维护副本（副本会随源码漂移，那时测的就是一份没人看的旧代码）。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 思考路由守卫自测（对着打桩源码跑）==="
sh tools/run_think_routing_guard_tests.sh
# 渲染出口的**长度段**与**尾窗口**守卫（模块 F）：
#   · F-1 `new_rendered_prompt` 用 `utf16_len(genSuffix)` 写长度段，却把同一串
#     交给 `new_string_utf8_safe` 发出。两个函数**各自实现**的解码器对非法序列
#     算出不同的 code unit 数（overlong `C0 80`：1 vs 2；CESU-8：1 vs 3）——
#     宿主侧 50 万例随机字节实测 35.7 万处不一致。合法 UTF-8 下恒等，所以真机
#     常见模型（后缀纯 ASCII）全无症状，只有第三方 GGUF 模型自带的模板里含
#     非法字节时才切错位：prompt 头部被当成后缀吃掉（接口 200、不报错）。
#   · F-2 尾窗口判据原先有**三份**各自实现，三处都叫"256 尾窗口"，但 C++ 取
#     256 **字节**、另两处取 256 个 **UTF-16 code unit**（256 字节 ≈ 85 个汉字
#     → 覆盖范围差 3 倍）。用例全是 ASCII 后缀 → 两种窗口覆盖同一段 → 分叉了
#     却全绿（镜像给出的正是"判据没漂"的假信心）。
# 判据锚定**结构关系**：长度段来源 == 发出串来源；判据本体唯一、
# 两侧窗口同值同单位。纯静态断言，不依赖任何工具链。
echo "=== 渲染尾窗口/长度段守卫（模块 F）==="
sh tools/run_render_tail_guard.sh
# 守卫自身的自测：拿 7 份**打桩源码树**（utf16_len 自己数 / 发出侧自写解码 /
# RenderedPrompt 自写窗口 / Kotlin 窗口退回 code unit / 两侧数值分叉 /
# ThinkingControl 复活第三份实现 / 长度段改用字节数）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自本轮真正的旧写法。纯 sh + python3 标准库，不依赖工具链。
echo "=== 渲染尾窗口守卫自测（对着打桩源码跑）==="
sh tools/run_render_tail_guard_tests.sh
# 行为测试：从真源码**逐字抽取** utf16_len / utf8_decode_each /
# classify_think_tail，再跑 20 万例合法 UTF-8 + 50 万例随机字节 +
# 256 个单字节 + 非 ASCII 尾窗口用例，并对 `main` 的旧实现做**反例对照**
# （旧写法必须在这两组输入上暴露问题，否则说明用例没打在洞上）。
# 需要宿主 g++，缺则 SKIP。
echo "=== 渲染出口行为测试（无 C++ 工具链则 SKIP）==="
REQUIRED=0 sh tools/run_render_tail_tests.sh
# grammar 模板同源守卫（结构化输出）：`response_format` 的 GBNF 要问库第二次
# 「用哪个模板」，而这一跳此前在 /v1/completions 传空串（"让库按模型自选"）、
# 在 /v1/chat/completions 传运行时模板 —— **两个端点对同一个问题给了两个答案**。
# 库内自选那份与运行时那份可能不是同一份，模板换了 grammar 的落点就跟着换：
# 约束落错位置（模型先自己吐一遍生成后缀）或压根产不出 GBNF（"降级为无约束采样"），
# 两者都是 **HTTP 200、不报错**，与"没修"逐字相同。
# 判据锚「取值来源」而不是存在性：两个端点**都**有那一行，差别只在等号右边 ——
# "出现过一次正确表达式"在"一个对一个错"时恒真，而那正是本条故障的形态。
# 取值链是真词法检查（括号配对取实参 + 追溯来源 + 去注释防误判），
# 不是 grep。纯静态，不依赖任何工具链。
echo "=== grammar 模板同源守卫（结构化输出）==="
sh tools/run_grammar_scope_guard.sh
# 守卫自身的自测：拿 7 份**打桩源码树**（一个端点退回旧写法 / 两个一起退回 /
# 空串形态 / **注释里**出现旧写法（不得误红）/ completions 不传模板 /
# 契约注释被删 / native 把模板写死空串）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自本轮真正的旧写法。桩④抓出的正是守卫第一版把**注释**也算成实现的误判。
echo "=== grammar 模板同源守卫自测（对着打桩源码跑）==="
sh tools/run_grammar_scope_guard_tests.sh
# 行为复刻：从真源码抽出两个端点的 `chatTemplateOverride` 实参，
# 按 Kotlin 语义折成"最终交给 native 的模板"，断言两侧**一致**且都落在运行时模板上，
# 并对旧写法（传 null = 让库自选）做**反例对照**（旧写法必须红）。
# 纯 python3 标准库，不依赖任何工具链。
echo "=== grammar 模板取值链复刻测试 ==="
sh tools/run_grammar_scope_tests.sh
# 工具调用请求侧解析 + OpenAI 线上响应形状，含 addAss/thinkingOn 必须与
# 渲染侧同源的断言（不同源 -> generation_prompt 形状对不上 -> tool_calls 静默变 0）。
echo "=== 工具调用单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_tool_call_tests.sh
# 采样参数：默认值只有一处、非法值必须报错而不是静默降级。
echo "=== 采样参数单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_sampling_tests.sh
# 纯逻辑层（模块 C）四条**零症状**修复的接线守卫：
#   · C-1 `SessionStore` 的字符数保险丝写成 `sum > 24000 && msgs.size > 2`
#     —— `size > 2` 是先决条件不是保底，消息数正好为 2 时整条循环短路，
#     最需要兜底的会话（一轮问答各上万字）恰好完全失效，
#     症状是 native 静默截断 prompt 前半段 =「答非所问」；
#   · C-2 `ThinkStream.flush()` 把 carry 里剩的字节一律原样发出 ——
#     截断/取消停在 `<thi` 时 content 尾部多出裸 `<think` / `</think`；
#   · C-3 `seed` 自己另写一套类型判据（只拦「字符串且非空」）——
#     bool/array/object 静默变成随机种子，与「非法显式取值报 400」相反；
#   · C-4 `j.has("enable_thinking")` 判"表态"，而 has 对 null 返回 true、
#     optBoolean 又回落到默认 true —— `{"enable_thinking":null}` 被判成开思考。
# 判据锚定**结构关系**（保险丝在只剩 2 条时仍生效 / flush 必须剥标签前缀 /
# seed 必须与其它字段共用同一条读取路径 / null 判据必须与 SamplingParams 对齐），
# 存在性断言挡不住这四条。纯静态断言，不依赖任何工具链。
echo "=== 纯逻辑接线守卫（模块 C）==="
sh tools/run_pure_logic_guard.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（保险丝退回复合 while / 截断只做末条 /
# 裁剪不留痕 / flush 原样下发 / 丢弃量不再暴露 / seed 退回特判判据 /
# Bad 分支静默随机 / null 判据退回只看 has / SamplingParams 的 null=Absent 被破坏）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自本轮真正的四处旧写法，不是编出来的反例。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 纯逻辑守卫自测（对着打桩源码跑）==="
sh tools/run_pure_logic_guard_tests.sh
# C-1 的行为复刻测试：`SessionStore.capChars` 签名带 android Context（只为打日志），
# 宿主下没有可用实现，因此把控制流逐字复刻成纯逻辑跑，并对**旧写法**同时跑一遍
# 做对照（同一组输入下旧写法必须暴露出问题）。依赖 kotlinc/JDK，缺则 SKIP。
echo "=== 纯逻辑行为复刻测试（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_pure_logic_tests.sh
# MiniCPM5 真模板下的 generation_prompt / 解码丢标记两组判据（纯 python3）。
echo "=== generation_prompt 对齐单测（纯 python3）==="
python3 tools/run_generation_prompt_tests.py
# KV 前缀复用（prompt cache）的切分语义。与 llama_jni.cpp 共用同一份
# kv_prefix.h，专钉三类**静默**失效：
#   · 多留一段（切在 token 中间 / 位置对不上）-> 模型读到错位历史，
#     表现为"答非所问""重复上一轮结尾"，但接口 200、不报错；
#   · 少留一段 -> 只是慢，加速效果凭空消失，谁都看不出"本该更快"；
#   · reuse == total -> 采样步在没有 logits 的上下文上取值。
# 纯 C++，不依赖工具链。
# 嵌入（embedding）纯逻辑单测：L2 归一化 / last-token pooling /
# 余弦与 top-k。与 llama_jni.cpp **共用同一份** embed_text.h。
# 失效模式全是**静默**的：不归一化 = 检索按向量长度排序（"总命中同几条"）；
# 零向量入库 = 余弦 0/0 = NaN；池化取错 token = 读到无关上下文；
# 维度对不上 = 点积读越界；同分顺序不稳 = 验收不可复现。
# 都不抛异常、不崩溃、不报错，只有断言能钉住。
echo "=== 嵌入纯逻辑单测 ==="; sh tools/run_embed_text_tests.sh
# 「双常驻（对话 + 嵌入）两份 Session 彼此隔离」的接线守卫。
# 单测钉不住"加载嵌入时有没有顺手拆了对话模型"（症状是"聊到一半模型没了"）、
# "embeddings=true 有没有真的设上"（症状是检索全空而接口 200）、
# "n_ctx 有没有钳到上限"（症状是手机上 ctx create failed）、
# "嵌入有没有与生成共用一把锁"（症状是双常驻白做，只是慢）。
# 四条都不崩、不报错、未必有日志，只能靠结构判据。
echo "=== 双常驻隔离守卫 ==="; sh tools/run_dual_model_guard.sh
# 上面那个守卫的自测：拿 8 份**打桩源码树**（加载嵌入时拆对话 /
# 卸载嵌入改拆 S / 卸载对话连嵌入一起拆 / 不调 llama_set_embeddings /
# n_ctx 不钳上限 / 嵌入共用 genLock / 嵌入 Session 声明消失 /
# 行注释里塞 `/*`）跑，断言"该红的红、点得出是哪一条、还原后全绿"。
# ⚠ 桩⑧ 是本轮真踩到的坑：strip_comments 曾把**行注释里的 `/*`** 当块注释
#   起点，一路吞掉中间真实代码 → 守卫假红（"两份 Session 都在"判失败）。
#   守卫对注释敏感 = 它读的不是实现，正是这条自测要拦的。
echo "=== 双常驻守卫自测（对着打桩源码跑）==="; sh tools/run_dual_model_guard_tests.sh
# 「嵌入模型在 App 里**真的有入口**、且 /health 报得出它」的接线守卫。
# 阶段 0/1 把 native 与 Kotlin 的嵌入能力全写好了，双常驻守卫 21 条全绿
# —— 因为那份守的是"两份 Session 彼此隔离"，而它确实隔离。缺的是**接线**：
# `loadEmbedModel` 调用方为 0，用户按对话模型的步骤加载向量模型 →
# 两个文件装进**同一个槽**（症状"两个模型互相顶掉"）；`/health` 一个嵌入
# 字段都没有 → 无法区分"嵌入没加载（检索必然全空）"与"检索没命中（正常）"。
# 两条都不崩、不报错，只能靠调用/取值关系判据。
echo "=== 嵌入入口守卫 ==="; sh tools/run_embed_entry_guard.sh
# 上面那个守卫的自测：拿 9 份打桩源码树（/health 取对话那份的描述 /
# 嵌入加载走 loadModel / 按钮没接线 / 卸载拆了对话 / 状态行不读引擎真值 /
# native 读错 Session / 两角色共用选中键 / 注释里塞函数名 / 入口被删）
# 跑，断言"该红的红、点得出是哪一条、还原后全绿"。
# ⚠ 桩⑧ 钉一个假绿：判据若锚"文件里出现过 loadEmbedModel"，加一句注释就满足它。
echo "=== 嵌入入口守卫自测 ==="; sh tools/run_embed_entry_guard_tests.sh
# 「嵌入失败可归因」的接线守卫（方案 B + C + A）：输出维度对照、pooling
# 语义交还模型、失败不再静默、**warm-up 与真实路径同形**。用户报的三个现象
# 是同一根因的三个面，而三个面都**不崩、不报错**（接口/界面照常）：
#   ① n_embd != n_embd_out → 跨 buffer 读（含未初始化 NaN）→ "某些条失败且
#      时好时坏"，与文本内容无关（用户看到"改改词序某次成功之后就好了"）；
#   ② 池化取错位置 → BGE 系非 CLS 位置范数下溢成 0 → "条条失败（100% 复现）"；
#   ③ 只 skipped++ → 100 条坏 3 条时只能二分重试，而那 3 条原文就在粘贴框里。
# 三条既不会被双常驻守卫发现（那份守"两份 Session 隔离"），也不会被宿主
# 单测发现（单测只测纯函数、测不到接线）。判据锚结构关系：对照点与读取点的
# 先后、logits 与 output_slot 是否同源、失败是否带上"哪一条"与实值。
# ⚠ 本轮补上第四条同形态的：**warm-up 的 batch 必须与真实编码路径同形**。
#   `embeddings=true` 时那块 output buffer 是未初始化内存，warm-up 的
#   存在就是为了把它写干净 —— 而"写干净"的判据**不是**"跑过一次前向"。
#   早先那版用 BOS 单 token、只要一行输出（推理是"库只要求至少一个"），
#   这条推理每一步都错：CLS（BGE 系）上库的门禁在 `llama_batch_allocr::init`
#   里认 `logits[]` 覆盖了所有 input token 没有 → 直接 `abort()`
#   （release 就是进程级崩溃，连"失败但不影响加载"都打不出来）；
#   NONE/LAST 上库"替我们补"补的是**真实 input**（只有 BOS 一个），
#   "补"完还是只有第 0 行 —— warm-up 想干的事根本没干成，只剩假绿日志。
#   上一版那两条判据（"文件里出现过 warm-up"、"那句日志在不在"）**都是恒真**的，
#   正是本仓库反复记录的那类假绿。
echo "=== 嵌入失败可归因守卫 ==="; sh tools/run_embed_failure_guard.sh
# 上面那个守卫的自测：**39 份**打桩源码树（维度对照被短路 / 对照排在读取之后 /
# logits 硬编码 / 读行用 batch 下标 / 不检查 plan.valid / 失败不报范数 /
# 失败条目整条丢弃 / 有失败仍走 save / 成功向量往前挤 / meta 不记失败下标 /
# 追加失败不带内容 / UI 把部分失败当真失败 / pooling 没存进 Session /
# 注释里塞 /* / LAST(3) 掉了 / LAST 支不分岔 / 换算函数被改名 /
# **warm-up 六桩**（logits 硬编码只要第 0 行 / 不用计划函数自己另写置位 /
# batch 退回字面量 1 / 跳过分支不留日志 / 门槛放到 1 / 不复用库门禁 /
# 不追问写满了没有））跑，断言"该红的红、点得出是哪一条、还原后全绿"。
# ⚠ 本轮真抓到 3 处弱断言：判据只判"函数体里出现过 plan.output_slot"
#   （日志那行也带着它）、"文件里出现过 savePartial"（改 if(false) 后文本还在）、
#   以及 s12 的文案断言 —— 全部改成锚**调用实参 / 分支条件**。
# ⚠ 本轮又抓到 4 处（warm-up 这一族）：判据写成"warmup_plan_usable 之后
#   出现过 jlog"（恒真 —— 后面还有 decode 失败那条 jlog），
#   以及新判据的**文案**里带了旧桩的判据关键词（"整段"/"条件"/"want_logits"），
#   于是旧桩红的是新判据的 PASS 行。前者改成锚**那个 if 分支自己的花括号体**，
#   后者把文案改开。桩值钱就在这种地方：它同时钉住了守卫的**形状**与**措辞**。
echo "=== 嵌入失败可归因守卫自测 ==="; sh tools/run_embed_failure_guard_tests.sh
# 知识库索引（RAG 阶段 1）的纯逻辑单测：分块 / 指纹 / 头布局 / 检索选择。
# 与 llama_jni.cpp **共用同一份** rag_index.h。失效模式全是**静默**的：
#   切在 UTF-8 码点中间 = 那一段永远编码失败（"某几段检索不到"，无日志指向分块）；
#   块间留空洞 = 中间几个字节的正文永远进不了索引（"找不到"与"没有"表现一样）；
#   指纹漏字段 = 换模型/换切块参数后旧索引被当新的用（命中旧向量，排序全乱而 200）；
#   头读错位 = 把指纹当向量读，维度"对得上"，检索永远无意义；
#   overlap >= target / target = 0 = 死循环或永久不前进；
#   空库当"建好了" / 无命中硬塞低分条目 = 污染回答且无法归因。
echo "=== 知识库索引纯逻辑单测 ==="; sh tools/run_rag_index_tests.sh
# 索引持久化（RagIndexStore.kt）的离线单测：编解码往返 / 头字段偏移 /
# 损坏与截断整体拒绝 / 库名净化（防目录穿越）/ 三态不混 / 检索 topK /
# 追加与删除的**算术**（阶段 3：rows 口径、向量逐行前移、行号对齐）。
# ⚠ 阶段 3 新增的一条口径契约：`rows`（按 `\n` 切的段数）与索引里的
#   `count`（条目数）**差恒为 1**（编码以换行收尾）。两边写成相等会让
#   每一份正常索引都被判成"索引落后于正文"，而症状只是"每次打开都在重建"
#   —— 慢的东西没人查，所以这一条单测与守卫各钉一遍。
echo "=== 知识库索引持久化单测 ==="; sh tools/run_rag_index_store_tests.sh
# 索引的**接线**守卫。单测验得了"每一处自己算得对"，验不了
# **三处之间对齐**（rag_index.h / RagIndexStore.kt / LlmEngine.kt）——
# 而对齐一旦破，症状全是不崩不报错的"把指纹当向量读""每次都在重建"
# "整库向量全空而建库照样报成功""换模型后继续用旧索引"。
# 其中头布局与指纹字段集合是**两边各写一遍**的典型：各自的往返测试
# 都自洽，只有"A 写出的字节交给 B 读"才会暴露 —— 守卫调
# tools/rag_index/cross_layout.py 真的拼一遍字节来比。
echo "=== 知识库索引接线守卫 ==="; sh tools/run_rag_index_guard.sh
# 上面那个守卫的自测：拿 12 份**打桩源码树**跑，断言"该红的红、
# 点得出是哪一条、还原后全绿"。桩⑦⑧ 与桩⑩⑪ 是这一族守卫
# 最容易自欺的两处：`_fnbody` 会抹掉字符串字面量（判据若锚字面量就恒假），
# 而"提取失效 -> 空集 -> 两个空集相等"会让交叉校验悄悄变绿。
echo "=== 索引守卫自测（对着打桩源码跑）==="; sh tools/run_rag_index_guard_tests.sh
# 分块规则的**差分测试**：C++ `chunk_text` 与 Kotlin `LlmEngine.chunk`
# 在**同一份语料**上逐字节/逐字符比输出。前面那些守卫只能判**结构**
# （"两边都有那张表"），判不了**行为**（"同一份输入切出同一份块"）——
# 而行为分家是静默的：建库按 A 规则切、追加按 B 规则切，同一个索引里
# 混着两套边界，指纹里只有 t/o，看不见"从哪儿切"。
# 三轮语料：ASCII 逐字节 / CJK 与 emoji 比块字符数。**三轮都要**——
# 少了 CJK 那一轮，"Kotlin 漏掉「、」"这类差异完全不可见（本轮真踩到）。
echo "=== 分块规则差分（C++ vs Kotlin，真源码抽出）==="; sh tools/rag_index/chunk_rule/run_diff.sh
# 硬截轮**不重叠**（软切轮保留重叠）这一档的专门判据：`run_diff.sh`
# 只验"C++ 与 Kotlin 切得一样"，判不了"切得对不对"。而这一档两份实现
# 会**一起错**：硬截轮结束时回退 overlap，起点落在上一轮已消化过的区间
# 内部 —— 那段正文被切两遍（相邻块公共片段 ≥ overlap，检索互相抢 top-k），
# 硬截计数被灌水。判据锚**行为**（真源码抽出编译后跑），是**合规**判据。
echo "=== 硬截轮不重叠（软切轮保留重叠）==="; sh tools/rag_index/chunk_rule/run_space_cases.sh
# RAG 注入（阶段 2）的纯逻辑单测：命中选择 / 片段渲染 / system 段拼接 /
# 开关判据 / 剥掉上次注入。失效形态全是不崩不报错的：
#   开关关着却仍注入 = 用户没要求却被污染上下文；
#   低分条目硬塞进去 = 用无关段落污染回答（比"没命中"更坏）；
#   空条目却渲染出表头 = "非空即已注入"的判据恒真；
#   全部低于阈值时退回最像的几条 = 「没有命中」被伪装成「命中了」。
echo "=== RAG 注入纯逻辑单测 ==="; sh tools/run_rag_inject_tests.sh
# RAG 注入的接线守卫：格式两边一致（C++ vs Kotlin 逐字段比）+
# **真的接到了拼 prompt 处**（preparedMessages）+ 只落 system 段 +
# 开关判据只有一处 + 索引连正文一起落盘。
# 这些缺口都是阶段 0 那个"调用方为 0"的复现形态：
# native / 存储层全绿，守卫全绿，而用户点下去什么都不会发生。
echo "=== RAG 注入接线守卫 ==="; sh tools/run_rag_inject_guard.sh
# 上面那个守卫的自测：拿 12 份**打桩源码树**跑，断言"该红的红、
# 点得出是哪一条、还原后全绿"。桩④⑤ 钉住"接线断了要红"
# （注入被摘掉 / 注入污染 user 段），桩①③ 钉住交叉校验不是恒真。
# ⚠ 本轮真踩到的两个坑也写进了这一份：`kotlin_fn_body` 返回 (body, line)
#   元组（拿元组做 in 判断恒假）；`_fnbody` 会抹掉字符串字面量
#   （判据锚 `role == "system"` 时必须读原文区间）。
echo "=== RAG 注入守卫自测（对着打桩源码跑）==="; sh tools/run_rag_inject_guard_tests.sh
# 知识库**正文的编辑闭环**守卫（阶段 2 的 1+2 条）。阶段 1 落盘、0.9.152 装
# 嵌入、0.9.153 接检索，三块都在而**编辑器是单向的**：粘贴框只在建库时被读，
# 从来不被写。于是同一个根因长出三个现象，全都不崩不报错：
#   点库只改库名（框里还是上一个库的文本）/ 启动不回填（重进后空白）/
#   没有"保存"（每次加一条都要重粘全量）。
# 修它要三处对齐（存储层 / 引擎 / UI），而单测验不了"它们接上了没有"。
echo "=== 知识库编辑闭环守卫 ==="; sh tools/run_rag_edit_guard.sh
# 上面那个守卫的自测：拿 8 份**打桩源码树**（点库不回填 / 启动不回填 /
# 保存走重建 / 切库不确认 / 回填被当成用户编辑 / 保存按输入框定目标 /
# 回填不记代际 / 列表不标需重建）跑，断言"该红的红、点得出是哪一条、
# 还原后全绿"。
# ⚠ 本轮真抓到 3 处**恒真**判据（桩⑥⑦⑧）：只写"出现了某标识符"，
#   而写错的那一支里标识符还在（`ragDocOwner` 在比对行 / `ragDocGen`
#   在标识符处 / "需重建"字面量在恒假分支里）。已全部改成锚**表达式**。
echo "=== 知识库编辑闭环守卫自测 ==="; sh tools/run_rag_edit_guard_tests.sh
# 知识库**条目编辑**守卫（阶段 3：追加一条 / 删除单条）。阶段 2 把"编辑"
# 逼成了"整库重建"（想加一条要重粘全量、重算 100 条向量 = 5~30 秒），
# 这一轮拆成增量。增量的失效形态**全是不崩不报错的错位**：
#   追加时"没算出向量的条目占正文一行、向量不占" -> 索引整体错位一行，
#     检索**给出结果**、只是内容全错（最坏的一种）；
#   删除时只删一个文件（或两个文件删的不是同一行）-> 后面每行前移一位；
#   索引 count 与正文行数不再相等 -> 刚加的那条永远搜不到（与"没命中"一样）；
#   写完不重算指纹（正文 hash / 行数都在里面）-> 索引当场失效，增量白做；
#   UI 用"第几个视图"当行号 -> 有坏行被跳过时删掉的是隔壁那一行。
# 这些缺口只存在于**接线处**，单测验不了，只能锚结构关系。
# 其中"rows（段数）与 count（条目数）差 1"是本轮新增的一条**口径契约**：
# 两边写成相等会让每一份正常索引都被判成"落后于正文"（每次都重建）。
echo "=== 知识库条目编辑守卫 ==="; sh tools/run_rag_entry_guard.sh
# 上面那个守卫的自测：拿 13 份**打桩源码树**（追加不编码 / 算了向量不交给
# Entry / rows 用累加 / 指纹不带行数 / 没算出的向量也进向量区 /
# 先写正文后写索引 / 不校验行数一致 / 去掉 rows->count 口径换算 /
# 搬向量不跳过被删行 / 去掉搬运数兜底 / 删除按视图下标 /
# 追加覆盖草稿 / 状态行不报条目数）跑，断言"该红的红、点得出是哪一条、
# 还原后全绿"。
# ⚠ 本轮真抓到 1 处**恒假**判据（桩⑫）：`kotlin_fn_body` 会把字符串
#   字面量抹成 `""`，于是"算了条目数"与"算出来放进 parts"在它眼里
#   完全一样 —— 已改成读原文区间 + 锚 `parts.add(...textsCount`。
echo "=== 知识库条目编辑守卫自测 ==="; sh tools/run_rag_entry_guard_tests.sh
# 远端知识库（RAG 阶段 3）+ `/v1/embeddings` 的**源码级接线**守卫。
# 这一批的失效形态全是不崩不报错的，其中两条**会主动伤害用户**：
#   远端开关默认值读成 true -> 升级后所有人被静默注入知识库内容；
#   远端库名与 App 内共用键 -> 在 App 里切库会静默改掉远端正在用的库。
# 其余是"接线没接上，而一切正常"：注入没接到 HTTP 路径上、协议层字段
# 漏在某条响应路径上、开关判据写了两处、`/v1/embeddings` 被绑上 RAG 开关
#（两件不相干的事互相绑架）。判据锚结构关系，纯静态，不依赖工具链。
echo "=== 远端知识库接线守卫 ==="; sh tools/run_rag_remote_guard.sh
# 上面那个守卫的自测：拿 12 份**打桩源码树**（远端默认改成 true /
# App 内默认被带偏 / 两个库名共用键 / remoteKb 跟随 currentKb /
# HTTP 不读远端开关 / 流式收尾帧漏 local_rag / embeddings 被绑开关 /
# embeddings 不判嵌入模型 / responseField 恒留字段 / ragField 拼两份 /
# **把 HttpApi 这个 object 当 Context 传**（0.9.169 补：这是编译阻断，
# 靠 CI 才知道的成本是一轮编译）/ 取值口判据写了两处）
# 跑，断言"该红的红、点得出是哪一条、还原后全绿"。
# ⚠ 本轮真抓到 3 处**恒真**判据（桩⑤⑩ 与一处同形）：只写"函数体里
#   出现过某标识符"，而写错的那一支里标识符还在（`RagInject.remoteEnabled`
#   在紧跟的 else-if 分支里 / `val ragField = ` 被 `val ragField2 = ` 绕过）。
#   已全部改成锚**调用实参**与**取值调用次数**。
echo "=== 远端知识库守卫自测（对着打桩源码跑）==="; sh tools/run_rag_remote_guard_tests.sh
# 远端路径 + `/v1/embeddings` 的**纯逻辑单测**（EmbeddingsApi /
# RagProtocol）。钉的是 index 错位、token 数组被"尽力解释"、
# 数值被 %f 压成 0、没注入却留协议字段这些**接口 200 的错**。
# 缺工具链时 REQUIRED=0 退化（CI 是 AFK 编包场景）。
REQUIRED=0 sh tools/run_rag_remote_tests.sh
# `/v1/embeddings` 三个协议参数（dimensions 明确 400 / encoding_format=base64
# / token id 数组）的**源码级接线**守卫。这三条的失效形态全是"不崩、不报错、
# 接口还 200"：dimensions 静默忽略 -> 调用方按错维度截；base64 字节序写反
# 或逐维编码 -> 解码成功但数值离谱/长度不对；id 数组被静默重编码 -> 向量与
# 调用方以为的文本无关。单测钉"算得对不对"，钉不住"接线有没有接上"
#（把 handleEmbeddings 里那句 detokenize 删掉，单测照样全绿）。
echo "=== 嵌入协议参数接线守卫 ==="; sh tools/run_embed_params_guard.sh
# 上面那个守卫的自测：拿 10 份**打桩源码树**（dimensions 改回静默忽略 /
# base64 分支被摘 / 字节序写成大端 / 逐维编码 / base64 项回数字数组 /
# HTTP 不再 detokenize / itemJson 漏带 encoding / 越界 id 被跳过 /
# detokenize 读对话模型的 vocab / native 导出被改名）跑，
# 断言"该红的红、点得出是哪一条、还原后全绿"。
# ⚠ 本轮真踩到两处**弱断言**并已修：① 字节序那条只锚"bytes[i*4] =、
#   ushr 8、ushr 24 在不在"—— 把低位与高位对调后这三样全在，判据照样绿；
#   已改成锚"哪一段移多少位"。② native 导出那条用子串包含，
#   改名成 `..._nativeDetokenize_GONE` 后照样命中；已改成锚
#   "导出符号 + 紧跟的 `(`"。
echo "=== 嵌入协议参数守卫自测（对着打桩源码跑）==="; sh tools/run_embed_params_guard_tests.sh
echo "=== KV 前缀复用单测 ==="; sh tools/run_kv_cache_tests.sh
# KV 前缀复用的**接线**守卫。单测只钉"切分算得对不对"，钉不住
# "每条改动 KV 的路径都作了废账本""生成出来的 token 记了账"
# "账本只在 prefill 成功后算有效" —— 这三件漏一处的后果都是
# **不崩、不报错、HTTP 200** 的错位历史（答非所问），
# 只能靠源码级断言钉住。纯静态断言，不依赖任何工具链。
echo "=== KV 前缀复用接线守卫 ==="; sh tools/run_kv_cache_guard.sh
# 守卫自身的自测：拿 7 份**打桩源码树**（换模型不作废 / 生成不记账 /
# prefill 失败还置有效 / 整段当已缓存 / /health 少报字段 /
# 缓存分支让请求失败 / 去掉复用门槛）跑上面那个守卫，断言
# "该红的红、该绿的绿、且失败时点得出是哪一条"。
# 为什么必须有：守卫的判据全是 grep 存在性断言，它有两个根深蒂固的自欺 ——
# pattern 只写到标识符（恒真，把判据改坏照样绿）或与源码差一个字符
#（恒假，很快就有人把 exit 1 改成 || true）。本轮**真抓到 2 处**这种弱断言
#（桩⑤/桩⑦），已改成"字段名 + 取值表达式"两段式。
# 桩是"就地改真源码 + 改完还原"，不维护副本。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== KV 复用守卫自测（对着打桩源码跑）==="; sh tools/run_kv_cache_guard_tests.sh
# 「生成轮次之间不得串味」：采样器状态每一轮**从哪来**。
# `nativeStartCompletion` 的注释原文声称「本轮开始前的采样器复位（stop
# 状态机等）」，而本文件**从未**调用过 `llama_sampler_reset` —— 注释与实现
# 分叉；`stop_sampler_reset`（iface 的 .reset）也**没有任何调用点**，
# 那段 `c->emitted = 0` 在真机路径上从未执行过。
# 当前**不是** bug，因为不变量由另一条路撑着：`nativeNewSampler` 每轮先
# `llama_sampler_free(S.smpl)` 再 `chain_init`，且 newSampler 恒在
# startCompletion 之前被调 —— 于是 stop_match_ctx 每轮都是全新的。
# 所以判据钉的是**这一对关系**：「链整条重建」与「reset 无调用点/已标注」
# 必须同时成立；只改其中一个（删掉 reset，或补一次 reset 而不看链是否重建）
# 都可能把一条"靠结构成立"的性质悄悄改坏。
# 纯静态断言，不依赖任何工具链。
echo "=== 生成轮次状态守卫 ==="; sh tools/run_gen_state_guard.sh
# 上面那个守卫的自测：拿 4 份**打桩源码树**（链不再重建 / 把"采样器复位"
# 这句谎话写回首行 / reset 的两条依据同时抽掉 / iface 的 .reset 接线被拿掉）
# 跑，断言"该红的红、点得出是哪一条"，还原后恢复全绿。
# 为什么必须有：本守卫里有一条**负向**断言（"首行不得声称采样器复位"），
# 负向断言天生恒绿 —— pattern 写错就永远成立、零信息量，而 CI 照样 PASS。
# 桩② 就是真的把那句谎话写回去。桩③ 则证明"reset 无依据"这一支真的可达。
# 桩是"就地改真源码 + 改完还原"，纯 sh + python3，不依赖工具链。
echo "=== 生成轮次状态守卫自测（对着打桩源码跑）==="; sh tools/run_gen_state_guard_tests.sh
# parallelN 说明文案的存在性守卫。它防的不是 bug，是"文案被顺手删掉"：
# 该参数只把 n_seq_max 传给库、**不产生并发能力**（服务端单并发，第二个
# 请求直接 503）。这句说明补在设置页输入框旁边与 README 里，
# 而文案不影响编译、不影响任何测试、删了没有任何现象 —— 只能靠断言钉住。
# 纯静态断言，不依赖工具链。
echo "=== parallelN 说明文案守卫 ==="; sh tools/run_parallel_hint_tests.sh
# CORS（白名单式）+ OPTIONS 204 预检 + GET / 自带测试页的**接线**守卫。
# 单测（下一行）只证明 CorsPolicy 这个纯函数算得对，证明不了它被真的用上：
#   · 21 处 writeJson 只要有一处绕过统一入口自己拼头，症状就是
#     "预检过了、某一条端点被拦" —— 在浏览器里与"服务随机抽风"完全同形；
#   · OPTIONS 必须在真实路由**之前**、且不参与任何业务校验
#     （将来加鉴权时预检必须豁免，拦了等于 CORS 白做）；
#   · **绝不允许**出现 `Access-Control-Allow-Origin: *`。本接口无鉴权，
#     一旦回通配，攻击面就从"局域网内直连"变成"你打开的任何一个网页"。
# 纯静态断言，不依赖工具链。
echo "=== CORS 接线守卫 ==="; sh tools/run_cors_guard.sh
# 守卫自身的自测：拿 10 份**打桩源码树**（白名单恒放行 / 预检回通配 /
# 开 Allow-Credentials / 删掉 OPTIONS / 预检挪到业务路由之后 /
# 预检里加业务校验 / 一条 writeJson 漏传 corsOrigin / 删掉 GET / /
# 白名单不再从设置注入 / README 删掉不通配的告警）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 为什么必须有：守卫的判据全是 grep 存在性断言，它有两个根深蒂固的自欺 ——
# pattern 写成到处都有的文本（恒真），或与源码差一个字符（恒假后被 `|| true` 静音）。
# 本轮**真抓到 1 处**这种弱断言（桩⑦：writeJson 的"看后 8 行"窗口会吃进
# 邻居调用里的 corsOrigin），已改成按括号配对截出调用自身参数。
# 桩是"就地改真源码 + 改完还原"，不维护副本。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== CORS 接线守卫自测（对着打桩源码跑）==="; sh tools/run_cors_guard_tests.sh
# CORS 判据本体（CorsPolicy 纯函数）+ 自带测试页 HTML 的离线单测。
# 依赖 kotlinc/JDK/android.jar/json.jar，缺失时 REQUIRED=0 退化为 SKIP。
echo "=== CORS 单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_cors_tests.sh
# CORS 的**响应形状**校验（拼出来的真实字节）：预检 204 且 CORS 头必须
# 落在头块内、空白名单下仍是 204、`Content-Length` 必须是**字节数**
# （页子里有中文，写成字符数不报错、只把页面截断）。
# 为什么必须进 CI：这一份此前**一直没被接进来**，于是它在 main 上
# 编都编不过（编译清单漏了页面本体 WebChatPage.kt 与它的
# SamplingParams.kt 依赖，且运行期 classpath 漏了 json.jar →
# android.jar 里抛 `Stub!` 的空壳 org.json 被当成真实现），
# 而 README/HTP-STATUS 却写着"16 条全通过" —— 一份不跑的测试
# 与"没有这份测试"等价，且比没有更坏（它让文档里的数字变成假证据）。
echo "=== CORS 响应形状单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_cors_live_tests.sh
# 自带测试页（折叠设置区 + 多轮会话）的页面结构单测。为什么必须进 CI：
# 页面是纯字符串产物，**完全不参与编译** —— 少一个 id、字段名写歪、
# 历史没带上，全都是"编译过、HTTP 200、只有打开浏览器的人看得出不对"。
# 而且失效形态都长得像"服务端有问题"（字段名漂移 = 设置没生效），
# 排查方向一开始就是错的。依赖与上一行相同，同样按 REQUIRED=0 退化。
echo "=== 自带测试页单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_web_chat_tests.sh
# 自带测试页的**接线**守卫 + 它自己的自测。页面失效全是静默的
# （字段名漂移 = 参数不生效、多轮不带历史 = 上下文错乱），
# 单测钉住"页面长什么样"，这里钉"接线没断、字段名与服务端逐字一致"。
# 纯静态断言，不依赖任何工具链。
echo "=== 自带测试页接线守卫 ==="; sh tools/run_web_chat_guard.sh
# 「生成中删除」的真实行为测（第二轮 D-2）。为什么不能只靠静态守卫：
# 这一族的失效**没有症状** —— 气泡真的从页面消失了，用户以为删成功了，
# 而被删的回答仍留在 history 里、之后每轮都喂给模型（还与文件头自己写的
# 「同时删气泡与 history」正好相反）。静态看"两处都写了 splice"永远是绿的。
# 这里从落盘源码抽出 removeAt 原样执行，覆盖三个场景 + 旧写法反向对照。
echo "=== 网页版生成中删除行为测 ==="; sh tools/run_web_chat_remove_behavior_tests.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（绕过 pageHtml 入口 / 设置区不再折叠 /
# 字段名漂移 / 多轮不带历史 / 失败也入历史 / 新对话不清气泡 /
# 默认值各写一份 / README 删掉设置区说明 / 思考链退化成两态）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 本轮**真抓到 2 处**弱断言（桩⑧/桩⑨：pattern 写成别处也有的词、
# 只断言"有个 select"而不断言三态），已改成具体措辞 + 三态齐备。
echo "=== 自带测试页守卫自测（对着打桩源码跑）==="; sh tools/run_web_chat_guard_tests.sh
# 宿主单测的**源码**必须真的是 Kotlin（Issue #110 第二轮 · D-1）。
# 为什么必须有一份**不依赖工具链**的判据：`WebChatPageTest.kt` 从 89ad274
# 起就编不过（把页面内嵌 JS 的 `html.match(/…/g)` 抄进了 Kotlin），
# 而 CI 的 runner 恰好没有 kotlinc -> 那一行 REQUIRED=0 走 SKIP ->
# "编译不过"与"环境缺件"在汇总里长得一模一样，坏了一整天没人发现。
# 工具链正是那个会缺失的东西，所以这条判据不能也建在它上面：
# 纯源码级（剥注释与字符串后查 JS 专有语法 + 括号配平），零依赖。
echo "=== Kotlin 单测源码守卫（不依赖工具链）==="; python3 tools/run_kt_test_source_guard.py
# 它的自测：拿 6 份**已知该红**的桩跑（真实的 match bug / 箭头函数 /
# document. / 括号不配平 / 清单被清空 / 以及 3 个合法 Kotlin 写法必须保持绿，
# 防"天天误红 -> 被人 || true 掉"）。
echo "=== Kotlin 单测源码守卫自测 ==="; sh tools/run_kt_test_source_guard_tests.sh
# 宿主单测的**运行器**必须真的跑得起来 —— 不许"永远 SKIP"（0.9.229）。
# 上一份守卫管的是"测试的**源码**编得过"，这一份管的是"那份测试**被真的跑过**"：
#   · `run_embed_retrieve_tests.sh` 的主类名少一个 `Kt`（那份测试是顶层
#     `fun main`，生成 `…TestKt`）-> 有工具链时 `ClassNotFoundException`、
#     没工具链时 SKIP —— **一条断言都没跑过**，#308 / #309 却都记成"本机 SKIP"；
#   · `run_embed_parity_tests.sh` / `run_health_tests.sh` 编进来的文件引用了
#     `LlmEngine`（依赖 Android，编不进来）-> `unresolved reference`。
#   修好之后那三份套件当场多报出 13 条真失败（断言主语取错行 / 测试输入
#   `1.0f + 1e-9f` 在 f32 里是空操作 / 措辞少一个字恒假）。
# 同样**不依赖工具链**（它正是会缺失的那个东西）：纯源码级推出"主类名该叫什么"
# + 查"编进来的文件有没有引用没编进来的类型"。
echo "=== Kotlin 单测运行器守卫（不依赖工具链）==="; python3 tools/run_kt_test_runner_guard.py
# 它的自测：拿 5 份**已知该红**的桩跑（主类名缺 Kt / 引用未编译的 LlmEngine /
# 三个数少一个 / 引擎里又写一遍字面量 / EmbedLimits 碰 Android），
# 外加 2 份**必须保持绿**的（object+顶层 main 并存、同名类型各文件各声明一份 ——
# 都是第一版守卫恒红过的形态）。
echo "=== Kotlin 单测运行器守卫自测 ==="; sh tools/run_kt_test_runner_guard_tests.sh
# 接口鉴权（Bearer，仅生成端点）的**接线**守卫 + 它自己的自测。
# 单测（下一行）只证明 ApiAuth 这个纯函数算得对，证明不了它被真的用上：
#   · `Authorization` 头有没有被**读出来**（没读 → 判定永远拿到 null，
#     要么全拦、要么（fail-open 时）等于鉴权没做，两者都不报错）；
#   · 判定位置：排在 OPTIONS **之后**（预检必须豁免，拦了等于 CORS 白做）、
#     真实路由**之前**（否则生成端点已经跑起来、算力已经烧了）；
#   · token 有没有从设置**真注入**（三个启动分支都要走，漏一个就是
#     "同一个设置有的路径生效、有的不生效"）；
#   · 服务端自己打 `/health` 的看门狗不能被鉴权挡住（否则"服务在跑、
#     探测说连不上"）；页面带凭据且**留空时不拼空 Bearer**；
#   · 拒绝日志**绝不**打印凭据。
# 纯静态断言，不依赖工具链。
echo "=== 鉴权接线守卫 ==="; sh tools/run_auth_guard.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（头不再被读出来 / 判定被短路 /
# 判定挪到 OPTIONS 之前 / token 不再从设置注入 / 日志打印凭据 /
# /health 不再免鉴权 / 页面无条件拼 Authorization / /v1/abort 不带凭据 /
# 注释里写 /v1/* 触发嵌套块注释）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。
# 本轮**真抓到 1 处**弱断言（桩②：存在性断言写成"文件里出现过这个调用"，
# 被 `if (false && ...)` 骗过），已改成锚定"这个 if 真的在执行"。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 鉴权守卫自测（对着打桩源码跑）==="; sh tools/run_auth_guard_tests.sh
# 鉴权判据本体（ApiAuth 纯函数）的离线单测：未设 token = 不放行任何东西
# （keep-compatible 的判据本体）、免鉴权四条路、Bearer 拆解的宽松边界、
# 错误正文不含 token 也不含其片段。依赖 kotlinc/JDK/android.jar/json.jar，
# 缺失时 REQUIRED=0 退化为 SKIP。
echo "=== 鉴权单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_auth_tests.sh
# 请求解析**边界**（超长行 / 超大 body）+ 路径**前缀边界** + 状态行文案的接线守卫。
# 本轮模块 B 的四条修复全在接线层，且四条的共同点是**零症状**：
#   · body 超限被 `coerceAtMost` **静默截断**，截断后的 JSON 交给解析器去炸，
#     客户端拿到 500「服务内部错误」，真因却是"请求体超限"；
#   · 头行超限时 readLine 返回 null，与"头结束（空行）"走同一条 break ——
#     超长行**之后**的头被整段静默丢弃：丢掉 Authorization 报成"未带头"、
#     丢掉 Content-Length 报成 "messages is empty"，拒因指向完全错的方向；
#   · 路由与鉴权都用裸 `startsWith`，`/healthz`、`/v1/models-evil` 都算命中 ——
#     免鉴权集合被前缀放大（fail-open），与 ApiAuth 文件头承诺的方向相反；
#   · `reason()` 缺 400/413/500 文案，状态行回 "HTTP/1.1 500 Error"。
# 存在性断言（"文件里出现过 413"）挡不住这四条，必须锚定结构：
# 哨兵要真的被区分、上限要真的被拒、前缀要真的按边界比较。
# 纯静态断言，不依赖任何工具链。
echo "=== 请求边界/前缀守卫 ==="; sh tools/run_http_hardening_guard.sh
# 守卫自身的自测：拿 10 份**打桩源码树**（超限退回静默截断 /
# 超长与 EOF 混同 / 头循环把超长混进 break / 413 与 500 文案被删 /
# 免鉴权退回裸 startsWith / /v1/ 不再 fail-closed /
# 路由侧退回裸 startsWith 与鉴权侧分叉 / 畸形判定挪到路由之后 /
# 超限拒绝不留日志）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 请求边界守卫自测（对着打桩源码跑）==="; sh tools/run_http_hardening_guard_tests.sh
# 上面那份守卫的自测：3 份就地改坏的真守卫（正向 pattern 打错 -> 恒红/恒静默 /
# 负向断言的守卫删掉自测 -> 那条"不出现"再没东西能触发 / 计数阈值被注释撑到），
# 断言"该红的红、指得出是哪一条、还原后全绿"。
echo "=== 判据锚定守卫自测（对着打桩守卫跑）==="; sh tools/run_assertion_anchor_guard_tests.sh
# 工作区独占锁的**自测**：对着真并发跑，断言"锁真互斥、同进程/跨进程
# 嵌套抢锁不自锁死、超时拿不到锁时明确报错"。为什么必须有：接线守卫只判
# "接线在不在"，一把"永远直接放行"的空壳锁照样全绿；这一份补"锁本身对不对"。
# 实测：把锁打回空壳后，5 份打桩套件并发跑产出 17 条假失败（互相盖桩），
# 挂着真锁跑同一批 5 份全绿 —— 这就是它要钉住的东西。
# 纯 sh，不依赖工具链。
echo "=== 工作区独占锁自测（真并发互斥 + 可重入）==="; sh tools/run_worktree_lock_tests.sh
# 工作区独占锁的**接线守卫**：断言"凡就地打桩真源码的自测（备份真源码 +
# trap 还原），都在动源码前抢了那把锁"。加锁是人工动作，新写的打桩自测
# 忘加的表现是**一切照常**（锁没加照样绿）—— 这条守卫把那个静默疏漏
# 变成响亮失败。当前 44 份就地打桩自测全部接线。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 工作区独占锁接线守卫 ==="; sh tools/run_worktree_lock_guard.sh
# HTTP 响应体守卫的**自测**：拿 5 份就地改坏的真源码跑它，断言
# "该红的红、指得出是哪一条、还原后全绿"。桩全是真会写的坏法
#（行尾引号多一个 = Issue #77 同款真事故 / 漏逗号 / index 缺失 /
# Content-Length 用字符数 / errJson 漏花括号）。
# 为什么要自测：这份守卫是词法的，两个自欺方向都不报错 ——
# 判宽了恒绿（本例最容易的是"插值一律填 0"，会把引号结构错填平），
# 判窄了恒红（有人 `|| true` 掉）。只对着真源码跑一次看它绿，两件事都发现不了。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== HTTP 响应体守卫自测（对着打桩源码跑）==="; sh tools/run_http_body_guard_tests.sh
# 线程与**资源生命周期**守卫（Issue #174 / 模块乙-4）。
# 这一族的失效方向是"只涨不跌"，且**与正确顺序同形**：
#   · beginCancelable 摘除没在 finally 里 -> 异常路径漏摘 -> 僵尸轮次
#     （HTTP 照常 200，只是"取消不好使了"）；
#   · 基础设施线程漏标 daemon -> Service 停后 JVM 被吊住不退；
#   · ioExecutor 不 shutdown -> 每次 Activity 重建各留一个线程。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 线程与资源生命周期守卫 ==="; sh tools/run_resource_lifecycle_guard.sh
# 上面那份守卫的自测：4 份就地改坏的真源码（摘除离开 finally / 线程漏标
# daemon / executor 不 shutdown / shutdown 排在 super 之后），断言"该红的红、
# 指得出是哪一条、还原后全绿"。
echo "=== 线程与资源生命周期守卫自测 ==="; sh tools/run_resource_lifecycle_guard_tests.sh
# 局部变量「先用后声明」的词法检查（read-before-declare）。
# 为什么必须单列一份、且必须是**顺序**判据：2026-09-22 CI 红在
# `HttpApi.kt:434` —— `unresolved reference 'corsOrigin'`。
# 成因是 400/413 两条拒绝被提到路由之前，而 `val corsOrigin = ...`
# 的声明留在原地（局部变量在 Kotlin 里没有提升）。
# 它此前躲过 22 条判据，因为那些**全是 grep 存在性/邻接断言**：
# 只回答"这段文字在不在"，从不回答"这个名字在被读的那一刻可见吗"——
# `corsOrigin` 在文件里确实出现过 20 次，每条断言都为真，而文件编不过。
# 纯 python3 标准库，不依赖工具链，故不退化。
echo "=== 局部变量顺序检查（read-before-declare）==="
python3 tools/run_http_scope_order.py \
  app/src/main/java/com/xiaowan/localinference/HttpApi.kt
# 上面那份检查的**自测**：拿 5 份打桩源码（含"最小复刻 + 真源码就地
# 改回旧写法"）跑它，断言"该红的红、指得出是哪一条、该绿的不误报"。
# 为什么必须有：这份检查是词法的，两个自欺方向都不报错 ——
# 判宽了恒红（命名实参 `method = ""`、字符串键 `put("id", ...)`、
# 跨行 lambda 形参 `{ t, asReason ->` 都会被当成"读者"，真源码里遍地都是）。
# 开发这一版**两个方向都真踩到**：第一版误报 19 条，改完又漏判 1 条。
# 只对着真源码跑一次看它绿，发现不了这两件事。
echo "=== 局部变量顺序检查自测（对着打桩源码跑）==="
sh tools/run_http_scope_order_tests.sh
# **真编译主源码集**（Kotlin 侧的类型/符号解析，不是 grep）。
# 为什么必须进 CI、且必须排在前面：`0.9.107` 那次 CI 红是
# `HttpApi.kt` 的 `unresolved reference 'corsOrigin'` —— 上百条 grep 断言
# 全绿（文件被 22 份脚本引用），因为**没有一条在编译它**。
# `scripts/kt_check.sh` 早就能抓到这条，但它**是手动的、不在 CI 里** ——
# 一把没接在必经之路上的尺子，与没有这把尺子在本轮是同一件事。
# 迟到的代价是具体的：流水线最后一步 `gradle :app:assembleDebug` 才是
# 真编译，红在那里 = 前面 40 多份套件**全部白跑**（每轮 5~8 分钟）。
# kotlinc/JDK/android.jar 上面已就绪（缺了会先 exit 1，不静默跳过）。
echo "=== 主源码 Kotlin 编译（真类型检查，不是 grep）==="
# 工具链在**这一步之前**必须已经就绪：`kt_check.sh` 缺 kotlinc /
# android.jar 时是 exit 2（"不静默跳过"），而这里的语义是"必须跑到"——
# 所以先显式确认一遍，缺了就当场拦下（与上面 json.jar 那句同一条理由：
# 编不了主源码 = 构建一定是坏的，没有"环境问题"可退）。
[ -x "$TC/kotlinc/bin/kotlinc" ] || { echo "!! 缺 kotlinc —— 主源码编译检查无法执行，拦下"; exit 1; }
[ -f "$TC/android-35/android.jar" ] || { echo "!! 缺 android.jar（/opt/android-sdk/platforms/android-35）—— 拦下"; exit 1; }
sh scripts/kt_check.sh
# HTTP 监听**生命周期**（本轮模块 A）：listener 代际号 / desired vs running /
# 在途连接收敛。四条修复全是"稳态下 100% 通过、只在多线程交错时出现"的
# 零症状故障，其中 A-1 会让服务进入「永久不可用且不自愈」：
#   · listener 的 `finally` 无条件清 running/server ——
#     迟到的旧线程踩掉新一代 listener，现场是「端口在 LISTEN 但没人 accept」，
#     且 server 被清成 null 后 stop() 关不掉那个仍在监听的 socket
#     → 重启 BindException（SO_REUSEADDR 只救 TIME_WAIT）→ 永久不可用；
#   · watchdog 唯一闸门写成 `if (!running.get()) continue` ——
#     A-1 一旦踩中，此后每次走这条，设计目标"30~60s 自愈"实际是"永久躺平"；
#   · 503 分支的 `busy.set(false)` 在 CAS 失败时清**别人**的占位 ——
#     背压失效（本应被拒的请求被放进来挂在 genLock 上）+ /health 的 busy 说谎；
#   · stop() 只关 listening socket —— 在途连接由各自线程的 s.use{} 才释放，
#     后果是"服务已停，仍在为已停的请求把整段响应写完"。
# 判据锚定**结构关系**（谁在什么条件下清状态 / 闸门看哪个标志 /
# 判据有几种组合 / 有没有登记表），存在性断言挡不住这四条。
# 纯静态断言，不依赖任何工具链。
echo "=== HTTP 生命周期接线守卫 ==="; sh tools/run_http_lifecycle_guard.sh
# 守卫自身的自测：拿 10 份**打桩源码树**（finally 又变成无条件清 /
# accept 循环不再查代际 / watchdog 只看 running / 重启入口不查 desired /
# 503 判据退回复合写法 / stop 不再收敛在途连接 / 不再登记在途连接 /
# stop 不递增代际 / desired 被删掉 / 迟到退出不留痕）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自本轮真正的四处旧写法，不是编出来的反例。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== 生命周期守卫自测（对着打桩源码跑）==="; sh tools/run_http_lifecycle_guard_tests.sh
# 「时长测量必须用单调钟」守卫（Issue #198：G1/G2/G3 同族）。
# 墙钟（currentTimeMillis）受 NTP 校准 / 用户改表影响会前跳也会后跳：
#   G1 卡死看门狗：前跳 → 误报 native 卡死（引导强杀，丢会话）；
#                  后跳 → idle 恒假 → 真实卡死永不上报（静音，更坏）；
#   G2 SSE 心跳：后跳 → `now - lastBeat > 800` 恒假 → 思考段不再保活；
#   G3 tok/s：后跳被 `sec > 0.01f` 兜成 0.0（仅显示，低危）。
# 判据锚**结构关系**：一对差值的两个端点必须取自同一口单调钟 ——
# "文件里出现过 nanoTime"是恒真的（SamplingParams 早就用它取种子），
# 真正的判据是"这一处**具体的差**的写方与读方同时在 nanoTime 上"。
# 反向也钉：id/created/updatedAt 是对外时间戳，必须留墙钟。
# 纯静态断言，不依赖任何工具链。
echo "=== 单调钟守卫（时长测量不许用墙钟）==="; sh tools/run_monotonic_clock_guard.sh
# 上一份的自测：10 份桩（G1 写方/读方、G2 判据/基准、G3、watchdog 睡眠、
# health 耗时各退回墙钟 + 反向的 created/updatedAt 被误改 nanoTime），
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。桩全部取自真旧写法。
echo "=== 单调钟守卫自测（对着打桩源码跑）==="; sh tools/run_monotonic_clock_guard_tests.sh
# 生命周期判据**本体**的宿主侧复刻测试：开真实 socket、真的构造交错
# （旧 listener 慢一拍才进 finally），断言修复后的结构在交错下仍然可用：
# stop→start 之后旧 finally 不许踩掉新 listener、反复重启每次都活、
# 用户停服后不许被自动拉起、listener 意外死亡必须被拉起、
# stop() 要收敛在途连接。`HttpApi.kt` 依赖 Android 栈宿主编不了，
# 所以复刻的是 start/stop/finally/watchdog 的**结构**（逐条对应关系写在
# tools/lifecycle/HttpLifecycleTest.kt 的注释里），并额外做一道
# "复刻与实现是否脱钩"的源码级检查。
# 依赖 kotlinc/JDK，缺失时 REQUIRED=0 退化为 SKIP。
echo "=== HTTP 生命周期复刻测试（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_http_lifecycle_tests.sh
# 在途连接上限（模块 A 的补充项：资源不设限）。
# 旧 accept 循环**不做任何计数** —— 本项目是"每连接一线程"，而 bindAll=true
# 时监听 0.0.0.0、局域网内任何人可连：连接数 = 线程数且没有上界，
# 一个跑飞的客户端就能把进程拉到 `OutOfMemoryError: unable to create
# native thread`（Error 逃出 `catch (Exception)`）→ listener 死亡 →
# 端口仍 LISTEN 但没人 accept（与 A-1 同形的假活态）。
# 这条在稳态下完全看不出来（一个客户端只开 1~2 条连接），
# 所以必须**主动打满**才看得见差异。
# 判据锚结构：受理判据在**起线程之前**、占坑/登记/减计数**同源**、
# 超限回可读 503（不静默 close）、拒绝路径不占线程槽、计数不许泄漏。
# 依赖 kotlinc/JDK，缺失时 REQUIRED=0 退化为 SKIP。
echo "=== 在途连接上限复刻测试（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_conn_cap_tests.sh
# 「UI / 服务 / 存储」的接线守卫（模块 I 之 PR-1：I-1 / I-6）。
# 两条是**同一条纪律**的两个半面：`HttpApi.stop()` 必须先于"卸载模型 / 停服务"。
#   · I-1 ACTION_STOP 先 `LlmEngine.unload()` 后 `HttpApi.stop()`，与文件里的
#     ⚠ 注释和 README 的声明**正好相反** —— `unload()` 只持 loadLock、既不持
#     genLock 也不看 hasModel，而 stop() 只 close() socket、不打断生成线程；
#     同一个动作 UI 路径（doUnload）挡了"生成中不许卸载"、Service 路径没挡，
#     两条路径判据不同，而更宽松的那条更危险。
#   · I-6 两处 `stopSelf()` 没配对 `HttpApi.stop()` —— desired 仍为 true，
#     watchdog 每 30s 探活、连续 2 次失败就重启 listener，只靠异步的
#     `onDestroy()` 兜。当前不可达，但"配对"没有任何守卫。
# 判据锚**顺序关系**（stop 的行号必须小于 unload）与**配对关系**
# （每处 stopSelf() 之前同一分支必须先有 stop()）+ **对照**
# （UI 与 Service 两处"生成中不许卸载"必须同时存在）——
# "文件里出现过 HttpApi.stop()"这种存在性断言挡不住 I-1（两行都在，顺序反了）。
# 纯静态断言，不依赖任何工具链。
echo "=== UI/服务接线守卫 ==="; sh tools/run_ui_service_guard.sh
# 守卫自身的自测：拿 7 份**打桩源码树**（先卸载后停服 / 两处 stopSelf 不配对 /
# 注释里说 stop 先而实现是 unload 先 / 卸载闸门被删 / onDestroy 不收敛 /
# UI 路径不再拦生成中卸载）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。桩全部来自本轮真正的旧写法，不是编出来的反例。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== UI/服务守卫自测（对着打桩源码跑）==="; sh tools/run_ui_service_guard_tests.sh
# 「后台保活（无障碍 / 电池优化引导）」的接线守卫（Issue #159 的 B 方案）。
# 这一族有四种**零症状**的坏法，每一种都不崩、不报错、也不影响别的测试：
#   · K-1 `<service>` 少了 BIND_ACCESSIBILITY_SERVICE / intent-filter /
#     meta-data 任一条 —— 系统把这一项从无障碍列表里丢掉，界面上只显示
#     "未开启"，用户会以为是自己没找到；
#   · K-2 `canRetrieveWindowContent="true"` 或 flags 里加了
#     retrieveInteractiveWindows / 事件类型写成 typeAllMask ——
#     服务开始能读到用户所有界面的文本，而**看起来完全正常**；
#   · K-3 直接写 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 代用户开启
#     （要么拿不到系统级权限，要么等于绕过用户同意）；
#   · K-4 把"查不到"折成"没开" —— 一次系统接口抽风变成一次误报，
#     用户会去反复开关那两项，而这正是本轮要终结的处境。
# 判据锚**结构关系**（声明齐备 / 形态只看有没有活着 / 三态没被折成两态），
# 不锚存在性（"文件里出现过 AccessibilityService"）—— 那种断言在上面四条上全绿。
# ⚠ 判据必须**排注释**：xml 注释里就在解释"禁止加这两项"，
#   第一版守卫因此在自己的说明文字上报了 3 条假故障（自测桩⑤ 钉这条）。
echo "=== 后台保活接线守卫（无障碍 / 电池优化）==="; sh tools/run_keepalive_guard.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（丢 permission / 事件类型退回
# typeAllMask / flags 加上交互窗口 / 查不到折成未开 / **把禁用项写进注释**
# （必须全绿）/ 源码里代用户开启 / 跳转降级只留一级 / onResume 不回读 /
# 冻结痕迹另发一次请求）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。桩⑤ 是刻意设计的对照：判据锚到注释上时它会红。
echo "=== 后台保活守卫自测（对着打桩源码跑）==="; sh tools/run_keepalive_guard_tests.sh
# 「常驻推理服务的 FGS 类型声明」守卫（Issue #159 的 A4）。
# 这一块有三种**零症状**的坏法（都不崩、不报错，只有 API 34+ 真机上才发作）：
#   · `specialUse` 声明了却没写 `<property ...PROPERTY_SPECIAL_USE_FGS_SUBTYPE>`
#     用途说明 —— manifest 照样合、照样装，但在系统/审核侧与"没声明"同形；
#   · 少了 `FOREGROUND_SERVICE_SPECIAL_USE` 权限 —— 该类型在 API 34+ 不被认，
#     而本仓库开发路径（targetSdk=28）永远撞不到，正是"没人会发现"的那类；
#   · 加了 `specialUse` 却把 `dataSync` 抹掉 —— 那是对上一次声明的静默改写。
# 还有一条方向性坏法：把类型声明说成"保活开关"（FGS 类型位**不**改
# oom_score_adj，也**换不到**冻结豁免）—— 判据要求同一段说明里点出"它不做什么"，
# 并锚"承诺词必须被就近否定"，而不是"全文里有没有出现过'不是'"。
echo "=== FGS 类型声明守卫（dataSync|specialUse）==="; sh tools/run_fgs_type_guard.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（抹掉 dataSync / specialUse 没有用途说明 /
# 少了对应权限 / 用途说明写成空话 / 说成"保证存活" / **把禁用项写进注释**
# （必须全绿）/ 偷偷提到 IMPORTANCE_HIGH / 顺手抬 targetSdk /
# README 不点明 oom_score_adj 由什么决定）跑上面那个守卫，断言"该红的红、
# 该绿的绿、且失败时点得出是哪一条"。桩⑥ 是刻意设计的对照：
# 判据锚到注释上时它会红（本仓库在这一族上栽过一次）。
echo "=== FGS 类型守卫自测（对着打桩源码跑）==="; sh tools/run_fgs_type_guard_tests.sh
# 「UI / 服务 / 存储」的主线程与资源守卫（模块 I 之 PR-2：I-2 / I-3 / I-4 / I-5）。
# 四条的共同形态是「代码在那儿、但没起作用」：
#   · I-2 `refreshModelList` 在**主线程**上逐行去问 prefs / stat ——
#     循环体里的 IO 次数随模型数线性增长（N=50 时可达数百毫秒）；
#   · I-3 `File(ctx.getExternalFilesDir(null), child)`：外部存储不可用时前者
#     返回 **null**，而 `File(File?, String)` 在 parent==null 时退化成
#     `File(child)` —— 一个**相对进程 CWD** 的路径，不抛、不返 null，静默改语义；
#   · I-4 `LogFileStore` 的折叠状态（fileBase/fileRep/noiseRun/noiseSample）
#     读-改-写全在 `synchronized(lock)` **之外**，而 append 会被四个线程并发调用 ——
#     而它产出的 `×N` 与「省略 N 条」**唯一用途就是给人对账**；
#   · I-5 `ioExecutor` 每次 onCreate 现建、`onDestroy` 从不 shutdown ——
#     每次重建留一个线程，且任务都捕获了 this（可观测的 Activity 泄漏）。
# 判据一律锚**结构关系**，不锚存在性：I-2 锚"取数在 ioExecutor 上、渲染是另一个函数"；
# I-3 锚"不得把 getExternalFilesDir 直接当 File 第一实参"；
# I-4 锚"这四处字段的每一次读写都在锁区段内"（不是"有 synchronized"）；
# I-5 锚"建了就关"。纯静态断言，不依赖任何工具链。
echo "=== UI/IO 守卫 ==="; sh tools/run_ui_io_guard.sh
# 聊天页「气泡化 + 单条删除」的接线守卫。三条不变量的共同点是**零症状**：
#   · 流式退回"每 token 重建整屏"照样出字，只在长会话 + 慢生成时表现为卡顿；
#   · 长按删除若在闭包里捕获下标，"删掉的不是我按的那一条"要等列表重绘过
#     才出现，且不可恢复（判据必须是 chatBubbles.indexOf 当场反查）；
#   · 删除/清空之后不作废 KV 账本**也不会有任何可观测的症状**（复用判据是
#     逐 token 比前缀，分叉了自然不复用）—— 只是那几 MB KV 白占显存。
# 判据一律锚结构关系（调用关系 / 参数来源 / 顺序），不锚存在性。
# 纯静态断言，不依赖任何工具链。
# 聊天记录**导入 / 导出**的接线守卫。它的坏法全部是"错得不响"：
#   · 导入把现有会话**覆盖**掉（几天后才发现记录被换成了别人的）；
#   · 选错文件被"尽力解析"成"导入 0 个"（用户以为自己的记录是空的）；
#   · 脏 role 混进历史 —— 历史直接喂模型，别的 role 会被**当指令执行**，
#     症状只是"回答怪怪的"，与模型抽风完全同形；
#   · 导出套上 MAX_CHARS 裁剪（记录"看起来完整"其实被截了头）；
#   · 导出 MIME 写成 text/plain（.json 被当纯文本，全程无报错）；
#   · 导入的会话文件布局在 UI 里自己拼（改命名规则后静默失效）；
#   · 导入复用"选模型"的请求码 42（选完文件什么都没发生）。
# 判据锚结构关系（谁调用谁 / 参数从哪儿来 / 行序 / 在哪个函数体内），
# 不锚存在性：顺序类不变量（格式名判据必须在读 sessions 之前）只有行序判据才不是恒真的。
echo "=== 聊天记录导入/导出守卫 ==="; sh tools/run_chat_archive_guard.sh
# 守卫自身的自测：拿 13 份**打桩源码树**（导入按原 id 覆盖 / 格式名对不上也尽力解析 /
# role 不过滤 / 导出做裁剪 / MIME 退回 text/plain / UI 自己拼会话文件名 /
# 导入与选模型共用请求码 / 上限静默截断 / 格式名判据挪到读 sessions 之后 /
# 读文件回到主线程 / 导入路径去改现有会话 / 新请求码挤掉模型导入分支 /
# 脏数据静默丢弃不计数）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。桩全部来自真正会写错的写法。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 聊天记录导入/导出守卫自测（对着打桩源码跑）==="; sh tools/run_chat_archive_guard_tests.sh
# 上面那份钉的是"结构没改回去"，证明不了"这套结构在**边界输入**下真的对"。
# 而本特性的难点全在边界：信封缺字段 / 版本过高 / sessions 不是数组
# 三种都必须**拒绝**且理由分得开；脏 role 要跳过**并计数**（不整份拒绝、
# 也不静默带上）；encode→decode 必须逐字段往返一致 ——
# "备份"本身不可信的话，这个功能就没有意义。
echo "=== 聊天记录导入/导出行为测试 ==="; sh tools/run_chat_archive_tests.sh
# 知识库**正文**导入/导出的接线守卫（与聊天记录那一族同形，但危险处不同）：
#   · 信封**连向量一起带走** —— 跨机器导入时指纹对不上，用旧语义的向量命中、
#     注入新边界切出的正文，余弦量纲都对不上、排序像随机，而接口 200、日志干净。
#     本特性唯一不可归因的错法，所以信封里一个向量字段都不留。
#   · 导入**覆盖**现有库 —— 不报错也不崩，只是原有笔记在某次"导入"之后换成
#     了别人的，用户要过几天才发现；这里只增不改，合并后**同时删掉旧索引**。
#   · 导入后**留着旧索引** —— 列表照旧报"有索引、N 条"，而正文已经比它多了；
#     症状是"导入完就能搜"的错觉，检索却一直在用与正文对不上的边界。
#   · 选错文件被"尽力解析"成"导入 0 条" —— 用户读成"我的笔记是空的"，
#     真相是选错了一个别的 App 的备份文件。
#   · 坏行静默丢 / 超限静默截断 / 请求码与选模型共用（选完文件什么都没发生）。
# 判据锚结构关系（谁调用谁 / 参数从哪儿来 / **行序** / 在哪个函数体内），
# 不锚存在性：顺序类不变量（格式名判据必须在读 entries 之前 / 删索引必须在
# 写正文之前 / 上限判据必须在逐条收之前）只有行序判据才不是恒真的。
echo "=== 知识库导入/导出守卫 ==="; sh tools/run_rag_archive_guard.sh
# 上面那个守卫的自测：拿 16 份**打桩源码树**（信封带上向量 / 格式名对不上也尽力解析 /
# 格式名判据挪到读 entries 之后 / 坏行不计数 / 超限阈值写死 / 导入整份覆盖 /
# 导入后不删旧索引 / 先写正文再删索引 / 导入改走 appendEntries /
# 导出要求嵌入模型在线 / 请求码共用 / 不弹确认 / 导入后不切库 /
# MIME 退回 text/plain / 正文写失败却报成功）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。桩全部来自真正会写错的写法。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 知识库导入/导出守卫自测（对着打桩源码跑）==="; sh tools/run_rag_archive_guard_tests.sh
# 上面那份钉的是"结构没改回去"，证明不了"这套结构在**边界输入**下真的对"。
# 而本特性的难点全在边界：三种形状（信封 / 裸 JSON 数组 / 裸 JSONL）都要能吃，
# 且按**内容**判而不是按扩展名（SAF 上 .json 常被报成 text/plain，按扩展名判
# 会让"明明在，却选不中"）；别的 App 的备份文件必须**拒绝**；坏行跳过**并计数**
# 而末尾空行不算坏行；超限**整份拒绝**不截断；encode→decode 逐条往返一致 ——
# "备份"本身不可信的话，这个功能就没有意义。
echo "=== 知识库导入/导出行为测试 ==="; sh tools/run_rag_archive_tests.sh
# 「.txt / .md 当正文导入（走粘贴建库那条路）」的接线守卫 + 自测。
# 它要修的是一个**用户报出来的**可用性问题：Android 的剪贴板装不下他
# 那段要粘的字，而同一份笔记**作为文件是完整的** —— 手里有文件却没路走。
# 危险处全在"错得不响"：
#   · 纯文本导入**直接写库**（绕开编辑器这条粘贴建库的路）—— 文档里
#     该并行的短行在用户没看清时落盘，而导入**没有撤销**；
#   · 格式判据靠扩展名（SAF 上 .md 常被报成 application/octet-stream，
#     "明明在却选不中"；反过来选错文件时二进制被逐行收成条目，
#     建库成功、检索**能命中**，命中的是乱码）；
#   · 非 UTF-8 的中文文件被当 UTF-8 读 —— 每一行都是 `�`，
#     落盘、建库、可检索，而内容全是乱码，没有任何一步会报错；
#   · 这条路**自己又写一遍**"落盘之后做什么"（切库 / 回读 / 刷列表）——
#     两处各写一份，改一处另一处不会红，漂移的形态是"库切过去了、
#     框里还是上一个库的正文"（两个信号打架，用户看到的那个说"成了"）。
# 判据锚结构关系（谁调用谁 / 谁设置哪个状态 / 在哪个函数体内 / **行序**），
# 不锚"某某字符串出现过"；取函数体的两个小工具（check_pure.sh /
# check_order.py）都自带"取不到体就报红 + 对着已知不纯的函数必须判红"的自证。
echo "=== 纯文本导入守卫 ==="; sh tools/run_rag_plain_import_guard.sh
# 上面那个守卫的自测：拿 9 份**打桩源码树**（判据挪到按行收之后 /
# 不挡二进制 / 不拒非 UTF-8 / 解析完直接落盘、**绕开确认框** /
# .txt 的确认框不说"会写盘" / 两个入口共用一个请求码 /
# 按钮名退回"导入正文" / 这条路自己又写一遍落盘后的动作 /
# 解码被塞进 Context）跑上面那个守卫，断言"该红的红、该绿的绿"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 纯文本导入守卫自测（对着打桩源码跑）==="; sh tools/run_rag_plain_import_guard_tests.sh
# 「建库要花多久」的**纯逻辑**估算（RagCost）单测：条数 < 阈值时**必须闭嘴**
# （每次弹框的后果是用户闭眼点确定，真有一条贵的提示时也被无视）、
# 量级换算（秒 / 分 / 十几分钟三档）、撞 maxChunks 时**换一句话**说
# （"超出的不进索引" ≠ "慢一点"，后果差一个量级）、文案 ≤ 3 行（用户要求"简短"）。
echo "=== 建库成本提示纯逻辑单测 ==="; sh tools/run_rag_cost_tests.sh
# 上面那条单测只证明"算得对、文案短"，证明不了**接上了** ——
# 而这一族缺陷的形状恰恰是"纯逻辑全绿、界面上什么都没有"：
#   · buildPrompt 写好了但没人调 -> 用户按下去之前**仍然是零信息**（本轮要修的那件事）；
#   · 调了但排在 startBuildIndex **之后** -> 与不提示等价（甚至更坏：弹框挡在已开跑的建库前）；
#   · 条数小时也调 -> 用户闭眼点确定；
#   · RagCost 里那份抄写的上限与 ChunkParams.maxChunks 分家 -> 撞上限时算错。
echo "=== 建库成本提示接线守卫 ==="; sh tools/run_rag_cost_guard.sh
# 上面那个守卫的自测：拿 6 份**打桩源码树**（提示排到开工之后 / 小批量也弹框 /
# 开工入口多开一处 / 抄写的上限与真实值分家 / RagCost 引了 android / 导入那条路
# 不再复用 RagCost）跑上面那个守卫，断言"该红的红、该绿的绿、且点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 建库成本提示守卫自测（对着打桩源码跑）==="; sh tools/run_rag_cost_guard_tests.sh
# 「建索引那一行的进度读数」（已耗时 / 已编条数 / 总数 / 占比 / 速率）的
# **纯逻辑**单测与接线守卫。
#
# 为什么这两条必须有：建库的耗时由**块数**主导，一份 3 MB 的整篇能切出
# 上万块、跑几十分钟 —— 那几十分钟里用户手上唯一的参照物就是这一行。
# 而它的失效形态全是"读数本身变成噪声或谎言"，一条都不崩、不报错：
#   · 位宽抖动（`1:5` vs `1:50`）-> 一行每 250 ms 抖一次，像"闪"不像"走"；
#   · 分母不知道还报占比（`0/0 条（NaN%）`）-> 没有意义的数看着像读数；
#   · 第一秒内报速率 -> 分母极小放大的噪声看着像真的；
#   · 报了"预计剩余" -> 块与块耗时差一个数量级，推出来的剩余**假精确**。
# 单测钉这些纯逻辑边界（59/60/61 秒、59/60 分、恰好 1 小时、负数）。
echo "=== 建索引进度读数纯逻辑单测 ==="; sh tools/run_rag_progress_tests.sh
# 上面那条单测只证明"拼得对、边界对"，证明不了**接上了** ——
# 而本仓库在这一处**真的栽过**（而且栽了很久，因为没有任何判据在看它）：
# 收方那份前缀副本多写了"逐条"两个字（发方 `正在编码：`、收方
# `正在逐条编码：`），于是 `startsWith(PROGRESS_PREFIX)` **恒假** ——
# 进度从来没走到进度行，每一行都落进"2000 行 ring 全量重排"那条闸
#（正是 `uiProgress` 那段注释里"上万行不进 ring"要防的事）。
# 判据恒假 = 整条保护一行都没生效，而它**看起来**一直在。
# 另钉四件事：开工时刻取**单调钟**（墙钟做差会被 NTP 跳变拉歪）、
# 切页那一跳**带读数**（用户报的"建索引提示丢失"）、收工后**清最后读数**
#（否则已完成的库看起来像"还在跑"）、native 与 Kotlin 两份 `format_elapsed`
# 对同一组边界值给出同一串（"日志 1:23、界面 1:5"这种漂移没有迹象）。
echo "=== 建索引进度读数接线守卫 ==="; sh tools/run_rag_progress_guard.sh
# 上面那个守卫的自测：拿 6 份**打桩源码树**（收方另写一份前缀副本 /
# 开工时刻用墙钟 / 切页退回静态话 / 收工不清最后读数 /
# native 格式化不补零 / 发方传 i+1 让进度提前报满）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 建索引进度读数守卫自测（对着打桩源码跑）==="; sh tools/run_rag_progress_guard_tests.sh
# 嵌入建库**速度**（ISSUE #269）：8e 手机上 1 秒 1.3 条 / 7000 条约两小时。
# 三处叠加，且每一处的形态都是"参数在某处写死、在某处断掉，界面上看不出来"：
#   · `loadEmbedModel` 的 `nThreads` 默认值写死 4（8 核机器一半核心闲着）；
#   · `doLoadEmbed` 不传线程数 -> 设置页那个 threads 输入框对建库**完全无效**
#     （用户从 4 调到 8，速度一点不变，且没有任何东西说明这一点）；
#   · `RagCost` 的秒/条口径比真机读数（0.77）还小 2.6 倍，
#     把 7000 条报成"约 35 分钟" —— **提示报小了比不提示更坏**。
# 这三条都**测不出来**（单测喂什么线程数就跑什么线程数，"UI 传没传"它看不见），
# 所以判据锚源码结构关系 + 常量与真机读数的大小关系。
echo "=== 嵌入建库速度守卫 ==="; sh tools/run_rag_index_speed_guard.sh
# 上面那个守卫的自测：拿 5 份打桩源码（默认值写回 4 / UI 不传线程数 /
# 兜底写回常数 / RagCost 上界回到 0.30 / 去掉小时档）跑它，
# 断言"该红的红、且点得出是哪一条"。纯 sh + python3 标准库。
echo "=== 嵌入建库速度守卫自测（对着打桩源码跑）==="; sh tools/run_rag_index_speed_guard_tests.sh
# 嵌入**合批**（ISSUE #269 的另一半）：PR #273 拿回来的是"线程数按本机核数"
# （多核那一份算力），它在自己的"放弃方案"里明确没走"多条目 batch"——
# 理由是"合批要先证明条目间语义不串"。那一半现在落地了：隔离靠
# `llama_batch` 的 `seq_id`（库按 seq 分块建注意力掩码），每条的 `pos`
# 从 0 起（**不是**整批全局下标）。
# 这一族错全是**静默的**：只清 seq 0 的 KV -> 后几条复用上一批的 KV
# （向量混味、接口 200）；`pos` 用全局下标 -> RoPE 一转，向量变成
# "另一条文本在另一段的语义"而长度/维度/归一化全对；两处批大小不等 ->
# 每批都在 native 被拒、回落逐条，合批白做而界面上看不出来。
# 单测钉不住它们（喂什么批大小就跑什么批大小），所以判据锚源码结构关系。
echo "=== 嵌入合批守卫 ==="; sh tools/run_embed_batch_guard.sh
# 上面那个守卫的自测：拿 10 份打桩源码树（只清 seq 0 的 KV / pos 用整批
# 全局下标 / 两处批大小不等 / 合批的失败记账抄成两处 / batchable_pooling
# 自己硬编码档位 / n_ctx 不随 n_seq_max 放大 / 截断点看 ctx 总量 /
# 合批不判全零 / nativeEmbedBatch 自己再判一次档位 /
# library_gives_seq 放行清单漏掉 CLS）跑它，断言"该红的红、且点得出是哪一条"。
echo "=== 嵌入合批守卫自测（对着打桩源码跑）==="; sh tools/run_embed_batch_guard_tests.sh
# 嵌入合批的**读数**（第 1 步）与**批大小可调**（第 2 步）是否真的接上了
# （ISSUE #269）。这两件事各自的纯逻辑单测都能全绿，而它们要修的恰恰是
# "纯逻辑全绿、界面上什么都没有"这一类：读数的算得对但建库不调 record
#（永远是空态）；/health 报了字段但 HealthCheck 不解析（报告里读不到）；
# 设置页能填能存但 buildIndex 不读它（与当初那个"线程数输入框对建库
# 完全无效"一模一样）；批大小没进指纹（改了不重建）。单测钉不住它们。
echo "=== 嵌入合批读数与可调守卫 ==="; sh tools/run_embed_batch_tuning_guard.sh
# 上面那个守卫的自测：拿 10 份打桩源码树（建库不记读数 / 只记合批不记回落 /
# 报了字段不解析 / 设置页存了但建库不读 / 指纹没写 g / FpInput(fp) 不搬 g /
# C++ 指纹没写 g / 检索侧不传批大小 / normalize 自己再钳一次 /
# 读数层去做决策）跑它，断言"该红的红、且点得出是哪一条"。
echo "=== 嵌入合批读数与可调守卫自测（对着打桩源码跑）==="; sh tools/run_embed_batch_tuning_guard_tests.sh
# 「向量逐位比对」是否真的接上了（ISSUE #269 第 5 步）。0.9.218 把批大小
# `g` 单独定级但**明确不授权沿用**，理由是那条断言「不同批大小算出的向量
# 逐位相同」尚未验证。而"尚未验证"这件事本身也会静默地一直挂着：入口没有 /
# 只跑合批那一侧（比的是"合批自己和自己"，结论恒为"相同"——一个假成立）/
# 结论只打日志不落 /health / 报了但 HealthCheck 不解析 / 报成布尔
#（"没测过"与"不是逐位"混成一个值）/ 换模型不清结论。单测钉不住它们
#（它喂什么向量就比什么向量，"界面上有没有那个入口"看不见）。
echo "=== 嵌入逐位比对守卫 ==="; sh tools/run_embed_parity_guard.sh
# 上面那个守卫的自测：拿 16 份打桩源码（只跑一侧 / 结论不落 /health /
# 报了不解析 / 报成布尔 / 换模型不清 / allIdentical 用"没有不同"判 /
# 界面没入口 / 样本集不是常量 / 逐条明细不打日志 / 不同的条数写成常量 0 /
# 去掉自洽拦截 / 入口不跑同路径重复 / 没有等长对照集 / "测没测"写成常量 /
# 入口不重复合批那条路 / 样本条数与批大小不对齐）跑它，
# 断言"该红的红、且点得出是哪一条"。
echo "=== 嵌入逐位比对守卫自测（对着打桩源码跑）==="; sh tools/run_embed_parity_guard_tests.sh
# 「换批大小重建索引后，**检索结果**还是不是同一些行」是否真的接上了
# （ISSUE #269 第 5 步：与上面那格**配对**的另一半）。
# 用户那句问得对：「索引逐字节对比都不一样，检索出来能一样？」——
# 而在此之前本仓库**只有一个向量级的读数**，这句问话只能靠**推断**回答，
# 而那条推断恰好是"`g` 能不能按库放行"的全部依据。这一格补的正是
# "向量不同**不蕴含**答案不同"这件事：同批文本按两种批大小各编一套向量、
# 同一组查询互查，比**命中的行下标**。静默失效的形态与上面同族：
# 没有入口 / 只编一套向量（同一侧查自己，结论恒"一致"——一个假成立）/
# 两侧同一个批大小 / 查询不在同一批文本里（两侧都搜不到 -> "都空"被读成
# "答案没变"）/ 结论只打日志不落 /health / 报了但 HealthCheck 不解析 /
# 「测没测」不单独一格（没测时 answer_changed 也是 0，与"全同"同形）/
# 换模型不清结论 / 那套向量**落盘**（写出一个用户没点过的知识库）。
echo "=== 嵌入检索对照守卫 ==="; sh tools/run_embed_retrieve_guard.sh
# 上面那个守卫的自测：拿 10 份打桩源码（只编一套向量 / 两侧同一批大小 /
# 查询集改手写（不在同一批文本里）/ 结论不落 Last / /health 漏报"测没测" /
# HealthCheck 不解析 / 界面不调 / 名次变了那格被删 / 换模型不清 /
# 那套向量落盘）跑它，断言"该红的红、且点得出是哪一条"。
echo "=== 嵌入检索对照守卫自测（对着打桩源码跑）==="; sh tools/run_embed_retrieve_guard_tests.sh
# 「检索对照」纯逻辑（EmbedRetrieveParity）的离线单测：命中**集合**变了
# 与只有**名次**变了必须分得开（打平处换序不是"答案变了"，两者处置不同）；
# 「测没测」单独一格（没测时 answer_changed 也是 0）；两侧都搜不到 =
# **没量到**（不是"一致"）；分数只作明细、不参与判定；Last 的自洽拦截。
# 都不抛异常，只有断言能钉住。
echo "=== 嵌入检索对照纯逻辑单测 ==="; sh tools/run_embed_retrieve_tests.sh
# 「向量逐位比对」纯逻辑（EmbedParity / EmbedParityProbe）的离线单测：
# 逐位相同 / 仅数值接近 / 真的不同 / 缺失 四档分得开（把 CLOSE 当 IDENTICAL
# 就是"据此沿用"，而索引里是两种向量）；allIdentical 用"全部逐位"而不是
# "没有不同"判（否则缺失也算成立）；空批不报"成立"；describe 的措辞与
# 结论档一一对应；**两条路各自的重现性分开**（逐条不确定 vs 合批自身
# 不确定，处置相反）；**样本条数与批大小对齐**（跨版本比结论时不多个
# "图不一样"的变量）。都不抛异常，只有断言能钉住。
echo "=== 嵌入逐位比对纯逻辑单测 ==="; sh tools/run_embed_parity_tests.sh
# 多 seq 下**池化向量取自哪一行**（ISSUE #269 第 3 步）。0.9.207 及以前，
# 库自己池化的三档（MEAN / CLS / LAST）**不合批** —— 理由是 `embd_seq`
# 在多 seq 下的布局没验过。这一轮验了（每个 seq 一行，第 j 条读
# `get_embeddings_seq(ctx, j)`），于是 BGE（CLS）与 Qwen3-Embedding（LAST）
# 才第一次真的拿到合批收益。撤闸之后**新增的唯一风险**就是"读第几行"，
# 而它的失效形态全静默：取错行 -> 8 条共用同一段语义；越界取 ->
# 库对越界没有契约，可能返回别的行的指针（比 null 更坏：那是别人的语义，
# 而下游一切正常）；走错 API -> 读 embd.data 那块从未写入的内存（全零）。
echo "=== 嵌入多 seq 池化取值守卫 ==="; sh tools/run_embed_seq_guard.sh
# 上面那个守卫的自测：拿 8 份打桩源码（取向量只走 ith / 分岔器被绕过 /
# seq 实参写死 0 / 越界判据挪到调用之后 / 行号就地写不经落点 /
# 单条路径手抄分岔 / 退回『库池化的档不合批』/ 行号函数里出现 native 符号 /
# read_from_seq 丢掉 library_gives_seq）跑它，
# 断言"该红的红、且点得出是哪一条"。
echo "=== 嵌入多 seq 池化取值守卫自测（对着打桩源码跑）==="; sh tools/run_embed_seq_guard_tests.sh
# 嵌入合批**读数层**（`EmbedBatchStats`）的离线纯逻辑单测：批次数与条数
# 对不对得上、负耗时按 0 记、空态的比例是 null（而不是 0.0 —— 那会被读成
# "合批命中率 0%"这个**结论**）、reset 清得干净。都不抛异常，只有断言能钉住。
echo "=== 嵌入合批读数纯逻辑单测 ==="; sh tools/run_embed_stats_tests.sh
# 知识库页「一屏画多少」的**纯逻辑**（RagUiCap）：条目列表 / 库列表 /
# 正文框三处容量上限的唯一出处。失效形态全是**静默**的、且方向相反 ——
# 截了不说（用户以为库就这么多）与没截也说（警告变噪音）都不抛异常，
# 只有断言能钉住。含"截断绝不切在代理对中间"那条（孤立代理落盘变 `?`）。
echo "=== 知识库页容量纯逻辑单测 ==="; sh tools/run_rag_ui_cap_tests.sh
# 上面那条单测只证明"算得对、话说明白了"，证明不了**接上了** ——
# 用户报的"进向量页直接卡、页面空白"是三处叠出来的：7021 行 × 3 个 View
# 全铺开（rootScroll 不虚拟化）、6 MB setText 进 EditText（全量布局，秒级）、
# 状态行在**主线程**上把整个 texts.jsonl 读两遍数条数。
# 三处都不抛异常，现象都是"慢/空白" —— 只有静态接线判据能钉住它们不许回来。
echo "=== 知识库页容量接线守卫 ==="; python3 tools/rag_ui_cap/check_wiring.py \
    app/src/main/java/com/xiaowan/localinference/EngineActivity.kt \
    app/src/main/java/com/xiaowan/localinference/RagUiCap.kt
# 「条目列表先出 + 删一条不重读盘」的接线守卫（ISSUE #228）。用户报的是
# *"输入框的预览和逐条预览这个，还能优化么，现在还会卡很久"*，以及"点库之后
# 一片空白很久"。两处**同族**的浪费，都不抛异常、都只表现为"慢/空白"：
#   P0 —— 条目列表的刷新挂在了正文框回填**之后**的 post 里，于是它要等这一屏
#         最贵的一步（`setText` 全量布局，几十万字符 = 秒级）做完才出现；
#   P3 —— 删除成功后走 `refreshRagRows`，把 6 MB 的 texts.jsonl 又读一遍，
#         而"删完之后磁盘上是哪张表"是**已知的**（摘掉第 index 行、其后前移一位）。
# 判据锚**相对次序**（列表刷新在后台读之前）与**调用了谁**（删除走纯函数推算），
# 不锚"某某字符串出现过"。含"推算不许按一份来路不明的表推行号"那条 ——
# 错行号的症状是"删掉的是隔壁那一行"，屏幕上一切正常。
echo "=== 条目列表先出 + 删除不重读守卫 ==="; sh tools/run_rag_list_first_guard.sh
# 上面那个守卫的自测：拿 10 份把真源码就地打桩的来源（列表挪回回填之后 /
# 列表发在归属之前 / 删除后整份重读 / 推算不验对齐不变量 / 保留旧行号 /
# 推算里出现磁盘调用 / UI 里手写第二份推算 / dropRowAt 不返回 null /
# 摘要行退回默认断行策略 / 列表改用压紧的 readEntries）跑它，
# 断言"该红的红、且点得出是哪一条"。纯 sh + python3 标准库。
echo "=== 条目列表先出 + 删除不重读守卫自测（对着打桩源码跑）==="; sh tools/run_rag_list_first_guard_tests.sh
# 正文框「收起 / 展开」的接线守卫（ISSUE #259）。用户报的是"滑动太慢"：
# 正文框是 WRAP_CONTENT，能装 4 万字符就**占十几屏**，而「追加」与条目列表
# 恰好在它下面 —— 每次去那儿都要滑一遍。这一族改动的坏法**全在"错得响、
# 但方向不对"**：「收起」只有两种常见写法，而界面效果前两秒完全一样 ——
#   · `maxLines`（对的）：内容一个字节不动，只是框变矮；
#   · `setText("")` / `visibility = GONE`（错的）：框"消失"了，
#     而 `ragDocEntries()` 读的就是它 —— 下一次「建库/重建」拿一份**空正文**
#     去建，不崩、不报错、索引照建，只是建出来的是空的。
# 所以判据钉的不是"看起来对不对"，是"**这个动作碰了哪些东西**"：
# 收起函数体内不许出现任何内容侧调用；收起态不许进 `ragDocEntries` /
# 「保存正文」/「建库/重建」的判据链；按钮还必须挂在框**之前**（挂在下面
# 等于把这一功能要解决的"滑太慢"原样还给用户）。
echo "=== 正文框收起接线守卫 ==="; sh tools/run_rag_doc_fold_guard.sh
# 正文框从「装整库的编辑器」改成「装一页的只读预览」（P1-a，ISSUE #228）。
# 用户报的是*"输入框的预览和逐条预览这个，还能优化么，现在还会卡很久"*：
# 框此前要一次装下整个库，而 EditText 装多少就排多少版（全量 StaticLayout），
# 于是库一大，"点开库"就要等几秒到十几秒。P1-a 让它只装**当前这一页**
# （与条目列表同一页）；P1-b 再把"只读"收回来 —— 框可以改这一页，保存按
# **行偏移**只回写改过的行（其余行/其余页的真值仍来自磁盘）。
#
# ⚠ 这一族的失效形态**全是静默毁数据**，一个都不抛异常：
#   · 建库时拿**一页**去建 -> 静默切出一个缺九成内容的库，回执说"索引已重建"；
#   · 保存时拿**一页**去整份覆盖 -> texts.jsonl 变成一页，**没有副本**；
#   · 这一片与列表**不是同一页** -> 用户按框里看到的内容点「删」/「追加」/
#     「保存本页改动」，改动落在一段他以为不是那里的行上；
#   · 回写用的是"保存那一刻现算的区间"（不是**画出去时**那一片）->
#     写进另一段行，而那两个区间都合法（只有"第 i 行配第 j 行"错了）。
#   屏幕上每一处都在说"成了"。所以判据锚结构关系（哪个状态字段决定走哪条路 /
#   谁在谁之前 / 取数走哪个函数），不锚"某某字符串出现过"。
echo "=== 正文框装一片 + 按行回写接线守卫（P1-a/P1-b）==="; sh tools/run_rag_doc_preview_guard.sh
# 上面那个守卫的自测：拿 12 份把真源码就地打桩的来源（这一片固定第 1 页 /
# 不画在 renderRagRows 里 / 切片态建库不走磁盘 / 保存时不按行回写 /
# 回写那一跳改用 saveTexts / ragDocEntries 在切片态不拒绝 / 拒绝在取数之后 /
# 切走时的「保存」落回 runRagSave / 切态里把框锁死 / 没记下画出去的那一片 /
# loadRagDoc 又拼整库 / 切库不清记录）跑它，断言"该红的红、且点得出是哪一条"。
echo "=== 正文框装一片 + 按行回写守卫自测（对着打桩源码跑）==="; sh tools/run_rag_doc_preview_guard_tests.sh
# 「改内容、行数不变 -> 重算改那一行向量」的接线守卫（ISSUE #228 的新特性）。
# 用户原话：*"改内容、行数不变 → 重算改那一行。"*
#
# 这一族的失效形态**全是静默的**：
#   · 指纹把模型那一半现算成"当前配置" -> 用户改过批大小/换过模型时，
#     刚写完的索引立刻被判"需重建"（指向一个他没动过的字段），
#     而这次修法本身就是为了免重建（自相矛盾）；
#   · 只重算向量、不重算指纹里的 `x` -> 索引与正文仍不同源，"免重建"没发生；
#   · 编码失败/索引不同源时不退回"只写正文" -> 用户的改动一个字节都没落盘，
#     而回执说得像成功了；
#   · 不先确认"索引此刻可用"就编码 -> 一份索引里两种语义，检索分数像随机，不报错。
echo "=== 改字重算向量接线守卫 ==="; sh tools/run_rag_row_reembed_guard.sh
# 上面那个守卫的自测：拿 12 份把真源码就地打桩的来源（指纹现算当前配置 /
# 指纹不换 x / 编码失败不退回 / 退回走 saveTexts / 不先确认索引可用 /
# 可用判据不与检索同源 / 半份索引也放行 / 正常路径不走两侧一起写 /
# 先写正文后写索引 / 不校验索引齐整与同源 / 指纹用旧正文 hash /
# 拿整库去 buildIndex）跑它，断言"该红的红、且点得出是哪一条"。
echo "=== 改字重算向量守卫自测（对着打桩源码跑）==="; sh tools/run_rag_row_reembed_guard_tests.sh
# 「新建库 + 目标库取当前选中」的接线守卫（ISSUE #259 的 ②③）。这一族
# 的坏法**每一步都不报错**，而后果是"用户的库被静默改了/多了一个"：
#   · 目标库取错（.txt 从前拿**文件名**当库名）-> 想往现有库追加一篇文档
#     只能先把文件改名；更坏的是**静默建出第二个库**（他以为在往 A 追加，
#     磁盘上多了个"文件名"库，A 里一条没多，而屏幕上两句提示都说"成了"）；
#   · 新建库**不清正文框** -> 下一步「建库/重建」把**上一个库的正文**
#     建成这个新库（建库成功、条数正常、搜到的是别的内容）；
#   · 新建库不立即落盘 -> 库名只活在内存里，用户一离开就没了（而界面上
#     什么都没说）；未添加内容不丢弃 -> 留下一个点不动、删不掉的幽灵行。
# 判据一律锚结构关系（谁调用谁 / 谁设置哪个状态 / 在哪个函数体内），
# 且两条"扫字符串"的判据都**剥注释**（解释这件事的注释里必然带着那些字）。
echo "=== 新建库接线守卫 ==="; python3 tools/rag_new_kb/check_wiring.py \
    app/src/main/java/com/xiaowan/localinference/EngineActivity.kt \
    app/src/main/java/com/xiaowan/localinference/RagArchive.kt
# 上面那个守卫的自测：拿 8 份**打桩源码树**（目标库退回取文件名 /
# 又凭空兜底一个名字 / 新建库不清正文框 / 新建库去建空目录 /
# 空内容也落盘 / 落盘后 pending 不清 / 重复点同一个库又走新建 /
# 丢弃 pending 时碰磁盘）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。桩全部来自这一族真正会写错的写法。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 新建库守卫自测（对着打桩源码跑）==="; sh tools/run_rag_new_kb_guard_tests.sh
# 「显示名 <-> 目录名」那张表的**纯逻辑**（RagKbName，ISSUE #259）。
# 用户第 ③ 条：*"选中库，然后在库名输入的地方更换新名字，保存的话会
# 直接生成一个新库，改名不生效，我认为应该直接生效，且不用重建索引"*。
# 这一层的失效形态全是**静默**的：映射丢了像"改名没生效"、映射串了像
# "改名把另一个库改没了"、唯一性没挡像"内容进了另一个库" ——
# 三种都不崩、不报错、日志干净，只有断言能钉住。
# ⚠ 它不是零依赖单测：RagKbName 借用 RagIndexStore.sanitizeName
#   （两处各写一套净化规则就是下一次分家），所以连那个文件一起编。
echo "=== 显示名/目录名映射纯逻辑单测 ==="; sh tools/run_rag_kb_name_tests.sh
# 「改名 / 删库 / 按钮名」的接线守卫（ISSUE #259 三条诉求）。
# 改名走成"新建" = 磁盘上多一个空库、旧库连着索引躺着，而屏幕上
# 两句提示都说"成了"（用户报的就是这个）；删库做成 drop = 留下一行
# 0 条的幽灵（列表里还有它，而没有任何按钮对它有效）；按钮名不改成
# "建索引/重建索引" = 用户以为"建库"是建一个库。判据锚结构关系
# （改名这条路上**不许**出现 buildIndex / loadRagDoc / mkdirs），
# 且"旧按钮名绝迹"那条**剥注释**（解释这次改名的注释里必然带着旧名）。
echo "=== 改名/删库/按钮名接线守卫 ==="; python3 tools/rag_kb_name/check_wiring.py \
    app/src/main/java/com/xiaowan/localinference/EngineActivity.kt \
    app/src/main/java/com/xiaowan/localinference/RagIndexStore.kt \
    app/src/main/java/com/xiaowan/localinference/RagKbName.kt
# 上面那个守卫的自测：拿 10 份**打桩源码树**（改名走成新建 / 改名顺手
# 重建索引 / 改名不同步 currentKb / 唯一性没挡 / 删库退回 drop /
# 删库不清当前库 / 按钮名改回旧名 / 列表退回目录名 /
# 大括号计数在 lambda 处收工 / 不剥注释）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩⑨⑩ 钉的是**守卫自己**（一个假红、一个恒红），因为那两种失效
# 的终点都是"判据被静音"，而静音的判据比没有更坏。
echo "=== 改名/删库/按钮名守卫自测（对着打桩源码跑）==="; sh tools/run_rag_kb_name_guard_tests.sh
# 「知识库状态」（存在 / 待重建 / 未落盘）与「库名框提交」的接线守卫
# （ISSUE #259 第二批 —— 用户一次报的五条）。
# 五条同根：这一页上有**五套互不相同**的"这个库是什么状态"判据，
# 而屏幕上只有一个答案。静默形态各自具体：
#   · 状态行读库名框的字面值 -> 用户改了名还没提交，屏幕上已经在说
#     "新建了库 test1 / 该库不存在"（他报的第 ①④ 条）；
#   · 导入的目标库不是"弹选择器之前冻结的那份" -> 选择器让库名框失焦，
#     覆盖掉 currentKb，那份文档落进了另一个库；
#   · 「未落盘」与「待重建」合成一句 -> 用户在一个 0 条的库上把每个按钮
#     点一遍，而屏幕上每个字都说"点重建"（他问的第 ③ 条）；
#   · 删除按钮按"有没有索引 / 有没有正文文件"判 -> 一个"建库被杀进程
#     打断"的库进得了列表，而**每个按钮都是灰的**（他报的第 ⑤ 条）；
#   · 切到知识库页无条件重画状态行 -> 建索引的进度提示被擦掉
#     （他报的第 ④ 条："我切换页面后，再回到知识库页面，建索引提示丢失"）。
# 判据一律锚结构关系，且"两把尺子同源"那一条**逐字**比对
# （isPresent 与 list 的列库判据分家了，症状就是列表里点得动、
#  按钮说"磁盘上没有这个库"）。
echo "=== 知识库状态接线守卫 ==="; sh tools/run_rag_kb_state_guard.sh
# 上面那个守卫的自测：拿 12 份**打桩源码树**（状态行读库名框 /
# 状态行自己看 texts.jsonl / 导入不冻结目标库 / 确认框读此刻的 currentKb /
# 未落盘与待重建合成一句 / pending 行不去重 / 删库按钮退回 hasTexts /
# 删索引丢掉返回值 / 删库按索引判存在 / 切页覆盖建索引提示 /
# 两把尺子分家 / 大括号计数在 lambda 处收工）跑上面那个守卫。
# 桩⑫ 钉的是**守卫自己**（`list()` 是表达式体函数，朴素大括号计数会在
# 第一个 lambda 的 `}` 处收工，判据恒假）—— 假红的终点是判据被静音。
echo "=== 知识库状态守卫自测（对着打桩源码跑）==="; sh tools/run_rag_kb_state_guard_tests.sh
# 「知识库续编」：把"建索引中途被杀 -> 下次从头跑"改成"接着编"。
# 三处接口任一处置空，行为**与改动前逐字相同**（照样从头跑）而**不报错**：
#   · 准入：`load` 要把"前缀连续的半份"判成 `partial`（可续），
#     而不是沿用旧口径 `none`（旧口径把可续掐死在门口）；
#   · 续编：`buildIndex` 要**先**读回前缀、从缺口接着编；
#     读回那一步若排在编码循环之后（或压根没有），与从头跑没有区别；
#   · 作废闸：指纹任一项变了要整档作废**且说出来**（`lastDiff`），
#     否则续编会拿"上次那套配置的向量"配"这次这套"（排序像随机、日志干净）。
# 另钉：定期落盘**只在没有失败时**发生（有失败时向量不许往前挤）。
echo "=== 知识库续编接线守卫 ==="; sh tools/run_rag_resume_guard.sh
# 上面那个守卫的自测：拿 7 份打桩源码（load 又用旧口径判 none /
# 半份判成 valid / loadPrefix 排在 embedBatch 之后 / 编码循环从 0 起 /
# 定期落盘不分失败与否 / 续不上时静默从头 / 可续判在正文已改之后）
# 跑上面那个守卫，断言"该红的红、且失败时点得出是哪一条"。
echo "=== 知识库续编守卫自测（对着打桩源码跑）==="; sh tools/run_rag_resume_guard_tests.sh
# 「这个库现在是什么状态」那一句话的纯逻辑单测（RagResumeUi）。
# 本轮第一次有了两种答案：**可续**（只补缺口）与**要重建**（整份重来，
# 大库差几十分钟）—— 合成一句「待重建」时用户分不清，而那是他判断
# "点这一下要等多久"的唯一依据。另钉：绝不报"预计剩余"（假精确）。
echo "=== 知识库状态措辞纯逻辑单测 ==="; sh tools/run_rag_resume_tests.sh
# 「这个库现在是什么状态」的分类（isPending / onDisk / deletable）纯逻辑单测。
# 用户五条里有三条的根都是这里的分类错：三态少一档 = 屏幕上说了另一件事；
# deletable 与 onDisk 分家 = 列表里点得动、按钮说没有它。
# 全都不崩、不报错、日志干净 —— 只有断言能钉住。
echo "=== 知识库状态分类纯逻辑单测 ==="; sh tools/run_rag_kb_state_tests.sh
# 「暗色模式」（需求：默认关闭，可选跟随系统）。本仓库的界面是**全代码装配**的
# （没有一个 XML 布局），颜色逐处 `setTextColor(0xFF...)` 硬编码 —— 于是暗色有
# 三个都必须成立、且**失效时都不报错**的落点：
#   A. `setTheme()` 必须早于 `super.onCreate()`（放到之后 = 页面还是亮的，
#      只有对话框暗了）。两行换个位置，编译过、点得动、不报错。
#   B. 取色必须**只有一个来源**（`pal`）。漏一处 = 某一小块在暗色下看不见，
#      而那处只在用户切到暗色时才出现（冷启动、截图、单测全都看不到）。
#   C. 档位必须真的接到设置页（有控件、有三档、点的结果落盘）——
#      "档位存在、但没有入口"与"没做这个功能"在用户侧完全同形。
# 另有一条**只有行为测试能抓**的：`ThemePref.clear` 若被改成"把默认值写进
# 键里"，行为上看起来一样而 `isSet` 恒为 true（"还原默认"这句话在读数上不成立）。
# 三份都是纯 sh + python3（行为测试依赖 kotlinc/JDK，缺则 SKIP）。
echo "=== 暗色模式接线守卫 ==="; sh tools/run_dark_mode_guard.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（setTheme 排在 super 之后 /
# 取色点又写回字面量 / 外壳只压一次色 / 暗色那列漏字段 / 单选组没挂进设置页 /
# 改档位不落盘 / 无条件重建 / 还原写默认值而不是删键 / 纯逻辑层引入 import android）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 暗色模式接线守卫自测（对着打桩源码跑）==="; sh tools/run_dark_mode_guard_tests.sh
# 上面那份钉的是"结构没改回去"，证明不了"这三档判定在**边界输入**下真的对"。
# 而 `ThemePref` 里带 `Context` 的三个函数各自只有一行，档位判定全是纯函数
# （零 android 依赖）—— 所以这里直接编**真实现**跑，不是复刻。
# 依赖 kotlinc/JDK，缺则 SKIP。
echo "=== 暗色模式档位行为测试（无工具链则 SKIP）==="; REQUIRED=0 sh tools/run_dark_mode_tests.sh
echo "=== 聊天页气泡/删除守卫 ==="; sh tools/run_chat_bubble_guard.sh
# 守卫自身的自测：拿 5 份**打桩源码树**（流式退回重建整屏 / 长按闭包捕获下标 /
# 删一条后不作废 KV / 删除路径夹带 capChars 裁剪 / 删空历史时报错退出）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 聊天页气泡守卫自测（对着打桩源码跑）==="; sh tools/run_chat_bubble_guard_tests.sh
# 聊天页「重新生成」的接线守卫（Issue #163 的 ③）。它的坏法与 ①② 不同族：
# **每一步都不报错，但用户的答案会不翼而飞**。四条零症状的不变量：
#   · 截断历史必须早于建"正在生成"的占位气泡 —— 先建气泡再截断时，参数非法
#     或 native 拒绝启动就会让"我的答案凭空消失"（两行换个位置，症状只在
#     参数填错时才出现，最容易踩）；
#   · 重跑边界必须是**用户长按的那一条**，取错丢掉的是好几条且不可恢复；
#   · 「停止」后的半截回答不落盘、只活在页面上，只能靠 `streamingAppended`
#     这个显式标志区分"重写"与"接着往下写"（猜一定会漂）；
#   · 作废 KV 账本的位置要钉在"从哪条开始重跑"那里 —— 搬进 startGeneration
#     会让"接着往下写"白白多作废一次，这一条**没有任何可观测症状**。
# 判据一律锚结构关系（调用关系 / 参数来源 / **行序** / 在哪个函数体内），
# 不锚存在性：顺序类不变量只有行序判据才不是恒真的。
echo "=== 聊天页重新生成守卫 ==="; sh tools/run_chat_regen_guard.sh
# 守卫自身的自测：拿 10 份**打桩源码树**（先置 generating 再建气泡 /
# startGeneration 内部也截断 / 重跑边界退化成末条 / 作废搬进 startGeneration /
# 半截回答不标记 / 停止路径把半截落盘 / 长按 user 时不补回问话 /
# 菜单不给末条限制 / 截断路径夹带裁剪 / 半截判据混进别的条件）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自 ③ 真正会写错的写法，不是编出来的。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 聊天页重新生成守卫自测（对着打桩源码跑）==="; sh tools/run_chat_regen_guard_tests.sh
# S1「会话裁剪保险丝」的接线守卫。两条零症状的坏法：
#   · **顺手啃掉本轮提问** —— 上一版从最新往前截、保头，超限时先把当轮提问
#     的尾部截掉，症状是"我贴的代码它只看到一半，还答得头头是道"，
#     与模型抽风完全同形，无法归因；
#   · **截到空条就停** —— `minOf(over, cur.length)` 不 continue 的写法下，
#     空的历史条让 cut=0、over 一点没减，静默少截，把超限留给下一轮，
#     而下一轮的末条已经换人了。
# 判据锚**结构关系**：条数上限是具名常量且"40 条 = 20 轮"同处 /
# 截断循环**不含末条** / 空条显式 continue 而不是被 minOf 顺手越过 /
# 挪位记账的行序 / 两级分别留痕。"某个函数存在"在这类判据里恒真。
echo "=== 会话裁剪保险丝守卫 ==="; sh tools/run_session_cap_guard.sh
# 守卫自身的自测：拿 8 份**打桩源码树**（条数退回裸魔数 40 / 常量不具名 /
# 截断方向改回保头啃末条 / 空条不 continue / 挪位记账挪到 cut 判断之前 /
# 留痕退回一句"已裁剪" / 复刻测试与实现脱钩 / 末条不变量退化成"非空"）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 会话裁剪守卫自测（对着打桩源码跑）==="; sh tools/run_session_cap_guard_tests.sh
# 上面那份钉的是"结构没改回去"，证明不了"这套结构在**边界输入**下真的对"。
# 而 `capChars` 签名带 android Context（只为打日志）、宿主下没有可用实现，
# 所以把控制流逐字复刻成纯逻辑跑，并对**上一版**（从最新往前截）与
# **上上版**（`size > 2` 当先决条件）同时跑一遍做对照：
# 同一组输入下它们必须暴露"啃掉当轮提问""只剩 2 条完全失效"。
# 依赖 kotlinc/JDK，缺则 SKIP。
echo "=== 会话裁剪行为复刻测试（无工具链则 SKIP）==="; REQUIRED=0 sh tools/run_session_cap_tests.sh
# S2「旧单会话迁移」的接线守卫。它只有一个坏法，但是本仓库最讨厌的那类
# —— **错得不响**：会话数撞上 50 上限时迁移被挡（`createNoMigrate` 返回 null），
# 旧写法是 `?: return` **静默返回**，旧文件留在原地。用户看到的是
# 「我的旧聊天记录不见了」，真相却是"被上限挡住了 —— 删掉几个旧会话就好"。
# 两者处置相反（一个是数据没了，一个是删几个会话），所以结局必须分得出来。
# 四条判据缺一条这个修复就退化成"看着像修了"：
#   ① 结局可区分（塌成 Unit/Boolean 就再也分不出"没有旧文件"与"被挡住"）；
#   ② NO_LEGACY 不许出声（绝大多数用户的常态，出声 = 每次冷启动都报一次
#      "迁移失败"，用户被吵到会去删数据），BLOCKED_BY_LIMIT 必须出声；
#   ③ 结局只在一处认领（currentId 里也调它；读取路径也顺手报一次，
#      会出现"删掉几个会话后提示自己消失又冒出来"，用户没法判断迁没迁）；
#   ④ 失败时不许删旧文件（`legacy.delete()` 是唯一不可逆的一步 ——
#      判据锚"delete 在失败判据**之后**"，不是"文件里出现过 delete"）。
# 判据锚结构关系（谁调用谁 / 参数从哪儿来 / **行序** / 在哪个函数体内），
# 不锚存在性：一个从不被认领的枚举与没有它等价。纯静态断言，不依赖任何工具链。
echo "=== 会话迁移守卫 ==="; sh tools/run_session_migrate_guard.sh
# 守卫自身的自测：拿 10 份**打桩源码树**（撞上限被当成写入失败 / 结局塌成
# Boolean / 上限与写入失败合成一格 / 落盘失败不判就删旧文件 /
# 删旧文件挪到失败判据之前 / 调用方吞掉结局不分支 / 没有旧文件也报"迁移失败" /
# 提示退回 Toast 弹一次 / 读取路径也认领结局 / 提示与标题分开刷 /
# 失败不变量退化成"文件非空"）跑上面那个守卫，断言"该红的红、该绿的绿、
# 且失败时点得出是哪一条"。桩全部来自真正会写错的写法。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 会话迁移守卫自测（对着打桩源码跑）==="; sh tools/run_session_migrate_guard_tests.sh
# 上面那份钉的是"结构没改回去"，证明不了"这套结构在**边界输入**下真的对"。
# 而 `migrateIfNeeded` 与 `createNoMigrate` 都带 `android.content.Context`
# （宿主下 android.jar 里是抛 `Stub!` 的空壳），所以把**结局的判定**逐字复刻
# 成纯逻辑，用临时目录当 filesDir 跑：49/50/51 三个边界点 / 失败时旧文件
# 逐字节不变 / 四格互不相等 / 并对照旧写法"三种结局塌成同一个静默返回"。
# 依赖 kotlinc/JDK，缺则 SKIP。
echo "=== 会话迁移行为复刻测试（无工具链则 SKIP）==="; REQUIRED=0 sh tools/run_session_migrate_tests.sh
# S3「会话上限：从个数换成总字符」的接线守卫。这一步是**唯一改用户可见行为**
# 的一条（会话数不再被 50 拦人），每个坏法都能"看着像修了"：
#   · **软提示线参与拒绝** —— 把 MAX_SESSIONS_WARN 塞进 decide，就退回成
#     "用个数硬卡"，用户攒了几百个短会话时又被 200 卡住，而他占用并不大；
#   · **闸门挂到读路径上** —— 在 list/load 里也判配额，会让已超限的老库
#     **打不开也删不掉**（死锁）。闸门只许在新增路径上；
#   · **导入整批一起判** —— 尾部一条超限就把前面能收的也全丢（用户看到
#     "什么都没进来"）。必须按预算从前往后一条条收；
#   · **判据写死在 UI 里** —— decide/acceptCount 的逻辑若在 EngineActivity
#     里手写一遍，改上限时两处会漂移（提示说 2M、实际按 1M 拒）。
# 判据锚结构关系（谁调用谁 / 在哪个函数体内），不锚存在性：一个从不被
# decide 用到的常量与没有它等价。纯静态断言，不依赖任何工具链。
echo "=== 会话配额守卫 ==="; sh tools/run_session_quota_guard.sh
# 守卫自身的自测：拿 7 份**打桩源码树**（软提示线掺进 decide / 闸门挂到
# list 上 / 导入整批一起判 / create 退回比个数 / UI 写死 2000000 /
# SessionQuota 偷偷 import android / acceptCount 跳过收不下的）跑上面那个守卫，
# 断言"该红的红、该绿的绿、且失败时点得出是哪一条"。桩全部来自真正会写错的写法。
echo "=== 会话配额守卫自测（对着打桩源码跑）==="; sh tools/run_session_quota_guard_tests.sh
# 上面两份钉的是"结构没改回去"，证明不了"这套算术在**边界输入**下真的对"。
# 而配额的本体就是算术：`已用 + 本次 == 上限` 必须**收下**（闭区间，差一就拒
# 会让用户对着刚好放得下的文件反复重试）；批量能收的条数要按从前往后算，
# 且遇到第一条收不下的就**停**（跳过它继续试后面会让结果随文件顺序变）。
# `SessionQuota` 是纯逻辑（无 android 依赖），直接用**真实现**断言。
# 依赖 kotlinc/JDK，缺则 SKIP。
echo "=== 会话配额行为测试（无工具链则 SKIP）==="; REQUIRED=0 sh tools/run_session_quota_tests.sh
# S4「会话列表：搜索 + 按时间批量清理」的接线守卫。S3 把上限从「个数」
# 换成「正文总字符」之后，会话数不再被拦 —— 用户面对两三百条会话，
# 而会话管理只有一个 `AlertDialog.setItems`：没有搜索、没有批量删。
# **上限放开了，用户却没有出路**。S4 补这条出路，三条坏法都"错得不响"：
#   · **搜索渗进删除取数** —— 搜索框只该决定"屏幕上显示哪几行"。一旦
#     删除/导出也走筛过的那一份，用户在搜索状态下删掉的是"看起来只有
#     三条"里的，而他其它会话在同一次里被误伤（症状是"我的会话不见了"，
#     与手动删同形）；
#   · **批量删默认勾好了候选** —— 预勾选 = 用户按下"删除"时以为在清垃圾，
#     实际带走的是他 30 天前认真写的那一条。默认必须**一个都不勾**；
#   · **删完不作废 KV 账本** —— 这一条**没有任何可观测的症状**
#     （复用的判据是逐 token 比前缀，分叉了自然不复用），只是那一轮
#     模型会拿着别人的上下文接着往下写。看不见的收益，也就看不见的丢失。
# 判据锚结构关系（谁调用谁 / 参数从哪儿来 / 在哪个函数体内），不锚存在性。
# 「还原默认设置」必须先问一句（ISSUE #108：用户报"加个确认弹窗，避免误触"）。
# 这个按钮一次性覆盖**整页**参数（ctx/gpu/线程数/采样/惩罚/mmap/repack 三档
# /端口/局域网开关），就在面板最底下紧挨着那些分组，而它**没有撤销**：
# 用户调过的值不落任何"上一版"记录，覆盖之后只能凭记忆重填。
# 同一族的破坏性动作（删会话 / 批量清理 / 导入落盘 / 切库丢草稿）都走确认框，
# 这里没有理由例外。这一族的假绿特别多：「有 AlertDialog.Builder」恒真
# （本文件有十几处）、「出现了 setPositiveButton」恒真、「按钮名叫还原默认设置」
# 恒真（它一直都在，正是它没有闸）。所以判据锚**次序与可达性**：
#   ① 按钮回调里**只允许**调确认方法（参数覆盖内联在回调里 = 弹窗弹在事后）；
#   ② 参数覆盖只有唯一写点 restoreDefaultParams()，且只被确认框的
#      setPositiveButton 那一句引用；
#   ③ 取消是 `null`（不是"取消也走一遍还原"）；
#   ④ "没有撤销 / 生效时机 / 作用范围"这三句必须在框里 ——
#      只说"确定要还原吗"的框，用户第二次误触时会闭眼点。
echo "=== 还原默认设置确认框守卫（ISSUE #108）==="; sh tools/run_reset_default_guard.sh
echo "=== 还原默认设置确认框守卫自测（对着打桩源码跑）==="; sh tools/run_reset_default_guard_tests.sh
echo "=== 会话列表清理守卫 ==="; sh tools/run_session_cleanup_guard.sh
# 守卫自身的自测：拿 8 份**打桩源码树**（候选不重取全库 / 批量删预勾选 /
# 删完不作废 KV / olderThan 改读系统时钟 / 点击退回下标换算 /
# 空集合也放行 / 天数换算各处重算 / SessionCleanup 偷偷 import android）
# 跑上面那个守卫，断言"该红的红、该绿的绿、且失败时点得出是哪一条"。
# 桩全部来自 S4 真正会写错的写法。纯 sh + python3 标准库，不依赖工具链。
echo "=== 会话列表清理守卫自测（对着打桩源码跑）==="; sh tools/run_session_cleanup_guard_tests.sh
# 上面两份钉的是"结构没改回去"，证明不了"这几条口径在**边界输入**下
# 真的对"。而这里的难点全在边界：「多少天算旧」是一条**闭区间**线
# （`updatedAt` 恰好等于 `now - days` 的那一条算旧，差一就把它留下来，
# 同一批会话连续两次打开表现不一致）；`updatedAt <= 0` 必须算旧，
# 否则"清理 90 天"永远清不掉；空关键字 = **不筛**；一个都没勾就不许删。
# `SessionCleanup` 是纯逻辑（无 android 依赖），直接用**真实现**断言。
# 依赖 kotlinc/JDK/android.jar，缺则 SKIP。
echo "=== 会话列表清理行为测试（无工具链则 SKIP）==="; REQUIRED=0 sh tools/run_session_cleanup_tests.sh
# 「角色卡（V1）」的接线守卫。这一版**代码量很小**，但它有三条
# "错得不响"的口径，而它们恰恰是当初决定"要做 V1"的全部理由：
#   · **切卡不作废 KV 账本** —— `system` 在 pos 0，换了它前缀命中长度
#     就是 0。不复用是**对**的，但那几 MB 白占着，而且用户不知道
#     "点一下卡片 = 整段历史重算"（症状只是"手机烫了"，与"模型就是慢"同形）；
#   · **会话只存卡片 id、不存内容快照** —— 卡片一删，那个会话的人设
#     **静默没了**：它照旧能打开、照旧能聊、照旧 200，只是"回答怪怪的"。
#     这是整件事里唯一不可归因的一条，也是"存快照"这个设计的全部理由；
#   · **改卡片顺手去改所有会话** —— 用户按下「保存修改」时并不知道
#     自己正在改 N 个会话，而那是不可恢复的。
# 判据一律锚**函数体 / 行序 / 参数来源**，不锚"某某函数存在" ——
# 一个从不被调用的 resetKvCache 与没有它等价。纯静态断言。
echo "=== 角色卡（V1）守卫 ==="; sh tools/run_role_card_guard.sh
# 上面那份的自测：9 份就地改坏的真源码（切卡不作废 KV / 按 id 现取
# system / 改卡片顺手改会话 / 写盘不走 tmp+rename / 读盘把 .tmp 当库 /
# 不挡住框架那次选中 / 坏卡静默丢 / 超限静默截断 / 保存卡片不说明影响面），
# 断言"该红的红、指得出是哪一条、还原后全绿"。
echo "=== 角色卡（V1）守卫自测（对着打桩源码跑）==="; sh tools/run_role_card_guard_tests.sh
# 上面两份钉的是"结构没改回去"，证明不了"这几条口径在**边界输入**下
# 真的对"。而这一族的难点全在边界，且**全都没有症状**：快照优先
# （卡片删掉之后会话还能拿出那段文本）、`match` 比**内容**而不是
# "id 还在"（只比 id 会把"改了文案"判成 OK）、读盘尽力而为但三类毛病
# 分得开、上限是闭区间、写盘"全成或全不成"。
# `RoleCard` / `RoleCardStore.parse|encode` 是纯逻辑，用**真实现**断言；
# 落盘那一半（带 Context）用临时目录当 filesDir 复刻。
# ⚠ classpath 顺序是判据的一部分：android.jar 里的 org.json 是抛
# `Stub!` 的空壳，排在 json.jar 之前会**赢**，于是"任何卡片库都读不出来"。
echo "=== 角色卡（V1）行为测试（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_role_card_tests.sh
# 守卫自身的自测：拿 9 份**打桩源码树**（渲染循环里逐行问 prefs / 取数挪回主线程 /
# modelsDir 退回 getExternalFilesDir 直传 / 折叠读改写回到锁外 / noteNoise 判与设分离 /
# init 退回 @Synchronized / onDestroy 不关 ioExecutor / ExternalModel.add 自己拼
# getExternalFilesDir / rows 退回逐键 getString）跑上面那个守卫，断言"该红的红、
# 该绿的绿、且失败时点得出是哪一条"。桩全部来自本轮真正的旧写法。
# 纯 sh + python3 标准库，不依赖工具链。
echo "=== UI/IO 守卫自测（对着打桩源码跑）==="; sh tools/run_ui_io_guard_tests.sh
# 「选中」与「驻留内存」是两件事（Issue #228）：模型状态的接线守卫。
# 用户加载了 a、又想切 b、点了列表里的 b 但**还没加载**时，内存里仍是 a，
# 而状态行/列表标记此前都在说 b —— 显示与现场**相反**，且没有任何读数纠正他。
# 根因是三个字段谁都不代表"在内存里"：`modelFile`（选中）、
# `HttpApi.currentModel`（协议别名）都会在没加载的情况下就变，
# 唯一代表驻留的是 `LlmEngine.loadedPath`。
# 判据锚**取数来源**与**两态可分辨**，不锚存在性：
#   ① 驻留判据只能取 loadedPath（不许拿 modelFile / currentModel 顶替）；
#   ② 状态行与列表必须同时报两态，且两者不同时看得出来；
#   ③ 驻留值变了必须重画（加载 / 卸载 / **服务侧卸载**三条路径都覆盖 ——
#      最后一条本页任何回调都不会被触发，只能靠轮询发现，最容易漏）；
#   ④ 列表的两个标记不得互相顶替（选中态曾把驻留态挤掉，就是本 issue 的本体）。
# 纯静态断言，不依赖任何工具链。
echo "=== 模型状态（选中 vs 驻留）守卫 ==="; sh tools/run_model_state_guard.sh
# 守卫自身的自测：拿 6 份**打桩源码树**（状态行退回只报选中 / 行标只由选中态
# 决定 / 驻留判据改问 modelFile / 轮询不比较驻留值 / 卸载后不重画 /
# **把旧写法只写进注释**（必须全绿））跑上面那个守卫，断言"该红的红、
# 该绿的绿、且失败时点得出是哪一条"。桩①②直接来自本 issue 修复前的原文，
# 桩⑥是刻意设计的对照：判据锚到注释上时它会红。
# 桩是"就地改真源码 + 改完还原"，不维护副本；纯 sh + python3 标准库。
echo "=== 模型状态守卫自测（对着打桩源码跑）==="; sh tools/run_model_state_guard_tests.sh
# 上面两张守的只是**对话**那一份。设置页里还有**第二张模型表**（嵌入模型），
# 它是另写的、没跟着改 —— 于是对话侧全绿、用户看到的仍是反话
# （"我选了 b、内存里还是 a"被画成"嵌入模型：b（768）"，而检索用的是 a）。
# 判据与对话侧逐条同构，另加一条**跨表一致性**：两张表的行标措辞必须同形，
# 否则用户没法把两张表放在一起读。纯静态断言，不依赖任何工具链。
echo "=== 嵌入状态（选中 vs 驻留）守卫 ==="; sh tools/run_embed_state_guard.sh
echo "=== 嵌入状态守卫自测（对着打桩源码跑）==="; sh tools/run_embed_state_guard_tests.sh
# 上面两张守卫钉的是"结构没改回去"，证明不了"这套结构在**并发**与 **null**
# 下真的对"。这一份在宿主上跑行为复刻：I-4 用**真四线程**跑出对账实数
# （修复后偏差 0.0000%、旧写法 +0.97%，方向是"多报"），I-2 / I-3 / I-5
# 的对象带 Context / Activity（宿主下是抛 `Stub!` 的空壳），改用同形判据复刻。
# 每组都同时跑"修复后"与"旧写法"两份，旧写法必须在同形输入下暴露问题。
# 依赖 kotlinc/JDK/android.jar，缺失时 REQUIRED=0 退化为 SKIP。
echo "=== UI/IO 行为复刻测试（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_ui_io_tests.sh
# 结构化输出（response_format）：请求侧解析/校验的离线单测。
# 专钉三类**静默**失效：
#   · 不带 response_format 时必须解析成"无约束"且不产生任何 native 调用 ——
#     否则所有既有客户端的输出分布被悄悄改掉，而没有任何测试会变红；
#   · 拼错的 type（json_shema）必须 400，不能被当成"没给"静默忽略；
#   · json_object 必须折成空串（不是 null），否则这个类型静默失效。
# 依赖 kotlinc/JDK/android.jar/json.jar，缺失时 REQUIRED=0 退化为 SKIP。
echo "=== 结构化输出请求侧单测（无工具链则 SKIP）==="
REQUIRED=0 sh tools/run_json_schema_tests.sh
# 结构化输出的**接线**守卫。上面那份只证明"解析得对"，证明不了
#   · schema 为 null 时**不挂** grammar 采样器（挂了就改了所有既有请求的分布）；
#   · grammar 排在所有选择器**之前**（之后等于不生效）；
#   · 取 GBNF / 编译 GBNF 两跳各自就地 catch（库对不支持的 schema 是抛异常，
#     穿过 JNI 帧 = SIGABRT，与"带 tools 必闪退"同款成因）；
#   · 转换失败必须**降级**、绝不让请求失败；
#   · Kotlin / C++ 形参个数一致（不一致 = 运行时 UnsatisfiedLinkError）；
#   · 两个生成端点都接上了（漏一个 = 那个端点静默不生效）；
#   · 模板与生成后缀由**渲染侧透传**（不对齐 = grammar 的起点与模型实际续写
#     的位置不是同一处，真机表现是 content 里多一段生成标记、数组只剩 [ ]）；
#   · 「未知」与「确认没有」分开判（折错方向是同一种故障、相反的方向）。
# 全是静态断言，不依赖工具链。
echo "=== 结构化输出接线守卫 ==="; sh tools/run_schema_sampler_guard.sh
# 守卫自身的自测：拿 12 份**打桩源码树**（无条件挂 grammar / 去掉某一跳的
# catch / 静默降级 / grammar 排在选择器之后 / 只接一个端点 / 形参漂移 /
# json_object 折成 null / 判据恒真化 / 生成后缀写死成 true / 模板写死成空串 /
# 「确认没有」折成 null）跑上面那个守卫，断言
# "该红的红、该绿的绿、且失败时点得出是哪一条"。
# 为什么必须有：判据全是 grep 存在性 + 行号比较，两类都容易写成恒真/恒假。
# 本自测在写的过程中**真抓到 2 处弱断言**（桩②：函数里有两处 catch，
# 只判"有没有 catch"会把删掉 templates_apply 那处的故障放过去；
# 桩④：只 grep 探针行、漏了 jlog，删掉日志照样绿），已改成
# "贴着那一跳判 catch" 与 "探针 + 日志两段式"。
# 桩是"就地改真源码 + 改完还原"，不维护副本。
echo "=== 结构化输出守卫自测（对着打桩源码跑）==="; sh tools/run_schema_sampler_guard_tests.sh
# llama_jni.cpp **本体**没有离线编译判据（要 NDK）—— `0.9.86` 那轮的编译错误
# （两个 static 函数缺前置声明）就是从这个缺口漏过去的。这里退一步：
# 把与 response_format 相关的两个函数体**逐字抽出来**交给 g++ 做真语法检查，
# 至少挡住"签名/形参表与声明不一致""函数体内语法错"这两类。
# 缺宿主编译器时 REQUIRED=0 SKIP（与其它脚本同源）。
#
# ⚠ `0.9.97` 那轮的教训：脚本**在** CI 里，但 `REQUIRED=0` 让它在缺 g++
# 时退化成 SKIP —— 而那轮 `llama_jni.cpp` 真有一个类型错
#（`strlen(gbnfFinal)`，而 `gbnfFinal` 是 `std::string`），
# 于是"编不过"这件事在整条流水线上**没有任何一处会变红**。
# 本镜像有 g++（见上面几行 `sh tools/run_abi_tests.sh` 都真的跑了），
# 所以这里不再退化：缺编译器 = 拦下，不许 SKIP。
echo "=== JNI 结构化输出函数语法检查（缺 g++ 则拦下）==="
REQUIRED=1 sh tools/run_jni_schema_syntax.sh

# 静态顺序守卫（模块 S）：上面那条是"抽取几个函数体 + 自造桩"，
# 只覆盖被抽到的那几个 —— 0.9.120 之后坏掉的 `classify_mmap_devices`
# / `pin_devices_to_cpu`（调用了定义在下方的 `split_desc_append`，
# 又没有前置声明）都不在抽取范围里，所以它全绿而源码根本编不过。
# 这一条换条路：补两份只声明不定义的宿主存根（jni.h / android/log.h），
# 让 **llama_jni.cpp 本体**过一遍 -fsyntax-only，并另有一条纯行号判据
# 把「首次使用早于声明/定义」点出来（违规时能报函数名与行号）。
#
# ⚠ 缺编译器时**不退化**：本镜像有 g++/clang++，而"源码能不能编过"
#   正是这条守卫存在的唯一理由，SKIP 等于把盲区原样留着。
echo "=== JNI 静态顺序 / 本体语法守卫（模块 S）==="
REQUIRED=1 sh tools/run_static_order_guard.sh
echo "=== 静态顺序守卫自测（对着打桩源码跑）==="
sh tools/run_static_order_guard_tests.sh

echo "=== 编 APK ==="
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
gradle :app:assembleDebug --no-daemon

APK=app/build/outputs/apk/debug/app-debug.apk
ls -l $APK

echo "=== 每个 llmjni 变体的 STL 阵营（期望 __ndk1>0 且 __cxx11=0）==="
for v in base dotprod i8mm hexagon; do
  unzip -o -q $APK lib/arm64-v8a/libllmjni_$v.so -d /tmp/x
  printf "llmjni_%s: " $v
  python3 tools/check_abi.py /tmp/x/lib/arm64-v8a/libllmjni_$v.so
done

echo "=== 产物内自检 ==="
unzip -o -q $APK lib/arm64-v8a/libllmjni_base.so -d /tmp/y
SO=/tmp/y/lib/arm64-v8a/libllmjni_base.so
# 注意：ABI 自检的提示文案是 snprintf 的格式串，strings 只能查到格式串骨架，
# 不要拿完整句子来 grep（上一版就是因此误报 MISS）。
for s in nativeProbeInit nativeProbeMark "common_chat_templates_apply" \
         "self-check failed" "abi] "; do
  if strings $SO | grep -qF "$s"; then echo "  OK   $s"; else echo "  MISS $s"; fi
done
if strings $SO | grep -q "__cxx11"; then echo "  !! 出现 __cxx11，ABI 混用"; exit 1; else echo "  OK   无 __cxx11"; fi

echo "=== 收拢 APK ==="
mkdir -p /workspace/dist
cp $APK /workspace/dist/app-debug.apk
sha256sum /workspace/dist/app-debug.apk
ls -l /workspace/dist/

# 光 cp 到这里，构建一结束容器就没了 —— 日志写着"已存档"、用户却拿不到文件。
# 这一步把它挂到 Issue 上，换一个能点的下载地址。没有 Issue 上下文（纯 push）就跳过。
# ⚠ 这一段在 4f20160（"stage 脚本挪出 .cnb.yml"）时丢过一次：那次挪动照的是
#   更早的一份正文，"上传到 Issue"这几行是在它之后加的。而构建照旧全绿 ——
#   少一步不报错，所以这行接线得有人钉着（见 tools/run_prune_assets_guard.sh）。
if [ -n "$CNB_ISSUE_IID" ]; then
  echo "=== 把 APK 挂到 Issue #$CNB_ISSUE_IID ==="
  APK=/workspace/dist/app-debug.apk sh scripts/upload-apk-to-issue.sh
else
  echo "无 CNB_ISSUE_IID，跳过附件上传（产物仅存在于本次构建容器内，构建结束即销毁）"
fi

