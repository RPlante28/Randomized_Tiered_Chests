plugins {
    id("java")
}

group = "com.randomizechests"
version = "1.0.0"

// Target Java 21 to match Spigot 1.21
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    // Spigot API snapshots
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // WorldEdit (EngineHub)
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    // compileOnly = available at compile time but NOT bundled into the jar
    // (the server already has these plugins installed)
    compileOnly("org.spigotmc:spigot-api:1.21-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0")
}

// Gradle automatically includes src/main/resources in the jar,
// so no extra configuration needed here.
