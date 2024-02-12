import ComparisonOperator.EQUALS
import ComparisonOperator.GREATER_THAN
import ComparisonOperator.IN_LIST
import ComparisonOperator.LESS_THAN
import ComparisonOperator.NOT_EQUALS
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
            4 -> parseInfixFunctionCriterion(andQueries)
            1 -> parsePolicyReference(andQueries)
            else -> {
                throw IllegalStateException("Unexpected terms structure: ${andQueries.terms}")
            }
        }
    }

    private fun parsePolicyReference(query: Expr): Nothing? {
        when (val policyReference = query.terms.first()) {
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
        val (operatorTerm: Term, leftOperand: Term, rightOperand: Term) = when (andQueries.terms.size) {
            3 -> andQueries.terms
            else -> throw IllegalStateException("andQueries should be 3 terms long but was $andQueries")
        }
        val (operator, functionCall) = parseComparisonOperator(operatorTerm)
        val left = parseOperand(leftOperand, functionCall)
        val right = parseOperand(rightOperand, null)
        return if (left is EntityFieldReference && right is EntityFieldReference) {
            throw IllegalStateException("Can't have two field references as operands: $left, $right")
        } else if (left is ConstantValue && right is ConstantValue) {
            throw IllegalStateException("Can't have two constants as operands (because OPA should have already evaluated them): $left, $right")
        } else {
            Criterion(operator, left, right)
        }
    }

    private fun parseInfixFunctionCriterion(andQueries: Expr): Criterion {
        val (infixOperatorTerm: Term, leftOperandFirst: Term, leftOperandSecond: Term, rightOperand: Term) = when (andQueries.terms.size) {
            4 -> andQueries.terms
            else -> throw IllegalStateException("andQueries should be 4 terms long but was $andQueries")
        }
        val functionCall = parseBinaryFunctionCall(infixOperatorTerm)
        val leftFirst = parseOperand(leftOperandFirst, null)
        val leftSecond = parseOperand(leftOperandSecond, null)
        val right = parseOperand(rightOperand, null)
        return if (right is EntityFieldReference && (leftFirst is EntityFieldReference || leftSecond is EntityFieldReference)) {
            throw IllegalStateException("Can't have two field references as operands: ($leftFirst $functionCall $leftSecond) = $right")
        } else {
            Criterion(EQUALS, InfixFunctionCallOnOperands(functionCall, leftFirst, leftSecond), right)
        }
    }

    private fun parseComparisonOperator(term: Term): Pair<ComparisonOperator, UnaryFunctionCall?> = when (term) {
        is RefTerm -> {
            val operatorVar = term.value.first()
            if (operatorVar !is VarTerm) {
                throw IllegalStateException("Unexpected type of operator term: $term")
            }
            when (operatorVar.value) {
                "internal" -> IN_LIST to null
                "eq" -> EQUALS to null
                "neq" -> NOT_EQUALS to null
                "gt" -> GREATER_THAN to null
                "lt" -> LESS_THAN to null
                else -> EQUALS to UnaryFunctionCall.fromName(operatorVar.value)
            }
        }

        else -> throw IllegalStateException("Operators must be ref terms: $term")
    }

    private fun parseUnaryFunctionCall(term: Term): UnaryFunctionCall = when (term) {
        is RefTerm -> {
            if (term.value.size != 1) {
                throw IllegalStateException("Unexpected unary function call ref term format: $term")
            }
            val functionVar = term.value.first()
            if (functionVar !is VarTerm) {
                throw IllegalStateException("Unexpected type of unary function call term: $term")
            }
            UnaryFunctionCall.fromName(functionVar.value)
        }
        else -> {
            throw IllegalStateException("Unary function calls must be ref terms: $term")
        }
    }

    private fun parseBinaryFunctionCall(term: Term): BinaryFunctionCall = when (term) {
        is RefTerm -> {
            if (term.value.size != 1) {
                throw IllegalStateException("Unexpected infix function call ref term format: $term")
            }
            val functionVar = term.value.first()
            if (functionVar !is VarTerm) {
                throw IllegalStateException("Unexpected type of infix function call term: $term")
            }
            BinaryFunctionCall.fromName(functionVar.value)
        }
        else -> {
            throw IllegalStateException("Infix function calls must be call terms: $term")
        }
    }

    private fun parseOperand(term: Term, operatorFunctionCall: UnaryFunctionCall?): Operand = when (term) {
        is RefTerm -> {
            val entityFieldReference = parseEntityFieldReference(term)
            operatorFunctionCall?.let { FunctionCallOnFieldReference(entityFieldReference, it) } ?: entityFieldReference
        }

        is CallTerm -> {
            if (term.value.size == 3) {
                // nested infix function calls
                val (infixFunction, leftOperand, rightOperand) = term.value
                val function = parseBinaryFunctionCall(infixFunction)
                val left = parseOperand(leftOperand, null)
                val right = parseOperand(rightOperand, null)
                InfixFunctionCallOnOperands(function, left, right)
            } else if (term.value.size == 2) {
                val (function, field) = term.value
                val entityFieldReference = when (field) {
                    is RefTerm -> parseEntityFieldReference(field)
                    else -> throw IllegalStateException("Field reference should be ref term but was: $field")
                }
                val functionCall = parseUnaryFunctionCall(function)
                if (operatorFunctionCall != null) {
                    throw IllegalStateException("Function call provided both in operator position and call position: $term, $operatorFunctionCall")
                }
                FunctionCallOnFieldReference(entityFieldReference, functionCall)
            } else {
                throw IllegalStateException("Unexpected call term format: $term")
            }
        }

        is StringTerm -> StringValue(term.value)
        is NumberTerm -> NumberValue(term.value)
        is BooleanTerm -> BooleanValue(term.value)
        is VarTerm -> throw IllegalStateException("Operand can't be a 'var' term: $term")
        is ArrayTerm -> when (term.value.first()) {
            is NumberTerm -> NumberArrayValue((term.value as List<NumberTerm>).map { it.value })
            is StringTerm -> StringArrayValue((term.value as List<StringTerm>).map { it.value })
            is BooleanTerm -> throw IllegalStateException("No valid scenario for lists of booleans: $term")
            is RefTerm -> throw IllegalStateException("Can't match on multiple references: $term")
            is ArrayTerm -> throw IllegalStateException("Can't support arrays of arrays: $term")
            is SetTerm -> throw IllegalStateException("Can't support arrays of sets: $term")
            is VarTerm -> throw IllegalStateException("Can't support arrays of vars: $term")
            is CallTerm -> throw IllegalStateException("Can't support arrays of calls: $term")
        }
        is SetTerm -> when (term.value.first()) {
            is NumberTerm -> NumberSetValue((term.value as Set<NumberTerm>).map { it.value }.toSet())
            is StringTerm -> StringSetValue((term.value as Set<StringTerm>).map { it.value }.toSet())
            is BooleanTerm -> throw IllegalStateException("No valid scenario for lists of booleans: $term")
            is RefTerm -> throw IllegalStateException("Can't match on multiple references: $term")
            is ArrayTerm -> throw IllegalStateException("Can't support sets of arrays: $term")
            is SetTerm -> throw IllegalStateException("Can't support sets of sets: $term")
            is VarTerm -> throw IllegalStateException("Can't support sets of vars: $term")
            is CallTerm -> throw IllegalStateException("Can't support sets of calls: $term")
        }
    }

    private fun parseEntityFieldReference(term: RefTerm): EntityFieldReference {
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
        return EntityFieldReference(entityName = entity.value, fieldName = field.value)
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
    JsonSubTypes.Type(value = SetTerm::class, name = "set"),
    JsonSubTypes.Type(value = CallTerm::class, name = "call"),
)
sealed interface Term
data class RefTerm(val value: List<Term>) : Term
data class VarTerm(val value: String) : Term
data class StringTerm(val value: String) : Term
data class NumberTerm(val value: Number) : Term // represent as string because it might be integer or may be fractional
data class BooleanTerm(val value: Boolean) : Term
data class ArrayTerm(val value: List<Term>) : Term
data class SetTerm(val value: Set<Term>) : Term
data class CallTerm(val value: List<Term>) : Term // takes form: 0-operator, 1-Ref term for the function, 2-column/leftOperand, 3-const/rightOperand

enum class ComparisonOperator {
    EQUALS {
        override fun toSqlString(left: Operand, right: Operand): String {
            return if (right is SetValue<*>) {
                "${left.toSqlString()} @> ${right.toSqlString()} AND ${left.toSqlString()} <@ ${right.toSqlString()}"
            } else if (left is SetValue<*>) {
                "${right.toSqlString()} @> ${left.toSqlString()} AND ${right.toSqlString()} <@ ${left.toSqlString()}"
            } else {
                "${left.toSqlString()} = ${right.toSqlString()}"
            }
        }
    },
    NOT_EQUALS {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} != ${right.toSqlString()}"
    },
    IN_LIST {
        override fun toSqlString(left: Operand, right: Operand): String {
            return "${left.toSqlString()} = ANY(${right.toSqlString()})"
        }
    },
    GREATER_THAN {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} > ${right.toSqlString()}"
    },
    LESS_THAN {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} < ${right.toSqlString()}"
    },
    ;

    abstract fun toSqlString(left: Operand, right: Operand): String
}

enum class UnaryFunctionCall {
    MAX {
        override fun toSqlString(field: EntityFieldReference): String = "(SELECT MAX(val) FROM unnest(${field.toSqlString()}) AS t(val))"
    },
    COUNT {
        override fun toSqlString(field: EntityFieldReference): String = "cardinality(${field.toSqlString()})"
    },
    ABS {
        override fun toSqlString(field: EntityFieldReference): String = "abs(${field.toSqlString()})"
    },
    CEIL {
        override fun toSqlString(field: EntityFieldReference): String = "ceil(${field.toSqlString()})"
    },
    SORT {
        override fun toSqlString(field: EntityFieldReference): String = "ARRAY(SELECT val FROM unnest(${field.toSqlString()}) AS t(val) ORDER BY val)"
    },
    SUM {
        override fun toSqlString(field: EntityFieldReference): String = "(SELECT SUM(val) FROM unnest(${field.toSqlString()}) AS t(val))"
    },
    ;

    companion object {
        fun fromName(name: String): UnaryFunctionCall = when (name) {
            "max" -> MAX
            "count" -> COUNT
            "abs" -> ABS
            "ceil" -> CEIL
            "sort" -> SORT
            "sum" -> SUM
            else -> throw IllegalStateException("Unrecognised unary function name $name")
        }
    }

    abstract fun toSqlString(field: EntityFieldReference): String
}

enum class BinaryFunctionCall {
    PLUS {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} + ${right.toSqlString()}"
    },
    MINUS {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} - ${right.toSqlString()}"
    },
    MOD {
        override fun toSqlString(left: Operand, right: Operand): String = "${left.toSqlString()} % ${right.toSqlString()}"
    },
    STARTS_WITH {
        override fun toSqlString(left: Operand, right: Operand): String = "starts_with(${right.toSqlString()}, ${left.toSqlString()})"
    },
    ;

    companion object {
        fun fromName(name: String): BinaryFunctionCall = when (name) {
            "plus" -> PLUS
            "minus" -> MINUS
            "rem" -> MOD
            "startswith" -> STARTS_WITH
            else -> throw IllegalStateException("Unrecognised infix function name $name")
        }
    }

    abstract fun toSqlString(left: Operand, right: Operand): String
}

sealed interface Operand {
    fun toSqlString(): String
}

data class EntityFieldReference(val entityName: String, val fieldName: String) : Operand {
    override fun toSqlString() = "$entityName.$fieldName"
}

data class FunctionCallOnFieldReference(val field: EntityFieldReference, val functionCall: UnaryFunctionCall) : Operand {
    override fun toSqlString() = functionCall.toSqlString(field)
}

data class InfixFunctionCallOnOperands(val infixFunction: BinaryFunctionCall, val left: Operand, val right: Operand) : Operand {
    override fun toSqlString() = infixFunction.toSqlString(left, right)
}

sealed interface ConstantValue : Operand
data class StringValue(val value: String) : ConstantValue {
    override fun toSqlString() = "'$value'"
}

data class NumberValue(val value: Number) : ConstantValue {
    override fun toSqlString() = "$value"
}

data class BooleanValue(val value: Boolean) : ConstantValue {
    override fun toSqlString() = value.toString().uppercase()
}

sealed interface ArrayValue<T> : ConstantValue {
    val values: List<T>
    override fun toSqlString() = "ARRAY${values.joinToString(prefix = "['", postfix = "']", separator = "', '")}"
}

data class StringArrayValue(override val values: List<String>) : ArrayValue<String>
data class NumberArrayValue(override val values: List<Number>) : ArrayValue<Number>

sealed interface SetValue<T> : ConstantValue {
    val values: Set<T>
    override fun toSqlString() = "ARRAY${values.joinToString(prefix = "['", postfix = "']", separator = "', '")}"
}

data class StringSetValue(override val values: Set<String>) : SetValue<String>
data class NumberSetValue(override val values: Set<Number>) : SetValue<Number>

data class Criterion(val comparisonOperator: ComparisonOperator, val left: Operand, val right: Operand) {
    fun toSqlString() = comparisonOperator.toSqlString(left, right)
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
