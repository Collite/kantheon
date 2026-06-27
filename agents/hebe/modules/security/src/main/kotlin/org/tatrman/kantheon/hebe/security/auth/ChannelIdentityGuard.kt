package org.tatrman.kantheon.hebe.security.auth

import org.tatrman.kantheon.hebe.config.ConfigValidationException
import org.tatrman.kantheon.hebe.config.PlatformIdentity

/**
 * Channel-identity enforcement (P2 Stage 2.3 T5; contracts §5.2). When
 * `platform_identity = keycloak`, an inbound Telegram message must map its
 * `chat_id` to a known Keycloak user via `chat_user_map`; unmapped chats are
 * rejected **before** the agent loop. On `local` (`platform_identity = none`)
 * the channel keeps today's allowlist behaviour (no mapping required).
 */
class ChannelIdentityGuard(
    private val platformIdentity: PlatformIdentity,
    private val chatUserMap: Map<String, String>,
) {
    /**
     * The Keycloak user a chat acts as, or `null` if the message must be
     * rejected. With `platform_identity = none`, identity is not enforced here
     * and the call returns [UNENFORCED].
     */
    fun resolveUser(chatId: String): String? =
        when (platformIdentity) {
            PlatformIdentity.NONE -> UNENFORCED
            PlatformIdentity.KEYCLOAK -> chatUserMap[chatId]
        }

    fun isAllowed(chatId: String): Boolean = resolveUser(chatId) != null

    companion object {
        /** Sentinel for "identity not enforced on this profile" (local). */
        const val UNENFORCED = "@unenforced"

        /**
         * Boot validation (contracts §5.2): on a keycloak profile the
         * `chat_user_map` must include the bound user, or the operator can never
         * act through Telegram. Fails fast.
         */
        fun validateAtBoot(
            platformIdentity: PlatformIdentity,
            chatUserMap: Map<String, String>,
            boundUser: String,
        ) {
            if (platformIdentity == PlatformIdentity.KEYCLOAK && boundUser !in chatUserMap.values) {
                throw ConfigValidationException(
                    "chat_user_map must include the bound user '$boundUser' on a keycloak profile " +
                        "(otherwise the operator cannot act via Telegram)",
                )
            }
        }
    }
}
