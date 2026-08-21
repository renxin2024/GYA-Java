plugins {
    application
}

dependencies {
    implementation("io.modelcontextprotocol.sdk:mcp:2.0.0")
}

application {
    mainClass = "cn.renxinblog.c08.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
