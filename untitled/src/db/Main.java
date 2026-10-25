package db;

import db.query.QueryEngine;
import db.repl.REPL;

public class Main {
    public static void main(String[] args) {
        System.out.println("""
                Commands:
                  CREATE TABLE <table> (<column> <type> [PRIMARY KEY], ...)   types: INTEGER, VARCHAR, BOOLEAN, FLOAT
                  INSERT INTO <table> [(<column>, ...)] VALUES (<value>, ...)
                  SELECT <column>, ... | * FROM <table> [<alias>]
                    [[INNER | LEFT] JOIN <table> [<alias>] ON <condition>] ... [WHERE <condition>]
                    [GROUP BY <column>, ...] [ORDER BY <column> [ASC | DESC], ...] [LIMIT <count> [OFFSET <count>]]
                  UPDATE <table> SET <column> = <value>, ... [WHERE ...]
                  DELETE FROM <table> [WHERE ...]
                  CREATE INDEX <name> ON <table> (<column>)
                  DROP INDEX <name> ON <table>
                  SHOW INDEX FROM <table>
                  EXPLAIN SELECT ...                shows which indexes a query uses
                  SAVE <table> '<path>'
                  LOAD '<path>'
                  EXIT
                Conditions: <column> <op> <value or column> with op one of = != <> > < >= <=,
                  combined with AND, OR, NOT and parentheses: (a = 1 OR b = 2) AND NOT c = 3
                Columns: name or table.name (u.name with an alias); u.* selects every column of u;
                  COUNT(*), COUNT(<column>), SUM(<column>), AVG(<column>) in the column list and ORDER BY
                Values: 42, -1.5, TRUE, FALSE, NULL, 'text' (text always in quotes)""");
        new REPL().start(new QueryEngine());
    }
}
