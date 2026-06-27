package shared.translator.schema

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.apache.calcite.DataContext
import org.apache.calcite.linq4j.Enumerable
import org.apache.calcite.linq4j.Linq4j
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.schema.ScannableTable
import org.apache.calcite.schema.Schema
import org.apache.calcite.schema.Table
import org.apache.calcite.schema.impl.AbstractSchema
import org.apache.calcite.schema.impl.AbstractTable
import shared.translator.framework.ModelEntity
import shared.translator.framework.ModelHandle

/**
 * Top-level ER schema adapter. Holds sub-schemas (one per namespace) but no direct tables.
 * MUST NOT override getTableMap() — doing so collapses the qualified-name path to 2 elements.
 */
class ErSchemaAdapter(
    private val model: ModelHandle,
) : AbstractSchema() {
    public override fun getTableMap(): Map<String, Table> = emptyMap()

    public override fun getSubSchemaMap(): Map<String, Schema> =
        model.namespaces(SchemaCode.ER).associateWith { ns ->
            EntityNamespaceSchema(model, ns)
        }
}

/**
 * Namespace-level ER schema adapter — holds the actual entity tables.
 */
class EntityNamespaceSchema(
    private val model: ModelHandle,
    private val namespace: String,
) : AbstractSchema() {
    public override fun getTableMap(): Map<String, Table> =
        model.entities(SchemaCode.ER, namespace).entries.associate { (qname, entity) ->
            qname.name to EntityCalciteTable(entity, model)
        }
}

/**
 * One entity inside an [EntityNamespaceSchema]. Implements [ScannableTable] with an
 * empty body — the v1 pipeline never executes against Calcite directly; tables
 * are only used for type derivation and SQL parsing/unparsing.
 */
class EntityCalciteTable(
    private val source: ModelEntity,
    private val model: ModelHandle,
) : AbstractTable(),
    ScannableTable {
    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
        val builder = typeFactory.builder()
        for (attr in source.attributes) {
            val type = TypeMapping.fromSurface(attr.surfaceType, typeFactory)
            builder.add(attr.name, type).nullable(attr.nullable)
        }
        return builder.build()
    }

    override fun scan(root: DataContext): Enumerable<Array<Any?>> = Linq4j.emptyEnumerable()

    override fun getJdbcTableType(): Schema.TableType = Schema.TableType.TABLE

    fun qname(): QualifiedName = source.qname
}
