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
    mainClass = "com.renxin.gya.c02.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}