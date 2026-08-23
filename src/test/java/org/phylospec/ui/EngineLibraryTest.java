package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.phylospec.components.Argument;
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
    private static Path secondAlignment;

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

        // A second partition over the *same* taxa. The partitions share one tree, so alignments
        // with different taxon sets describe an analysis the resolver rightly doubts, and a test
        // that only needs two partitions should not accidentally build one.
        secondAlignment = directory.resolve("second.nex");
        Files.writeString(secondAlignment, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=8;
                  format datatype=dna;
                  matrix
                    taxonA ACGTTTGT
                    taxonB ACGTTTGA
                    taxonC ACGTTTGC
                    taxonD ACGTTTGG
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
        assertEquals(List.of("PhyloCTMC", "PhyloBM", "PhyloOU"),
                core.withRole(Library.TREE_LIKELIHOOD, List.of()).stream()
                        .map(Generator::getName).distinct().toList());
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
            assertEquals(List.of(), Validator.check(withBeast, script).all(), treePrior);
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
        assertEquals(List.of(), Validator.check(withBeast, script).all(), script);
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
        assertEquals(List.of(), Validator.check(withBeast, script).all(), script);
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
     * The site-rate half of bModelTest. Its two switches are indicators like the model indicator:
     * BEAUti would draw them as checkboxes, and bModelTest samples them, so ticking "average over"
     * has to turn each into a Bernoulli draw.
     *
     * <p>The Bernoulli it draws from is core's. It did not used to be: core's generated a
     * NonNegativeInteger, which cannot stand in for a Boolean switch, so this library carried an
     * overload generating {@code Distribution<Boolean>} beside it. Core gained exactly that in
     * phylospec #76, and the overload here was removed rather than left to shadow it.
     */
    @Test
    void bModelTestSiteRatesAverageOverTheirSwitches() {
        Analysis analysis = analysisWithData(withBeast);
        Component rates = analysis.siteRates();
        rates.generatorProperty().set(withBeast.overloads("bSiteRates").get(0));
        rates.param("numCategories").valueProperty().set("4");

        assertEquals(1, withBeast.overloads("Bernoulli").size(), "core's alone, since #76");
        assertEquals("Bernoulli", withBeast.defaultPriorFor("Boolean").getName());
        assertEquals("Distribution<Boolean>",
                withBeast.overloads("Bernoulli").get(0).getGeneratedType());

        for (String switchName : List.of("useShape", "useProportionInvariable")) {
            Param flag = rates.param(switchName);
            assertTrue(flag.isIndicator(), switchName + " is a model choice, not a measurement");
            flag.estimateProperty().set(true);
            assertEquals("Bernoulli", flag.priorProperty().get().name());
        }

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("Boolean useShape ~ Bernoulli("), script);
        assertTrue(script.contains("Boolean useProportionInvariable ~ Bernoulli("), script);
        assertTrue(script.contains("numSites=numSites(primates)"), script);
        assertEquals(List.of(), Validator.check(withBeast, script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(withBeast, script)));
    }

    /**
     * A vector-valued argument is as long as the library says it is — both as a literal and in the
     * prior it is drawn from. Nothing on the Dirichlet's side of the library can know that the
     * simplex it is drawing has six elements rather than four, so the argument has to say.
     */
    @Test
    void aDeclaredDimensionSizesBothTheValueAndItsPrior() {
        Component substitution = analysisWithData(withBeast).substitutionModel();
        substitution.generatorProperty().set(withBeast.overloads("nucleotideModel").get(0));

        Param rates = substitution.param("rates");
        assertEquals(6, rates.dimension());
        assertEquals("[1.0, 1.0, 1.0, 1.0, 1.0, 1.0]",
                rates.priorProperty().get().param("concentration").valueProperty().get());

        // The literal is what the tab shows once "estimate" is unticked.
        rates.estimateProperty().set(false);
        assertEquals("[0.166666666667, 0.166666666667, 0.166666666667, "
                        + "0.166666666667, 0.166666666667, 0.166666666665]",
                rates.valueProperty().get());

        Param freq = substitution.param("freq");
        assertEquals(4, freq.dimension());
        freq.estimateProperty().set(false);
        assertEquals("[0.25, 0.25, 0.25, 0.25]", freq.valueProperty().get());
    }

    /**
     * Where a library declares no length, four is the fallback: right for nucleotide frequencies and
     * a guess otherwise. Core declares none today, so its amino acid models get four base
     * frequencies where they need twenty — a gap in core, and one this UI already handles the moment
     * core closes it, as {@link #aLengthMayBeWrittenEitherWay} shows.
     */
    @Test
    void anUndeclaredLengthFallsBackToFour() {
        Argument silent = new Argument();
        silent.setName("baseFrequencies");
        silent.setType("Simplex");

        Param param = new Param(silent, false);
        assertEquals(null, param.dimension());
        assertEquals("[0.25, 0.25, 0.25, 0.25]", param.valueProperty().get());
    }

    /**
     * A simplex default has to be a simplex. BEAST's tiling accepts a sum within 1e-6 of one and
     * PhyloSpec's own Simplex asks for 1e-10, so a flat simplex of a size that does not divide one
     * exactly cannot just repeat a rounded element.
     */
    @Test
    void aFlatSimplexDefaultSumsToOne() {
        Argument argument = new Argument();
        argument.setName("frequencies");
        argument.setType("Simplex");

        for (int size : new int[] {2, 3, 4, 5, 6, 7, 9, 20, 61}) {
            argument.setDimension(size);
            Param param = new Param(argument, false);

            List<Double> elements = Arrays.stream(
                            param.valueProperty().get().replaceAll("[\\[\\]]", "").split(","))
                    .map(String::trim).map(Double::valueOf).toList();

            assertEquals(size, elements.size(), "wrong length for " + size);
            double sum = elements.stream().mapToDouble(Double::doubleValue).sum();
            assertTrue(Math.abs(sum - 1.0) <= 1e-10,
                    "a flat simplex of " + size + " summed to " + sum);
            assertTrue(elements.stream().allMatch(e -> e >= 0 && e <= 1), "not in [0, 1]: " + size);
        }
    }

    /**
     * A length may be written as the schema's {@code dimension} field or as the type language's
     * {@code num} property. Neither is checked by the resolver, so a library may use either and the
     * UI reads both.
     */
    @Test
    void aLengthMayBeWrittenEitherWay() {
        Argument field = new Argument();
        field.setName("rates");
        field.setType("Simplex");
        field.setDimension(6);

        Argument property = new Argument();
        property.setName("rates");
        property.setType("Simplex<;num=6>");

        assertEquals(6, new Param(field, false).dimension());
        assertEquals(6, new Param(property, false).dimension());
        assertEquals(new Param(field, false).valueProperty().get(),
                new Param(property, false).valueProperty().get());

        // A length that depends on the rest of the model is not a number, and is left alone.
        Argument expression = new Argument();
        expression.setName("branchRates");
        expression.setType("Vector<Rate; num=tree.numBranches>");
        expression.setDimension("tree.numBranches");
        assertEquals(null, new Param(expression, false).dimension());
    }

    /**
     * An observation is invariant, so a likelihood over continuous traits cannot be observed as a
     * nucleotide alignment. Offering it would be offering a script that cannot validate.
     */
    @Test
    void onlyLikelihoodsMatchingTheDataAreOffered() {
        Analysis empty = new Analysis(core);
        assertEquals(List.of("PhyloCTMC", "PhyloBM", "PhyloOU"), names(empty, Library.TREE_LIKELIHOOD),
                "with no data loaded there is nothing to match against");

        assertEquals(List.of("PhyloCTMC"), names(analysisWithData(core), Library.TREE_LIKELIHOOD),
                "PhyloBM and PhyloOU model continuous traits, which no loader produces");
    }

    /**
     * The point of making the likelihood a choice: SNAPP takes no rate matrix, no site rates and no
     * clock, so those tabs have nothing to set and their models must not be written.
     */
    @Test
    void aLikelihoodWithoutASiteModelWritesNone() {
        Analysis analysis = analysisWithData(withBeast);
        assertTrue(names(analysis, Library.TREE_LIKELIHOOD).contains("SNAPP"));

        analysis.likelihood().generatorProperty().set(withBeast.overloads("SNAPP").get(0));
        for (String argument : List.of("qMatrix", "siteRates", "branchRates")) {
            assertFalse(analysis.likelihoodTakes(argument), "SNAPP takes no " + argument);
        }

        // What the UI does when those tabs go away.
        analysis.substitutionModel().generatorProperty().set(null);
        analysis.siteRates().generatorProperty().set(null);
        analysis.clockModel().generatorProperty().set(null);

        String script = ScriptWriter.write(analysis);
        assertFalse(script.contains("qMatrix"), script);
        assertFalse(script.contains("branchRates"), script);
        assertTrue(script.contains("~ SNAPP("), script);
        assertTrue(script.contains(") observed as primates"), script);
        assertEquals(List.of(), Validator.check(withBeast, script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(withBeast, script)));
    }

    /** A likelihood's own arguments are edited and estimated like any other component's. */
    @Test
    void aLikelihoodsOwnArgumentsAreEstimable() {
        Analysis analysis = analysisWithData(withBeast);
        analysis.likelihood().generatorProperty().set(withBeast.overloads("SNAPP").get(0));

        assertEquals(List.of("theta", "mutationRateU", "mutationRateV",
                        "nonPolymorphic", "mutationOnlyAtRoot", "dominant"),
                analysis.likelihood().params().stream().map(Param::name).toList());
        assertTrue(analysis.estimatedParams().stream().map(Param::name).toList()
                .containsAll(List.of("mutationRateU", "mutationRateV")),
                "the required rates are estimated by default");

        assertTrue(analysis.likelihoodNeeds("theta"));
        assertFalse(analysis.likelihoodNeeds("dominant"));
    }

    /**
     * SNAPP's theta and coalescenceRate are XOR inputs in BEAST, which is two signatures here. Both
     * have to write and read back, and the reader has to tell them apart — they differ only in the
     * name of that one argument.
     */
    @Test
    void bothSnappParameterisationsRoundTrip() {
        List<Generator> overloads = withBeast.overloads("SNAPP");
        assertEquals(2, overloads.size());

        for (Generator overload : overloads) {
            Analysis analysis = analysisWithData(withBeast);
            analysis.likelihood().generatorProperty().set(overload);
            analysis.substitutionModel().generatorProperty().set(null);
            analysis.siteRates().generatorProperty().set(null);
            analysis.clockModel().generatorProperty().set(null);

            String parameterisation = overload.getArguments().get(1).getName();
            String script = ScriptWriter.write(analysis);
            assertTrue(script.contains(parameterisation + "="), script);
            assertEquals(List.of(), Validator.check(withBeast, script).all(), parameterisation);

            Analysis reloaded = ScriptReader.read(withBeast, script);
            assertEquals(overload, reloaded.likelihood().generator(), parameterisation);
            assertEquals(script, ScriptWriter.write(reloaded), parameterisation);
        }
    }

    /**
     * Every partition is observed under the same likelihood, so a script that gives two of them
     * different ones says something the tabs cannot hold. SNAPP's two parameterisations make the
     * case with two calls that are each perfectly valid.
     */
    @Test
    void differingLikelihoodsAcrossPartitionsAreRefused() {
        Analysis analysis = analysisWithData(withBeast);
        analysis.addPartition(secondAlignment);
        analysis.likelihood().generatorProperty().set(withBeast.overloads("SNAPP").get(0));
        analysis.substitutionModel().generatorProperty().set(null);
        analysis.siteRates().generatorProperty().set(null);
        analysis.clockModel().generatorProperty().set(null);

        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(withBeast, script).all(), script);

        int last = script.lastIndexOf("theta=");
        String mixed = script.substring(0, last) + "coalescenceRate=" + script.substring(last + 6);
        assertEquals(List.of(), Validator.check(withBeast, mixed).all(), "both calls must be valid");

        ScriptReader.Unsupported refusal = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(withBeast, mixed));
        assertTrue(refusal.getMessage().contains("different likelihoods"), refusal.getMessage());
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
