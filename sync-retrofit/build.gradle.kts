import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.multiplatform) }

kotlin {
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    sourceSets {
        jvmMain.dependencies {
            api(project(":sync-core"))
            implementation(libs.retrofit)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":sync-server"))
        }
    }
}
