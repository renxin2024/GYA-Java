plugins {
    application
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

application {
    mainClass = "cn.renxinblog.c06.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
