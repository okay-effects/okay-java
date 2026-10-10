plugins {
    `java-library`
    `maven-publish`
}

group = "dev.okay"
version = "0.2.0-SNAPSHOT"

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

// a library of one dependency, okay-kernel's API (interfaces and a few records, of no dependency of its own): okay-java
// implements it (docs/kernel.md). Published (`./gradlew publishToMavenLocal`, to ~/.m2) for okay-kernel's TCK
publishing {
    publications {
        create<MavenPublication>("okayJava") {
            from(components["java"])
        }
    }
}

dependencies {
    api("dev.okay:okay-kernel-api:0.2.0-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

tasks.test {
    useJUnitPlatform()
    // the stack-safety tests prove constant stack: a small stack makes a regression fail loudly
    jvmArgs("-Xss512k")
}

// JMH without a plugin: a source set of benchmarks, the annotation processor generating the harness
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// ./gradlew bench --args='MonadsBenchmark -prof gc' [-PbenchJdk=26]: on the toolchain's JDK, or another — the same
// as okay-core's, to compare with it
tasks.register<JavaExec>("bench") {
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    (project.findProperty("benchJdk") as String?)?.let { jdk ->
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
    }
}
