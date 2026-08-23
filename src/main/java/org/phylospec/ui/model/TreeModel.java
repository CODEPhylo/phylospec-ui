package org.phylospec.ui.model;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * One tree in the analysis, and the prior it is drawn from.
 *
 * <p>The prior is held by reference rather than owned, because several trees can share one. That is
 * the point of the two levels: separate trees drawn from a single {@code Coalescent} give one
 * {@code populationSize} estimated across every locus,
 *
 * <pre>
 * PositiveReal theta ~ LogNormal(logMean=0.0, logSd=1.0)
 * Tree gene1Tree ~ Coalescent(populationSize=theta, taxa=taxa(gene1))
 * Tree gene2Tree ~ Coalescent(populationSize=theta, taxa=taxa(gene2))
 * </pre>
 *
 * <p>which is the multi-locus estimate, and is not something BEAUti can express without editing the
 * XML: unlinking a tree there gives it a separate prior with separate parameters. Giving each tree
 * its own prior component is the other case, and is what BEAUti does.
 *
 * <p>Note what this means for the writer: one component serves two statements whose {@code taxa}
 * argument differs, so arguments cannot be rendered once per component.
 */
public final class TreeModel {

    private final StringProperty name = new SimpleStringProperty("tree");
    private final ObjectProperty<Component> prior = new SimpleObjectProperty<>();

    /** Whether the name was chosen rather than derived, so that nothing overwrites it. */
    private boolean pinned;

    public TreeModel(Component prior) {
        this.prior.set(prior);
    }

    /** The script variable this tree is written as. */
    public StringProperty nameProperty() {
        return name;
    }

    public String name() {
        return name.get();
    }

    /**
     * Fixes the name, against a later partition being added and the trees being renamed after the
     * partitions drawn on them. A script that calls its tree {@code t1} keeps that name, so reading
     * it and writing it back gives the script it came from.
     */
    public void pinName(String chosen) {
        name.set(chosen);
        pinned = true;
    }

    public boolean pinned() {
        return pinned;
    }

    /**
     * The distribution the tree is drawn from, shared with any other tree pointing at the same
     * component. Two trees share their parameters exactly when they share this.
     */
    public ObjectProperty<Component> priorProperty() {
        return prior;
    }

    public Component prior() {
        return prior.get();
    }
}
