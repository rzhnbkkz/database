package db;

import db.query.QueryEngine;
import db.repl.REPL;

public class Main {
    public static void main(String[] args) {
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
                  SAVE <table> '<path>'
                  LOAD '<path>'
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
        new REPL().start(new QueryEngine());
    }
}
