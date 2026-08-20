package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/**
 * Checks that a component library loaded beside core reaches the tabs and produces valid scripts.
 *
 * <p>The point of the exercise is that no UI code names BICEPS, YuleSkyline or bModelTest: they are
 * offered because of the role they fill, which is either declared by the library or inferred from
 * what the component produces. So these tests assert on what the tabs offer rather than on the
 * library's contents.
 */
class EngineLibraryTest {

    private static final Path BEAST = Path.of("libraries", "beast28.json");

    @TempDir
    static Path directory;

    private static Library core;
    private static Library withBeast;
    private static Path alignment;

    @BeforeAll
    static void loadLibrariesAndWriteAlignment() throws IOException {
        core = Library.load();
        withBeast = Library.load(List.of(BEAST));
        alignment = directory.resolve("primates.nex");
        Files.writeString(alignment, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=8;
                  format datatype=dna;
                  matrix
                    taxonA ACGTACGT
                    taxonB ACGTACGA
                    taxonC ACGTACGC
                    taxonD ACGTACGG
                  ;
                end;
                """);
    }

    private static Analysis analysisWithData(Library library) {
        Analysis analysis = new Analysis(library);
        analysis.partitions().add(new Partition(alignment));
        return analysis;
    }

    private static List<String> names(Analysis analysis, String role) {
        return analysis.choicesFor(role).stream().map(Generator::getName).distinct().toList();
    }

    // ----------------------------------------------------------------- core

    /**
     * Core declares no roles, so every tab is filled by inference alone. The pairing that matters is
     * the clock and the site rates: both produce a vector of rates, and only the tree argument tells
     * them apart.
     */
    @Test
    void coreComponentsAreSortedIntoTabsByWhatTheyProduce() {
        Analysis analysis = analysisWithData(core);

        assertEquals(List.of("StrictClock", "RelaxedClock"), names(analysis, Library.CLOCK_MODEL));
        assertEquals(List.of("DiscreteGammaInv"), names(analysis, Library.SITE_RATES));
        assertEquals(
                List.of("Yule", "BirthDeath", "Coalescent", "SkylineCoalescent", "FossilizedBirthDeath"),
                names(analysis, Library.TREE_PRIOR));
        assertTrue(names(analysis, Library.SUBSTITUTION_MODEL).containsAll(Analysis.SUBSTITUTION_MODELS));
        assertEquals(List.of("PhyloCTMC", "PhyloBM", "PhyloOU"), names(analysis, Library.TREE_LIKELIHOOD));
    }

    /** Distributions over a vector of something other than a rate are not site rates. */
    @Test
    void unrelatedVectorDistributionsFillNoTab() {
        Analysis analysis = analysisWithData(core);
        for (String role : List.of(Library.SITE_RATES, Library.CLOCK_MODEL)) {
            List<String> offered = names(analysis, role);
            assertFalse(offered.contains("IID"), role + " offered IID");
            assertFalse(offered.contains("MultivariateNormal"), role + " offered MultivariateNormal");
            assertFalse(offered.contains("ExponentialMarkovChain"), role + " offered ExponentialMarkovChain");
        }
    }

    @Test
    void coreOnLoadsOwnOffersNothingFromAnEngine() {
        Analysis analysis = analysisWithData(core);
        assertFalse(names(analysis, Library.TREE_PRIOR).contains("BICEPS"));
        assertEquals(List.of(), core.overloads("YuleSkyline"));
    }

    // --------------------------------------------------------------- engine

    /** The whole point: a library file adds tree priors without the UI naming them. */
    @Test
    void anEngineLibraryAddsToTheTreePriorTab() {
        List<String> offered = names(analysisWithData(withBeast), Library.TREE_PRIOR);
        assertTrue(offered.contains("BICEPS"), offered.toString());
        assertTrue(offered.contains("YuleSkyline"), offered.toString());

        // The familiar ones keep their BEAUti order, and the newcomers land after them.
        assertEquals(Analysis.TREE_PRIORS, offered.subList(0, Analysis.TREE_PRIORS.size()));
    }

    /** A component may declare its own role, which is how one no rule would recognise still lands. */
    @Test
    void aDeclaredRoleIsHonoured() {
        Analysis analysis = analysisWithData(withBeast);
        assertTrue(names(analysis, Library.SITE_RATES).contains("bSiteRates"));
        assertTrue(names(analysis, Library.SUBSTITUTION_MODEL).contains("nucleotideModel"));

        // A function that fills no tab is still loaded, for use as a nested argument.
        assertEquals(1, withBeast.overloads("bModelSet").size());
        assertEquals(null, withBeast.roleOf(withBeast.overloads("bModelSet").get(0)));
    }

    /**
     * An engine component has to survive validation as well as appear, which means its namespace was
     * imported into the resolver — registering the library alone would leave the name unresolvable.
     */
    @Test
    void engineTreePriorsProduceValidScripts() {
        for (String treePrior : List.of("BICEPS", "YuleSkyline")) {
            Analysis analysis = analysisWithData(withBeast);
            analysis.treePrior().generatorProperty().set(withBeast.overloads(treePrior).get(0));

            String script = ScriptWriter.write(analysis);
            assertTrue(script.contains("Tree tree ~ " + treePrior + "("), script);
            assertEquals(List.of(), Validator.validate(withBeast, script), treePrior);
        }
    }

    /**
     * bModelTest keeps the rate matrix deterministic and samples a model indicator instead, so the
     * Site Model tab has to be able to write a call with a nested function in it.
     */
    @Test
    void bModelTestRateMatrixProducesAValidScript() {
        Analysis analysis = analysisWithData(withBeast);
        Component substitution = analysis.substitutionModel();
        substitution.generatorProperty().set(withBeast.overloads("nucleotideModel").get(0));

        Param modelSet = substitution.param("modelSet");
        modelSet.priorProperty().get().param("name").valueProperty().set("transitionTransversionSplit");
        substitution.param("rates").valueProperty().set("[0.1, 0.3, 0.1, 0.1, 0.3, 0.1]");

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("QMatrix qMatrix = nucleotideModel("), script);
        assertTrue(script.contains("bModelSet(name=\"transitionTransversionSplit\")"), script);
        assertEquals(List.of(), Validator.validate(withBeast, script), script);
    }
}
