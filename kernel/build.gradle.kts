// okay-java AS AN IMPLEMENTATION OF OKAY-KERNEL'S API: its bridge, OnJava — optional, apart from the library, which
// stays of no dependency
plugins {
    `java-library`
    `maven-publish`
}

group = "dev.okay"
version = "0.1.0-SNAPSHOT"

base {
    archivesName = "okay-java-kernel"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    // okay-kernel-api: published by okay-kernel to ~/.m2
    mavenLocal()
    mavenCentral()
}

publishing {
    publications {
        create<MavenPublication>("okayJavaKernel") {
            artifactId = "okay-java-kernel"
            from(components["java"])
        }
    }
}

dependencies {
    api(project(":"))
    api("dev.okay:okay-kernel-api:0.1.0-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    // constant stack proved: a small stack makes a regression fail loudly
    jvmArgs("-Xss512k")
}
