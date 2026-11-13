plugins { application }

dependencies {
    implementation("com.edu.standalone:sync-core-jvm:0.1.0-SNAPSHOT")
    implementation("com.edu.standalone:sync-retrofit-jvm:0.1.0-SNAPSHOT")
}

java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
application { mainClass.set("example.LibraryConsumer") }
