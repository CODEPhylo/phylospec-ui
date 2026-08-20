package org.phylospec.ui.spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.phylospec.ast.AstType;
import org.phylospec.ast.Expr;
import org.phylospec.ast.Stmt;
import org.phylospec.components.Argument;
import org.phylospec.components.Generator;
import org.phylospec.lexer.Lexer;
import org.phylospec.lexer.Token;
import org.phylospec.lexer.TokenType;
import org.phylospec.parser.Parser;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;

/**
 * Reads a PhyloSpec script back onto the tabs — the inverse of {@link ScriptWriter}.
 *
 * <p>The tabs express a subset of PhyloSpec, so not every valid script can be represented here. When
 * a statement falls outside that subset the whole load is refused rather than partly applied: a
 * half-loaded analysis looks complete, and the next save would quietly discard whatever could not be
 * mapped.
 *
 * <p>The script's structure is recognised by the names {@link ScriptWriter} gives its structural
 * variables — {@code qMatrix}, {@code siteRates}, {@code branchRates} — and by the declared type of
 * the tree. Everything else drawn in the model block is a prior on an estimated value.
 */
public final class ScriptReader {

    /** A script that parses but says something the tabs cannot express. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) {
            super(message);
        }
    }

    private final Library library;

    /** Every {@code ~} statement in the model block, by variable name. */
    private final Map<String, Stmt.Draw> draws = new LinkedHashMap<>();

    /**
     * Every {@code =} statement in the model block that names a function-valued intermediate, and
     * the names of those an argument has since claimed. One nothing claims is a statement the tabs
     * have nowhere to put, so the load is refused rather than quietly dropping it.
     */
    private final Map<String, Expr.Call> intermediates = new LinkedHashMap<>();

    private final Set<String> claimed = new LinkedHashSet<>();

    private ScriptReader(Library library) {
        this.library = library;
    }

    /**
     * Builds an analysis from {@code source}.
     *
     * @throws Unsupported if the script does not parse, or says something the tabs cannot express
     */
    public static Analysis read(Library library, String source) {
        return new ScriptReader(library).build(source);
    }

    private Analysis build(String source) {
        List<Stmt> statements = parse(source);
        Analysis analysis = new Analysis(library);

        for (Stmt statement : statements) {
            if (statement instanceof Stmt.Draw draw && Stmt.Block.MODEL.equals(draw.block)) {
                draws.put(draw.name, draw);
            }
            // An intermediate may be declared after the statement that uses it is read, so they are
            // all collected before anything is bound.
            if (statement instanceof Stmt.Assignment assignment
                    && Stmt.Block.MODEL.equals(assignment.block)
                    && !"qMatrix".equals(assignment.name)) {
                intermediates.put(assignment.name, callOf(assignment.expression, assignment.name));
            }
        }

        // The tabs' defaults have to be cleared, or a script that leaves out a clock or rate
        // heterogeneity would come back with the default one still selected.
        analysis.siteRates().generatorProperty().set(null);
        analysis.clockModel().generatorProperty().set(null);

        boolean sawModel = false;
        for (Stmt statement : statements) {
            switch (blockOf(statement)) {
                case "data" -> readDataStatement(analysis, statement);
                case "model" -> sawModel |= readModelStatement(analysis, statement);
                case "mcmc" -> readMcmcStatement(analysis, statement);
                default -> throw new Unsupported(
                        "Statements outside the data, model and mcmc blocks are not supported.");
            }
        }

        if (analysis.partitions().isEmpty()) throw new Unsupported("The script loads no alignment.");
        if (!sawModel) throw new Unsupported("The script has no substitution model or tree prior.");

        for (String name : intermediates.keySet()) {
            if (!claimed.contains(name)) {
                throw new Unsupported("Nothing in the model uses " + name + ", "
                        + "and the tabs have nowhere to keep it.");
            }
        }
        return analysis;
    }

    private List<Stmt> parse(String source) {
        List<String> problems = Validator.validate(library, source);
        if (!problems.isEmpty()) {
            throw new Unsupported("This is not a valid PhyloSpec script: " + problems.get(0));
        }
        Lexer lexer = new Lexer(source);
        List<Token> tokens = lexer.scanTokens();
        return new Parser(tokens).parse();
    }

    private static String blockOf(Stmt statement) {
        return String.valueOf(statement.block);
    }

    // ---------------------------------------------------------------- data

    private void readDataStatement(Analysis analysis, Stmt statement) {
        if (!(statement instanceof Stmt.Assignment assignment)) {
            throw new Unsupported("Only alignment assignments are supported in the data block.");
        }
        Expr.Call call = callOf(assignment.expression, assignment.name);
        if (!List.of("fromNexus", "fromFasta").contains(call.functionName)) {
            throw new Unsupported("Unsupported data loader: " + call.functionName + ".");
        }

        Expr file = argument(call, "file");
        if (file == null) throw new Unsupported(call.functionName + " needs a file argument.");

        Partition partition = new Partition(Path.of(text(file, "file")));
        partition.nameProperty().set(assignment.name);
        readTipDates(partition, call);
        analysis.partitions().add(partition);
    }

    private void readTipDates(Partition partition, Expr.Call loader) {
        for (Partition.DateKind kind : Partition.DateKind.values()) {
            Expr dates = argument(loader, kind.argument());
            if (dates == null) continue;

            Expr.Call parse = callOf(dates, kind.argument());
            if (!"parse".equals(parse.functionName)) {
                throw new Unsupported("Tip dates must be read with parse(...), not "
                        + parse.functionName + "(...).");
            }
            partition.useTipDatesProperty().set(true);
            partition.dateKindProperty().set(kind);

            Expr regex = argument(parse, "regex");
            if (regex != null) {
                partition.parseModeProperty().set(Partition.ParseMode.REGEX);
                partition.regexProperty().set(text(regex, "regex"));
            } else {
                partition.parseModeProperty().set(Partition.ParseMode.SPLIT);
                Expr delimiter = argument(parse, "delimiter");
                Expr part = argument(parse, "part");
                if (delimiter != null) partition.delimiterProperty().set(text(delimiter, "delimiter"));
                if (part != null) partition.partProperty().set(text(part, "part"));
            }
            return;
        }
    }

    // --------------------------------------------------------------- model

    /** Returns true if the statement set one of the model tabs. */
    private boolean readModelStatement(Analysis analysis, Stmt statement) {
        if (statement instanceof Stmt.Assignment assignment) {
            // Anything else is a function-valued intermediate, read as part of the argument that
            // names it rather than on its own.
            if (!"qMatrix".equals(assignment.name)) return false;
            bind(analysis.substitutionModel(), callOf(assignment.expression, assignment.name), true);
            return true;
        }

        // The PhyloCTMC is written from the shape of the analysis, so there is nothing to read back
        // from it beyond the fact that it is there.
        if (statement instanceof Stmt.ObservedAs) return false;

        if (statement instanceof Stmt.Draw draw) {
            Expr.Call call = callOf(draw.expression, draw.name);
            switch (draw.name) {
                case "siteRates" -> bind(analysis.siteRates(), call, true);
                case "branchRates" -> bind(analysis.clockModel(), call, true);
                default -> {
                    if (!"Tree".equals(head(draw.type))) return false; // a prior; bound with its param
                    analysis.treeNameProperty().set(draw.name);
                    bind(analysis.treePrior(), call, true);
                }
            }
            return true;
        }

        throw new Unsupported("Unsupported statement in the model block: "
                + statement.getClass().getSimpleName() + ".");
    }

    /**
     * Points a component at the generator the call names and fills in its params from the call's
     * arguments. Arguments the writer supplies itself have no param and are skipped.
     */
    private void bind(Component component, Expr.Call call, boolean argsEstimable) {
        component.generatorProperty().set(overloadFor(call));

        for (Param param : component.params()) {
            Expr value = argument(call, param.name());
            if (value == null) {
                param.includeProperty().set(false);
                param.estimateProperty().set(false);
                continue;
            }
            param.includeProperty().set(true);
            bindValue(param, value, argsEstimable);
        }
    }

    private void bindValue(Param param, Expr value, boolean argsEstimable) {
        if (value instanceof Expr.Variable variable && draws.containsKey(variable.variableName)) {
            if (!param.estimable()) {
                throw new Unsupported(param.name() + " is drawn from a distribution, "
                        + "which this tab cannot express for a value of type " + param.type() + ".");
            }
            param.estimateProperty().set(true);
            param.priorProperty().set(
                    nested(callOf(draws.get(variable.variableName).expression, param.name()), false));
            return;
        }

        param.estimateProperty().set(false);
        if (value instanceof Expr.Variable variable && param.isComponentValued()
                && intermediates.containsKey(variable.variableName)) {
            claimed.add(variable.variableName);
            param.priorProperty().set(nested(intermediates.get(variable.variableName), argsEstimable));
            return;
        }
        if (value instanceof Expr.Call call && param.isComponentValued()) {
            param.priorProperty().set(nested(call, argsEstimable));
            return;
        }
        param.valueProperty().set(text(value, param.name()));
    }

    /** A component for a nested call: a prior, a distribution-valued argument, or a function. */
    private Component nested(Expr.Call call, boolean argsEstimable) {
        Component component = Component.nested(overloadFor(call), library, argsEstimable);
        bind(component, call, argsEstimable);
        return component;
    }

    /**
     * The signature the call names.
     *
     * <p>Argument names tell the two BirthDeath parameterisations apart, but not the two coalescents:
     * both take {@code populationSize} and {@code taxa}, and differ only in whether the population
     * size is a number or a function of time. So candidates are also scored on whether each supplied
     * value could have the type the signature asks for.
     */
    private Generator overloadFor(Expr.Call call) {
        List<String> supplied = new ArrayList<>();
        for (Expr.Argument argument : call.arguments) {
            if (argument.name != null) supplied.add(argument.name);
        }

        Generator best = null;
        int bestFit = -1;
        int fewest = Integer.MAX_VALUE;
        for (Generator candidate : library.overloads(call.functionName)) {
            List<String> names = candidate.getArguments().stream().map(Argument::getName).toList();
            if (!names.containsAll(supplied)) continue;

            int fit = 0;
            for (Argument declared : candidate.getArguments()) {
                Expr value = argument(call, declared.getName());
                if (value != null && fits(declared.getType(), value)) fit++;
            }
            if (fit > bestFit || (fit == bestFit && names.size() < fewest)) {
                bestFit = fit;
                fewest = names.size();
                best = candidate;
            }
        }
        if (best == null) {
            throw new Unsupported(library.overloads(call.functionName).isEmpty()
                    ? "The component library has no " + call.functionName + "."
                    : "No signature of " + call.functionName + " takes ("
                            + String.join(", ", supplied) + ").");
        }
        return best;
    }

    /** Whether {@code expression} could produce a value of {@code type}. */
    private boolean fits(String type, Expr expression) {
        if (expression instanceof Expr.Call call) {
            List<Generator> candidates = library.overloads(call.functionName);
            // An unknown function says nothing either way; let the argument names decide.
            return candidates.isEmpty()
                    || candidates.stream().anyMatch(g -> produces(type, g.getGeneratedType()));
        }
        if (expression instanceof Expr.Variable variable) {
            // An intermediate is typed by the function that builds it. This is what tells the two
            // coalescents apart: both take populationSize, one a number and one a function of time.
            Expr.Call intermediate = intermediates.get(variable.variableName);
            if (intermediate != null) return fits(type, intermediate);
            Stmt.Draw draw = draws.get(variable.variableName);
            // A variable the writer supplies, such as the tree, is not declared in the model block.
            return draw == null || produces(type, typeName(draw.type));
        }
        return Library.hasLiteralSyntax(type);
    }

    /** Whether a value of {@code produced} can be passed where {@code wanted} is asked for. */
    private boolean produces(String wanted, String produced) {
        if (produced == null) return false;
        if (!library.isSubtype(Library.head(produced), Library.head(wanted))) return false;
        String wantedInner = Library.inner(wanted);
        String producedInner = Library.inner(produced);
        if (wantedInner == null || producedInner == null) return true;
        return library.isSubtype(Library.head(producedInner), Library.head(wantedInner));
    }

    /** Renders a declared type back into the library's spelling, so {@code Vector<Rate>} survives. */
    private static String typeName(AstType type) {
        if (type == null) return null;
        if (type instanceof AstType.Generic generic) {
            return generic.name + "<" + typeName(generic.typeParameters[0]) + ">";
        }
        return type.name;
    }

    // ---------------------------------------------------------------- mcmc

    private void readMcmcStatement(Analysis analysis, Stmt statement) {
        if (!(statement instanceof Stmt.Assignment assignment)) {
            throw new Unsupported("Only assignments are supported in the mcmc block.");
        }
        String value = text(assignment.expression, assignment.name);
        switch (assignment.name) {
            case "chainLength" -> analysis.chainLengthProperty().set(value);
            case "logEvery" -> analysis.logEveryProperty().set(value);
            case "logFile" -> analysis.logFileProperty().set(value);
            case "randomSeed" -> analysis.seedProperty().set(value);
            default -> throw new Unsupported("Unsupported mcmc setting: " + assignment.name + ".");
        }
    }

    // --------------------------------------------------------------- utils

    private static Expr argument(Expr.Call call, String name) {
        for (Expr.Argument argument : call.arguments) {
            if (name.equals(argument.name)) return argument.expression;
        }
        return null;
    }

    private static Expr.Call callOf(Expr expression, String what) {
        if (expression instanceof Expr.Call call) return call;
        throw new Unsupported(what + " must be built by calling a component.");
    }

    private static String head(AstType type) {
        return type == null ? null : type.name;
    }

    /** Renders a literal back into the text the value box would hold. */
    private static String text(Expr expression, String what) {
        if (expression instanceof Expr.Literal literal) {
            return literal.value instanceof String string
                    ? unescape(string)
                    : String.valueOf(literal.value);
        }
        if (expression instanceof Expr.Unary unary) {
            return TokenType.getLexeme(unary.operator) + text(unary.right, what);
        }
        if (expression instanceof Expr.Grouping grouping) return text(grouping.expression, what);
        if (expression instanceof Expr.Array array) {
            return array.elements.stream()
                    .map(element -> text(element, what))
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        // A value box holds free text, so an expression typed into one is rendered straight back.
        // Whitespace is normalised in the process, which is the only thing a round trip changes.
        if (expression instanceof Expr.Variable variable) return variable.variableName;
        if (expression instanceof Expr.Binary binary) {
            return text(binary.left, what) + " " + TokenType.getLexeme(binary.operator)
                    + " " + text(binary.right, what);
        }
        if (expression instanceof Expr.Call call) {
            return call.functionName + java.util.Arrays.stream(call.arguments)
                    .map(argument -> (argument.name == null ? "" : argument.name + "=")
                            + text(argument.expression, what))
                    .collect(Collectors.joining(", ", "(", ")"));
        }
        throw new Unsupported("The value of " + what + " is an expression the tabs cannot edit.");
    }

    /**
     * Undoes the escaping {@link ScriptWriter} applies to string values. The lexer hands back the
     * text between the quotes as written, escapes and all, so a regular expression would otherwise
     * grow a backslash every time it was loaded and saved.
     */
    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean escaped = c == '\\' && i + 1 < value.length()
                    && (value.charAt(i + 1) == '\\' || value.charAt(i + 1) == '"');
            if (escaped) {
                out.append(value.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
