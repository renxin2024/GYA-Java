plugins {
    application
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    implementation("org.slf4j:slf4j-api:2.0.18")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

application {
    mainClass = "cn.renxinblog.c12.Main"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnitPlatform()
}
