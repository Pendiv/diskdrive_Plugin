plugins {
    java
}

group = "local.disc"
version = "0.19.34"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.26-alpha")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand("version" to pluginVersion)
    }
}

val resourcePack by tasks.registering(Zip::class) {
    from(layout.projectDirectory.dir("resource-pack"))
    archiveFileName.set("Disc-ResourcePack-26.3.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.assemble {
    dependsOn(resourcePack)
}

val runServer by tasks.registering(Exec::class) {
    group = "application"
    description = "Starts the local Paper test server."
    val serverJava = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    workingDir(layout.projectDirectory.dir("run"))
    standardInput = System.`in`
    doFirst {
        commandLine(
            serverJava.get().executablePath.asFile.absolutePath,
            "-Xms512M",
            "-Xmx1536M",
            "-Dterminal.jline=false",
            "-Dterminal.ansi=false",
            "-jar",
            "paper-26.3-159.jar",
            "--nogui"
        )
    }
}

tasks.register("runserver") {
    group = "application"
    description = "Starts the local Paper test server."
    dependsOn(runServer)
}
