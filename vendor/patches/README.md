# 本仓库的上游补丁

| 文件 | 用途 |
|---|---|
| `llama.cpp-opencl-coopk-q4k-scale-min.patch` | 纯 diff，路径相对 **llama.cpp 仓库根**（`ggml/src/...`），可直接 `git apply`；这是要发给上游的那一份 |
| `cui-llama.rn-1.13.0-0001-opencl-coopk-q4k-scale-min.patch` | 同一改动的 vendored 路径版本（`vendor/llama.cpp/...`），供本仓库自用 |
| `UPSTREAM-PR.md` | 提给谁、PR 标题/描述、提交前自检、合入后本仓库的收尾步骤 |

## Adreno OpenCL：CoopK Q4_K 窄批 GEMM 的 6-bit 尺度/最小值符号扩展

上游：cui-llama.rn v1.13.0（内置 llama.cpp commit 8642541）
文件：vendor/llama.cpp/ggml/src/ggml-opencl/kernels/gemm_coopk_q4_k_q8_1_dp4a.cl
落点：cui-llama.rn 版在 cui-llama.rn 源码树根 `patch -p1`；llama.cpp 版在
llama.cpp 仓库根 `git apply`（该文件在 master 上与 vendored 副本逐字节相同，
由 #30741 引入）

现象
----
Adreno 830 上，预填充最后一块 n=2..4（`ggml_cl_coopk_dp4a_narrow_on()` 的
启用区间）时整张 logits 变成 NaN，采样兜底选中词表未使用槽位 131071，
正文出现乱码。n=1 与 n=5..32 全部正常。

根因
----
`get_scale_min_k4_c()` 的 j>=4 分支用 `(q[(j-4)*stride] >> 6) << 4` 取打包
字节的高 2 位。Adreno 驱动把 `uchar` 的 `>>6` 编译成**符号扩展字节加载 +
算术右移**，任何 >= 0x80 的字节因此得到 -1；`-1 << 4` 再截断回 `uchar` 就是
`0xF0`（不是预期的 2-bit 值左移 4 位），于是每个 super-block 的后 4 个
32-block 的 min 项被污染。

实测（Spark-X2.6-4B Q4_K_M，layer 2 ffn_gate，row 0）：
内核读到 mn=249 / 255，真值 41 / 63；sv 与 dot 全部正确，只有 min 项错。
同一份源码在普通 buffer 上结果正确，参数绑到 `clCreateSubBuffer`（origin 0
也算）时必定复现——驱动对 sub-buffer 入参走另一条 codegen 路径。

修复
----
先掩码再移位：

```c
*d = (q[(j + 4)*stride] & mask_d4) | ((q[(j - 4)*stride] & mask_hi2) >> 2);
*m = ((q[(j + 4)*stride] >> 4) & mask_d4) | ((q[(j)*stride] & mask_hi2) >> 2);
```

这与同目录 `gemm_noshuffle_q4_k_f32.cl` / `gemm_noshuffle_q4_k_q8_1_dp4a.cl` /
`gemv_noshuffle_q4_k_f32.cl` 里既有的写法逐字一致——只有 #30741 新加的 coopk
文件用了有问题的 `(q[i] >> 6) << 4`。掩码把高 2 位按到确定的低位，同时给
`>> 4` 那半也补上 `mask_d4`，不再依赖驱动对 `uchar` 提升的处理。

已验证的等价写法与坑
--------------------
- 掩码后右移（本补丁，与同目录其它 kernel 一致）：两种绑定都正确。
- 掩码后右移但保留 `(uint)` 强转（本仓库上一版 .so 用的写法）：同样正确，
  只是与同目录风格不一致。
- `vload4` 读取后取分量：两种模式都错。
- 先存入 `const uint` 临时变量再移位：两种模式都错。
- 用字面量 `& 0xC0u` 代替 `mask_hi2` 形参：普通 buffer 正确，sub-buffer 仍然错。

验证
----
1. 独立 OpenCL 复算（dumped 权重/激活，M=10240 K=2560 N=2）：
   全 20480 输出 vs float64 参考 maxdiff ≈ 7.9e-8；未修复时首行 0.345359
   （参考 0.0843903）。
2. 真机（Adreno 830，Spark-X2.6-4B Q4_K_M，CoopK 保持开启）：
   `tools/device/compare_tail_matrix.py --lengths 1-40,63-70` → PASS 48 / FAIL 0，
   nan=0、inf=0、unused=0。
3. MiniCPM5-2B i1-Q4_K_M：len=2/3/4 全部 nan=0，top-1 与 CPU 一致。

注意
----
`ggml-opencl` 的 kernel 源码是**编译进 .so 的字符串、运行时由驱动编译**，
所以本补丁只在重新构建 librnllama 时生效；改用上游预编译 .so 时不会自动带上。
