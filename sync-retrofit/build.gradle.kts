import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "com.edu.standalone"
version = "0.1.0-SNAPSHOT"

publishing {
    repositories {
        maven {
            name = "Workspace"
            url = rootProject.layout.buildDirectory.dir("maven-repository").get().asFile.toURI()
        }
    }
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    sourceSets {
        jvmMain.dependencies {
            api(project(":sync-core"))
            // OkHttpClient is part of the public injected-client constructor.
            api(libs.okhttp)
            implementation(libs.retrofit)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":sync-server"))
        }
    }
}
