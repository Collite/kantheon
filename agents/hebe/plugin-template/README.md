# Hebe Plugin Template

A starting template for building Hebe plugins.

## Prerequisites

- Java 21+
- Gradle 9 (wrapper included)

## Quick Start

1. Copy this template directory as your plugin base
2. Edit `src/main/kotlin/com/example/HelloPlugin.kt` with your plugin class
3. Edit `src/main/resources/plugin.toml` with your plugin metadata
4. Edit `src/main/resources/plugin.properties` with your plugin ID and class

## Plugin Structure

```
src/main/
├── kotlin/com/example/
│   └── YourPlugin.kt      # Main plugin class extending HebePlugin
├── resources/
│   ├── plugin.properties  # PF4J plugin metadata (id, class, version)
│   ├── plugin.toml        # Hebe plugin manifest (api version, capabilities)
│   └── META-INF/services/org.tatrman.kantheon.hebe.plugin.api.HebePlugin  # Service provider
```

## plugin.toml

```toml
hebe_api_version = "0.1.x"
capabilities = ["tool"]
permissions = []
allowlist_domains = []
```

## plugin.properties

```properties
plugin.id=your-plugin-id
plugin.class=com.example.YourPlugin
plugin.version=0.1.0
plugin.provider=your-name
plugin.dependencies=
```

## Building

```bash
./gradlew jar
```

Output: `build/libs/your-plugin-id-0.1.0.jar`

## Testing

```bash
./gradlew jar && cp build/libs/your-plugin-id-0.1.0.jar /tmp/test-plugin.jar
# Then use: hebe plugin install /tmp/test-plugin.jar --unsigned
```

## Publishing

To publish to an OCI registry (requires oras-publish.gradle.kts setup):

```bash
./gradlew orasPublish --repository ghcr.io/your-org/your-plugin
```

## API

Extend `org.tatrman.kantheon.hebe.plugin.api.HebePlugin` and override:

- `tools(host: PluginHost): List<Tool>` — Register tools the plugin provides
- `init(host: PluginHost)` — Called once when plugin loads
- `teardown()` — Called when plugin unloads

### PluginHost

Available via `host`:

- `httpClient(): GatedHttpClient` — HTTP client with domain allowlist
- `createSecretStore(pluginId: String): SecretStore` — Per-plugin secrets
- `pluginId(): String` — The plugin's unique ID
- `pluginVersion(): String` — The plugin's version