package org.chronotrace.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class ChronoTraceKotlinPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val compilerProject = project.rootProject.project(":chronotrace-kotlin-plugin")
        val pluginJar = compilerProject.tasks.named("jar", Jar::class.java)

        project.tasks.withType(KotlinCompilationTask::class.java).configureEach { task ->
            task.dependsOn(pluginJar)
            task.compilerOptions.freeCompilerArgs.addAll(
                project.provider {
                    listOf("-Xplugin=${pluginJar.get().archiveFile.get().asFile.absolutePath}")
                },
            )
        }
    }
}
