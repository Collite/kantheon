package shared.translator.schema

import org.apache.calcite.DataContext
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.linq4j.Linq4j
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.ScannableTable
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.impl.AbstractTable
import shared.translator.framework.AttributeOrColumnRef
import shared.translator.framework.ModelHandle
import shared.translator.framework.ModelSavedQuery
import shared.translator.framework.SurfaceType

/**
 * One saved query inside a [QueryNamespaceSchema]. Implements [ScannableTable]
 * with an empty body — the v1 pipeline never executes against Calcite directly;
 * tables are only used for type derivation and SQL parsing/unparsing.
 *
 * The row type is derived from [ModelHandle.savedQueryBody] output columns.
 * "Scan's output_columns win" — the declared output columns on the Scan are
 * authoritative; the body's actual row type may differ and is checked by
 * UNFOLD for cardinality mismatches.
 */
class SavedQueryCalciteTable(
    private val source: ModelSavedQuery,
    private val model: ModelHandle,
) : AbstractTable(),
    ScannableTable {
    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
        val builder = typeFactory.builder()
        val body = model.savedQueryBody(source.qname)
        for (col in body.outputColumns) {
            val fieldName =
                when (col) {
                    is AttributeOrColumnRef.Attr -> col.ref.name
                    is AttributeOrColumnRef.Col -> col.ref.name
                }
            val fieldType =
                when (col) {
                    is AttributeOrColumnRef.Attr -> {
                        val attrs = model.attributes(col.ref)
                        val surf = attrs.firstOrNull()?.surfaceType ?: SurfaceType.TEXT
                        TypeMapping.fromSurface(surf, typeFactory)
                    }
                    is AttributeOrColumnRef.Col -> {
                        val cols = model.columns(col.ref)
                        val surf = cols.firstOrNull()?.surfaceType ?: SurfaceType.TEXT
                        TypeMapping.fromSurface(surf, typeFactory)
                    }
                }
            builder.add(fieldName, fieldType)
        }
        return builder.build()
    }

    override fun scan(root: DataContext): Enumerable<Array<Any?>> = Linq4j.emptyEnumerable()

    override fun getJdbcTableType(): Schema.TableType = Schema.TableType.TABLE

    /** Public accessor — used by [Unfold] to recognise OBJ scans via `RelOptTable.unwrap`. */
    fun qname(): org.tatrman.plan.v1.QualifiedName = source.qname
}
