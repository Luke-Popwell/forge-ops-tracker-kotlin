package io.github.lukepopwell.tracker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

class SqlStatementTest {
    /** Stands in for Hibernate's JDBCException, which exposes the statement as getSQL(). */
    class FakeJdbcException(message: String, private val sql: String) : RuntimeException(message) {
        @Suppress("unused")
        fun getSQL(): String = sql
    }

    /** Stands in for Spring's BadSqlGrammarException, which uses getSql(). */
    class FakeSpringException : RuntimeException("boom") {
        @Suppress("unused")
        fun getSql(): String = "CALL refund_order(1)"
    }

    @Test
    fun `finds the statement via either accessor name`() {
        assertEquals("SELECT 1", SqlStatement.findIn(FakeJdbcException("boom", "SELECT 1")))
        assertEquals("CALL refund_order(1)", SqlStatement.findIn(FakeSpringException()))
    }

    @Test
    fun `walks the cause chain and the SQLException chain`() {
        assertEquals("CALL x(1)", SqlStatement.findIn(RuntimeException("refund failed", FakeJdbcException("db", "CALL x(1)"))))

        val first = SQLException("first")
        first.nextException = SQLException("second", FakeJdbcException("db", "SELECT 2"))
        assertEquals("SELECT 2", SqlStatement.findIn(first))
    }

    @Test
    fun `reads the statement out of SQLite's while compiling text`() {
        val error = RuntimeException("near \"FORM\": syntax error (code 1 SQLITE_ERROR): , while compiling: SELECT * FORM t")
        assertEquals("SELECT * FORM t", SqlStatement.findIn(error))
    }

    @Test
    fun `returns null without SQL`() {
        assertNull(SqlStatement.findIn(IllegalStateException("nope")))
        assertNull(SqlStatement.findIn(FakeJdbcException("blank", "  ")))
        assertNull(SqlStatement.findIn(null))
    }

    @Test
    fun `masks strings and numbers but not identifiers or placeholders`() {
        assertEquals(
            "SELECT * FROM orders2 WHERE email = ? AND id = ? AND x = \$1",
            SqlStatement.mask("SELECT * FROM orders2 WHERE email = 'a@b.co' AND id = 42 AND x = \$1"),
        )
        assertEquals("SELECT price * ? FROM t", SqlStatement.mask("SELECT price * 1.5 FROM t"))
    }

    @Test
    fun `masks an escaped quote, a cut off string and a dollar quoted body`() {
        assertEquals("EXEC sp_x @t = ?", SqlStatement.mask("EXEC sp_x @t = 'it''s'"))
        assertEquals("SELECT ? WHERE n = ?", SqlStatement.mask("SELECT 1 WHERE n = 'oops"))
        assertEquals("DO ?", SqlStatement.mask("DO \$b\$ BEGIN PERFORM 1; END \$b\$"))
    }

    @Test
    fun `is idempotent, truncates and returns null for blank`() {
        val once = SqlStatement.mask("SELECT * FROM t WHERE a = 'x' AND b = 9")
        assertEquals(once, SqlStatement.mask(once))
        assertEquals(SqlStatement.MAX_LENGTH + 3, SqlStatement.mask("SELECT " + "a, ".repeat(3000) + " b")!!.length)
        assertNull(SqlStatement.mask("  "))
        assertNull(SqlStatement.mask(null))
    }

    @Test
    fun `matches the server's masker on every shared case`() {
        for ((input, system, expected) in SHARED_CASES) {
            assertEquals(expected, SqlStatement.mask(input, system), "$input ($system)")
            assertEquals(expected, SqlStatement.mask(expected, system), "masking again changed $expected")
        }
    }

    @Test
    fun `masks a very long string in one piece and never overflows`() {
        assertEquals("SELECT ?", SqlStatement.mask("SELECT '" + "x".repeat(100_000) + "'"))
        assertEquals("SELECT ?", SqlStatement.mask("SELECT \"" + "x".repeat(100_000) + "\"", "mysql"))
        val escapes = SqlStatement.mask("SELECT '" + "x\\'".repeat(50_000) + "'")
        assertTrue(escapes == "SELECT ?" || escapes == "?", escapes)
    }

    @Test
    fun `finds a stored procedure with its schema`() {
        val found = SqlStatement.objects("EXEC dbo.sp_refund_order @id = ?")!!
        assertEquals("EXEC", found["operation"])
        assertEquals(listOf("dbo.sp_refund_order"), found["procedures"])
        assertEquals(emptyList<String>(), found["relations"])
        assertEquals(listOf("refund_order"), SqlStatement.objects("CALL refund_order(?, ?)")!!["procedures"])
        assertEquals(listOf("refund_order"), SqlStatement.objects("SELECT refund_order(?, ?)")!!["procedures"])
    }

    @Test
    fun `finds views, joined tables and table functions`() {
        assertEquals(
            listOf("v_totals", "public.customers"),
            SqlStatement.objects("SELECT * FROM v_totals t JOIN public.customers c ON c.id = t.id")!!["relations"],
        )
        assertEquals(listOf("get_open_orders"), SqlStatement.objects("SELECT * FROM get_open_orders(?) o")!!["procedures"])
    }

    @Test
    fun `does not misread column lists or builtins and returns null for garbage`() {
        assertEquals(emptyList<String>(), SqlStatement.objects("INSERT INTO audit_log (a) VALUES (?)")!!["procedures"])
        assertEquals(emptyList<String>(), SqlStatement.objects("SELECT count(*) FROM orders")!!["procedures"])
        assertNull(SqlStatement.objects("garbage"))
    }

    @Test
    fun `event builder sends the procedure name by default and the masked statement only when opted in`() {
        val error = FakeJdbcException("boom", "EXEC dbo.sp_refund_order @order_id = 8814, @note = 'a@b.co'")
        val config = Configuration().apply { environment = "production" }

        val payload = EventBuilder(config).build(error, emptyMap())
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("dbo.sp_refund_order"), (payload["sql_objects"] as Map<String, Any?>)["procedures"])
        assertFalse(payload.containsKey("sql_statement"))

        config.captureSqlStatement = true
        assertEquals("EXEC dbo.sp_refund_order @order_id = ?, @note = ?", EventBuilder(config).build(error, emptyMap())["sql_statement"])

        config.captureSqlObjects = false
        config.captureSqlStatement = false
        val off = EventBuilder(config).build(error, emptyMap())
        assertFalse(off.containsKey("sql_objects"))
        assertFalse(off.containsKey("sql_statement"))
    }

    companion object {
        /** The server's own test set for SqlStatementMasker: input, db.system (or null), expected. */
        private val SHARED_CASES = listOf<Triple<String, String?, String>>(
            Triple("SELECT * FROM orders WHERE email = 'a@b.co' AND id = 42 LIMIT 10", null, "SELECT * FROM orders WHERE email = ? AND id = ? LIMIT ?"),
            Triple("EXEC sp_note @text = 'it''s broken'", null, "EXEC sp_note @text = ?"),
            Triple("SELECT 1 WHERE name = 'unterminated", null, "SELECT ? WHERE name = ?"),
            Triple("DO \$body\$ BEGIN PERFORM 1; END \$body\$", null, "DO ?"),
            Triple("SELECT \"user id\" FROM orders2 WHERE id = \$1 AND v = sp_v2(?)", null, "SELECT \"user id\" FROM orders2 WHERE id = \$1 AND v = sp_v2(?)"),
            Triple("SELECT price * 1.5 FROM t", null, "SELECT price * ? FROM t"),
            Triple("SELECT * FROM users WHERE name = E'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"),
            Triple("SELECT * FROM users WHERE name = 'o\\'brien' AND id = 1", null, "SELECT * FROM users WHERE name = ? AND id = ?"),
            Triple("SELECT * FROM t WHERE b = X'DEADBEEF' AND s = N'uni' AND u = U&'d\\0061t' AND e = e'x'", null, "SELECT * FROM t WHERE b = ? AND s = ? AND u = ? AND e = ?"),
            Triple("SELECT * FROM t WHERE a LIKE'%secret%'", null, "SELECT * FROM t WHERE a LIKE?"),
            Triple("SELECT * FROM t WHERE f = 0x1F AND b = 0b101 AND n = 3e10 AND m = 1.5E-3 AND k = .5", null, "SELECT * FROM t WHERE f = ? AND b = ? AND n = ? AND m = ? AND k = ?"),
            Triple("SELECT e, t.col, 1e5e FROM t", null, "SELECT e, t.col, 1e5e FROM t"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "mysql", "SELECT ? FROM t WHERE token = ?"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "MariaDB", "SELECT ? FROM t WHERE token = ?"),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", "postgresql", "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""),
            Triple("SELECT \"user id\" FROM t WHERE token = \"abc123secret\"", null, "SELECT \"user id\" FROM t WHERE token = \"abc123secret\""),
            Triple("SELECT * FROM t WHERE a = 'x' AND b = 9", null, "SELECT * FROM t WHERE a = ? AND b = ?"),
            Triple("SELECT * FROM t WHERE a = ? AND b = ?", null, "SELECT * FROM t WHERE a = ? AND b = ?"),
            Triple("SELECT * FROM t WHERE path = 'C:\\\\dir\\\\' AND n = 5", null, "SELECT * FROM t WHERE path = ? AND n = ?"),
            Triple("INSERT INTO t (a, b) VALUES (-5, +3.25e+2)", null, "INSERT INTO t (a, b) VALUES (-?, +?)"),
            Triple("SELECT * FROM t WHERE a = 'secret\\", null, "SELECT * FROM t WHERE a = ?"),
            Triple("SELECT * FROM t WHERE a = \"secret\\", "mysql", "SELECT * FROM t WHERE a = ?"),
        )
    }
}
