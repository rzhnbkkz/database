package db.statement;

import db.query.QueryEngine;

// BEGIN (or START TRANSACTION), COMMIT and ROLLBACK
public class TransactionStatement implements Statement {
    public enum Kind { BEGIN, COMMIT, ROLLBACK }

    private final Kind kind;

    public TransactionStatement(Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public void execute(QueryEngine query) {
        if (kind == Kind.BEGIN) {
            // As in MySQL, BEGIN inside a transaction commits it first
            boolean committed = query.inTransaction();
            query.begin();
            System.out.println(committed ? "Open transaction committed, new transaction started" : "Transaction started");
        } else if (!query.inTransaction()) {
            System.out.println("No transaction is open, nothing to " + (kind == Kind.COMMIT ? "commit" : "roll back"));
        } else if (kind == Kind.COMMIT) {
            query.commit();
            System.out.println("Transaction committed");
        } else {
            query.rollback();
            System.out.println("Transaction rolled back");
        }
    }
}
