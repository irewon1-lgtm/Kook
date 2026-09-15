plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.krstock.v3"
    compileSdk = 34

    val releaseKeystoreFile = System.getenv("KR4_KEYSTORE_FILE")
    val releaseStorePassword = System.getenv("KR4_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("KR4_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("KR4_KEY_PASSWORD")

    val releaseSigningConfig = if (
        !releaseKeystoreFile.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()
    ) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    } else null

    defaultConfig {
        applicationId = "com.krstock.v3"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("KR4_VERSION_CODE")?.toIntOrNull() ?: 5
        versionName = System.getenv("KR4_VERSION_NAME") ?: "4.1.0-senior-ui"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.kt)
    implementation(libs.androidx.lifecycle.runtime.kt)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
