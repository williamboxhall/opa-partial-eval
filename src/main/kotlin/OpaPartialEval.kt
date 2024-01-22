import Operator.EQUALS
import Operator.IN_LIST
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

// refer to the Go classes https://github.com/open-policy-agent/opa/blob/main/ast/policy.go
object OpaPartialEval {
    private val objectMapper = jacksonObjectMapper()

    fun compileApiResponseToSql(compileApiResponseJson: String): String {
        val compileApiResponse = objectMapper.readValue(compileApiResponseJson, CompileResult::class.java)
        val criteria = compileApiResponseToCriteria(compileApiResponse)
        return criteria.toSqlString()
    }

    // TODO can both rules.queries and rules.support exist at the same time?
    private fun compileApiResponseToCriteria(compileApiResponse: CompileResult): OrCriteria {
        if (compileApiResponse.result.support != null) {
            throw IllegalStateException("Not yet supporting 'support' block")
        }
        val andCriteria = compileApiResponse.result.queries.map { AndCriteria(parseQueries(it)) }
        return OrCriteria(andCriteria)
    }

    private fun parseQueries(orQueries: List<Expr>) = orQueries.mapNotNull { andQueries ->
        when (andQueries.terms.size) {
            3 -> parseCriterion(andQueries)
            1 -> parsePolicyReference(andQueries)
            else -> throw IllegalStateException("Unexpected terms structure: ${andQueries.terms}")
        }
    }

    private fun parsePolicyReference(query: Expr): Nothing? {
        val policyReference = query.terms.first()
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

            else -> throw IllegalStateException("Expected policy reference term but found $query")
        }
        return null
    }

    private fun parseCriterion(andQueries: Expr): Criterion {
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
        return if (left is EntityFieldReference && right is EntityFieldReference) {
            throw IllegalStateException("Can't have two field references as operands: $left, $right")
        } else if (left is ConstantValue && right is ConstantValue) {
            throw IllegalStateException("Can't have two constants as operands (because OPA should have already evaluated them): $left, $right")
        } else {
            Criterion(operator, left, right)
        }
    }

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

typealias Body = List<Expr> // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L224
typealias Ref = List<Term> // see https://github.com/open-policy-agent/opa/blob/c0589c1272020ee8681a78fe432393008a28efb8/ast/term.go#L880

data class Expr(val index: Int, @JsonDeserialize(using = TermsDeserializer::class) val terms: List<Term>) // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L227
data class Head(val name: String, @JsonDeserialize(using = TermsDeserializer::class) val value: Term, val ref: Ref) // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L205
data class Rule(val body: Body, val default: Boolean?, val head: Head) // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L187
data class Package(@JsonDeserialize(using = TermsDeserializer::class) val path: Ref) // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L168
data class Module(val `package`: Package, val rules: List<Rule>) // see https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L147
data class PartialQueries(val queries: List<Body>, val support: List<Module>?) // see https://github.com/open-policy-agent/opa/blob/c0589c1272020ee8681a78fe432393008a28efb8/server/types/types.go#L388
data class CompileResult(val result: PartialQueries)

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
    JsonSubTypes.Type(value = BooleanTerm::class, name = "boolean"),
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

// To handle weird json where it can be a singleton or list of Term
// see: https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L229
// and: https://github.com/open-policy-agent/opa/blob/main/ast/policy.go#L1206
// TODO we may be able to get rid of this if we no longer need to support "support" block of compile api
class TermsDeserializer : JsonDeserializer<List<Term>>() {
    override fun deserialize(jp: JsonParser, ctxt: DeserializationContext): List<Term> {
        val node: JsonNode = jp.codec.readTree(jp)
        val objectMapper: ObjectMapper = jacksonObjectMapper()

        return if (node.isArray) {
            // Deserialize as a list of Terms
            node.map { objectMapper.treeToValue(it, Term::class.java) }
        } else {
            // Deserialize as a single Term and wrap in a list
            listOf(objectMapper.treeToValue(node, Term::class.java))
        }
    }
}
