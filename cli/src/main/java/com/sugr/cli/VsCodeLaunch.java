package com.sugr.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Auto-creates/updates {@code .vscode/launch.json} with "attach" configs for
 * `sugr debug`, so the app's own Run & Debug panel just works - no manual JSON
 * editing needed. Only ever adds these two configs (identified by name); never
 * touches anything else already in the file. VS Code's launch.json is JSONC
 * (comments/trailing commas allowed), so this deliberately does simple text
 * insertion instead of a strict JSON parse/re-serialize, which would choke on
 * or silently strip a user's existing comments.
 */
final class VsCodeLaunch {

    private VsCodeLaunch() {
    }

    static final String BACKEND_CONFIG_NAME = "sugr: attach backend";
    static final String FRONTEND_CONFIG_NAME = "sugr: attach frontend";

    static void ensure(Path appRoot, int backendPort, int frontendPort) {
        Path launchJson = appRoot.resolve(".vscode").resolve("launch.json");
        try {
            if (!Files.isRegularFile(launchJson)) {
                Files.createDirectories(launchJson.getParent());
                Files.writeString(launchJson, freshFile(backendPort, frontendPort));
                System.out.println("[sugr] created " + launchJson
                        + " - open VS Code's Run & Debug panel and pick \"" + BACKEND_CONFIG_NAME
                        + "\" / \"" + FRONTEND_CONFIG_NAME + "\"");
                return;
            }

            String content = Files.readString(launchJson);
            StringBuilder toInsert = new StringBuilder();
            if (!content.contains(BACKEND_CONFIG_NAME)) {
                toInsert.append(indent(backendConfig(backendPort))).append(",\n");
            }
            if (!content.contains(FRONTEND_CONFIG_NAME)) {
                toInsert.append(indent(frontendConfig(frontendPort))).append(",\n");
            }
            if (toInsert.isEmpty()) {
                return;
            }

            int arrayStart = content.indexOf("\"configurations\"");
            int bracket = arrayStart < 0 ? -1 : content.indexOf('[', arrayStart);
            if (bracket < 0) {
                printManualInstructions(launchJson, backendPort, frontendPort);
                return;
            }
            String updated = content.substring(0, bracket + 1) + "\n" + toInsert + content.substring(bracket + 1);
            Files.writeString(launchJson, updated);
            System.out.println("[sugr] added missing debug config(s) to " + launchJson);
        } catch (IOException e) {
            System.err.println("[sugr] couldn't update " + launchJson + ": " + e.getMessage());
            printManualInstructions(launchJson, backendPort, frontendPort);
        }
    }

    private static String freshFile(int backendPort, int frontendPort) {
        return """
                {
                    "version": "0.2.0",
                    "configurations": [
                %s,
                %s
                    ]
                }
                """.formatted(indent(backendConfig(backendPort)), indent(frontendConfig(frontendPort)));
    }

    private static String backendConfig(int port) {
        return """
                {
                    "type": "java",
                    "name": "%s",
                    "request": "attach",
                    "hostName": "localhost",
                    "port": %d
                }""".formatted(BACKEND_CONFIG_NAME, port);
    }

    private static String frontendConfig(int port) {
        return """
                {
                    "type": "msedge",
                    "name": "%s",
                    "request": "attach",
                    "port": %d,
                    "webRoot": "${workspaceFolder}/frontend"
                }""".formatted(FRONTEND_CONFIG_NAME, port);
    }

    private static String indent(String block) {
        return block.lines().map(l -> "        " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static void printManualInstructions(Path launchJson, int backendPort, int frontendPort) {
        System.out.println("[sugr] couldn't locate a \"configurations\" array in " + launchJson
                + " - add these entries to it manually:");
        System.out.println(backendConfig(backendPort) + ",");
        System.out.println(frontendConfig(frontendPort));
    }
}
