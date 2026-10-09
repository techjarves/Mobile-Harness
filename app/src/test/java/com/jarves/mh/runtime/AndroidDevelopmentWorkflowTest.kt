package com.jarves.mh.runtime

import com.jarves.mh.model.AndroidTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidDevelopmentWorkflowTest {
    @Test
    fun packageNamesAreValidForHumanNames() {
        assertEquals("com.pocketdev.my_cool_app", AndroidProjectTemplateGenerator.packageName("My cool app!"))
        assertEquals("com.pocketdev.app_123_notes", AndroidProjectTemplateGenerator.packageName("123 Notes"))
    }

    @Test
    fun composeTemplateContainsBuildableProjectShape() = withTempDirectory { root ->
        val project = File(root, "compose")
        AndroidProjectTemplateGenerator.generate(project, "Compose Demo", AndroidTemplate.COMPOSE)

        assertTrue(File(project, "settings.gradle.kts").isFile)
        assertTrue(File(project, "app/src/main/AndroidManifest.xml").isFile)
        assertTrue(File(project, "app/src/main/java/com/pocketdev/compose_demo/MainActivity.kt").readText().contains("setContent"))
        assertTrue(File(project, "app/build.gradle.kts").readText().contains("compose = true"))
        assertTrue(File(project, "app/src/main/res/values/themes.xml").readText().contains("android:windowLightStatusBar\">true"))
    }

    @Test
    fun xmlTemplateContainsLayoutAndKotlinActivity() = withTempDirectory { root ->
        val project = File(root, "xml")
        AndroidProjectTemplateGenerator.generate(project, "Views Demo", AndroidTemplate.XML)

        assertTrue(File(project, "app/src/main/res/layout/activity_main.xml").isFile)
        assertTrue(File(project, "app/src/main/java/com/pocketdev/views_demo/MainActivity.kt").readText().contains("setContentView"))
        assertTrue(File(project, "app/build.gradle.kts").readText().contains("appcompat:1.6.1"))
    }

    @Test
    fun gradleCommandPrefersWrapper() = withTempDirectory { root ->
        File(root, "gradlew").writeText("#!/bin/sh")
        assertTrue(androidGradleCommand(root, "assembleDebug").startsWith("bash ./gradlew"))
        File(root, "gradlew").delete()
        assertTrue(androidGradleCommand(root, "assembleDebug").startsWith("gradle "))
    }

    @Test
    fun apkDiscoveryUsesGradleMetadata() = withTempDirectory { root ->
        val output = File(root, "app/build/outputs/apk/debug").apply { mkdirs() }
        val apk = File(output, "demo-debug.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(output, "output-metadata.json").writeText("""{"elements":[{"outputFile":"demo-debug.apk"}]}""")
        assertEquals(apk.canonicalFile, findDebugApk(root)?.canonicalFile)
    }

    @Test
    fun diagnosticsExplainCommonFailures() {
        assertTrue(diagnoseAndroidBuildFailure("No space left on device", 1).contains("storage"))
        assertTrue(diagnoseAndroidBuildFailure("Manifest merger failed", 1).contains("manifest", ignoreCase = true))
        assertNotNull(diagnoseAndroidBuildFailure("unknown", 7))
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("pocketdev-android-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
