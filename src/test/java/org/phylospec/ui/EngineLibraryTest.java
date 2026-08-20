package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.phylospec.ui.spec.ScriptReader;
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

    /**
     * Engine components land on the right tab with nothing declared: what a component produces is
     * already what decides where it belongs, and no component of this library says otherwise.
     */
    @Test
    void engineComponentsArePlacedByWhatTheyProduce() {
        Analysis analysis = analysisWithData(withBeast);
        assertTrue(names(analysis, Library.SITE_RATES).contains("bSiteRates"));
        assertTrue(names(analysis, Library.SUBSTITUTION_MODEL).contains("nucleotideModel"));

        for (String name : List.of("BICEPS", "YuleSkyline", "nucleotideModel", "bSiteRates")) {
            Generator generator = withBeast.overloads(name).get(0);
            assertEquals(null, generator.getAdditionalProperties().get("role"),
                    name + " declares a role that its generated type already says");
        }

        // A function that fills no tab is still loaded, for use as a nested argument.
        assertEquals(1, withBeast.overloads("bModelSet").size());
        assertEquals(null, withBeast.roleOf(withBeast.overloads("bModelSet").get(0)));
    }

    /**
     * The one thing a generated type cannot say is that a component fills a slot the UI does not
     * have yet. StarBEAST's gene trees are the case in point: a gene tree is drawn from a
     * distribution over a Tree like any other, but it does not belong on the Tree Prior tab.
     */
    @Test
    void aDeclaredRoleOverridesWhatTheTypeWouldSay(@TempDir Path scratch) throws IOException {
        Path library = scratch.resolve("starbeast.json");
        Files.writeString(library, """
                {"componentLibrary": {
                  "name": "role override", "version": "0.1.0",
                  "engine": "BEAST", "engineVersion": "2.8.0",
                  "description": "One generator that asks not to be a tree prior.",
                  "types": [],
                  "generators": [{
                    "name": "MultispeciesCoalescent",
                    "namespace": "beast.evolution.speciation",
                    "description": "A gene tree within a species tree.",
                    "generatedType": "Distribution<Tree<;numTaxa=taxa.num>>",
                    "role": "geneTreePrior",
                    "arguments": [
                      {"name": "speciesTree", "type": "Tree", "required": true,
                       "description": "The species tree."},
                      {"name": "populationSizes", "type": "Vector<PositiveReal>", "required": true,
                       "description": "Population size per species-tree branch."},
                      {"name": "taxa", "type": "Taxa", "required": true,
                       "description": "The taxa of the gene tree."}
                    ]}]}}
                """);

        Library loaded = Library.load(List.of(library));
        Generator geneTree = loaded.overloads("MultispeciesCoalescent").get(0);

        assertEquals("geneTreePrior", loaded.roleOf(geneTree));
        assertFalse(loaded.withRole(Library.TREE_PRIOR, List.of()).contains(geneTree),
                "a declared role must keep it off the Tree Prior tab");
        assertEquals(List.of(geneTree), loaded.withRole("geneTreePrior", List.of()));
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
     * bModelTest keeps the rate matrix deterministic and samples a model indicator instead. The
     * model set is a function-valued argument, so it becomes a statement of its own rather than a
     * call nested inside the one that uses it.
     */
    @Test
    void bModelTestRateMatrixProducesAValidScript() {
        String script = ScriptWriter.write(bModelTestAnalysis());
        assertTrue(script.contains("BModelSet modelSet = bModelSet(name=\"transitionTransversionSplit\")"),
                script);
        assertTrue(script.contains("modelSet=modelSet"), script);
        assertEquals(List.of(), Validator.validate(withBeast, script), script);
    }

    /**
     * The indicator is what a bModelTest run is for, so it has to be samplable even though it is an
     * integer, and its prior has to be able to reach the model set — which is the whole reason the
     * model set needed a name.
     */
    @Test
    void averagingOverModelsProducesAValidScript() {
        Analysis analysis = bModelTestAnalysis();
        Param indicator = analysis.substitutionModel().param("modelIndicator");

        assertTrue(indicator.estimable(), "a model indicator must be samplable");
        assertTrue(indicator.isIndicator(), "and must say that it is averaged over, not estimated");

        indicator.estimateProperty().set(true);
        Component prior = indicator.priorProperty().get();
        assertEquals("DiscreteUniform", prior.name());
        prior.param("lower").valueProperty().set("0");
        prior.param("upper").valueProperty().set("size(modelSet) - 1");

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Integer modelIndicator ~ DiscreteUniform("), script);
        assertTrue(script.contains("upper=size(modelSet) - 1"), script);
        assertEquals(List.of(), Validator.validate(withBeast, script), script);
    }

    /** The script survives a trip back onto the tabs, intermediate, indicator and all. */
    @Test
    void bModelTestScriptRoundTrips() {
        Analysis analysis = bModelTestAnalysis();
        Param indicator = analysis.substitutionModel().param("modelIndicator");
        indicator.estimateProperty().set(true);
        indicator.priorProperty().get().param("lower").valueProperty().set("0");
        indicator.priorProperty().get().param("upper").valueProperty().set("size(modelSet) - 1");

        String written = ScriptWriter.write(analysis);
        assertEquals(written, ScriptWriter.write(ScriptReader.read(withBeast, written)));
    }

    /** A named intermediate nothing refers to has nowhere to live on the tabs, so it is refused. */
    @Test
    void anUnusedIntermediateIsRefused() {
        Analysis analysis = bModelTestAnalysis();
        String written = ScriptWriter.write(analysis).replace(
                "BModelSet modelSet = bModelSet",
                "BModelSet spare = bModelSet(name=\"namedSimple\")\n    BModelSet modelSet = bModelSet");

        ScriptReader.Unsupported refusal = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(withBeast, written));
        assertTrue(refusal.getMessage().contains("spare"), refusal.getMessage());
    }

    /**
     * The site-rate half of bModelTest. Its two switches stay literal: BEAUti would draw them as
     * checkboxes, and bModelTest samples them, but core has no distribution over Boolean for the
     * `~` statement to use — so "average over" has nothing to offer them yet.
     */
    @Test
    void bModelTestSiteRatesProduceAValidScript() {
        Analysis analysis = analysisWithData(withBeast);
        Component rates = analysis.siteRates();
        rates.generatorProperty().set(withBeast.overloads("bSiteRates").get(0));
        rates.param("numCategories").valueProperty().set("4");

        assertEquals(List.of(), withBeast.priorsFor("Boolean"), "core gained a Boolean distribution");
        assertFalse(rates.param("useShape").estimable());

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Vector<Rate> siteRates ~ bSiteRates("), script);
        assertTrue(script.contains("numSites=numSites(primates)"), script);
        assertEquals(List.of(), Validator.validate(withBeast, script), script);
    }

    private static Analysis bModelTestAnalysis() {
        Analysis analysis = analysisWithData(withBeast);
        Component substitution = analysis.substitutionModel();
        substitution.generatorProperty().set(withBeast.overloads("nucleotideModel").get(0));

        substitution.param("modelSet").priorProperty().get()
                .param("name").valueProperty().set("transitionTransversionSplit");
        substitution.param("rates").valueProperty().set("[0.1, 0.3, 0.1, 0.1, 0.3, 0.1]");
        return analysis;
    }
}
