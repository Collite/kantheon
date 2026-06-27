package org.tatrman.kantheon.capabilities.registry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.capabilities.asCapability
import org.tatrman.kantheon.capabilities.toolCapability

class VersionResolverSpec :
    StringSpec({

        "parse strips :vN suffix" {
            VersionResolver.parse("model.fit.arima:v1") shouldBe ("model.fit.arima" to "v1")
            VersionResolver.parse("model.fit.arima:v12") shouldBe ("model.fit.arima" to "v12")
        }

        "parse returns null version when suffix missing" {
            VersionResolver.parse("model.fit.arima") shouldBe ("model.fit.arima" to null)
        }

        "parse leaves non-version suffixes alone" {
            VersionResolver.parse("model.fit.arima:foo") shouldBe ("model.fit.arima:foo" to null)
        }

        "resolveLatest picks the highest semver suffix" {
            val v1 =
                RegistryEntry(
                    toolCapability("model.fit.arima:v1").asCapability(),
                    registrationId = "rid-1",
                    lastHeartbeatAt = null,
                    registeredAt = java.time.Instant.EPOCH,
                )
            val v2 = v1.copy(capability = toolCapability("model.fit.arima:v2").asCapability(), registrationId = "rid-2")
            val v10 =
                v1.copy(
                    capability = toolCapability("model.fit.arima:v10").asCapability(),
                    registrationId = "rid-10",
                )

            VersionResolver.resolveLatest(listOf(v1, v2, v10))!!.registrationId shouldBe "rid-10"
        }

        "InMemoryRegistry.get(unsuffixed) returns the latest matching version" {
            val reg = InMemoryRegistry()
            reg.register(toolCapability("model.fit.arima:v1").asCapability())
            reg.register(toolCapability("model.fit.arima:v2").asCapability())
            val latest = reg.get("model.fit.arima")
            latest.shouldNotBeNull()
            latest.capability.tool.capabilityId shouldBe "model.fit.arima:v2"
        }

        "InMemoryRegistry.get(specific) returns exactly that version" {
            val reg = InMemoryRegistry()
            reg.register(toolCapability("model.fit.arima:v1").asCapability())
            reg.register(toolCapability("model.fit.arima:v2").asCapability())
            reg
                .get("model.fit.arima:v1")
                ?.capability
                ?.tool
                ?.capabilityId shouldBe "model.fit.arima:v1"
            reg
                .get("model.fit.arima:v2")
                ?.capability
                ?.tool
                ?.capabilityId shouldBe "model.fit.arima:v2"
            reg.get("model.fit.arima:v3") shouldBe null
        }
    })
