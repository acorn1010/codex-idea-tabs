plugins { `java-library` }

repositories { mavenCentral() }
dependencies {
    api("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
// Exercise the Python relay through its real HTTP and curl entry points.
val testModelRelay by tasks.registering(Exec::class) {
    commandLine("python3", layout.projectDirectory.file("src/test/python/test_wsl_model_relay.py").asFile.absolutePath)
}
tasks.test { useJUnitPlatform(); dependsOn(testModelRelay) }
