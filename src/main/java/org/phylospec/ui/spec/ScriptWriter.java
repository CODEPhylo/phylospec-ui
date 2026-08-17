package org.phylospec.ui.spec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;

/** Renders an {@link Analysis} as a PhyloSpec script. */
public final class ScriptWriter {

    private static final String INDENT = "    ";
    private static final int WRAP_AT = 88;

    /** Names the writer introduces itself, which user parameters must not collide with. */
    private static final List<String> RESERVED = List.of("qMatrix", "siteRates", "branchRates");

    private final Analysis analysis;
    private final Set<String> used = new LinkedHashSet<>();

    private ScriptWriter(Analysis analysis) {
        this.analysis = analysis;
    }

    public static String write(Analysis analysis) {
        return new ScriptWriter(analysis).build();
    }

    private String build() {
        reserveNames();

        StringBuilder out = new StringBuilder();
        block(out, "data", dataStatements());
        out.append('\n');
        block(out, "model", modelStatements());
        out.append('\n');
        block(out, "mcmc", mcmcStatements());
        return out.toString();
    }

    /**
     * Fixes the script-level name of every estimated parameter up front, so that a value can be
     * referred to before its statement is generated.
     */
    private void reserveNames() {
        used.clear();
        used.add(analysis.treeNameProperty().get());
        used.addAll(RESERVED);
        analysis.partitions().forEach(partition -> used.add(partition.name()));
        for (Param param : analysis.estimatedParams()) {
            param.variableProperty().set(unique(param.name()));
        }
    }

    private String unique(String preferred) {
        if (used.add(preferred)) return preferred;
        for (int suffix = 2; ; suffix++) {
            String candidate = preferred + suffix;
            if (used.add(candidate)) return candidate;
        }
    }

    private static void block(StringBuilder out, String name, List<String> statements) {
        out.append(name).append(" {\n");
        for (String statement : statements) {
            statement.lines().forEach(line -> out.append(INDENT).append(line).append('\n'));
        }
        out.append("}\n");
    }

    // ---------------------------------------------------------------- data

    private List<String> dataStatements() {
        List<String> statements = new ArrayList<>();
        for (Partition partition : analysis.partitions()) {
            List<String> args = new ArrayList<>();
            args.add("file=" + quote(partition.fileProperty().get()));
            if (partition.useTipDatesProperty().get()) {
                args.add(partition.dateKindProperty().get().argument() + "=" + parseCall(partition));
            }
            statements.add(call("Alignment " + partition.name() + " = ", partition.loader(), args));
        }
        if (statements.isEmpty()) {
            statements.add("// No alignment loaded — add one in the Partitions tab.");
        }
        return statements;
    }

    private static String parseCall(Partition partition) {
        List<String> args = partition.parseModeProperty().get() == Partition.ParseMode.SPLIT
                ? List.of("delimiter=" + quote(partition.delimiterProperty().get()),
                          "part=" + partition.partProperty().get())
                : List.of("regex=" + quote(partition.regexProperty().get()));
        return "parse(" + String.join(", ", args) + ")";
    }

    // --------------------------------------------------------------- model

    private List<String> modelStatements() {
        List<String> statements = new ArrayList<>();
        String tree = analysis.treeNameProperty().get();

        priorStatements(analysis.substitutionModel(), statements);
        statements.add(assignment("QMatrix qMatrix = ", analysis.substitutionModel()));

        boolean hasSiteRates = analysis.siteRates().generator() != null;
        if (hasSiteRates) {
            statements.add("");
            priorStatements(analysis.siteRates(), statements);
            statements.add(draw("siteRates", analysis.siteRates()));
        }

        statements.add("");
        priorStatements(analysis.treePrior(), statements);
        statements.add(draw(tree, analysis.treePrior()));

        boolean hasClock = analysis.clockModel().generator() != null;
        if (hasClock) {
            statements.add("");
            priorStatements(analysis.clockModel(), statements);
            statements.add(draw("branchRates", analysis.clockModel()));
        }

        for (Partition partition : analysis.partitions()) {
            statements.add("");
            statements.add(phyloCtmc(partition, hasSiteRates, hasClock));
        }
        return statements;
    }

    /**
     * Emits `Type name ~ Distribution(...)` for each estimated param of a component, descending into
     * nested functions first so their parameters are declared before the call that uses them.
     */
    private void priorStatements(Component component, List<String> statements) {
        for (Param param : component.params()) {
            Component nested = param.priorProperty().get();
            if (nested != null && !param.isEstimated()) {
                priorStatements(nested, statements);
                continue;
            }
            if (!param.isEstimated()) continue;
            String variable = param.variableProperty().get();
            if (nested == null || nested.generator() == null) {
                statements.add("// " + variable + " is estimated but has no prior — set one in the Priors tab.");
                continue;
            }
            statements.add(call(declared(param.type()) + " " + variable + " ~ ", nested.name(), argsOf(nested)));
        }
    }

    private String assignment(String prefix, Component component) {
        return call(prefix, component.name(), argsOf(component));
    }

    private String draw(String variable, Component component) {
        String type = declared(Library.inner(component.generator().getGeneratedType()));
        return call(type + " " + variable + " ~ ", component.name(), argsOf(component));
    }

    private String phyloCtmc(Partition partition, boolean hasSiteRates, boolean hasClock) {
        List<String> args = new ArrayList<>();
        args.add("tree=" + analysis.treeNameProperty().get());
        args.add("qMatrix=qMatrix");
        if (hasSiteRates) args.add("siteRates=siteRates");
        if (hasClock) args.add("branchRates=branchRates");
        String variable = unique(partition.name() + "Alignment");
        return call("Alignment " + variable + " ~ ", "PhyloCTMC", args)
                + " observed as " + partition.name();
    }

    // ---------------------------------------------------------------- mcmc

    private List<String> mcmcStatements() {
        List<String> statements = new ArrayList<>();
        statements.add("Integer chainLength = " + analysis.chainLengthProperty().get());
        statements.add("Integer logEvery = " + analysis.logEveryProperty().get());
        statements.add("String logFile = " + quote(analysis.logFileProperty().get()));
        String seed = analysis.seedProperty().get();
        if (seed != null && !seed.isBlank()) {
            statements.add("Integer randomSeed = " + seed.trim());
        }
        return statements;
    }

    // ------------------------------------------------------------ argument

    /** Renders every argument of a component, wiring in the values the writer owns. */
    private List<String> argsOf(Component component) {
        List<String> args = new ArrayList<>();
        for (org.phylospec.components.Argument argument : component.generator().getArguments()) {
            String wired = wiring(argument.getName());
            if (wired != null) {
                args.add(argument.getName() + "=" + wired);
                continue;
            }
            Param param = component.param(argument.getName());
            if (param == null || !param.includeProperty().get()) continue;
            args.add(param.name() + "=" + valueOf(param));
        }
        return args;
    }

    /** The expression the writer supplies for a structural argument, or null if the user owns it. */
    private String wiring(String argument) {
        String first = analysis.partitions().isEmpty() ? "data" : analysis.partitions().get(0).name();
        return switch (argument) {
            case "tree" -> analysis.treeNameProperty().get();
            case "taxa" -> "taxa(" + first + ")";
            case "numSites" -> "numSites(" + first + ")";
            case "qMatrix" -> "qMatrix";
            case "siteRates" -> "siteRates";
            case "branchRates" -> "branchRates";
            default -> null;
        };
    }

    private String valueOf(Param param) {
        if (param.isEstimated()) return param.variableProperty().get();
        if (param.isComponentValued()) {
            Component nested = param.priorProperty().get();
            if (nested == null || nested.generator() == null) return "/* choose a " + param.type() + " */";
            return nested.name() + "(" + String.join(", ", argsOf(nested)) + ")";
        }
        String value = param.valueProperty().get();
        if (value == null || value.isBlank()) return "/* " + param.name() + " */";
        return "String".equals(Library.head(param.type())) ? quote(value) : value.trim();
    }

    // --------------------------------------------------------------- utils

    /**
     * Turns a library type into one that can be written in a script: strips the type properties
     * PhyloSpec attaches to generated types, so {@code Tree<;numTaxa=x>} becomes {@code Tree}, and
     * drops any namespace qualification.
     */
    static String declared(String type) {
        if (type == null) return "Real";
        String head = Library.head(type);
        head = head.substring(head.lastIndexOf('.') + 1);
        String inner = Library.inner(type);
        if (inner == null) return head;
        String kept = inner.split(";", 2)[0].trim();
        return kept.isEmpty() ? head : head + "<" + declared(kept) + ">";
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1) return trimmed;
        return "\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** One line if it fits, otherwise one argument per line. */
    private static String call(String prefix, String function, List<String> args) {
        String flat = prefix + function + "(" + String.join(", ", args) + ")";
        if (flat.length() + INDENT.length() <= WRAP_AT || args.isEmpty()) return flat;
        return prefix + function + "(\n"
                + args.stream().map(a -> INDENT + a).collect(Collectors.joining(",\n"))
                + "\n)";
    }
}
