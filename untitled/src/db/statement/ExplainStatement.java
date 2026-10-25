package db.statement;

import db.query.QueryEngine;

// EXPLAIN SELECT ...: prints how each table would be read, without running the query
public class ExplainStatement implements Statement {
    private SelectStatement select;

    public ExplainStatement(SelectStatement select) {
        this.select = select;
    }

    public SelectStatement getSelect() {
        return select;
    }

    public void execute(QueryEngine query) {
        for (String line : query.explain(select.getQuery())) {
            System.out.println(line);
        }
    }
}
