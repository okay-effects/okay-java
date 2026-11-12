plugins {
    `java-library`
}

group = "dev.okay"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    // okay-kernel-api, okay-java's one dependency: published by okay-kernel to ~/.m2
    mavenLocal()
    mavenCentral()
}

// direct-style programs made resumable (README.md): a bytecode transformation, its runtime, the boundary into
// okay.rows. Of no dependency but okay-java
dependencies {
    api(rootProject)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial"))
}

// the module's own test classes, transformed as a consumer's would be: written to a directory of their own, which
// the tests run from instead of the compiler's
val resumableTestClasses = layout.buildDirectory.dir("resumable/test")
val transformTestClasses by tasks.registering(JavaExec::class) {
    val classes = sourceSets.test.get().output.classesDirs
    inputs.files(classes)
    inputs.files(sourceSets.main.get().output)
    outputs.dir(resumableTestClasses)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "okay.resumable.transform.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    doFirst {
        delete(resumableTestClasses)
        args(classes.files.first { it.exists() }.path, resumableTestClasses.get().asFile.path,
            sourceSets.test.get().runtimeClasspath.asPath)
    }
    // the refusal tests' class stays as javac wrote it: code built without the transformation
    doLast {
        copy {
            from(classes) { include("okay/resumable/Untransformed*.class") }
            into(resumableTestClasses)
        }
    }
}

tasks.test {
    dependsOn(transformTestClasses)
    useJUnitPlatform()
    testClassesDirs = files(resumableTestClasses)
    classpath = files(resumableTestClasses) + (classpath - sourceSets.test.get().output.classesDirs)
    // the stack-safety tests prove constant stack: a small stack makes a regression fail loudly
    jvmArgs("-Xss512k")
}

// a program in a jar built WITHOUT the transformation, everything it needs inside: `java -jar` transforms it as it
// loads, through the manifest's Launcher-Agent-Class (AgentTest)
val jarSample: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
val jarSampleJar by tasks.registering(Jar::class) {
    archiveFileName = "jar-sample.jar"
    destinationDirectory = layout.buildDirectory.dir("jar-sample")
    from(jarSample.output)
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "okay.resumable.sample.Shop",
            "Launcher-Agent-Class" to "okay.resumable.transform.Agent")
    }
}
tasks.test {
    dependsOn(jarSampleJar)
    systemProperty("okay.resumable.jarSample", jarSampleJar.get().archiveFile.get().asFile.path)
}
