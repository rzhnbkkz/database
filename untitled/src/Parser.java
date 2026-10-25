import java.util.*;

public class Parser {
    String command;

    public static Statement parse(String input) {
        String[] tokens = input.trim().split("\\s+");
        if(tokens.length < 2) throw new IllegalArgumentException("Command is not recognized, try again");
        switch (tokens[0].toUpperCase()) {
            case "SELECT":
                return parseSelect(tokens);
            case "INSERT":
                return parseInsert(tokens);
            default:
                throw new IllegalArgumentException("Command " + tokens[0] + " is unrecognized");
        }
    }

    private static Statement parseSelect(String[] command) {
        String tableName = command[1];
        if (command.length > 2 && command[2].equalsIgnoreCase("WHERE")) {
            if (command.length < 4) throw new IllegalArgumentException("Expected a condition after WHERE");
            Map.Entry<String, Object> condition = parseAssignment(command[3]);
            return new SelectStatement(
                    tableName,
                    new Condition(condition.getKey(), condition.getValue())
            );
        }
        return new SelectStatement(tableName);
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

    private static Map.Entry<String, Object> parseAssignment(String token) {
        String[] pair = token.split("=", 2);

        if (pair.length != 2) {
            throw new IllegalArgumentException("Expected key=value, got: " + token);
        }

        return Map.entry(pair[0], parseValue(pair[1]));
    }

    private static Object parseValue(String value) {
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
