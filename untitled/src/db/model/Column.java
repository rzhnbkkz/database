package db.model;

public class Column {
    private final String name;
    private final DataType dataType;
    private final boolean primaryKey;

    public Column(String name, DataType dataType, boolean primaryKey) {
        this.name = name;
        this.dataType = dataType;
        this.primaryKey = primaryKey;
    }

    public Column(String name, DataType dataType) {
        this(name, dataType, false);
    }

    public String getName() {
        return name;
    }

    public DataType getDataType() {
        return dataType;
    }

    public boolean isPrimaryKey() {
        return primaryKey;
    }
}
