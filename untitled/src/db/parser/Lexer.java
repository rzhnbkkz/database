package db.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Turns a command line into tokens; the last token is always END
public class Lexer {
    // Words that can't be used as table or column names unless written in `backticks`.
    // SAVE and LOAD are left out because they only ever start a command.
    static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "AND", "OR", "NOT",
            "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "PRIMARY", "KEY",
            "JOIN", "INNER", "LEFT", "OUTER", "ON", "AS", "ORDER", "BY", "LIMIT", "GROUP",
            "TRUE", "FALSE", "NULL");

    private final String input;
    private int pos = 0;
    private final List<Token> tokens = new ArrayList<>();

    private Lexer(String input) {
        this.input = input;
    }

    public static List<Token> tokenize(String input) {
        return new Lexer(input).run();
    }

    private List<Token> run() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) pos++;
            else if (Character.isLetter(c) || c == '_') readWord();
            else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) readNumber();
            else if (c == '\'' || c == '"') readString(c);
            else if (c == '`') readQuotedIdentifier();
            else readSymbol();
        }
        tokens.add(new Token(TokenType.END, "", pos + 1));
        return tokens;
    }

    private char peek(int offset) {
        int i = pos + offset;
        return i < input.length() ? input.charAt(i) : '\0';
    }

    private void readWord() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) pos++;
        String word = input.substring(start, pos);
        String upper = word.toUpperCase();
        if (KEYWORDS.contains(upper)) tokens.add(new Token(TokenType.KEYWORD, upper, start + 1));
        else tokens.add(new Token(TokenType.IDENTIFIER, word, start + 1));
    }

    private void readNumber() {
        int start = pos;
        while (Character.isDigit(peek(0))) pos++;
        if (peek(0) == '.') {
            pos++;
            while (Character.isDigit(peek(0))) pos++;
        }
        tokens.add(new Token(TokenType.NUMBER, input.substring(start, pos), start + 1));
    }

    // Inside 'text' or "text": \' \" \\ and a doubled quote ('') stand for one character;
    // any other backslash is kept as is, so paths like C:\Users work
    private void readString(char quote) {
        int start = pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '\\' && (peek(1) == quote || peek(1) == '\\')) {
                sb.append(peek(1));
                pos += 2;
            } else if (c == quote && peek(1) == quote) {
                sb.append(quote);
                pos += 2;
            } else if (c == quote) {
                pos++;
                tokens.add(new Token(TokenType.STRING, sb.toString(), start + 1));
                return;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new IllegalArgumentException("Missing closing quote for text starting at position " + (start + 1));
    }

    // `name` lets a keyword or a name with spaces be used as a table or column name
    private void readQuotedIdentifier() {
        int start = pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '`' && peek(1) == '`') {
                sb.append('`');
                pos += 2;
            } else if (c == '`') {
                pos++;
                if (sb.isEmpty()) throw new IllegalArgumentException("Empty name in backticks at position " + (start + 1));
                tokens.add(new Token(TokenType.IDENTIFIER, sb.toString(), start + 1));
                return;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new IllegalArgumentException("Missing closing backtick for name starting at position " + (start + 1));
    }

    private void readSymbol() {
        int start = pos;
        String two = input.substring(pos, Math.min(pos + 2, input.length()));
        String symbol;
        if (two.equals("!=") || two.equals("<>") || two.equals("<=") || two.equals(">=")) symbol = two;
        else if ("(),;*-=<>.".indexOf(input.charAt(pos)) >= 0) symbol = String.valueOf(input.charAt(pos));
        else throw new IllegalArgumentException("Unexpected character '" + input.charAt(pos) + "' at position " + (start + 1));
        pos += symbol.length();
        tokens.add(new Token(TokenType.SYMBOL, symbol, start + 1));
    }
}
