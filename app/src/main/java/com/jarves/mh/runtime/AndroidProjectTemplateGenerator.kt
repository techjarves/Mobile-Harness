package com.jarves.mh.runtime

import com.jarves.mh.model.AndroidTemplate
import com.jarves.mh.model.projectSlug
import java.io.File

object AndroidProjectTemplateGenerator {
    fun packageName(name: String): String {
        val raw = projectSlug(name).replace('-', '_').take(40).ifBlank { "app" }
        val suffix = if (raw.first().isLetter()) raw else "app_$raw"
        return "com.pocketdev.$suffix"
    }

    fun generate(destination: File, appName: String, template: AndroidTemplate) {
        val parent = requireNotNull(destination.parentFile)
        val staging = File(parent, ".${destination.name}.android-template")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the Android project" }
        try {
            writeCommon(staging, appName, packageName(appName), template)
            when (template) {
                AndroidTemplate.COMPOSE -> writeCompose(staging, packageName(appName))
                AndroidTemplate.XML -> writeXml(staging, packageName(appName))
            }
            if (destination.exists()) {
                check(destination.listFiles().isNullOrEmpty()) { "Project folder is not empty" }
                check(destination.delete()) { "Could not replace the empty project folder" }
            }
            check(staging.renameTo(destination)) { "Could not finish creating the Android project" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun writeCommon(root: File, appName: String, namespace: String, template: AndroidTemplate) {
        val compose = template == AndroidTemplate.COMPOSE
        write(root, "settings.gradle.kts", """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
            rootProject.name = ${kotlinString(appName)}
            include(":app")
        """)
        write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "8.11.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
        """)
        write(root, "gradle.properties", """
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """)
        write(root, "app/build.gradle.kts", """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$namespace"
                compileSdk = 36

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = 28
                    targetSdk = 36
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
                buildFeatures { compose = $compose }
                ${if (compose) "composeOptions { kotlinCompilerExtensionVersion = \"1.5.10\" }" else ""}
            }

            dependencies {
                implementation("androidx.core:core-ktx:1.8.0")
                ${if (compose) """
                implementation("androidx.activity:activity-compose:1.5.1")
                implementation("androidx.compose.ui:ui:1.3.0")
                implementation("androidx.compose.foundation:foundation:1.3.0")
                implementation("androidx.compose.material3:material3:1.0.0")
                """.trimIndent() else """
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.9.0")
                """.trimIndent()}
            }
        """)
        write(root, ".gitignore", """
            .gradle/
            local.properties
            **/build/
            *.iml
        """)
        write(root, ".pocketdev/android-project.json", """{"schemaVersion":1,"template":"${template.name}","packageName":"$namespace"}""")
        write(root, "app/src/main/res/values/strings.xml", """
            <resources><string name="app_name">${xmlEscape(appName)}</string></resources>
        """)
    }

    private fun writeCompose(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(root, "app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="android:style/Theme.Material.Light.NoActionBar">
                    <item name="android:fontFamily">sans</item>
                    <item name="android:colorAccent">#6750A4</item>
                    <item name="android:statusBarColor">#FFFBFE</item>
                    <item name="android:navigationBarColor">#FFFBFE</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/java/$packagePath/MainActivity.kt", """
            package $namespace

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { MaterialTheme { Welcome() } }
                }
            }

            @Composable
            private fun Welcome() {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Built with PocketDev")
                }
            }
        """)
    }

    private fun writeXml(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(root, "app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#6750A4</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/res/values-night/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#D0BCFF</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">false</item>
                    <item name="android:windowLightNavigationBar">false</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/res/layout/activity_main.xml", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Built with PocketDev" android:textSize="22sp" />
            </LinearLayout>
        """)
        write(root, "app/src/main/java/$packagePath/MainActivity.kt", """
            package $namespace

            import android.os.Bundle
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                }
            }
        """)
    }

    private fun write(root: File, relative: String, content: String) {
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText(content.trimIndent().trim() + "\n")
        }
    }

    private fun kotlinString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
