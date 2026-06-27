# Plugin Proposal 0000 — Title

**Status**: Draft | Under Review | Accepted | Rejected | Published  
**Author(s)**: Your Name  
**Plugin id**: `my-plugin`  
**Version**: 0.1.0  
**Created**: YYYY-MM-DD  
**Updated**: YYYY-MM-DD  

---

## Summary

One or two sentences describing what this plugin does and why it is useful.

---

## Motivation

What problem does this plugin solve? Who is it for? Include concrete use cases.

---

## Design

### Capabilities required

| Capability / Permission | Why needed |
|---|---|
| `tool = true` | Plugin contributes a tool |
| `http_client = true` | Calls an external API |
| `secrets = ["my-plugin.api-key"]` | Authenticates with the external API |

### Tools contributed

| Tool name | Risk | Description |
|---|---|---|
| `my-plugin:do_something` | Low | Brief description |

### Tool schemas

```json
{
  "name": "my-plugin:do_something",
  "description": "Does something useful.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "input": {
        "type": "string",
        "description": "The input to process"
      }
    },
    "required": ["input"]
  }
}
```

### External dependencies

List any third-party libraries the plugin bundles (not hebe's own libs):

| Library | Version | Purpose |
|---|---|---|
| `com.example:some-lib` | 1.2.3 | Description |

---

## Alternatives considered

What other ways did you consider implementing this? Why did you choose this approach?

---

## Drawbacks

What are the limitations of this plugin? Are there edge cases it doesn't handle?

---

## Prior art

Are there existing tools, libraries, or hebe features that overlap? How does this plugin relate?

---

## Unresolved questions

What is still uncertain about this plugin's design or behaviour?

---

## Distribution

| Field | Value |
|---|---|
| OCI registry reference | `acr.example.com/hebe-plugins/my-plugin:0.1.0` |
| Signature mode | optional / required |
| Publisher key | (hex-encoded Ed25519 public key) |
