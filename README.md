# Java Database Engine

A relational database engine built from scratch in Java, modeled after MySQL.

## Current Features
- In-memory storage with typed columns (INTEGER, VARCHAR, BOOLEAN, FLOAT)
- Schema validation on insert and update (types and column names)
- Interactive REPL supporting CREATE, INSERT, SELECT, UPDATE and DELETE
- WHERE conditions with `=`, `!=`, `>`, `<`, `>=`, `<=`, combined with AND
- Choosing columns in SELECT
- Quoted text values (`name="Bob Smith"`)
- Saving tables to JSON files and loading them back (SAVE / LOAD)

## Usage
```
CREATE users id:INTEGER name:VARCHAR active:BOOLEAN
INSERT users id=1 name="Alice Smith" active=true
INSERT users id=2 name=Bob active=false
SELECT users name,id WHERE active=true AND id>=1
UPDATE users SET active=true WHERE name=Bob
DELETE users WHERE id=2
SAVE users users.json
LOAD users.json
EXIT
```

## Roadmap
- SQL parser
- JOIN support
- Indexing