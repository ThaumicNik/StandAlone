plugins { application }
dependencies { implementation(project(":sync-core")); testImplementation(libs.junit) }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
application { mainClass.set("com.edu.standalone.server.SyncServer") }

