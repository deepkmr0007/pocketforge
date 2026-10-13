package dev.pocketforge.engine.projects

import dev.pocketforge.engine.common.PfException

data class TemplateInput(
    val name: String,
    val packageId: String,
    val minSdk: Int = 26,
    val includeReadme: Boolean = true,
    val license: License = License.NONE,
    val author: String = "",
    val year: Int = java.time.Year.now().value,
)

enum class License(val label: String) { NONE("No license"), MIT("MIT"), APACHE2("Apache-2.0") }

interface ProjectTemplate {
    val id: String
    val title: String
    val description: String
    val language: String
    val needsPackageId: Boolean
    /** Prerequisites shown in the wizard and written to the README — never hidden. */
    val prerequisites: List<String>
    fun files(input: TemplateInput): Map<String, String>
}

object PackageNames {
    private val keywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null",
        "fun", "val", "var", "object", "typealias", "when", "is", "in", "as",
    )

    /** Returns null when valid, otherwise an actionable reason. Rules follow Android applicationId rules. */
    fun validate(pkg: String): String? {
        if (pkg.isBlank()) return "Package name is required."
        if (pkg.length > 150) return "Package name is too long (max 150)."
        val parts = pkg.split('.')
        if (parts.size < 2) return "Use at least two segments, e.g. com.example.app."
        for (p in parts) {
            if (p.isEmpty()) return "Package name has an empty segment (double dot or leading/trailing dot)."
            if (!p[0].isLetter() || p[0].code > 127) return "Segment '$p' must start with an ASCII letter."
            if (!p.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '_' }) return "Segment '$p' may only contain letters, digits and _."
            if (p in keywords) return "Segment '$p' is a reserved keyword."
        }
        return null
    }

    fun suggestFrom(name: String): String {
        val slug = name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "app" }
        val seg = if (slug[0].isLetter()) slug else "app$slug"
        return "com.example.$seg"
    }
}

object ProjectNames {
    fun validate(name: String): String? = when {
        name.isBlank() -> "Project name is required."
        name.length > 64 -> "Keep the name under 64 characters."
        name.any { it in "/\\:*?\"<>|" || it.code < 32 } -> "Name cannot contain / \\ : * ? \" < > |"
        name.trim() == "." || name.trim() == ".." -> "That name is reserved."
        else -> null
    }
}

object Templates {
    val all: List<ProjectTemplate> = listOf(AndroidComposeTemplate, KotlinJvmTemplate, WebTemplate, PythonTemplate, EmptyTemplate)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: throw PfException("template.unknown", "Unknown template '$id'.")

    internal fun common(input: TemplateInput, t: ProjectTemplate, extraReadme: String): Map<String, String> {
        val m = linkedMapOf<String, String>()
        if (input.includeReadme) {
            m["README.md"] = buildString {
                append("# ${input.name}\n\n")
                append("Created with PocketForge IDE from the **${t.title}** template.\n\n")
                append("## Prerequisites\n\n")
                t.prerequisites.forEach { append("- $it\n") }
                append("\n").append(extraReadme).append("\n")
            }
        }
        when (input.license) {
            License.MIT -> m["LICENSE"] = mit(input)
            License.APACHE2 -> m["LICENSE"] = "Copyright ${input.year} ${input.author}\n\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\n    http://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software\ndistributed under the License is distributed on an \"AS IS\" BASIS,\nWITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\nSee the License for the specific language governing permissions and\nlimitations under the License.\n"
            License.NONE -> {}
        }
        return m
    }

    private fun mit(i: TemplateInput) = """MIT License

Copyright (c) ${i.year} ${i.author}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"""
}

private const val GITIGNORE_GRADLE = "*.iml\n.gradle/\n/local.properties\n.idea/\n.DS_Store\nbuild/\n/captures\n.externalNativeBuild\n.cxx\n*.jks\n*.keystore\nkeystore.properties\n"

object AndroidComposeTemplate : ProjectTemplate {
    override val id = "android-compose"
    override val title = "Android app (Kotlin + Jetpack Compose)"
    override val description = "Single-activity Material 3 app. Builds with Android Gradle Plugin on a desktop, CI, or a compatible build host."
    override val language = "Kotlin"
    override val needsPackageId = true
    override val prerequisites = listOf(
        "JDK 17 or newer",
        "Android SDK Platform 36 and Build-Tools (installed by Android Studio or sdkmanager)",
        "Gradle 8.14+ (run `gradle wrapper` once, or open the folder in Android Studio)",
        "Internet access on the build machine to download Gradle plugins and libraries the first time",
    )

    override fun files(input: TemplateInput): Map<String, String> {
        val pkgPath = input.packageId.replace('.', '/')
        val m = Templates.common(input, this, "## Build\n\n```\ngradle wrapper\n./gradlew assembleDebug\n```\nThe debug APK appears in `app/build/outputs/apk/debug/`.\n").toMutableMap()
        m[".gitignore"] = GITIGNORE_GRADLE
        m["gradle.properties"] = "org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\nkotlin.code.style=official\n"
        m["settings.gradle.kts"] = """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "${input.name.replace("\"", "")}"
include(":app")
"""
        m["build.gradle.kts"] = """plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
"""
        m["app/build.gradle.kts"] = """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "${input.packageId}"
    compileSdk = 36

    defaultConfig {
        applicationId = "${input.packageId}"
        minSdk = ${input.minSdk}
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
"""
        m["app/src/main/AndroidManifest.xml"] = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="${xmlEscape(input.name)}"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
        m["app/src/main/java/$pkgPath/MainActivity.kt"] = """package ${input.packageId}

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Greeting("${kotlinEscape(input.name)}") }
            }
        }
    }
}

@Composable
fun Greeting(name: String) {
    Box(contentAlignment = Alignment.Center) {
        Text(text = greetingFor(name), style = MaterialTheme.typography.headlineMedium)
    }
}

fun greetingFor(name: String): String = "Hello from ${'$'}name!"
"""
        m["app/src/test/java/$pkgPath/GreetingTest.kt"] = """package ${input.packageId}

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingTest {
    @Test
    fun greetingIncludesName() {
        assertEquals("Hello from Demo!", greetingFor("Demo"))
    }
}
"""
        return m
    }
}

object KotlinJvmTemplate : ProjectTemplate {
    override val id = "kotlin-jvm"
    override val title = "Kotlin console app (JVM)"
    override val description = "Command-line Kotlin program with a unit test."
    override val language = "Kotlin"
    override val needsPackageId = true
    override val prerequisites = listOf("JDK 17 or newer", "Gradle 8.14+ (or `gradle wrapper` once)")
    override fun files(input: TemplateInput): Map<String, String> {
        val pkgPath = input.packageId.replace('.', '/')
        val m = Templates.common(input, this, "## Run\n\n```\ngradle run\ngradle test\n```\n").toMutableMap()
        m[".gitignore"] = GITIGNORE_GRADLE
        m["settings.gradle.kts"] = "rootProject.name = \"${input.name.replace("\"", "")}\"\n"
        m["build.gradle.kts"] = """plugins {
    kotlin("jvm") version "2.2.20"
    application
}

repositories { mavenCentral() }

dependencies { testImplementation(kotlin("test")) }

kotlin { jvmToolchain(17) }
application { mainClass.set("${input.packageId}.MainKt") }
tasks.test { useJUnitPlatform() }
"""
        m["src/main/kotlin/$pkgPath/Main.kt"] = "package ${input.packageId}\n\nfun add(a: Int, b: Int): Int = a + b\n\nfun main() {\n    println(\"Hello from ${kotlinEscape(input.name)}! 2 + 3 = ${'$'}{add(2, 3)}\")\n}\n"
        m["src/test/kotlin/$pkgPath/MainTest.kt"] = "package ${input.packageId}\n\nimport kotlin.test.Test\nimport kotlin.test.assertEquals\n\nclass MainTest {\n    @Test\n    fun addsNumbers() = assertEquals(5, add(2, 3))\n}\n"
        return m
    }
}

object WebTemplate : ProjectTemplate {
    override val id = "web"
    override val title = "Web page (HTML/CSS/JS)"
    override val description = "Static site you can preview offline in the in-app preview."
    override val language = "HTML"
    override val needsPackageId = false
    override val prerequisites = listOf("Nothing — open index.html in any browser")
    override fun files(input: TemplateInput): Map<String, String> {
        val m = Templates.common(input, this, "Open `index.html` to preview.").toMutableMap()
        m["index.html"] = """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${xmlEscape(input.name)}</title>
  <link rel="stylesheet" href="style.css">
</head>
<body>
  <main>
    <h1>${xmlEscape(input.name)}</h1>
    <button id="btn">Count: 0</button>
  </main>
  <script src="app.js"></script>
</body>
</html>
"""
        m["style.css"] = "body { font-family: system-ui, sans-serif; margin: 0; display: grid; place-items: center; min-height: 100vh; }\nbutton { font-size: 1.25rem; padding: .6rem 1.2rem; border-radius: .6rem; }\n"
        m["app.js"] = "'use strict';\nlet count = 0;\nconst btn = document.getElementById('btn');\nbtn.addEventListener('click', () => { count += 1; btn.textContent = `Count: ${'$'}{count}`; });\n"
        return m
    }
}

object PythonTemplate : ProjectTemplate {
    override val id = "python"
    override val title = "Python script"
    override val description = "Python 3 module with a unittest."
    override val language = "Python"
    override val needsPackageId = false
    override val prerequisites = listOf("Python 3.9+ on a computer or a compatible terminal app (not bundled)")
    override fun files(input: TemplateInput): Map<String, String> {
        val m = Templates.common(input, this, "## Run\n\n```\npython3 main.py\npython3 -m unittest\n```\n").toMutableMap()
        m["main.py"] = "def add(a: int, b: int) -> int:\n    return a + b\n\n\nif __name__ == \"__main__\":\n    print(f\"2 + 3 = {add(2, 3)}\")\n"
        m["test_main.py"] = "import unittest\n\nfrom main import add\n\n\nclass AddTest(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n\n\nif __name__ == \"__main__\":\n    unittest.main()\n"
        m[".gitignore"] = "__pycache__/\n*.pyc\n.venv/\n.env\n"
        return m
    }
}

object EmptyTemplate : ProjectTemplate {
    override val id = "empty"
    override val title = "Empty project"
    override val description = "Just a folder (and README if selected)."
    override val language = "Text"
    override val needsPackageId = false
    override val prerequisites = listOf("None")
    override fun files(input: TemplateInput): Map<String, String> = Templates.common(input, this, "")
}

internal fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
internal fun kotlinEscape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
