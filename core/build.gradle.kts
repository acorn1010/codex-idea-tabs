plugins { `java-library` }

repositories { mavenCentral() }
dependencies {
    api("com.google.code.gson:gson:2.13.2")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.test { useJUnitPlatform() }
