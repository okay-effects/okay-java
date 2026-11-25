plugins {
    `java-library`
}

group = "io.github.okay-effects"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // okay-kernel-api, okay-java's one dependency: released in okay-kernel's Maven repository on GitHub Pages
    maven("https://okay-effects.github.io/okay-kernel/maven")
}

// sugar by annotations (README.md): an annotation processor that only GENERATES okay.rows code — an effect's data
// from a plain interface (@Effect), the operations of a class's row with no `up` (@Uses). Nothing is transformed,
// nothing of javac's own is touched; the generated sources are ordinary okay.rows, readable and steppable.
// A consumer: compileOnly + annotationProcessor on this module (javac runs no processor by default since JDK 23)
dependencies {
    api(rootProject)
    testAnnotationProcessor(sourceSets.main.get().output)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
}
