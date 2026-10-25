package db.parser;

import db.model.Column;
import db.model.DataType;
import db.model.Table;
import db.query.QueryEngine;
import db.statement.InsertStatement;
import db.statement.LoadStatement;
import db.statement.SaveStatement;
import db.statement.SelectStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuotedValuesTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;
    private Table users;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR)
        ));
        engine.addTable(users);
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String command) {
        Parser.parse(command).execute(engine);
    }

    private Object insertedName(String literal) {
        InsertStatement s = (InsertStatement) Parser.parse("INSERT INTO users (name) VALUES (" + literal + ")");
        return s.getValues().get(0);
    }

    @Test
    void singleAndDoubleQuotesBothMakeText() {
        assertEquals("Bob Smith", insertedName("'Bob Smith'"));
        assertEquals("Bob Smith", insertedName("\"Bob Smith\""));
    }

    @Test
    void spacesInsideQuotesAreKeptExactly() {
        assertEquals("  two  spaces  ", insertedName("'  two  spaces  '"));
    }

    @Test
    void quotedValueIsAlwaysText() {
        assertEquals("123", insertedName("'123'"));
        assertEquals("true", insertedName("'true'"));
        assertEquals("NULL", insertedName("'NULL'"));
        assertEquals("1.5", insertedName("'1.5'"));
    }

    @Test
    void emptyQuotedValueIsEmptyText() {
        assertEquals("", insertedName("''"));
    }

    @Test
    void quotedValueMayContainSymbolsAndKeywords() {
        assertEquals("a=b, WHERE (x>1) AND; `y`", insertedName("'a=b, WHERE (x>1) AND; `y`'"));
    }

    @Test
    void doubledQuoteStandsForOneQuote() {
        assertEquals("O'Brien", insertedName("'O''Brien'"));
        assertEquals("say \"hi\"", insertedName("\"say \"\"hi\"\"\""));
    }

    @Test
    void otherQuoteKindNeedsNoEscape() {
        assertEquals("O'Brien", insertedName("\"O'Brien\""));
        assertEquals("say \"hi\"", insertedName("'say \"hi\"'"));
    }

    @Test
    void backslashEscapesQuoteAndBackslash() {
        assertEquals("O'Brien", insertedName("'O\\'Brien'"));
        assertEquals("say \"hi\"", insertedName("\"say \\\"hi\\\"\""));
        assertEquals("back\\slash", insertedName("'back\\\\slash'"));
    }

    @Test
    void otherBackslashesAreKeptLiterally() {
        assertEquals("C:\\Users\\me", insertedName("'C:\\Users\\me'"));
    }

    @Test
    void rejectsMissingClosingQuote() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("INSERT INTO users VALUES (1, 'Bob)"));
        assertEquals("Missing closing quote for text starting at position 30", e.getMessage());
    }

    @Test
    void rejectsTextDirectlyAfterClosingQuote() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("INSERT INTO users (name) VALUES ('Bob'by)"));
    }

    @Test
    void rejectsMissingClosingBacktick() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT `name FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT `` FROM users"));
    }

    @Test
    void quotedValuesWorkInWhereAndSet() {
        run("INSERT INTO users VALUES (1, 'Bob Smith')");
        run("INSERT INTO users VALUES (2, 'Bob')");
        run("UPDATE users SET name = 'Robert Smith' WHERE name = 'Bob Smith'");

        assertEquals("Robert Smith", users.getRows().get(0).get("name"));
        assertEquals("Bob", users.getRows().get(1).get("name"));

        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE name != 'Robert Smith' AND id >= 1");
        assertEquals(List.of(2), QueryEngine.selectWhere(users, s.getWhere()).stream().map(r -> r.get("id")).toList());
    }

    @Test
    void quotedNumberDoesNotMatchIntegerColumn() {
        run("INSERT INTO users (id) VALUES (1)");
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id = '1'");
        assertTrue(QueryEngine.selectWhere(users, s.getWhere()).isEmpty());
    }

    @Test
    void quotedNumberIsRejectedForIntegerColumn() {
        assertThrows(IllegalArgumentException.class, () -> run("INSERT INTO users (id) VALUES ('1')"));
    }

    @Test
    void quotedPathsWithSpacesForSaveAndLoad() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("my folder"));
        Path file = folder.resolve("users table.json");
        run("INSERT INTO users VALUES (1, 'Bob Smith')");

        run("SAVE users '" + file + "'");
        assertTrue(Files.exists(file));

        engine = new QueryEngine();
        run("LOAD \"" + file + "\"");
        assertEquals("Bob Smith", engine.getTable("users").getRows().get(0).get("name"));
    }

    @Test
    void savePathParsing() {
        assertEquals("/tmp/a b.json", ((SaveStatement) Parser.parse("SAVE users '/tmp/a b.json'")).getPath());
        assertEquals("/tmp/a.json", ((LoadStatement) Parser.parse("LOAD \"/tmp/a.json\"")).getPath());
        assertEquals("C:\\data\\a.json", ((LoadStatement) Parser.parse("LOAD 'C:\\data\\a.json'")).getPath());
    }
}
