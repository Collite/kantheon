package shared.translator.detect

import org.apache.calcite.sql.SqlBasicCall
import org.apache.calcite.sql.SqlIdentifier
import org.apache.calcite.sql.SqlJoin
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlNode
import org.apache.calcite.sql.SqlOrderBy
import org.apache.calcite.sql.SqlSelect
import org.apache.calcite.sql.SqlWith
import org.apache.calcite.sql.SqlWithItem

object TableIdentifierExtractor {
    fun fromQuery(sqlNode: SqlNode): List<String> {
        val tables = mutableSetOf<String>()
        val cteNames = mutableSetOf<String>()
        collect(sqlNode, tables, cteNames)
        return tables.sorted()
    }

    private fun collect(
        node: SqlNode?,
        tables: MutableSet<String>,
        cteNames: MutableSet<String>,
    ) {
        if (node == null) return

        when (node) {
            is SqlWith -> {
                node.withList.forEach { withItem ->
                    if (withItem is SqlWithItem) {
                        cteNames.add(withItem.name.toString().lowercase())
                        collect(withItem.query, tables, cteNames)
                    }
                }
                collect(node.body, tables, cteNames)
            }

            is SqlOrderBy -> {
                collect(node.query, tables, cteNames)
            }

            is SqlSelect -> {
                collectFrom(node.from, tables, cteNames)
            }

            is SqlJoin -> {
                collectFrom(node.left, tables, cteNames)
                collectFrom(node.right, tables, cteNames)
            }

            is SqlBasicCall -> {
                if (node.operator.kind == SqlKind.AS) {
                    collectFrom(node.operandList.getOrNull(0), tables, cteNames)
                } else {
                    node.operandList.filterIsInstance<SqlNode>().forEach { operand ->
                        collect(operand, tables, cteNames)
                    }
                }
            }

            is SqlIdentifier -> {
                val name = node.names.lastOrNull() ?: return
                val lname = name.lowercase()
                if (lname !in cteNames) {
                    tables.add(lname)
                }
            }

            else -> {}
        }
    }

    private fun collectFrom(
        node: SqlNode?,
        tables: MutableSet<String>,
        cteNames: MutableSet<String>,
    ) {
        when (node) {
            is SqlIdentifier -> {
                val name = node.names.lastOrNull() ?: return
                val lname = name.lowercase()
                if (lname !in cteNames) {
                    tables.add(lname)
                }
            }

            is SqlJoin -> {
                collectFrom(node.left, tables, cteNames)
                collectFrom(node.right, tables, cteNames)
            }

            is SqlSelect -> {
                collectFrom(node.from, tables, cteNames)
            }

            is SqlWith -> {
                collect(node, tables, cteNames)
            }

            is SqlBasicCall -> {
                if (node.operator.kind == SqlKind.AS) {
                    collectFrom(node.operandList.getOrNull(0), tables, cteNames)
                } else {
                    node.operandList.filterIsInstance<SqlNode>().forEach { operand ->
                        collectFrom(operand, tables, cteNames)
                    }
                }
            }

            else -> {}
        }
    }
}
