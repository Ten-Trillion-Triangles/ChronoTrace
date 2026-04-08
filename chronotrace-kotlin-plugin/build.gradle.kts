plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
