# cui-llama.rn 预编译 Android native 库

| 项 | 值 |
|---|---|
| 上游仓库 | https://github.com/Vali-98/cui-llama.rn （npm 包名 `cui-llama.rn`） |
| 版本 | 1.12.2 |
| 产物 | release asset `llama-rn-android-jni-libs.tar.gz`（42,088,338 字节） |
| 下载 | https://github.com/Vali-98/cui-llama.rn/releases/download/v1.12.2/llama-rn-android-jni-libs.tar.gz |
| 取用范围 | 仅 `arm64-v8a/` 的 4 个 librnllama 变体（本仓库只发 arm64） |
| 完整性 | 与 tarball 内 `.llama-rn.sha256` 一致；本仓库不对这些 `.so` 做任何改写 |

本目录的 `.so` 来自上游两个发布渠道，两者的内容与体积都不同，校验清单也不同：

| 文件 | 来源渠道 | 校验清单 |
|---|---|---|
| `arm64-v8a/librnllama*.so`（4 个 CPU 变体） | GitHub release asset（上表 tar.gz） | `.llama-rn.sha256` |
| `libggml-htp-v*.so`（5 个 DSP skeleton，已装入 assets） | npm 包 `cui-llama.rn-1.12.2.tgz`（10696237 字节，`sha256=da08a83ac222d8ca740b1094d0fc52d2c778af1a51df78d520fae97e617ef52a`） | 本目录 [`HTP_ASSETS.sha256`](HTP_ASSETS.sha256) |

`HTP_ASSETS.sha256` 对标 npm 包，不适用于 GitHub release asset；复算时按上表选对渠道。

保持上游原样是硬要求：这些库按指令集分档编译，改动其二进制会让运行时探测失去意义。机型选择由代码负责，见
`app/src/main/cpp/CMakeLists.txt` 与 `LlmEngine.loadNative()`。

## 本目录内 4 个变体（arm64-v8a）

| 文件 | 用途 | DT_NEEDED 额外项 | SDOT | SMMLA |
|---|---|---|---|---|
| `librnllama.so` | ARMv8.0 基线（骁龙835/845/865/870，A76/A77 无 dotprod） | — | 0 | 0 |
| `librnllama_v8_2_dotprod.so` | ARMv8.1/8.2 dotprod（天玑1000/8100/8000、Exynos 2100、A55~A78） | — | 1333 | 0 |
| `librnllama_v8_2_dotprod_i8mm.so` | ARMv8.4 i8mm（A79/A710/X2、天玑9000、骁龙8 Gen1+） | — | 1173 | 428 |
| `librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so` | 上一条 + OpenCL GPU + Hexagon NPU | `libcdsprpc.so`、`libOpenCL.so` | 1173 | 428 |

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
cd vendor/cui-llama.rn-v1.12.2 && sha256sum -c SHA256SUMS
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
