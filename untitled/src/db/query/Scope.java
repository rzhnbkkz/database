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

    public Scope(boolean qualified) {
        this.qualified = qualified;
    }

    // UPDATE and DELETE: one table, also reachable by its own name (users.id)
    public static Scope of(Table table) {
        Scope scope = new Scope(false);
        scope.add(table.getName(), table);
        return scope;
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
        if (ref.table() != null) {
            Table table = tables.get(ref.table());
            if (table == null) throw new IllegalArgumentException("Unknown table '" + ref.table() + "' in " + ref);
            if (!hasColumn(table, ref.column())) throw new IllegalArgumentException("Unknown column '" + ref + "'");
            return key(ref.table(), ref.column());
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
        return key(owners.get(0), ref.column());
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
