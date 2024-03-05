import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.21"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.arrow-kt:arrow-core:1.2.0")
    implementation("io.arrow-kt:arrow-fx-stm:1.2.0")
    implementation("io.arrow-kt:arrow-resilience:1.2.3")
    implementation("io.arrow-kt:suspendapp-jvm:0.4.1-alpha.5")
    implementation("io.arrow-kt:suspendapp-ktor-jvm:0.4.1-alpha.5")
    implementation("io.arrow-kt:arrow-exact-jvm:0.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn", "-Xcontext-receivers")
    }
}