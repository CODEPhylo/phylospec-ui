package org.phylospec.ui.spec;

import java.util.ArrayList;
import java.util.List;

import org.phylospec.ast.Stmt;
import org.phylospec.errors.Error;
import org.phylospec.errors.ErrorEventListener;
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

    /**
     * What the reference implementation makes of a script.
     *
     * <p>The two are kept apart because they say different things. A problem means the script is
     * wrong: it did not parse, or the resolver refused it outright. A warning means the resolver
     * could not rule the script out but doubts it — a component's {@code constraints} relate two
     * arguments and the types say the relation does not hold. Reporting a doubt as a refusal would
     * overstate it.
     */
    public record Report(List<String> problems, List<String> warnings) {

        /** Everything said about the script, refusals first; empty if it is beyond reproach. */
        public List<String> all() {
            List<String> all = new ArrayList<>(problems);
            all.addAll(warnings);
            return all;
        }
    }

    /** Problems found in {@code source}, in reading order; empty if the script is valid. */
    public static List<String> validate(Library library, String source) {
        return check(library, source).problems();
    }

    /** Everything the lexer, parser and type resolver report about {@code source}. */
    public static Report check(Library library, String source) {
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Lexer lexer = new Lexer(source);
        lexer.registerEventListener(error -> problems.add(describe(error)));
        List<Token> tokens = lexer.scanTokens();

        Parser parser = new Parser(tokens);
        parser.registerEventListener(error -> problems.add(describe(error)));
        List<Stmt> statements = parser.parse();

        // Type checking a script that did not parse only produces noise.
        if (!problems.isEmpty()) return new Report(problems, warnings);

        TypeResolver resolver = library.newTypeResolver();

        // The resolver throws what it refuses, but reports what it merely doubts on this channel,
        // which is also where the property engine's constraint checks land.
        resolver.registerEventListener(new ErrorEventListener() {
            @Override
            public void errorDetected(Error error) {
                problems.add(describe(error));
            }

            @Override
            public void warningDetected(Error warning) {
                warnings.add(describe(warning));
            }
        });

        for (Stmt statement : statements) {
            try {
                statement.accept(resolver);
            } catch (TypeError error) {
                problems.add(error.getMessage());
            }
        }
        return new Report(problems, warnings);
    }

    private static String describe(Error error) {
        String hint = error.hint();
        return hint == null || hint.isBlank() ? error.description() : error.description() + " " + hint;
    }
}
