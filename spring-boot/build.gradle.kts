plugins {
    `java-library`
    `maven-publish`
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

// okay-java in Spring Boot (README.md): a controller's method returns an Eff, its value the response, in web MVC and
// in WebFlux; an @Async method returns one; a program runs in a transaction. Spring compileOnly: the application's
// own Spring Boot is the one used, none imposed; each auto-configuration is off where its part of Spring is not there
publishing {
    publications {
        create<MavenPublication>("okayJavaSpringBoot") {
            from(components["java"])
        }
    }
}

dependencies {
    api(rootProject)
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("io.projectreactor:reactor-core")
    compileOnly("org.springframework:spring-tx")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-transaction")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
}
