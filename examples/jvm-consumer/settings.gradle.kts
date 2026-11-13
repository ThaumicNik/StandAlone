rootProject.name = "standalone-external-consumer"
dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/maven-repository") }
        mavenCentral()
    }
}
