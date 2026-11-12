plugins {
    `java-gradle-plugin`
}

group = "dev.okay"
version = "0.2.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// the plugin runs in Gradle's own JVM, whatever it is: compiled for 17. The transformation itself runs in a JVM of
// the project's toolchain (25 or later: java.lang.classfile)
tasks.withType<JavaCompile> {
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-options"))
}

gradlePlugin {
    plugins {
        create("resumable") {
            id = "dev.okay.resumable"
            implementationClass = "okay.resumable.gradle.ResumablePlugin"
        }
    }
}

// the plugin adds okay-java-resumable of its own version
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
