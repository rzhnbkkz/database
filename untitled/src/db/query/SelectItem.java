package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.ArrayList;
import java.util.List;

// A value expression: a column, a literal, an aggregate, arithmetic, a function call or a CASE.
// Select lists, conditions, ORDER BY, GROUP BY and UPDATE ... SET are all made of these.
public sealed interface SelectItem permits ColumnRef, Aggregate, Literal, Arithmetic, Negate, FunctionCall, CaseWhen, Subquery {
    // Once resolved, the key its value is stored under in a row: u.id for a column, COUNT(*) for an
    // aggregate in a group row, and its text (UPPER(city)) for a GROUP BY expression in a group row
    String key();

    // Checks every column against the scope and turns it into its row key
    SelectItem resolve(Scope scope);

    // The same expression rebuilt with its columns and subqueries passed through the rewriter
    SelectItem rewrite(Rewriter rewriter);

    // The value for one row; null is empty (SQL NULL)
    Object evaluate(Row row);

    // The type of the values it gives, or null when unknown (a NULL literal). Used once resolved, to report
    // mistakes like name * 2 before any row is read.
    DataType type(Scope scope);

    // The expressions it's built from. An aggregate's argument isn't one: it's computed from the rows of a
    // group, not from the group row.
    List<SelectItem> children();

    // Columns and aggregates it uses, outside of aggregates: what GROUP BY checks and what grouping computes
    static List<SelectItem> leaves(SelectItem item) {
        if (item instanceof ColumnRef || item instanceof Aggregate) return List.of(item);
        List<SelectItem> leaves = new ArrayList<>();
        for (SelectItem child : item.children()) leaves.addAll(leaves(child));
        return leaves;
    }

    // Whether it gives the same value for every row, so it can be worked out once while parsing
    static boolean isConstant(SelectItem item) {
        return leaves(item).isEmpty();
    }

    // A group row may already hold an expression's value, when it's a GROUP BY expression
    static boolean stored(SelectItem item, Row row) {
        return row.getData().containsKey(item.key());
    }

    static boolean isNumber(DataType type) {
        return type == null || type == DataType.INTEGER || type == DataType.FLOAT;
    }

    // The type of a number expression from its operands: FLOAT if either is, else INTEGER
    static DataType numberType(DataType a, DataType b) {
        return a == DataType.FLOAT || b == DataType.FLOAT ? DataType.FLOAT : DataType.INTEGER;
    }

    // A whole-number result: INTEGER when it fits, otherwise the exact larger number, like SUM
    static Object whole(long value) {
        return value == (int) value ? (Object) (int) value : (Object) value;
    }

    static boolean isWhole(Object value) {
        return value instanceof Integer || value instanceof Long;
    }

    // A FLOAT result; out of range is an error, as in MySQL
    static Float decimal(double value, String what) {
        float result = (float) value;
        if (Float.isInfinite(result) || Float.isNaN(result)) throw new IllegalArgumentException(what + " is out of range");
        return result;
    }
}
