plugins {
    application
}

group = "cn.renxinblog"
version = "1.0.0"

repositories {
    // 国内镜像源（阿里云 Maven 镜像，加速依赖下载；无需科学上网）
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

application {
    mainClass = "cn.renxinblog.c02.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}