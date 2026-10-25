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

    private Object insertedName(String value) {
        InsertStatement s = (InsertStatement) Parser.parse("INSERT users name=" + value);
        return s.getRow().get("name");
    }

    @Test
    void quotedValueMayContainSpaces() {
        assertEquals("Bob Smith", insertedName("\"Bob Smith\""));
    }

    @Test
    void spacesInsideQuotesAreKeptExactly() {
        assertEquals("  two  spaces  ", insertedName("\"  two  spaces  \""));
    }

    @Test
    void quotedValueIsAlwaysText() {
        assertEquals("123", insertedName("\"123\""));
        assertEquals("true", insertedName("\"true\""));
        assertEquals("1.5", insertedName("\"1.5\""));
    }

    @Test
    void emptyQuotedValueIsEmptyText() {
        assertEquals("", insertedName("\"\""));
    }

    @Test
    void quotedValueMayContainOperatorsAndKeywords() {
        assertEquals("a=b WHERE x>1 AND", insertedName("\"a=b WHERE x>1 AND\""));
    }

    @Test
    void escapedQuoteAndBackslash() {
        assertEquals("say \"hi\"", insertedName("\"say \\\"hi\\\"\""));
        assertEquals("back\\slash", insertedName("\"back\\\\slash\""));
    }

    @Test
    void otherBackslashesAreKeptLiterally() {
        assertEquals("C:\\Users\\me", insertedName("\"C:\\Users\\me\""));
    }

    @Test
    void unquotedValuesStillWork() {
        assertEquals("O'Brien", insertedName("O'Brien"));
        assertEquals(5, ((InsertStatement) Parser.parse("INSERT users id=5")).getRow().get("id"));
    }

    @Test
    void rejectsMissingClosingQuote() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("INSERT users name=\"Bob"));
        assertEquals("Missing closing quote", e.getMessage());
    }

    @Test
    void rejectsTextAfterClosingQuote() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("INSERT users name=\"Bob\"by"));
    }

    @Test
    void quotedValuesWorkInWhereAndSet() {
        run("INSERT users id=1 name=\"Bob Smith\"");
        run("INSERT users id=2 name=Bob");
        run("UPDATE users SET name=\"Robert Smith\" WHERE name=\"Bob Smith\"");

        assertEquals("Robert Smith", users.getRows().get(0).get("name"));
        assertEquals("Bob", users.getRows().get(1).get("name"));

        SelectStatement s = (SelectStatement) Parser.parse("SELECT users WHERE name!=\"Robert Smith\" AND id>=1");
        assertEquals(List.of(2), QueryEngine.selectWhere(users, s.getConditions()).stream().map(r -> r.get("id")).toList());
    }

    @Test
    void quotedNumberDoesNotMatchIntegerColumn() {
        run("INSERT users id=1");
        SelectStatement s = (SelectStatement) Parser.parse("SELECT users WHERE id=\"1\"");
        assertTrue(QueryEngine.selectWhere(users, s.getConditions()).isEmpty());
    }

    @Test
    void quotedNumberIsRejectedForIntegerColumn() {
        assertThrows(IllegalArgumentException.class, () -> run("INSERT users id=\"1\""));
    }

    @Test
    void quotedPathsWithSpacesForSaveAndLoad() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("my folder"));
        Path file = folder.resolve("users table.json");
        run("INSERT users id=1 name=\"Bob Smith\"");

        run("SAVE users \"" + file + "\"");
        assertTrue(Files.exists(file));

        engine = new QueryEngine();
        run("LOAD \"" + file + "\"");
        assertEquals("Bob Smith", engine.getTable("users").getRows().get(0).get("name"));
    }

    @Test
    void savePathParsing() {
        assertEquals("/tmp/a b.json", ((SaveStatement) Parser.parse("SAVE users \"/tmp/a b.json\"")).getPath());
        assertEquals("/tmp/a.json", ((LoadStatement) Parser.parse("LOAD /tmp/a.json")).getPath());
    }
}
