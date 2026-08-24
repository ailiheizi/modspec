plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    // Compose compiler ships as a KGP plugin since Kotlin 2.0
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
