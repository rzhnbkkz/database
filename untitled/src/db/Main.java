package db;

import db.repl.REPL;
import db.storage.Database;
import db.storage.StorageException;

import java.io.IOException;
import java.nio.file.Path;

public class Main {
    // java -jar database.jar [data folder], default ./data
    public static void main(String[] args) {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        Database database;
        try {
            database = Database.open(dir);
        } catch (IOException | StorageException e) {
            System.err.println("Could not open data folder " + dir + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("""
                Commands:
                  CREATE TABLE [IF NOT EXISTS] <table> (<column> <type> [<option> ...], ...)
                    types: INTEGER, VARCHAR, BOOLEAN, FLOAT
                    options: PRIMARY KEY, NOT NULL, DEFAULT <value>, AUTO_INCREMENT (INTEGER primary key only)
                  ALTER TABLE <table> ADD [COLUMN] <column> <type> [<option> ...] | DROP [COLUMN] <column>
                    | RENAME COLUMN <column> TO <name> | RENAME [TO] <name>
                  DROP TABLE [IF EXISTS] <table>
                  INSERT INTO <table> [(<column>, ...)] VALUES (<value>, ...), ...
                  SELECT [DISTINCT] <expression> [AS <alias>], ... | * FROM <table> [<alias>]
                    [[INNER | LEFT] JOIN <table> [<alias>] ON <condition>] ... [WHERE <condition>]
                    [GROUP BY <expression>, ...] [HAVING <condition>] [ORDER BY <expression> [ASC | DESC], ...]
                    [LIMIT <count> [OFFSET <count>]]      GROUP BY 1 / ORDER BY 2: a position in the SELECT list
                  UPDATE <table> SET <column> = <expression>, ... [WHERE ...]     SET n = n + 1
                  DELETE FROM <table> [WHERE ...]
                  CREATE [UNIQUE] INDEX <name> ON <table> (<column>, ...)
                  DROP INDEX <name> ON <table>
                  SHOW INDEX FROM <table>
                  EXPLAIN SELECT ...                shows which indexes a query uses
                  SAVE <table> '<path>'              export a table to a JSON file
                  LOAD '<path>'                      import a table from one
                  BEGIN | START TRANSACTION, COMMIT, ROLLBACK    group statements into one all-or-nothing change
                  CHECKPOINT                         save every table to the data folder's snapshot and empty its log
                  EXIT
                Expressions: columns, values, + - * / %, parentheses, CASE WHEN <condition> THEN ... [ELSE ...] END,
                  UPPER LOWER LENGTH TRIM CONCAT SUBSTRING REPLACE ABS ROUND FLOOR CEIL COALESCE IFNULL NULLIF
                Conditions: <expression> <op> <expression> with op one of = != <> > < >= <=,
                  or <expression> IS [NOT] NULL, [NOT] IN (<value>, ... | SELECT ...), [NOT] BETWEEN <value> AND <value>,
                  [NOT] EXISTS (SELECT ...), and (SELECT ...) as a value; subqueries may use the outer query's columns
                  [NOT] LIKE '<pattern>' (% any text, _ one character),
                  combined with AND, OR, NOT and parentheses: (a = 1 OR b = 2) AND NOT c = 3
                Columns: name or table.name (u.name with an alias); u.* selects every column of u;
                  COUNT(*), COUNT([DISTINCT] <expression>), SUM, AVG, MIN, MAX in the column list, HAVING and ORDER BY
                Values: 42, -1.5, TRUE, FALSE, NULL, 'text' (text always in quotes);
                  in INSERT and UPDATE ... SET also DEFAULT, the column's default""");
        int tables = database.engine().getTables().size();
        System.out.println("Data folder " + dir.toAbsolutePath().normalize() + ": " + tables + (tables == 1 ? " table" : " tables")
                + (database.recoveredChanges() > 0 ? ", " + database.recoveredChanges() + " changes recovered from the log" : ""));
        new REPL().start(database.engine());
        // As in MySQL, leaving with a transaction open rolls it back
        if (database.engine().inTransaction()) {
            database.engine().rollback();
            System.out.println("Open transaction rolled back");
        }
        // Every change is already in the log; the checkpoint just makes the next start faster
        try {
            database.checkpoint();
        } catch (StorageException e) {
            System.err.println(e.getMessage());
        }
        try {
            database.close();
        } catch (IOException e) {
            System.err.println("Could not close data folder " + dir + ": " + e.getMessage());
        }
    }
}
