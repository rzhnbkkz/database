package db.repl;

import db.model.Column;
import db.model.DataType;
import db.model.Table;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class REPLTest {
    private final InputStream originalIn = System.in;
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;
    private Table users;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR)
        ));
        engine.addTable(users);
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice"));
    }

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
    }

    // REPL builds its Scanner from System.in on construction, so input must be set first
    private void run(String input) {
        System.setIn(new ByteArrayInputStream(input.getBytes()));
        new REPL().start(engine);
    }

    @Test
    void exitStopsTheLoop() {
        run("EXIT\n");
        assertEquals("Input a command or type EXIT to exit: ", output.toString());
    }

    @Test
    void exitIsCaseInsensitiveAndTrimmed() {
        assertDoesNotThrow(() -> run("  exit  \n"));
    }

    @Test
    void executesCommandsUntilExit() {
        run("INSERT INTO users VALUES (2, 'Bob');\nSELECT * FROM users WHERE name = 'Bob';\nEXIT\nSELECT * FROM users;\n");

        assertEquals(2, users.getRows().size());
        assertTrue(output.toString().contains("|  2 | Bob  |"));
        assertFalse(output.toString().contains("Alice"), "commands after EXIT must not run");
    }

    @Test
    void printsErrorAndKeepsRunningAfterBadCommand() {
        run("TRUNCATE users;\nINSERT INTO users (id) VALUES ('oops');\nSELECT FROM users;\nSELECT * FROM users;\nEXIT\n");

        String out = output.toString();
        assertTrue(out.contains("Command TRUNCATE is unrecognized"));
        assertTrue(out.contains("Column 'id' expects INTEGER"));
        assertTrue(out.contains("Expected a value, got keyword 'FROM' at position 8"));
        assertTrue(out.contains("|  1 | Alice |"));
    }

    @Test
    void endOfInputStopsTheLoop() {
        // e.g. the user presses Ctrl+D instead of typing EXIT
        assertDoesNotThrow(() -> run("SELECT * FROM users;\n"));
    }

    @Test
    void statementCanSpanSeveralLines() {
        run("SELECT name\n  FROM users\n  WHERE id = 1;\nEXIT\n");

        String out = output.toString();
        assertTrue(out.contains("    -> "), "a continuation prompt is shown");
        assertTrue(out.contains("| Alice |"), out);
        assertFalse(out.contains("unrecognized"), out);
    }

    @Test
    void severalStatementsOnOneLine() {
        run("INSERT INTO users VALUES (2, 'Bob'); INSERT INTO users VALUES (3, 'Carol');\nEXIT\n");
        assertEquals(3, users.getRows().size());
    }

    @Test
    void semicolonInsideTextDoesNotEndTheStatement() {
        run("INSERT INTO users VALUES (2, 'a;b');\nINSERT INTO users VALUES (3, 'it''s;');\nEXIT\n");
        assertEquals("a;b", users.getRows().get(1).get("name"));
        assertEquals("it's;", users.getRows().get(2).get("name"));
    }

    @Test
    void unfinishedStatementRunsAtEndOfInput() {
        run("INSERT INTO users VALUES (2, 'Bob')\n");
        assertEquals(2, users.getRows().size());
    }

    @Test
    void exitWithSemicolon() {
        run("EXIT;\nINSERT INTO users VALUES (2, 'Bob');\n");
        assertEquals(1, users.getRows().size());
    }

}
