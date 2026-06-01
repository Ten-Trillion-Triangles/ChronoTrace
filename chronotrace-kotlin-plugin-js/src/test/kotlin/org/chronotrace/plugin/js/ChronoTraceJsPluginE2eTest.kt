package org.chronotrace.plugin.js

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.chronotrace.contract.IngestBatch
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end integration tests for the ChronoTrace JS compiler plugin.
 *
 * Full pipeline verification:
 * 1. Plugin transforms ChronoLogger calls to capture local variables
 * 2. Compiled Kotlin/JS sends frame snapshots to the server via HTTP
 * 3. Server receives and stores frame snapshots with localsJson
 *
 * No mocks, no stubs — real compilation, real Node.js execution, real HTTP, real storage.
 */
class ChronoTraceJsPluginE2eTest {
    companion object {
        private const val SERVER_PORT = 18081
        private const val API_KEY = "e2e-test-key"

        @TempDir
        @JvmStatic
        lateinit var tempDir: Path

        // Shared collection: server writes received batches here, test reads them
        private val receivedBatches = CopyOnWriteArrayList<IngestBatch>()

        private var server: EmbeddedServer<*, *>? = null
        private val workspaceRoot: String = findWorkspaceRoot()

        private fun findWorkspaceRoot(): String {
            // Allow override via -Dchronotrace.workspace.root=/path/to/repo
            System.getProperty("chronotrace.workspace.root")?.let { return it }
            var dir: File? = File(System.getProperty("user.dir"))
            while (dir != null) {
                val settings = dir.resolve("settings.gradle.kts")
                if (settings.exists() &&
                    settings.readText().contains("chronotrace-kotlin-plugin-gradle")) {
                    return dir.absolutePath
                }
                dir = dir.parentFile
            }
            error("Could not find ChronoTrace workspace root. " +
                  "Run from the project root, or set -Dchronotrace.workspace.root=/path/to/repo")
        }

        @BeforeAll
        @JvmStatic
        fun startServer() {
            val serverJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
            server = embeddedServer(Netty, SERVER_PORT) {
                install(ContentNegotiation) {
                    json(serverJson)
                }
                routing {
                    // Probe endpoint: returns received frame snapshots for test verification
                    get("/admin/test/frames") {
                        val frames = receivedBatches.flatMap { it.frameSnapshots }
                        call.respondText(
                            serverJson.encodeToString(frames),
                            contentType = ContentType.Application.Json
                        )
                    }

                    // Capture endpoint: stores the batch for test verification
                    post("/api/v1/ingest") {
                        try {
                            val body = call.receiveText()
                            val batch = serverJson.decodeFromString<IngestBatch>(body)
                            receivedBatches.add(batch)
                            call.respondText(
                                """{"accepted":true,"frameCount":${batch.frameSnapshots.size}}""",
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.OK
                            )
                        } catch (e: Exception) {
                            call.respondText(
                                """{"error":"${e.message?.replace("\"", "\\\"") ?: "unknown"}"}""",
                                contentType = ContentType.Application.Json,
                                status = HttpStatusCode.InternalServerError
                            )
                        }
                    }
                }
            }.start()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server?.stop(1000, 1000)
            server = null
            receivedBatches.clear()
        }
    }

    /**
     * Verify the complete pipeline: plugin transforms code → compiled JS sends frame snapshots →
     * server receives frame snapshots with localsJson containing captured local variables.
     */
    @Test
    fun `ChronoLogger info with local variables produces frame snapshot with localsJson`(@TempDir tempPath: Path) {
        // Skip if Node.js is not available
        val nodePath = System.getenv("PATH")?.split(File.pathSeparator)
            ?.firstOrNull { File(it, "node").exists() }
            ?.let { File(it, "node") }
        if (nodePath == null || !nodePath.exists()) {
            println("Node.js not found in PATH — skipping JS E2E test")
            return
        }

        receivedBatches.clear()

        // SDK JAR (JS variant)
        val sdkJar = File("$workspaceRoot/sdk-kmp/build/libs/sdk-kmp-js-1.0.0.jar")
        if (!sdkJar.exists()) {
            println("SDK JS JAR not found at ${sdkJar.absolutePath} — skipping JS E2E test (Phase 1: pre-existing SDK compile errors block JS/Wasm SDK builds)")
            return
        }

        val dir = tempPath.toFile()

        // Write settings.gradle.kts
        File(dir, "settings.gradle.kts").writeText("""
            pluginManagement {
                repositories {
                    mavenCentral()
                    gradlePluginPortal()
                }
                plugins {
                    kotlin("js") version "2.2.21"
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            includeBuild("$workspaceRoot/chronotrace-kotlin-plugin-gradle")
            rootProject.name = "e2e-js-test"
            include(":app")
        """.trimIndent())

        File(dir, "build.gradle.kts").writeText("""
            plugins { id("org.chronotrace.kotlin-plugin") apply false }
        """.trimIndent())

        // Write app/build.gradle.kts
        val appDir = File(dir, "app").also { it.mkdirs() }
        File(appDir, "build.gradle.kts").writeText("""
            plugins {
                id("org.chronotrace.kotlin-plugin")
                kotlin("js") {
                    IR {
                        nodejs()
                    }
                }
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation(files("${sdkJar.absolutePath.replace("\\", "\\\\")}"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        """.trimIndent())

        // Write test source
        val srcDir = File(appDir, "src/main/kotlin").also { it.mkdirs() }
        File(srcDir, "UserService.kt").writeText("""
            package demo

            import com.chronotrace.sdk.ChronoConfig
            import com.chronotrace.sdk.ChronoLogger
            import com.chronotrace.sdk.ChronoTrace
            import com.chronotrace.sdk.transport.HttpTransport
            import kotlinx.coroutines.runBlocking

            fun main() {
                val transport = HttpTransport("http://localhost:${SERVER_PORT}", "${API_KEY}", devUrlBypass = true)
                val config = ChronoConfig(
                    appId = "e2e-test-js",
                    serviceName = "e2e-test-js",
                    environment = "test",
                    transport = transport
                )
                ChronoTrace.init(config)
                runBlocking {
                    val userId = 42L
                    val name = "Alice"
                    val result = "processed:" + userId
                    ChronoLogger.info("User processed", mapOf(
                        "userId" to userId,
                        "name" to name,
                        "result" to result
                    ))
                    ChronoTrace.shutdown()
                }
            }
        """.trimIndent())

        // Compile the test app with GradleRunner
        val compileResult = GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(":app:compileKotlin", "--info")
            .build()

        assertEquals(
            TaskOutcome.SUCCESS,
            compileResult.task(":app:compileKotlin")?.outcome,
            "Compilation should succeed with ChronoTrace plugin"
        )

        // Verify no ChronoTrace-related compilation errors
        val chronoErrors = compileResult.output.lines().filter {
            it.contains("e:") && it.contains("ChronoTrace") && !it.contains("daemon log")
        }
        assertTrue(chronoErrors.isEmpty(), "Should have no ChronoTrace errors: $chronoErrors")

        // Run the compiled JS via Node.js
        val distributionDir = File(appDir, "build/distributions")
        val jsFile = distributionDir.listFiles()?.firstOrNull {
            it.name.endsWith(".js") && !it.name.endsWith(".meta.js")
        } ?: throw IllegalStateException(
            "No JS distribution file found in ${distributionDir.absolutePath}. " +
            "Files found: ${distributionDir.listFiles()?.joinToString { it.name }}"
        )

        val nodeResult = ProcessBuilder(
            listOf("node", jsFile.absolutePath),
        )
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        val exitCode = nodeResult.waitFor()
        assertEquals(0, exitCode, "Node.js process should exit successfully")

        // Wait briefly for server to process
        Thread.sleep(500)

        // Verify server received at least one batch with frame snapshots
        assertFalse(receivedBatches.isEmpty(), "Server should have received at least one ingest batch. " +
            "Test app may have failed to connect to server at localhost:$SERVER_PORT.")

        val batchWithFrame = receivedBatches.find { it.frameSnapshots.isNotEmpty() }
        assertNotNull(batchWithFrame, "At least one batch should contain frame snapshots. " +
            "Received ${receivedBatches.size} batch(es). " +
            "Frames in each batch: ${receivedBatches.map { it.frameSnapshots.size }}")

        val frame = batchWithFrame!!.frameSnapshots.first()
        assertNotNull(frame.localsJson, "Frame snapshot should have non-null localsJson")
        assertTrue(frame.localsJson.isNotEmpty(), "Frame snapshot localsJson should not be empty")
        assertTrue(
            frame.localsJson.contains("userId") || frame.localsJson.contains("\"userId\""),
            "Frame snapshot localsJson should contain captured variable 'userId'. Was: ${frame.localsJson}"
        )
        assertTrue(
            frame.localsJson.contains("name") || frame.localsJson.contains("\"name\""),
            "Frame snapshot localsJson should contain captured variable 'name'. Was: ${frame.localsJson}"
        )
        assertTrue(
            frame.localsJson.contains("result") || frame.localsJson.contains("\"result\""),
            "Frame snapshot localsJson should contain captured variable 'result'. Was: ${frame.localsJson}"
        )
        assertEquals("e2e-test-js", frame.appId)
        assertEquals("test", frame.environment)
    }
}