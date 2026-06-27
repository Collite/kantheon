package org.tatrman.kantheon.hebe.detektrules

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

@Suppress("detekt:NestedBlockDepth", "detekt:ReturnCount")
class MutationFunnelRule(
    config: Config = Config.empty,
) : Rule(config) {
    override val issue =
        io.gitlab.arturbosch.detekt.api.Issue(
            id = "MutationFunnel",
            severity = Severity.Defect,
            description =
                "Side-effects must go through ToolDispatcher.dispatch. " +
                    "Direct calls to side-effect methods (file I/O, DB writes, tool invocations) " +
                    "are forbidden unless annotated with // dispatch-exempt: <reason>.",
            debt = io.gitlab.arturbosch.detekt.api.Debt.TWENTY_MINS,
        )

    private val sideEffectMethods =
        setOf(
            "delete",
            "write",
            "writeString",
            "append",
            "createDirectory",
            "deleteIfExists",
            "copy",
            "move",
            "execute",
            "executeUpdate",
            "executeQuery",
            "commit",
            "rollback",
        )

    override fun visitNamedFunction(function: KtNamedFunction) {
        if (isInDispatcher(function)) return
        if (hasDispatchExempt(function)) return
        checkForSideEffects(function)
        super.visitNamedFunction(function)
    }

    private fun isInDispatcher(function: KtNamedFunction): Boolean {
        val functionName = function.name
        if (functionName == "dispatch") {
            val containingFile = function.containingKtFile
            val fqName = containingFile.packageFqName.asString()
            if (fqName.contains("dispatch")) return true
        }
        return false
    }

    @Suppress("detekt:LoopWithTooManyJumpStatements")
    private fun hasDispatchExempt(function: KtNamedFunction): Boolean {
        var prev = function.prevSibling
        while (prev != null) {
            val text = prev.text
            if (text.isBlank()) {
                prev = prev.prevSibling
                continue
            }
            if (text.trimStart().startsWith("//") && text.contains("dispatch-exempt")) return true
            break
        }
        val docComment = function.docComment
        if (docComment != null) {
            val text = docComment.text
            if (text.contains("dispatch-exempt")) return true
        }
        val annotationEntries = function.annotationEntries
        for (entry in annotationEntries) {
            val text = entry.text
            if (text.contains("dispatch-exempt")) return true
        }
        return false
    }

    private fun checkForSideEffects(function: KtNamedFunction) {
        val body = function.bodyExpression ?: return
        checkExpressionForSideEffects(body, function.name ?: "anonymous")
    }

    private fun checkExpressionForSideEffects(
        expression: KtExpression,
        contextName: String,
    ) {
        when (expression) {
            is KtDotQualifiedExpression -> {
                val selector = expression.selectorExpression
                if (selector is KtCallExpression) {
                    val methodName = getMethodName(selector)
                    if (methodName != null && isSideEffectMethod(methodName)) {
                        val receiverText = expression.receiverExpression.text
                        if (isSideEffectReceiver(receiverText)) {
                            report(
                                io.gitlab.arturbosch.detekt.api.CodeSmell(
                                    issue,
                                    io.gitlab.arturbosch.detekt.api.Entity
                                        .from(selector),
                                    "Direct side-effect call to '$methodName' " +
                                        "outside ToolDispatcher.dispatch. " +
                                        "Add // dispatch-exempt: <reason> to allow this call.",
                                ),
                            )
                        }
                    }
                }
                checkExpressionForSideEffects(expression.receiverExpression, contextName)
                expression.selectorExpression?.let { checkExpressionForSideEffects(it, contextName) }
            }
            is KtCallExpression -> {
                val methodName = getMethodName(expression)
                if (methodName != null && isSideEffectMethod(methodName)) {
                    report(
                        io.gitlab.arturbosch.detekt.api.CodeSmell(
                            issue,
                            io.gitlab.arturbosch.detekt.api.Entity
                                .from(expression),
                            "Direct side-effect call to '$methodName' " +
                                "outside ToolDispatcher.dispatch. " +
                                "Add // dispatch-exempt: <reason> to allow this call.",
                        ),
                    )
                }
            }
            is KtBlockExpression -> {
                for (stmt in expression.statements) {
                    checkExpressionForSideEffects(stmt, contextName)
                }
            }
        }
    }

    private fun getMethodName(call: KtCallExpression): String? {
        val callee = call.calleeExpression
        return (callee as? KtNameReferenceExpression)?.getReferencedName()
    }

    private fun isSideEffectMethod(methodName: String): Boolean = methodName in sideEffectMethods

    private fun isSideEffectReceiver(receiverText: String): Boolean {
        val sideEffectReceivers =
            listOf(
                "Files",
                "File",
                "Paths",
                "ProcessBuilder",
                "Runtime",
                "Connection",
                "Statement",
                "PreparedStatement",
                "ResultSet",
            )
        return sideEffectReceivers.any { receiverText.startsWith(it) }
    }
}

class MutationFunnelRuleSetProvider : io.gitlab.arturbosch.detekt.api.RuleSetProvider {
    override val ruleSetId: String = "hebe-mutation-funnel"

    override fun instance(config: Config): io.gitlab.arturbosch.detekt.api.RuleSet =
        io.gitlab.arturbosch.detekt.api.RuleSet(
            ruleSetId,
            listOf(MutationFunnelRule(config)),
        )
}
