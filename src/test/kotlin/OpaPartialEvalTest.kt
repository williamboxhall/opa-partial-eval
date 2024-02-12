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

    @Test
    fun `generates SQL fragments for all operator types`() {
        assertEquals(
            """
                ((entity.a = 1 AND
                entity.b @> ARRAY['bar', 'foo'] AND
                entity.b <@ ARRAY['bar', 'foo'] AND
                entity.b2 = ARRAY['foo', 'bar'] AND
                FALSE = ANY(entity.c) AND
                entity.d != 2 AND
                entity.e > 3 AND
                entity.i + 1 = 7 AND
                3 + entity.i3 = 73 AND
                entity.i2 + 2 > 72 AND
                cardinality(entity.f) = 4 AND
                cardinality(entity.f2) > 42 AND
                cardinality(entity.f3) + 3 = 43 AND
                cardinality(entity.f4) + 4 < 44 AND
                abs(entity.g) = 5 AND
                abs(entity.g2) > 52 AND
                ceil(entity.h) = 6 AND
                ceil(entity.h2) > 62 AND
                entity.i4 + 3 + 4 + 5 = 72 AND
                entity.i5 + 3 + 4 + 5 > 73 AND
                entity.j % 8 = 0 AND
                entity.k = 0.912 AND
                (SELECT MAX(val) FROM unnest(entity.l) AS t(val)) < 10 AND
                ARRAY(SELECT val FROM unnest(entity.m) AS t(val) ORDER BY val) = ARRAY['a', 'b'] AND
                (SELECT SUM(val) FROM unnest(entity.n) AS t(val)) = 11 AND
                (SELECT SUM(val) FROM unnest(entity.n2) AS t(val)) + 1 = 12 AND
                starts_with(entity.o, 'foo') = TRUE))
            """.trimIndent(),
            OpaPartialEval.compileApiResponseToSql(readFile("responses/compile-api-result-all-operators.json")).replace(" AND ", " AND\n"),
        )
    }

    private fun readFile(fileName: String): String {
        return this::class.java.getResource(fileName).readText(Charsets.UTF_8)
    }
}
