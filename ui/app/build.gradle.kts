plugins {
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}

apply(from = rootProject.file("config/kmp-module.gradle"))

// Strings and brand assets live in one place and are generated into one class, so a
// screen cannot invent a literal: `Res.string` is the only way to reach user-facing text.
compose.resources {
    publicResClass = true
    packageOfResClass = "io.hydrabox.ui.app.resources"
    generateResClass = always
}

dependencies {
    add("commonMainImplementation", project(":core:runtime"))
    add("commonMainImplementation", project(":core:config"))
    add("commonMainImplementation", project(":core:subscription"))
    add("commonMainImplementation", project(":core:settings"))
    add("commonMainImplementation", project(":ui:design"))
    add("commonMainImplementation", project(":core:projection"))
    add("commonMainImplementation", project(":core:model"))
    add("commonMainImplementation", compose.runtime)
    add("commonMainImplementation", compose.ui)
    add("commonMainImplementation", compose.foundation)
    add("commonMainImplementation", compose.material3)
    add("commonMainImplementation", compose.components.resources)
    add("commonTestImplementation", "org.jetbrains.kotlin:kotlin-test")
}
