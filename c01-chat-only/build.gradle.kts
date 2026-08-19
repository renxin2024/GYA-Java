plugins {
    application
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

application {
    mainClass = "cn.renxinblog.c01.Chat"
}

// 让 ./gradlew :c01-chat-only:run 把终端 stdin 转发给 Java 进程
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}