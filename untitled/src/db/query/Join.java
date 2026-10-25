package db.query;

// One JOIN clause. INNER keeps only combined rows that match ON; LEFT also keeps each row
// from the left side that matched nothing, with the joined table's columns left empty.
public record Join(Type type, TableRef table, Expression on) {
    public enum Type { INNER, LEFT }
}
