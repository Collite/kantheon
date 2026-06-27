package org.tatrman.kantheon.hebe.cli.doctor

import org.tatrman.kantheon.hebe.config.Axes
import org.tatrman.kantheon.hebe.config.PlatformIdentity

/**
 * Keycloak / platform-identity doctor checks (P2 Stage 2.3 T6), registered into
 * the [AxisAwareDoctor] matrix. Gated on `platform_identity = keycloak`, with
 * the required-vs-probed demotion of the resolved profile. Probes are injected
 * so the unit test stays offline.
 */
object KeycloakChecks {
    fun usesKeycloak(axes: Axes): Boolean = axes.security.platformIdentity == PlatformIdentity.KEYCLOAK

    /**
     * [reachable] = Keycloak realm answered; [canMint] = an OBO token minted for
     * the bound user; [unresolvedSecretRefs] = configured secret-refs that failed
     * to resolve (empty ⇒ all good).
     */
    fun specs(
        reachable: suspend () -> Boolean,
        canMint: suspend () -> Boolean,
        unresolvedSecretRefs: suspend () -> List<String>,
    ): List<DoctorCheckSpec> =
        listOf(
            DoctorCheckSpec("Keycloak", ::usesKeycloak) { axes ->
                demote(
                    axes,
                    if (reachable()) {
                        CheckResult("Keycloak", CheckStatus.Pass, "realm reachable")
                    } else {
                        CheckResult("Keycloak", CheckStatus.Fail, "realm unreachable", hint = "check [security].keycloak_url")
                    },
                )
            },
            DoctorCheckSpec("OBO Mint", ::usesKeycloak) { axes ->
                demote(
                    axes,
                    if (canMint()) {
                        CheckResult("OBO Mint", CheckStatus.Pass, "minted OBO token for bound user")
                    } else {
                        CheckResult("OBO Mint", CheckStatus.Fail, "could not mint OBO token", hint = "check bound_user + client creds")
                    },
                )
            },
            // Secret resolution is local (not a platform reach) → always required.
            DoctorCheckSpec("Secrets", { true }) { _ ->
                val unresolved = unresolvedSecretRefs()
                if (unresolved.isEmpty()) {
                    CheckResult("Secrets", CheckStatus.Pass, "all secret refs resolve")
                } else {
                    CheckResult("Secrets", CheckStatus.Fail, "unresolved: ${unresolved.joinToString(", ")}")
                }
            },
        )

    private fun demote(
        axes: Axes,
        raw: CheckResult,
    ) = AxisAwareDoctor.applyRequirement(AxisAwareDoctor.platformRequirement(axes), raw)
}
