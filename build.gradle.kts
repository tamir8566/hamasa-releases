plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    // Samsung Health Data SDK (app/libs AAR) is built with @Parcelize and needs its runtime.
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    // Screenshot tests on the JVM (Robolectric). Test-only; see app/src/test/.../screenshots.
    id("io.github.takahirom.roborazzi") version "1.43.1" apply false
}
