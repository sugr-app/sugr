rootProject.name = "sugr"

include("core")
include("bridge")
include("processor")
include("cli")
include("examples:sql-client")
project(":examples:sql-client").projectDir = file("examples/sql-client/lib")
