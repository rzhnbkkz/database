package db.statement;

import db.model.Table;
import db.query.ColumnRef;
import db.query.Expression;
import db.query.QueryEngine;
import db.query.Scope;
import db.query.SelectItem;

import java.util.LinkedHashMap;
import java.util.Map;

public class UpdateStatement implements Statement {
    private String tableName;
    // A value is a literal, Table.DEFAULT, or an expression worked out for each row (n + 1)
    private Map<String, Object> values;
    private Expression where;

    public UpdateStatement(String tableName, Map<String, Object> values, Expression where) {
        this.tableName = tableName;
        this.values = values;
        this.where = where;
    }

    public String getTableName() {
        return tableName;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public Expression getWhere() {
        return where;
    }

    // As in standard SQL, every expression sees the row as it was before the update: SET a = b, b = a swaps them
    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        Scope scope = Scope.of(table, query);
        Map<String, Object> resolved = new LinkedHashMap<>();
        boolean perRow = false;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (table.getColumns().stream().noneMatch(column -> column.getName().equals(entry.getKey()))) {
                throw new IllegalArgumentException("Unknown column '" + entry.getKey() + "'");
            }
            Object value = entry.getValue();
            if (value instanceof SelectItem item) {
                // A bare word was most likely meant as text
                if (item instanceof ColumnRef ref && ref.table() == null && !scope.hasColumn(ref.column())) {
                    throw new IllegalArgumentException("Unknown column '" + ref.column() + "' (to set text, put it in quotes: '"
                            + ref.column() + "')");
                }
                value = item.resolve(scope);
                perRow = true;
            }
            resolved.put(entry.getKey(), value);
        }
        Expression filter = where.resolve(scope);
        int count = perRow
                ? QueryEngine.update(table, row -> {
                    Map<String, Object> rowValues = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : resolved.entrySet()) {
                        rowValues.put(entry.getKey(), entry.getValue() instanceof SelectItem item ? item.evaluate(row) : entry.getValue());
                    }
                    return rowValues;
                }, filter)
                : QueryEngine.update(table, resolved, filter);
        System.out.println(count + (count == 1 ? " row" : " rows") + " updated");
    }
}
