import java.util.*;

public class Parser {
    String command;

    public static Statement parse(String input) {
        String[] tokens = tokenize(input);
        if(tokens.length < 2) throw new IllegalArgumentException("Command is not recognized, try again");
        switch (tokens[0].toUpperCase()) {
            case "SELECT":
                return parseSelect(tokens);
            case "INSERT":
                return parseInsert(tokens);
            case "DELETE":
                return parseDelete(tokens);
            case "UPDATE":
                return parseUpdate(tokens);
            case "CREATE":
                return parseCreate(tokens);
            case "SAVE":
                return parseSave(tokens);
            case "LOAD":
                return parseLoad(tokens);
            default:
                throw new IllegalArgumentException("Command " + tokens[0] + " is unrecognized");
        }
    }

    // Splits on whitespace, except inside double quotes; quotes are kept so values can be recognised as text
    private static String[] tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inQuotes) {
                current.append(c);
                if (c == '\\' && i + 1 < input.length()) current.append(input.charAt(++i));
                else if (c == '"') inQuotes = false;
            } else if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                if (c == '"') inQuotes = true;
                current.append(c);
            }
        }
        if (inQuotes) throw new IllegalArgumentException("Missing closing quote");
        if (!current.isEmpty()) tokens.add(current.toString());
        return tokens.toArray(new String[0]);
    }

    // "text" -> text, with \" and \\ unescaped; anything without a leading quote is returned unchanged
    private static String unquote(String token) {
        if (!token.startsWith("\"")) return token;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '"') {
                if (i != token.length() - 1) throw new IllegalArgumentException("Unexpected text after closing quote: " + token);
                return sb.toString();
            }
            if (c == '\\' && i + 1 < token.length() && (token.charAt(i + 1) == '"' || token.charAt(i + 1) == '\\')) {
                c = token.charAt(++i);
            }
            sb.append(c);
        }
        throw new IllegalArgumentException("Missing closing quote: " + token);
    }

    private static Statement parseSelect(String[] command) {
        // SELECT <table> [<column>,<column>... | *] [WHERE ...]
        if (command.length < 3 || command[2].equalsIgnoreCase("WHERE")) {
            return new SelectStatement(command[1], parseWhere(command, 2));
        }
        return new SelectStatement(command[1], parseColumnList(command[2]), parseWhere(command, 3));
    }

    private static List<String> parseColumnList(String token) {
        if (token.equals("*")) return List.of();
        List<String> columns = new ArrayList<>();
        for (String column : token.split(",", -1)) {
            if (column.isEmpty()) throw new IllegalArgumentException("Expected column names separated by commas, got: " + token);
            columns.add(column);
        }
        return columns;
    }

    // DELETE <table> [WHERE <condition>]
    private static Statement parseDelete(String[] command) {
        return new DeleteStatement(command[1], parseWhere(command, 2));
    }

    // UPDATE <table> SET <column>=<value> ... [WHERE <condition>]
    private static Statement parseUpdate(String[] command) {
        if (command.length < 3 || !command[2].equalsIgnoreCase("SET")) {
            throw new IllegalArgumentException("Expected: UPDATE <table> SET <column>=<value> ... [WHERE <condition>]");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int i = 3;
        for (; i < command.length && !command[i].equalsIgnoreCase("WHERE"); i++) {
            Map.Entry<String, Object> assignment = parseAssignment(command[i]);
            values.put(assignment.getKey(), assignment.getValue());
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Expected at least one column=value after SET");
        return new UpdateStatement(command[1], values, parseWhere(command, i));
    }

    // Parses an optional "WHERE <condition> [AND <condition>] ..." starting at index; empty when absent
    private static List<Condition> parseWhere(String[] command, int index) {
        List<Condition> conditions = new ArrayList<>();
        if (index >= command.length) return conditions;
        if (!command[index].equalsIgnoreCase("WHERE")) {
            throw new IllegalArgumentException("Unexpected " + command[index] + ", expected WHERE");
        }
        String keyword = "WHERE";
        for (int i = index + 1; ; i += 2) {
            if (i >= command.length) throw new IllegalArgumentException("Expected a condition after " + keyword);
            conditions.add(parseCondition(command[i]));
            if (i + 1 >= command.length) return conditions;
            keyword = command[i + 1];
            if (!keyword.equalsIgnoreCase("AND")) {
                throw new IllegalArgumentException("Unexpected " + keyword + " after condition, expected AND");
            }
        }
    }

    // <column><op><value> where op is one of = != > < >= <=
    private static Condition parseCondition(String token) {
        int i = 0;
        while (i < token.length() && "=!<>".indexOf(token.charAt(i)) < 0) i++;
        if (i == 0 || i == token.length()) {
            throw new IllegalArgumentException("Expected a condition like column=value, got: " + token);
        }
        int end = i + 1;
        if (end < token.length() && token.charAt(end) == '=' && token.charAt(i) != '=') end++;
        String symbol = token.substring(i, end);
        if (symbol.equals("!")) throw new IllegalArgumentException("Expected != in condition, got: " + token);
        return new Condition(token.substring(0, i), Condition.Operator.fromSymbol(symbol), parseValue(token.substring(end)));
    }

    private static Statement parseInsert(String[] command) {
        String tableName = command[1];
        Map<String, Object> map = new HashMap<>();
        for (int i = 2; i < command.length; i++) {
            Map.Entry<String, Object> assignment = parseAssignment(command[i]);
            map.put(assignment.getKey(), assignment.getValue());
        }
        Row row = new Row(map);
        return new InsertStatement(tableName, row);
    }

    private static Statement parseCreate(String[] command) {
        String tableName = command[1];
        if (command.length < 3) throw new IllegalArgumentException("Expected at least one column, e.g. id:INTEGER");
        // CREATE <table> <column>:<type>[:PRIMARY] ...
        List<Column> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 2; i < command.length; i++) {
            String[] parts = command[i].split(":", 3);
            if (parts.length < 2 || parts[0].isEmpty()) {
                throw new IllegalArgumentException("Expected column:TYPE or column:TYPE:PRIMARY, got: " + command[i]);
            }
            boolean primaryKey = parts.length == 3;
            if (primaryKey && !parts[2].equalsIgnoreCase("PRIMARY")) {
                throw new IllegalArgumentException("Expected PRIMARY after the type, got: " + parts[2]);
            }
            if (!names.add(parts[0])) throw new IllegalArgumentException("Duplicate column: " + parts[0]);
            columns.add(new Column(parts[0], parseDataType(parts[1]), primaryKey));
        }
        return new CreateStatement(tableName, columns);
    }

    private static DataType parseDataType(String type) {
        try {
            return DataType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown type " + type + ", expected one of " + Arrays.toString(DataType.values()));
        }
    }

    // SAVE <table> <path>
    private static Statement parseSave(String[] command) {
        if (command.length != 3) throw new IllegalArgumentException("Expected: SAVE <table> <path>");
        return new SaveStatement(command[1], unquote(command[2]));
    }

    // LOAD <path>
    private static Statement parseLoad(String[] command) {
        if (command.length != 2) throw new IllegalArgumentException("Expected: LOAD <path>");
        return new LoadStatement(unquote(command[1]));
    }

    private static Map.Entry<String, Object> parseAssignment(String token) {
        String[] pair = token.split("=", 2);

        if (pair.length != 2) {
            throw new IllegalArgumentException("Expected key=value, got: " + token);
        }

        return Map.entry(pair[0], parseValue(pair[1]));
    }

    private static Object parseValue(String value) {
        if (value.startsWith("\"")) return unquote(value);

        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e) {}

        try {
            return Float.parseFloat(value);
        }
        catch(NumberFormatException e) {}

        if(value.equalsIgnoreCase("true")) return true;
        if(value.equalsIgnoreCase("false")) return false;

        return value;
    }
}
