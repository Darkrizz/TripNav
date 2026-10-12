import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

// Planetiler needs Java 21; this tool only runs on build machines, never on the phone.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_21) } }

application {
    mainClass.set("com.tripnav.packbuilder.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

dependencies {
    implementation("com.onthegomap.planetiler:planetiler-core:0.10.2")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.ibm.icu:icu4j:77.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
