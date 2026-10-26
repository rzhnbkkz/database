package db.statement;

import db.query.QueryEngine;

// CHECKPOINT: writes every table to the data folder's snapshot and empties the log
public class CheckpointStatement implements Statement {
    public void execute(QueryEngine query) {
        query.checkpoint();
        System.out.println("Checkpoint written");
    }
}
