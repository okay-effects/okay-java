rootProject.name = "okay-java"

// optional: Spring Boot support, a module of its own — okay-java itself depends on nothing of Spring's
include("spring-boot")
project(":spring-boot").name = "okay-java-spring-boot"
