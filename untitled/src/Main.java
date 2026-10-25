import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        QueryEngine engine = new QueryEngine();
        // Setup
        Table users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN)
        ));

        engine.addTable(users);

        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice", "active", true));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob",   "active", false));
        QueryEngine.insert(users, Map.of("id", 3, "name", "Carol", "active", true));

        // /Users/aruzhanybekkyzy/Desktop/users.json
        Scanner scanner = new Scanner(System.in);
        System.out.print("Path to save the table: ");
        String path = scanner.next();

        new REPL().start(engine);

        TableSerializer.saveToFile(users, path);
        System.out.println("\nFile successfully saved at " + path);
    }
}