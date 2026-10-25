package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.ArrayList;
import java.util.List;

// CASE WHEN condition THEN value ... [ELSE value] END: the value of the first condition that is TRUE
// (not unknown), else the ELSE value, else empty. CASE x WHEN a THEN ... is parsed into WHEN x = a.
public record CaseWhen(List<Expression> conditions, List<SelectItem> results, SelectItem otherwise) implements SelectItem {

    public CaseWhen {
        conditions = List.copyOf(conditions);
        results = List.copyOf(results);
    }

    public String key() {
        return toString();
    }

    public SelectItem resolve(Scope scope) {
        List<Expression> resolvedConditions = new ArrayList<>();
        for (Expression condition : conditions) resolvedConditions.add(condition.resolve(scope));
        List<SelectItem> resolvedResults = new ArrayList<>();
        for (SelectItem result : results) resolvedResults.add(result.resolve(scope));
        return new CaseWhen(resolvedConditions, resolvedResults, otherwise == null ? null : otherwise.resolve(scope));
    }

    public SelectItem rewrite(Rewriter rewriter) {
        return new CaseWhen(conditions.stream().map(condition -> condition.rewrite(rewriter)).toList(),
                results.stream().map(result -> result.rewrite(rewriter)).toList(),
                otherwise == null ? null : otherwise.rewrite(rewriter));
    }

    public Object evaluate(Row row) {
        if (SelectItem.stored(this, row)) return row.get(key());
        for (int i = 0; i < conditions.size(); i++) {
            if (conditions.get(i).matches(row)) return results.get(i).evaluate(row);
        }
        return otherwise == null ? null : otherwise.evaluate(row);
    }

    // The first result whose type is known
    public DataType type(Scope scope) {
        List<SelectItem> all = new ArrayList<>(results);
        if (otherwise != null) all.add(otherwise);
        for (SelectItem result : all) {
            DataType type = result.type(scope);
            if (type != null) return type;
        }
        return null;
    }

    // What the conditions compare, and the results
    public List<SelectItem> children() {
        List<SelectItem> children = new ArrayList<>();
        for (Expression condition : conditions) children.addAll(condition.items());
        children.addAll(results);
        if (otherwise != null) children.add(otherwise);
        return children;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("CASE");
        for (int i = 0; i < conditions.size(); i++) {
            sb.append(" WHEN ").append(conditions.get(i)).append(" THEN ").append(results.get(i));
        }
        if (otherwise != null) sb.append(" ELSE ").append(otherwise);
        return sb.append(" END").toString();
    }
}
