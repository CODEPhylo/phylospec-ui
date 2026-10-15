package org.phylospec.ui.model;

import java.util.List;

import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

/**
 * A substitution process, and the partitions that share it.
 *
 * <p>The substitution model and the site rates travel together as one unit, the way BEAUti's Site
 * Model does: unlinking gives a partition its own rate matrix <em>and</em> its own rate
 * heterogeneity, since a partition that needs one almost always needs the other.
 *
 * <p>Groups may reference shared parameters while retaining distinct matrices and mutation-rate
 * multipliers. A vector prior belongs to the analysis, not independently to each group using it.
 */
public final class SiteModel {

    private final Component substitutionModel;
    private final Component siteRates;
    private final Param mutationRate;

    public SiteModel(Library library, EngineSupport support) {
        this.substitutionModel = Component.estimable(library, support);
        this.siteRates = Component.estimable(library, support);
        var owner = library.overloads("PhyloCTMC").stream()
                .filter(g -> g.getArguments().stream().anyMatch(a -> "mutationRate".equals(a.getName())))
                .findFirst().orElse(null);
        mutationRate = owner == null ? null : new Param(owner.getArguments().stream()
                .filter(a -> "mutationRate".equals(a.getName())).findFirst().orElseThrow(),
                !Boolean.FALSE.equals(support.canBeStochastic(owner, "mutationRate")), library);
        if (mutationRate != null) {
            mutationRate.includeProperty().set(false); // Omission retains the engine's unit multiplier.
            mutationRate.allowReference(owner);
            mutationRate.estimateProperty().addListener((observable, was, now) -> {
                if (now && mutationRate.priorProperty().get() == null) {
                    var prior = library.defaultPriorFor(mutationRate.type());
                    if (prior != null) mutationRate.priorProperty().set(
                            Component.prior(prior, null, library, mutationRate.type()));
                }
            });
        }
    }

    /** The rate matrix, which becomes a {@code QMatrix} assignment. */
    public Component substitutionModel() {
        return substitutionModel;
    }

    /** Rate heterogeneity across sites, which becomes a {@code Vector<Rate>} drawn from it. */
    public Component siteRates() {
        return siteRates;
    }

    public Param mutationRate() { return mutationRate; }

    void copyRateTo(SiteModel target, Library library) {
        if (mutationRate == null || target.mutationRate == null) return;
        Param copy = target.mutationRate;
        copy.valueProperty().set(mutationRate.valueProperty().get());
        copy.includeProperty().set(mutationRate.includeProperty().get());
        copy.estimateProperty().set(mutationRate.isEstimated());
        if (mutationRate.priorProperty().get() != null) {
            var prior = mutationRate.priorProperty().get();
            var cloned = Component.prior(prior.generator(), null, library, copy.type());
            Component.copy(prior, cloned);
            copy.priorProperty().set(cloned);
        }
        copy.referenceProperty().set(mutationRate.referenceProperty().get());
        copy.referenceIndexProperty().set(mutationRate.referenceIndexProperty().get());
    }

    public List<Component> components() {
        return List.of(substitutionModel, siteRates);
    }
}
