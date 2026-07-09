package org.tatrman.kantheon.hebe.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlinx.coroutines.runBlocking

/**
 * The three `secrets_backend` impls (P2 Stage 2.3 T2) + the `SecretRef` scheme
 * front, exercised against temp dirs / fake env (no real keychain).
 */
class SecretsBackendsSpec :
    StringSpec({

        "FileSecretStore round-trips set/get/list/delete and persists" {
            runTest {
                val dir = Files.createTempDirectory("hebe-secrets")
                val store = FileSecretStore(dir.resolve("secrets.json"))
                store.set("llm", "k-123".toByteArray())
                store.set("telegram", "t-456".toByteArray())
                String(store.get("llm")!!) shouldBe "k-123"
                store.list() shouldContainExactlyInAnyOrder listOf("llm", "telegram")
                store.delete("llm") shouldBe true
                store.get("llm").shouldBeNull()
                // A fresh instance over the same file sees the persisted state.
                String(FileSecretStore(dir.resolve("secrets.json")).get("telegram")!!) shouldBe "t-456"
            }
        }

        "FileSecretStore creates the secrets file 0600 on POSIX" {
            runTest {
                val dir = Files.createTempDirectory("hebe-secrets-perms")
                val file = dir.resolve("secrets.json")
                val isPosix = file.fileSystem.supportedFileAttributeViews().contains("posix")
                if (isPosix) {
                    FileSecretStore(file).set("llm", "k-123".toByteArray())
                    val perms = Files.getPosixFilePermissions(file)
                    java.nio.file.attribute.PosixFilePermissions
                        .toString(perms) shouldBe "rw-------"
                }
            }
        }

        "K8sSecretStore reads a mounted file, then falls back to env" {
            runTest {
                val mount = Files.createTempDirectory("hebe-k8s-secrets")
                Files.writeString(mount.resolve("pg"), "pg-secret")
                val store = K8sSecretStore(mountDir = mount, env = mapOf("HEBE_SECRET_API_KEY" to "env-key"))
                String(store.get("pg")!!) shouldBe "pg-secret"
                String(store.get("api.key")!!) shouldBe "env-key"
                store.get("missing").shouldBeNull()
            }
        }

        "K8sSecretStore is read-only (cluster owns the Secret)" {
            runTest {
                val store = K8sSecretStore(mountDir = Files.createTempDirectory("k8s-ro"), env = emptyMap())
                store.set("x", "y".toByteArray()) // no-op
                store.get("x").shouldBeNull()
                store.delete("x") shouldBe false
            }
        }

        "SecretsStoreFactory selects the impl by axis" {
            val dir = Files.createTempDirectory("hebe-factory")
            SecretsStoreFactory.create(SecretsBackend.FILE, dir).shouldBeInstanceOf<FileSecretStore>()
            SecretsStoreFactory.create(SecretsBackend.K8S, dir).shouldBeInstanceOf<K8sSecretStore>()
            SecretsStoreFactory.create(SecretsBackend.KEYCHAIN, dir).shouldBeInstanceOf<OsKeychainSecretStore>()
        }

        "SecretRef resolves keychain/secret/file schemes through the store and env separately" {
            runTest {
                val dir = Files.createTempDirectory("hebe-ref")
                val store = FileSecretStore(dir.resolve("s.json"))
                store.set("pg", "pg-val".toByteArray())
                val env = mapOf("MY_ENV" to "env-val")
                SecretRef.resolve("keychain:pg", store, env) shouldBe "pg-val"
                SecretRef.resolve("secret:pg", store, env) shouldBe "pg-val"
                SecretRef.resolve("file:pg", store, env) shouldBe "pg-val"
                SecretRef.resolve("pg", store, env) shouldBe "pg-val"
                SecretRef.resolve("env:MY_ENV", store, env) shouldBe "env-val"
            }
        }

        "SecretRef rejects an unknown scheme" {
            runTest {
                val dir = Files.createTempDirectory("hebe-ref2")
                try {
                    SecretRef.resolve("vault:pg", FileSecretStore(dir.resolve("s.json")), emptyMap())
                    throw AssertionError("expected ConfigValidationException")
                } catch (e: ConfigValidationException) {
                    (e.message?.contains("vault") ?: false) shouldBe true
                }
            }
        }
    })

private fun runTest(block: suspend () -> Unit) = runBlocking { block() }
