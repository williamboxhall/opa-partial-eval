import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpaPartialEvalTest {
    @Test
    fun `generates SQL fragments for complex 'some i' construct in result payload`() {
        assertEquals(
            "((456 = entity.account_id AND 123 = entity.author_id) OR (456 = entity.account_id AND 789 = entity.author_id) OR (456 = entity.account_id AND 333 = entity.author_id) OR (456 = entity.account_id))",
            OpaPartialEval.compileApiResponseToSql(readFile("compile-api-result-complex-some-construct.json")),
        )
    }

    @Test
    fun `generates SQL fragments for 'in' construct in result payload`() {
        assertEquals(
            "((456 = entity.account_id AND 123 = entity.author_id) OR (456 = entity.account_id) OR (456 = entity.account_id AND entity.author_id IN [789, 333]))",
            OpaPartialEval.compileApiResponseToSql(readFile("compile-api-result-in-construct.json")),
        )
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
