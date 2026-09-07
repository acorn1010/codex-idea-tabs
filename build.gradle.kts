plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.acorn.codextabs"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIde")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2026.1.3")
    }
    implementation(project(":core"))
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

val buildWeb by tasks.registering(Exec::class) {
    workingDir("web")
    commandLine("npm", "run", "build")
    inputs.dir("web/src")
    inputs.files("web/package.json", "web/package-lock.json", "web/vite.config.ts", "web/index.html")
    inputs.file("scripts/license-notices.mjs")
    outputs.dir("web/dist")
}
tasks.processResources {
    dependsOn(buildWeb)
    from("web/dist") { into("web") }
}

val nativeSmoke by sourceSets.creating {
    java.srcDir("scripts/native-smoke")
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
}
tasks.register<Jar>("nativeSmokePlugin") {
    dependsOn(nativeSmoke.classesTaskName)
    from(nativeSmoke.output.classesDirs)
    from("scripts/native-smoke/plugin.xml") { into("META-INF") }
    archiveFileName = "codex-tabs-native-smoke.jar"
    destinationDirectory = layout.buildDirectory.dir("native-smoke")
}

intellijPlatform {
    pluginConfiguration {
        name = "Codex Tabs"
        ideaVersion { sinceBuild = "261" }
    }
    buildSearchableOptions = false
}
