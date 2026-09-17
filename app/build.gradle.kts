plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.xiaowan.localinference"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xiaowan.localinference"
        // targetSdk 有意保持 28：不再 exec 应用数据目录里的外部二进制，
        // 所以"必须 <=28"的 W^X 硬性理由已不成立（见 MainActivity 顶部说明）；
        // 但低 targetSdk 下的通知/前台服务/权限行为在现有机型上表现一致且已长期验证，
        // 上调会牵动 FGS 类型声明与 POST_NOTIFICATIONS 运行时权限，需要单独回归，暂不动。
        // 侧载安装不受商店 targetSdk 门槛约束。
        minSdk = 26
        targetSdk = 28
        // 版本规则：versionName 恒为 0.9.<versionCode>，两者在同一次提交里一起改。
        // 本文件注释里请勿写出 versionName 紧跟等号与引号的形式——发布器按该形式定位
        // 并改写版本号，全文命中数不等于 1 会直接中止发布。
        versionCode = 69
        versionName = "0.9.69"
        ndk {
            // llama.cpp rnllama 构建仅面向 arm64；Hexagon HTP/OpenCL 均在此 ABI
            abiFilters += listOf("arm64-v8a")
        }
    }
    sourceSets {
        getByName("main") {
            // 4 个预编译 librnllama 变体从 vendor 目录直接打包（srcDir 是"追加"语义，
            // 不影响默认 jniLibs 与 CMake 产物）；srcDirs += 在 AGP 里会报 Val cannot be reassigned。
            jniLibs.srcDir("$rootDir/vendor/cui-llama.rn-v1.12.2")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 进程内推理：llama_jni.cpp + 预编译 librnllama（jniLibs）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig.externalNativeBuild.cmake {
        arguments += listOf(
            "-DANDROID_STL=c++_static",
            // 预编译 rnllama 变体目录（见 vendor/cui-llama.rn-v1.12.2/README.md）
            "-DRN_LIBS_DIR=$rootDir/vendor/cui-llama.rn-v1.12.2"
        )
        cppFlags += "-std=c++17"
    }
    packagingOptions {
        jniLibs {
            // 预编译 rnllama 与 CMake 产物共存
            useLegacyPackaging = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
// 零第三方依赖：全部使用 Android 平台 API，最大化兼容性与构建速度
