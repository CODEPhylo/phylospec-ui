package org.phylospec.ui.model;

import java.util.List;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.phylospec.components.Generator;
import org.phylospec.ui.spec.Library;

/**
 * The whole analysis being built: the data, the four model choices, and the run settings.
 *
 * <p>Which components each tab offers is declared here rather than discovered from the library,
 * because a tab is a curated choice — "these are the substitution models" — not simply
 * "everything that returns a QMatrix".
 */
public final class Analysis {

    public static final List<String> SUBSTITUTION_MODELS =
            List.of("jc69", "k80", "f81", "hky", "gtr", "wag", "jtt", "lg", "gy94", "mk");
    public static final List<String> SITE_RATE_MODELS = List.of("DiscreteGammaInv");
    public static final List<String> CLOCK_MODELS = List.of("StrictClock", "RelaxedClock");
    public static final List<String> TREE_PRIORS =
            List.of("Yule", "BirthDeath", "Coalescent", "SkylineCoalescent", "FossilizedBirthDeath");

    private final Library library;

    private final ObservableList<Partition> partitions = FXCollections.observableArrayList();
    private final Component substitutionModel;
    private final Component siteRates;
    private final Component clockModel;
    private final Component treePrior;

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
        applyDefaults();
    }

    /** The starting model, chosen to match BEAUti's: HKY, no rate heterogeneity, strict clock, Yule. */
    private void applyDefaults() {
        select(substitutionModel, "hky");
        select(clockModel, "StrictClock");
        select(treePrior, "Yule");

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

    /** Generators offered by a tab, in the declared order. */
    public List<Generator> choicesFor(List<String> names) {
        return library.lookup(names);
    }

    /**
     * Every param across the analysis that the user has asked to estimate, including those inside
     * nested components. This is what the Priors tab lists, and it is derived rather than stored so
     * the two can never drift apart.
     */
    public List<Param> estimatedParams() {
        List<Param> estimated = new java.util.ArrayList<>();
        for (Component component : List.of(substitutionModel, siteRates, clockModel, treePrior)) {
            collectEstimated(component, estimated);
        }
        return estimated;
    }

    private static void collectEstimated(Component component, List<Param> into) {
        for (Param param : component.params()) {
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
