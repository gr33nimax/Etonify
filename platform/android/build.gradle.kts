import com.android.build.api.dsl.ApplicationExtension
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

val libboxAar = file("libs/libbox.aar")
val provenance = file("libs/libbox.provenance.json")
val provenanceText = provenance.readText()
val hydraCoreVersion = Regex("""\"version\"\s*:\s*\"([^\"]+)\"""")
    .find(provenanceText)?.groupValues?.get(1)
    ?: error("libbox provenance has no distribution version")
val hydraCoreCommit = Regex("""\"commit\"\s*:\s*\"([0-9a-f]{40})\"""")
    .find(provenanceText)?.groupValues?.get(1)
    ?: error("libbox provenance has no source commit")
val libboxSha256 = Regex("""\"sha256\"\s*:\s*\"([0-9a-f]{64})\"""")
    .find(provenanceText)?.groupValues?.get(1)
    ?: error("libbox provenance has no AAR digest")

extensions.configure<ApplicationExtension> {
    namespace = "io.hydrabox.platform.android"
    compileSdk = 36
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    defaultConfig {
        applicationId = "io.hydrabox.platform.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 200
        versionName = "2.0.0-alpha1"
        buildConfigField("String", "HYDRACORE_VERSION", "\"$hydraCoreVersion\"")
        // The alpha ships arm64 only: the other ABIs triple the artifact for devices we
        // are not testing on. Restore them when the alpha becomes a release candidate.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        getByName("debug") {
            ndk { debugSymbolLevel = "none" }
        }
    }
    sourceSets.getByName("main").manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets.getByName("main").java.srcDir("src/androidMain/kotlin")
    sourceSets.getByName("main").res.srcDir("src/androidMain/res")
}

dependencies {
    implementation(project(":core:contract"))
    implementation(project(":core:runtime"))
    implementation(project(":core:config"))
    implementation(project(":core:ruleset"))
    implementation(project(":core:subscription"))
    implementation(project(":core:settings"))
    implementation(project(":core:storage"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:model"))
    implementation(project(":core:projection"))
    implementation(project(":ui:app"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(files(libboxAar))
}

tasks.register("verifyLibboxProvenance") {
    group = "verification"
    description = "Verifies the hydrated libbox AAR against its published provenance and gitlink."
    inputs.files(provenance, libboxAar)
    doLast {
        check(libboxAar.isFile) { "Missing ${libboxAar.path}; hydrate the published libbox release." }
        val gitlink = providers.exec {
            workingDir(rootProject.file("hydracore"))
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        check(gitlink == hydraCoreCommit) { "HydraCore gitlink does not match libbox provenance" }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(libboxAar.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == libboxSha256) { "libbox AAR does not match published provenance" }
    }
}
