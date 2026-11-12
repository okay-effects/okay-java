plugins {
    application
    id("dev.okay.resumable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal() // okay-kernel-api, okay-java's one dependency, as okay-java's build finds it
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "sample.Shop"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
