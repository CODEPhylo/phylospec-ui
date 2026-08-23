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
 * <p>This is the single-level axis. A tree is different: two partitions can have separate trees
 * drawn from one shared prior, which is how a multi-locus coalescent estimates one population size
 * across loci. Nothing analogous applies here, because two rate matrices sharing a {@code kappa}
 * would only be a different matrix if something else about them differed, and nothing does.
 */
public final class SiteModel {

    private final Component substitutionModel;
    private final Component siteRates;

    public SiteModel(Library library, EngineSupport support) {
        this.substitutionModel = Component.estimable(library, support);
        this.siteRates = Component.estimable(library, support);
    }

    /** The rate matrix, which becomes a {@code QMatrix} assignment. */
    public Component substitutionModel() {
        return substitutionModel;
    }

    /** Rate heterogeneity across sites, which becomes a {@code Vector<Rate>} drawn from it. */
    public Component siteRates() {
        return siteRates;
    }

    public List<Component> components() {
        return List.of(substitutionModel, siteRates);
    }
}
