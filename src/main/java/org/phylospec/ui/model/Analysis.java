package org.phylospec.ui.model;

import java.util.List;
import java.util.Map;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.phylospec.components.Argument;
import org.phylospec.components.Generator;
import org.phylospec.ui.spec.EngineSupport;
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
    private final EngineSupport support;

    private final ObservableList<Partition> partitions = FXCollections.observableArrayList();

    // The model groups. A group serves every partition pointing at it, so "linked" is one object
    // with several referrers and "unlinked" is a copy of its own. One of each to begin with, which
    // is BEAUti's state immediately after import.
    private final ObservableList<SiteModel> siteModels = FXCollections.observableArrayList();
    private final ObservableList<Component> clockModels = FXCollections.observableArrayList();
    private final ObservableList<TreeModel> trees = FXCollections.observableArrayList();

    /**
     * The distinct tree priors, kept in step with the trees.
     *
     * <p>A tree prior can be shared or separated without the tree list changing at all, so a panel
     * watching the trees alone would miss it. This is the list the Tree Prior tab watches.
     */
    private final ObservableList<Component> treePriors = FXCollections.observableArrayList();

    private final Component likelihood;
    private final StringProperty chainLength = new SimpleStringProperty("10000000");
    private final StringProperty logEvery = new SimpleStringProperty("1000");
    private final StringProperty logFile = new SimpleStringProperty("analysis.log");
    private final StringProperty seed = new SimpleStringProperty("");

    public Analysis(Library library) {
        this(library, EngineSupport.unclaimed());
    }

    public Analysis(Library library, EngineSupport support) {
        this.library = library;
        this.support = support;
        this.siteModels.add(new SiteModel(library, support));
        this.clockModels.add(Component.estimable(library, support));
        this.trees.add(new TreeModel(Component.estimable(library, support)));
        this.treePriors.add(this.trees.get(0).prior());
        this.likelihood = Component.estimable(library, support);

        // A partition can arrive through addPartition, through a drop on the Partitions tab, or
        // straight onto the list. Attaching the models here covers all three, so no caller has to
        // remember, and a partition is never half-built.
        partitions.addListener((javafx.collections.ListChangeListener<Partition>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(this::attachModels);
            }
            renameTrees();
        });
        applyDefaults();
    }

    /** The starting model, chosen to match BEAUti's: HKY, no rate heterogeneity, strict clock, Yule. */
    private void applyDefaults() {
        select(substitutionModel(), "hky");
        select(clockModel(), "StrictClock");
        select(treePrior(), "Yule");
        select(likelihood, "PhyloCTMC");

        // BEAUti fixes the clock rate at 1.0 by default so the tree is scaled in substitutions.
        Param clockRate = clockModel().param("clockRate");
        if (clockRate != null) {
            clockRate.estimateProperty().set(false);
            clockRate.valueProperty().set("1.0");
        }
    }

    /** Points a partition at the first group of each kind, which is what "linked" starts as. */
    private void attachModels(Partition partition) {
        if (partition.siteModel() == null) partition.siteModelProperty().set(siteModels.get(0));
        if (partition.clockModel() == null) partition.clockModelProperty().set(clockModels.get(0));
        if (partition.tree() == null) partition.treeProperty().set(trees.get(0));
    }

    /** Applies the same default choices to a group that has just been unlinked. */
    private void applyDefaultsTo(SiteModel model) {
        select(model.substitutionModel(), "hky");
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

    /** What the loaded engines can run. Claims nothing unless a specification was given. */
    public EngineSupport support() {
        return support;
    }

    public ObservableList<Partition> partitions() {
        return partitions;
    }

    /** The site models in use, one per group of partitions sharing a substitution process. */
    public ObservableList<SiteModel> siteModels() {
        return siteModels;
    }

    /** The clock models in use. */
    public ObservableList<Component> clockModels() {
        return clockModels;
    }

    /** The trees in the analysis. Several may share one prior, and so one set of parameters. */
    public ObservableList<TreeModel> trees() {
        return trees;
    }

    /**
     * The first site model's substitution model.
     *
     * <p>The tabs edit one group at a time, and with everything linked there is only one. These
     * shorthands are what the tabs bind to, and what a linked analysis means by "the" site model.
     */
    public Component substitutionModel() {
        return siteModels.get(0).substitutionModel();
    }

    public Component siteRates() {
        return siteModels.get(0).siteRates();
    }

    public Component clockModel() {
        return clockModels.get(0);
    }

    public Component treePrior() {
        return trees.get(0).prior();
    }

    // ------------------------------------------------------------- linking

    /**
     * Gives a partition a site model of its own, copied from the one it shares now.
     *
     * <p>A copy rather than a fresh default: unlinking is usually the prelude to changing one
     * thing about one partition, so starting from what was already chosen loses nothing and asks
     * the user for less.
     */
    public SiteModel unlinkSiteModel(Partition partition) {
        SiteModel copy = new SiteModel(library, support);
        Component.copy(partition.siteModel().substitutionModel(), copy.substitutionModel());
        Component.copy(partition.siteModel().siteRates(), copy.siteRates());
        siteModels.add(copy);
        partition.siteModelProperty().set(copy);
        discardUnusedSiteModels();
        return copy;
    }

    /** Points a partition at another partition's site model, and drops any group left empty. */
    public void linkSiteModel(Partition partition, SiteModel target) {
        partition.siteModelProperty().set(target);
        discardUnusedSiteModels();
    }

    /** Gives a partition a clock model of its own, copied from the one it shares now. */
    public Component unlinkClockModel(Partition partition) {
        Component copy = Component.estimable(library, support);
        Component.copy(partition.clockModel(), copy);
        clockModels.add(copy);
        partition.clockModelProperty().set(copy);
        discardUnusedClockModels();
        return copy;
    }

    public void linkClockModel(Partition partition, Component target) {
        partition.clockModelProperty().set(target);
        discardUnusedClockModels();
    }

    /**
     * Gives a partition a tree of its own.
     *
     * <p>The new tree keeps the <em>same</em> prior component by default, which is the two-level
     * part: separate trees drawn from one {@code Coalescent} share its {@code populationSize},
     * which is the multi-locus estimate. {@link #unlinkTreePrior} separates the parameters as well.
     */
    public TreeModel unlinkTree(Partition partition) {
        TreeModel tree = new TreeModel(partition.tree().prior());
        trees.add(tree);
        refreshTreePriors();
        partition.treeProperty().set(tree);
        discardUnusedTrees();
        renameTrees();
        return tree;
    }

    public void linkTree(Partition partition, TreeModel target) {
        partition.treeProperty().set(target);
        discardUnusedTrees();
        renameTrees();
    }

    /** Gives a tree a prior of its own, copied from the one it shares now, separating parameters. */
    public Component unlinkTreePrior(TreeModel tree) {
        Component copy = Component.estimable(library, support);
        Component.copy(tree.prior(), copy);
        tree.priorProperty().set(copy);
        refreshTreePriors();
        return copy;
    }

    /** Points a tree at another tree's prior, so the two share every parameter of it. */
    public void linkTreePrior(TreeModel tree, TreeModel target) {
        tree.priorProperty().set(target.prior());
        refreshTreePriors();
    }

    /** The partitions using a site model, in table order, for naming a group on screen. */
    public List<Partition> partitionsUsing(SiteModel model) {
        return partitions.stream().filter(partition -> partition.siteModel() == model).toList();
    }

    /** The partitions using a clock model. */
    public List<Partition> partitionsUsing(Component clock) {
        return partitions.stream().filter(partition -> partition.clockModel() == clock).toList();
    }

    /** The trees drawn from a prior, which is more than one exactly when they share parameters. */
    public List<TreeModel> treesUsing(Component prior) {
        return trees.stream().filter(tree -> tree.prior() == prior).toList();
    }

    private void discardUnusedSiteModels() {
        siteModels.removeIf(model -> siteModels.size() > 1 && partitions.stream()
                .noneMatch(partition -> partition.siteModel() == model));
    }

    private void discardUnusedClockModels() {
        clockModels.removeIf(model -> clockModels.size() > 1 && partitions.stream()
                .noneMatch(partition -> partition.clockModel() == model));
    }

    /** The tree priors in use, in tree order, without repeating one that two trees share. */
    private void refreshTreePriors() {
        List<Component> distinct = new java.util.ArrayList<>();
        for (TreeModel tree : trees) {
            if (distinct.stream().noneMatch(seen -> seen == tree.prior())) distinct.add(tree.prior());
        }
        if (!sameOrder(distinct, treePriors)) treePriors.setAll(distinct);
    }

    private static boolean sameOrder(List<Component> left, List<Component> right) {
        if (left.size() != right.size()) return false;
        for (int at = 0; at < left.size(); at++) {
            if (left.get(at) != right.get(at)) return false;
        }
        return true;
    }

    /** The tree priors in use, one per set of trees sharing parameters. */
    public ObservableList<Component> treePriors() {
        return treePriors;
    }

    private void discardUnusedTrees() {
        trees.removeIf(tree -> trees.size() > 1 && partitions.stream()
                .noneMatch(partition -> partition.tree() == tree));
        refreshTreePriors();
    }

    /**
     * Names each tree after the first partition drawn on it, so a script reads {@code gene1Tree}
     * rather than {@code tree2}. A single tree keeps whatever name it has, which is the plain
     * {@code tree} the writer has always used, and which the user may have changed.
     */
    private void renameTrees() {
        if (trees.size() == 1) {
            // Back to one tree, so back to the plain name. Otherwise relinking would leave the
            // last unlink's name behind and the script would not be the one it started as.
            TreeModel only = trees.get(0);
            if (!only.pinned()) only.nameProperty().set("tree");
            return;
        }
        for (TreeModel tree : trees) {
            if (tree.pinned()) continue;
            partitions.stream()
                    .filter(partition -> partition.tree() == tree)
                    .findFirst()
                    .ifPresent(partition -> tree.nameProperty().set(partition.name() + "Tree"));
        }
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
        List<Component> all = new java.util.ArrayList<>();
        for (SiteModel model : siteModels) all.addAll(model.components());
        all.addAll(clockModels);
        // Trees can share a prior, and a component visited twice would have its params counted
        // twice: once as a prior to list and once as a statement to write.
        for (TreeModel tree : trees) {
            if (all.stream().noneMatch(seen -> seen == tree.prior())) all.add(tree.prior());
        }
        all.add(likelihood);
        return all;
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

    /** The name of the first tree, which is the only one unless trees have been unlinked. */
    public StringProperty treeNameProperty() {
        return trees.get(0).nameProperty();
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
