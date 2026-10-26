package db.storage;

import db.model.Column;
import db.model.Index;
import db.model.Row;
import db.model.Table;

import java.util.*;
import java.util.stream.Collectors;
import java.io.FileWriter;
import java.io.IOException;

public class TableSerializer {

    public static String toJSON(Table table) {
        return toJSON(table, false);
    }

    // On one line, for a log record. Every newline in toJSON, and the indent after it, is formatting:
    // quote escapes the newlines in text.
    static String toCompactJSON(Table table) {
        return toJSON(table, true).replaceAll("\n *", "");
    }

    // withRowIds also writes each row's internal id ("rowIds", in row order), which a data folder's snapshot
    // needs because its log records name rows by id. Files for SAVE don't, and get new ids when loaded.
    static String toJSON(Table table, boolean withRowIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(quote(table.getName())).append(",\n");

        sb.append("  \"columns\": [");
        List<Column> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    ").append(columnToJSON(columns.get(i)));
        }
        sb.append(columns.isEmpty() ? "],\n" : "\n  ],\n");

        // Saved so numbers of deleted rows aren't given out again after LOAD
        if (columns.stream().anyMatch(Column::isAutoIncrement)) {
            sb.append("  \"nextAutoIncrement\": ").append(table.getNextAutoIncrement()).append(",\n");
        }

        // The PRIMARY index comes back with the primary key, so only the others are written
        List<Index> indexes = table.getIndexes().stream().filter(i -> !i.getName().equals(Table.PRIMARY)).toList();
        if (!indexes.isEmpty()) {
            sb.append("  \"indexes\": [");
            for (int i = 0; i < indexes.size(); i++) {
                sb.append(i == 0 ? "\n" : ",\n");
                Index index = indexes.get(i);
                sb.append("    {\"name\": ").append(quote(index.getName())).append(", \"columns\": [")
                  .append(index.getColumns().stream().map(TableSerializer::quote).collect(Collectors.joining(", "))).append("]");
                if (index.isUnique()) sb.append(", \"unique\": true");
                sb.append("}");
            }
            sb.append("\n  ],\n");
        }

        List<Row> rows = table.getRows();
        if (withRowIds) {
            sb.append("  \"rowIds\": [")
              .append(table.getRowsById().keySet().stream().map(String::valueOf).collect(Collectors.joining(", "))).append("],\n");
        }
        sb.append("  \"rows\": [");
        for (int i = 0; i < rows.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {");
            // Write values in column order so every row has the same shape
            for (int j = 0; j < columns.size(); j++) {
                String colName = columns.get(j).getName();
                if (j > 0) sb.append(", ");
                sb.append(quote(colName)).append(": ").append(valueToJSON(rows.get(i).get(colName)));
            }
            sb.append("}");
        }
        sb.append(rows.isEmpty() ? "]\n" : "\n  ]\n");

        sb.append("}\n");
        return sb.toString();
    }

    static String columnToJSON(Column col) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"name\": ").append(quote(col.getName()))
          .append(", \"type\": ").append(quote(col.getDataType().name()));
        if (col.isPrimaryKey()) sb.append(", \"primaryKey\": true");
        if (col.isNotNull()) sb.append(", \"notNull\": true");
        if (col.getDefaultValue() != null) sb.append(", \"default\": ").append(valueToJSON(col.getDefaultValue()));
        if (col.isAutoIncrement()) sb.append(", \"autoIncrement\": true");
        return sb.append("}").toString();
    }

    // A row's cells as {"column": value}; empty cells are left out
    static String rowToJSON(Row row) {
        return row.getData().entrySet().stream()
                .map(entry -> quote(entry.getKey()) + ": " + valueToJSON(entry.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    static String valueToJSON(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return quote(s);
        if (value instanceof Float f) {
            // JSON has no NaN/Infinity
            if (f.isNaN() || f.isInfinite()) return "null";
            return f.toString();
        }
        // Integer, Boolean
        return value.toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
    }

    public static void saveToFile(Table table, String path) throws IOException {
        try (FileWriter fileWriter = new FileWriter(path)){
            fileWriter.write(toJSON(table));
        }
    }

}
