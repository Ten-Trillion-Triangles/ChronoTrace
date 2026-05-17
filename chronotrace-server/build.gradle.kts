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
    implementation("io.github.oshai:kotlin-logging:7.0.14")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.1.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.2.21")
    testImplementation("org.testcontainers:clickhouse:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.12.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.12.2")
}

tasks.test {
    useJUnitPlatform()
}