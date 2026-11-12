rootProject.name = "okay-java"

// optional: Spring Boot support, a module of its own — okay-java itself depends on nothing of Spring's
include("spring-boot")
project(":spring-boot").name = "okay-java-spring-boot"

// optional: direct-style programs made resumable by a bytecode transformation (resumable/README.md) — okay-java itself
// depends on nothing of it
include("resumable")
project(":resumable").name = "okay-java-resumable"
