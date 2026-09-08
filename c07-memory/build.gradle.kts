plugins {
    application
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
}

application {
    mainClass = "cn.renxinblog.c07.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
