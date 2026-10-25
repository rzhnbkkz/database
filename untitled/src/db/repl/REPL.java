package db.repl;

import db.parser.Parser;
import db.query.QueryEngine;
import db.statement.Statement;

import java.util.Scanner;

public class REPL {
    Scanner scanner = new Scanner(System.in);
    public void start(QueryEngine query) {
        while(true) {
            System.out.print("Input a command or type EXIT to exit: ");
            if(!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if(input.equalsIgnoreCase("EXIT")) {
                break;
            }
            try {
                Statement statement = Parser.parse(input);
                statement.execute(query);
            }
            catch (IllegalArgumentException e) {
                System.out.println(e.getMessage());
            }
        }
    }
}
