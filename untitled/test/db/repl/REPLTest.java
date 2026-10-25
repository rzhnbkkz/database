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
        run("INSERT users id=2 name=Bob\nSELECT users WHERE name=Bob\nEXIT\nSELECT users\n");

        assertEquals(2, users.getRows().size());
        assertTrue(output.toString().contains("id=2") && output.toString().contains("name=Bob"));
        assertFalse(output.toString().contains("Alice"), "commands after EXIT must not run");
    }

    @Test
    void printsErrorAndKeepsRunningAfterBadCommand() {
        run("DROP users\nINSERT users id=oops\nSELECT users\nEXIT\n");

        String out = output.toString();
        assertTrue(out.contains("Command DROP is unrecognized"));
        assertTrue(out.contains("Column 'id' expects INTEGER"));
        assertTrue(out.contains("id=1") && out.contains("name=Alice"));
    }

    @Test
    void endOfInputStopsTheLoop() {
        // e.g. the user presses Ctrl+D instead of typing EXIT
        assertDoesNotThrow(() -> run("SELECT users\n"));
    }
}
