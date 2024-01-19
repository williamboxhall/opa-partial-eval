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
                orQueries.mapNotNull { andQueries ->
                    if (andQueries.terms.size == 3) {
                        val (operatorTerm, leftOperand, rightOperand) = andQueries.terms
                        val operator = when (operatorTerm) {
                            is RefTerm -> {
                                val operatorVar = operatorTerm.value.first()
                                if (operatorVar !is VarTerm) {
                                    throw IllegalStateException("Unexpected type of operator term: $operatorTerm")
                                }
                                when (operatorVar.value) {
                                    "internal" -> IN_LIST
                                    "eq" -> EQUALS
                                    else -> throw IllegalStateException("Unexpected operator in term $operatorTerm")
                                }
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
                    } else if (andQueries.terms.size == 1) {
                        val policyReference = andQueries.terms.first()
                        when (policyReference) {
                            is RefTerm -> {
                                if (policyReference.value.size != 4) {
                                    throw IllegalStateException("Unexpected length of policy reference term: $policyReference")
                                }
                                val policyReferenceVar = policyReference.value.first()
                                if (policyReferenceVar !is VarTerm || policyReferenceVar.value != "data") {
                                    throw IllegalStateException("Unexpected format of policy reference term: $policyReferenceVar")
                                }
                                println("Looks like this is just a policy reference: ${policyReference.value}")
                            }
                            else -> throw IllegalStateException("Expected policy reference term but found $policyReference")
                        }
                        null
                    } else {
                        throw IllegalStateException("Unexpected terms structure: ${andQueries.terms}")
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
            if (dataSource !is VarTerm || dataSource.value != "input") {
                throw IllegalStateException("Unknown data source $dataSource")
            }
            if (entity !is StringTerm) {
                throw IllegalStateException("Unknown entity type $entity")
            }
            if (field !is StringTerm) {
                throw IllegalStateException("Unknown field type $field")
            }
            EntityFieldReference(entityName = entity.value, fieldName = field.value)
        }
        is StringTerm -> StringValue(term.value)
        is NumberTerm -> NumberValue(term.value)
        is BooleanTerm -> BooleanValue(term.value)
        is ArrayTerm -> when (term.value.first()) {
            is NumberTerm -> NumberArrayValue((term.value as List<NumberTerm>).map { it.value })
            is StringTerm -> StringArrayValue((term.value as List<StringTerm>).map { it.value })
            is BooleanTerm -> throw IllegalStateException("No valid scenario for lists of booleans: $term")
            is RefTerm -> throw IllegalStateException("Can't match on multiple references: $term")
            is ArrayTerm -> throw IllegalStateException("Can't support arrays of arrays: $term")
            is VarTerm -> throw IllegalStateException("Can't support arrays of vars: $term")
        }
        is VarTerm -> throw IllegalStateException("Operand can't be a 'var' term: $term")
    }
}

data class Query(val index: Int, val terms: List<Term>)
data class Head(val name: String, val value: Term)
data class Rule(val body: List<Query>, val default: Boolean, val head: Head)
data class Package(val path: List<Term>)
data class Support(val `package`: Package, val rules: List<Rule>)
data class Result(val queries: List<List<Query>>, val support: List<Support>?)
data class CompileApiResponse(val result: Result)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = RefTerm::class, name = "ref"),
    JsonSubTypes.Type(value = VarTerm::class, name = "var"),
    JsonSubTypes.Type(value = StringTerm::class, name = "string"),
    JsonSubTypes.Type(value = NumberTerm::class, name = "number"),
    JsonSubTypes.Type(value = ArrayTerm::class, name = "boolean"),
    JsonSubTypes.Type(value = ArrayTerm::class, name = "array"),
)
sealed interface Term
data class RefTerm(val value: List<Term>) : Term
data class VarTerm(val value: String) : Term
data class StringTerm(val value: String) : Term
data class NumberTerm(val value: Long) : Term // TODO can this be floating point?
data class BooleanTerm(val value: Boolean) : Term
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

data class BooleanValue(val value: Boolean) : ConstantValue {
    override fun toSqlString() = value.toString().uppercase()
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
