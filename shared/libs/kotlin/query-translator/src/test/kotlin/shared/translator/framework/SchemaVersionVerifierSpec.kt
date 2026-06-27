package shared.translator.framework

import org.tatrman.plan.v1.PipelineContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SchemaVersionVerifierSpec :
    StringSpec({

        fun ctx(version: String): PipelineContext = PipelineContext.newBuilder().setModelVersion(version).build()

        "matching versions return Ok" {
            SchemaVersionVerifier.verifyContext(ctx("v42"), "v42") shouldBe VerificationResult.Ok
        }

        "mismatched versions return MismatchWarn carrying expected + got" {
            val r = SchemaVersionVerifier.verifyContext(ctx("v40"), "v42")
            r.shouldBeInstanceOf<VerificationResult.MismatchWarn>()
            r.expected shouldBe "v42"
            r.got shouldBe "v40"
        }

        "missing model_version on the incoming context returns Missing(current)" {
            val r = SchemaVersionVerifier.verifyContext(ctx(""), "v42")
            r.shouldBeInstanceOf<VerificationResult.Missing>()
            r.current shouldBe "v42"
        }

        "verifier never throws — it returns a structured result" {
            // Intentionally pass odd inputs; verifier should still classify them.
            SchemaVersionVerifier.verifyContext(ctx("v0"), "v0") shouldBe VerificationResult.Ok
            val r = SchemaVersionVerifier.verifyContext(ctx("not-a-version"), "v42")
            r.shouldBeInstanceOf<VerificationResult.MismatchWarn>()
        }
    })
