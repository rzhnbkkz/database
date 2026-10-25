## Java Database Engine

A relational database engine built from scratch in Java, modeled after MySQL.

## Current Features
- In-memory storage with typed columns (INTEGER, VARCHAR, BOOLEAN, FLOAT)
- Schema validation on insert and update (types and column names)
- Primary keys: unique and never empty (`id INTEGER PRIMARY KEY`)
- SQL parser (lexer + recursive descent) with error messages that point at the problem, e.g. `Expected FROM, got 'users' at position 10`
- Interactive REPL supporting CREATE TABLE, INSERT, SELECT, UPDATE and DELETE
- WHERE conditions with `=`, `!=`, `<>`, `>`, `<`, `>=`, `<=`, combined with AND
- Choosing columns in SELECT
- Values: numbers, `TRUE`/`FALSE`, `NULL` and quoted text (`'Bob Smith'`, `"Bob Smith"`, `'O''Brien'`)
- Keywords can be used as names in backticks (`` `from` ``)
- Saving tables to JSON files and loading them back (SAVE / LOAD)

## Usage
```
CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(50), active BOOLEAN);
INSERT INTO users VALUES (1, 'Alice Smith', TRUE);
INSERT INTO users (id, name, active) VALUES (2, 'Bob', FALSE);
SELECT name, id FROM users WHERE active = TRUE AND id >= 1;
UPDATE users SET active = TRUE, name = 'Bobby' WHERE name = 'Bob';
DELETE FROM users WHERE id = 2;
SAVE users 'users.json';
LOAD 'users.json';
EXIT
```
Types: `INTEGER` (or `INT`), `VARCHAR` (a length like `VARCHAR(50)` is accepted but not enforced), `BOOLEAN` (or `BOOL`), `FLOAT`. Keywords are case-insensitive and the trailing `;` is optional. SAVE and LOAD aren't standard SQL; they're this engine's own commands.

## Project Structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType
│   ├── query/         QueryEngine, Condition (WHERE matching)
│   ├── statement/     one class per command: Create, Insert, Select, Update, Delete, Save, Load
│   ├── parser/        Lexer (text → tokens) and Parser (tokens → Statement)
│   ├── storage/       TableSerializer / TableDeserializer (JSON files)
│   └── repl/          REPL: the interactive prompt
└── test/db/           JUnit 5 tests, same packages as src
```

## Roadmap
- OR, NOT and parentheses in WHERE
- JOIN support
- Indexing