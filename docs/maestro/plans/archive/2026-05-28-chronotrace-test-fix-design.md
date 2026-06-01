# ChronoTrace Test Infrastructure Fix

## Problem Statement

The `ChronoTraceIrGenerationExtensionFunctionCountTest` fails because:
1. The plugin can't find the JAR path via `project.findProperty()` in the test's composite build environment
2. `println` output from the Kotlin daemon isn't captured by GradleRunner's `result.output`

## Solution

Use GradleRunner's `withEnvironment()` to pass the plugin JAR path via the `CHRONOTRACE_PLUGIN_JAR` environment variable, which the plugin already supports as a fallback.

## Implementation

Modify FunctionCountTest to:
1. Remove `gradle.properties` file creation
2. Add `.withEnvironment(mapOf("CHRONOTRACE_PLUGIN_JAR" to pluginJarPath))` to GradleRunner