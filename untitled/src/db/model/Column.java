package db.model;

public class Column {
    private final String name;
    private final DataType dataType;
    private final boolean primaryKey;
    // NOT NULL: the cell can never be empty (a primary key never can either)
    private final boolean notNull;
    // DEFAULT: the value an INSERT that leaves the column out gets; null when there is none
    private final Object defaultValue;
    // AUTO_INCREMENT: an INSERT without a value gets the next number
    private final boolean autoIncrement;

    public Column(String name, DataType dataType, boolean primaryKey, boolean notNull, Object defaultValue, boolean autoIncrement) {
        this.name = name;
        this.dataType = dataType;
        this.primaryKey = primaryKey;
        this.notNull = notNull;
        this.defaultValue = defaultValue;
        this.autoIncrement = autoIncrement;
    }

    public Column(String name, DataType dataType, boolean primaryKey) {
        this(name, dataType, primaryKey, false, null, false);
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

    public boolean isNotNull() {
        return notNull;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }

    // The same column under another name, for ALTER TABLE ... RENAME COLUMN
    Column renamed(String newName) {
        return new Column(newName, dataType, primaryKey, notNull, defaultValue, autoIncrement);
    }
}
