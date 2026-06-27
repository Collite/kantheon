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
import org.apache.calcite.sql.type.SqlTypeName
import shared.translator.framework.ModelColumn
import shared.translator.framework.ModelHandle
import shared.translator.framework.ModelTable
import shared.translator.framework.PhysicalType
import shared.translator.framework.SurfaceType

/**
 * Top-level DB schema adapter. Holds sub-schemas (one per namespace) but no direct tables.
 * Calcite will use getSubSchemaMap() to find namespace-level tables; getTableMap() is not
 * overridden so the inherited empty-map default prevents table resolution at the top level.
 */
class DbSchemaAdapter(
    private val model: ModelHandle,
) : AbstractSchema() {
    public override fun getSubSchemaMap(): Map<String, Schema> =
        model.namespaces(SchemaCode.DB).associateWith { ns ->
            TableNamespaceSchema(model, ns)
        }

    override fun getTableMap(): Map<String, Table> = emptyMap()
}

/**
 * Namespace-level DB schema adapter — holds the actual physical tables.
 */
class TableNamespaceSchema(
    private val model: ModelHandle,
    private val namespace: String,
) : AbstractSchema() {
    public override fun getTableMap(): Map<String, Table> =
        model.tables(SchemaCode.DB, namespace).entries.associate { (qname, table) ->
            qname.name to PhysicalCalciteTable(table)
        }
}

/**
 * One physical table inside a [TableNamespaceSchema]. Implements [ScannableTable] with an
 * empty body — the v1 pipeline never executes against Calcite directly; tables
 * are only used for type derivation and SQL parsing/unparsing.
 */
class PhysicalCalciteTable(
    val source: ModelTable,
) : AbstractTable(),
    ScannableTable {
    override fun getRowType(typeFactory: RelDataTypeFactory): RelDataType {
        val builder = typeFactory.builder()
        for (col in source.columns) {
            val type = TypeMapping.toRelDataType(col, typeFactory)
            builder.add(col.name, type).nullable(col.nullable)
        }
        return builder.build()
    }

    override fun scan(root: DataContext): Enumerable<Array<Any?>> = Linq4j.emptyEnumerable()

    override fun getJdbcTableType(): Schema.TableType = Schema.TableType.TABLE

    fun qname(): QualifiedName = source.qname
}

object TypeMapping {
    fun toRelDataType(
        col: ModelColumn,
        factory: RelDataTypeFactory,
    ): RelDataType {
        col.physicalType?.let { return fromPhysical(it, factory) }
        return fromSurface(col.surfaceType, factory)
    }

    fun fromSurface(
        t: SurfaceType,
        factory: RelDataTypeFactory,
    ): RelDataType =
        when (t) {
            SurfaceType.TEXT -> factory.createSqlType(SqlTypeName.VARCHAR)
            SurfaceType.INT -> factory.createSqlType(SqlTypeName.BIGINT)
            SurfaceType.FLOAT -> factory.createSqlType(SqlTypeName.DOUBLE)
            SurfaceType.BOOL -> factory.createSqlType(SqlTypeName.BOOLEAN)
            SurfaceType.DATETIME -> factory.createSqlType(SqlTypeName.TIMESTAMP)
        }

    fun fromPhysical(
        p: PhysicalType,
        factory: RelDataTypeFactory,
    ): RelDataType {
        val name =
            when (p.kind) {
                PhysicalType.Kind.VARCHAR, PhysicalType.Kind.NVARCHAR -> SqlTypeName.VARCHAR
                PhysicalType.Kind.CHAR -> SqlTypeName.CHAR
                PhysicalType.Kind.DECIMAL, PhysicalType.Kind.NUMERIC -> SqlTypeName.DECIMAL
                PhysicalType.Kind.INTEGER -> SqlTypeName.INTEGER
                PhysicalType.Kind.BIGINT -> SqlTypeName.BIGINT
                PhysicalType.Kind.FLOAT -> SqlTypeName.FLOAT
                PhysicalType.Kind.DOUBLE -> SqlTypeName.DOUBLE
                PhysicalType.Kind.BOOLEAN -> SqlTypeName.BOOLEAN
                PhysicalType.Kind.DATE -> SqlTypeName.DATE
                PhysicalType.Kind.TIME -> SqlTypeName.TIME
                PhysicalType.Kind.TIMESTAMP -> SqlTypeName.TIMESTAMP
                PhysicalType.Kind.BINARY -> SqlTypeName.BINARY
            }
        val precision = p.precision
        val scale = p.scale
        return when {
            precision != null && scale != null -> factory.createSqlType(name, precision, scale)
            precision != null -> factory.createSqlType(name, precision)
            else -> factory.createSqlType(name)
        }
    }
}
