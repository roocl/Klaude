plugins {
    application
}

group = "io.klaude"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "io.klaude.daemon.Main"
    applicationName = "klaude-core-java"
}

sourceSets {
    test {
        resources.srcDir("contract")
    }
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.testRuntimeOnly.get())
}

dependencies {
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.dotenv.java)
    implementation(libs.json.schema.validator)
    implementation(libs.netty.all)
    implementation(libs.tomlj)

    testImplementation(libs.assertj.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs process, socket, and distribution integration tests."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    dependsOn(tasks.installDist, tasks.distZip)
    shouldRunAfter(tasks.test)
    systemProperty(
        "klaude.distribution",
        layout.buildDirectory.dir("install/klaude-core-java").get().asFile.absolutePath,
    )
    systemProperty("klaude.projectRoot", layout.projectDirectory.asFile.absolutePath)
    systemProperty("klaude.contractRoot", layout.projectDirectory.dir("contract").asFile.absolutePath)
    systemProperty(
        "klaude.distributionZip",
        tasks.distZip.flatMap { it.archiveFile }.get().asFile.absolutePath,
    )
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.register<JavaExec>("offlineDemo") {
    group = "application"
    description = "Run the deterministic offline daemon demonstration"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.klaude.daemon.OfflineDemoMain"
}

tasks.register<JavaExec>("roundTripFixtures") {
    group = "verification"
    description = "Round-trip protocol fixtures through the Java models"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.klaude.protocol.FixtureRoundTripMain"
    args(
        layout.projectDirectory.dir("contract").asFile.absolutePath,
        layout.buildDirectory.dir("contract-roundtrip").get().asFile.absolutePath,
    )
}
