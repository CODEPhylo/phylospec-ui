package org.phylospec.ui.spec;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
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
import org.phylospec.ui.model.SiteModel;
import org.phylospec.ui.model.TreeModel;

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
    private final EngineSupport support;
    private final Path directory;
    private Analysis analysis;

    /** Every {@code ~} statement in the model block, by variable name. */
    private final Map<String, Stmt.Draw> draws = new LinkedHashMap<>();

    // Per-entry editors own independent priors; they cannot represent a shared sampled scalar.
    private final Set<String> vectorEntryDraws = new LinkedHashSet<>();
    private final Set<String> boundDraws = new LinkedHashSet<>();
    private final Map<String, Param> drawnParams = new LinkedHashMap<>();
    private final Set<String> ambiguousDraws = new LinkedHashSet<>();
    private final Map<Param, String> references = new LinkedHashMap<>();
    private final Map<String, Expr.Array> vectors = new LinkedHashMap<>();
    private final Set<String> boundVectors = new LinkedHashSet<>();
    private final Map<String, Param> componentVectors = new LinkedHashMap<>();
    private final Map<Param, String> vectorReferences = new LinkedHashMap<>();

    /**
     * Every {@code =} statement in the model block that names a function-valued intermediate, and
     * the names of those an argument has since claimed. One nothing claims is a statement the tabs
     * have nowhere to put, so the load is refused rather than quietly dropping it.
     */
    private final Map<String, Expr.Call> intermediates = new LinkedHashMap<>();

    /** Every {@code QMatrix} assignment, by variable name: one per site model in use. */
    private final Map<String, Expr.Call> matrices = new LinkedHashMap<>();

    /** What each partition's observation referred to, which is what the grouping is read from. */
    private final Map<String, Wiring> wiring = new LinkedHashMap<>();

    /** The structural variables one observation names. Absent ones are null. */
    private record Wiring(String tree, String qMatrix, String siteRates, String branchRates, Expr mutationRate) {}
    private record SiteKey(String matrix, String sites, Expr mutationRate) {}

    /** Components built from a drawn variable, so that a shared one is built once. */
    private final Map<String, Component> byVariable = new LinkedHashMap<>();

    private final Set<String> claimed = new LinkedHashSet<>();

    /** Whether a likelihood has been read, so that a second one can be compared against it. */
    private boolean sawLikelihood;
    private Expr.Call clockMixture;
    private Expr.Call latentObservation;
    private final Set<String> latentAlignments = new LinkedHashSet<>();

    private ScriptReader(Library library, EngineSupport support, Path directory) {
        this.library = library;
        this.support = support;
        this.directory = directory;
    }

    /**
     * Builds an analysis from {@code source}.
     *
     * @throws Unsupported if the script does not parse, or says something the tabs cannot express
     */
    public static Analysis read(Library library, String source) {
        return read(library, EngineSupport.unclaimed(), source);
    }

    public static Analysis read(Library library, EngineSupport support, String source) {
        return new ScriptReader(library, support, null).build(source);
    }

    /** Keep installed capabilities and resolve data paths relative to the opened script. */
    public static Analysis read(Library library, EngineSupport support, Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return new ScriptReader(library, support, absolute.getParent()).build(Files.readString(absolute));
    }

    private Analysis build(String source) {
        List<Stmt> statements = parse(source);
        analysis = new Analysis(library, support);

        for (Stmt statement : statements) {
            if (statement instanceof Stmt.Draw draw && Stmt.Block.MODEL.equals(draw.block)) {
                draws.put(draw.name, draw);
            }
            // An intermediate may be declared after the statement that uses it is read, so they are
            // all collected before anything is bound.
            if (statement instanceof Stmt.Assignment assignment
                    && Stmt.Block.MODEL.equals(assignment.block)) {
                // A rate matrix is a site model, not an intermediate. Recognised by its declared
                // type rather than by the name `qMatrix`, since unlinked partitions have one each.
                if ("QMatrix".equals(head(assignment.type))) {
                    matrices.put(assignment.name, callOf(assignment.expression, assignment.name));
                } else if (assignment.expression instanceof Expr.Array array) {
                    vectors.put(assignment.name, array);
                } else {
                    intermediates.put(assignment.name, callOf(assignment.expression, assignment.name));
                }
            }
        }

        // The tabs' defaults have to be cleared, or a script that leaves out a clock, rate
        // heterogeneity or — since the likelihood became a choice — a rate matrix would come back
        // with the default one still selected.
        analysis.substitutionModel().generatorProperty().set(null);
        analysis.siteRates().generatorProperty().set(null);
        analysis.clockModel().generatorProperty().set(null);
        analysis.likelihood().generatorProperty().set(null);

        boolean sawModel = false;
        for (Stmt statement : statements) {
            // Namespace imports affect name resolution, but do not correspond to a GUI field.
            if (statement instanceof Stmt.Import) continue;
            switch (blockOf(statement)) {
                case "data" -> readDataStatement(analysis, statement);
                case "model" -> sawModel |= readModelStatement(analysis, statement);
                case "mcmc" -> readMcmcStatement(analysis, statement);
                default -> throw new Unsupported(
                        "Statements outside the data, model and mcmc blocks are not supported.");
            }
        }

        // Every partition's observation has been read, so what each one refers to is known, and the
        // grouping follows from it: partitions naming the same variable share the model behind it.
        sawModel |= buildGroups(analysis);

        for (var entry : vectorReferences.entrySet()) {
            Param target = componentVectors.get(entry.getValue());
            if (target == null || !library.isSubtype(target.type(), entry.getKey().type())) {
                throw new Unsupported("Component vector " + entry.getValue()
                        + " needs a compatible editable owner in the model tabs.");
            }
            entry.getKey().referenceProperty().set(target);
            if (!entry.getKey().validReference(library, support)) {
                throw new Unsupported("Incompatible parameter or vector index for " + entry.getKey().name() + ".");
            }
        }

        for (var entry : references.entrySet()) {
            Param target = drawnParams.get(entry.getValue());
            if (target == null || ambiguousDraws.contains(entry.getValue())
                    || !analysis.estimatedParams().contains(target)) {
                throw new Unsupported("The referenced parameter " + entry.getValue()
                        + " needs an estimated parameter or vector entry in the model tabs.");
            }
            entry.getKey().referenceProperty().set(target);
            if (!entry.getKey().validReference(library, support)) {
                throw new Unsupported("Incompatible parameter or vector index for " + entry.getKey().name() + ".");
            }
        }

        if (analysis.partitions().isEmpty()) throw new Unsupported("The script loads no alignment.");
        if (!sawLikelihood) throw new Unsupported("No alignment is observed under a likelihood.");
        if (!sawModel) throw new Unsupported("The script has no substitution model or tree prior.");

        if (analysis.isReadCount()) {
            if (analysis.partitions().size() != 1 || !analysis.partitions().getFirst().isReadCount()) {
                throw new Unsupported("ReadCountModel currently requires one read-count partition in this UI.");
            }
            Partition partition = analysis.partitions().getFirst();
            Expr taxa = argument(drawnCall(partition.tree().name()), "taxa");
            if (!(taxa instanceof Expr.Call accessor) || !"readCountTaxa".equals(accessor.functionName)
                    || !partition.name().equals(variableArgument(accessor, "counts"))) {
                throw new Unsupported("The read-count tree must use readCountTaxa(counts=the observed data); "
                        + "other taxon selections cannot be preserved by this UI.");
            }
        }

        for (String name : intermediates.keySet()) {
            if (!claimed.contains(name)) {
                throw new Unsupported("Nothing in the model uses " + name + ", "
                        + "and the tabs have nowhere to keep it.");
            }
        }
        for (String name : vectors.keySet()) {
            if (!boundVectors.contains(name)) throw new Unsupported("Nothing in the model uses vector " + name + ".");
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
        if (!List.of("fromNexus", "fromFasta", "fromReadCountNexus").contains(call.functionName)) {
            throw new Unsupported("Unsupported data loader: " + call.functionName + ".");
        }

        Expr file = argument(call, "file");
        if (file == null) throw new Unsupported(call.functionName + " needs a file argument.");

        Path data = Path.of(text(file, "file"));
        if (directory != null && !data.isAbsolute()) data = directory.resolve(data).normalize();
        Partition partition = new Partition(data);
        if ((partition.isReadCount() || "fromReadCountNexus".equals(call.functionName))
                && !partition.loader().equals(call.functionName)) {
            throw new Unsupported("Read-count data require fromReadCountNexus and a readable NEXUS file declaring datatype=readCount.");
        }
        if (!partition.loader().equals(call.functionName)) {
            throw new Unsupported("Cannot preserve " + call.functionName + " for " + data
                    + ": the UI selects " + partition.loader() + " for this file. "
                    + "Use a file whose format and extension match the declared loader.");
        }
        partition.nameProperty().set(assignment.name);
        readTipDates(partition, call);
        readSpecies(partition, call);
        try {
            analysis.requireSupportedLoader(partition);
        } catch (IllegalStateException exception) {
            throw new Unsupported(exception.getMessage());
        }
        analysis.partitions().add(partition);
    }

    /** The species each taxon belongs to, read the same way a tip date is. */
    private void readSpecies(Partition partition, Expr.Call loader) {
        Expr species = argument(loader, "speciesName");
        if (species == null) return;

        Expr.Call parse = callOf(species, "speciesName");
        if (!"parse".equals(parse.functionName)) {
            throw new Unsupported("A species name must be read with parse(...), not "
                    + parse.functionName + ".");
        }
        partition.useSpeciesProperty().set(true);

        Expr regex = argument(parse, "regex");
        if (regex != null) {
            partition.speciesParseModeProperty().set(Partition.ParseMode.REGEX);
            partition.speciesRegexProperty().set(text(regex, "regex"));
            return;
        }
        partition.speciesParseModeProperty().set(Partition.ParseMode.SPLIT);
        Expr delimiter = argument(parse, "delimiter");
        Expr part = argument(parse, "part");
        if (delimiter != null) partition.speciesDelimiterProperty().set(text(delimiter, "delimiter"));
        if (part != null) partition.speciesPartProperty().set(text(part, "part"));
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
        if (statement instanceof Stmt.Assignment) {
            // Rate matrices and function-valued intermediates were both collected before binding
            // began: a matrix belongs to a site model, an intermediate to the argument naming it.
            return false;
        }

        // An observed draw is the likelihood, and names the structural variables its partition
        // uses. Every partition is written from one likelihood, so a script giving them different
        // ones says something the tabs cannot hold.
        if (statement instanceof Stmt.ObservedAs observed) {
            if (!(observed.stmt instanceof Stmt.Draw draw)) {
                throw new Unsupported("An observed value must be drawn from a distribution.");
            }
            Expr.Call call = callOf(draw.expression, draw.name);
            Generator chosen = overloadFor(call);
            Generator already = analysis.likelihood().generator();
            if (already != null && sawLikelihood && already != chosen) {
                throw new Unsupported("The partitions are observed under different likelihoods, "
                        + "which the tabs cannot express: they share one.");
            }
            bind(analysis.likelihood(), call, true);
            sawLikelihood = true;

            if (!(observed.observedAs instanceof Expr.Variable data)) {
                throw new Unsupported("An observation must name the alignment it is observed as.");
            }
            Wiring model;
            if (analysis.isClockMixture()) model = clockMixtureWiring(call);
            else if (analysis.isLatentObservation()) model = latentObservationWiring(call);
            else {
                String matrix = variableArgument(call, "qMatrix");
                if (argument(call, "qMatrix") instanceof Expr.Call inline) {
                    matrix = "#" + data.variableName;
                    matrices.put(matrix, inline);
                }
                model = new Wiring(variableArgument(call, "tree"), matrix,
                        variableArgument(call, "siteRates"), variableArgument(call, "branchRates"),
                        argument(call, "mutationRate"));
            }
            wiring.put(data.variableName, model);
            return true;
        }

        // Everything else drawn is either a structural variable, which the observations will point
        // at, or a prior on an estimated value, which is bound with the param that names it.
        if (statement instanceof Stmt.Draw) return false;

        throw new Unsupported("Unsupported statement in the model block: "
                + statement.getClass().getSimpleName() + ".");
    }

    /** Only the explicit latent PhyloCTMC composition can be preserved by this observation preset. */
    private Wiring latentObservationWiring(Expr.Call observation) {
        if ("ReadCountModel".equals(observation.functionName)) {
            Set<String> sampled = new LinkedHashSet<>();
            for (String name : List.of("epsilon", "delta", "t", "v", "s", "w1", "w2")) {
                String variable = variableArgument(observation, name);
                if (variable != null && draws.containsKey(variable) && !sampled.add(variable)) {
                    throw new Unsupported("Sharing sampled read-count parameters between inputs cannot be preserved by this UI.");
                }
            }
        }
        String epsilon = variableArgument(observation, "epsilon");
        if (epsilon != null && epsilon.equals(variableArgument(observation, "delta")) && draws.containsKey(epsilon)) {
            throw new Unsupported("Sharing one sampled parameter between epsilon and delta cannot be preserved by this UI.");
        }
        if (latentObservation != null && !alikeApartFrom(latentObservation, observation, "alignment")) {
            throw new Unsupported("Observation partitions must share the same error parameters in this UI.");
        }
        latentObservation = observation;
        String latent = variableArgument(observation, "alignment");
        if (latent == null || !draws.containsKey(latent)) {
            throw new Unsupported(observation.functionName + " requires a named latent alignment drawn from PhyloCTMC, not observed data.");
        }
        if (!latentAlignments.add(latent)) {
            throw new Unsupported("Sharing one latent alignment between observations cannot be preserved by this UI.");
        }
        Expr.Call call = drawnCall(latent);
        if (!"PhyloCTMC".equals(call.functionName)) {
            throw new Unsupported(observation.functionName + " currently supports only a latent PhyloCTMC alignment.");
        }
        for (Expr.Argument argument : observation.arguments) {
            if (argument instanceof Expr.DrawnArgument) {
                throw new Unsupported("Declare sampled error parameters as named variables before using this UI.");
            }
        }
        for (Expr.Argument argument : call.arguments) {
            if (argument instanceof Expr.DrawnArgument
                    || !List.of("tree", "qMatrix", "siteRates", "branchRates").contains(argument.name)
                    || variableArgument(call, argument.name) == null) {
                throw new Unsupported("The latent PhyloCTMC must use named tree, qMatrix, siteRates and branchRates; "
                        + "additional inputs cannot be preserved by this UI preset.");
            }
        }
        return new Wiring(variableArgument(call, "tree"), variableArgument(call, "qMatrix"),
                variableArgument(call, "siteRates"), variableArgument(call, "branchRates"), null);
    }

    /** Recognises only the composition the clock-averaging preset can preserve on re-export. */
    private Wiring clockMixtureWiring(Expr.Call mixture) {
        if (java.util.Arrays.stream(mixture.arguments).anyMatch(a -> a instanceof Expr.DrawnArgument)) {
            throw new Unsupported("Declare sampled mixture parameters as named variables before using the preset.");
        }
        if (clockMixture != null && !alikeApartFrom(clockMixture, mixture, "components")) {
            throw new Unsupported("The partitions must share the same mixture weights and ambiguity setting.");
        }
        clockMixture = mixture;
        Expr components = argument(mixture, "components");
        if (!(components instanceof Expr.Array array) || array.elements.size() != 2) {
            throw new Unsupported("The clock-averaging preset requires exactly two inline PhyloCTMC components.");
        }
        Expr.Call strict = callOf(array.elements.get(0), "Strict component");
        Expr.Call relaxed = callOf(array.elements.get(1), "Relaxed component");
        for (Expr.Call child : List.of(strict, relaxed)) {
            if (!"PhyloCTMC".equals(child.functionName)
                    || java.util.Arrays.stream(child.arguments).anyMatch(a -> !Set.of(
                            "tree", "qMatrix", "siteRates", "branchRates").contains(a.name)
                            || (a instanceof Expr.DrawnArgument && !"branchRates".equals(a.name)))) {
                throw new Unsupported("This preset supports PhyloCTMC children with shared tree and site-model parameters only.");
            }
        }
        String tree = variableArgument(relaxed, "tree");
        String matrix = variableArgument(relaxed, "qMatrix");
        if (tree == null || matrix == null
                || !tree.equals(variableArgument(strict, "tree"))
                || !matrix.equals(variableArgument(strict, "qMatrix"))) {
            throw new Unsupported("The clock-averaging components must share tree and qMatrix variables.");
        }
        Expr.Call strictClock = argument(strict, "branchRates") instanceof Expr.Call inline
                ? inline : clockCall(requiredVariable(strict, "branchRates"));
        Expr.Call sharedClock = clockCall(requiredVariable(relaxed, "branchRates"));
        if (!"StrictClock".equals(strictClock.functionName)
                || !Analysis.isSharedRatesClock(overloadFor(sharedClock))
                || java.util.Arrays.stream(strictClock.arguments).anyMatch(a -> !Set.of("tree", "clockRate").contains(a.name))
                || java.util.Arrays.stream(strictClock.arguments).anyMatch(a -> a instanceof Expr.DrawnArgument)
                || java.util.Arrays.stream(sharedClock.arguments).anyMatch(a -> a instanceof Expr.DrawnArgument)
                || !tree.equals(variableArgument(strictClock, "tree"))
                || !tree.equals(variableArgument(sharedClock, "tree"))) {
            throw new Unsupported("The clock-averaging components must be ordered [StrictClock, sharedRatesClock] on the same tree.");
        }
        Expr strictRate = argument(strictClock, "clockRate");
        Expr meanRate = argument(sharedClock, "meanRate");
        if (meanRate == null) meanRate = new Expr.Literal(1.0);
        if (!java.util.Objects.equals(strictRate, meanRate)) {
            throw new Unsupported("The strict clock rate must be the same expression as the shared clock meanRate.");
        }
        String strictSite = variableArgument(strict, "siteRates");
        String relaxedSite = variableArgument(relaxed, "siteRates");
        if ((argument(strict, "siteRates") != null && strictSite == null)
                || (argument(relaxed, "siteRates") != null && relaxedSite == null)
                || ((strictSite == null) != (relaxedSite == null))
                || (strictSite != null && strictSite.equals(relaxedSite))
                || (strictSite != null && !alikeApartFrom(
                        drawnCall(strictSite), drawnCall(relaxedSite), ""))) {
            throw new Unsupported("Both components must use identical site-rate calls and shared parameter expressions.");
        }
        return new Wiring(tree, matrix, relaxedSite, variableArgument(relaxed, "branchRates"), null);
    }

    private static String requiredVariable(Expr.Call call, String name) {
        String variable = variableArgument(call, name);
        if (variable == null) throw new Unsupported(name + " must name a model variable in the clock-averaging preset.");
        return variable;
    }

    /** The variable an argument names, or null if it is absent or is not a plain variable. */
    private static String variableArgument(Expr.Call call, String name) {
        Expr value = argument(call, name);
        return value instanceof Expr.Variable variable ? variable.variableName : null;
    }

    /**
     * Builds the model groups from what the observations referred to.
     *
     * <p>This is the inverse of the writer, and it reads the grouping off the script rather than
     * off the variable names: partitions naming the same {@code qMatrix} share a site model,
     * partitions naming the same tree share a tree. Names are then only names, so a script that
     * calls its tree {@code t1} loads and saves unchanged.
     *
     * <p>Two trees drawn from calls that differ only in {@code taxa} share one prior component,
     * which is what makes a single {@code populationSize} across loci survive the round trip. The
     * same applies to two branch-rate vectors whose calls differ only in {@code tree}.
     */
    private boolean buildGroups(Analysis analysis) {
        if (wiring.isEmpty()) return false;
        boolean sawModel = false;

        Map<SiteKey, SiteModel> siteModels = new LinkedHashMap<>();
        Map<String, TreeModel> trees = new LinkedHashMap<>();
        Map<String, Component> clocks = new LinkedHashMap<>();

        for (Partition partition : analysis.partitions()) {
            Wiring used = wiring.get(partition.name());
            if (used == null) {
                throw new Unsupported(partition.name() + " is loaded but never observed.");
            }

            SiteKey siteKey = new SiteKey(used.qMatrix(), used.siteRates(), used.mutationRate());
            SiteModel siteModel = siteModels.get(siteKey);
            if (siteModel == null) {
                siteModel = siteModels.isEmpty()
                        ? analysis.siteModels().get(0)
                        : new SiteModel(library, analysis.support());
                if (!siteModels.isEmpty()) analysis.siteModels().add(siteModel);
                siteModels.put(siteKey, siteModel);

                if (used.qMatrix() != null) {
                    Expr.Call call = matrices.get(used.qMatrix());
                    if (call == null) throw new Unsupported(used.qMatrix() + " is used but never assigned.");
                    bind(siteModel.substitutionModel(), call, true);
                    sawModel = true;
                }
                if (used.siteRates() != null) {
                    bind(siteModel.siteRates(), drawnCall(used.siteRates()), true);
                }
                if (used.mutationRate() != null) {
                    if (siteModel.mutationRate() == null) throw new Unsupported("Load metadata supporting mutationRate.");
                    siteModel.mutationRate().includeProperty().set(true);
                    bindValue(siteModel.mutationRate(), used.mutationRate(), true);
                }
            }
            partition.siteModelProperty().set(siteModel);

            if (used.tree() == null) throw new Unsupported("An observation names no tree.");
            TreeModel tree = trees.get(used.tree());
            if (tree == null) {
                Component prior = sharedComponent(used.tree(), trees.keySet(), "taxa",
                        name -> trees.get(name).prior());
                if (trees.isEmpty()) {
                    tree = analysis.trees().get(0);
                } else {
                    tree = new TreeModel(prior == null
                            ? Component.estimable(library, analysis.support()) : prior);
                    analysis.trees().add(tree);
                }
                tree.pinName(used.tree());
                if (prior == null) {
                    bind(tree.prior(), drawnCall(used.tree()), true);
                } else {
                    tree.priorProperty().set(prior);
                }
                trees.put(used.tree(), tree);
                sawModel = true;
            }
            partition.treeProperty().set(tree);

            String clockKey = String.valueOf(used.branchRates());
            Component clock = clocks.get(clockKey);
            if (clock == null) {
                Component shared = used.branchRates() == null ? null
                        : sharedComponent(used.branchRates(), clocks.keySet(), "tree", clocks::get);
                clock = clocks.isEmpty() ? analysis.clockModels().get(0)
                        : (shared != null ? shared : Component.estimable(library, analysis.support()));
                if (!clocks.isEmpty() && shared == null) analysis.clockModels().add(clock);
                if (shared == null && used.branchRates() != null) {
                    bind(clock, clockCall(used.branchRates()), true);
                }
                clocks.put(clockKey, clock);
            }
            partition.clockModelProperty().set(clock);
        }
        return sawModel;
    }

    /**
     * The component already built for a variable whose call differs from this one only in the given
     * structural argument, or null if there is none.
     *
     * <p>Two statements alike but for {@code taxa} are one distribution applied to two sets of
     * taxa, which is one component in the tabs and one set of parameters in the script. Telling
     * that from two coincidentally identical distributions is not possible and not needed: they
     * would be written out the same way either way.
     */
    private Component sharedComponent(String variable, Set<String> built, String structural,
            java.util.function.Function<String, Component> lookup) {
        boolean clock = "tree".equals(structural);
        Expr.Call call = clock ? clockCall(variable) : drawnCall(variable);
        for (String other : built) {
            Expr.Call otherCall = clock ? clockCall(other) : drawnCall(other);
            if (alikeApartFrom(call, otherCall, structural)) return lookup.apply(other);
        }
        return null;
    }

    private boolean alikeApartFrom(Expr.Call left, Expr.Call right, String structural) {
        if (!left.functionName.equals(right.functionName)) return false;
        Set<String> names = new LinkedHashSet<>();
        for (Expr.Argument argument : left.arguments) names.add(argument.name);
        for (Expr.Argument argument : right.arguments) names.add(argument.name);
        for (String name : names) {
            if (name.equals(structural)) continue;
            Expr leftValue = argument(left, name);
            Expr rightValue = argument(right, name);
            if (leftValue == null || rightValue == null) return false;
            // The AST compares structurally, so this is "the same argument, written the same way".
            if (!java.util.Objects.equals(leftValue, rightValue)) return false;
        }
        return true;
    }

    /** A drawn variable's call, refused rather than assumed if the script never draws it. */
    private Expr.Call drawnCall(String variable) {
        Stmt.Draw draw = draws.get(variable);
        if (draw == null) throw new Unsupported(variable + " is used but never drawn.");
        return callOf(draw.expression, variable);
    }

    /** A clock may be drawn from a distribution or assigned by a deterministic function. */
    private Expr.Call clockCall(String variable) {
        if (draws.containsKey(variable)) return drawnCall(variable);
        Expr.Call call = intermediates.get(variable);
        if (call == null) throw new Unsupported(variable + " is used but never defined.");
        claimed.add(variable);
        return call;
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
        if (value instanceof Expr.Index index) {
            bindIndex(param, index);
            return;
        }
        if (value instanceof Expr.Variable variable && vectors.containsKey(variable.variableName)) {
            if (param.canEditComponents()) {
                if (componentVectors.get(variable.variableName) == param) return;
                if (!argsEstimable || componentVectors.containsKey(variable.variableName)) {
                    vectorReferences.put(param, variable.variableName);
                    return;
                }
                componentVectors.put(variable.variableName, param);
                boundVectors.add(variable.variableName);
                Set<String> entries = new LinkedHashSet<>();
                for (Expr element : vectors.get(variable.variableName).elements) {
                    if (!(element instanceof Expr.Variable named && intermediates.containsKey(named.variableName))) {
                        throw new Unsupported("Component vector entries must name function-valued model variables.");
                    }
                    if (!entries.add(named.variableName)) {
                        throw new Unsupported("Repeated component vector entry " + named.variableName
                                + " cannot be edited as independently owned components.");
                    }
                    bindValue(param.addElement("", library, support), element, argsEstimable);
                }
                if (param.elements().isEmpty()) throw new Unsupported("An editable component vector cannot be empty.");
                return;
            }
            if (!param.canEditElements() || !boundVectors.add(variable.variableName)) {
                throw new Unsupported("Named vector " + variable.variableName
                        + " must be used by one editable numeric vector argument.");
            }
            bindValue(param, vectors.get(variable.variableName), argsEstimable);
            return;
        }
        if (value instanceof Expr.Array array && param.canEditElements()
                && array.elements.stream().anyMatch(element ->
                        element instanceof Expr.Variable variable && draws.containsKey(variable.variableName))) {
            for (Expr element : array.elements) {
                if (element instanceof Expr.Variable variable && draws.containsKey(variable.variableName)) {
                    vectorEntryDraws.add(variable.variableName);
                }
            }
            param.estimateProperty().set(false);
            for (Expr element : array.elements) bindValue(param.addElement("0", library), element, argsEstimable);
            return;
        }
        if (value instanceof Expr.Variable variable && draws.containsKey(variable.variableName)) {
            // A shared likelihood may be bound again for another partition. Its own parameter
            // is still the owner, not a second input referencing that owner.
            if (drawnParams.get(variable.variableName) == param) return;
            if (drawnParams.containsKey(variable.variableName) && param.canReference(support)) {
                param.estimateProperty().set(false);
                references.put(param, variable.variableName);
                return;
            }
            if (!param.estimable() && param.canReference(support)) {
                references.put(param, variable.variableName);
                return;
            }
            if (!param.estimable()) {
                throw new Unsupported(param.name() + " is drawn from a distribution, "
                        + "which this tab cannot express for a value of type " + param.type() + ".");
            }
            if (!boundDraws.add(variable.variableName) && vectorEntryDraws.contains(variable.variableName)) {
                throw new Unsupported("Repeated sampled vector entry " + variable.variableName
                        + " cannot be edited as independent entries without changing its prior.");
            }
            param.estimateProperty().set(true);
            param.setDrawType(typeName(draws.get(variable.variableName).type), library);
            Param previous = drawnParams.putIfAbsent(variable.variableName, param);
            if (previous != null && previous != param) {
                // Shared component bindings are handled earlier; other duplicate owners cannot
                // safely serve as the target of a later hyperparameter reference.
                ambiguousDraws.add(variable.variableName);
            }
            param.priorProperty().set(
                    priorFrom(callOf(draws.get(variable.variableName).expression, param.name()), param));
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

    /** A vector is sampled once; scalar inputs retain a live, one-based reference to it. */
    private void bindIndex(Param param, Expr.Index index) {
        if (!param.canReference(support) || !(index.object instanceof Expr.Variable variable)
                || index.indices.size() != 1 || !(index.indices.getFirst() instanceof Expr.Literal)) {
            throw new Unsupported("A vector reference needs a named sampled vector and one fixed positive integer index.");
        }
        int position;
        try {
            position = Integer.parseInt(text(index.indices.getFirst(), param.name()));
        } catch (NumberFormatException e) {
            throw new Unsupported("Vector indices must be fixed positive integers.");
        }
        if (position < 1) throw new Unsupported("Vector indices start at 1.");
        Stmt.Draw draw = draws.get(variable.variableName);
        if (draw == null || !"Vector".equals(head(draw.type))) {
            throw new Unsupported("The indexed parameter " + variable.variableName + " must be a sampled numeric vector.");
        }
        Param target = drawnParams.get(variable.variableName);
        if (target == null) {
            try {
                target = analysis.addSharedVector(variable.variableName, typeName(draw.type));
            } catch (IllegalArgumentException e) {
                throw new Unsupported(e.getMessage());
            }
            drawnParams.put(variable.variableName, target);
            boundDraws.add(variable.variableName);
            target.priorProperty().set(priorFrom(callOf(draw.expression, draw.name), target));
        }
        param.estimateProperty().set(false);
        param.referenceIndexProperty().set(position);
        references.put(param, variable.variableName);
    }

    /** A component for a nested call: a prior, a distribution-valued argument, or a function. */
    /**
     * A prior on an estimated value, built the way the tabs build one.
     *
     * <p>Not as a nested component. A nested component is given the library, which makes any
     * argument the library can produce a slot to be filled by another component rather than a value
     * to be typed: a Yule's {@code taxa} would come back as a component to choose and be written as
     * its own name rather than as the species set it holds.
     */
    private Component priorFrom(Expr.Call call, Param param) {
        Component component = Component.prior(overloadFor(call), param.dimension(), library,
                param.priorSupport(), param.isClockRates());
        bind(component, call, param.isClockRates());
        return component;
    }

    private Component nested(Expr.Call call, boolean argsEstimable) {
        Component component = Component.nested(overloadFor(call), library, argsEstimable, support);
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
            case "defaultLogEvery", "logEvery" -> analysis.logEveryProperty().set(value);
            case "outputPrefix" -> analysis.logFileProperty().set(value + ".log");
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
        return text(expression, what, false);
    }

    /**
     * Renders an expression as the text a value box holds.
     *
     * <p>A string is quoted everywhere except at the top, where the box holds the bare text and the
     * writer quotes it back on the way out. Inside a call or a list it is part of an expression the
     * box holds verbatim, so it keeps its quotes: {@code taxon(name="human")} that came back as
     * {@code taxon(name=human)} would not parse, and a species set is written exactly that way.
     */
    private static String text(Expr expression, String what, boolean quoted) {
        if (expression instanceof Expr.Literal literal) {
            if (!(literal.value instanceof String string)) return String.valueOf(literal.value);
            return quoted ? "\"" + string + "\"" : unescape(string);
        }
        if (expression instanceof Expr.Unary unary) {
            return TokenType.getLexeme(unary.operator) + text(unary.right, what, quoted);
        }
        if (expression instanceof Expr.Grouping grouping) return text(grouping.expression, what, quoted);
        if (expression instanceof Expr.Array array) {
            return array.elements.stream()
                    .map(element -> text(element, what, true))
                    .collect(Collectors.joining(", ", "[", "]"));
        }
        // A value box holds free text, so an expression typed into one is rendered straight back.
        // Whitespace is normalised in the process, which is the only thing a round trip changes.
        if (expression instanceof Expr.Variable variable) return variable.variableName;
        if (expression instanceof Expr.Binary binary) {
            return text(binary.left, what, quoted) + " " + TokenType.getLexeme(binary.operator)
                    + " " + text(binary.right, what, quoted);
        }
        if (expression instanceof Expr.Call call) {
            return call.functionName + java.util.Arrays.stream(call.arguments)
                    .map(argument -> (argument.name == null ? "" : argument.name + "=")
                            + text(argument.expression, what, true))
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
