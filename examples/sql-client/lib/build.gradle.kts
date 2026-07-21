plugins {
    application
    id("org.graalvm.buildtools.native") version "0.10.3"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":core"))
    annotationProcessor(project(":processor"))
}

application {
    mainClass = "com.sugr.examples.sqlclient.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("sql-client")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
        }
    }
}
