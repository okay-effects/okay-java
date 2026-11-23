// okay-java-api: the vocabulary of effects in Java — Op, Eff, Pure, Sensitive, Audited (package okay.effect) — of no
// implementation and no dependency; okay-java and okay-direct are written over it
plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.okay-effects"
version = rootProject.version

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

publishing {
    publications {
        create<MavenPublication>("okayJavaApi") {
            artifactId = "okay-java-api"
            from(components["java"])
        }
    }
}
