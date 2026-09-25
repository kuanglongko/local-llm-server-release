// 仓库镜像配置：把国内镜像**排在前面**，mavenCentral()/google() 留作兜底。
//
// 为什么必须这么写：2026-09-21 连续三轮 CI 构建都死在 Maven Central 的
// `429 Too Many Requests` 上 —— 第一轮是一个 78KB 的 json.jar，第二轮是
// Gradle 自己解析 `:classpath` 时要下的几十个插件依赖（asm / protobuf / grpc …）。
// 两次都不是代码问题，但**两次都把 APK 构建拦下了**。
//
// 限流是共享出口 IP 的常态（CI 每次都是新容器、缓存不持久），而镜像切换是廉价的。
// 顺序有讲究：镜像放前面、官方源放后面 —— 反过来的话，镜像永远不会被用到。
// 镜像同步的是同一批坐标（阿里云 public 聚合了 central + jcenter），因此内容等价。
pluginManagement {
    repositories {
        // 阿里云：覆盖 central，同步延迟通常分钟级
        maven("https://maven.aliyun.com/repository/public")
        // 腾讯云：备用；两个都挂的概率远低于单点
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 阿里云：覆盖 central，同步延迟通常分钟级
        maven("https://maven.aliyun.com/repository/public")
        // 腾讯云：备用；两个都挂的概率远低于单点
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public")
        google()
        mavenCentral()
    }
}

rootProject.name = "LocalLlmServer"
include(":app")
