import java.io.File

// The mod toolchain of this workspace: applying cn.enaium.fabricpvz.loom supplies the game
// distribution (the game, the API modules, the real Fabric Loader and Mixin, ASM, SLF4J/Log4j and
// LWJGL, on the compile, test and launch class paths), Kotlin, the launcher class path and JUnit for
// the end-to-end test, the runClient task and the publication of the mod jar under mavenLocal
// coordinates. A mod never declares any of those artifacts by hand; see loom.
//
// The version is the plugin's own (every artifact of this workspace is released under its own
// version); the plugin itself resolves from the Gradle Plugin Portal, which serves it
// (settings.gradle.kts lists the portal first and keeps mavenLocal after it as an optional fallback
// for a version the portal does not serve yet).
plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.1"
}

dependencies {
    // Which game distribution this mod is built against: the release of the game, i.e. the asset
    // pvz-portable-<version>.zip of the GitHub release of FabricPvZ/pvz-portable. The toolchain
    // downloads it once into ~/.gradle/caches/pvz (and records its SHA-256 next to it) and unpacks it
    // into build/pvz/dist, so this mod is built against exactly the archive the release attaches, with
    // nothing of the game's build directory in play. The version is stated even though it is the
    // toolchain's default: the line says against which game this mod was built. While working on the
    // game's own code, point it at the distribution that build writes instead
    // (pvz(files("../PvZ-Portable/build/distributions"))).
    pvz("cn.enaium.fabricpvz:pvz-portable:1.0.0")
}

group = "cn.enaium.fabricpvz"
version = "1.0.0"
description = "Game-speed mod for the PvZ port: slow / pause / fast buttons below the in-game menu button"

/**
 * The visual smoke check of the mod: boots the game through the real Fabric Loader (so the mod's
 * mixins are applied) with the real video driver, starts a real level, drives the framework's own
 * frame loop and writes what the window shows into {@code run/build/window-dump/} (see
 * {@code cn.enaium.fabricpvz.speedmod.test.SpeedModWindowShot}). Same shape as {@code runClient}, plus
 * the harness class path ({@code -Dpvz.extraClasspath}) and the harness game entry point
 * ({@code -Dpvz.game.mainClass}) - which is why the mod's own output is put on the class path here
 * the same way the toolchain's own task does it (see loom/README.md, "The development run"): the
 * distribution's jars plus this mod's output directories (only those that exist, evaluated when the
 * run is configured for execution), as one `fabric.classPathGroups` entry. Without the group the
 * loader would leave this mod's classes to the parent class loader and the run would die on the
 * first API call (a class the parent loads cannot see the modules nested inside the API jar).
 */
val modOutput = sourceSets["main"].output.filter { it.exists() }

tasks.register<JavaExec>("dumpWindow") {
    group = "verification"
    description = "Runs the game with this mod and the real video driver and screenshots the board."
    mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient"
    classpath = configurations.getByName("pvzRunClasspath") + modOutput
    // The harness (the game entry point the launcher is pointed at) is this project's test classes.
    dependsOn(tasks.named("testClasses"))
    val runDirectory = layout.projectDirectory.dir("run").asFile
    workingDir = runDirectory
    doFirst { runDirectory.mkdirs() }
    jvmArgs("-Dpvz.gameDir=${runDirectory.absolutePath}")
    jvmArgs("-Dfabric.skipMcProvider=true")
    jvmArgs("-Dfabric.development=true")
    // An argument provider rather than a `jvmArgs` line: it reads the same lazily filtered
    // collection the class path is made of, i.e. at execution time, when those directories exist.
    jvmArgumentProviders.add { listOf("-Dfabric.classPathGroups=" + modOutput.files.joinToString(File.pathSeparator)) }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // The harness lives in this project's test classes, which are not part of the launcher's own
    // class path: the provider adds them to the loader's class loader (-Dpvz.extraClasspath).
    jvmArgs("-Dpvz.extraClasspath=${sourceSets["test"].output.classesDirs.asPath}")
    jvmArgs("-Dpvz.game.mainClass=cn.enaium.fabricpvz.speedmod.test.SpeedModWindowShot")
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        jvmArgs("-XstartOnFirstThread")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "PvZ Speed Mod",
            "Implementation-Version" to project.version,
            "Fabric-Mod-Id" to "speed-mod",
        )
    }
}
