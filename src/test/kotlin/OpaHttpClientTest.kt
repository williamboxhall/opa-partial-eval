import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.fail

fun main() {
    OpaHttpClientTest.verifyTestResources()
}

object OpaHttpClientTest {
    fun verifyTestResources() {
        val userContextJson = readFile("user-security-context.json")
        val userContext = OpaHttpClient.objectMapper.readValue(userContextJson, UserContext::class.java)
        runBlocking {
            assertEquals(readFile("responses/compile-api-result-complex-some-construct.json"), compileApiResponseJson(userContext, "complex_some_construct"))
            assertEquals(readFile("responses/compile-api-result-complex-in-construct.json"), compileApiResponseJson(userContext, "complex_in_construct"))
            assertEquals(readFile("responses/compile-api-result-all-operators.json"), compileApiResponseJson(userContext, "all_operators"))
            assertEquals(readFile("responses/compile-api-result-simple-without-default.json"), compileApiResponseJson(userContext, "simple_without_default"))
            assertEquals(readFile("responses/compile-api-result-simple-with-default.json"), compileApiResponseJson(userContext, "simple_with_default"))
            assertEquals(readFile("responses/compile-api-result-simple-with-illegal-default.json"), compileApiResponseJson(userContext, "simple_with_illegal_default"))
            assertEquals(readFile("responses/compile-api-result-complex-in-construct-with-default.json"), compileApiResponseJson(userContext, "complex_in_construct_with_default"))
        }
        println("All test files look correct!")
    }

    private fun compileApiResponseJson(userContext: UserContext, policyPackage: String): String {
        val uglyJson = runBlocking {
            OpaHttpClient.compileApiJson(userContext, "data.$policyPackage.allow")
        }
        val jsonObject = OpaHttpClient.objectMapper.readValue(uglyJson, Object::class.java)
        val prettyJson = OpaHttpClient.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)

        try {
            OpaHttpClient.objectMapper.readValue(uglyJson, CompileResult::class.java)
        } catch (e: Exception) {
            fail("Failed to marshall compile api response to CompileResult. Exception below, here's the json: $prettyJson", e)
        }

        return prettyJson
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
