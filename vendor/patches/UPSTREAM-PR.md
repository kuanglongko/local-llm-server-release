# 向上游提交：Adreno CoopK Q4_K 尺度/最小值符号扩展

## 提交给谁

| 对象 | 为什么 |
|---|---|
| **ggml-org/llama.cpp**（主目标） | `ggml/src/ggml-opencl/kernels/gemm_coopk_q4_k_q8_1_dp4a.cl` 在上游 master 里**逐字节相同**，缺陷就长在上游。该文件由 #30741 引入（作者 `lhez`，Qualcomm，2026-10-19），PR 里 @ 他最快。 |
| Vali-98/cui-llama.rn | 只是把 llama.cpp 整棵 vendor 进来（`scripts/patches/llama.cpp/` 里**没有**改这个文件）。上游合入后重新 vendor 就自动带上，不需要单独补丁；通知一声避免他们重新发版时漏掉即可。 |
| Qualcomm（可选但值得） | 真正的缺陷在 Adreno OpenCL 编译器：同样的写法在**普通 buffer** 上正确、在 `clCreateSubBuffer` 入参上被编成符号扩展。kernel 里加掩码只是绕开，驱动不修的话其它源码还会踩。 |

一句话：**发 llama.cpp 的 PR 是主线，Vali-98 只是下游消费者。**

## 文件

- `llama.cpp-opencl-coopk-q4k-scale-min.patch` —— 可直接 `git apply` 到 llama.cpp 仓库根目录（路径是 `ggml/src/...`），纯 diff；commit message 见下文。
- `cui-llama.rn-1.13.0-0001-...patch` —— 同一改动的 vendored 路径版本（本仓库自用）。

## 关键论据

同一目录下另外三个 Q4_K kernel 的 `get_scale_min_k4()` **早就是掩码写法**，
只有 #30741 新加的 coopk 文件写成了 `(q[i] >> 6) << 4`：

| 文件 | 行 |
|---|---|
| `ggml-opencl/kernels/gemm_noshuffle_q4_k_f32.cl` | 27-28 |
| `ggml-opencl/kernels/gemm_noshuffle_q4_k_q8_1_dp4a.cl` | 28-29 |
| `ggml-opencl/kernels/gemv_noshuffle_q4_k_f32.cl` | 37-38 |

```c
*d = (q[(j+4)*stride] & mask_d4) | ((q[(j-4)*stride] & mask_hi2) >> 2);
*m = ((q[(j+4)*stride] >> 4) & mask_d4) | ((q[j*stride] & mask_hi2) >> 2);
```

所以本补丁不是发明新写法，而是**把 coopk 文件改回同一目录已经验证过的写法**——
这一点最能说服 reviewer：不是 workaround，是与邻居代码对齐。

## 数值细节（写进 PR 时别省）

- Adreno 把 `(q[i] >> 6) << 4` 对 `uchar` 的写法编成**符号扩展字节加载 + 算术右移**。
- `q[i] = 0xA0` 时：`(int8)0xA0 >> 6 == -1`，`-1 << 4 == -16`，截断到 `uchar` 得
  **`0xF0`**（不是 `0x30`）。于是 `mn` 的高 2 位被置成 `0b11` 并保留低 4 位的
  原值——所以 `mn` 变成 `249`（`0xF9`）/ `255`（`0xFF`），而不是 ref 的 `41`（`0x29`）
  / `63`（`0x3F`）。高 2 位掩码本身是 `0x30`，不是 `0x3`；写成 `0x30` 才是对的。
- 错误只出现在 `sub_idx >= 4` 的四个 32 值子块（即每个 256 值 super-block 的后半）。
- 只有 kernel 参数是 `clCreateSubBuffer` 视图时才复现；传父 buffer + 字节偏移
  （origin 0 也一样）仍然复现，所以是 sub-buffer 的代码生成路径，不是偏移算错。

## PR 标题

```
opencl: fix Adreno sign-extension in CoopK Q4_K scale/min decode
```

## PR 描述（可直接粘贴，fence 已配平）

```markdown
On Adreno, the Q4_K CoopK GEMM returns NaN for 2-4 column batches. The cause is
not in the GEMM math but in the 6-bit scale/min decode:

    *m = (q[(j + 4)*stride] >> 4) | ((q[(j)*stride] >> 6) << 4);

The Adreno compiler turns `(q[i] >> 6) << 4` on a `uchar` into a sign-extending
byte load plus an arithmetic shift. For a packed byte `q[i] >= 0x80` that yields
`-1`, and `-1 << 4` truncated back to `uchar` is `0xF0`, not the intended
2-bit value shifted into place. The high two bits of the min term therefore
become `0b11` for the last four 32-value sub-blocks of every 256-value
super-block, and the min term is wrong (`249`/`255` instead of `41`/`63` for the
blocks measured below).

It only reproduces when the kernel argument is a `clCreateSubBuffer` view, which
is how ggml-opencl binds the q/s/d/dm weight regions here. Passing the parent
buffer plus byte offsets still reproduces it, so it is the sub-buffer codegen
path, not an offset error.

Reported on: Adreno 830, `OpenCL 3.0 Adreno(TM) 830`,
driver `OpenCL 3.0 QUALCOMM build: 0800.56.1 Compiler E031.47.18.42`,
build options `-cl-fast-relaxed-math -cl-finite-math-only -DCOOPK_NSG=4`.
Symptom: Spark-X2.6-4B Q4_K_M with 33 layers on OpenCL, the last prefill chunk
of 2-4 tokens produces all-NaN logits (131072 entries), i.e. the whole batch.

Evidence, one `ffn_gate` layer (M=10240, K=2560, N=2), instrumented kernel vs
float64 reference on the same dumped q/s/d/dm/activations:

| block | sv (kernel/ref) | mn (kernel/ref) | dot (kernel/ref) |
|---|---|---|---|
| 0 | 34 / 34 | 32 / 32 | 2576 / 2576 |
| 20 | 46 / 46 | **249 / 41** | 660 / 660 |
| 52 | 57 / 57 | **255 / 63** | -1607 / -1607 |

`sv` and `dot` are always correct; only the high 2 bits of `mn` are polluted,
and only for `sub_idx >= 4` blocks whose packed byte is >= 0x80.

Fix: mask the packed byte in `uint` before shifting. This is the same form the
other Q4_K kernels in this directory already use (`gemm_noshuffle_q4_k_f32.cl`
lines 27-28, `gemm_noshuffle_q4_k_q8_1_dp4a.cl` lines 28-29,
`gemv_noshuffle_q4_k_f32.cl` lines 37-38) - only the newer coopk file introduced
in #30741 used the `>> 6 << 4` form, so this patch aligns it with the rest:

```diff
     } else {
-        *d = (q[(j + 4)*stride] & mask_d4) | ((q[(j - 4)*stride] >> 6) << 4);
-        *m = (q[(j + 4)*stride] >>   4)    | ((q[(j    )*stride] >> 6) << 4);
+        *d = (q[(j + 4)*stride] & mask_d4) | ((q[(j - 4)*stride] & mask_hi2) >> 2);
+        *m = ((q[(j + 4)*stride] >>   4) & mask_d4) | ((q[(j    )*stride] & mask_hi2) >> 2);
     }
```

The `& mask_d4` added to the `>> 4` half is a no-op on correct hardware (the
shift already yields four bits), but it documents the field width and matches
the sibling kernels byte-for-byte.

With the fix, all 20480 outputs of the layer match the float64 reference to
7.9e-8, in both the sub-buffer and the plain-buffer binding. Unpatched, the first
element is 0.345359 instead of 0.0843903.

Alternatives that do not work on the same driver (in case someone tries to
simplify this later): reading the bytes with `vload4` and taking the component,
and storing the byte into a `const uint` temporary before shifting, are both
still miscompiled; `& 0xC0u` with a literal mask is correct only for plain
(non-sub-buffer) bindings.

Tested on device with a CPU/GPU logits matrix over prefill lengths 1-40 and
63-70 (48 lengths, chunk 32, layer 0 on CPU): 48/48 match the CPU top-1, no NaN,
no Inf. Also checked a second model (MiniCPM5-2B i1-Q4_K_M) at lengths 2-4.
```

## 建议的 commit message（当前 PR 里那条 “implementation-defined” 说法不准确）

```
opencl: mask packed bytes in CoopK Q4_K scale/min decode

get_scale_min_k4_c() extracts the two high bits of the scale/min word with
`(q[i] >> 6) << 4`. On Adreno (830, OpenCL 3.0, driver 0800.56.1 / compiler
E031.47.18.42) this is compiled into a sign-extending byte load and an
arithmetic shift when the kernel argument comes from clCreateSubBuffer. For
any packed byte >= 0x80 the result is -1, and `-1 << 4` truncated to uchar is
0xF0, so the min term of the last four 32-value sub-blocks of each 256-value
super-block is corrupted.

Mask the byte before shifting, which is the form the other Q4_K kernels in
this directory already use. Masking also makes the `>> 4` half explicitly
4-bit wide.

Fixes NaN logits for 2-4 column prefill batches on Adreno.
```

## 提交前的自检

```sh
# 1) 补丁能干净地应用到当前 master
git clone --depth 1 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp
cd /tmp/llama.cpp && git apply --check /path/to/llama.cpp-opencl-coopk-q4k-scale-min.patch

# 2) 上游那份文件没有被别处改过（本补丁的上下文依赖它逐字节一致）
git -C /tmp/llama.cpp log --oneline -1 -- ggml/src/ggml-opencl/kernels/gemm_coopk_q4_k_q8_1_dp4a.cl
```

第 2 条现在应是 `1b29621`（#30741）。如果之后有人改了这个文件、补丁打不上，按
`git apply -3` 的结果手工挪一下上下文即可，改动本身只有两行。

> `llama.cpp-opencl-coopk-q4k-scale-min.patch` 是**纯 diff**（带 `diff --git` 头，
> 没有说明文字），可以直接 `git apply`；说明文字都在本文件里。

## 合入之后本仓库要做什么

1. 升级 cui-llama.rn 到含该修复的版本；
2. 用原厂 `.so` 覆盖 `vendor/cui-llama.rn-v1.13.0/arm64-v8a/librnllama_v8_2_dotprod_i8mm_hexagon_opencl.so`，
   更新 `SHA256SUMS` 与 README 的「本仓库携带的上游补丁」一节；
3. 删掉 `vendor/patches/` 下的两条补丁与 `tools/patch_vendor_coopk.py`；
4. `llama_jni.cpp` 不需要改（已经不再落 `GGML_OPENCL_COOPK_DP4A=0`）。
