plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "io.github.okay-effects"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

// the plugin runs in Gradle's own JVM, whatever it is: compiled for 17. The transformation itself runs in a JVM of
// the project's toolchain (25 or later: java.lang.classfile)
tasks.withType<JavaCompile> {
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options"))
}
tasks.withType<Javadoc> { (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet") }

gradlePlugin {
    plugins {
        create("resumable") {
            id = "io.github.okay-effects.resumable"
            implementationClass = "okay.resumable.gradle.ResumablePlugin"
            displayName = "okay-java-resumable"
            description = "direct-style methods made resumable by okay-java-resumable's bytecode transformation"
        }
    }
}

// the plugin adds okay-java-resumable of its own version
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

// THE RELEASE (../../RELEASING.md): the plugin (io.github.okay-effects:okay-java-resumable-gradle-plugin) and its
// marker (io.github.okay-effects.resumable:io.github.okay-effects.resumable.gradle.plugin), written into okay-java's
// build/pages-maven beside the libraries: `./gradlew release` from okay-java's root
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
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
    }
    repositories {
        maven {
            name = "pages"
            url = uri(layout.projectDirectory.dir("../../build/pages-maven"))
        }
    }
}
