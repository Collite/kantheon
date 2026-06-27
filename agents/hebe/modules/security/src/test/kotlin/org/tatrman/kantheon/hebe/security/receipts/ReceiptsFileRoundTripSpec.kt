package org.tatrman.kantheon.hebe.security.receipts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.kantheon.hebe.api.PartialReceipt
import java.nio.file.Files

/**
 * The file [Receipts] log routed through the shared [ReceiptChain] (P3 S3.2 review fix):
 * proves the append→verify round trip holds with the seed-derived public key, that `seq`
 * is NOT in the hashed payload (the chain links via `prev_hash`/`self_hash`), and that
 * the self-healing `public.key` is rewritten on `init()`.
 */
class ReceiptsFileRoundTripSpec :
    StringSpec({
        fun partial(turn: String) =
            PartialReceipt(
                sessionId = "s1",
                turnId = turn,
                tool = "echo",
                argsRedacted = "{}",
                risk = "low",
                durationMs = 5,
                ok = true,
            )

        "append then verify succeeds against the seed-derived public key" {
            val dir = Files.createTempDirectory("hebe-receipts")
            val key = Ed25519PrivateKey.generate()
            val receipts = Receipts(dir, key).init()
            receipts.append(partial("t1"))
            receipts.append(partial("t2"))

            val result = ReceiptVerifier().verifyDirectory(dir, key.publicKeyBytes())
            result.shouldBeInstanceOf<VerifyResult.Ok>().records shouldBe 2
        }

        "the canonical payload does not include seq (chain links via prev/self hash)" {
            val dir = Files.createTempDirectory("hebe-receipts")
            val key = Ed25519PrivateKey.generate()
            Receipts(dir, key).init().append(partial("t1"))

            val line =
                Files
                    .list(dir)
                    .filter { it.toString().endsWith(".log") }
                    .findFirst()
                    .get()
            val receipt = Receipt.fromJson(Files.readAllLines(line).first())
            // Recompute via the shared canonical builder (no seq) — must match what was signed.
            val canonical =
                CanonicalJson.serializeCanonical(
                    ReceiptChain.canonicalEntries(
                        ts = receipt.ts,
                        sessionId = receipt.sessionId,
                        turnId = receipt.turnId,
                        tool = receipt.tool,
                        argsRedacted = receipt.argsRedacted,
                        risk = receipt.risk,
                        approvalRequired = receipt.approval.required,
                        durationMs = receipt.durationMs,
                        ok = receipt.ok,
                        resultHash = receipt.resultHash,
                        prevHash = receipt.prevHash,
                    ),
                )
            ReceiptChain.selfHashOf(canonical) shouldBe receipt.selfHash
        }

        "init rewrites public.key so a stale key self-heals on upgrade" {
            val dir = Files.createTempDirectory("hebe-receipts")
            val key = Ed25519PrivateKey.generate()
            val pubKeyPath = dir.resolve("public.key")
            Files.createDirectories(dir)
            Files.writeString(pubKeyPath, "stale-garbage-from-the-old-random-key")

            Receipts(dir, key).init()

            val expected =
                java.util.Base64
                    .getEncoder()
                    .encodeToString(key.publicKeyBytes())
            Files.readString(pubKeyPath) shouldBe expected
        }
    })
