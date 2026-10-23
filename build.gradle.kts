plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
    application
}

val telar = "0.1.0-alpha13"

dependencies {
    implementation("dev.deeptelar:telar-core:$telar")
    implementation("dev.deeptelar:telar-agent:$telar")
    implementation("dev.deeptelar:telar-openai:$telar")
    implementation("dev.deeptelar:telar-serialization:$telar")
    implementation("dev.deeptelar:telar-checkpoint-file:$telar")
    implementation("io.ktor:ktor-client-cio:3.6.0")
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
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "dev.deeptelar.peresos.MainKt"
    applicationName = "peresos"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
