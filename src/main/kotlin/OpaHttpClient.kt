import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.apache.Apache
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

object OpaHttpClient {
    val objectMapper = jacksonObjectMapper().apply {
        propertyNamingStrategy = PropertyNamingStrategies.SNAKE_CASE
    }

    private val client: HttpClient = HttpClient(Apache) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10000
        }
    }

    suspend fun compileApi(input: UserContext, query: String): CompileResult {
        return objectMapper.readValue(compileApiJson(input, query), CompileResult::class.java)
    }

    internal suspend fun compileApiJson(input: UserContext, query: String): String {
        val body = objectMapper.writeValueAsString(CompileRequest(query, input, listOf("input.entity")))
        println("Querying opa compile api with: $body")
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
