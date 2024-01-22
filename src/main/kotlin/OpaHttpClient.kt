import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.jackson.jackson

object OpaHttpClient {
    val objectMapper = jacksonObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }

    private val client: HttpClient = HttpClient(Apache) {
        install(ContentNegotiation) {
            jackson() {
                 // TODO is this even needed when we're manually mapping to string?
                registerKotlinModule()
                propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
            }
        }

        install(HttpTimeout) {
            connectTimeoutMillis = 10000
        }
    }

    // curl -X POST      -H "Content-Type: application/json"      -d @request.json      http://localhost:8181/v1/compile
    suspend fun compileApi(input: UserContext, query: String): String {
        val body = objectMapper.writeValueAsString(CompileRequest(query, input, listOf("input.entity")))
        println(body)
        val response = client.post("http://localhost:8181/v1/compile") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return response.body()
    }
}

data class UserContext(
    val currentUser: User,
)

data class User(
    val userId: Int,
    val accountId: Int,
    val directReports: List<Int>,
    val roles: List<String>,
    val permissions: List<String>,
    val orgUnits: List<Int>,
)

data class CompileRequest(val query: String, val input: UserContext, val unknowns: List<String>)

/**
 * {
 *   "query": "data.goals.allow",
 *   "input": {
 *     "current_user": {
 *       "user_id": 123,
 *       "account_id": 456,
 *       "direct_reports": [789, 333],
 *       "roles": ["admin", "hrbp:scoped:org-unit:222"],
 *       "permissions": ["view_all_goals", "create_survey"],
 *       "org_units": [222, 444, 666]
 *     }
 *   },
 *   "unknowns": ["input.entity"]
 * }
 */
