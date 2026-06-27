# Plugin Developer's Guide

This guide walks you through developing, testing, and distributing a hebe plugin from scratch.

See [plugin-protocol.md](../plugin-protocol.md) for the formal specification. This guide focuses on the practical workflow.

---

## Prerequisites

- JDK 21+
- Gradle 8.x
- A working hebe installation for local testing (`hebe doctor` reports green)
- (Optional) Docker for the OCI registry distribution flow

---

## Step 1: Create your plugin from the template

The in-tree `plugin-template/` is the canonical starting point:

```bash
cp -r plugin-template/ my-plugin
cd my-plugin
```

Rename the plugin id, class name, and package throughout:

```bash
# plugin.properties
plugin.id=my-plugin
plugin.class=com.example.MyPlugin
plugin.version=0.1.0

# plugin.toml
hebe_api_version = "0.1.x"
```

Update `settings.gradle.kts`:

```kotlin
rootProject.name = "my-plugin"
```

---

## Step 2: Understand the plugin module structure

```
my-plugin/
├── build.gradle.kts          ← declares api and plugin-api as compileOnly
├── plugin.properties         ← PF4J metadata
├── plugin.toml               ← hebe manifest (capabilities, permissions, signing)
├── src/
│   └── main/kotlin/com/example/
│       ├── MyPlugin.kt       ← HebePlugin subclass (entry point)
│       └── MyTool.kt         ← one or more Tool implementations
└── src/
    └── test/kotlin/com/example/
        └── MyToolTest.kt     ← unit tests with MockK
```

### Dependency scopes in `build.gradle.kts`

```kotlin
dependencies {
    // MUST be compileOnly — these are provided by the hebe host at runtime
    compileOnly(libs.hebe.api)
    compileOnly(libs.hebe.pluginApi)

    // Your own deps go in implementation or runtimeOnly
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

Never use `implementation` for `hebe-api` or `plugin-api`. Bundling them causes `ClassCastException` when hebe loads the plugin.

---

## Step 3: Implement your plugin

### `HebePlugin` subclass

```kotlin
class MyPlugin(wrapper: PluginWrapper) : HebePlugin(wrapper) {

    override fun tools(host: PluginHost): List<Tool> = listOf(MyTool(host))

    override fun init(host: PluginHost) {
        host.log.info("MyPlugin initialised; plugin_id={}", host.pluginId)
    }

    override fun teardown() {
        // close any resources (connections, thread pools) here
    }
}
```

### `Tool` implementation

```kotlin
class MyTool(private val host: PluginHost) : Tool {

    override val spec = ToolSpec(
        name = "my_plugin:do_something",
        description = "Does something useful.",
        schema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("input") {
                    put("type", "string")
                    put("description", "The input to process")
                }
            }
            putJsonArray("required") { add("input") }
        }
    )

    override val risk = RiskLevel.Low

    override suspend fun invoke(args: JsonObject, ctx: ToolContext): ToolResult {
        val input = args["input"]?.jsonPrimitive?.content
            ?: return ToolResult.Err("missing required argument: input")

        // Use the gated HTTP client if you declared http_client
        // val response = host.http().get("https://api.example.com/process?q=$input")

        return ToolResult.Ok(JsonPrimitive("processed: $input"))
    }
}
```

---

## Step 4: Declare capabilities in `plugin.toml`

Only declare what you use. Undeclared capabilities are denied at runtime.

```toml
hebe_api_version = "0.1.x"

[capabilities]
tool = true

[permissions]
http_client = true          # if you call host.http()
env_read    = false         # if you call host.env("NAME")
secrets     = []            # list named secrets if you call host.secret("name")

[http]
allowlist = ["api.example.com"]
```

---

## Step 5: Write tests

Use MockK to mock `PluginHost`:

```kotlin
class MyToolTest : StringSpec({

    val host = mockk<PluginHost>()
    val tool = MyTool(host)

    "invoke returns processed result" {
        val args = buildJsonObject { put("input", "hello") }
        val ctx = mockk<ToolContext>()

        val result = tool.invoke(args, ctx)

        result shouldBe ToolResult.Ok(JsonPrimitive("processed: hello"))
    }

    "invoke returns error when input missing" {
        val args = buildJsonObject {}
        val ctx = mockk<ToolContext>()

        val result = tool.invoke(args, ctx)

        result shouldBeInstanceOf ToolResult.Err::class
    }
})
```

Run tests:

```bash
./gradlew test
```

---

## Step 6: Build and sideload for local testing

```bash
./gradlew shadowJar
hebe plugin install build/libs/my-plugin-0.1.0-all.jar
hebe plugin list
# my-plugin  v0.1.0  loaded  capabilities=[tool]
```

Test from a chat:

```
hebe run
hebe> do something with input "hello"
agent> [calls my_plugin:do_something] processed: hello
```

To reload after a code change:

```bash
hebe plugin remove my-plugin
./gradlew shadowJar
hebe plugin install build/libs/my-plugin-0.1.0-all.jar
```

---

## Step 7: Publish to an OCI registry

### Local registry (development)

```bash
docker run -d -p 5000:5000 registry:2

./gradlew publishPlugin -Pregistry=localhost:5000/hebe-plugins
# pushes my-plugin:0.1.0

hebe plugin install localhost:5000/hebe-plugins/my-plugin:0.1.0
```

### Remote registry (ACR example)

```bash
az acr login --name myregistry

./gradlew publishPlugin -Pregistry=myregistry.azurecr.io/hebe-plugins

hebe plugin install myregistry.azurecr.io/hebe-plugins/my-plugin:0.1.0
```

---

## Step 8: Sign your plugin (for production)

See [plugin-protocol.md §5](../plugin-protocol.md#5-signing) for the full signing procedure.

Short version:
1. Generate an Ed25519 keypair.
2. Compute the SHA-256 of the archive layer.
3. Sign the hash; set `signing.signature` and `signing.publisher_key` in `plugin.toml`.
4. Ensure your public key is in the hebe config's `publisher_keys` list.
5. Set `plugin_signature_mode = "required"` on the target hebe instance.

---

## Common mistakes

| Mistake | Symptom | Fix |
|---|---|---|
| Bundling `hebe-api` in `implementation` | `ClassCastException` on plugin load | Move to `compileOnly` |
| Missing capability in `plugin.toml` | `PluginCapabilityException` at runtime | Add the capability and permission |
| Domain not in `http.allowlist` | `PluginCapabilityException` on `host.http().get(url)` | Add the domain to `[http].allowlist` |
| Plugin not reloaded after JAR change | Old version still running | `hebe plugin remove` + reinstall |
| `hebe_api_version` mismatch | Plugin stuck in RESOLVED state | Update the version range to match running hebe |
| Using `Runtime.exec()` instead of `host.http()` | Bypasses domain allowlist | Use `GatedHttpClient` for all HTTP |

---

## Useful references

- [Plugin protocol spec](../plugin-protocol.md) — the formal contract
- `modules/plugin-api/src/main/kotlin/com/hebe/plugin/` — the interfaces your plugin implements
- `plugin-template/` — the in-tree example with a working Gradle build
- `docs/rfcs/` — how to propose ABI changes
