import java.util.List;
import java.util.Map;

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

        new REPL().start(engine);
    }
}