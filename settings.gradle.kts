pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal.
        // mavenLocal comes after it as the fallback for 1.0.14, which the portal does not serve yet:
        // `(cd ../loom && ./gradlew publishToMavenLocal)` publishes it there. A version the portal
        // serves always resolves from the portal, so the fallback can be dropped once 1.0.14 is on it.
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Online only: the API this mod may declare is on Maven Central
        // (cn.enaium.fabricpvz:fabric-api), and so is every library the version manifest names.
        mavenCentral()
        // Fabric Loader, Mixin and fabric-language-kotlin resolve from Fabric's maven (none of them
        // is on Maven Central).
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
}

rootProject.name = "speed-mod"
