package db.repl;

import db.parser.Parser;
import db.query.QueryEngine;
import db.statement.Statement;
import db.storage.StorageException;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

// Like the mysql client, a statement runs once a ; ends it, so it can span several lines and one line can
// hold several statements. EXIT on a line of its own needs no ;, and whatever is left at the end of input runs.
public class REPL {
    Scanner scanner = new Scanner(System.in);
    public void start(QueryEngine query) {
        StringBuilder pending = new StringBuilder();
        while(true) {
            System.out.print(pending.isEmpty() ? "Input a command or type EXIT to exit: " : "    -> ");
            if(!scanner.hasNextLine()) {
                if (!pending.isEmpty()) run(pending.toString(), query);
                break;
            }
            String line = scanner.nextLine();
            if(pending.isEmpty() && isExit(line)) {
                break;
            }
            pending.append(line).append('\n');
            List<String> statements = split(pending);
            if (pending.toString().isBlank()) pending.setLength(0);
            for (String statement : statements) {
                if (isExit(statement)) return;
                run(statement, query);
            }
        }
    }

    private static boolean isExit(String text) {
        return text.trim().equalsIgnoreCase("EXIT");
    }

    private static void run(String input, QueryEngine query) {
        try {
            Statement statement = Parser.parse(input);
            statement.execute(query);
        }
        catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
        catch (StorageException e) {
            System.out.println("Storage error: " + e.getMessage());
        }
        // A bug shouldn't end the session
        catch (RuntimeException e) {
            System.out.println("Internal error: " + e);
        }
    }

    // Takes every statement ended by a ; out of pending, leaving the unfinished rest. A ; inside 'text',
    // "text" or `name` doesn't end a statement; empty statements (;;) are skipped.
    static List<String> split(StringBuilder pending) {
        List<String> statements = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            if (quote != 0) {
                // \' and \\ inside text; a doubled quote ('') closes and reopens, which works out the same
                if (c == '\\' && quote != '`') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == ';') {
                String statement = pending.substring(start, i).trim();
                if (!statement.isEmpty()) statements.add(statement);
                start = i + 1;
            }
        }
        pending.delete(0, start);
        return statements;
    }
}
