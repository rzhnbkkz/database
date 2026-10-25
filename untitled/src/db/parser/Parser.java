package db.parser;

import db.model.Column;
import db.model.DataType;
import db.query.Aggregate;
import db.query.And;
import db.query.Condition;
import db.query.ColumnRef;
import db.query.Expression;
import db.query.Join;
import db.query.Not;
import db.query.Or;
import db.query.OrderBy;
import db.query.SelectItem;
import db.query.SelectQuery;
import db.query.TableRef;
import db.statement.CreateIndexStatement;
import db.statement.CreateStatement;
import db.statement.DeleteStatement;
import db.statement.DropIndexStatement;
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
//   statement := (select | insert | update | delete | create | createIdx | dropIdx | showIdx | explain
//                 | save | load) [';']
//   select    := SELECT ('*' | item {',' item}) FROM table {join} [where] [groupBy] [having] [orderBy] [limit]
//   item      := aggregate | column | name '.' '*'
//   aggregate := COUNT '(' ('*' | column) ')' | (SUM | AVG | MIN | MAX) '(' column ')'
//   column    := name ['.' name]          table.column, where table may be an alias
//   table     := name [[AS] name]         the optional second name is an alias
//   join      := [INNER | LEFT [OUTER]] JOIN table ON or
//   groupBy   := GROUP BY column {',' column}
//   having    := HAVING or                in HAVING, either side of a comparison may be an aggregate
//   orderBy   := ORDER BY sortItem {',' sortItem}
//   sortItem  := (aggregate | column) [ASC | DESC]
//   limit     := LIMIT count [OFFSET count] | LIMIT count ',' count    (the second: LIMIT offset, count)
//   insert    := INSERT INTO name ['(' name {',' name} ')'] VALUES '(' literal {',' literal} ')'
//   update    := UPDATE name SET name '=' literal {',' name '=' literal} [where]
//   delete    := DELETE FROM name [where]
//   create    := CREATE TABLE name '(' columnDef {',' columnDef} ')'
//   columnDef := name type ['(' NUMBER ')'] [PRIMARY KEY]
//   createIdx := CREATE [UNIQUE] INDEX name ON name '(' name {',' name} ')'
//   dropIdx   := DROP INDEX name ON name
//   showIdx   := SHOW (INDEX | INDEXES) FROM name
//   explain   := EXPLAIN select
//   save      := SAVE name STRING
//   load      := LOAD STRING
//   where     := WHERE or
//   or        := and {OR and}             OR binds loosest, so a AND b OR c is (a AND b) OR c
//   and       := not {AND not}
//   not       := NOT not | '(' or ')' | operand op (literal | operand)
//   operand   := column, or in HAVING also aggregate
//   literal   := STRING | ['-'] NUMBER | TRUE | FALSE | NULL
public class Parser {
    private final List<Token> tokens;
    private int index = 0;
    // Set while parsing HAVING, the only place a condition may use an aggregate
    private boolean inHaving = false;

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
            case "DROP" -> parseDropIndex();
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
        List<SelectItem> columns = new ArrayList<>();
        if (!acceptSymbol("*")) {
            do {
                columns.add(parseSelectItem());
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
                groupBy.add(parseColumnRef());
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
                SelectItem item = aggregate != null ? aggregate : parseColumnRef();
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
        return new SelectStatement(new SelectQuery(from, joins, columns, where, groupBy, having, orderBy, limit, offset));
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
        Aggregate aggregate = function == Aggregate.Function.COUNT && acceptSymbol("*")
                ? Aggregate.countAll()
                : new Aggregate(function, parseColumnRef());
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
        expectSymbol("(");
        List<Object> values = new ArrayList<>();
        do {
            values.add(parseLiteral());
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new InsertStatement(table, columns, values);
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
            values.put(column, parseLiteral());
        } while (acceptSymbol(","));
        return new UpdateStatement(table, values, parseWhere());
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
        String table = expectName("table name");
        expectSymbol("(");
        List<Column> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        do {
            String name = expectName("column name");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate column: " + name);
            DataType type = parseDataType();
            boolean primaryKey = false;
            if (acceptKeyword("PRIMARY")) {
                expectKeyword("KEY");
                primaryKey = true;
            }
            columns.add(new Column(name, type, primaryKey));
        } while (acceptSymbol(","));
        expectSymbol(")");
        return new CreateStatement(table, columns);
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

    private Statement parseDropIndex() {
        index++;
        if (!acceptWord("INDEX")) throw expected("INDEX");
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

    private Condition parseCondition() {
        SelectItem column = parseOperand();
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
        // A name on the right compares two columns: u.id = o.user_id
        if (peek().type() == TokenType.IDENTIFIER) return new Condition(column, operator, parseOperand());
        return new Condition(column, operator, parseLiteral());
    }

    // A column, or in HAVING an aggregate
    private SelectItem parseOperand() {
        if (peek().type() == TokenType.IDENTIFIER && tokens.get(index + 1).isSymbol("(") && !inHaving) {
            throw new IllegalArgumentException(peek().text() + "(...) can't be used in WHERE or ON, "
                    + "use HAVING to filter on an aggregate");
        }
        Aggregate aggregate = parseAggregate();
        return aggregate != null ? aggregate : parseColumnRef();
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
