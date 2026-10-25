import java.io.IOException;

public class LoadStatement implements Statement {
    private String path;

    public LoadStatement(String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    public void execute(QueryEngine query) {
        Table table;
        try {
            table = TableDeserializer.loadFromFile(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read " + path + ": " + e.getMessage());
        }
        if (query.getTable(table.getName()) != null) {
            throw new IllegalArgumentException("Table '" + table.getName() + "' already exists");
        }
        query.addTable(table);
        int count = table.getRows().size();
        System.out.println("Table '" + table.getName() + "' loaded with " + count + (count == 1 ? " row" : " rows"));
    }
}
