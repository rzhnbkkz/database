package db.parser;

public enum TokenType {
    KEYWORD,    // reserved word such as SELECT or FROM, stored in upper case
    IDENTIFIER, // table or column name, as written (or inside `backticks`)
    STRING,     // 'text' or "text", stored without quotes and with escapes resolved
    NUMBER,     // 42 or 1.5, without sign; the parser decides INTEGER or FLOAT
    SYMBOL,     // ( ) , ; * - and the operators = != <> < > <= >=
    END         // end of input
}
