plugins { application }
dependencies { implementation(project(":sync-core")); testImplementation(libs.junit) }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
application { mainClass.set("com.edu.standalone.server.SyncServer") }

tasks.register<JavaExec>("benchmark") {
    description = "Measure local journal/backlog behavior; writes CSV under build/benchmarks."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.edu.standalone.server.SyncBenchmark")
    args(rootProject.layout.buildDirectory.file("benchmarks/current.csv").get().asFile.absolutePath)
}

