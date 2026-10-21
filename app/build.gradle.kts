import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/** versionCode / versionName; the release scripts bump versionCode. */
val version = Properties().apply { file("version.properties").inputStream().use(::load) }

/**
 * Release keys live outside the repository, in ~/.config/finsync/ (mode 600): signing.properties for the
 * Google Play upload key, direct-signing.properties for the key that signs the APKs on GitHub. Each holds
 * storeFile, storePassword, keyAlias and keyPassword. Without them, release builds are signed with the
 * debug key, which is fine for trying a build locally.
 */
fun keyProperties(name: String) = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/finsync/$name")
    if (f.isFile) f.inputStream().use(::load)
}
val keys = mapOf("upload" to keyProperties("signing.properties"), "direct" to keyProperties("direct-signing.properties"))

/** The public repository: the github build looks for updates in its releases. */
val githubRepo = "3EYE3Y3/finsync"

android {
    namespace = "com.eye3y3.finsync"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.eye3y3.finsync"
        minSdk = 26
        targetSdk = 36
        versionCode = version.getProperty("versionCode").toInt().coerceAtLeast(1)
        versionName = version.getProperty("versionName")
        buildConfigField("String", "FEEDBACK_URL", "\"https://3eye3y3.github.io/finsync-site/feedback\"")
    }
    signingConfigs {
        for ((name, p) in keys) if (p.getProperty("storeFile") != null) create(name) {
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    // Without its key a flavour's release build is debug-signed (the release scripts refuse to publish that).
    // Same application id: a Play install and a GitHub install can't update each other (different keys).
    flavorDimensions += "channel"
    productFlavors {
        create("play") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"play\"")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        create("github") {
            dimension = "channel"
            buildConfigField("String", "CHANNEL", "\"github\"")
            buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
            signingConfig = signingConfigs.findByName("direct") ?: signingConfigs.getByName("debug")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/*.kotlin_module")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":engine"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    "playImplementation"(libs.play.app.update)
    implementation(libs.ktor.client.mock) // the built-in sample library (demo mode)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okio.fakefilesystem)
    testImplementation(libs.ktor.client.mock)
}
