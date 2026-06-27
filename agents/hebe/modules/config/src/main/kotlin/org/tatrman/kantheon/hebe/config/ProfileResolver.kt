package org.tatrman.kantheon.hebe.config

/**
 * The raw inputs to axis resolution, separated by source so the precedence
 * ladder is explicit and testable:
 *  - axis precedence: **env axis > file axis > profile default**
 *  - profile precedence: **`HEBE_PROFILE` env > file `profile`**
 *  - instance precedence: **env > file**
 *
 * [fileAxes]/[envAxes] are flat `axisKey -> wireToken` maps (e.g.
 * `"tools.posture" -> "full"`); non-axis keys are ignored.
 */
data class RawAxisConfig(
    val profile: String? = null,
    val envProfile: String? = null,
    val fileAxes: Map<String, String> = emptyMap(),
    val envAxes: Map<String, String> = emptyMap(),
    val instanceId: String? = null,
    val envInstanceId: String? = null,
    /**
     * `tools.enable`/`tools.disable` opt-in lists and the `bound_user` identity
     * are not flat enum axes, so they ride alongside the axis map (env wins over
     * file, as everywhere else).
     */
    val toolsEnable: List<String> = emptyList(),
    val envToolsEnable: List<String>? = null,
    val toolsDisable: List<String> = emptyList(),
    val envToolsDisable: List<String>? = null,
    val boundUser: String? = null,
    val envBoundUser: String? = null,
)

/**
 * Resolves [RawAxisConfig] into a typed, validated [Axes] (Hebe arc P2 Stage
 * 2.1; contracts §5). The single boot-time entry point: load profile → overlay
 * file axes → overlay env axes → parse to enums (validates values) → validate
 * cross-axis invariants → return immutable [Axes]. Any failure throws
 * [ConfigValidationException] (boots-stopping).
 */
object ProfileResolver {
    const val DEFAULT_PROFILE = "local"
    const val DEFAULT_INSTANCE_ID = "local"

    fun resolve(raw: RawAxisConfig): Axes {
        val profileToken = raw.envProfile ?: raw.profile ?: DEFAULT_PROFILE
        val profile =
            Profile.byToken(profileToken)
                ?: throw ConfigValidationException(
                    "unknown profile '$profileToken' — expected one of ${Profile.entries.joinToString(", ") { it.token }}",
                )

        // env axis > file axis > profile default (later entries win the merge).
        val merged = profile.axisDefaults + raw.fileAxes + raw.envAxes

        val explicitInstance = raw.envInstanceId ?: raw.instanceId
        val instanceId = explicitInstance ?: DEFAULT_INSTANCE_ID
        // env opt-in lists / bound user win over file (consistent with axes).
        val toolsEnable = raw.envToolsEnable ?: raw.toolsEnable
        val toolsDisable = raw.envToolsDisable ?: raw.toolsDisable
        val boundUser = raw.envBoundUser ?: raw.boundUser
        val axes = parse(merged, instanceId, toolsEnable, toolsDisable, boundUser)
        validate(axes, instanceProvided = explicitInstance != null)
        return axes
    }

    private fun parse(
        m: Map<String, String>,
        instanceId: String,
        toolsEnable: List<String>,
        toolsDisable: List<String>,
        boundUser: String?,
    ): Axes =
        Axes(
            storage = StorageAxis(StorageBackend.parse(req(m, StorageBackend.key))),
            fs = FsAxis(Durability.parse(req(m, Durability.key))),
            workspace = WorkspaceAxis(WorkspaceBackend.parse(req(m, WorkspaceBackend.key))),
            receipts = ReceiptsAxis(ReceiptsBackend.parse(req(m, ReceiptsBackend.key))),
            platform =
                PlatformAxis(
                    reach = PlatformReach.parse(req(m, PlatformReach.key)),
                    // "—" for local: absent ⇒ null (no platform to be available).
                    availability = m[Availability.key]?.let { Availability.parse(it) },
                ),
            llm = LlmAxis(LlmSource.parse(req(m, LlmSource.key))),
            security =
                SecurityAxis(
                    platformIdentity = PlatformIdentity.parse(req(m, PlatformIdentity.key)),
                    consoleAuth = ConsoleAuth.parse(req(m, ConsoleAuth.key)),
                    secretsBackend = SecretsBackend.parse(req(m, SecretsBackend.key)),
                ),
            otel = OtelAxis(parseBool(req(m, "otel.enabled"), "otel.enabled")),
            capabilities = CapabilitiesAxis(CapabilitiesEnabled.parse(req(m, CapabilitiesEnabled.key))),
            tools =
                ToolsAxis(
                    posture = Posture.parse(req(m, Posture.key)),
                    enable = toolsEnable.toSet(),
                    disable = toolsDisable.toSet(),
                ),
            instanceId = instanceId,
            boundUser = boundUser,
        )

    private fun req(
        m: Map<String, String>,
        key: String,
    ): String = m[key] ?: throw ConfigValidationException("missing axis '$key' (no profile default and no override)")

    private fun parseBool(
        raw: String,
        key: String,
    ): Boolean =
        when (raw) {
            "true" -> true
            "false" -> false
            else -> throw ConfigValidationException("invalid value '$raw' for axis '$key' — expected true or false")
        }

    // Each cross-axis invariant is its own fail-fast — a validation funnel, not
    // branching logic, so the throw count is by design.
    @Suppress("ThrowsCount")
    private fun validate(
        axes: Axes,
        instanceProvided: Boolean,
    ) {
        // The load-bearing invariant: an ephemeral FS (k8s pod) would lose
        // workspace + receipts on restart unless both live in postgres.
        if (axes.fs.durability == Durability.EPHEMERAL) {
            if (axes.workspace.backend != WorkspaceBackend.POSTGRES || axes.receipts.backend != ReceiptsBackend.POSTGRES) {
                throw ConfigValidationException(
                    "fs.durability=ephemeral would lose state: workspace.backend and receipts.backend must both be " +
                        "postgres (got workspace=${axes.workspace.backend.token}, receipts=${axes.receipts.backend.token})",
                )
            }
        }
        // A postgres store is schema-per-instance — it has no meaning without an
        // explicit instance id.
        if (axes.storage.backend == StorageBackend.POSTGRES && !instanceProvided) {
            throw ConfigValidationException(
                "storage.backend=postgres requires an explicit instance_id (schema hebe_<instance_id>)",
            )
        }
        // A platform-reaching profile must declare how available the platform is
        // (the doctor required-vs-probed split keys off it) and must carry an
        // identity (§5.2: identity is required for ANY platform.reach != none).
        if (axes.platform.reach != PlatformReach.NONE) {
            if (axes.platform.availability == null) {
                throw ConfigValidationException(
                    "platform.reach=${axes.platform.reach.token} requires " +
                        "platform.availability (always | intermittent)",
                )
            }
            if (axes.security.platformIdentity == PlatformIdentity.NONE) {
                throw ConfigValidationException(
                    "platform.reach=${axes.platform.reach.token} requires security.platform_identity=keycloak " +
                        "(identity is mandatory for any platform reach)",
                )
            }
        }
    }
}
