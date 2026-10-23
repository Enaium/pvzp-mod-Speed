pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal.
        gradlePluginPortal()
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
