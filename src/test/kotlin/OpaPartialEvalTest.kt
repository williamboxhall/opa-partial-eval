import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OpaPartialEvalTest {
    @Test
    fun `generates SQL fragments for complex 'some i' construct in result payload`() {
        assertEquals(
            """
                ((456 = entity.account_id AND 123 = entity.author_id) OR 
                (456 = entity.account_id AND 789 = entity.author_id) OR 
                (456 = entity.account_id AND 333 = entity.author_id) OR 
                (456 = entity.account_id))""",
            OpaPartialEval.compileApiResponseToSql(readFile("responses/compile-api-result-complex-some-construct.json")),
        )
    }

    @Test
    fun `generates SQL fragments for 'in' construct in result payload`() {
        assertEquals(
            "((456 = entity.account_id AND 123 = entity.author_id) OR (456 = entity.account_id) OR (456 = entity.account_id AND entity.author_id IN [789, 333]))",
            OpaPartialEval.compileApiResponseToSql(readFile("responses/compile-api-result-in-construct.json")),
        )
    }

    /**
     * entity.a = 1 AND
     * entity.b = ['bar', 'foo'] AND
     * false IN entity.c AND
     * entity.d != 2 AND
     * entity.e > 3 AND
     * entity.i + 1 = 7 AND
     * entity.i2 + 2 > 72 AND
     * count(entity.f) = 4 AND
     * count(entity.f2) > 42 AND
     * count(entity.f3) + 3 = 43 AND
     * count(entity.f4) + 4 < 44 AND
     * abs(entity.g) = 5 AND
     * abs(entity.g2) > 52 AND
     * ceil(entity.h) = 6 AND
     * ceil(entity.h2) > 62 AND
     * entity.i2 + 3 + 4 + 5 = 72 AND
     * entity.i3 + 3 + 4 + 5 > 73 AND
     * entity.j % 8 = 8 AND
     * entity.k = 0 AND
     * max(entity.l) < 10 AND
     * sort(entity.m) = ['a', 'b'] AND
     * sum(entity.n) = 11 AND
     * sum(entity.n2) + 1 = 12 AND
     * starts_with(entity.o, "foo") = true
     */
    @Test
    fun `generates SQL fragments for all operator types`() {
        assertEquals(
            "((entity.a = 1 AND entity.b = ['bar', 'foo'] AND FALSE IN entity.c AND entity.d != 2 AND entity.e > 3 AND entity.i + 1 = 7 AND entity.i2 + 2 > 72 AND count(entity.f) = 4 AND count(entity.f2) > 42 AND count(entity.f3) + 3 = 43 AND count(entity.f4) + 4 < 44 AND abs(entity.g) = 5 AND abs(entity.g2) > 52 AND ceil(entity.h) = 6 AND ceil(entity.h2) > 62 AND entity.i2 + 3 + 4 + 5 = 72 AND entity.i3 + 3 + 4 + 5 > 73 AND entity.j % 8 = 8 AND entity.k = 0 AND max(entity.l) < 10 AND sort(entity.m) = ['a', 'b'] AND sum(entity.n) = 11 AND sum(entity.n2) + 1 = 12 AND starts_with(entity.o, \"foo\") = TRUE))",
            OpaPartialEval.compileApiResponseToSql(readFile("responses/compile-api-result-all-operators.json")),
        )
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
