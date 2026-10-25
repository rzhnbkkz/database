# Java Database Engine

A relational database engine built from scratch in Java, modeled after MySQL.

## Current Features
- In-memory storage with typed columns (INTEGER, VARCHAR, BOOLEAN, FLOAT)
- Schema validation on insert and update (types and column names)
- Primary keys: unique and never empty (`id:INTEGER:PRIMARY`)
- Interactive REPL supporting CREATE, INSERT, SELECT, UPDATE and DELETE
- WHERE conditions with `=`, `!=`, `>`, `<`, `>=`, `<=`, combined with AND
- Choosing columns in SELECT
- Quoted text values (`name="Bob Smith"`)
- Saving tables to JSON files and loading them back (SAVE / LOAD)

## Usage
```
CREATE users id:INTEGER:PRIMARY name:VARCHAR active:BOOLEAN
INSERT users id=1 name="Alice Smith" active=true
INSERT users id=2 name=Bob active=false
SELECT users name,id WHERE active=true AND id>=1
UPDATE users SET active=true WHERE name=Bob
DELETE users WHERE id=2
SAVE users users.json
LOAD users.json
EXIT
```

## Project Structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType
│   ├── query/         QueryEngine, Condition (WHERE matching)
│   ├── statement/     one class per command: Create, Insert, Select, Update, Delete, Save, Load
│   ├── parser/        Parser: turns a command line into a Statement
│   ├── storage/       TableSerializer / TableDeserializer (JSON files)
│   └── repl/          REPL: the interactive prompt
└── test/db/           JUnit 5 tests, same packages as src
```

## Roadmap
- SQL parser
- JOIN support
- Indexing