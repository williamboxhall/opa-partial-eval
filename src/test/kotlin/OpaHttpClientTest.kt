import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals

fun main() {
    OpaHttpClientTest.verifyTestResources()
}

object OpaHttpClientTest {
    fun verifyTestResources() {
        val userContextJson = readFile("user-security-context.json")
        val userContext = OpaHttpClient.objectMapper.readValue(userContextJson, UserContext::class.java)
        runBlocking {
            assertEquals(readFile("compile-api-result-complex-some-construct.json"), compileApiResponseJson(userContext, "goals3"))
        }
        println("All test files look correct!")
    }

    private fun compileApiResponseJson(userContext: UserContext, policyPackage: String): String {
        val uglyJson = runBlocking {
            OpaHttpClient.compileApi(userContext, "data.$policyPackage.allow")
        }
        val jsonObject = OpaHttpClient.objectMapper.readValue(uglyJson, Object::class.java)
        val prettyJson = OpaHttpClient.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject)
        return prettyJson
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
