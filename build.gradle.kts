// 根工程：统一配置（仓库镜像 + Java 21 toolchain）
// 子模块在各自 build.gradle.kts 里声明 application 插件、依赖、mainClass。

allprojects {
    repositories {
        // 国内镜像源（阿里云 Maven，加速依赖下载；无需科学上网）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
    }
}