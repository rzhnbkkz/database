package db.query;

import db.model.Row;

import java.util.List;
import java.util.regex.Pattern;

// column [NOT] LIKE 'pattern': % matches any run of characters, _ exactly one, and \ makes the next
// character literal (\% is a percent sign). Case-sensitive, like every text comparison here.
// Numbers are matched as text, as in MySQL (42 LIKE '4%').
public final class Like implements Expression {
    private final SelectItem item;
    private final String pattern;
    private final boolean negated;
    private final Pattern regex;

    public Like(SelectItem item, String pattern, boolean negated) {
        this.item = item;
        this.pattern = pattern;
        this.negated = negated;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) sb.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            else if (c == '%') sb.append(".*");
            else if (c == '_') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        this.regex = Pattern.compile(sb.toString(), Pattern.DOTALL);
    }

    public SelectItem item() {
        return item;
    }

    public boolean negated() {
        return negated;
    }

    public Boolean evaluate(Row row) {
        Object value = item.evaluate(row);
        if (value == null) return null;
        return regex.matcher(value.toString()).matches() != negated;
    }

    public Expression resolve(Scope scope) {
        return new Like(item.resolve(scope), pattern, negated);
    }

    public List<SelectItem> items() {
        return List.of(item);
    }

    // The text every match starts with, up to the first wildcard: 'Al%' -> "Al", so an index can read a range
    public String literalPrefix() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '%' || c == '_') break;
            if (c == '\\' && i + 1 < pattern.length()) c = pattern.charAt(++i);
            sb.append(c);
        }
        return sb.toString();
    }

    public int precedence() {
        return 4;
    }

    @Override
    public String toString() {
        return item + (negated ? " NOT LIKE '" : " LIKE '") + pattern.replace("'", "''") + "'";
    }
}
