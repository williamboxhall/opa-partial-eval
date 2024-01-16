import SqlOperator.EQUALS
import SqlOperator.IN_LIST
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

val objectMapper = jacksonObjectMapper()

fun main() {
    val whereClause = OpaPartialEval.toSql("input.json", "result.json")
    val expected = "((entity.account_id = 456 AND entity.author_id = 123) OR (entity.account_id = 456 AND TRUE) OR (entity.account_id = 456 AND entity.author_id IN [789, 333]))"
    if (whereClause != expected) {
        throw IllegalStateException("Didn't generate expected SQL")
    }
    println(whereClause)
}

object OpaPartialEval {

    fun toSql(inputFileName: String, resultFileName: String): String {
        val inputJson = readFileFromResources(inputFileName)
        val inputMap = objectMapper.readValue(inputJson, object : TypeReference<Map<String, Any>>() {})
        val resultJson = readFileFromResources(resultFileName)
        val resultAst = objectMapper.readValue(resultJson, Result::class.java)

        val parsed: List<List<String>> = resultAst.partial.queries.map { orQueries ->
            orQueries.map { andQueries ->
                val (operatorTerm, firstOperand, secondOperand) = andQueries.terms
                val sqlOperator = when (operatorTerm) {
                    is RefTerm -> when (operatorTerm.value.first().value) {
                        "internal" -> IN_LIST
                        "eq" -> EQUALS
                        else -> throw IllegalStateException("Unexpected operator in term $operatorTerm")
                    }

                    is StringTerm -> throw IllegalStateException("Operators can't be string terms: $operatorTerm")
                }
                val first = parseOperand(firstOperand, inputMap)
                val second = parseOperand(secondOperand, inputMap)
                val clause: String = if (first is Right && second is Right) {
                    throw IllegalStateException("Can't have two columns in a WHERE clause: $first, $second")
                } else if (first is Left && second is Left) {
                    when (first.left) {
                        is StringValue -> if (second.left is StringValue) {
                            when (sqlOperator) {
                                EQUALS -> first.left.value == second.left.value
                                IN_LIST -> throw IllegalStateException("Can't use IN operator with single strings: ${andQueries.terms}")
                            }
                        } else if (second.left is StringArrayValue) {
                            when (sqlOperator) {
                                EQUALS -> throw IllegalStateException("Can't use '=' operator with string arrays: ${andQueries.terms}")
                                IN_LIST -> second.left.value.contains(first.left.value)
                            }
                        } else {
                            throw IllegalStateException("Can't compare a StringValue (${first.left}) with a ${second::class.simpleName} (${second.left}).")
                        }
                        is IntValue -> if (second.left is IntValue) {
                            when (sqlOperator) {
                                EQUALS -> first.left.value == second.left.value
                                IN_LIST -> throw IllegalStateException("Can't use IN operator with single ints: ${andQueries.terms}")
                            }
                        } else if (second.left is IntArrayValue) {
                            when (sqlOperator) {
                                EQUALS -> throw IllegalStateException("Can't use '=' operator with int arrays: ${andQueries.terms}")
                                IN_LIST -> second.left.value.contains(first.left.value)
                            }
                        } else {
                            throw IllegalStateException("Can't compare an IntValue (${first.left}) with a ${second::class.simpleName} (${second.left}).")
                        }
                        is StringArrayValue -> if (second.left is StringArrayValue) {
                            when (sqlOperator) {
                                EQUALS -> first.left.value == second.left.value
                                IN_LIST -> throw IllegalStateException("Can't use IN operator with pairs of string arrays: ${andQueries.terms}")
                            }
                        } else {
                            throw IllegalStateException("Can't compare an StringArrayValue (${first.left}) with a ${second::class.simpleName} (${second.left}).")
                        }
                        is IntArrayValue -> if (second.left is IntArrayValue) {
                            when (sqlOperator) {
                                EQUALS -> first.left.value == second.left.value
                                IN_LIST -> throw IllegalStateException("Can't use IN operator with pairs of int arrays: ${andQueries.terms}")
                            }
                        } else {
                            throw IllegalStateException("Can't compare an IntArrayValue (${first.left}) with a ${second::class.simpleName} (${second.left}).")
                        }
                    }.also {
                        // use real operator not always equals
                        println("Compared ${first.left} $sqlOperator ${second.left} and got $it")
                    }.toString().uppercase()
                } else {
                    val leftOperand = when (first) {
                        is Left -> first.left.toSqlString()
                        is Right -> first.right.name
                    }
                    val rightOperand = when (second) {
                        is Left -> second.left.toSqlString()
                        is Right -> second.right.name
                    }
                    val operand = when (sqlOperator) {
                        EQUALS -> " = "
                        IN_LIST -> " IN "
                    }
                    leftOperand + operand + rightOperand
                }

                clause
            }
        }
        val innerParsed = parsed.map { andQueries -> andQueries.joinToString(prefix = "(", postfix = ")", separator = " AND ") }

        return innerParsed.joinToString(prefix = "(", postfix = ")", separator = " OR ")
    }

    private fun parseOperand(leftTerm: Term, inputMap: Map<String, Any>): Either<ConstantValue, ColumnName> = when (leftTerm) {
        is RefTerm -> {
            val dataSource = leftTerm.value.first().value
            if (dataSource != "input") {
                throw IllegalStateException("Unknown data source $dataSource")
            }
            val path = leftTerm.value.drop(1).map {
                if (it.type != "string") {
                    throw IllegalStateException("Unexpected value type in $it")
                }
                it.value
            }
            val valueFromInput = getValueFromNestedMapRecursive(inputMap, path)
            valueFromInput?.let {
                when (it) {
                    is String -> Left(StringValue(it))
                    is Int -> Left(IntValue(it))
                    is ArrayList<*> -> {
                        when (it.first()) {
                            is String -> Left(StringArrayValue(it as ArrayList<String>))
                            is Int -> Left(IntArrayValue(it as ArrayList<Int>))
                            else -> throw IllegalStateException("No support for $it data type")
                        }
                    }

                    else -> throw IllegalStateException("No support for $it data type")
                }
            } ?: Right(ColumnName(path.joinToString(".")))
        }

        is StringTerm -> Left(StringValue(leftTerm.value))
    }

    private fun readFileFromResources(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }

    // there must be libraries for accessing json recursively
    tailrec fun getValueFromNestedMapRecursive(current: Map<String, Any>, path: List<String>): Any? {
        if (path.isEmpty()) {
            throw IllegalArgumentException("Path cannot be empty")
        }

        val nextKey = path.first()
        val nextValue = current[nextKey]

        return when (nextValue) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                getValueFromNestedMapRecursive(nextValue as Map<String, Any>, path.drop(1))
            }

            null -> null
            else -> if (path.size == 1) nextValue else throw IllegalArgumentException("Path does not lead to a final value")
        }
    }
}

data class Value(val type: String, val value: String)
data class Query(val index: Int, val terms: List<Term>)
data class Partial(val queries: List<List<Query>>)
data class Result(val partial: Partial)

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = RefTerm::class, name = "ref"),
    JsonSubTypes.Type(value = StringTerm::class, name = "string"),
)
sealed interface Term
data class RefTerm(val value: List<Value>) : Term
data class StringTerm(val value: String) : Term
// TODO there are probably other primitive types here like NumericTerm etc.

enum class SqlOperator { EQUALS, IN_LIST }

sealed class Either<out E, out V>
data class Left<E>(val left: E) : Either<E, Nothing>()
data class Right<V>(val right: V) : Either<Nothing, V>()

fun <E, V, R> Either<E, V>.map(transform: (V) -> R): Either<E, R> = when (this) {
    is Right -> Right(transform(this.right))
    is Left -> this
}

fun <E, V> Either<E, Either<E, V>>.flatten(): Either<E, V> = when (this) {
    is Left -> this
    is Right -> this.right
}

sealed interface ConstantValue {
    fun toSqlString(): String
}
data class StringValue(val value: String) : ConstantValue {
    override fun toSqlString() = "\"$value\""
}
data class IntValue(val value: Int) : ConstantValue {
    override fun toSqlString() = "$value"
}
data class StringArrayValue(val value: ArrayList<String>) : ConstantValue {
    override fun toSqlString() = value.joinToString(prefix = "['", postfix = "']", separator = "', '")
}
data class IntArrayValue(val value: ArrayList<Int>) : ConstantValue {
    override fun toSqlString() = value.joinToString(prefix = "[", postfix = "]", separator = ", ")
}

data class ColumnName(val name: String)
