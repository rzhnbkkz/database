package db.statement;

import db.model.Row;

import java.util.List;

// Rows drawn as a table like the mysql client's: empty values show as NULL and numbers are right-aligned
public class ResultTable {
    private ResultTable() {}

    public static String format(List<String> columns, List<Row> rows) {
        if (rows.isEmpty()) return "Empty set";
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
            for (Row row : rows) widths[i] = Math.max(widths[i], text(row.get(columns.get(i))).length());
        }
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) border.append("-".repeat(width + 2)).append('+');

        StringBuilder sb = new StringBuilder().append(border).append("\n|");
        for (int i = 0; i < columns.size(); i++) sb.append(' ').append(pad(columns.get(i), widths[i], false)).append(" |");
        sb.append('\n').append(border).append('\n');
        for (Row row : rows) {
            sb.append('|');
            for (int i = 0; i < columns.size(); i++) {
                Object value = row.get(columns.get(i));
                sb.append(' ').append(pad(text(value), widths[i], value instanceof Number)).append(" |");
            }
            sb.append('\n');
        }
        sb.append(border).append('\n');
        return sb.append(rows.size()).append(rows.size() == 1 ? " row in set" : " rows in set").toString();
    }

    private static String pad(String text, int width, boolean right) {
        String padding = " ".repeat(width - text.length());
        return right ? padding + text : text + padding;
    }

    private static String text(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        return value.toString();
    }
}
