plugins {
    `java-library`
}

group = "dev.okay"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
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
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// ./gradlew bench --args='EffBenchmark -prof gc'
tasks.register<JavaExec>("bench") {
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
}
