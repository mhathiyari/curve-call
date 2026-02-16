plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.curvecall"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.curvecall"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/versions/**"
        }
    }
}

dependencies {
    // Project modules
    implementation(project(":engine"))
    implementation(project(":narration"))

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Google Play Services Location (FusedLocationProvider)
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // OkHttp for Overpass API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // osmdroid — OpenStreetMap map view with offline tile caching
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // GraphHopper — on-device routing engine (pre-built graph loading only)
    implementation("com.graphhopper:graphhopper-core:11.0") {
        // java.awt.* does not exist on Android (only needed for elevation TIFF import)
        exclude(group = "org.apache.xmlgraphics", module = "xmlgraphics-commons")
        // Only needed for OSM PBF import; also avoids protobuf-java vs protobuf-lite conflict
        exclude(group = "org.openstreetmap.osmosis")
        // Not needed for routing, pulls javax.xml.stream (Stax)
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-xml")
        // Let Gradle resolve to the project's Kotlin version
        exclude(group = "org.jetbrains.kotlin")
    }
    // SLF4J Android binding (GraphHopper uses SLF4J 2.x for logging)
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.github.tony19:logback-android:3.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

kapt {
    correctErrorTypes = true
}
