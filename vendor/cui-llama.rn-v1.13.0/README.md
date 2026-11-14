# cui-llama.rn 预编译 Android native 库

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/Vali-98/cui-llama.rn （npm 包名 `cui-llama.rn`） |
| 版本 | 1.13.0 |
| 产物 | release asset `llama-rn-android-jni-libs.tar.gz`（50,260,579 字节） |
| 下载 | https://github.com/Vali-98/cui-llama.rn/releases/download/v1.13.0/llama-rn-android-jni-libs.tar.gz |
| tarball sha256 | `92a1c3d415a63f11ff4581815c4825667e10e7bffb958ec27dcc9572cd947b26` |
| 取用范围 | 仅 `arm64-v8a/` 的 4 个 librnllama 变体（本仓库只发 arm64） |
| 完整性 | 与 tarball 内 `.llama-rn.sha256` 一致，**唯一例外**是 hexagon_opencl 变体打了本仓库的 OpenCL 补丁（见下节，可复现） |
| 内置 llama.cpp | commit `8642541`（build 11960） |

本目录的 `.so` 来自上游两个发布渠道，两者的内容与体积都不同，校验清单也不同：

| 文件 | 来源渠道 | 校验清单 |
|---|---|---|
| `arm64-v8a/librnllama*.so`（4 个 CPU 变体） | GitHub release asset（上表 tar.gz） | `.llama-rn.sha256` |
| `libggml-htp-v*.so`（6 个 DSP skeleton，已装入 assets） | npm 包 `cui-llama.rn-1.13.0.tgz`（12338308 字节，`sha256=61d0682b0ff0e7fa10e62ad36aa35b54f2d0ce1dc3ff5acb92e44f1a48a3de99`） | 本目录 [`HTP_ASSETS.sha256`](HTP_ASSETS.sha256) |

`HTP_ASSETS.sha256` 对标 npm 包，不适用于 GitHub release asset；复算时按上表选对渠道。

保持上游原样是硬要求：这些库按指令集分档编译，改动其二进制会让运行时探测失去意义。机型选择由代码负责，见
`app/src/main/cpp/CMakeLists.txt` 与 `LlmEngine.loadNative()`。

## 本目录内 4 个变体（arm64-v8a）

| 文件 | 用途 | DT_NEEDED 额外项 | SDOT | SMMLA |
|---|---|---|---|---|
| `librnllama.so` | ARMv8.0 基线（骁龙835/845/865/870，A76/A77 无 dotprod） | — | 0 | 0 |
| `librnllama_v8_2_dotprod.so` | ARMv8.1/8.2 dotprod（天玑1000/8100/8000、Exynos 2100、A55~A78） | — | 1405 | 0 |
| `librnllama_v8_2_dotprod_i8mm.so` | ARMv8.4 i8mm（A79/A710/X2、天玑9000、骁龙8 Gen1+） | — | 1245 | 444 |
| `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so` | 上一条 + OpenCL GPU + Hexagon NPU | `libcdsprpc.so`、`libOpenCL.so` | 1245 | 444 |

未取用的变体：`librnllama_v8.so`、`librnllama_v8_2.so`（无量化点积加速，纯冗余）、
`librnllama_v8_2_i8mm.so`（现实 CPU 不会缺 dotprod 而只有 i8mm）、
以及 `neon` / `wpasm` / `x86*` 等非 arm64-android 构建。

## 与 Kotlin 的接线方式

- Gradle 把本目录加为额外 `jniLibs` 源（见 `app/build.gradle.kts` 的
  `sourceSets.main.jniLibs.srcDirs`），并给 CMake 传 `-DRN_LIBS_DIR`。
- CMake 为每个变体各编一个 JNI 壳 `libllmjni_<tag>.so`（tag = hexagon/i8mm/dotprod/base），
  因为 4 个变体的 SONAME 互不相同，单个壳无法同时满足。
- 运行时用 `libcpufeat.so`（`getauxval(AT_HWCAP/AT_HWCAP2)`）探测 dotprod/i8mm 后
  `System.loadLibrary("llmjni_$tag")`；只有探测到高通 SoC 才优先加载 hexagon 变体。

## 校验

```sh
cd vendor/cui-llama.rn-v1.13.0 && sha256sum -c SHA256SUMS
```

变体的量化指令差异可自助复核（无需信任本文件的数字）：

```sh
NDK=$ANDROID_NDK_HOME   # 或任意 llvm-objdump
for f in arm64-v8a/*.so; do
  echo -n "$(basename $f): sdot="; $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump -d --no-show-raw-insn $f | grep -cE '\bsd[ot]\b' | tr -d '\n'
  echo -n " smmla="; $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump -d --no-show-raw-insn $f | grep -cE '\bsmmla\b'
done
```

基线 `librnllama.so` 应为 `sdot=0 smmla=0`。含 `smmla>0` 的变体**不能**下发到无 ARMv8.4
的机型（骁龙865、天玑1000 等会 SIGILL），这正是 `LlmEngine.loadNative()` 存在的原因。

## 为什么按机型链多个变体，而不是改二进制

只改 `DT_NEEDED`（去掉 `libcdsprpc.so`、`libOpenCL.so`）并弱化 `HAP_debug*`、
`@OPENCL_*` 这类符号引用，hexagon 变体确实能 `dlopen` 成功；但指令集在编译期定死，
含 smmla 的指令在无 ARMv8.4 的 CPU 上仍然 SIGILL，改链接元数据不改变这一点。
因此这里按扩展等级各链一个 `libllmjni_<tag>`、运行时探测后选一个，二进制本身不修改。

## 本仓库携带的上游补丁

`vendor/patches/` 记录本仓库需要的上游源码修改（当前只有一条）：

| 文件 | 内容 |
|---|---|
| `cui-llama.rn-1.13.0-0001-opencl-coopk-q4k-scale-min.patch` | Adreno 上 CoopK Q4_K 窄批 GEMM 的 6-bit 尺度/最小值符号扩展 |

补丁修的是「预填充尾块 n=2..4 时 logits 全 NaN」的**真正根因**：kernel 里
`(q[i] >> 6) << 4` 被 Adreno 驱动编译成符号扩展字节加载 + 算术右移，任何
`>= 0x80` 的打包字节都会污染 min 项。补丁改成先掩码再右移——与同目录
`gemm_noshuffle_q4_k_f32.cl` / `gemm_noshuffle_q4_k_q8_1_dp4a.cl` /
`gemv_noshuffle_q4_k_f32.cl` 里既有的写法一致（只有新加的 coopk 文件写成了
有问题的那种）。

### 二进制落地方式

`ggml-opencl` 的 kernel 源码是以字符串形式编进 `.so`、运行时才由驱动编译的，
所以只改 `arm64-v8a/librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so` 里那两行
字符串就能生效，**不需要重编整个库，也不掉 HTP**：

| 项 | 值 |
|---|---|
| 原厂 sha256（release asset） | `b744290e32f447b301769ae54225600ce3d5dc9b3ca8a714f11becf30599d24f` |
| 本仓库 sha256 | `feb16972e045f03b8246c588290294ac5daa7cf97d703fd9030c45cad5b05155` |
| 差异 | 126 字节，全部落在 `get_scale_min_k4_c()` 的两行源码内（offset 3326500 / 3326578，各 69 字节） |
| 未变 | `DT_NEEDED`（含 `libcdsprpc.so` / `libOpenCL.so`）、android-21、strip、符号表、HTP 资产 |

复现：

```sh
# 从上游 release 取原厂 .so（sha256 需等于上表第一行）
python3 tools/patch_vendor_coopk.py 原厂.so vendor/cui-llama.rn-v1.13.0/arm64-v8a/librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so
python3 tools/patch_vendor_coopk.py --check arm64-v8a/librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so
```

等上游把 `vendor/patches` 里那条补丁合入并发布新版本后，直接换回原厂 `.so`、
更新 `SHA256SUMS` 与本表即可，脚本和补丁都不再需要。

### 验证

- 独立 OpenCL 复算（dumped 权重/激活，M=10240 K=2560 N=2，sub-buffer 与普通
  buffer 两种绑定）：全 20480 输出 vs float64 参考 `maxdiff ≈ 7.9e-8`；未修时
  首行 0.345359（参考 0.0843903）。
- 真机 Adreno 830，Spark-X2.6-4B Q4_K_M，CoopK 保持开启：
  `tools/device/compare_tail_matrix.py --lengths 1-40,63-70` → **PASS 48 / FAIL 0**
  （nan=0、inf=0、unused=0）。
- MiniCPM5-2B i1-Q4_K_M：len=2/3/4 nan=0，top-1 与 CPU 一致。

因此 App 侧的 `GGML_OPENCL_COOPK_DP4A=0` 已经删掉，投机验证批重新走 CoopK。
