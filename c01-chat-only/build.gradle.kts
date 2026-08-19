plugins {
    application
}

group = "com.renxin.gya"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

application {
    mainClass = "com.renxin.gya.c01.Chat"
}

// 让 gradle run 把终端 stdin 转发给 Java 进程（否则管道输入会丢失）
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}