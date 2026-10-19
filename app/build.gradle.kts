import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 3
val releaseVersionName = providers.gradleProperty("versionName").orNull ?: "0.1.2-dev"
val signingProperties = rootProject.file("keystore.properties")
val signing = Properties().apply {
    if (signingProperties.isFile) signingProperties.inputStream().use(::load)
}

android {
    namespace = "dev.multiprompt.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.multiprompt.companion"
        manifestPlaceholders["accountRedirectScheme"] = "dev.multiprompt.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        buildConfigField("boolean", "MATERIAL3", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://github.com/valentinyeo/multiprompt-android/releases/download/android-latest/multiprompt-companion-update.json\"",
        )
    }

    if (signingProperties.isFile) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["accountRedirectScheme"] = "dev.multiprompt.companion.debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signingProperties.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("m3") {
            initWith(getByName("release"))
            applicationIdSuffix = ".m3"
            versionNameSuffix = "-m3"
            matchingFallbacks += "release"
            manifestPlaceholders["accountRedirectScheme"] = "dev.multiprompt.companion.m3"
            buildConfigField("boolean", "MATERIAL3", "true")
            buildConfigField(
                "String",
                "UPDATE_MANIFEST_URL",
                "\"https://github.com/valentinyeo/multiprompt-android/releases/download/android-m3-latest/multiprompt-m3-update.json\"",
            )
            if (signingProperties.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/INDEX.LIST",
        )
    }

    testOptions.unitTests {
        isIncludeAndroidResources = true
        all {
            providers.gradleProperty("m3Shots").orNull?.let { directory ->
                it.systemProperty("m3.shots", directory)
            }
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("m3")) {
        it.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.google.fonts)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.connectbot.terminal)
    implementation(libs.connectbot.ssh)
    implementation(libs.bouncycastle)
    implementation(libs.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.work)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("m3Implementation", libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.material.color.utilities) { isTransitive = false }
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}
