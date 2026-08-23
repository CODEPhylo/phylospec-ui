package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

/**
 * Reading an engine specification, and what the UI does with it.
 *
 * <p>The specification under test is the real one, generated from `integrations/beast3` by
 * phylospec's own `CreateEngineSpecification` at `21cba006`, rather than one written here to suit
 * the assertions. That matters: the awkward cases below, such as an overload collapsing onto
 * another and an argument order that disagrees with core, are properties of the generated output,
 * and a fixture written by hand would have quietly omitted them.
 */
public class EngineSupportTest {

    private static final Path BEAST2 = Path.of("engines", "beast2-2.8.0-beta4.json");

    private static Library library;
    private static EngineSupport beast2;

    @BeforeAll
    static void load() {
        library = Library.load();
        beast2 = EngineSupport.load(List.of(BEAST2));
    }

    private static Generator generator(String name, int overload) {
        List<Generator> overloads = library.overloads(name);
        assertTrue(overloads.size() > overload, name + " has no overload " + overload);
        return overloads.get(overload);
    }

    @Test
    void withNoSpecificationNothingIsRuledOut() {
        EngineSupport nothing = EngineSupport.unclaimed();

        assertFalse(nothing.claimsAnything());
        assertTrue(nothing.supports(generator("PhyloOU", 0)), "an unclaimed engine forbids nothing");
        assertNull(nothing.canBeStochastic(generator("hky", 0), "kappa"), "and has no opinion");
    }

    @Test
    void componentsTheEngineImplementsAreSupported() {
        assertTrue(beast2.supports(generator("hky", 0)));
        assertTrue(beast2.supports(generator("Yule", 0)));
        assertTrue(beast2.supports(generator("StrictClock", 0)));

        assertEquals(List.of("beast2 2.8.0-beta4"), beast2.engines());
        assertTrue(beast2.installationAdvice().get(0).contains("beast2.org"),
                beast2.installationAdvice().toString());
    }

    /**
     * The subtraction this is all for. BEAST 2 implements 51 of core's 92 components, and the UI
     * offers several of the missing ones today: PhyloBM and PhyloOU on the Likelihood tab,
     * SkylineCoalescent on the Tree Prior tab, lg and gy94 as substitution models.
     */
    @Test
    void componentsTheEngineDoesNotImplementAreNot() {
        for (String missing : List.of("PhyloBM", "PhyloOU", "SkylineCoalescent", "lg", "gy94", "mk")) {
            EngineSupport.Verdict verdict = beast2.verdictFor(generator(missing, 0));
            assertFalse(verdict.supported(), missing + " is not in the BEAST 2 specification");
            assertTrue(verdict.reason().contains(missing), verdict.reason());
            assertTrue(verdict.reason().contains("beast2 2.8.0-beta4"), verdict.reason());
        }
    }

    /**
     * The specification is generated, so it goes stale when core moves. Core gained a second
     * {@code gtr} in #76, taking joint {@code relativeRates}, and BEAST 2 implements it: against
     * the specification generated before that, this UI would have greyed out a model the engine
     * can run. Both overloads are checked so that a stale copy fails here rather than in the tabs.
     */
    @Test
    void bothGtrOverloadsAreImplemented() {
        List<Generator> gtrs = library.overloads("gtr");
        assertEquals(2, gtrs.size(), "core declares gtr with six rates and with a simplex");
        for (Generator gtr : gtrs) {
            assertTrue(beast2.supports(gtr), gtr.getArguments().get(0).getName());
        }
    }

    /**
     * Argument order is not part of a shape. Core declares PhyloCTMC as
     * {@code tree, qMatrix, siteRates, branchRates} and the generated specification lists
     * {@code tree, qMatrix, branchRates, siteRates}; a call names its arguments, so the difference
     * means nothing and matching on order would invent a disagreement.
     */
    @Test
    void argumentOrderDoesNotDecideAMatch() {
        assertTrue(beast2.supports(generator("PhyloCTMC", 0)));
    }

    /**
     * Core spells an optional argument as a second overload; a specification spells it with
     * {@code required: false}. BEAST 2 lists one {@code Yule} with an optional {@code rootAge},
     * which is both of core's {@code Yule} signatures at once. Requiring the argument lists to
     * match exactly would have called the two-argument Yule unsupported, which is wrong and would
     * have greyed out the default tree prior.
     */
    @Test
    void anOptionalArgumentCoversBothCoreOverloads() {
        List<Generator> yules = library.overloads("Yule");
        assertEquals(2, yules.size(), "core declares Yule with and without rootAge");

        for (Generator yule : yules) {
            assertTrue(beast2.supports(yule), "one specification entry covers both");
        }
    }

    /**
     * The other direction: an argument the engine has never heard of means this is not the
     * component it implements. Core's second PhyloCTMC takes {@code siteQMatrices}, which the
     * BEAST 2 entry does not offer, so only the {@code qMatrix} signature is supported.
     */
    @Test
    void anArgumentTheEngineDoesNotOfferIsNotAMatch() {
        List<Generator> likelihoods = library.overloads("PhyloCTMC");
        assertEquals(2, likelihoods.size());

        List<Boolean> supported = likelihoods.stream().map(beast2::supports).toList();
        assertEquals(List.of(true, false), supported, "only the qMatrix signature is implemented");
    }

    /**
     * The gap reported as CODEPhylo/phylospec#73, in the engine's own generated output rather than
     * in the abstract: the specification records argument names but no types, so two overloads
     * differing only in a type collapse onto one entry. Core's two Coalescent signatures both take
     * {@code populationSize} and {@code taxa}, so one specification entry matches both and the UI
     * cannot tell which one BEAST 2 actually implements. Both are offered, since refusing a model
     * an engine can run is the worse of the two errors.
     */
    @Test
    void overloadsThatDifferOnlyInTypeCannotBeToldApart() {
        List<Generator> coalescents = library.overloads("Coalescent");
        assertEquals(2, coalescents.size(), "core declares two Coalescent signatures");

        for (Generator coalescent : coalescents) {
            assertTrue(beast2.supports(coalescent), "both match the single specification entry");
        }
    }

    /**
     * The same collapse seen from the stochasticity side. The specification lists {@code exp(x)}
     * twice, once allowing a stochastic argument and once not, because two core overloads share the
     * shape. A yes from either is taken as a yes.
     */
    @Test
    void aDisagreementAboutStochasticityIsResolvedInFavourOfAllowingIt() {
        assertEquals(Boolean.TRUE, beast2.canBeStochastic(generator("exp", 0), "x"));
    }

    @Test
    void anArgumentTheEngineCannotSampleLosesItsEstimateTick() {
        assertEquals(Boolean.FALSE, beast2.canBeStochastic(generator("env", 0), "variable"),
                "the specification says env's variable cannot be stochastic");

        Analysis analysis = new Analysis(library, beast2);
        analysis.substitutionModel().generatorProperty().set(generator("hky", 0));

        Param kappa = analysis.substitutionModel().param("kappa");
        assertNotNull(kappa);
        assertTrue(kappa.estimable(), "BEAST 2 can sample hky's kappa, so the tick stays");
    }

    /**
     * Loading a specification must not change what the tabs offer. Support decides how a choice is
     * rendered, not whether it is there, so that someone else's script still reads and reports.
     */
    @Test
    void supportDoesNotRemoveChoices() {
        Analysis without = new Analysis(library);
        Analysis with = new Analysis(library, beast2);

        for (String role : List.of(Library.SUBSTITUTION_MODEL, Library.CLOCK_MODEL,
                Library.TREE_PRIOR, Library.SITE_RATES, Library.TREE_LIKELIHOOD)) {
            assertEquals(without.choicesFor(role), with.choicesFor(role), role);
        }
    }
}
