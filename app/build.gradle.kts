plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.throwback"
    compileSdk = 35

    defaultConfig {
        // Working ID; it can still change freely until the first Play Store upload.
        applicationId = "app.throwback"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// filters/rules.json at the repo root is the single source of truth for the filter rules.
// It is bundled into the APK as a fallback; installed apps also fetch the latest copy from GitHub.
val copyFilterRules by tasks.registering(Copy::class) {
    // A broken rules file (e.g. merge-conflict markers) must fail the build, never ship: the app would
    // silently fall back to its minimal built-in rules.
    doFirst {
        val text = rootProject.file("filters/rules.json").readText()
        require(!Regex("^(<<<<<<<|=======|>>>>>>>)", RegexOption.MULTILINE).containsMatchIn(text)) {
            "filters/rules.json contains merge-conflict markers"
        }
        groovy.json.JsonSlurper().parseText(text)
    }
    from(rootProject.file("filters/rules.json"))
    into(layout.buildDirectory.dir("generated/filterAssets/filters"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/filterAssets"))
tasks.named("preBuild") { dependsOn(copyFilterRules) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
