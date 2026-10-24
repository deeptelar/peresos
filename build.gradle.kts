plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    application
}

val telar = "0.1.0-alpha14"

version = "1.0.4"

dependencies {
    implementation("dev.deeptelar:telar-core:$telar")
    implementation("dev.deeptelar:telar-agent:$telar")
    implementation("dev.deeptelar:telar-openai:$telar")
    implementation("dev.deeptelar:telar-serialization:$telar")
    implementation("dev.deeptelar:telar-checkpoint-file:$telar")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    // The text of a PDF fed to the pile, or pictures of its pages: Ollama takes no PDFs.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    // The window, `peresos window`: Compose for the desktop this is built on.
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Ktor logs through SLF4J; without a logger it prints a warning on every start.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("dev.deeptelar:telar-test:$telar")
    testImplementation("dev.deeptelar:telar-checkpoint-file:$telar")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // The doctor's tests talk to a pretend Ollama.
    testImplementation("io.ktor:ktor-client-mock:3.6.0")
    // The soak test clicks the window's buttons.
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "dev.deeptelar.peresos.MainKt"
    applicationName = "peresos"
}

tasks.jar {
    manifest { attributes("Implementation-Title" to "Peresós", "Implementation-Version" to version) }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// A long random walk through the window: ./gradlew soak -Psteps=3000 -Pseed=7, or -Pgemma for the real model.
val soak by tasks.registering(Test::class) {
    description = "Clicks through the window for a long time, checking every action."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*SoakTest*") }
    systemProperty("soak.steps", providers.gradleProperty("steps").getOrElse("3000"))
    providers.gradleProperty("seed").orNull?.let { systemProperty("soak.seed", it) }
    if (providers.gradleProperty("gemma").isPresent) systemProperty("soak.gemma", "true")
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

// The packages: Peresós with its own Java, for the system this runs on. packaging/ turns it into a
// .deb, a .tar.gz or a .zip, and .github/workflows/package.yml does that on Linux, macOS and Windows.
val os: String = System.getProperty("os.name")
val mac = os.startsWith("Mac")
val windows = os.startsWith("Windows")

val icons by tasks.registering(JavaExec::class) {
    description = "Draws the icon of the app from the face in the tray."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "dev.deeptelar.peresos.Icons"
    val folder = layout.buildDirectory.dir("icons")
    args(folder.get().asFile.path)
    jvmArgs("-Djava.awt.headless=true")
    outputs.dir(folder)
}

val appImage by tasks.registering(Exec::class) {
    description = "Packs Peresós with its own Java in build/jpackage, with jpackage, for the system this runs on."
    group = "distribution"
    dependsOn(tasks.installDist, icons)
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }.get().metadata.installationPath
    val dest = layout.buildDirectory.dir("jpackage").get().asFile
    val icon = layout.buildDirectory.file("icons/peresos." + if (mac) "icns" else if (windows) "ico" else "png").get().asFile
    executable(jdk.file("bin/jpackage" + if (windows) ".exe" else "").asFile)
    args("--type", "app-image", "--dest", dest, "--app-version", version, "--vendor", "DeepTelar")
    args("--description", "A sloth that eats one item of your backlog a day", "--icon", icon)
    args("--input", layout.buildDirectory.dir("install/peresos/lib").get().asFile, "--main-jar", tasks.jar.get().archiveFileName.get())
    args("--main-class", "dev.deeptelar.peresos.MainKt")
    // The modules jdeps finds in its jars, and the one for HTTPS to Open-Meteo.
    args("--add-modules", "java.base,java.desktop,java.instrument,java.management,jdk.unsupported,jdk.crypto.ec")
    args("--jlink-options", "--strip-debug --no-man-pages --no-header-files --strip-native-commands")
    when {
        // The app of the Finder opens the window: Peresós.app/Contents/MacOS/Peresos without a terminal.
        mac -> args("--name", "Peresos", "--mac-package-identifier", "dev.deeptelar.peresos")
        // peresos.exe prints to the terminal; peresos-window.exe opens the window, from the Start menu.
        windows -> args("--name", "peresos", "--win-console", "--add-launcher", "peresos-window=${file("packaging/windows/window.properties")}")
        else -> args("--name", "peresos")
    }
    doFirst { dest.deleteRecursively() }
    // Without inputs, Gradle would take an old image for up to date, whatever changed in the code.
    inputs.dir(layout.buildDirectory.dir("install/peresos/lib"))
    inputs.file(icon)
    inputs.dir("packaging")
    outputs.dir(dest)
}
