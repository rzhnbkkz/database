package db.parser;

// position is the 1-based character where the token starts, used in error messages
public record Token(TokenType type, String text, int position) {

    public boolean is(TokenType type, String text) {
        return this.type == type && this.text.equals(text);
    }

    public boolean isKeyword(String keyword) {
        return is(TokenType.KEYWORD, keyword);
    }

    public boolean isSymbol(String symbol) {
        return is(TokenType.SYMBOL, symbol);
    }

    // How the token appears in error messages, e.g. 'users' at position 15
    public String describe() {
        return switch (type) {
            case END -> "end of input";
            case STRING -> "text '" + text + "' at position " + position;
            default -> "'" + text + "' at position " + position;
        };
    }
}
