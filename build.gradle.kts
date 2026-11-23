// Plugins are declared once here (not applied) so every module resolves the same versions.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
}
