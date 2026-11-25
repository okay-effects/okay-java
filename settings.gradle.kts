rootProject.name = "okay-java"

// the vocabulary (okay.effect): Op, Eff, Pure, Sensitive, Audited — of no dependency; okay-java and okay-direct are
// written over it
include("api")
project(":api").name = "okay-java-api"

// optional: Spring Boot support, a module of its own — okay-java itself depends on nothing of Spring's
include("spring-boot")
project(":spring-boot").name = "okay-java-spring-boot"

// optional: direct-style programs made resumable by a bytecode transformation (resumable/README.md) — okay-java itself
// depends on nothing of it
include("resumable")
project(":resumable").name = "okay-java-resumable"

// optional: sugar by annotations — an annotation processor that generates okay.rows code (sugar/README.md); okay-java
// itself depends on nothing of it
include("sugar")
project(":sugar").name = "okay-java-sugar"
