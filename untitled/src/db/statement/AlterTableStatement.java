package db.statement;

import db.model.Column;
import db.model.Table;
import db.query.QueryEngine;

import java.util.List;

// ALTER TABLE with one change: ADD COLUMN, DROP COLUMN, RENAME COLUMN a TO b, or RENAME TO new_name
public class AlterTableStatement implements Statement {
    public enum Action { ADD_COLUMN, DROP_COLUMN, RENAME_COLUMN, RENAME_TABLE }

    private String tableName;
    private Action action;
    // ADD_COLUMN: the new column
    private Column column;
    // DROP_COLUMN and RENAME_COLUMN: the column; RENAME_COLUMN and RENAME_TABLE: the new name
    private String name;
    private String newName;

    private AlterTableStatement(String tableName, Action action, Column column, String name, String newName) {
        this.tableName = tableName;
        this.action = action;
        this.column = column;
        this.name = name;
        this.newName = newName;
    }

    public static AlterTableStatement addColumn(String tableName, Column column) {
        return new AlterTableStatement(tableName, Action.ADD_COLUMN, column, null, null);
    }

    public static AlterTableStatement dropColumn(String tableName, String column) {
        return new AlterTableStatement(tableName, Action.DROP_COLUMN, null, column, null);
    }

    public static AlterTableStatement renameColumn(String tableName, String from, String to) {
        return new AlterTableStatement(tableName, Action.RENAME_COLUMN, null, from, to);
    }

    public static AlterTableStatement renameTable(String tableName, String newName) {
        return new AlterTableStatement(tableName, Action.RENAME_TABLE, null, null, newName);
    }

    public String getTableName() {
        return tableName;
    }

    public Action getAction() {
        return action;
    }

    public Column getColumn() {
        return column;
    }

    public String getName() {
        return name;
    }

    public String getNewName() {
        return newName;
    }

    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        switch (action) {
            case ADD_COLUMN -> {
                table.addColumn(column);
                System.out.println("Column '" + column.getName() + "' added to " + tableName);
            }
            case DROP_COLUMN -> {
                List<String> droppedIndexes = table.dropColumn(name);
                System.out.println("Column '" + name + "' dropped from " + tableName
                        + (droppedIndexes.isEmpty() ? "" : ", along with " + (droppedIndexes.size() == 1 ? "index " : "indexes ")
                        + String.join(", ", droppedIndexes)));
            }
            case RENAME_COLUMN -> {
                table.renameColumn(name, newName);
                System.out.println("Column '" + name + "' renamed to '" + newName + "'");
            }
            case RENAME_TABLE -> {
                query.renameTable(tableName, newName);
                System.out.println("Table '" + tableName + "' renamed to '" + newName + "'");
            }
        }
    }
}
