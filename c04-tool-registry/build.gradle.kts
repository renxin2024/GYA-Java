plugins {
    application
}

application {
    mainClass = "cn.renxinblog.c04.ToolRegistry"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
