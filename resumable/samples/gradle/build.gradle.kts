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

// effectful code that reaches a handler or an effect's implementation other than to install it, or an API of the
// strict list (the network, files, processes, reflection, ..), or the ambient way of doing what an effect of
// okay.rows.std does (pure: state, reader, writer, clock, fresh, async, io, a nested run): refused at build time
okayResumable {
    guard = "error"
    forbid = "strict, pure"
}

application {
    mainClass = "sample.Shop"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
