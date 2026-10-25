import java.util.*;
import java.io.FileWriter;
import java.io.IOException;

public class TableSerializer {

    public static String toJSON(Table table) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"name\": ").append(quote(table.getName())).append(",\n");

        sb.append("  \"columns\": [");
        List<Column> columns = table.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            sb.append(i == 0 ? "\n" : ",\n");
            sb.append("    {\"name\": ").append(quote(col.getName()))
              .append(", \"type\": ").append(quote(col.getDataType().name())).append("}");
        }
        sb.append(columns.isEmpty() ? "],\n" : "\n  ],\n");

        sb.append("  \"rows\": [");
        List<Row> rows = table.getRows();
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

    private static String valueToJSON(Object value) {
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

    private static String quote(String s) {
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
