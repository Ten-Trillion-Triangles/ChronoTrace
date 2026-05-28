plugins {
    kotlin("jvm")
}

group = "com.chronotrace"

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-subclass:5.14.2")
    testCompileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation(gradleTestKit())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
