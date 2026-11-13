// a consumer of okay-java-resumable, built against this repository with nothing published: the plugin's build and
// okay-java's build included (a composite build), io.github.okay-effects:okay-java-resumable substituted by its project
pluginManagement {
    includeBuild("../../gradle-plugin")
}
includeBuild("../../..")

rootProject.name = "resumable-sample-gradle"
