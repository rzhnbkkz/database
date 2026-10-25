## Java Database Engine
impl
A relational database engine built from scratch in Java, modeled after MySQL.

## Current Features
- In-memory storage with typed columns (INTEGER, VARCHAR, BOOLEAN, FLOAT)
- Schema validation on insert and update (types and column names)
- Primary keys: unique and never empty (`id INTEGER PRIMARY KEY`)
- SQL parser (lexer + recursive descent) with error messages that point at the problem, e.g. `Expected FROM, got 'users' at position 10`
- Interactive REPL supporting CREATE TABLE, INSERT, SELECT, UPDATE and DELETE
- WHERE conditions with `=`, `!=`, `<>`, `>`, `<`, `>=`, `<=`, combined with AND, OR, NOT and parentheses. NOT binds tightest, then AND, then OR, as in SQL
- SQL-style NULL logic: comparing an empty cell is *unknown*, so it never matches, not even under NOT
- Choosing columns in SELECT, qualified as `table.column` and `table.*`
- JOIN: `INNER JOIN` and `LEFT [OUTER] JOIN ... ON`, chained, with table aliases (`users u` or `users AS u`), including self joins
- Comparing two columns (`ON u.id = o.user_id`, `WHERE price > cost`)
- Name checking like MySQL: unknown columns and tables are errors, and so is a column name found in more than one joined table
- Values: numbers, `TRUE`/`FALSE`, `NULL` and quoted text (`'Bob Smith'`, `"Bob Smith"`, `'O''Brien'`)
- Keywords can be used as names in backticks (`` `from` ``)
- ORDER BY on one or more columns, ASC or DESC, including columns that aren't selected; LIMIT with OFFSET (or MySQL's `LIMIT offset, count`)
- Aggregates `COUNT(*)`, `COUNT(column)`, `SUM`, `AVG`, `MIN`, `MAX` with `GROUP BY` on one or more columns; ORDER BY can sort by an aggregate
- HAVING to filter groups, comparing aggregates and GROUP BY columns with values or each other (`HAVING MAX(age) > AVG(age)`)
- Indexes: `CREATE INDEX`, `DROP INDEX`, `SHOW INDEX`, plus an automatic `PRIMARY` index on the primary key. They are sorted, like MySQL's default B-tree indexes, so they speed up both `column = value` and ranges (`<`, `<=`, `>`, `>=`, and two ends combined like `age >= 18 AND age < 30`) in WHERE (SELECT, UPDATE, DELETE) and on the joined table's side of ON (`ON a.x = b.y`, `ON o.total > u.min_total`)
- `EXPLAIN SELECT ...` shows, per table, whether it is read with an index or a full scan
- Saving tables to JSON files and loading them back (SAVE / LOAD), including their indexes

## Usage
```
CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(50), active BOOLEAN);
INSERT INTO users VALUES (1, 'Alice Smith', TRUE);
INSERT INTO users (id, name, active) VALUES (2, 'Bob', FALSE);
SELECT name, id FROM users WHERE active = TRUE AND id >= 1;
SELECT * FROM users WHERE (id = 1 OR name = 'Bob') AND NOT active = FALSE;
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
EXPLAIN SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id;
SHOW INDEX FROM orders;
SAVE users 'users.json';
LOAD 'users.json';
EXIT
```
Types: `INTEGER` (or `INT`), `VARCHAR` (a length like `VARCHAR(50)` is accepted but not enforced), `BOOLEAN` (or `BOOL`), `FLOAT`. Keywords are case-insensitive and the trailing `;` is optional. SAVE and LOAD aren't standard SQL; they're this engine's own commands.

In a join, `SELECT *` labels every column `table.column` (`u.id`, `o.id`), and chosen columns are labeled as written. ORDER BY compares values the same way WHERE does: numbers by value, text case-sensitively (`Bob` before `alice`), `FALSE` before `TRUE`. Empty cells come first, or last with DESC, as in MySQL, and rows that tie keep their table order. A query runs join → WHERE → GROUP BY → HAVING → sort → LIMIT/OFFSET → choose columns.

Aggregates follow SQL: empty cells are skipped (`COUNT(age)` counts only rows that have an age), `SUM` and `AVG` of no values are empty while `COUNT` is 0, and without GROUP BY the whole result is one row. `SUM` of an INTEGER column is a whole number; `AVG`, and `SUM` of a FLOAT column, are FLOAT. `MIN` and `MAX` work on any column and compare like WHERE (so text is case-sensitive), returning the column's own type. HAVING works like WHERE on the grouped rows: a column it names must be in GROUP BY, an empty aggregate (such as `MIN` of no values) never matches, and without GROUP BY the whole table is one group. Aggregates can't go in WHERE or ON. As in MySQL's default mode, a selected column that isn't inside an aggregate must be in GROUP BY, and `SELECT *` can't be grouped. Aggregates are labeled in upper case as `COUNT(*)` or `SUM(o.total)`, even when written in lower case.

Joins are nested loops: without an index each row is compared with every row of the joined table, with an index on the joined column only the matching rows are looked up.

Indexes cover one column and help `=` and ranges, but not `!=`, OR or NOT (those fall back to a full scan). When several conditions could use an index, `=` wins since it narrows the most, then a range with both ends; any other conditions are still checked on the rows found. Rows found through an index come back in table order, so an index never changes a result. In a join, the first table can use an index from WHERE and each joined table one from its ON; WHERE conditions on joined tables are not used for lookups. On 3,000 users × 3,000 orders, `JOIN orders o ON u.id = o.user_id` went from about 4.9 s to 7 ms with an index on `orders(user_id)`, and on 100,000 rows `WHERE age >= 50000 AND age < 50100` went from 11 ms to 0.1 ms with an index on `age`.

## Project Structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType, Index (sorted index on one column)
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
- ORDER BY read from an index instead of sorting
- UNIQUE and multi-column indexes