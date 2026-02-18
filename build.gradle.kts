import java.util.Properties

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.unisaarland.se"
version = project.findProperty("pluginVersion")?.toString()!!

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        create("CL", "2025.1.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    // JSON parsing
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Add the Gradle IntelliJ Plugin for publishing/build/run tasks

val localRepoDir = layout.projectDirectory.dir("local-repo")
// Capture values at configuration time (avoid calling 'project' at execution time)
val resolvedProjectName: String = project.name
val resolvedProjectVersionAtConfig: String = project.version.toString()
val resolvedPluginId: String = project.findProperty("pluginId")?.toString() ?: resolvedProjectName
val resolvedPluginVersionAtConfig: String? = project.findProperty("pluginVersion")?.toString()
val resolvedSinceBuildAtConfig: String = project.findProperty("sinceBuild")?.toString() ?: "0"
val propsFileProvider = layout.projectDirectory.file("gradle.properties")
val updatePluginsFileProvider = layout.projectDirectory.file("local-repo/updatePlugins.xml")
val distributionsDir = layout.buildDirectory.dir("distributions")

// Resolve providers to plain java.io.File at configuration time to avoid capturing Providers in task closures
val repoDirFileAtConfig: java.io.File = localRepoDir.asFile
val updatePluginsFileAtConfig: java.io.File = updatePluginsFileProvider.asFile
val propsFileAtConfig: java.io.File = propsFileProvider.asFile

// Copy generated zips into local-repo (use layout.buildDirectory) - Copy task may accept providers
tasks.register<Copy>("copyZipToLocalRepo") {
    group = "distribution"
    description = "Copy plugin zip(s) into local-repo/"
    dependsOn("buildPlugin")
    from(distributionsDir.map { it.asFile })
    include("*.zip")
    into(localRepoDir)
}

// Generate updatePlugins.xml scanning the local-repo zips (configuration-cache friendly)
// Use only plain Strings and java.io.File inside the task action (doLast)
tasks.register("generateUpdatePluginsXml") {
    group = "distribution"
    description = "Scan local-repo for zips and write updatePlugins.xml (simplified, config-cache safe)"
    dependsOn("copyZipToLocalRepo")
    outputs.file(updatePluginsFileProvider)
    doLast {
        // Use pre-resolved file and strings
        val repoDir = repoDirFileAtConfig
        if (!repoDir.exists()) repoDir.mkdirs()
        val zips = repoDir.listFiles { f -> f.extension == "zip" }?.sortedBy { it.name } ?: emptyList()

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugins>\n")
        for (zip in zips) {
            val fileName = zip.name
            val id = resolvedPluginId
            val versionStr = fileName.removeSuffix(".zip").substringAfterLast("-")
            val sinceBuild = resolvedSinceBuildAtConfig
            val sizeBytes = zip.length()

            sb.append("  <idea-plugin>\n")
            sb.append("    <id>${id}</id>\n")
            sb.append("    <name>${resolvedProjectName}</name>\n")
            sb.append("    <version>${versionStr}</version>\n")
            sb.append("    <idea-version since-build=\"${sinceBuild}\"/>\n")
            sb.append("    <description>Local build</description>\n")
            sb.append("    <downloads>\n")
            sb.append("      <size>${sizeBytes}</size>\n")
            sb.append("      <download-url>${fileName}</download-url>\n")
            sb.append("    </downloads>\n")
            sb.append("  </idea-plugin>\n")
        }
        sb.append("</plugins>\n")

        // Write using the resolved java.io.File
        updatePluginsFileAtConfig.writeText(sb.toString())
        println("Wrote updatePlugins.xml with ${zips.size} entries to ${repoDir.absolutePath}")
    }
}

// A convenience task to build and publish into local-repo (ensure we reference the simplified generator)
tasks.register("publishLocalRepo") {
    group = "distribution"
    description = "Build plugin and publish into local-repo with updatePlugins.xml"
    dependsOn("bumpSnapshotVersion", "generateUpdatePluginsXml")
}

// Optional: bump snapshot version in gradle.properties to force updates
// Make this configuration-cache friendly by computing key at configuration time and using the file provider
val bumpKey = if (project.findProperty("pluginVersion") != null) "pluginVersion" else if (project.findProperty("version") != null) "version" else "pluginVersion"
tasks.register("bumpSnapshotVersion") {
    group = "versioning"
    description = "Append timestamp to snapshot version in gradle.properties (pluginVersion preferred, config-cache safe)"
    outputs.file(propsFileProvider)
    doLast {
        val propsFile = propsFileAtConfig
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        val current = (props.getProperty(bumpKey) ?: resolvedProjectVersionAtConfig)
        val base = "1.0"
        val newVersion = "${base}.${System.currentTimeMillis()}"
        props.setProperty(bumpKey, newVersion)
        propsFile.outputStream().use { props.store(it, null) }
        println("Bumped ${bumpKey}: ${current} -> ${newVersion}")
    }
}
