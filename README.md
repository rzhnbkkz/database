# Java Database Engine

[![CI](https://github.com/rzhnbkkz/database/actions/workflows/ci.yml/badge.svg)](https://github.com/rzhnbkkz/database/actions/workflows/ci.yml)

A relational database engine built from scratch in Java, modeled after MySQL. Consists of a lexer, a recursive-descent parser, a query planner with sorted (B-tree style) indexes and an interactive prompt.

It handles SQL including joins, subqueries (correlated as well), GROUP BY and HAVING, expressions and functions, and SQL's NULL logic.

```
Input a command or type EXIT to exit: SELECT id, name, city FROM users;
+----+-------+------+
| id | name  | city |
+----+-------+------+
|  1 | Alice | Oslo |
|  2 | Bob   | NULL |
+----+-------+------+
2 rows in set
```

## Highlights
- **SQL**: CREATE / ALTER / DROP TABLE, INSERT, SELECT, UPDATE, DELETE
- **Queries**: `INNER` and `LEFT` joins, `WHERE`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`, `DISTINCT`
- **Subqueries**: `IN`, `EXISTS` and `(SELECT ...)` used as a value, including correlated ones
- **Expressions**: arithmetic, `CASE`, and 14 functions (`UPPER`, `CONCAT`, `ROUND`, `COALESCE`, ...)
- **Indexes**: single and multi-column, `UNIQUE`, used for `=`, ranges, joins and `ORDER BY`, with `EXPLAIN` to see the plan
- **Schema**: primary keys, `AUTO_INCREMENT`, `NOT NULL`, `DEFAULT`, and type checks before any row is read
- **Transactions**: `BEGIN`, `COMMIT` and `ROLLBACK`, all-or-nothing even across a crash
- **Durability**: a write-ahead log in a data folder, so every acknowledged write survives a crash, with checkpoints and recovery on startup
- **Import and export**: `SAVE` and `LOAD` single tables as JSON
- **Errors that point at the problem**: `Expected FROM, got 'users' at position 10`

## Build and run
Requires Java 17 or newer and Maven.
```
mvn test                       # run the tests
mvn package                    # build target/database.jar
java -jar target/database.jar  # start the REPL on the data folder ./data
java -jar target/database.jar mydb   # or on another folder
```

## Example session
```sql
CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50) NOT NULL, active BOOLEAN DEFAULT TRUE);
CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT);
INSERT INTO users (name) VALUES ('Alice Smith'), ('Bob');
INSERT INTO orders VALUES (10, 1, 9.5);

-- Filtering, grouping, sorting
SELECT * FROM users WHERE (id = 1 OR name = 'Bob') AND NOT active = FALSE;
SELECT name AS who, COUNT(*) AS n FROM users WHERE id NOT IN (3, 4) GROUP BY who HAVING n > 0 ORDER BY n DESC;
SELECT UPPER(name), CASE WHEN active = TRUE THEN 'yes' ELSE 'no' END FROM users WHERE LENGTH(name) > 3;

-- Joins
SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id WHERE o.total > 5;
SELECT u.name, COUNT(o.id), SUM(o.total) FROM users u LEFT JOIN orders o ON u.id = o.user_id
  GROUP BY u.name HAVING COUNT(o.id) > 0 ORDER BY SUM(o.total) DESC;

-- Subqueries
SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS orders FROM users u
  WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id AND o.total > 10);

-- Indexes
CREATE INDEX idx_user_total ON orders (user_id, total);
EXPLAIN SELECT * FROM orders WHERE user_id = 1 AND total > 5;

-- Saving (every write is already in the data folder's log)
CHECKPOINT;
SAVE users 'users.json';
LOAD 'users.json';
EXIT
```
More statements are listed below.

---

# Details

## Supported SQL
| Statement | Notes |
|---|---|
| `CREATE TABLE [IF NOT EXISTS] t (col TYPE [options], ...)` | options: `PRIMARY KEY`, `AUTO_INCREMENT`, `NOT NULL`, `DEFAULT <value>` |
| `ALTER TABLE t ADD [COLUMN] ... \| DROP [COLUMN] c \| RENAME COLUMN a TO b \| RENAME [TO] name` | one change at a time |
| `DROP TABLE [IF EXISTS] t` | |
| `INSERT INTO t [(cols)] VALUES (...), (...)` | `DEFAULT` can be used as a value |
| `SELECT [DISTINCT] ... FROM ... [JOIN ... ON ...] [WHERE] [GROUP BY] [HAVING] [ORDER BY] [LIMIT n [OFFSET m]]` | also MySQL's `LIMIT offset, count` |
| `UPDATE t SET c = <expr>, ... [WHERE ...]` | |
| `DELETE FROM t [WHERE ...]` | |
| `CREATE [UNIQUE] INDEX name ON t (col, ...)`, `DROP INDEX name ON t`, `SHOW INDEX FROM t` | |
| `EXPLAIN SELECT ...` | shows, per table, whether an index or a full scan is used |
| `BEGIN` (or `START TRANSACTION`), `COMMIT`, `ROLLBACK` | group statements into one all-or-nothing change |
| `CHECKPOINT` | writes a snapshot of every table and empties the log; also runs on `EXIT` |
| `SAVE t 'file.json'`, `LOAD 'file.json'` | export and import one table; this engine's own commands, not standard SQL |

**Conditions**: `=`, `!=`, `<>`, `<`, `>`, `<=`, `>=`, `IS [NOT] NULL`, `[NOT] IN (...)`, `[NOT] BETWEEN ... AND ...`, `[NOT] LIKE`, `[NOT] EXISTS`, combined with `AND`, `OR`, `NOT` and parentheses. NOT binds tightest, then AND, then OR.

**Functions**: UPPER, LOWER, LENGTH, TRIM, CONCAT, SUBSTRING, REPLACE, ABS, ROUND, FLOOR, CEIL, COALESCE, IFNULL, NULLIF. **Aggregates**: `COUNT(*)`, `COUNT([DISTINCT] x)`, `SUM`, `AVG`, `MIN`, `MAX`.

## Types and values
- Types: `INTEGER` (or `INT`), `VARCHAR` (a length like `VARCHAR(50)` is accepted but not enforced), `BOOLEAN` (or `BOOL`), `FLOAT`.
- Values: numbers (`42`, `-1.5`, `2.5e3`), `TRUE` / `FALSE`, `NULL`, and quoted text (`'Bob Smith'`, `"Bob Smith"`, `'O''Brien'`).
- Keywords are case-insensitive. A keyword can be used as a name in backticks (`` `from` ``).
- Types are checked before any row is read. `name * 2` is an error, and so is comparing different types (`id = '1'`, `name > 5`, `qty = TRUE`). INTEGER and FLOAT can be compared with each other. MySQL would convert the text to a number instead.
- Unlike MySQL, an INTEGER column only takes whole numbers, so `SET n = n / 2` needs ROUND, FLOOR or CEIL.

## The REPL
- Like the `mysql` client, a statement runs once a `;` ends it (outside quotes). A statement can span several lines, and one line can hold several statements.
- `EXIT` needs no `;`. An unfinished statement runs when input ends.
- SELECT prints a table, with empty cells shown as `NULL` and numbers right-aligned.
- An error prints a message and the session continues.

## NULL logic
- Comparing an empty cell is *unknown*, so it never matches, not even under NOT. `IS NULL` is the only test on an empty cell that is never unknown.
- `x IN (a, b)` means `x = a OR x = b`, and `x BETWEEN a AND b` means `x >= a AND x <= b`, as the SQL standard defines them. So `x NOT IN (1, NULL)` matches no row.
- In expressions, an empty operand gives an empty result, also in CONCAT. COALESCE, IFNULL and NULLIF are the exceptions.

## Selecting and sorting
- Columns can be qualified as `table.column` and `table.*`. In a join, `SELECT *` labels every column `table.column` (`u.id`, `o.id`). Chosen columns are labeled as written.
- As in MySQL, `AS` is optional (`SELECT name id` means `name AS id`). An alias wins over a column with the same name in GROUP BY, HAVING and ORDER BY, and can't be used in WHERE.
- GROUP BY and ORDER BY accept a position in the select list (`ORDER BY 2`).
- ORDER BY can use columns that aren't selected, except with DISTINCT. It compares like WHERE: numbers by value, text case-sensitively (`Bob` before `alice`), `FALSE` before `TRUE`. Empty cells come first, or last with DESC. Rows that tie keep their table order.
- LIKE uses `%` for any text and `_` for one character, and `\` makes the next character literal. Like other text comparisons, it is case-sensitive.
- Unknown columns and tables are errors, and so is a column name found in more than one joined table.
- A query runs in this order: join → WHERE → GROUP BY → HAVING → sort → LIMIT/OFFSET → choose columns.

## Aggregates and HAVING
- Empty cells are skipped (`COUNT(age)` counts only rows that have an age). `SUM` and `AVG` of no values are empty, while `COUNT` is 0. Without GROUP BY, the whole result is one row.
- `SUM` of an INTEGER column is a whole number. `AVG`, and `SUM` of a FLOAT column, are FLOAT. `MIN` and `MAX` work on any column and return its type.
- As in MySQL's default mode, a selected column that isn't inside an aggregate must be in GROUP BY, and `SELECT *` can't be grouped. A selected expression can be built from GROUP BY expressions (`CONCAT(UPPER(city), '!')` with `GROUP BY UPPER(city)`).
- HAVING works like WHERE on the grouped rows, and can compare aggregates with each other (`HAVING MAX(age) > AVG(age)`). An empty aggregate never matches. Aggregates can't go in WHERE or ON.
- Aggregates are labeled in upper case (`COUNT(*)`, `SUM(o.total)`), even when written in lower case.

## Expressions
Expressions work everywhere a value goes: the select list, WHERE / ON / HAVING, GROUP BY, ORDER BY, aggregates, `UPDATE ... SET` and `VALUES`. They follow MySQL:
- `/` always gives a fraction, and dividing by zero gives an empty result. Whole numbers otherwise stay whole, and overflowing them is an error.
- ROUND rounds halves away from zero. SUBSTRING counts from 1 (negative counts from the end). Text functions accept any value as text. `REPLACE(s, '', x)` leaves `s` unchanged.
- In `UPDATE ... SET`, every expression sees the row as it was before the update, so `SET a = b, b = a` swaps.
- A condition's constant side is worked out while parsing, so `id = 1 + 2` still uses an index. Something computed from the column (`id + 0 = 3`) can't.

## Writes
- A multi-row INSERT adds every row or none, and prints the AUTO_INCREMENT numbers it gave out.
- A column left out of an INSERT (or given `DEFAULT`) gets its DEFAULT. An explicit NULL stays empty.
- AUTO_INCREMENT must be on the INTEGER primary key. A NULL or missing value gets the next number, and a number given by hand moves the counter past it. Numbers of deleted rows aren't reused, and the counter is saved with the table.
- Primary keys are unique and never empty. UNIQUE indexes reject duplicates on INSERT and UPDATE; several empty cells are fine, as in MySQL.
- ALTER TABLE: a new NOT NULL column needs a DEFAULT when the table has rows. Dropping a column also drops every index that uses it. The primary key column can't be dropped.
- SAVE stores a table's indexes, column options and AUTO_INCREMENT counter along with its rows.

## Durability
The database lives in a data folder (`./data` unless you name another one) holding two files: `snapshot.json`, every table as of a log sequence number (LSN), and `wal.log`, every change since.
- **Write-ahead**: each write is checked first, then written to `wal.log` as one record and forced to disk (fsync), and only then applied and acknowledged. If the write to the log fails, the table doesn't change. A statement is one record, so a multi-row INSERT or an UPDATE of many rows comes back whole or not at all.
- **Row changes, not SQL**: a record holds the statement's effect: the inserted rows, the new version of each updated row, the ids of deleted rows, or the schema change. Recovery re-applies these through the same code as the original write, without re-running any query.
- **Recovery**: opening the folder loads the snapshot, then replays the log records after its LSN. Each record carries a CRC32 checksum. An incomplete last record, left by a crash during a write, was never acknowledged, so it's dropped. A damaged record with good ones after it means corruption, and the folder isn't opened rather than losing those records.
- **Checkpoints**: `CHECKPOINT` writes a new snapshot to a temporary file, renames it into place, and empties the log. A crash before the rename leaves the old snapshot. A crash after it leaves log records the snapshot already holds, which the LSN check skips, so no change is applied twice.
- **When checkpoints run**: on `CHECKPOINT`, on `EXIT`, and by themselves once the log reaches 16 MB, which keeps the log and the time to replay it bounded. The automatic one runs just before the next write is logged, when every earlier statement is fully applied, so a snapshot never holds part of a statement. Inside a transaction it waits until just after the `COMMIT` record is on disk, so a snapshot never holds uncommitted changes. It also runs when opening a folder whose log is already that long. If it fails, the write still goes ahead (the log has every change), a warning is printed, and it's tried again once the log has grown another 16 MB.
- **Row ids**: rows have internal ids that indexes and log records use. The snapshot keeps them, unlike `SAVE` files, which get new ids when loaded.
- **One process at a time**: a lock file stops a second process from opening the same folder.
- **Cost**: the fsync dominates, at about 4 ms per write statement on a laptop SSD. A multi-row INSERT pays it once for all its rows. Reopening 2,000 rows from a snapshot takes about 40 ms.

## Transactions
Outside a transaction every statement is its own (autocommit). `BEGIN` groups the statements that follow until `COMMIT` or `ROLLBACK`, following MySQL:
- Statements inside a transaction see its changes right away. Nothing is written to the log until `COMMIT`, which writes the whole transaction as one record and forces it to disk. After a crash, a transaction is either all there or not there at all. A commit record cut off by a crash counts as no commit.
- `ROLLBACK` puts every table the transaction changed back as it was at `BEGIN`: rows, indexes and AUTO_INCREMENT counters. Unlike MySQL, the counter goes back too, so numbers handed out inside the rolled-back transaction are given out again.
- A statement that fails inside a transaction changes nothing, and the transaction stays open.
- A schema change (`CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, `CREATE INDEX`, `DROP INDEX`, `LOAD`) and `CHECKPOINT` commit an open transaction first, and so does `BEGIN` inside one. So transactions only hold row changes. Unlike MySQL, a schema change that fails its checks (say, a column that already exists) leaves the transaction open.
- `COMMIT` or `ROLLBACK` with no transaction open does nothing. Leaving the REPL with a transaction open rolls it back.
- If writing the commit fails, the transaction is rolled back, so the tables never hold changes the log doesn't.
- There's one session, so there's nothing to isolate from: no locks, and no other connection that could see uncommitted rows.
- Each row change inside a transaction records its inverse first: the ids to delete for an INSERT, the old rows for an UPDATE or DELETE. `ROLLBACK` applies them newest first, updating indexes as it goes. Both cost time in proportion to the rows changed, not the size of the table. Rows are kept in id order, which is insertion order, so deleted rows go back to where they were.

## Subqueries
- A name the subquery's own tables don't have is looked up in the outer query, so `o.user_id = u.id` uses the outer row's `u.id`. This also works from subqueries nested deeper.
- A subquery used as a value, or after IN, selects one column. As a value, no rows give an empty value, and more than one row is an error.
- `x IN (SELECT ...)` is FALSE when the subquery gives no rows. Following the SQL standard, `NOT IN` matches nothing once the subquery gives an empty value; use `WHERE ... IS NOT NULL` inside it, or NOT EXISTS.
- A subquery's value isn't known until it runs, so comparing it with another type is unknown and never matches, also with `!=`.
- A subquery without outer columns runs once. One with them runs for each outer row, so an index on the inner table helps a lot. EXISTS stops at the first row.
- Subqueries in UPDATE and DELETE see the table as it was before the change, so unlike MySQL they may read the table being changed.

## Indexes and performance
Indexes are sorted, like MySQL's default B-tree indexes. Every table gets an automatic `PRIMARY` index on its primary key.
- **What they speed up**: `column = value` and ranges (`<`, `<=`, `>`, `>=`, or both ends like `age >= 18 AND age < 30`) in WHERE (SELECT, UPDATE, DELETE), and the joined table's side of ON (`ON a.x = b.y`, `ON o.total > u.min_total`). A LIKE pattern with a fixed start (`'Al%'`) on an indexed text column reads a range.
- **What they don't**: `!=`, OR and NOT fall back to a full scan.
- **Multi-column indexes** are used from the first column on: `=` on some leading columns, then optionally a range on the next one. So `(user_id, total)` helps `user_id = 1 AND total > 5` and `user_id = 1`, but not `total > 5` alone.
- **Choosing an index**: the planner picks the one that narrows the most: a UNIQUE index with every column matched by `=`, then the most columns matched by `=`, then a range with both ends. Any other conditions are still checked on the rows found.
- **ORDER BY** is read in index order when its columns are exactly an index's columns, all from the first table and all ASC or all DESC, with no GROUP BY. The match must be exact, because extra columns would order tied rows differently from a sort.
- **LIMIT** with nothing left to sort stops reading once it has enough rows. EXPLAIN shows this as `stops reading early`.
- **Joins** are nested loops. The first table can use an index from WHERE, and each joined table one from its ON. WHERE conditions on joined tables aren't used for lookups.
- Rows found through an index come back in table order, so an index never changes a result.

| Query | Without index | With index |
|---|---|---|
| `JOIN orders o ON u.id = o.user_id` (3,000 × 3,000 rows) | 4.9 s | 7 ms |
| Correlated subquery (2,000 users × 20,000 orders) | 3.6 s | 16 ms |
| `WHERE age >= 50000 AND age < 50100` (100,000 rows) | 11 ms | 0.1 ms |
| `ORDER BY age DESC LIMIT 10` (100,000 rows) | 98 ms | 0.07 ms |

## Project structure
```
untitled/
├── src/db/
│   ├── Main.java      entry point
│   ├── model/         Table, Row, Column, DataType, Index (sorted index on one or more columns),
│   │                  Change (one statement's effect), Journal (where changes are written first)
│   ├── query/         QueryEngine (planning, joins, grouping); WHERE/ON as an Expression tree
│   │                  (Condition, And, Or, Not, ...); value expressions (ColumnRef, Aggregate,
│   │                  Arithmetic, FunctionCall, CaseWhen, Subquery); Scope (name resolution);
│   │                  IndexLookup (index choice)
│   ├── statement/     one class per command, plus ResultTable (prints results as a table)
│   ├── parser/        Lexer (text → tokens) and Parser (tokens → Statement)
│   ├── storage/       Database (data folder, recovery, checkpoints), WriteAheadLog,
│   │                  TableSerializer / TableDeserializer (JSON)
│   └── repl/          REPL: the interactive prompt
└── test/db/           JUnit 5 tests, same packages as src
```