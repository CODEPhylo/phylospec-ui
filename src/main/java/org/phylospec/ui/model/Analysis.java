package org.phylospec.ui.model;

import java.util.List;
import java.util.Map;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.phylospec.components.Argument;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.Library;

/**
 * The whole analysis being built: the data, the four model choices, and the run settings.
 *
 * <p>What each tab offers is discovered from the library by role, so an engine library's
 * components appear without being named here. The lists below only fix the order the familiar
 * ones come in, so that a BEAUti user still finds HKY at the top.
 */
public final class Analysis {

    public static final List<String> SUBSTITUTION_MODELS =
            List.of("jc69", "k80", "f81", "hky", "gtr", "wag", "jtt", "lg", "gy94", "mk");
    public static final List<String> SITE_RATE_MODELS = List.of("DiscreteGammaInv");
    public static final List<String> CLOCK_MODELS = List.of("StrictClock", "RelaxedClock");
    public static final List<String> TREE_PRIORS =
            List.of("Yule", "BirthDeath", "Coalescent", "SkylineCoalescent", "FossilizedBirthDeath");

    private static final Map<String, List<String>> PREFERRED = Map.of(
            Library.SUBSTITUTION_MODEL, SUBSTITUTION_MODELS,
            Library.SITE_RATES, SITE_RATE_MODELS,
            Library.CLOCK_MODEL, CLOCK_MODELS,
            Library.TREE_PRIOR, TREE_PRIORS);

    private final Library library;

    private final ObservableList<Partition> partitions = FXCollections.observableArrayList();
    private final Component substitutionModel;
    private final Component siteRates;
    private final Component clockModel;
    private final Component treePrior;
    private final Component likelihood;

    private final StringProperty treeName = new SimpleStringProperty("tree");
    private final StringProperty chainLength = new SimpleStringProperty("10000000");
    private final StringProperty logEvery = new SimpleStringProperty("1000");
    private final StringProperty logFile = new SimpleStringProperty("analysis.log");
    private final StringProperty seed = new SimpleStringProperty("");

    public Analysis(Library library) {
        this.library = library;
        this.substitutionModel = Component.estimable(library);
        this.siteRates = Component.estimable(library);
        this.clockModel = Component.estimable(library);
        this.treePrior = Component.estimable(library);
        this.likelihood = Component.estimable(library);
        applyDefaults();
    }

    /** The starting model, chosen to match BEAUti's: HKY, no rate heterogeneity, strict clock, Yule. */
    private void applyDefaults() {
        select(substitutionModel, "hky");
        select(clockModel, "StrictClock");
        select(treePrior, "Yule");
        select(likelihood, "PhyloCTMC");

        // BEAUti fixes the clock rate at 1.0 by default so the tree is scaled in substitutions.
        Param clockRate = clockModel.param("clockRate");
        if (clockRate != null) {
            clockRate.estimateProperty().set(false);
            clockRate.valueProperty().set("1.0");
        }
    }

    private void select(Component component, String generatorName) {
        List<Generator> overloads = library.overloads(generatorName);
        if (!overloads.isEmpty()) component.generatorProperty().set(overloads.get(0));
    }

    /**
     * Loads an alignment, ignoring one already loaded and giving it a name no other partition uses.
     * Two declarations of one name is a script error, so it is settled here rather than left for
     * the user to notice.
     */
    public void addPartition(java.nio.file.Path path) {
        if (partitions.stream().anyMatch(p -> p.fileProperty().get().equals(path.toString()))) return;

        Partition partition = new Partition(path);
        String preferred = partition.name();
        String name = preferred;
        for (int suffix = 2; isNameTaken(name); suffix++) {
            name = preferred + suffix;
        }
        partition.nameProperty().set(name);
        partitions.add(partition);
    }

    private boolean isNameTaken(String name) {
        return partitions.stream().anyMatch(partition -> name.equals(partition.name()));
    }

    /** Everything the library offers for a tab, the familiar ones first. */
    public List<Generator> choicesFor(String role) {
        List<Generator> found = library.withRole(role, PREFERRED.getOrDefault(role, List.of()));
        return Library.TREE_LIKELIHOOD.equals(role) ? observableAsTheData(found) : found;
    }

    /**
     * Likelihoods that could actually be observed as the data that is loaded. An observation is
     * invariant in PhyloSpec — an {@code Alignment<Real>} cannot be observed as an
     * {@code Alignment<Character>} — so a likelihood over the wrong kind of data is not a choice
     * the user could make, it is a script that will not validate. PhyloBM and PhyloOU are the cases:
     * they model continuous traits, and no alignment loader produces those.
     */
    private List<Generator> observableAsTheData(List<Generator> candidates) {
        String observed = elementOfTheData();
        if (observed == null) return candidates;
        return candidates.stream()
                .filter(candidate -> observed.equals(
                        Library.element(Library.inner(candidate.getGeneratedType()))))
                .toList();
    }

    /** What the first partition's loader produces, read from the library rather than assumed. */
    private String elementOfTheData() {
        if (partitions.isEmpty()) return null;
        List<Generator> loaders = library.overloads(partitions.get(0).loader());
        return loaders.isEmpty() ? null : Library.element(loaders.get(0).getGeneratedType());
    }

    /**
     * Every param across the analysis that the user has asked to estimate, including those inside
     * nested components. This is what the Priors tab lists, and it is derived rather than stored so
     * the two can never drift apart.
     */
    public List<Param> estimatedParams() {
        List<Param> estimated = new java.util.ArrayList<>();
        for (Component component : components()) {
            collectEstimated(component, estimated);
        }
        return estimated;
    }

    private static void collectEstimated(Component component, List<Param> into) {
        for (Param param : component.params()) {
            // An argument left out of the call is not part of the model, so it gets no prior.
            if (!param.includeProperty().get()) continue;
            Component nested = param.priorProperty().get();
            // A nested function's own arguments may be estimated; a prior's hyperparameters cannot.
            if (nested != null && !param.isEstimated()) collectEstimated(nested, into);
            if (param.isEstimated()) into.add(param);
        }
    }

    public Library library() {
        return library;
    }

    public ObservableList<Partition> partitions() {
        return partitions;
    }

    public Component substitutionModel() {
        return substitutionModel;
    }

    public Component siteRates() {
        return siteRates;
    }

    public Component clockModel() {
        return clockModel;
    }

    public Component treePrior() {
        return treePrior;
    }

    /**
     * The distribution the alignments are drawn from. Everything else on the tabs exists to supply
     * one of its arguments, which is why choosing it decides what the other tabs are for: a
     * likelihood with no {@code qMatrix} argument has no site model to set.
     */
    public Component likelihood() {
        return likelihood;
    }

    /** Every model slot, in the order their statements are written. */
    public List<Component> components() {
        return List.of(substitutionModel, siteRates, clockModel, treePrior, likelihood);
    }

    /** True if the chosen likelihood takes an argument of this name, so its chooser is worth showing. */
    public boolean likelihoodTakes(String argument) {
        return argumentOfLikelihood(argument) != null;
    }

    /** True if it not only takes that argument but requires it, so the chooser offers no "None". */
    public boolean likelihoodNeeds(String argument) {
        Argument declared = argumentOfLikelihood(argument);
        return declared != null && Boolean.TRUE.equals(declared.getRequired());
    }

    private Argument argumentOfLikelihood(String argument) {
        Generator chosen = likelihood.generator();
        if (chosen == null) return null;
        return chosen.getArguments().stream()
                .filter(a -> argument.equals(a.getName()))
                .findFirst()
                .orElse(null);
    }

    public StringProperty treeNameProperty() {
        return treeName;
    }

    public StringProperty chainLengthProperty() {
        return chainLength;
    }

    public StringProperty logEveryProperty() {
        return logEvery;
    }

    public StringProperty logFileProperty() {
        return logFile;
    }

    public StringProperty seedProperty() {
        return seed;
    }
}
