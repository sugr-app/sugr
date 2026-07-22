plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":bridge"))

    // Test-only: BindProcessorTest compiles generated code for real (see its javadoc for
    // why -proc:only alone isn't enough), and that generated code references
    // com.sugr.core.Application - main sourceSet still has no dependency on core.
    testImplementation(project(":core"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
