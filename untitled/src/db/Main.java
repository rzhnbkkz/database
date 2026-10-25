package db;

import db.query.QueryEngine;
import db.repl.REPL;

public class Main {
    public static void main(String[] args) {
        System.out.println("""
                Commands:
                  CREATE <table> <column>:<type>[:PRIMARY] ... types: INTEGER, VARCHAR, BOOLEAN, FLOAT
                  INSERT <table> <column>=<value> ...
                  SELECT <table> [<column>,... | *] [WHERE <condition> [AND <condition>] ...]
                  UPDATE <table> SET <column>=<value> ... [WHERE ...]
                  DELETE <table> [WHERE ...]
                  SAVE <table> <path>
                  LOAD <path>
                  EXIT
                Conditions: <column><op><value> with op one of = != > < >= <=
                Use double quotes for text with spaces: name="Bob Smith\"""");
        new REPL().start(new QueryEngine());
    }
}
