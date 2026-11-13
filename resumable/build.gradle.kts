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
// okay.rows. Of no dependency but okay-java; okay-audit (okay's okay-audit module, published by `okayAudit/publishM2`
// to ~/.m2) checks what a build forbids effectful code — an optional dependency, needed on the transformer's class
// path only when a list is named, never by a program at run time
val okayAudit = "io.github.okay-effects:okay-audit_3:0.2.0-SNAPSHOT"
dependencies {
    api(rootProject)
    compileOnly(okayAudit)
    testImplementation(okayAudit)
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// the JVM's one-shot continuations (jdk.internal.vm) and the thread's scoped-value bindings (jdk.internal.access,
// java.lang): to compile, and to run the tests with them
val loomExports = listOf("--add-exports=java.base/jdk.internal.vm=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial") + loomExports)
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
    jvmArgs(loomExports + "--add-opens=java.base/java.lang=ALL-UNNAMED")
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
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "okay.resumable.sample.Shop",
            "Launcher-Agent-Class" to "okay.resumable.transform.Agent",
            "Add-Exports" to "java.base/jdk.internal.vm java.base/jdk.internal.access",
            "Add-Opens" to "java.base/java.lang")
    }
}
tasks.test {
    dependsOn(jarSampleJar)
    // the tests count which way each operation went
    systemProperty("okay.resumable.trace", "true")
    systemProperty("okay.resumable.jarSample", jarSampleJar.get().archiveFile.get().asFile.path)
}

// THE BENCHMARK (./gradlew :okay-java-resumable:bench [--args='-f 1 ...']): the three tiers on the same programs,
// against okay.rows and okay-direct's Loom implementation, resolved from its published repository; JMH as the root
// project has it. Its classes and the test classes it uses are run transformed
repositories {
    maven("https://okay-effects.github.io/okay-direct/maven") {
        content { includeGroup("io.github.okay-effects") }
    }
}
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output +
        sourceSets.test.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
}
dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    "jmhImplementation"("io.github.okay-effects:okay-direct-loom:0.1.0")
}
tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.add("-Xlint:-processing")
}
val resumableJmhClasses = layout.buildDirectory.dir("resumable/jmh")
val transformJmhClasses by tasks.registering(JavaExec::class) {
    val classes = jmh.output.classesDirs
    dependsOn(tasks.named("jmhClasses"))
    inputs.files(classes)
    outputs.dir(resumableJmhClasses)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "okay.resumable.transform.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    doFirst {
        delete(resumableJmhClasses)
        args(classes.files.first { it.exists() }.path, resumableJmhClasses.get().asFile.path,
            jmh.runtimeClasspath.asPath)
    }
    // the untransformed twin of a transformed method, to measure what the transformation costs when nothing suspends
    doLast {
        copy {
            from(classes) { include("okay/resumable/bench/Plain.class") }
            into(resumableJmhClasses)
        }
    }
}
tasks.register<JavaExec>("bench") {
    dependsOn(transformJmhClasses, transformTestClasses)
    classpath = files(resumableJmhClasses, resumableTestClasses) +
        (jmh.runtimeClasspath - jmh.output.classesDirs - sourceSets.test.get().output.classesDirs)
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs(loomExports + "--add-opens=java.base/java.lang=ALL-UNNAMED")
}

// THE MAVEN PLUGIN AND THE MAVEN SAMPLE (./gradlew :okay-java-resumable:mavenSample): okay-java and this module
// published to a repository directory under build/ — not ~/.m2 —, with okay-kernel-api and okay-audit beside them
// (copied from ~/.m2); the plugin built by the Maven wrapper (Maven fetched under build/), deployed to that
// directory; the sample built against it, each
// Maven build with a local repository of its own under build/
apply(plugin = "maven-publish")
val samplesRepo = layout.buildDirectory.dir("maven-repo")
configure<PublishingExtension> {
    publications { create<MavenPublication>("okayJavaResumable") { from(components["java"]) } }
    repositories { maven { name = "samples"; url = uri(samplesRepo) } }
}
rootProject.configure<PublishingExtension> {
    repositories { maven { name = "resumableSamples"; url = uri(samplesRepo) } }
}
// okay-audit, which the Maven plugin puts on the transformer's class path for `forbid`
val samplesRepoAudit by tasks.registering(Copy::class) {
    from(File(System.getProperty("user.home"), ".m2/repository/io/github/okay-effects/okay-audit_3"))
    into(samplesRepo.map { it.dir("io/github/okay-effects/okay-audit_3") })
}
val samplesRepoReady by tasks.registering(Copy::class) {
    dependsOn("publishOkayJavaResumablePublicationToSamplesRepository",
        ":publishOkayJavaPublicationToResumableSamplesRepository")
    dependsOn(samplesRepoAudit)
    from(File(System.getProperty("user.home"), ".m2/repository/dev/okay/okay-kernel-api"))
    into(samplesRepo.map { it.dir("dev/okay/okay-kernel-api") })
}
fun mvnw(task: Exec, vararg args: String) {
    task.workingDir = projectDir
    task.environment("MAVEN_USER_HOME", layout.buildDirectory.dir("maven-home").get().asFile.path)
    task.environment("JAVA_HOME", javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        .get().metadata.installationPath.asFile.path)
    task.commandLine(listOf("./mvnw", "-B", "-ntp") + args)
}
val mavenPlugin by tasks.registering(Exec::class) {
    dependsOn(samplesRepoReady)
    mvnw(this, "-f", "maven-plugin/pom.xml", "-Dmaven.repo.local=build/m2-plugin", "deploy",
        "-DaltDeploymentRepository=samples::" + samplesRepo.get().asFile.toURI())
}
tasks.register<Exec>("mavenSample") {
    dependsOn(mavenPlugin)
    mvnw(this, "-f", "samples/maven/pom.xml", "-Dmaven.repo.local=build/m2-sample", "verify")
}
