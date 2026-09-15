# Third-Party Licenses

本项目（MIT，见 [LICENSE](LICENSE)）在运行时随 APK 分发下列第三方组件，其版权与许可条件
由各自作者持有，列于此以满足 MIT 及各上游的署名要求。

---

## 1. cui-llama.rn — MIT License

- **用途**：Android arm64 预编译的 `librnllama_*.so` 与配套头文件（`app/src/main/cpp/include/`）。
- **来源**：<https://github.com/Vali-98/cui-llama.rn>
- **版本**：v1.12.2（打包自 llama.cpp / ggml commit `d620bae92`）
- **落库位置**：`vendor/cui-llama.rn-v1.12.2/`（含 `SHA256SUMS` 与来源说明）
- **改动**：无。二进制未做任何改写（早期尝试过 ELF 补丁，已回滚并作为教训记录）。
- **版权**：Copyright (c) 2023 Jhen-Jie Hong

```
MIT License

Copyright (c) 2023 Jhen-Jie Hong

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. llama.cpp / ggml — MIT License

- **用途**：上述预编译库内含的推理引擎与 GGML 后端框架本体（含 Hexagon NPU 后端实现）；
  头文件亦源自该 commit。
- **来源**：<https://github.com/ggml-org/llama.cpp>
- **版本**：commit `d620bae92`（经 cui-llama.rn v1.12.2 间接引入）
- **版权**：Copyright (c) 2023-2026 The ggml authors

```
MIT License

Copyright (c) 2023-2026 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. Hexagon DSP skeleton（`app/src/main/assets/ggml-hexagon/`）— MIT（经 cui-llama.rn 分发）

- **文件**：`libggml-htp-v69.so` / `-v73` / `-v75` / `-v79` / `-v81`（按 SoC 架构代际选择，共 3.1 MB）
- **用途**：llama.cpp `ggml-hexagon` 后端在 DSP 侧执行的 skeleton，经 FastRPC
  （`libcdsprpc.so`，由**设备系统提供、本项目不打包**）加载进 Hexagon NPU。

**性质认定（已取证，非推测）**：这五个文件是 **llama.cpp 自身的构建产物**，不是高通专有二进制。

- 文件内残留的源路径全部是 `cpp/ggml-hexagon/htp/*.c`（llama.cpp MIT 源码）；
- 5 个文件的 `DT_NEEDED` 只有 `libc.so` 与 `libgcc.so`；`qurt_*` / `HAP_*`（Hexagon RTOS 与框架 API）共 110 处引用（单文件 21–23 处）**全部是 `UND` 未定义符号**，由设备 DSP 侧在运行时解析——也就是说文件里**没有内嵌任何专有运行时**，静态链入的只有 `-lc -lgcc` 与 Hexagon SDK 的启动桩；
- llama.cpp 官方 `docs/backend/snapdragon/README.md` 就把 `libggml-htp-v*.so` 列为
  `cmake --install` 的标准输出文件（其示例输出列出 v73/v75/v79/v81；**v69 未出现在该示例里**，
  但下文的 npm 逐字节校验对 v69 同样成立，故一并按上游产物对待）。

**获取渠道**：npm 包 `cui-llama.rn@1.12.2` → `bin/arm64-v8a/libggml-htp-v*.so`，
即上文第 1 项的同一作者（Jhen-Jie Hong）官方发布。该包 `package.json` 声明
`license: MIT`，且包内自带 `LICENSE`（`Copyright (c) 2023 Jhen-Jie Hong`）。

**完整性校验**：本仓库五个文件与该 npm 包**逐字节一致**，SHA-256 已存档于
[`vendor/cui-llama.rn-v1.12.2/HTP_ASSETS.sha256`](vendor/cui-llama.rn-v1.12.2/HTP_ASSETS.sha256)。
比对基准是 **npm tarball**（与第 1 项的 GitHub release asset 属不同渠道，内容不同）。
tarball 为 `https://registry.npmjs.org/cui-llama.rn/-/cui-llama.rn-1.12.2.tgz`，10696237 字节，`sha256 = da08a83ac222d8ca740b1094d0fc52d2c778af1a51df78d520fae97e617ef52a`，可一条命令复算：

```sh
npm pack cui-llama.rn@1.12.2 && sha256sum cui-llama.rn-1.12.2.tgz \
  && tar -xzf cui-llama.rn-1.12.2.tgz package/bin/arm64-v8a \
  && sha256sum package/bin/arm64-v8a/libggml-htp-v*.so
```

**与 ChatterUI 的关系（说明，非许可依据）**：这批文件最初是从 ChatterUI
（<https://github.com/Vali-98/ChatterUI>，AGPL-3.0）的 APK 中提取的。经查证，ChatterUI 本身
也不构建它们——其 `copyhtp.plugin.js` 只是把上述 npm 包的 `bin/arm64-v8a/libggml-htp*.so`
原样复制到 `assets/ggml-hexagon/`。因此本项目与 ChatterUI **不共享任何代码或构建产物来源**，
许可链条直接落在 MIT 的 cui-llama.rn / llama.cpp 上，**不触发 AGPL 的衍生作品条款**
（既未使用 ChatterUI 的代码，其 AGPL 也不覆盖它自己只是转发的第三方 MIT 二进制）。

> 注：这些文件最初引入时未记录出处，该缺口现已通过哈希比对补齐（见上文 SHA-256 存档）。

---

## 4. 构建期工具链（不随 APK 分发）

Android Gradle Plugin、Kotlin、LLVM/libc++（NDK r27）、CMake、GitHub Actions 镜像等，
仅在 CI 与本地构建时使用，产物不进入分发包；各自许可见其项目仓库。

**Qualcomm Hexagon SDK（易被漏掉的一项）**：llama.cpp 的 `docs/backend/snapdragon/README.md`
明确说明构建这些 DSP 库需要容器镜像里预装的 **Hexagon SDK**，而该 SDK 受高通专有许可约束。
本仓库**不使用该 SDK、不重新编译**上文第 3 项的 5 个 `.so`，只复用上游（MIT）已经编译发布的产物，
因此不受其条款传染；但**任何想自行重编 HTP skeleton 的人都必须另行接受高通的 SDK 许可条款**，
不要把本仓库当作"可自由重编"的授权来源。

---

## 5. 模型权重

**本仓库与 APK 都不包含任何模型权重。** 用户自行下载的 `.gguf` 受其自身许可证约束
（例如各类模型的社区/接受条款），不由本项目的 MIT 覆盖。
