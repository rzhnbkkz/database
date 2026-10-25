package db;

import db.query.QueryEngine;
import db.repl.REPL;

public class Main {
    public static void main(String[] args) {
        System.out.println("""
                Commands:
                  CREATE TABLE <table> (<column> <type> [PRIMARY KEY], ...)   types: INTEGER, VARCHAR, BOOLEAN, FLOAT
                  INSERT INTO <table> [(<column>, ...)] VALUES (<value>, ...)
                  SELECT <column>, ... | * FROM <table> [WHERE <condition> [AND <condition>] ...]
                  UPDATE <table> SET <column> = <value>, ... [WHERE ...]
                  DELETE FROM <table> [WHERE ...]
                  SAVE <table> '<path>'
                  LOAD '<path>'
                  EXIT
                Conditions: <column> <op> <value> with op one of = != <> > < >= <=
                Values: 42, -1.5, TRUE, FALSE, NULL, 'text' (text always in quotes)""");
        new REPL().start(new QueryEngine());
    }
}
