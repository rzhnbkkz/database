package db.parser;

import db.model.Column;
import db.model.DataType;
import db.model.Table;
import db.query.Aggregate;
import db.query.And;
import db.query.Condition;
import db.query.ColumnRef;
import db.query.Expression;
import db.query.IsNull;
import db.query.Join;
import db.query.Like;
import db.query.Not;
import db.query.Or;
import db.query.OrderBy;
import db.query.SelectItem;
import db.query.SelectQuery;
import db.query.TableRef;
import db.statement.AlterTableStatement;
import db.statement.CreateIndexStatement;
import db.statement.CreateStatement;
import db.statement.DeleteStatement;
import db.statement.DropIndexStatement;
import db.statement.DropTableStatement;
import db.statement.ExplainStatement;
import db.statement.InsertStatement;
import db.statement.LoadStatement;
import db.statement.SaveStatement;
import db.statement.SelectStatement;
import db.statement.ShowIndexStatement;
import db.statement.Statement;
import db.statement.UpdateStatement;

import java.util.*;

// Recursive descent parser: one method per grammar rule, each consuming the tokens it recognises.
//
//   statement := (select | insert | update | delete | create | alter | drop | createIdx | dropIdx | showIdx
//                 | explain | save | load) [';']
//   select    := SELECT [DISTINCT] ('*' | item {',' item}) FROM table {join} [where] [groupBy] [having] [orderBy] [limit]
//   item      := (aggregate | column) [[AS] name] | name '.' '*'
//   aggregate := COUNT '(' '*' ')' | (COUNT | SUM | AVG | MIN | MAX) '(' [DISTINCT] column ')'
//   column    := name ['.' name]          table.column, where table may be an alias
//   table     := name [[AS] name]         the optional second name is an alias
//   join      := [INNER | LEFT [OUTER]] JOIN table ON or
//   groupBy   := GROUP BY column {',' column}
//   having    := HAVING or                in HAVING, either side of a comparison may be an aggregate
//                                         GROUP BY, HAVING and ORDER BY may name a select-list alias
//   orderBy   := ORDER BY sortItem {',' sortItem}
//   sortItem  := (aggregate | column) [ASC | DESC]
//   limit     := LIMIT count [OFFSET count] | LIMIT count ',' count    (the second: LIMIT offset, count)
//   insert    := INSERT INTO name ['(' name {',' name} ')'] VALUES tuple {',' tuple}
//   tuple     := '(' newValue {',' newValue} ')'
//   newValue  := literal | DEFAULT
//   update    := UPDATE name SET name '=' newValue {',' name '=' newValue} [where]
//   delete    := DELETE FROM name [where]
//   create    := CREATE TABLE [IF NOT EXISTS] name '(' columnDef {',' columnDef} ')'
//   columnDef := name type ['(' NUMBER ')'] {PRIMARY KEY | NOT NULL | NULL | DEFAULT literal | AUTO_INCREMENT}
//   alter     := ALTER TABLE name (ADD [COLUMN] columnDef | DROP [COLUMN] name
//                 | RENAME COLUMN name TO name | RENAME [TO] name)
//   drop      := DROP TABLE [IF EXISTS] name
//   createIdx := CREATE [UNIQUE] INDEX name ON name '(' name {',' name} ')'
//   dropIdx   := DROP INDEX name ON name
//   showIdx   := SHOW (INDEX | INDEXES) FROM name
//   explain   := EXPLAIN select
//   save      := SAVE name STRING
//   load      := LOAD STRING
//   where     := WHERE or
//   or        := and {OR and}             OR binds loosest, so a AND b OR c is (a AND b) OR c
//   and       := not {AND not}
//   not       := NOT not | '(' or ')' | test
//   test      := operand op value | operand IS [NOT] NULL | operand [NOT] IN '(' literal {',' literal} ')'
//              | operand [NOT] BETWEEN value AND value | operand [NOT] LIKE STRING
//   value     := literal | operand
//   operand   := column, or in HAVING also aggregate
//   literal   := STRING | ['-'] NUMBER | TRUE | FALSE | NULL
public class Parser {
    private final List<Token> tokens;
    private int index = 0;
    // Set while parsing HAVING, the only place a condition may use an aggregate
    private boolean inHaving = false;
    // The SELECT list's aliases, which GROUP BY, HAVING and ORDER BY may use in place of what they name
    private final Map<String, SelectItem> aliases = new HashMap<>();

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Statement parse(String input) {
        return new Parser(Lexer.tokenize(input)).parseStatement();
    }

    private Statement parseStatement() {
        Token first = peek();
        if (first.type() == TokenType.END) throw new IllegalArgumentException("Command is not recognized, try again");
        Statement statement = switch (first.text().toUpperCase()) {
            case "SELECT" -> parseSelect();
            case "INSERT" -> parseInsert();
            case "UPDATE" -> parseUpdate();
            case "DELETE" -> parseDelete();
            case "CREATE" -> parseCreate();
            case "DROP" -> parseDrop();
            case "ALTER" -> parseAlter();
            case "SHOW" -> parseShowIndex();
            case "EXPLAIN" -> {
                index++;
                if (!peek().isKeyword("SELECT")) throw expected("SELECT");
                yield new ExplainStatement((SelectStatement) parseSelect());
            }
            case "SAVE" -> parseSave();
            case "LOAD" -> parseLoad();
            default -> throw new IllegalArgumentException("Command " + first.text() + " is unrecognized");
        };
        acceptSymbol(";");
        if (peek().type() != TokenType.END) throw new IllegalArgumentException("Unexpected " + peek().describe());
        return statement;
    }

    private Statement parseSelect() {
        expectKeyword("SELECT");
        boolean distinct = acceptKeyword("DISTINCT");
        List<SelectItem> columns = new ArrayList<>();
        List<String> columnAliases = new ArrayList<>();
        if (!acceptSymbol("*")) {
            do {
                SelectItem item = parseSelectItem();
                // AS is optional, as in MySQL: SELECT COUNT(*) total
                String alias = acceptKeyword("AS") || peek().type() == TokenType.IDENTIFIER ? expectName("alias") : null;
                if (alias != null) {
                    if (item instanceof ColumnRef column && column.isStar()) {
                        throw new IllegalArgumentException(column + " can't have an alias");
                    }
                    if (aliases.put(alias, item) != null) throw new IllegalArgumentException("Alias '" + alias + "' is used twice");
                }
                columns.add(item);
                columnAliases.add(alias);
            } while (acceptSymbol(","));
        }
        expectKeyword("FROM");
        TableRef from = parseTableRef();
        List<Join> joins = new ArrayList<>();
        Join join;
        while ((join = parseJoin()) != null) joins.add(join);
        Expression where = parseWhere();

        List<ColumnRef> groupBy = new ArrayList<>();
        if (acceptKeyword("GROUP")) {
            expectKeyword("BY");
            do {
                SelectItem item = withAlias(parseColumnRef());
                if (item instanceof Aggregate aggregate) {
                    throw new IllegalArgumentException("Can't GROUP BY an aggregate like " + aggregate);
                }
                groupBy.add((ColumnRef) item);
            } while (acceptSymbol(","));
        }

        Expression having = Expression.ALL_ROWS;
        if (acceptKeyword("HAVING")) {
            inHaving = true;
            having = parseOr();
            inHaving = false;
        }

        List<OrderBy> orderBy = new ArrayList<>();
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                Aggregate aggregate = parseAggregate();
                SelectItem item = aggregate != null ? aggregate : withAlias(parseColumnRef());
                boolean descending = acceptWord("DESC");
                if (!descending) acceptWord("ASC");
                orderBy.add(new OrderBy(item, descending));
            } while (acceptSymbol(","));
        }

        Integer limit = null;
        int offset = 0;
        if (acceptKeyword("LIMIT")) {
            limit = parseCount("LIMIT");
            if (acceptWord("OFFSET")) {
                offset = parseCount("OFFSET");
            } else if (acceptSymbol(",")) {
                // MySQL's LIMIT offset, count
                offset = limit;
                limit = parseCount("LIMIT");
            }
        }
        return new SelectStatement(new SelectQuery(distinct, columns, columnAliases, from, joins, where, groupBy, having,
                orderBy, limit, offset));
    }

    // A plain name that is a select-list alias stands for what it names: ORDER BY total, for COUNT(*) AS total.
    // An alias wins over a column of the same name.
    private SelectItem withAlias(ColumnRef ref) {
        return ref.table() == null && aliases.containsKey(ref.column()) ? aliases.get(ref.column()) : ref;
    }

    // A whole number, zero or more, for LIMIT and OFFSET
    private int parseCount(String what) {
        Token token = peek();
        if (token.type() != TokenType.NUMBER || token.text().contains(".")) {
            throw expected("a whole number after " + what);
        }
        index++;
        try {
            return Integer.parseInt(token.text());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is too large: " + token.text());
        }
    }

    // aggregate, column or table.*
    private SelectItem parseSelectItem() {
        Aggregate aggregate = parseAggregate();
        if (aggregate != null) return aggregate;
        String name = expectName("column name");
        if (!acceptSymbol(".")) return new ColumnRef(name);
        if (acceptSymbol("*")) return new ColumnRef(name, "*");
        return new ColumnRef(name, expectName("column name"));
    }

    // A name followed by '(' is a function call; null when the next tokens aren't one
    private Aggregate parseAggregate() {
        Token name = peek();
        if (name.type() != TokenType.IDENTIFIER || !tokens.get(index + 1).isSymbol("(")) return null;
        Aggregate.Function function;
        try {
            function = Aggregate.Function.valueOf(name.text().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown function " + name.text() + ", expected COUNT, SUM, AVG, MIN or MAX");
        }
        index += 2;
        if (function == Aggregate.Function.COUNT && acceptSymbol("*")) {
            expectSymbol(")");
            return Aggregate.countAll();
        }
        boolean distinct = acceptKeyword("DISTINCT");
        Aggregate aggregate = new Aggregate(function, parseColumnRef(), distinct);
        expectSymbol(")");
        return aggregate;
    }

    private ColumnRef parseColumnRef() {
        String name = expectName("column name");
        if (!acceptSymbol(".")) return new ColumnRef(name);
        return new ColumnRef(name, expectName("column name"));
    }

    private TableRef parseTableRef() {
        String table = expectName("table name");
        if (acceptKeyword("AS")) return new TableRef(table, expectName("alias"));
        if (peek().type() == TokenType.IDENTIFIER) return new TableRef(table, expectName("alias"));
        return new TableRef(table);
    }

    // null when no JOIN follows
    private Join parseJoin() {
        Join.Type type = Join.Type.INNER;
        if (acceptKeyword("LEFT")) {
            acceptKeyword("OUTER");
            type = Join.Type.LEFT;
        } else if (!acceptKeyword("INNER") && !peek().isKeyword("JOIN")) {
            return null;
        }
        expectKeyword("JOIN");
        TableRef table = parseTableRef();
        expectKeyword("ON");
        return new Join(type, table, parseOr());
    }

    private Statement parseInsert() {
        expectKeyword("INSERT");
        expectKeyword("INTO");
        String table = expectName("table name");
        List<String> columns = List.of();
        if (acceptSymbol("(")) {
            columns = parseNameList("column name");
            expectSymbol(")");
            Set<String> seen = new HashSet<>();
            for (String column : columns) {
                if (!seen.add(column)) throw new IllegalArgumentException("Column '" + column + "' is listed twice");
            }
        }
        expectKeyword("VALUES");
        List<List<Object>> rows = new ArrayList<>();
        do {
            expectSymbol("(");
            List<Object> values = new ArrayList<>();
            do {
                values.add(parseNewValue());
            } while (acceptSymbol(","));
            expectSymbol(")");
            rows.add(values);
        } while (acceptSymbol(","));
        return InsertStatement.ofRows(table, columns, rows);
    }

    private Statement parseUpdate() {
        expectKeyword("UPDATE");
        String table = expectName("table name");
        expectKeyword("SET");
        Map<String, Object> values = new LinkedHashMap<>();
        do {
            String column = expectName("column name");
            expectSymbol("=");
            if (values.containsKey(column)) throw new IllegalArgumentException("Column '" + column + "' is set twice");
            values.put(column, parseNewValue());
        } while (acceptSymbol(","));
        return new UpdateStatement(table, values, parseWhere());
    }

    // A value to store: a literal, or DEFAULT for the column's default
    private Object parseNewValue() {
        return acceptWord("DEFAULT") ? Table.DEFAULT : parseLiteral();
    }

    private Statement parseDelete() {
        expectKeyword("DELETE");
        expectKeyword("FROM");
        String table = expectName("table name");
        return new DeleteStatement(table, parseWhere());
    }

    private Statement parseCreate() {
        expectKeyword("CREATE");
        if (acceptWord("INDEX")) return parseCreateIndex(false);
        if (acceptWord("UNIQUE")) {
            if (!acceptWord("INDEX")) throw expected("INDEX");
            return parseCreateIndex(true);
        }
        if (!acceptKeyword("TABLE")) throw expected("TABLE, INDEX or UNIQUE INDEX");
        boolean ifNotExists = false;
        if (acceptWord("IF")) {
            expectKeyword("NOT");
            expectWord("EXISTS");
            ifNotExists = true;
        }
        String table = expectName("table name");
        expectSymbol("(");
        List<Column> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        do {
            Column column = parseColumnDef();
            if (!names.add(column.getName())) throw new IllegalArgumentException("Duplicate column: " + column.getName());
            columns.add(column);
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new CreateStatement(table, columns, ifNotExists);
    }

    // A column's name, type and options, which may come in any order, as in MySQL
    private Column parseColumnDef() {
        String name = expectName("column name");
        DataType type = parseDataType();
        boolean primaryKey = false, notNull = false, nullable = false, autoIncrement = false, hasDefault = false;
        Object defaultValue = null;
        while (true) {
            if (acceptKeyword("PRIMARY")) {
                expectKeyword("KEY");
                primaryKey = true;
            } else if (acceptKeyword("NOT")) {
                expectKeyword("NULL");
                notNull = true;
            } else if (acceptKeyword("NULL")) {
                nullable = true;
            } else if (acceptWord("DEFAULT")) {
                if (hasDefault) throw new IllegalArgumentException("Column '" + name + "' has two DEFAULTs");
                defaultValue = parseLiteral();
                hasDefault = true;
            } else if (acceptWord("AUTO_INCREMENT")) {
                autoIncrement = true;
            } else {
                break;
            }
        }
        if (notNull && nullable) throw new IllegalArgumentException("Column '" + name + "' can't be both NULL and NOT NULL");
        return new Column(name, type, primaryKey, notNull, defaultValue, autoIncrement);
    }

    private Statement parseAlter() {
        index++;
        expectKeyword("TABLE");
        String table = expectName("table name");
        if (acceptWord("ADD")) {
            acceptWord("COLUMN");
            return AlterTableStatement.addColumn(table, parseColumnDef());
        }
        if (acceptWord("DROP")) {
            acceptWord("COLUMN");
            return AlterTableStatement.dropColumn(table, expectName("column name"));
        }
        if (acceptWord("RENAME")) {
            if (acceptWord("COLUMN")) {
                String from = expectName("column name");
                expectWord("TO");
                return AlterTableStatement.renameColumn(table, from, expectName("new column name"));
            }
            if (!acceptWord("TO")) acceptKeyword("AS");
            return AlterTableStatement.renameTable(table, expectName("new table name"));
        }
        throw expected("ADD, DROP or RENAME");
    }

    // After CREATE [UNIQUE] INDEX
    private Statement parseCreateIndex(boolean unique) {
        String name = expectName("index name");
        expectKeyword("ON");
        String table = expectName("table name");
        expectSymbol("(");
        List<String> columns = parseNameList("column name");
        expectSymbol(")");
        return new CreateIndexStatement(name, table, columns, unique);
    }

    private Statement parseDrop() {
        index++;
        if (acceptKeyword("TABLE")) {
            boolean ifExists = false;
            if (acceptWord("IF")) {
                expectWord("EXISTS");
                ifExists = true;
            }
            return new DropTableStatement(expectName("table name"), ifExists);
        }
        if (!acceptWord("INDEX")) throw expected("TABLE or INDEX");
        String name = expectName("index name");
        expectKeyword("ON");
        return new DropIndexStatement(name, expectName("table name"));
    }

    private Statement parseShowIndex() {
        index++;
        if (!acceptWord("INDEX") && !acceptWord("INDEXES")) throw expected("INDEX");
        expectKeyword("FROM");
        return new ShowIndexStatement(expectName("table name"));
    }

    // INTEGER (or INT), VARCHAR with an optional length like VARCHAR(50), BOOLEAN (or BOOL), FLOAT
    private DataType parseDataType() {
        Token token = peek();
        if (token.type() != TokenType.IDENTIFIER) throw expected("a column type");
        index++;
        DataType type = switch (token.text().toUpperCase()) {
            case "INTEGER", "INT" -> DataType.INTEGER;
            case "VARCHAR" -> DataType.VARCHAR;
            case "BOOLEAN", "BOOL" -> DataType.BOOLEAN;
            case "FLOAT" -> DataType.FLOAT;
            default -> throw new IllegalArgumentException("Unknown type " + token.text() + ", expected one of "
                    + Arrays.toString(DataType.values()));
        };
        // The length is accepted for MySQL compatibility but not enforced
        if (type == DataType.VARCHAR && acceptSymbol("(")) {
            if (peek().type() != TokenType.NUMBER || peek().text().contains(".")) throw expected("a length");
            index++;
            expectSymbol(")");
        }
        return type;
    }

    private Statement parseSave() {
        index++;
        String table = expectName("table name");
        return new SaveStatement(table, expectString("a file path in quotes"));
    }

    private Statement parseLoad() {
        index++;
        return new LoadStatement(expectString("a file path in quotes"));
    }

    // Expression.ALL_ROWS when there is no WHERE
    private Expression parseWhere() {
        if (!acceptKeyword("WHERE")) return Expression.ALL_ROWS;
        return parseOr();
    }

    // A chain like a OR b OR c becomes one Or with three parts
    private Expression parseOr() {
        List<Expression> parts = new ArrayList<>();
        do {
            parts.add(parseAnd());
        } while (acceptKeyword("OR"));
        return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }

    private Expression parseAnd() {
        List<Expression> parts = new ArrayList<>();
        do {
            parts.add(parseNot());
        } while (acceptKeyword("AND"));
        return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    private Expression parseNot() {
        if (acceptKeyword("NOT")) return new Not(parseNot());
        if (acceptSymbol("(")) {
            Expression inner = parseOr();
            expectSymbol(")");
            return inner;
        }
        return parseCondition();
    }

    private Expression parseCondition() {
        SelectItem column = parseOperand();
        if (acceptKeyword("IS")) {
            boolean negated = acceptKeyword("NOT");
            expectKeyword("NULL");
            return new IsNull(column, negated);
        }
        boolean negated = acceptKeyword("NOT");
        if (acceptKeyword("IN")) {
            // x IN (a, b) means x = a OR x = b, which also gives SQL's answer for NULLs in the list
            expectSymbol("(");
            List<Expression> options = new ArrayList<>();
            do {
                options.add(new Condition(column, Condition.Operator.EQUALS, parseLiteral()));
            } while (acceptSymbol(","));
            expectSymbol(")");
            Expression in = options.size() == 1 ? options.get(0) : new Or(options);
            return negated ? new Not(in) : in;
        }
        if (acceptKeyword("BETWEEN")) {
            // x BETWEEN a AND b means x >= a AND x <= b, so a range index can answer it
            Object low = parseValue();
            expectKeyword("AND");
            Object high = parseValue();
            Expression between = new And(List.of(new Condition(column, Condition.Operator.GREATER_OR_EQUAL, low),
                    new Condition(column, Condition.Operator.LESS_OR_EQUAL, high)));
            return negated ? new Not(between) : between;
        }
        if (acceptKeyword("LIKE")) {
            Token pattern = peek();
            if (pattern.type() != TokenType.STRING) throw expected("a pattern in quotes, like 'Al%'");
            index++;
            return new Like(column, pattern.text(), negated);
        }
        if (negated) throw expected("IN, BETWEEN or LIKE after NOT");
        Token token = peek();
        Condition.Operator operator;
        if (token.type() != TokenType.SYMBOL) throw expected("a comparison (= != <> < > <= >=)");
        if (token.text().equals("<>")) operator = Condition.Operator.NOT_EQUALS;
        else {
            try {
                operator = Condition.Operator.fromSymbol(token.text());
            } catch (IllegalArgumentException e) {
                throw expected("a comparison (= != <> < > <= >=)");
            }
        }
        index++;
        return new Condition(column, operator, parseValue());
    }

    // A literal, or a name, which compares two columns: u.id = o.user_id
    private Object parseValue() {
        return peek().type() == TokenType.IDENTIFIER ? parseOperand() : parseLiteral();
    }

    // A column, or in HAVING an aggregate
    private SelectItem parseOperand() {
        if (peek().type() == TokenType.IDENTIFIER && tokens.get(index + 1).isSymbol("(") && !inHaving) {
            throw new IllegalArgumentException(peek().text() + "(...) can't be used in WHERE or ON, "
                    + "use HAVING to filter on an aggregate");
        }
        Aggregate aggregate = parseAggregate();
        if (aggregate != null) return aggregate;
        ColumnRef column = parseColumnRef();
        return inHaving ? withAlias(column) : column;
    }

    private Object parseLiteral() {
        Token token = peek();
        if (token.type() == TokenType.STRING) {
            index++;
            return token.text();
        }
        if (acceptKeyword("TRUE")) return true;
        if (acceptKeyword("FALSE")) return false;
        if (acceptKeyword("NULL")) return null;
        boolean negative = acceptSymbol("-");
        token = peek();
        if (token.type() != TokenType.NUMBER) {
            if (token.type() == TokenType.IDENTIFIER && !negative) {
                throw new IllegalArgumentException("Expected a value, got " + token.describe()
                        + " (put text in quotes: '" + token.text() + "')");
            }
            throw expected("a value");
        }
        index++;
        String number = (negative ? "-" : "") + token.text();
        if (!number.contains(".")) {
            try {
                return Integer.parseInt(number);
            } catch (NumberFormatException e) {
                // Too big for INTEGER, falls through to FLOAT
            }
        }
        return Float.parseFloat(number);
    }

    private List<String> parseNameList(String what) {
        List<String> names = new ArrayList<>();
        do {
            names.add(expectName(what));
        } while (acceptSymbol(","));
        return names;
    }

    private Token peek() {
        return tokens.get(index);
    }

    private boolean acceptKeyword(String keyword) {
        if (!peek().isKeyword(keyword)) return false;
        index++;
        return true;
    }

    private boolean acceptSymbol(String symbol) {
        if (!peek().isSymbol(symbol)) return false;
        index++;
        return true;
    }

    // A command word that isn't reserved (INDEX, like SAVE and LOAD, can still be a column name)
    private boolean acceptWord(String word) {
        if (peek().type() != TokenType.IDENTIFIER || !peek().text().equalsIgnoreCase(word)) return false;
        index++;
        return true;
    }

    private void expectWord(String word) {
        if (!acceptWord(word)) throw expected(word);
    }

    private void expectKeyword(String keyword) {
        if (!acceptKeyword(keyword)) throw expected(keyword);
    }

    private void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) throw expected("'" + symbol + "'");
    }

    private String expectName(String what) {
        Token token = peek();
        if (token.type() != TokenType.IDENTIFIER) {
            if (token.type() == TokenType.KEYWORD) {
                throw new IllegalArgumentException("Expected " + what + ", got keyword " + token.describe()
                        + " (use `" + token.text().toLowerCase() + "` to use it as a name)");
            }
            throw expected(what);
        }
        index++;
        return token.text();
    }

    private String expectString(String what) {
        Token token = peek();
        if (token.type() != TokenType.STRING) throw expected(what);
        index++;
        return token.text();
    }

    private IllegalArgumentException expected(String what) {
        return new IllegalArgumentException("Expected " + what + ", got " + peek().describe());
    }
}
