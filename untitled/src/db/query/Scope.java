package db.query;

import db.model.Column;
import db.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// The tables a query can see, by alias, used to check column names and turn them into row keys.
// With one table, rows are keyed by plain column name; in a join, by alias.column.
public class Scope {
    private final boolean qualified;
    private final Map<String, Table> tables = new LinkedHashMap<>();
    // Where subqueries in this query run; null when there's no engine (then subqueries can't be used)
    private final QueryEngine engine;

    public Scope(boolean qualified, QueryEngine engine) {
        this.qualified = qualified;
        this.engine = engine;
    }

    public Scope(boolean qualified) {
        this(qualified, null);
    }

    // UPDATE and DELETE: one table, also reachable by its own name (users.id)
    public static Scope of(Table table, QueryEngine engine) {
        Scope scope = new Scope(false, engine);
        scope.add(table.getName(), table);
        return scope;
    }

    public static Scope of(Table table) {
        return of(table, null);
    }

    // The tables a query's FROM and JOINs name, for looking at its column names from outside
    static Scope forQuery(SelectQuery query, QueryEngine engine) {
        Scope scope = new Scope(!query.joins().isEmpty(), engine);
        scope.add(query.from().alias(), engine.requireTable(query.from().table()));
        for (Join join : query.joins()) scope.add(join.table().alias(), engine.requireTable(join.table().table()));
        return scope;
    }

    public QueryEngine engine() {
        if (engine == null) throw new IllegalArgumentException("Subqueries can't be used here");
        return engine;
    }

    // Whether a column name means something here (it may still be ambiguous), without failing like resolve
    public boolean knows(ColumnRef ref) {
        if (ref.table() == null) return hasColumn(ref.column());
        Table table = tables.get(ref.table());
        return table != null && (ref.isStar() || hasColumn(table, ref.column()));
    }

    public void add(String alias, Table table) {
        if (tables.containsKey(alias)) {
            throw new IllegalArgumentException("Table name '" + alias + "' is used twice, give one an alias");
        }
        tables.put(alias, table);
    }

    public boolean isQualified() {
        return qualified;
    }

    public boolean hasColumn(String column) {
        return tables.values().stream().anyMatch(table -> hasColumn(table, column));
    }

    // Checks the column exists and is not ambiguous; the result's key() is what rows are keyed by
    public ColumnRef resolve(ColumnRef ref) {
        return key(owner(ref), ref.column());
    }

    // The column a reference names, to look at its type
    public Column column(ColumnRef ref) {
        return tables.get(owner(ref)).getColumns().stream()
                .filter(col -> col.getName().equals(ref.column())).findFirst().orElseThrow();
    }

    // Alias of the one table that has this column
    private String owner(ColumnRef ref) {
        if (ref.table() != null) {
            Table table = tables.get(ref.table());
            if (table == null) throw new IllegalArgumentException("Unknown table '" + ref.table() + "' in " + ref);
            if (!hasColumn(table, ref.column())) throw new IllegalArgumentException("Unknown column '" + ref + "'");
            return ref.table();
        }
        List<String> owners = new ArrayList<>();
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            if (hasColumn(entry.getValue(), ref.column())) owners.add(entry.getKey());
        }
        if (owners.isEmpty()) throw new IllegalArgumentException("Unknown column '" + ref.column() + "'");
        if (owners.size() > 1) {
            throw new IllegalArgumentException("Column '" + ref.column() + "' is ambiguous, write "
                    + owners.stream().map(owner -> owner + "." + ref.column()).collect(Collectors.joining(" or ")));
        }
        return owners.get(0);
    }

    // Every column of the table with this alias, or of all tables when alias is null, in order
    public List<ColumnRef> columns(String alias) {
        if (alias != null && !tables.containsKey(alias)) {
            throw new IllegalArgumentException("Unknown table '" + alias + "' in " + alias + ".*");
        }
        List<ColumnRef> result = new ArrayList<>();
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            if (alias != null && !entry.getKey().equals(alias)) continue;
            for (Column column : entry.getValue().getColumns()) result.add(key(entry.getKey(), column.getName()));
        }
        return result;
    }

    private ColumnRef key(String alias, String column) {
        return qualified ? new ColumnRef(alias, column) : new ColumnRef(column);
    }

    private static boolean hasColumn(Table table, String column) {
        return table.getColumns().stream().anyMatch(col -> col.getName().equals(column));
    }
}
