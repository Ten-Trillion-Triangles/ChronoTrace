package org.chronotrace.gradle

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import java.io.File

class ChronoTraceKotlinPlugin : Plugin<Project> {
    companion object {
        const val CHRONOTRACE_PLUGIN_JAR_PROPERTY = "chronotrace.plugin.jar"

        // ChronoTrace K2 IR generation requires the Kotlin K2 compiler, which
        // is available in Kotlin 2.0.0 and later. Older Kotlin versions ship
        // only K1 and will silently produce no instrumentation.
        const val MIN_SUPPORTED_KOTLIN_VERSION = "2.0.0"
    }

    override fun apply(project: Project) {
        val pluginJarPath: String? = project.findProperty(CHRONOTRACE_PLUGIN_JAR_PROPERTY) as? String
            ?: System.getenv("CHRONOTRACE_PLUGIN_JAR")

        val resolvedJarPath: String?
        if (pluginJarPath != null) {
            resolvedJarPath = pluginJarPath
        } else {
            resolvedJarPath = tryResolveViaProjectReference(project)
        }

        if (resolvedJarPath == null) {
            project.logger.error(
                "ChronoTrace Kotlin plugin: failed to resolve compiler plugin JAR. " +
                    "Set `chronotrace.plugin.jar` system property or env var, " +
                    "or include `:chronotrace-kotlin-plugin` in the build."
            )
            throw GradleException(
                "ChronoTrace Kotlin plugin: compiler plugin JAR not found. See error log above."
            )
        }

        val pluginJar = File(resolvedJarPath)
        if (!pluginJar.exists()) {
            project.logger.error(
                "ChronoTrace Kotlin plugin: Plugin JAR not found at $resolvedJarPath."
            )
            throw GradleException(
                "ChronoTrace Kotlin plugin: compiler plugin JAR not found at $resolvedJarPath. " +
                    "See error log above."
            )
        }

        // Soft check: warn (do not throw) if the consumer's Kotlin compiler is
        // older than 2.0.0. K2 IR generation is required for the
        // chronotrace-kotlin-plugin module; pre-K2 compilers will silently
        // produce no instrumentation.
        warnIfUnsupportedKotlinVersion(project)

        project.tasks.withType(KotlinCompilationTask::class.java).configureEach { task ->
            val targetPluginJar = resolveTargetPluginJar(project, task.name, resolvedJarPath)

            if (targetPluginJar != null && File(targetPluginJar).exists()) {
                task.inputs.file(targetPluginJar)
                task.compilerOptions.freeCompilerArgs.addAll(
                    listOf("-Xplugin=$targetPluginJar")
                )
            } else {
                project.logger.warn(
                    "ChronoTrace Kotlin plugin: target-specific JAR not found for task " +
                        "`${task.name}`. Falling back to default plugin JAR."
                )
            }
        }
    }

    /**
     * Logs a warning when the consumer project's Kotlin Gradle plugin is older
     * than the minimum supported version (K2 IR generation requires Kotlin
     * 2.0.0+). This is a soft check — we never block the build on a version
     * mismatch because the user may have set `-Xskip-prerelease-check` or
     * wired the plugin into a build that doesn't expose a Kotlin classpath.
     */
    private fun warnIfUnsupportedKotlinVersion(project: Project) {
        val detectedVersion = detectKotlinVersion(project)
        if (detectedVersion == null) {
            // No Kotlin plugin on the classpath — nothing to compare against.
            return
        }
        if (isVersionLessThan(detectedVersion, MIN_SUPPORTED_KOTLIN_VERSION)) {
            project.logger.warn(
                "ChronoTrace Kotlin plugin: detected Kotlin compiler version " +
                    "$detectedVersion which is older than the minimum supported version " +
                    "$MIN_SUPPORTED_KOTLIN_VERSION. K2 IR generation will not run; " +
                    "please upgrade the Kotlin Gradle plugin to 2.0.0 or later."
            )
        }
    }

    /**
     * Best-effort detection of the Kotlin Gradle plugin version applied to
     * this project. Returns `null` when no Kotlin plugin is on the build
     * classpath, in which case the version check is skipped.
     */
    private fun detectKotlinVersion(project: Project): String? {
        return try {
            val kotlinPlugin = project.plugins.findPlugin("org.jetbrains.kotlin.jvm")
                ?: project.plugins.findPlugin("org.jetbrains.kotlin.multiplatform")
                ?: project.plugins.findPlugin("org.jetbrains.kotlin.android")
                ?: return null
            // The Kotlin Gradle plugin's class is loaded from the build classpath,
            // which always reflects the version the user declared.
            val pluginClass = kotlinPlugin.javaClass
            val packageVersion = pluginClass.`package`?.implementationVersion
                ?: pluginClass.protectionDomain?.codeSource?.location?.toString()
            parseVersionString(packageVersion)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts the first dotted `major.minor.patch` triple from a free-form
     * version string (e.g. a JAR manifest, a `jar:` URL, or plain text).
     */
    private fun parseVersionString(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(raw) ?: return null
        val (major, minor, patch) = match.destructured
        return if (patch.isEmpty()) "$major.$minor.0" else "$major.$minor.$patch"
    }

    /**
     * Lexicographic numeric comparison of two dotted version strings. Returns
     * `true` when `candidate` is strictly less than `floor`.
     */
    private fun isVersionLessThan(candidate: String, floor: String): Boolean {
        val c = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val f = floor.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(c.size, f.size)
        for (i in 0 until maxLen) {
            val cv = c.getOrElse(i) { 0 }
            val fv = f.getOrElse(i) { 0 }
            if (cv < fv) return true
            if (cv > fv) return false
        }
        return false
    }

    private fun resolveTargetPluginJar(project: Project, taskName: String, defaultJarPath: String): String? {
        val rootProject = project.rootProject

        // Target-aware lookup: route Kotlin/JS compile tasks to the JS
        // compiler plugin module and Kotlin/Wasm compile tasks to the Wasm
        // compiler plugin module. Tasks that don't match either target fall
        // through to the JVM/general compiler plugin module.
        val isWasm = taskName.contains("Wasm", ignoreCase = true)
        val isJs = taskName.contains("Js", ignoreCase = true) && !isWasm

        val targetProjectName = when {
            isWasm -> ":chronotrace-kotlin-plugin-wasm"
            isJs -> ":chronotrace-kotlin-plugin-js"
            else -> ":chronotrace-kotlin-plugin"
        }

        return try {
            val targetProject = rootProject.project(targetProjectName)
            val jarTask = targetProject.tasks.named("jar", Jar::class.java).get()
            jarTask.archiveFile.get().asFile.absolutePath
        } catch (e: Exception) {
            defaultJarPath
        }
    }

    private fun tryResolveViaProjectReference(project: Project): String? {
        return try {
            val compilerProject = project.rootProject.project(":chronotrace-kotlin-plugin")
            val jarTask = compilerProject.tasks.named("jar", Jar::class.java).get()
            jarTask.archiveFile.get().asFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
