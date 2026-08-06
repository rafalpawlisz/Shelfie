import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.google.services)
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Release signing, from a gitignored keystore.properties locally or from the
// environment in CI. Absent on a fresh clone and in the ordinary CI jobs, and
// that has to stay harmless: the release variant then builds unsigned, exactly
// as it did before this existed, and only the release workflow needs the real
// thing. Nothing about the keystore — path, passwords, alias — belongs in the
// repo, so this file reads and never stores.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

val keystorePath = signingValue("storeFile", "SHELFIE_KEYSTORE_FILE")
val keystoreReady = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "io.github.rafalpawlisz.shelfie"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.rafalpawlisz.shelfie"
        minSdk = 29
        targetSdk = 36
        // Overridden by the release workflow from the tag being built, so a
        // version is never bumped by hand and never disagrees with its tag.
        versionCode = System.getenv("SHELFIE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("SHELFIE_VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreReady) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = signingValue("storePassword", "SHELFIE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SHELFIE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SHELFIE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Unsigned when there is no keystore — a state the ordinary CI jobs
            // and any clone live in.
            if (keystoreReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint {
        // CI runs lint, and a warning nobody has to answer for is a warning that
        // accumulates: ten Compose errors sat here unnoticed because nothing ran
        // this check. Warnings fail the build so the next one is dealt with while
        // it is one, not thirty.
        warningsAsErrors = true
        // Except the ones that fire on the calendar rather than on this code.
        // Dependency updates are a deliberate act here (versions are pinned in
        // libs.versions.toml); a build must not start failing because somebody
        // else published a release.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
        )
    }
    buildFeatures {
        compose = true
        // Off by default since AGP 8. Wanted for VERSION_NAME/VERSION_CODE,
        // which the app reports to its household so the running version is
        // visible in the database.
        buildConfig = true
    }
    androidResources {
        // Puts Shelfie in the system's per-app language list (Android 13+),
        // which is built from the manifest's localeConfig and nothing else —
        // having a values-pl is not something the system goes looking for. AGP
        // generates that file from the values-* directories present, so adding
        // a language stays a matter of adding its strings. The language of the
        // unqualified values/ is declared in res/resources.properties, since a
        // directory without a qualifier does not say what it is written in.
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            // The android.jar on the unit-test classpath throws from every
            // method. android.util.Log is the one that reaches JVM tests: a
            // ViewModel logging inside a catch block used to blow up there and
            // replace the error it was reporting.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Aligns kotlinx-serialization app-wide: lifecycle pulls core 1.7.3 while
    // room-testing pulls json 1.8.1, and mixing them AbstractMethodErrors in
    // the migration tests (AGP keeps test and app classpaths consistent, so
    // the pin must live here, not in androidTest). enforcedPlatform, not
    // platform: the latter only constrains, and Gradle would still resolve to
    // a higher version if some future dependency asked for one — silently
    // un-aligning the two artifacts and bringing the crash back.
    implementation(enforcedPlatform(libs.kotlinx.serialization.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.play.services.code.scanner)
    implementation(libs.reorderable)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.kotlinx.coroutines.play.services)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}