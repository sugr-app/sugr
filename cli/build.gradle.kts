plugins {
    application
    id("org.graalvm.buildtools.native") version "0.10.3"
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
    implementation("info.picocli:picocli:4.7.6")
    implementation(project(":bridge"))
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "com.sugr.cli.Main"
    applicationName = "sugr"
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("sugr")
        }
    }
}
