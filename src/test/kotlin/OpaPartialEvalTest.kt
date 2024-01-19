import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class OpaPartialEvalTest {
    @Test
    fun `generates SQL fragments for 'some i' construct`() {
        assertEquals(
            "???",
            OpaPartialEval.compileApiResponseToSql(readFile("compile-api-result-some-construct.json")),
        )
    }

    @Test
    fun `generates SQL fragments for 'in' construct`() {
        assertEquals(
            "((456 = entity.account_id AND 123 = entity.author_id) OR (456 = entity.account_id) OR (456 = entity.account_id AND entity.author_id IN [789, 333]))",
            OpaPartialEval.compileApiResponseToSql(readFile("compile-api-result-in-construct.json")),
        )
    }

    @Test
    fun `generates SQL fragments string equality`() {
        fail("To be implemented")
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
