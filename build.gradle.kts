import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.7.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()
val localAndroidStudioPath = "/Applications/Android Studio.app"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    intellijPlatform {
        if (file(localAndroidStudioPath).exists()) {
            local(localAndroidStudioPath)
        } else {
            create(
                providers.gradleProperty("platformType").get(),
                providers.gradleProperty("platformVersion").get(),
            )
        }
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("junit:junit:4.13.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Complexity Radar"
        description =
            """
            Static complexity radar for Java and Kotlin files in IntelliJ IDEA and Android Studio.
            """.trimIndent()
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild").get()
        }
        changeNotes =
            """
            Initial implementation with scoring, caching, configuration, IDE visualizations, report export, and AI prompt generation.
            """.trimIndent()
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    test {
        useJUnitPlatform()
    }

    named("buildSearchableOptions") {
        enabled = false
    }
}
