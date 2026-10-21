plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":engine"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    runtimeOnly(libs.slf4j.nop) // silences Ktor's logging warning
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.eye3y3.finsync.cli.MainKt")
    applicationName = "finsync"
}
