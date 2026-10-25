package db.parser;

import db.model.Column;
import db.model.Row;
import db.model.DataType;
import db.model.Table;
import db.query.Aggregate;
import db.query.And;
import db.query.Arithmetic;
import db.query.CaseWhen;
import db.query.Condition;
import db.query.ColumnRef;
import db.query.Exists;
import db.query.Expression;
import db.query.FunctionCall;
import db.query.InSubquery;
import db.query.IsNull;
import db.query.Join;
import db.query.Like;
import db.query.Literal;
import db.query.Negate;
import db.query.Not;
import db.query.Or;
import db.query.OrderBy;
import db.query.Rewriter;
import db.query.SelectItem;
import db.query.SelectQuery;
import db.query.Subquery;
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
//   item      := expr [[AS] name] | name '.' '*'
//   column    := name ['.' name]          table.column, where table may be an alias
//   table     := name [[AS] name]         the optional second name is an alias
//   join      := [INNER | LEFT [OUTER]] JOIN table ON or
//   groupBy   := GROUP BY expr {',' expr}           a whole number is a select-list position: GROUP BY 1
//   having    := HAVING or                in HAVING, either side of a comparison may be an aggregate
//                                         GROUP BY, HAVING and ORDER BY may name a select-list alias
//   orderBy   := ORDER BY sortItem {',' sortItem}
//   sortItem  := expr [ASC | DESC]                  ORDER BY 2 sorts on the second selected value
//   limit     := LIMIT count [OFFSET count] | LIMIT count ',' count    (the second: LIMIT offset, count)
//   insert    := INSERT INTO name ['(' name {',' name} ')'] VALUES tuple {',' tuple}
//   tuple     := '(' newValue {',' newValue} ')'
//   newValue  := expr | DEFAULT           in VALUES the expression can't use columns: 2 * 3, UPPER('a')
//   update    := UPDATE name SET name '=' newValue {',' name '=' newValue} [where]   (SET n = n + 1)
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
//   test      := expr op expr | expr IS [NOT] NULL | expr [NOT] IN '(' (expr {',' expr} | select) ')'
//              | EXISTS '(' select ')'
//              | expr [NOT] BETWEEN expr AND expr | expr [NOT] LIKE STRING
//   expr      := term {('+' | '-') term}           * / % bind tighter than + -
//   term      := unary {('*' | '/' | '%') unary}
//   unary     := '-' unary | primary
//   primary   := literal | column | '(' expr ')' | '(' select ')' | call | case
//                a subquery used as a value selects one column, and may use the outer query's columns
//   call      := COUNT '(' '*' ')' | aggregate '(' [DISTINCT] expr ')' | function '(' [expr {',' expr}] ')'
//                aggregates (COUNT SUM AVG MIN MAX) only in the select list, HAVING and ORDER BY
//   case      := CASE WHEN or THEN expr {WHEN or THEN expr} [ELSE expr] END
//              | CASE expr WHEN expr THEN expr {WHEN expr THEN expr} [ELSE expr] END
//   literal   := STRING | ['-'] NUMBER | TRUE | FALSE | NULL
public class Parser {
    private final List<Token> tokens;
    private int index = 0;
    // Where the expression being parsed is, when aggregates aren't allowed there (for the error), else null
    private String noAggregatesIn = "WHERE or ON";
    // Whether a name may be a select-list alias: in GROUP BY, HAVING and ORDER BY
    private boolean aliasesAllowed = false;
    // The SELECT list's aliases, which GROUP BY, HAVING and ORDER BY may use in place of what they name
    // (each SELECT has its own: a subquery's aliases aren't the outer query's)
    private Map<String, SelectItem> aliases = new HashMap<>();

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
        return new SelectStatement(parseSelectQuery());
    }

    private SelectQuery parseSelectQuery() {
        Map<String, SelectItem> outerAliases = aliases;
        aliases = new HashMap<>();
        try {
            return parseSelectBody();
        } finally {
            aliases = outerAliases;
        }
    }

    private SelectQuery parseSelectBody() {
        expectKeyword("SELECT");
        boolean distinct = acceptKeyword("DISTINCT");
        List<SelectItem> columns = new ArrayList<>();
        List<String> columnAliases = new ArrayList<>();
        if (!acceptSymbol("*")) {
            do {
                SelectItem item = within(null, false, this::parseSelectItem);
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

        List<SelectItem> groupBy = new ArrayList<>();
        if (acceptKeyword("GROUP")) {
            expectKeyword("BY");
            do {
                SelectItem item = byPosition("GROUP BY", within("GROUP BY", true, this::parseExpr), columns);
                // Through an alias, an aggregate can still turn up
                if (SelectItem.leaves(item).stream().anyMatch(leaf -> leaf instanceof Aggregate)) {
                    throw new IllegalArgumentException("Can't GROUP BY an aggregate like " + item);
                }
                groupBy.add(item);
            } while (acceptSymbol(","));
        }

        Expression having = Expression.ALL_ROWS;
        if (acceptKeyword("HAVING")) having = within(null, true, this::parseOr);

        List<OrderBy> orderBy = new ArrayList<>();
        if (acceptKeyword("ORDER")) {
            expectKeyword("BY");
            do {
                SelectItem item = byPosition("ORDER BY", within(null, true, this::parseExpr), columns);
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
        return new SelectQuery(distinct, columns, columnAliases, from, joins, where, groupBy, having, orderBy, limit, offset);
    }

    // As in MySQL, a whole number in GROUP BY or ORDER BY is a position in the select list: ORDER BY 2 sorts on
    // the second value selected, rather than on the number 2
    private static SelectItem byPosition(String clause, SelectItem item, List<SelectItem> columns) {
        if (!(item instanceof Literal literal && literal.value() instanceof Integer position)) return item;
        if (columns.isEmpty()) throw new IllegalArgumentException(clause + " " + position + " needs the columns listed, not *");
        if (position < 1 || position > columns.size()) {
            throw new IllegalArgumentException(clause + " " + position + " is not a position in the SELECT list, which has "
                    + columns.size() + (columns.size() == 1 ? " value" : " values"));
        }
        SelectItem chosen = columns.get(position - 1);
        if (chosen instanceof ColumnRef column && column.isStar()) {
            throw new IllegalArgumentException(clause + " " + position + " is " + column + ", list the columns instead");
        }
        return chosen;
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

    // An expression, or table.*
    private SelectItem parseSelectItem() {
        if (peek().type() == TokenType.IDENTIFIER && tokens.get(index + 1).isSymbol(".") && tokens.get(index + 2).isSymbol("*")) {
            String table = peek().text();
            index += 3;
            return new ColumnRef(table, "*");
        }
        return parseExpr();
    }

    // Parses with these rules for aggregates and aliases, then puts the old ones back
    private <T> T within(String noAggregates, boolean aliases, java.util.function.Supplier<T> parse) {
        String savedAggregates = noAggregatesIn;
        boolean savedAliases = aliasesAllowed;
        noAggregatesIn = noAggregates;
        aliasesAllowed = aliases;
        try {
            return parse.get();
        } finally {
            noAggregatesIn = savedAggregates;
            aliasesAllowed = savedAliases;
        }
    }

    private SelectItem parseExpr() {
        SelectItem left = parseTerm();
        while (peek().isSymbol("+") || peek().isSymbol("-")) {
            Arithmetic.Operator operator = Arithmetic.Operator.fromSymbol(peek().text());
            index++;
            left = new Arithmetic(left, operator, parseTerm());
        }
        return left;
    }

    private SelectItem parseTerm() {
        SelectItem left = parseUnary();
        while (peek().isSymbol("*") || peek().isSymbol("/") || peek().isSymbol("%")) {
            Arithmetic.Operator operator = Arithmetic.Operator.fromSymbol(peek().text());
            index++;
            left = new Arithmetic(left, operator, parseUnary());
        }
        return left;
    }

    private SelectItem parseUnary() {
        if (peek().isSymbol("-")) {
            // -5 is a number, so -2147483648 still fits INTEGER
            if (tokens.get(index + 1).type() == TokenType.NUMBER) return new Literal(parseLiteral());
            index++;
            return new Negate(parseUnary());
        }
        return parsePrimary();
    }

    private SelectItem parsePrimary() {
        Token token = peek();
        if (token.isSymbol("(") && tokens.get(index + 1).isKeyword("SELECT")) {
            index++;
            SelectQuery query = parseOneColumnQuery("A subquery used as a value");
            expectSymbol(")");
            return new Subquery(query);
        }
        if (acceptSymbol("(")) {
            SelectItem inner = parseExpr();
            expectSymbol(")");
            return inner;
        }
        if (acceptKeyword("CASE")) return parseCase();
        if (token.type() == TokenType.STRING || token.type() == TokenType.NUMBER
                || token.isKeyword("TRUE") || token.isKeyword("FALSE") || token.isKeyword("NULL")) {
            return new Literal(parseLiteral());
        }
        if (token.type() == TokenType.IDENTIFIER && tokens.get(index + 1).isSymbol("(")) return parseCall();
        if (token.type() == TokenType.IDENTIFIER) {
            ColumnRef column = parseColumnRef();
            return aliasesAllowed ? withAlias(column) : column;
        }
        if (token.type() == TokenType.KEYWORD) {
            throw new IllegalArgumentException("Expected a value, got keyword " + token.describe()
                    + " (use `" + token.text().toLowerCase() + "` to use it as a name)");
        }
        throw expected("a value");
    }

    // An aggregate like SUM(price * qty), or a function like UPPER(name)
    private SelectItem parseCall() {
        Token name = peek();
        Aggregate.Function aggregate = Arrays.stream(Aggregate.Function.values())
                .filter(f -> f.name().equalsIgnoreCase(name.text())).findFirst().orElse(null);
        FunctionCall.Function function = FunctionCall.Function.named(name.text());
        if (aggregate == null && function == null) {
            throw new IllegalArgumentException("Unknown function " + name.text() + ", expected an aggregate (COUNT, SUM, AVG, MIN, MAX) or one of "
                    + Arrays.stream(FunctionCall.Function.values()).map(Enum::name).collect(java.util.stream.Collectors.joining(", ")));
        }
        index += 2;
        if (aggregate != null) {
            if (noAggregatesIn != null) {
                throw new IllegalArgumentException(name.text() + "(...) can't be used in " + noAggregatesIn
                        + (noAggregatesIn.equals("WHERE or ON") ? ", use HAVING to filter on an aggregate" : ""));
            }
            if (aggregate == Aggregate.Function.COUNT && acceptSymbol("*")) {
                expectSymbol(")");
                return Aggregate.countAll();
            }
            boolean distinct = acceptKeyword("DISTINCT");
            SelectItem argument = within("another aggregate", false, this::parseExpr);
            expectSymbol(")");
            return new Aggregate(aggregate, argument, distinct);
        }
        List<SelectItem> args = new ArrayList<>();
        if (!acceptSymbol(")")) {
            do {
                args.add(parseExpr());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        return new FunctionCall(function, args);
    }

    // After CASE: CASE WHEN condition THEN ..., or CASE x WHEN value THEN ..., which means WHEN x = value
    private SelectItem parseCase() {
        SelectItem subject = peek().isKeyword("WHEN") ? null : parseExpr();
        List<Expression> conditions = new ArrayList<>();
        List<SelectItem> results = new ArrayList<>();
        if (!peek().isKeyword("WHEN")) throw expected("WHEN");
        while (acceptKeyword("WHEN")) {
            conditions.add(subject == null ? parseOr() : new Condition(subject, Condition.Operator.EQUALS, constant(parseExpr())));
            expectKeyword("THEN");
            results.add(parseExpr());
        }
        SelectItem otherwise = acceptKeyword("ELSE") ? parseExpr() : null;
        expectWord("END");
        return new CaseWhen(conditions, results, otherwise);
    }

    // A constant expression is worked out now, so it's a plain value: id = 2 + 3 is id = 5, which can use an index.
    // One with a subquery isn't constant: the subquery runs when the query does.
    private static Object constant(SelectItem item) {
        return SelectItem.isConstant(item) && !hasSubquery(item) ? item.evaluate(new Row(Map.of())) : item;
    }

    private static boolean hasSubquery(SelectItem item) {
        boolean[] found = {false};
        item.rewrite(new Rewriter() {
            public SelectItem column(ColumnRef column) {
                return column;
            }

            public SelectQuery query(SelectQuery query) {
                found[0] = true;
                return query;
            }
        });
        return found[0];
    }

    // A SELECT whose single column is used as a value (by IN, or as a value itself)
    private SelectQuery parseOneColumnQuery(String what) {
        SelectQuery query = parseSelectQuery();
        if (query.columns().size() != 1 || (query.columns().get(0) instanceof ColumnRef column && column.isStar())) {
            throw new IllegalArgumentException(what + " must select one column, got "
                    + (query.columns().isEmpty() ? "*" : String.valueOf(query.columns().size())));
        }
        return query;
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
        return new Join(type, table, within("WHERE or ON", false, this::parseOr));
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
                values.add(parseInsertValue());
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
            values.put(column, acceptWord("DEFAULT") ? Table.DEFAULT : constant(within("SET", false, this::parseExpr)));
        } while (acceptSymbol(","));
        return new UpdateStatement(table, values, parseWhere());
    }

    // A value for a new row: DEFAULT, or an expression that doesn't use columns (2 * 3, UPPER('a'))
    private Object parseInsertValue() {
        if (acceptWord("DEFAULT")) return Table.DEFAULT;
        // A lone bare word was most likely meant as text, so say that rather than "VALUES can't use columns"
        Token after = tokens.get(index + 1);
        if (peek().type() == TokenType.IDENTIFIER && (after.isSymbol(",") || after.isSymbol(")"))) return parseLiteral();
        SelectItem value = within("VALUES", false, this::parseExpr);
        if (hasSubquery(value)) throw new IllegalArgumentException("VALUES can't use subqueries, got " + value);
        if (!SelectItem.isConstant(value)) throw new IllegalArgumentException("VALUES can't use columns, got " + value);
        return constant(value);
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
            expectKeyword("EXISTS");
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
                expectKeyword("EXISTS");
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
        return within("WHERE or ON", false, this::parseOr);
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
        if (peek().isSymbol("(")) {
            // ( starts a value, (price + 1) * 2 > 3, or a group of conditions, (a = 1 OR b = 2). The value
            // is tried first; for a group it fails at the first comparison inside, then the group is read.
            int start = index;
            try {
                return parseCondition();
            } catch (IllegalArgumentException notAValue) {
                index = start + 1;
                Expression inner = parseOr();
                expectSymbol(")");
                return inner;
            }
        }
        return parseCondition();
    }

    private Expression parseCondition() {
        if (acceptKeyword("EXISTS")) {
            expectSymbol("(");
            SelectQuery query = parseSelectQuery();
            expectSymbol(")");
            return new Exists(query);
        }
        SelectItem column = parseExpr();
        if (acceptKeyword("IS")) {
            boolean negated = acceptKeyword("NOT");
            expectKeyword("NULL");
            return new IsNull(column, negated);
        }
        boolean negated = acceptKeyword("NOT");
        if (acceptKeyword("IN")) {
            expectSymbol("(");
            if (peek().isKeyword("SELECT")) {
                SelectQuery query = parseOneColumnQuery("A subquery after IN");
                expectSymbol(")");
                return new InSubquery(column, query, negated);
            }
            // x IN (a, b) means x = a OR x = b, which also gives SQL's answer for NULLs in the list
            List<Expression> options = new ArrayList<>();
            do {
                options.add(new Condition(column, Condition.Operator.EQUALS, constant(parseExpr())));
            } while (acceptSymbol(","));
            expectSymbol(")");
            Expression in = options.size() == 1 ? options.get(0) : new Or(options);
            return negated ? new Not(in) : in;
        }
        if (acceptKeyword("BETWEEN")) {
            // x BETWEEN a AND b means x >= a AND x <= b, so a range index can answer it
            Object low = constant(parseExpr());
            expectKeyword("AND");
            Object high = constant(parseExpr());
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
        // A name on the right compares two columns: u.id = o.user_id
        return new Condition(column, operator, constant(parseExpr()));
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
