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
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
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
