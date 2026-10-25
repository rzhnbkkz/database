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
- Saving tables to JSON files and loading them back (SAVE / LOAD)

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
UPDATE users SET active = TRUE, name = 'Bobby' WHERE name = 'Bob';
DELETE FROM users WHERE id = 2;
SAVE users 'users.json';
LOAD 'users.json';
EXIT
```
Types: `INTEGER` (or `INT`), `VARCHAR` (a length like `VARCHAR(50)` is accepted but not enforced), `BOOLEAN` (or `BOOL`), `FLOAT`. Keywords are case-insensitive and the trailing `;` is optional. SAVE and LOAD aren't standard SQL; they're this engine's own commands.

In a join, `SELECT *` labels every column `table.column` (`u.id`, `o.id`), and chosen columns are labeled as written. Joins are nested loops, so they compare every pair of rows.

## Project Structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType
│   ├── query/         QueryEngine (incl. joins); WHERE/ON as an Expression tree: Condition, And, Or, Not;
│   │                  Scope (name resolution), ColumnRef, TableRef, Join
│   ├── statement/     one class per command: Create, Insert, Select, Update, Delete, Save, Load
│   ├── parser/        Lexer (text → tokens) and Parser (tokens → Statement)
│   ├── storage/       TableSerializer / TableDeserializer (JSON files)
│   └── repl/          REPL: the interactive prompt
└── test/db/           JUnit 5 tests, same packages as src
```

## Roadmap
- Indexing