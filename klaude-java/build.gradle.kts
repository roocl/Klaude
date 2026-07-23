plugins {
    application
}

import org.gradle.jvm.application.tasks.CreateStartScripts

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

val cliStartScripts = tasks.register<CreateStartScripts>("cliStartScripts") {
    description = "Creates the Klaude CLI launcher scripts."
    mainClass = "io.klaude.cli.CliMain"
    applicationName = "klaude"
    outputDir = layout.buildDirectory.dir("scripts/cli").get().asFile
    classpath = files(tasks.jar, configurations.runtimeClasspath)
}

val tuiStartScripts = tasks.register<CreateStartScripts>("tuiStartScripts") {
    description = "Creates the Klaude TUI launcher scripts."
    mainClass = "io.klaude.cli.TuiMain"
    applicationName = "klaude-tui"
    outputDir = layout.buildDirectory.dir("scripts/tui").get().asFile
    classpath = files(tasks.jar, configurations.runtimeClasspath)
}

tasks.withType<CreateStartScripts>().configureEach {
    doLast {
        val windowsMarker = "@rem Find java.exe"
        val windowsOverride = """@rem Load the project-specific Java runtime before starting the JVM
if not defined KLAUDE_JAVA_HOME if exist "%CD%\.env" for /F "usebackq tokens=1,* delims==" %%A in ("%CD%\.env") do if "%%A"=="KLAUDE_JAVA_HOME" set "KLAUDE_JAVA_HOME=%%B"
if not defined KLAUDE_JAVA_HOME if exist "%APP_HOME%\.env" for /F "usebackq tokens=1,* delims==" %%A in ("%APP_HOME%\.env") do if "%%A"=="KLAUDE_JAVA_HOME" set "KLAUDE_JAVA_HOME=%%B"
if defined KLAUDE_JAVA_HOME set "JAVA_HOME=%KLAUDE_JAVA_HOME%"

$windowsMarker""".replace("\n", "\r\n")
        windowsScript.writeText(
            windowsScript.readText().replaceFirst(windowsMarker, windowsOverride),
        )
        val unixMarker = "# Determine the Java command to use to start the JVM."
        val unixOverride = """# Load the project-specific Java runtime before starting the JVM
if [ -z "${'$'}{KLAUDE_JAVA_HOME:-}" ] ; then
    for klaude_env_file in "${'$'}PWD/.env" "${'$'}APP_HOME/.env" ; do
        if [ -f "${'$'}klaude_env_file" ] ; then
            while IFS='=' read -r klaude_env_key klaude_env_value ; do
                if [ "${'$'}klaude_env_key" = "KLAUDE_JAVA_HOME" ] ; then
                    KLAUDE_JAVA_HOME="${'$'}klaude_env_value"
                    break
                fi
            done < "${'$'}klaude_env_file"
        fi
        [ -n "${'$'}{KLAUDE_JAVA_HOME:-}" ] && break
    done
fi
if [ -n "${'$'}{KLAUDE_JAVA_HOME:-}" ] ; then
    JAVA_HOME="${'$'}KLAUDE_JAVA_HOME"
fi

$unixMarker"""
        unixScript.writeText(
            unixScript.readText().replaceFirst(unixMarker, unixOverride),
        )
    }
}

distributions {
    main {
        contents {
            from(cliStartScripts) {
                into("bin")
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
            from(tuiStartScripts) {
                into("bin")
                filePermissions {
                    unix("rwxr-xr-x")
                }
            }
        }
    }
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

tasks.withType<JavaExec>().configureEach {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
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
