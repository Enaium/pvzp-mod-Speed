pluginManagement {
    repositories {
        // The mod toolchain plugin (cn.enaium.fabricpvz.loom) comes from the Gradle Plugin Portal,
        // which serves the version this build states (1.0.9). No mavenLocal() line: the workspace
        // published the plugin there while 1.0.9 was unreleased, and the portal is the online source
        // of it now.
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
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
