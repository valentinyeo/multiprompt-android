plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

val mavenCentralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

subprojects {
    plugins.withId("com.android.application") {
        tasks.withType<Test>().configureEach {
            systemProperty("robolectric.dependency.repo.url", mavenCentralMirror)
            systemProperty("robolectric.dependency.repo.id", "google-maven-central-mirror")
        }
    }
}
