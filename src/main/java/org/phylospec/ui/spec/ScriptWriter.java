package org.phylospec.ui.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.model.SiteModel;
import org.phylospec.ui.model.TreeModel;

/** Renders an {@link Analysis} as a PhyloSpec script. */
public final class ScriptWriter {

    private static final String INDENT = "    ";
    private static final int WRAP_AT = 88;

    private final Analysis analysis;
    private final Set<String> used = new LinkedHashSet<>();

    /** The script variable each group's product is written as, fixed before anything is emitted. */
    private final Map<SiteModel, String> qMatrixNames = new IdentityHashMap<>();
    private final Map<SiteModel, String> siteRatesNames = new IdentityHashMap<>();
    private final Map<Partition, String> branchRatesNames = new IdentityHashMap<>();

    /** Components already written out, since a shared one must not declare its params twice. */
    private final Set<Component> declared = Collections.newSetFromMap(new IdentityHashMap<>());

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
        analysis.trees().forEach(tree -> used.add(tree.name()));
        analysis.partitions().forEach(partition -> used.add(partition.name()));
        nameGroups();
        for (Param param : hoisted()) {
            param.variableProperty().set(unique(param.name()));
        }
        for (Param param : analysis.estimatedParams()) {
            param.variableProperty().set(unique(param.name()));
        }
    }

    /**
     * Fixes the variable each group is written as.
     *
     * <p>With everything linked there is one of each and the names are the plain {@code qMatrix},
     * {@code siteRates} and {@code branchRates} the writer has always used, so a linked analysis
     * produces exactly the script it produced before groups existed. Unlinking qualifies them by
     * the first partition using the group, which is also how the trees are named.
     */
    private void nameGroups() {
        qMatrixNames.clear();
        siteRatesNames.clear();
        branchRatesNames.clear();

        boolean oneSiteModel = analysis.siteModels().size() == 1;
        for (SiteModel model : analysis.siteModels()) {
            String qualifier = oneSiteModel ? "" : firstPartitionUsing(model);
            qMatrixNames.put(model, reserve(oneSiteModel ? "qMatrix" : qualifier + "QMatrix"));
            siteRatesNames.put(model, reserve(oneSiteModel ? "siteRates" : qualifier + "SiteRates"));
        }

        // Branch rates belong to a clock model and a tree together: one clock shared by partitions
        // on different trees still needs a vector per tree, since its length is the tree's.
        boolean oneVector = analysis.clockModels().size() == 1 && analysis.trees().size() == 1;
        Map<Component, Map<TreeModel, String>> byClockAndTree = new IdentityHashMap<>();
        for (Partition partition : analysis.partitions()) {
            Map<TreeModel, String> byTree =
                    byClockAndTree.computeIfAbsent(partition.clockModel(), c -> new IdentityHashMap<>());
            String name = byTree.get(partition.tree());
            if (name == null) {
                name = reserve(oneVector ? "branchRates" : partition.name() + "BranchRates");
                byTree.put(partition.tree(), name);
            }
            branchRatesNames.put(partition, name);
        }
        if (analysis.partitions().isEmpty()) reserve("branchRates");
    }

    private String firstPartitionUsing(SiteModel model) {
        return analysis.partitions().stream()
                .filter(partition -> partition.siteModel() == model)
                .map(Partition::name)
                .findFirst()
                .orElse("data");
    }

    private String reserve(String name) {
        String unique = unique(name);
        used.add(unique);
        return unique;
    }

    /**
     * Function-valued arguments across the analysis, innermost first.
     *
     * <p>Each becomes a statement of its own rather than a call nested inside the one that uses it,
     * so that it has a name — a population function or a bModelTest model set may be referred to
     * from more than one place, and an inlined call cannot be.
     */
    private List<Param> hoisted() {
        List<Param> found = new ArrayList<>();
        for (Component component : analysis.components()) {
            collectHoisted(component, found);
        }
        return found;
    }

    private static void collectHoisted(Component component, List<Param> into) {
        for (Param param : component.params()) {
            if (!param.includeProperty().get() || param.isEstimated()) continue;
            Component nested = param.priorProperty().get();
            if (nested == null || !isFunctionValued(param)) continue;
            collectHoisted(nested, into);
            into.add(param);
        }
    }

    /**
     * True for an argument built by calling a function, as opposed to one drawn from a distribution.
     * A distribution stays where it is written — {@code RelaxedClock(base=LogNormal(...))} has no
     * spelling as a statement.
     */
    private static boolean isFunctionValued(Param param) {
        return param.isComponentValued() && !param.isDistributionValued();
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
            if (partition.useSpeciesProperty().get()) {
                args.add("speciesName=" + speciesParseCall(partition));
            }
            statements.add(call("Alignment " + partition.name() + " = ", partition.loader(), args));
        }
        if (statements.isEmpty()) {
            statements.add("// No alignment loaded — add one in the Partitions tab.");
        }
        return statements;
    }

    /** The species half of the same idea: which piece of the taxon name names the species. */
    private static String speciesParseCall(Partition partition) {
        List<String> args = partition.speciesParseModeProperty().get() == Partition.ParseMode.SPLIT
                ? List.of("delimiter=" + quote(partition.speciesDelimiterProperty().get()),
                          "part=" + partition.speciesPartProperty().get())
                : List.of("regex=" + quote(partition.speciesRegexProperty().get()));
        return "parse(" + String.join(", ", args) + ")";
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
        declared.clear();

        // A likelihood that takes no rate matrix leaves the Site Model tab with nothing to choose,
        // and an unused qMatrix declaration would be left standing.
        for (SiteModel model : analysis.siteModels()) {
            Slot slot = slotFor(model);
            if (model != analysis.siteModels().get(0)) statements.add("");
            if (model.substitutionModel().generator() != null) {
                declarations(model.substitutionModel(), slot, statements);
                statements.add(assignment("QMatrix " + qMatrixNames.get(model) + " = ",
                        model.substitutionModel(), slot));
            }
            if (model.siteRates().generator() != null) {
                statements.add("");
                declarations(model.siteRates(), slot, statements);
                statements.add(draw(siteRatesNames.get(model), model.siteRates(), slot));
            }
        }

        for (TreeModel tree : analysis.trees()) {
            statements.add("");
            Slot slot = slotFor(tree);
            declarations(tree.prior(), slot, statements);
            statements.add(draw(tree.name(), tree.prior(), slot));
        }

        for (Slot slot : clockSlots()) {
            statements.add("");
            declarations(slot.clock(), slot, statements);
            statements.add(draw(slot.branchRates(), slot.clock(), slot));
        }

        if (analysis.likelihood().generator() != null) {
            List<String> lines = new ArrayList<>();
            declarations(analysis.likelihood(), slotForData(), lines);
            if (!lines.isEmpty()) {
                statements.add("");
                statements.addAll(lines);
            }
        }

        for (Partition partition : analysis.partitions()) {
            statements.add("");
            statements.add(observation(partition));
        }
        return statements;
    }

    /**
     * What the writer supplies for the structural arguments of one statement.
     *
     * <p>Per statement rather than per component, because a component can serve more than one. Two
     * trees drawn from a single shared {@code Coalescent} are two statements from one component
     * whose {@code taxa} argument differs, which is the whole point of sharing a prior: the
     * parameters are one, the trees are not.
     */
    private record Slot(Partition data, TreeModel tree, Component clock,
            String qMatrix, String siteRates, String branchRates, boolean structural) {

        Slot(Partition data, TreeModel tree, Component clock,
                String qMatrix, String siteRates, String branchRates) {
            this(data, tree, clock, qMatrix, siteRates, branchRates, true);
        }

        /**
         * The same slot, supplying nothing.
         *
         * <p>A prior is not one of the model's own components, so the writer has no business
         * wiring its arguments: a Yule over an estimated species tree spans the species, not the
         * taxa of whichever partition happens to be first.
         */
        Slot forPrior() {
            return new Slot(data, tree, clock, qMatrix, siteRates, branchRates, false);
        }
    }

    /** The partition a group's structural arguments are read from: the first one using it. */
    private Partition firstPartition() {
        return analysis.partitions().isEmpty() ? null : analysis.partitions().get(0);
    }

    private Slot slotForData() {
        return new Slot(firstPartition(), null, null, null, null, null);
    }

    private Slot slotFor(SiteModel model) {
        Partition data = analysis.partitions().stream()
                .filter(partition -> partition.siteModel() == model)
                .findFirst()
                .orElse(firstPartition());
        return new Slot(data, null, null, null, null, null);
    }

    private Slot slotFor(TreeModel tree) {
        Partition data = analysis.partitions().stream()
                .filter(partition -> partition.tree() == tree)
                .findFirst()
                .orElse(firstPartition());
        return new Slot(data, tree, null, null, null, null);
    }

    /** One slot per clock model and tree in use together, in the order the partitions give them. */
    private List<Slot> clockSlots() {
        List<Slot> slots = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Partition partition : analysis.partitions()) {
            if (partition.clockModel().generator() == null) continue;
            String name = branchRatesNames.get(partition);
            if (!seen.add(name)) continue;
            slots.add(new Slot(partition, partition.tree(), partition.clockModel(),
                    null, null, name));
        }
        if (analysis.partitions().isEmpty() && analysis.clockModel().generator() != null) {
            slots.add(new Slot(null, analysis.trees().get(0), analysis.clockModel(),
                    null, null, "branchRates"));
        }
        return slots;
    }

    /** Everything one partition's observation refers to. */
    private Slot slotFor(Partition partition) {
        SiteModel model = partition.siteModel();
        return new Slot(partition, partition.tree(), partition.clockModel(),
                model.substitutionModel().generator() == null ? null : qMatrixNames.get(model),
                model.siteRates().generator() == null ? null : siteRatesNames.get(model),
                partition.clockModel().generator() == null ? null : branchRatesNames.get(partition));
    }

    /**
     * Emits everything a component's statement refers to: `Type name ~ Distribution(...)` for each
     * estimated param, and `Type name = function(...)` for each function-valued one. Nested
     * components are descended into first, so a name is always declared before it is used.
     */
    private void declarations(Component component, Slot slot, List<String> statements) {
        // A prior shared by two trees is one set of parameters, so it declares them once.
        if (!declared.add(component)) return;
        for (Param param : component.params()) {
            // An argument left out of the call would leave its prior declared but unused.
            if (!param.includeProperty().get()) continue;
            Component nested = param.priorProperty().get();
            if (nested != null && !param.isEstimated()) {
                declarations(nested, slot, statements);
                if (isFunctionValued(param) && nested.generator() != null) {
                    statements.add(call(declared(param.type()) + " "
                            + param.variableProperty().get() + " = ", nested.name(), argsOf(nested, slot)));
                }
                continue;
            }
            if (!param.isEstimated()) continue;
            String variable = param.variableProperty().get();
            if (nested == null || nested.generator() == null) {
                statements.add("// " + variable + " is estimated but has no prior — set one in the Priors tab.");
                continue;
            }
            statements.add(call(declared(param.type()) + " " + variable + " ~ ",
                    nested.name(), argsOf(nested, slot.forPrior())));
        }
    }

    private String assignment(String prefix, Component component, Slot slot) {
        return call(prefix, component.name(), argsOf(component, slot));
    }

    private String draw(String variable, Component component, Slot slot) {
        String type = declared(Library.inner(component.generator().getGeneratedType()));
        return call(type + " " + variable + " ~ ", component.name(), argsOf(component, slot));
    }

    /**
     * The statement that ties an alignment to the model: the chosen likelihood, applied to the
     * structural variables the rest of the tabs produced, observed as the loaded data.
     */
    private String observation(Partition partition) {
        Component likelihood = analysis.likelihood();
        if (likelihood.generator() == null) {
            return "// No likelihood chosen — pick one in the Likelihood tab.";
        }
        String variable = unique(partition.name() + "Alignment");
        String type = declared(Library.inner(likelihood.generator().getGeneratedType()));
        return call(type + " " + variable + " ~ ", likelihood.name(), argsOf(likelihood, slotFor(partition)))
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
    private List<String> argsOf(Component component, Slot slot) {
        List<String> args = new ArrayList<>();
        for (org.phylospec.components.Argument argument : component.generator().getArguments()) {
            String wired = wiring(argument.getName(), slot);
            if (wired != null) {
                args.add(argument.getName() + "=" + wired);
                continue;
            }
            Param param = component.param(argument.getName());
            if (param == null || !param.includeProperty().get()) continue;
            args.add(param.name() + "=" + valueOf(param, slot));
        }
        return args;
    }

    /** The expression the writer supplies for a structural argument, or null if the user owns it. */
    private String wiring(String argument, Slot slot) {
        if (!slot.structural()) return null;
        String data = slot.data() == null ? "data" : slot.data().name();
        return switch (argument) {
            case "tree" -> slot.tree() == null ? null : slot.tree().name();
            case "taxa" -> "taxa(" + data + ")";
            case "numSites" -> "numSites(" + data + ")";
            // A structural variable only exists if the tab that declares it chose a component. An
            // optional argument whose variable was never declared is left out of the call.
            case "qMatrix" -> slot.qMatrix();
            case "siteRates" -> slot.siteRates();
            case "branchRates" -> slot.branchRates();
            default -> null;
        };
    }

    private String valueOf(Param param, Slot slot) {
        if (param.isEstimated()) return param.variableProperty().get();
        if (param.isComponentValued()) {
            Component nested = param.priorProperty().get();
            if (nested == null || nested.generator() == null) return "/* choose a " + param.type() + " */";
            if (isFunctionValued(param)) return param.variableProperty().get();
            return nested.name() + "(" + String.join(", ", argsOf(nested, slot)) + ")";
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
