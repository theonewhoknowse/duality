
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thaz.duality"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thaz.duality"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf(
                    "androidx.**",
                    "kotlin.**",
                    "kotlinx.**"
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

        resources {
            excludes += setOf(
                "prebuilt/linux/**",
                "prebuilt/macosx/**",
                "prebuilt/windows/**"
            )
        }
    }

    androidResources {
        noCompress += "apk"
    }
}

dependencies {
    // Apktool Android library
    implementation(
        files("libs/apktool/apktool-android-1.0.0.aar")
    )

    implementation("com.android.tools.build:apksig:9.4.1")


    configurations.configureEach {
        exclude(
            group = "com.google.guava",
            module = "listenablefuture"
        )
    }

    // Apktool bundled runtime dependencies
    implementation(
        fileTree(
            mapOf(
                "dir" to "libs/apktool/runtime",
                "include" to listOf("*.jar")
            )
        )
    )

    // Existing Jetpack Compose dependencies
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
