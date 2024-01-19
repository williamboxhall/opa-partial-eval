import Operator.EQUALS
import Operator.IN_LIST
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

val objectMapper = jacksonObjectMapper()

object OpaPartialEval {
    fun compileApiResponseToSql(compileApiResponseJson: String): String {
        val compileApiResponse = objectMapper.readValue(compileApiResponseJson, CompileApiResponse::class.java)
        val criteria = compileApiResponseToCriteria(compileApiResponse)
        return criteria.toSqlString()
    }

    private fun compileApiResponseToCriteria(compileApiResponse: CompileApiResponse): OrCriteria = OrCriteria(
        compileApiResponse.result.queries.map { orQueries ->
            AndCriteria(
                orQueries.map { andQueries ->
                    val (operatorTerm, leftOperand, rightOperand) = andQueries.terms
                    val operator = when (operatorTerm) {
                        is RefTerm -> when (operatorTerm.value.first().value) {
                            "internal" -> IN_LIST
                            "eq" -> EQUALS
                            else -> throw IllegalStateException("Unexpected operator in term $operatorTerm")
                        }

                        else -> throw IllegalStateException("Operators must be ref terms: $operatorTerm")
                    }
                    val left = parseOperand(leftOperand)
                    val right = parseOperand(rightOperand)
                    if (left is EntityFieldReference && right is EntityFieldReference) {
                        throw IllegalStateException("Can't have two field references as operands: $left, $right")
                    } else if (left is ConstantValue && right is ConstantValue) {
                        throw IllegalStateException("Can't have two constants as operands (because OPA should have already evaluated them): $left, $right")
                    } else {
                        Criterion(operator, left, right)
                    }
                },
            )
        },
    )

    private fun parseOperand(term: Term): Operand = when (term) {
        is RefTerm -> {
            if (term.value.size != 3) {
                throw IllegalStateException("Unexpected term format: $term")
            }
            val (dataSource, entity, field) = term.value
            if (dataSource.type != "var" || dataSource.value != "input") {
                throw IllegalStateException("Unknown data source $dataSource")
            }
            if (entity.type != "string") {
                throw IllegalStateException("Unknown entity type $entity")
            }
            if (field.type != "string") {
                throw IllegalStateException("Unknown field type $field")
            }
            EntityFieldReference(entityName = entity.value, fieldName = field.value)
        }

        is StringTerm -> StringValue(term.value)
        is NumberTerm -> NumberValue(term.value)
        is ArrayTerm -> when (term.value.first()) {
            is NumberTerm -> NumberArrayValue((term.value as List<NumberTerm>).map { it.value })
            is StringTerm -> StringArrayValue((term.value as List<StringTerm>).map { it.value })
            is RefTerm -> throw IllegalStateException("Can't match on multiple references: $term")
            is ArrayTerm -> throw IllegalStateException("Can't support arrays of arrays: $term")
        }
    }
}

data class Value(val type: String, val value: String)
data class Query(val index: Int, val terms: List<Term>)
data class Result(val queries: List<List<Query>>)
data class CompileApiResponse(val result: Result)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = RefTerm::class, name = "ref"),
    JsonSubTypes.Type(value = StringTerm::class, name = "string"),
    JsonSubTypes.Type(value = NumberTerm::class, name = "number"),
    JsonSubTypes.Type(value = ArrayTerm::class, name = "array"),
)
sealed interface Term
data class RefTerm(val value: List<Value>) : Term
data class StringTerm(val value: String) : Term
data class NumberTerm(val value: Long) : Term // TODO can this be floating point?
data class ArrayTerm(val value: List<Term>) : Term

enum class Operator {
    EQUALS {
        override fun toSqlString() = "="
    },
    IN_LIST {
        override fun toSqlString() = "IN"
    },
    ;

    abstract fun toSqlString(): String
}

sealed interface Operand {
    fun toSqlString(): String
}

data class EntityFieldReference(val entityName: String, val fieldName: String) : Operand {
    override fun toSqlString() = "$entityName.$fieldName"
}

sealed interface ConstantValue : Operand
data class StringValue(val value: String) : ConstantValue {
    override fun toSqlString() = "\"$value\""
}

data class NumberValue(val value: Long) : ConstantValue {
    override fun toSqlString() = "$value"
}

data class StringArrayValue(val values: List<String>) : ConstantValue {
    override fun toSqlString() = values.joinToString(prefix = "['", postfix = "']", separator = "', '")
}

data class NumberArrayValue(val values: List<Long>) : ConstantValue {
    override fun toSqlString() = values.joinToString(prefix = "[", postfix = "]", separator = ", ")
}

data class Criterion(val operator: Operator, val leftOperand: Operand, val rightOperand: Operand) {
    fun toSqlString() = "${leftOperand.toSqlString()} ${operator.toSqlString()} ${rightOperand.toSqlString()}"
}

data class AndCriteria(val criteria: List<Criterion>) {
    fun toSqlString() = criteria.map { it.toSqlString() }.joinToString(prefix = "(", postfix = ")", separator = " AND ")
}

data class OrCriteria(val andCriteria: List<AndCriteria>) {
    fun toSqlString() = andCriteria.map { it.toSqlString() }.joinToString(prefix = "(", postfix = ")", separator = " OR ")
}
