import java.io.File

// The mod toolchain of this workspace: applying cn.enaium.fabricpvz.loom supplies the game - the
// game jar and the libraries the online version package's JSON next to it names (the real Fabric
// Loader, Mixin, ASM, SLF4J/Log4j and LWJGL with its natives), on the compile, test and launch class
// paths - plus the runClient task, the end-to-end test conventions and the publication of the mod jar
// under mavenLocal coordinates. It supplies nothing else: everything this mod uses besides the game
// is declared below, with the version this build was made with.
//
// The version is the plugin's own (every artifact of this workspace is released under its own
// version); the plugin resolves from the Gradle Plugin Portal.

plugins {
    id("cn.enaium.fabricpvz.loom") version "1.0.15"
    // Kotlin 2.4 is the first release line that accepts jvmTarget 25 and reads the game's Java 25
    // class files, i.e. the one this mod's sources are compiled with and the one its stdlib is.
    kotlin("jvm") version "2.4.21"
}

// The versions this build was made with, each stated once.
val kotlinStdlib = "org.jetbrains.kotlin:kotlin-stdlib:2.4.21"
val languageKotlin = "net.fabricmc:fabric-language-kotlin:1.14.2+kotlin.2.4.21"
val fabricApi = "cn.enaium.fabricpvz:fabric-api:1.2.2"

dependencies {
    // Which game distribution this mod is built against: the version package of the game's release,
    // i.e. the asset pvz-portable-<version>.zip of FabricPvZ/pvz-portable. The toolchain downloads it
    // once into ~/.gradle/caches/pvz (recording its SHA-256 next to it) and unpacks it into
    // build/pvz/dist: the game jar goes on the class paths as a file, and every library the JSON
    // manifest next to it names (loader, Mixin, ASM, logging, LWJGL with this machine's natives) is a
    // Maven coordinate. While working on the game's own code, point it at the distribution that
    // build writes instead (pvz(files("../PvZ-Portable/build/distributions")) or
    // pvz(files("../PvZ-Portable/build/mcversion/PvZ-Portable-<version>"))).
    pvz("cn.enaium.fabricpvz:pvz-portable:1.2.5")

    // Kotlin: compile against the stdlib, bundle none of it (the jar task treats a duplicate as a
    // bug); at runtime the copy is the one fabric-language-kotlin carries. The Kotlin Gradle plugin's
    // default `implementation(kotlin-stdlib)` is switched off in gradle.properties.
    compileOnly(kotlinStdlib)
    testImplementation(kotlinStdlib)

    // This mod's tests (the end-to-end harness included): JUnit, on the platform the toolchain's test
    // tasks use.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The language module this Kotlin mod runs on. One jar, whose nested kotlin-stdlib is the runtime
    // the loader provides: `implementation` puts it on this mod's runtime class path (which
    // `runClient` launches), `testRuntimeOnly` on the test fork's. Its POM's Fabric Loader dependency
    // is the one the game distribution names as well, so the launch class path resolves a single
    // copy of it, and the Kotlin runtime jars Gradle resolves for this build are kept off both
    // launch class paths (the loader provides the stdlib through the language module).
    implementation(languageKotlin)
    testRuntimeOnly(languageKotlin)

    // The API this mod uses: the aggregate jar, whose nested jars are the five feature modules.
    // Compilation carries the modules through its POM (javac and kotlinc read jar files, not jars
    // nested inside a jar); `include` embeds the aggregate in this mod's jar - an installation has
    // one jar to drop into `mods/`, because the loader reads the modules nested in it - and puts it
    // on the run class path. `testCompileOnly` because a test source set does not inherit
    // `compileOnly`; the end-to-end test loads the built jar, whose nested API the loader resolves.
    compileOnly(fabricApi)
    testCompileOnly(fabricApi)
    include(fabricApi)
}

group = "cn.enaium.fabricpvz"
version = "1.1.0"
description = "Game-speed mod for the PvZ port: the slow / pause / fast buttons and the speed readout in two rows below the in-game menu button, plus space to pause"

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
    // The launch class path the toolchain composes for runClient: the game distribution plus this
    // mod's runtime class path and its `include` jars.
    classpath = tasks.named<JavaExec>("runClient").get().classpath
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
