package org.tatrman.kantheon.hebe.security.policy

import org.tatrman.kantheon.hebe.api.Validator
import org.tatrman.kantheon.hebe.config.HebeConfig
import java.nio.file.Path

object PolicyChain {
    fun standard(
        config: HebeConfig,
        workspaceRoot: Path,
    ): List<Validator> {
        val validators = mutableListOf<Validator>()

        validators.add(
            AutonomyValidator(config.autonomy.level),
        )

        validators.add(
            WorkspaceBoundaryValidator(
                forbiddenPaths = config.security.forbiddenPaths,
                workspaceRoot = workspaceRoot,
            ),
        )

        validators.add(
            CommandPolicyValidator(
                allowedCommandGlobs = config.security.allowedCommandGlobs,
                forbiddenCommandGlobs = config.security.forbiddenCommandGlobs,
            ),
        )

        val ssrfGuard = SsrfGuard()
        validators.add(
            DomainAllowlistValidator(
                allowedDomains = config.security.httpAllowlistDomains,
                ssrfGuard = ssrfGuard,
            ),
        )

        validators.add(
            PromptInjectionValidator(),
        )

        return validators
    }
}
