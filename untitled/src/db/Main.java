package db;

import db.query.QueryEngine;
import db.repl.REPL;

public class Main {
    public static void main(String[] args) {
        System.out.println("""
                Commands:
                  CREATE TABLE <table> (<column> <type> [PRIMARY KEY], ...)   types: INTEGER, VARCHAR, BOOLEAN, FLOAT
                  INSERT INTO <table> [(<column>, ...)] VALUES (<value>, ...)
                  SELECT [DISTINCT] <column> [AS <alias>], ... | * FROM <table> [<alias>]
                    [[INNER | LEFT] JOIN <table> [<alias>] ON <condition>] ... [WHERE <condition>]
                    [GROUP BY <column>, ...] [HAVING <condition>] [ORDER BY <column> [ASC | DESC], ...] [LIMIT <count> [OFFSET <count>]]
                  UPDATE <table> SET <column> = <value>, ... [WHERE ...]
                  DELETE FROM <table> [WHERE ...]
                  CREATE [UNIQUE] INDEX <name> ON <table> (<column>, ...)
                  DROP INDEX <name> ON <table>
                  SHOW INDEX FROM <table>
                  EXPLAIN SELECT ...                shows which indexes a query uses
                  SAVE <table> '<path>'
                  LOAD '<path>'
                  EXIT
                Conditions: <column> <op> <value or column> with op one of = != <> > < >= <=,
                  or <column> IS [NOT] NULL, [NOT] IN (<value>, ...), [NOT] BETWEEN <value> AND <value>,
                  [NOT] LIKE '<pattern>' (% any text, _ one character),
                  combined with AND, OR, NOT and parentheses: (a = 1 OR b = 2) AND NOT c = 3
                Columns: name or table.name (u.name with an alias); u.* selects every column of u;
                  COUNT(*), COUNT([DISTINCT] <column>), SUM, AVG, MIN, MAX in the column list, HAVING and ORDER BY
                Values: 42, -1.5, TRUE, FALSE, NULL, 'text' (text always in quotes)""");
        new REPL().start(new QueryEngine());
    }
}
