plugins {
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

apply(from = rootProject.file("config/kmp-module.gradle"))

dependencies {
    add("commonMainImplementation", compose.runtime)
    add("commonMainImplementation", compose.ui)
    add("commonMainImplementation", compose.foundation)
    add("commonMainImplementation", compose.material3)
    // Only on Android, and only for turning a subscription link into a square.
    add("androidMainImplementation", "com.google.zxing:core:3.5.3")
    add("commonTestImplementation", "org.jetbrains.kotlin:kotlin-test")
    // Rendering a composable to a PNG off-device needs the desktop Skia binary; it is a test
    // dependency only, and it is what lets the aperture be looked at without a phone.
    add("jvmTestImplementation", compose.desktop.currentOs)
}
