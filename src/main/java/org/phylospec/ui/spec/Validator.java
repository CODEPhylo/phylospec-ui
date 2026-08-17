package org.phylospec.ui.spec;

import java.util.ArrayList;
import java.util.List;

import org.phylospec.ast.Stmt;
import org.phylospec.errors.Error;
import org.phylospec.lexer.Lexer;
import org.phylospec.lexer.Token;
import org.phylospec.parser.Parser;
import org.phylospec.typeresolver.TypeError;
import org.phylospec.typeresolver.TypeResolver;

/**
 * Checks generated scripts with the real PhyloSpec lexer, parser and type resolver.
 *
 * <p>Using the reference implementation rather than the GUI's own idea of validity means the status
 * bar reports exactly what an engine would.
 */
public final class Validator {

    private Validator() {}

    /** Problems found in {@code source}, in reading order; empty if the script is valid. */
    public static List<String> validate(Library library, String source) {
        List<String> problems = new ArrayList<>();

        Lexer lexer = new Lexer(source);
        lexer.registerEventListener(error -> problems.add(describe(error)));
        List<Token> tokens = lexer.scanTokens();

        Parser parser = new Parser(tokens);
        parser.registerEventListener(error -> problems.add(describe(error)));
        List<Stmt> statements = parser.parse();

        // Type checking a script that did not parse only produces noise.
        if (!problems.isEmpty()) return problems;

        TypeResolver resolver = library.newTypeResolver();
        for (Stmt statement : statements) {
            try {
                statement.accept(resolver);
            } catch (TypeError error) {
                problems.add(error.getMessage());
            }
        }
        return problems;
    }

    private static String describe(Error error) {
        String hint = error.hint();
        return hint == null || hint.isBlank() ? error.description() : error.description() + " " + hint;
    }
}
