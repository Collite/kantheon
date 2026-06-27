package shared.translator.schema

import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.impl.AbstractSchema
import shared.translator.framework.ModelHandle

/**
 * Root SchemaPlus adapter that exposes all three schema surfaces (db, er, obj)
 * as Calcite sub-schemas, one per [SchemaCode] token.
 *
 * Calcite's SQL parser navigates `<schemaCode>.<namespace>.<name>` by looking up
 * the root schema, then looking up the child schema by the first path segment
 * (e.g. `er`), then looking up the table within that child schema.
 */
class SchemaPlusAdapter(
    private val model: ModelHandle,
) : AbstractSchema() {
    val db: DbSchemaAdapter = DbSchemaAdapter(model)
    val er: ErSchemaAdapter = ErSchemaAdapter(model)
    val obj: ObjSchemaAdapter = ObjSchemaAdapter(model)

    public override fun getSubSchemaMap(): Map<String, Schema> =
        buildMap {
            if (model.namespaces(org.tatrman.plan.v1.SchemaCode.DB).isNotEmpty()) put("db", db)
            if (model.namespaces(org.tatrman.plan.v1.SchemaCode.ER).isNotEmpty()) put("er", er)
            if (model.namespaces(org.tatrman.plan.v1.SchemaCode.OBJ).isNotEmpty()) put("obj", obj)
        }
}
