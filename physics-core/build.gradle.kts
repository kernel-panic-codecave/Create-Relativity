plugins {
    kotlin("jvm")
}

group = property("mod_group").toString()
version = property("mod_version").toString()

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Loaded as a nested library in production; GAMELIBRARY puts it in the layer mods can see.
tasks.jar {
    manifest.attributes(
        "FMLModType" to "GAMELIBRARY",
        "Automatic-Module-Name" to "net.kernelpanicsoft.relativity.physics",
    )
}

tasks.test {
    useJUnitPlatform()
}
