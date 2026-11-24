import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.maekom"
version = "0.1.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.commonmark:commonmark:0.24.0")

    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        bundledPlugin("Git4Idea")
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.maekom.codetracker"
        name = "Maekom Code Tracker"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
    instrumentCode = false
    buildSearchableOptions = false
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    patchPluginXml {
        sinceBuild.set("242")
        untilBuild.set(provider { null })
    }

    buildSearchableOptions {
        enabled = false
    }
}
