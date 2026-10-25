## Java Database Engine
impl
A relational database engine built from scratch in Java, modeled after MySQL.

## Current Features
- In-memory storage with typed columns (INTEGER, VARCHAR, BOOLEAN, FLOAT)
- Schema validation on insert and update (types and column names)
- Primary keys: unique and never empty (`id INTEGER PRIMARY KEY`), optionally `AUTO_INCREMENT`
- Column options `NOT NULL` and `DEFAULT <value>`, and `DEFAULT` as a value in INSERT and UPDATE
- Multi-row INSERT (`VALUES (...), (...)`), all-or-nothing
- `DROP TABLE [IF EXISTS]`, `CREATE TABLE IF NOT EXISTS`, and `ALTER TABLE` to add, drop and rename columns or rename the table
- SQL parser (lexer + recursive descent) with error messages that point at the problem, e.g. `Expected FROM, got 'users' at position 10`
- Interactive REPL supporting CREATE TABLE, INSERT, SELECT, UPDATE and DELETE
- WHERE conditions with `=`, `!=`, `<>`, `>`, `<`, `>=`, `<=`, combined with AND, OR, NOT and parentheses. NOT binds tightest, then AND, then OR, as in SQL
- SQL-style NULL logic: comparing an empty cell is *unknown*, so it never matches, not even under NOT
- Choosing columns in SELECT, qualified as `table.column` and `table.*`, with `AS` aliases that GROUP BY, HAVING and ORDER BY can use
- `SELECT DISTINCT`, and `COUNT(DISTINCT column)` (also SUM, AVG, MIN, MAX)
- `IS [NOT] NULL`, `[NOT] IN (...)`, `[NOT] BETWEEN ... AND ...`, `[NOT] LIKE 'pattern'`
- Expressions everywhere a value goes (select list, WHERE/ON/HAVING, GROUP BY, ORDER BY, aggregates, `UPDATE ... SET`, `VALUES`): `+ - * / %`, parentheses, `CASE`, and the functions UPPER, LOWER, LENGTH, TRIM, CONCAT, SUBSTRING, REPLACE, ABS, ROUND, FLOOR, CEIL, COALESCE, IFNULL, NULLIF
- `GROUP BY` and `ORDER BY` by position in the select list (`ORDER BY 2`), as in MySQL
- JOIN: `INNER JOIN` and `LEFT [OUTER] JOIN ... ON`, chained, with table aliases (`users u` or `users AS u`), including self joins
- Comparing two columns (`ON u.id = o.user_id`, `WHERE price > cost`)
- Name checking like MySQL: unknown columns and tables are errors, and so is a column name found in more than one joined table
- Values: numbers, `TRUE`/`FALSE`, `NULL` and quoted text (`'Bob Smith'`, `"Bob Smith"`, `'O''Brien'`)
- Keywords can be used as names in backticks (`` `from` ``)
- ORDER BY on one or more columns, ASC or DESC, including columns that aren't selected; LIMIT with OFFSET (or MySQL's `LIMIT offset, count`)
- Aggregates `COUNT(*)`, `COUNT(column)`, `SUM`, `AVG`, `MIN`, `MAX` with `GROUP BY` on one or more columns; ORDER BY can sort by an aggregate
- HAVING to filter groups, comparing aggregates and GROUP BY columns with values or each other (`HAVING MAX(age) > AVG(age)`)
- Indexes: `CREATE [UNIQUE] INDEX` on one or more columns, `DROP INDEX`, `SHOW INDEX`, plus an automatic `PRIMARY` index on the primary key. They are sorted, like MySQL's default B-tree indexes, so they speed up both `column = value` and ranges (`<`, `<=`, `>`, `>=`, and two ends combined like `age >= 18 AND age < 30`) in WHERE (SELECT, UPDATE, DELETE) and on the joined table's side of ON (`ON a.x = b.y`, `ON o.total > u.min_total`). An ORDER BY that matches an index is read in index order instead of being sorted, and LIMIT then stops reading early
- UNIQUE indexes reject duplicate values on INSERT and UPDATE (several empty cells are fine, as in MySQL)
- `EXPLAIN SELECT ...` shows, per table, whether it is read with an index or a full scan
- Saving tables to JSON files and loading them back (SAVE / LOAD), including their indexes, column options and AUTO_INCREMENT counter

## Usage
```
CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, active BOOLEAN DEFAULT TRUE);
INSERT INTO users (name) VALUES ('Alice Smith'), ('Bob');
INSERT INTO users VALUES (DEFAULT, 'Carol', FALSE);
ALTER TABLE users ADD COLUMN level INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users RENAME COLUMN level TO rank;
SELECT name, id FROM users WHERE active = TRUE AND id >= 1;
SELECT * FROM users WHERE (id = 1 OR name = 'Bob') AND NOT active = FALSE;
SELECT DISTINCT name FROM users WHERE name LIKE 'A%' AND id BETWEEN 1 AND 10 AND active IS NOT NULL;
SELECT name AS who, COUNT(*) AS n FROM users WHERE id NOT IN (3, 4) GROUP BY who HAVING n > 0 ORDER BY n DESC;
SELECT UPPER(name), CASE WHEN active = TRUE THEN 'yes' ELSE 'no' END FROM users WHERE LENGTH(name) > 3;
UPDATE users SET rank = rank + 1 WHERE active = TRUE;
CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT);
INSERT INTO orders VALUES (10, 1, 9.5);
SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id WHERE o.total > 5;
SELECT name, o.id FROM users u LEFT JOIN orders o ON u.id = o.user_id;
SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC, u.name LIMIT 10 OFFSET 20;
SELECT u.name, COUNT(o.id), SUM(o.total) FROM users u LEFT JOIN orders o ON u.id = o.user_id
  GROUP BY u.name HAVING COUNT(o.id) > 0 ORDER BY SUM(o.total) DESC;
UPDATE users SET active = TRUE, name = 'Bobby' WHERE name = 'Bob';
DELETE FROM users WHERE id = 2;
CREATE INDEX idx_user ON orders (user_id);
CREATE UNIQUE INDEX idx_name ON users (name);
CREATE INDEX idx_user_total ON orders (user_id, total);
SELECT * FROM orders WHERE user_id = 1 AND total > 5;
SELECT * FROM orders ORDER BY user_id, total LIMIT 10;
EXPLAIN SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id;
SHOW INDEX FROM orders;
SAVE users 'users.json';
LOAD 'users.json';
EXIT
```
Types: `INTEGER` (or `INT`), `VARCHAR` (a length like `VARCHAR(50)` is accepted but not enforced), `BOOLEAN` (or `BOOL`), `FLOAT`. Keywords are case-insensitive and the trailing `;` is optional. SAVE and LOAD aren't standard SQL; they're this engine's own commands.

In a join, `SELECT *` labels every column `table.column` (`u.id`, `o.id`), and chosen columns are labeled as written. ORDER BY compares values the same way WHERE does: numbers by value, text case-sensitively (`Bob` before `alice`), `FALSE` before `TRUE`. Empty cells come first, or last with DESC, as in MySQL, and rows that tie keep their table order. A query runs join → WHERE → GROUP BY → HAVING → sort → LIMIT/OFFSET → choose columns.

Aggregates follow SQL: empty cells are skipped (`COUNT(age)` counts only rows that have an age), `SUM` and `AVG` of no values are empty while `COUNT` is 0, and without GROUP BY the whole result is one row. `SUM` of an INTEGER column is a whole number; `AVG`, and `SUM` of a FLOAT column, are FLOAT. `MIN` and `MAX` work on any column and compare like WHERE (so text is case-sensitive), returning the column's own type. HAVING works like WHERE on the grouped rows: a column it names must be in GROUP BY, an empty aggregate (such as `MIN` of no values) never matches, and without GROUP BY the whole table is one group. Aggregates can't go in WHERE or ON. As in MySQL's default mode, a selected column that isn't inside an aggregate must be in GROUP BY, and `SELECT *` can't be grouped. Aggregates are labeled in upper case as `COUNT(*)` or `SUM(o.total)`, even when written in lower case.

`x BETWEEN a AND b` means `x >= a AND x <= b`, and `x IN (a, b)` means `x = a OR x = b`, as the SQL standard defines them, so `x NOT IN (1, NULL)` matches no row. `IS NULL` is the only test on an empty cell that is never unknown. LIKE uses `%` for any text and `_` for one character, `\` makes the next character literal, and like other text comparisons it is case-sensitive; a pattern with a fixed start (`'Al%'`) on an indexed text column reads an index range. As in MySQL, `AS` is optional (so `SELECT name id` means `name AS id`), an alias wins over a column with the same name in GROUP BY, HAVING and ORDER BY, aliases can't be used in WHERE, and with DISTINCT, ORDER BY can only use selected columns.

Writes follow MySQL: a multi-row INSERT adds every row or none, and prints the AUTO_INCREMENT numbers it gave out. A column left out of an INSERT (or given `DEFAULT`) gets its DEFAULT, while an explicit NULL stays empty. AUTO_INCREMENT must be the INTEGER primary key; a NULL or missing value gets the next number, a number given by hand moves the counter past it, and numbers of deleted rows aren't reused (the counter is saved with the table). ALTER TABLE does one change at a time: a new NOT NULL column needs a DEFAULT when the table has rows, dropping a column also drops every index that uses it, and the primary key column can't be dropped.

Expressions follow MySQL: an empty (NULL) operand gives an empty result (also in CONCAT; COALESCE, IFNULL and NULLIF are the exceptions), `/` always gives a fraction and dividing by zero gives an empty result, whole numbers stay whole otherwise and overflowing them is an error, ROUND rounds halves away from zero and gives a whole number without decimals, SUBSTRING counts from 1 (negative from the end), and text functions take any value as text. Types are checked before rows are read (`name * 2` is an error). Unlike MySQL, an INTEGER column only takes whole numbers, so `SET n = n / 2` needs ROUND, FLOOR or CEIL. In `UPDATE ... SET`, every expression sees the row as it was before the update (standard SQL; `SET a = b, b = a` swaps). A condition's constant side is worked out while parsing, so `id = 1 + 2` still uses an index; something computed from the column (`id + 0 = 3`) can't. Once rows are grouped, a selected expression can be a GROUP BY expression or built from them (`CONCAT(UPPER(city), '!')` with `GROUP BY UPPER(city)`).

Joins are nested loops: without an index each row is compared with every row of the joined table, with an index on the joined column only the matching rows are looked up.

Indexes help `=` and ranges, but not `!=`, OR or NOT (those fall back to a full scan). A multi-column index is used from its first column on, like in MySQL: `=` on some leading columns, then optionally a range on the next one, so `(user_id, total)` helps `user_id = 1 AND total > 5` and `user_id = 1`, but not `total > 5` alone. The planner picks the index that narrows the most: a UNIQUE index with every column matched by `=`, then the most columns matched by `=`, then a range with both ends; any other conditions are still checked on the rows found. ORDER BY is read from an index when its columns are exactly that index's columns, all from the first table and all ASC or all DESC (and there is no GROUP BY); the index must match exactly, since extra columns would order rows that tie differently from a sort. With LIMIT and nothing left to sort, a query stops reading as soon as it has enough rows; EXPLAIN shows this as `stops reading early`. Rows found through an index come back in table order, so an index never changes a result. In a join, the first table can use an index from WHERE and each joined table one from its ON; WHERE conditions on joined tables are not used for lookups. On 3,000 users × 3,000 orders, `JOIN orders o ON u.id = o.user_id` went from about 4.9 s to 7 ms with an index on `orders(user_id)`, and on 100,000 rows `WHERE age >= 50000 AND age < 50100` went from 11 ms to 0.1 ms with an index on `age`, and `ORDER BY age DESC LIMIT 10` from 98 ms to 0.07 ms.

## Project Structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType, Index (sorted index on one or more columns)
│   ├── query/         QueryEngine (incl. joins); WHERE/ON as an Expression tree: Condition, And, Or, Not;
│   │                  SelectQuery, Scope (name resolution), ColumnRef, Aggregate, TableRef, Join, OrderBy,
│   │                  IndexLookup (index choice)
│   ├── statement/     one class per command: Create, Insert, Select, Update, Delete, Save, Load,
│   │                  CreateIndex, DropIndex, ShowIndex, Explain
│   ├── parser/        Lexer (text → tokens) and Parser (tokens → Statement)
│   ├── storage/       TableSerializer / TableDeserializer (JSON files)
│   └── repl/          REPL: the interactive prompt
└── test/db/           JUnit 5 tests, same packages as src
```

## Roadmap
- DROP TABLE and ALTER TABLE
- Subqueries