package db.statement;

import db.query.QueryEngine;

public interface Statement {
    void execute(QueryEngine query);
}
