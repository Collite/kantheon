package shared.translator.schema

import org.tatrman.plan.v1.SchemaCode
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.impl.AbstractSchema
import shared.translator.framework.ModelHandle

/**
 * Top-level OBJ schema adapter. Holds sub-schemas (one per namespace) but no direct tables.
 * MUST NOT override getTableMap() — doing so collapses the qualified-name path to 2 elements.
 *
 * In Stage 2, getSubSchemaMap() will return QueryNamespaceSchema per namespace,
 * and each namespace schema will return SavedQueryCalciteTable via getTableMap().
 */
class ObjSchemaAdapter(
    private val model: ModelHandle,
) : AbstractSchema() {
    override fun getTableMap(): Map<String, org.apache.calcite.schema.Table> = emptyMap()

    public override fun getSubSchemaMap(): Map<String, Schema> =
        model.namespaces(SchemaCode.OBJ).associateWith { ns ->
            QueryNamespaceSchema(model, ns)
        }
}

/**
 * Namespace-level OBJ schema adapter — exposes saved queries as Calcite tables so the SQL
 * parser can resolve `obj.<namespace>.<query_name>` references during PARSE/TO_REL and the
 * decoder can re-resolve them on REL_NODE re-entry.
 *
 * Each entry materialises as a [SavedQueryCalciteTable] whose row type comes from the saved
 * query body's declared `outputColumns` (per master plan §220, "Scan's output_columns win").
 * UNFOLD then inlines the body's actual [PlanNode] at the Calcite layer.
 */
class QueryNamespaceSchema(
    private val model: ModelHandle,
    private val namespace: String,
) : AbstractSchema() {
    public override fun getTableMap(): Map<String, org.apache.calcite.schema.Table> =
        model.savedQueries(SchemaCode.OBJ, namespace).entries.associate { (qname, sq) ->
            qname.name to SavedQueryCalciteTable(sq, model)
        }
}
