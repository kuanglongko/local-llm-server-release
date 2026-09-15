cmake_minimum_required(VERSION 3.22.1)
# cpu_feat.c 是 C，llama_jni.cpp 是 C++
project(llmjni C CXX)

# ── 多 CPU 变体 + 运行时选择 ──────────────────────────────────────────
# 上游 cui-llama.rn 为不同 ARM 扩展等级各出一个预编译 librnllama 变体，
# 全部导出同一套 llama/ggml API（符号集合差集为空，可互相替换）：
#   hexagon  : +OpenCL GPU +Hexagon HTP，但 DT_NEEDED 含高通专有 libcdsprpc.so
#   i8mm     : ARMv8.4（SMMLA），只需 liblog/libandroid/libm/libdl/libc
#   dotprod  : ARMv8.1/8.2（SDOT），覆盖天玑1000/8100、老 Exynos 等
#   base     : ARMv8.0 基线，骁龙835~870 等无 dotprod 的机型
# 因此按机型各链一个 llmjni_<tag>（其 DT_NEEDED 指向自己的变体），Kotlin 侧
# 用 getauxval 探测后 System.loadLibrary("llmjni_$tag") 选一个，二进制本身不做修改。
# 只改 DT_NEEDED 让 hexagon 变体在低阶机型上加载成功并不可行：符号引用可弱化，
# smmla 指令却在编译期定死，无 ARMv8.4 的 CPU 仍会 SIGILL。故多链 + 运行时选择。
#
# 变体二进制随仓库提交在 vendor/ 下（Gradle 以 -DRN_LIBS_DIR 传入路径）；
# 不放 app/src/main/jniLibs，避免同一份 47MB 二进制在仓库里存两次。
if(NOT DEFINED RN_LIBS_DIR)
    set(RN_LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/cui-llama.rn-v1.12.2)
endif()

# CPU 能力探测：必须在 llmjni_* 之前可用，故独立成小库（仅依赖 libc）
add_library(cpufeat SHARED cpu_feat.c)
target_compile_options(cpufeat PRIVATE -O2)

# 用 function 而不是 foreach 遍历 "tag;lib" 列表：
# CMake 的 list 会按分号展平元素，手工拆分容易越界；显式参数最稳。
function(add_llmjni_variant TAG BASE)
    add_library(${BASE} SHARED IMPORTED)
    set_target_properties(${BASE} PROPERTIES
        IMPORTED_LOCATION ${RN_LIBS_DIR}/${ANDROID_ABI}/lib${BASE}.so)

    add_library(llmjni_${TAG} SHARED ${CMAKE_CURRENT_SOURCE_DIR}/llama_jni.cpp)
    set_target_properties(llmjni_${TAG} PROPERTIES OUTPUT_NAME llmjni_${TAG})
    target_include_directories(llmjni_${TAG} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
    target_compile_options(llmjni_${TAG} PRIVATE -O2 -fvisibility=hidden)
    target_link_libraries(llmjni_${TAG} PRIVATE ${BASE} log)
    # 只允许 llama/ggml 与 libc/liblog 这类系统符号；缺符号在 CI 就报错而不是运行时 UnsatisfiedLinkError
    target_link_options(llmjni_${TAG} PRIVATE -Wl,--no-undefined)
endfunction()

add_llmjni_variant(hexagon rnllama_v8_2_dotprod_i8mm_hexagon_opencl)
add_llmjni_variant(i8mm    rnllama_v8_2_dotprod_i8mm)
add_llmjni_variant(dotprod rnllama_v8_2_dotprod)
add_llmjni_variant(base    rnllama)
