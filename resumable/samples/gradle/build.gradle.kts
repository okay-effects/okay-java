plugins {
    application
    id("io.github.okay-effects.resumable")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://okay-effects.github.io/okay-kernel/maven") // okay-kernel-api, okay-java's one dependency
    maven("https://okay-effects.github.io/okay/maven") // okay-audit, the check of forbid
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// effectful code that reaches a handler or an effect's implementation other than to install it, or an API of the
// strict list (the network, files, processes, reflection, ..), or the ambient way of doing what an effect of
// okay.rows.std does (pure: state, reader, writer, clock, fresh, async, io; and nested-run): refused at build time
okayResumable {
    guard = "error"
    forbid = "strict, pure, nested-run"
}

application {
    mainClass = "sample.Shop"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
