import java.util.*;

public class Parser {
    String command;

    public static Statement parse(String input) {
        String[] command = input.split(" ");
        String tableName = command[1];
        if(command[0].equalsIgnoreCase("SELECT")) {
            if(command.length > 2 && command[2].equalsIgnoreCase("WHERE")) {
                String[] condition = command[3].split("=");
                return new SelectStatement(tableName, new Condition(condition[0], parseValue(condition[1])));
            }
            return new SelectStatement(tableName);
        }
        else if(command[0].equalsIgnoreCase("INSERT")) {
            Map<String, Object> map = new HashMap<>();
            for(int i = 2; i < command.length; i++) {
                String[] objects = command[i].split("=");
                map.put(objects[0], parseValue(objects[1]));
            }
            Row row = new Row(map);
            return new InsertStatement(tableName, row);
        }
        throw new IllegalArgumentException("Command " + command[0] + " is unrecognized");
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
