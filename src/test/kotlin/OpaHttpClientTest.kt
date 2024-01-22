import kotlinx.coroutines.runBlocking

fun main() {
    OpaHttpClientTest.verifyTestResources()
}

object OpaHttpClientTest {
    fun verifyTestResources() {
        val userContextJson = readFile("user-security-context.json")
        val userContext = OpaHttpClient.objectMapper.readValue(userContextJson, UserContext::class.java)
        val result = runBlocking {
            OpaHttpClient.compileApi(userContext, "data.goals3.allow")
        }
        println(result)
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
