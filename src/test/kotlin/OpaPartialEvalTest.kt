import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpaPartialEvalTest {
    @Test
    fun `generates SQL fragments for 'some i' construct`() {
        assertEquals(
            "((entity.account_id = 456 AND entity.author_id = 123) OR (entity.account_id = 456 AND TRUE) OR (entity.account_id = 456 AND entity.author_id IN [789, 333]))",
            OpaPartialEval.toSql(readFile("input.json"), readFile("result.json")),
        )
    }

    fun `generates SQL fragments for 'in' construct`() {
    }

    fun `generates SQL fragments string equality`() {
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
