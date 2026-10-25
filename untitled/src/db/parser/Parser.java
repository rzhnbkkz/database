package db.parser;

import db.model.Column;
import db.model.DataType;
import db.query.And;
import db.query.Condition;
import db.query.Expression;
import db.query.Not;
import db.query.Or;
import db.statement.CreateStatement;
import db.statement.DeleteStatement;
import db.statement.InsertStatement;
import db.statement.LoadStatement;
import db.statement.SaveStatement;
import db.statement.SelectStatement;
import db.statement.Statement;
import db.statement.UpdateStatement;

import java.util.*;

// Recursive descent parser: one method per grammar rule, each consuming the tokens it recognises.
//
//   statement := (select | insert | update | delete | create | save | load) [';']
//   select    := SELECT ('*' | name {',' name}) FROM name [where]
//   insert    := INSERT INTO name ['(' name {',' name} ')'] VALUES '(' literal {',' literal} ')'
//   update    := UPDATE name SET name '=' literal {',' name '=' literal} [where]
//   delete    := DELETE FROM name [where]
//   create    := CREATE TABLE name '(' columnDef {',' columnDef} ')'
//   columnDef := name type ['(' NUMBER ')'] [PRIMARY KEY]
//   save      := SAVE name STRING
//   load      := LOAD STRING
//   where     := WHERE or
//   or        := and {OR and}             OR binds loosest, so a AND b OR c is (a AND b) OR c
//   and       := not {AND not}
//   not       := NOT not | '(' or ')' | name op literal
//   literal   := STRING | ['-'] NUMBER | TRUE | FALSE | NULL
public class Parser {
    private final List<Token> tokens;
    private int index = 0;

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
        List<String> columns = new ArrayList<>();
        if (!acceptSymbol("*")) columns = parseNameList("column name");
        expectKeyword("FROM");
        String table = expectName("table name");
        return new SelectStatement(table, columns, parseWhere());
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
        expectKeyword("TABLE");
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
        String column = expectName("column name");
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
        return new Condition(column, operator, parseLiteral());
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
