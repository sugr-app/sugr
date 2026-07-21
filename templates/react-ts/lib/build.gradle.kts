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
    mainClass = "app.__pkg__.Main"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("__APP_NAME__")
            buildArgs.add("--enable-native-access=ALL-UNNAMED")
            // native-image defaults to the console subsystem on Windows, which pops
            // up a cmd window alongside the app's own GUI window - these two linker
            // flags switch it to the Windows GUI subsystem instead (ENTRY is needed
            // because /SUBSYSTEM:WINDOWS otherwise expects a WinMain entry point,
            // not the standard main() native-image generates).
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                buildArgs.add("-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS")
                buildArgs.add("-H:NativeLinkerOption=/ENTRY:mainCRTStartup")
            }
        }
    }
}
