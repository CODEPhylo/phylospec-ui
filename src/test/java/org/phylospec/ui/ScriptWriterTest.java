package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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
 * Checks that the GUI can only produce scripts the reference implementation accepts.
 *
 * <p>The interesting failure mode of a script generator is emitting something that does not parse or
 * does not type-check, so every model the tabs can express is generated and run through the real
 * PhyloSpec parser and type resolver. The tabs vary along four axes — which generator fills each
 * model slot, which of its arguments are estimated, which prior each estimated argument draws from,
 * and whether an optional argument is included — and there is a test for each.
 */
class ScriptWriterTest {

    /** Deep enough for a coalescent's population function and that function's own arguments. */
    private static final int MAX_NESTING = 4;

    @TempDir
    static Path directory;

    private static Library library;
    private static Path alignment;

    @BeforeAll
    static void loadLibraryAndWriteAlignment() throws IOException {
        library = Library.load();
        alignment = directory.resolve("primates.nex");
        Files.writeString(alignment, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=8;
                  format datatype=dna;
                  matrix
                    taxonA_1990 ACGTACGT
                    taxonB_1991 ACGTACGA
                    taxonC_1992 ACGTACGC
                    taxonD_1993 ACGTACGG
                  ;
                end;
                """);
    }

    private Analysis analysisWithData() {
        Analysis analysis = new Analysis(library);
        analysis.partitions().add(new Partition(alignment));
        return analysis;
    }

    @Test
    void readsTaxonAndSiteCountsFromNexus() {
        Partition partition = analysisWithData().partitions().get(0);
        assertEquals(4, partition.taxaProperty().get());
        assertEquals(8, partition.sitesProperty().get());
        assertEquals("fromNexus", partition.loader());
        assertEquals("primates", partition.name());
    }

    @Test
    void defaultAnalysisIsValid() {
        Analysis analysis = analysisWithData();
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all());
        assertTrue(script.contains("Tree tree ~ Yule("), script);
        assertTrue(script.contains("observed as primates"), script);
    }

    @Test
    void tipDatesBecomeAParseArgument() {
        Analysis analysis = analysisWithData();
        analysis.partitions().get(0).useTipDatesProperty().set(true);
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("age=parse(delimiter=\"_\", part=2)"), script);
        assertEquals(List.of(), Validator.check(library, script).all());
    }

    /** The bundled BEAST datasets must all be readable and produce a valid script. */
    @Test
    void bundledExamplesLoadAndValidate() {
        record Expected(String file, String variable, String loader, int taxa, int sites) {}
        List<Expected> examples = List.of(
                new Expected("Primates.nex", "primates", "fromNexus", 12, 898),
                new Expected("dna.nex", "dna", "fromNexus", 10, 705),
                new Expected("anolis.nex", "anolis", "fromNexus", 29, 1456),
                new Expected("Flu.nex", "flu", "fromNexus", 21, 1698),
                new Expected("Dengue4.env.nex", "dengue4env", "fromNexus", 17, 1485),
                new Expected("RSV2.nex", "rsv2", "fromNexus", 129, 629),
                new Expected("dna.fasta", "dna", "fromFasta", 10, 705),
                new Expected("aminoacid.fasta", "aminoacid", "fromFasta", 10, 234));

        for (Expected expected : examples) {
            Path file = Path.of("examples", expected.file());
            assertTrue(Files.isRegularFile(file), "missing example: " + file);

            Analysis analysis = new Analysis(library);
            analysis.addPartition(file);
            Partition partition = analysis.partitions().get(0);

            assertEquals(expected.variable(), partition.name(), expected.file());
            assertEquals(expected.loader(), partition.loader(), expected.file());
            assertEquals(expected.taxa(), partition.taxaProperty().get(), expected.file() + " taxa");
            assertEquals(expected.sites(), partition.sitesProperty().get(), expected.file() + " sites");
            assertEquals(List.of(), Validator.check(library, ScriptWriter.write(analysis)).all(), expected.file());
        }
    }

    /** Two files wanting the same variable name must not collide in the script. */
    @Test
    void clashingPartitionNamesAreMadeUnique() {
        Analysis analysis = new Analysis(library);
        analysis.addPartition(Path.of("examples", "dna.nex"));
        analysis.addPartition(Path.of("examples", "dna.fasta"));

        assertEquals(List.of("dna", "dna2"),
                analysis.partitions().stream().map(Partition::name).toList());
        assertEquals(List.of(), Validator.check(library, ScriptWriter.write(analysis)).all());
    }

    /** Every combination the tabs can express must parse and type-check. */
    @Test
    void everyModelCombinationIsValid() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (String substitution : Analysis.SUBSTITUTION_MODELS) {
            for (String clock : Analysis.CLOCK_MODELS) {
                for (String treePrior : Analysis.TREE_PRIORS) {
                    for (boolean gammaRates : new boolean[] {false, true}) {
                        int overloads = library.overloads(treePrior).size();
                        for (int overload = 0; overload < overloads; overload++) {
                            Analysis analysis = analysisWithData();
                            analysis.substitutionModel().generatorProperty()
                                    .set(library.overloads(substitution).get(0));
                            analysis.clockModel().generatorProperty().set(library.overloads(clock).get(0));
                            analysis.treePrior().generatorProperty()
                                    .set(library.overloads(treePrior).get(overload));
                            if (gammaRates) {
                                analysis.siteRates().generatorProperty()
                                        .set(library.overloads("DiscreteGammaInv").get(0));
                                Param categories = analysis.siteRates().param("numCategories");
                                if (categories != null) categories.valueProperty().set("4");
                            }

                            String script = ScriptWriter.write(analysis);
                            List<String> problems = Validator.check(library, script).all();
                            checked++;
                            if (!problems.isEmpty()) {
                                failures.add(substitution + " + " + clock + " + " + treePrior
                                        + "#" + overload + (gammaRates ? " + gamma" : "")
                                        + " -> " + problems.get(0));
                            }
                        }
                    }
                }
            }
        }

        assertTrue(checked > 300, "expected the tabs to express many models, got " + checked);
        assertEquals(List.of(), failures);
    }

    // ------------------------------------------------------- estimates and priors

    /** One model tab: the generators it offers, and the component it edits. */
    private record Slot(String label, List<String> generators, Function<Analysis, Component> component) {}

    private static final List<Slot> SLOTS = List.of(
            new Slot("site model", Analysis.SUBSTITUTION_MODELS, Analysis::substitutionModel),
            new Slot("site rates", Analysis.SITE_RATE_MODELS, Analysis::siteRates),
            new Slot("clock model", Analysis.CLOCK_MODELS, Analysis::clockModel),
            new Slot("tree prior", Analysis.TREE_PRIORS, Analysis::treePrior));

    /**
     * Ticking "estimate" replaces a literal with a random variable and adds a {@code ~} statement, so
     * it changes the shape of the model block rather than just a value. Every tick the tabs offer is
     * flipped from its default, one at a time and then all together.
     */
    @Test
    void everyEstimateTickIsValid() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Slot slot : SLOTS) {
            List<Generator> offered = library.lookup(slot.generators());
            for (Generator generator : offered) {
                String label = slot.label() + " " + Component.describe(generator, offered);

                for (int index = 0; index < estimableCount(slot, generator); index++) {
                    Analysis analysis = analysisWith(slot, generator);
                    Param param = estimable(slot.component().apply(analysis)).get(index);
                    boolean was = param.isEstimated();
                    param.estimateProperty().set(!was);
                    checked++;
                    check(failures, analysis, label + ": " + param.name() + (was ? " fixed" : " estimated"));
                }

                for (boolean all : new boolean[] {true, false}) {
                    Analysis analysis = analysisWith(slot, generator);
                    for (Param param : estimable(slot.component().apply(analysis))) {
                        param.estimateProperty().set(all);
                    }
                    checked++;
                    check(failures, analysis, label + ": everything " + (all ? "estimated" : "fixed"));
                }
            }
        }

        assertTrue(checked > 50, "expected many estimate combinations, got " + checked);
        assertEquals(List.of(), failures);
    }

    /**
     * The Priors tab offers every distribution whose support fits the value being estimated. Choosing
     * any of them must still type-check — the support test is the GUI's own, so this is where it would
     * show up if it were more permissive than the type resolver.
     */
    @Test
    void everyPriorChoiceIsValid() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Slot slot : SLOTS) {
            List<Generator> offered = library.lookup(slot.generators());
            for (Generator generator : offered) {
                String label = slot.label() + " " + Component.describe(generator, offered);

                for (int index = 0; index < estimableCount(slot, generator); index++) {
                    Analysis probe = analysisWith(slot, generator);
                    Param probed = estimable(slot.component().apply(probe)).get(index);
                    if (!probed.estimable()) continue;

                    for (Generator prior : library.priorsFor(probed.priorSupport())) {
                        Analysis analysis = analysisWith(slot, generator);
                        Param param = estimable(slot.component().apply(analysis)).get(index);
                        param.estimateProperty().set(true);
                        // The Priors tab builds the chosen distribution exactly this way.
                        param.priorProperty().set(Component.nested(prior, library, false));
                        checked++;
                        check(failures, analysis, label + ": " + param.name() + " ~ " + prior.getName());
                    }
                }
            }
        }

        assertTrue(checked > 100, "expected many prior combinations, got " + checked);
        assertEquals(List.of(), failures);
    }

    /** Optional arguments can be left out, which drops them from the call. */
    @Test
    void everyOptionalArgumentCanBeDropped() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Slot slot : SLOTS) {
            List<Generator> offered = library.lookup(slot.generators());
            for (Generator generator : offered) {
                String label = slot.label() + " " + Component.describe(generator, offered);

                for (int index = 0; index < estimableCount(slot, generator); index++) {
                    Analysis analysis = analysisWith(slot, generator);
                    Param param = estimable(slot.component().apply(analysis)).get(index);
                    if (param.required()) continue;
                    param.includeProperty().set(false);
                    checked++;
                    check(failures, analysis, label + ": " + param.name() + " omitted");
                }
            }
        }

        assertEquals(List.of(), failures);
        assertTrue(checked > 0, "expected the tabs to offer some optional arguments");
    }

    private Analysis analysisWith(Slot slot, Generator generator) {
        Analysis analysis = analysisWithData();
        slot.component().apply(analysis).generatorProperty().set(generator);
        return analysis;
    }

    private int estimableCount(Slot slot, Generator generator) {
        return estimable(slot.component().apply(analysisWith(slot, generator))).size();
    }

    /**
     * Every param the user could tick "estimate" on, in the order the tabs present them, descending
     * into nested functions the way {@link Analysis#estimatedParams()} does — a coalescent's
     * population size is estimated through its population function, not directly.
     */
    private static List<Param> estimable(Component component) {
        List<Param> found = new ArrayList<>();
        collectEstimable(component, found, 0);
        return found;
    }

    private static void collectEstimable(Component component, List<Param> into, int depth) {
        if (component == null || depth > MAX_NESTING) return;
        for (Param param : component.params()) {
            if (param.estimable()) into.add(param);
            Component nested = param.priorProperty().get();
            if (nested != null && !param.isEstimated()) collectEstimable(nested, into, depth + 1);
        }
    }

    /**
     * Generates and validates, recording the failure rather than stopping at the first one, so a run
     * reports the whole set. A placeholder is a failure too: the writer emits one where the user still
     * has to supply something, and the tabs are supposed to have supplied it already.
     */
    private static void check(List<String> failures, Analysis analysis, String what) {
        String script = ScriptWriter.write(analysis);
        List<String> problems = Validator.check(library, script).all();
        if (!problems.isEmpty()) {
            failures.add(what + " -> " + problems.get(0));
        } else if (script.contains("/*") || script.contains("has no prior")) {
            failures.add(what + " -> unfilled placeholder");
        }
    }
}
