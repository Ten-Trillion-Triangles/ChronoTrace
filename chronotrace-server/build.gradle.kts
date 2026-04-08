plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("io.ktor.plugin")
    application
}

application {
    mainClass = "org.chronotrace.server.ChronoTraceServerKt"
}

dependencies {
    implementation(project(":chronotrace-contract"))
    implementation("com.clickhouse:clickhouse-jdbc:0.9.1")
    implementation("io.ktor:ktor-server-core-jvm:3.1.1")
    implementation("io.ktor:ktor-server-netty-jvm:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.1.1")
    implementation("io.ktor:ktor-server-websockets-jvm:3.1.1")
    implementation("io.ktor:ktor-server-status-pages-jvm:3.1.1")
    implementation("io.ktor:ktor-server-call-logging-jvm:3.1.1")
    implementation("redis.clients:jedis:5.2.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.1")
}

tasks.test {
    useJUnitPlatform()
}
