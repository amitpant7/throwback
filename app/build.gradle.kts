import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/*
 * Release signing with the Google Play upload key, kept outside the repository. A git-ignored
 * keystore.properties at the repository root names it:
 *   storeFile=/absolute/path/to/throwback-upload.jks
 *   storePassword=...
 *   keyAlias=upload
 *   keyPassword=...
 * Without the file, release builds are unsigned (Google Play then refuses the upload).
 */
/*
 * Google Play tips (DECISIONS.md #4e), a switch in gradle.properties: throwback.playTips. Their code,
 * texts and artwork live in src/playTips and go into the build, with Play Billing, only when it is
 * true; otherwise src/webTips (Support links to the website's support page) and no billing library.
 */
val playTips: Boolean = providers.gradleProperty("throwback.playTips").orNull.toBoolean()

val uploadKey: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "app.throwback"
    compileSdk = 36

    defaultConfig {
        // Working ID; it can still change freely until the first Play Store upload.
        applicationId = "app.throwback"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
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
    sourceSets.getByName("main") {
        val tips = if (playTips) "src/playTips" else "src/webTips"
        java.srcDir("$tips/java")
        res.srcDir("$tips/res")
    }
}

/*
 * Generated assets (both added to every variant below):
 *  - inject/throwback.js: the page script, written as parts in src/main/pagescript/ (one area per
 *    file, all inside one function) and joined here in file-name order. See its README.md.
 *  - filters/rules.json: the filter rules. filters/rules.json at the repo root is the single source of
 *    truth; installed apps also fetch the latest copy from GitHub, and the bundled one is the fallback.
 */
abstract class JoinPageScript : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val parts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun join() {
        val files = parts.files.sortedBy { it.name }
        check(files.isNotEmpty()) { "no page script parts in src/main/pagescript" }
        val out = outputDir.file("inject/throwback.js").get().asFile
        out.parentFile.mkdirs()
        out.writeText(files.joinToString("") { it.readText() })
    }
}

abstract class BundleFilterRules : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rules: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val text = rules.get().asFile.readText()
        // A broken rules file must fail the build, never ship: the app would silently fall back to
        // its minimal built-in rules.
        require(!Regex("^(<<<<<<<|=======|>>>>>>>)", RegexOption.MULTILINE).containsMatchIn(text)) {
            "filters/rules.json contains merge-conflict markers"
        }
        groovy.json.JsonSlurper().parseText(text)
        val out = outputDir.file("filters/rules.json").get().asFile
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val pageScript = tasks.register<JoinPageScript>("join${name}PageScript") {
            parts.from(fileTree("src/main/pagescript") { include("*.js") })
        }
        val filterRules = tasks.register<BundleFilterRules>("bundle${name}FilterRules") {
            rules.set(rootProject.layout.projectDirectory.file("filters/rules.json"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(pageScript, JoinPageScript::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(filterRules, BundleFilterRules::outputDir)
    }
}

// Tests read the page script parts and the rules from the source tree (PageScriptTest, RulesParserTest):
// declare them, or a change to them alone leaves the tests "up to date" and unrun.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main/pagescript").withPropertyName("pageScript")
    inputs.file("../filters/rules.json").withPropertyName("rules")
}

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
    // Google Play's in-app review card (asked once, after a month of use; see core/RateAsk.kt).
    implementation(libs.play.review.ktx)
    if (playTips) implementation(libs.play.billing)

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
