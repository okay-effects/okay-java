plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.okay-effects"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    // okay-kernel-api: released in okay-kernel's Maven repository on GitHub Pages
    maven("https://okay-effects.github.io/okay-kernel/maven")
}

// a library of one dependency, okay-kernel's API (interfaces and a few records, of no dependency of its own): okay-java
// implements it (docs/kernel.md). Released (RELEASING.md) as io.github.okay-effects:okay-java
publishing {
    publications {
        create<MavenPublication>("okayJava") {
            from(components["java"])
        }
    }
}

// THE RELEASE (RELEASING.md): every module's publication — okay-java, okay-java-resumable, okay-java-spring-boot —
// with its sources, its javadoc and the family's POM; `./gradlew publishAllPublicationsToPagesRepository` writes them
// into build/pages-maven, the Maven repository on GitHub Pages (the Gradle and Maven plugins beside them:
// `./gradlew release`)
allprojects {
    plugins.withId("java") {
        configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Javadoc> { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }
    }
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach { pom { okayPom(project.name) } }
            repositories {
                maven {
                    name = "pages"
                    url = uri(rootProject.layout.buildDirectory.dir("pages-maven"))
                }
            }
        }
    }
}

fun MavenPom.okayPom(artifact: String) {
    name = artifact
    description = "okay-java: algebraic effects for Java — effects as data, handlers, a row of effects (okay.rows); " +
        "direct-style programs made resumable (okay.resumable); Spring Boot support"
    url = "https://github.com/okay-effects/okay-java"
    licenses {
        license {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
        }
    }
    developers {
        developer {
            id = "sergey-scherbina"
            name = "Sergiy Shcherbyna"
            email = "sergey.scherbina@gmail.com"
        }
    }
    scm {
        url = "https://github.com/okay-effects/okay-java"
        connection = "scm:git:https://github.com/okay-effects/okay-java.git"
    }
}

dependencies {
    api("io.github.okay-effects:okay-kernel-api:0.1.0")
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

// okay-effects' four lanes (state, reader3, choose, binds) on the row notation, its Bench with only the package
// changed: ./gradlew rowsBench [-PbenchJdk=26]
tasks.register<JavaExec>("rowsBench") {
    classpath = jmh.runtimeClasspath
    mainClass = "okay.rows.bench.Bench"
    (project.findProperty("benchJdk") as String?)?.let { jdk ->
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(jdk) }
    }
}
