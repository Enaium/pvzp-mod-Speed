pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal,
        // which serves the version this build states (1.0.4, via `(cd ../loom && ./gradlew
        // publishPlugins)`).
        gradlePluginPortal()
        // An optional fallback, deliberately *after* the portal: a version the portal does not serve
        // yet - one built from a local checkout with `(cd ../loom && ./gradlew publishToMavenLocal)` -
        // can still be resolved from mavenLocal. A version the portal does serve, 1.0.4 among them,
        // always resolves from the portal and never from here.
        mavenLocal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        // The API modules and the loader resolve Fabric Loader (and Mixin) from Fabric's maven (neither
        // is on Maven Central).
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}

rootProject.name = "speed-mod"
