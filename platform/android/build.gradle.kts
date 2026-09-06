import com.android.build.api.dsl.ApplicationExtension
import java.security.MessageDigest
import java.util.Properties

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

/**
 * A signing value from `local.properties` or the environment, in that order. Neither is in the
 * repository, and a missing value is not an error: it means this machine does not sign releases.
 */
fun releaseSigningProperty(name: String): String? {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.isFile) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty(name)
    } else {
        null
    }
    return (fromFile ?: System.getenv(name))?.takeIf(String::isNotBlank)
}

extensions.configure<ApplicationExtension> {
    namespace = "io.hydrabox.platform.android"
    compileSdk = 36
    // Named so AGP has an NDK to strip with. Without it nothing strips the core's library, and
    // the release APK shipped `libbox.so` at 103.6 MB of its 108 MB with the Go symbol table and
    // DWARF still in it.
    ndkVersion = "28.2.13676358"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    defaultConfig {
        // The product's identifier, which is 1.x's. It is not cosmetic: the per-origin device
        // identifier is derived from the package name, so keeping the alpha's module name here
        // would hand every Hydra provider a second device for the same phone, and 2.0 would
        // install beside 1.x instead of replacing it.
        applicationId = "io.hydrabox.client"
        minSdk = 26
        targetSdk = 36
        versionCode = 200
        versionName = "2.0.0-alpha1"
        buildConfigField("String", "HYDRACORE_VERSION", "\"$hydraCoreVersion\"")
        // The alpha ships arm64 only: the other ABIs triple the artifact for devices we
        // are not testing on. Restore them when the alpha becomes a release candidate.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Release signing, if this machine has the key. The alpha shipped `-unsigned.apk` and there
    // was no way to sign it from the build at all; the key itself is the owner's and never enters
    // the repository, so it is read from `local.properties` or the environment and simply absent
    // otherwise. An absent key leaves the release unsigned exactly as before, rather than failing
    // every build that is not a release.
    val keystore = releaseSigningProperty("HYDRABOX_KEYSTORE")?.let(rootProject::file)
    if (keystore?.isFile == true) {
        signingConfigs.create("release") {
            storeFile = keystore
            storePassword = releaseSigningProperty("HYDRABOX_KEYSTORE_PASSWORD")
            keyAlias = releaseSigningProperty("HYDRABOX_KEY_ALIAS")
            keyPassword = releaseSigningProperty("HYDRABOX_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") {
            ndk { debugSymbolLevel = "none" }
        }
        getByName("release") {
            // `proguardFiles` alone did nothing: without this flag R8 never ran, so the rules
            // file here was dead, 22 MB of dex shipped unshrunk, and there was no mapping to
            // deobfuscate a release crash with.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    sourceSets.getByName("main").manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets.getByName("main").java.srcDir("src/androidMain/kotlin")
    // `go.HydraNativeLoader` lives here: the core's Android artifact is patched to load its
    // native library through that class, so the app has to supply it.
    sourceSets.getByName("main").java.srcDir("src/androidMain/java")
    sourceSets.getByName("main").res.srcDir("src/androidMain/res")
}

dependencies {
    testImplementation(kotlin("test-junit"))
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

/**
 * The core's Android artifact is patched to load its native library through
 * `go.HydraNativeLoader`, a class the application has to supply. Bundling the AAR without it
 * compiles and installs cleanly and then fails at the first call into the core — every call,
 * in every process. That is what the 2.0 alpha shipped, so the invariant is checked here
 * rather than trusted.
 */
tasks.register("verifyNativeLoaderSeam") {
    group = "verification"
    description = "Fails when the patched libbox AAR has no HydraNativeLoader to load through."
    val loader = file("src/androidMain/java/go/HydraNativeLoader.java")
    inputs.files(libboxAar, loader)
    doLast {
        val patched = zipTree(libboxAar).matching { include("classes.jar") }.singleFile.let { jar ->
            zipTree(jar).matching { include("go/Seq.class") }.singleFile.readBytes()
        }.let { bytes -> String(bytes, Charsets.ISO_8859_1).contains("go/HydraNativeLoader") }
        if (!patched) return@doLast
        check(loader.isFile) {
            "The bundled core loads its library through go.HydraNativeLoader; " +
                "${loader.path} is missing and every call into the core would fail."
        }
    }
}

tasks.named("preBuild") { dependsOn("verifyNativeLoaderSeam") }

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
        val digest = MessageDigest.getInstance("SHA-256")
        libboxAar.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == libboxSha256) { "libbox AAR does not match published provenance" }
    }
}

tasks.named("check") { dependsOn("verifyLibboxProvenance") }
tasks.named("preBuild") { dependsOn("verifyLibboxProvenance") }
