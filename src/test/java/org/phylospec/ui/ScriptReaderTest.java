package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Checks that a script written by the GUI reads back onto the tabs unchanged.
 *
 * <p>The property tested is a fix point on the script rather than on the analysis: writing what was
 * read must reproduce the script exactly. An {@link Analysis} has no equality of its own, and the
 * script is what the user actually keeps, so it is the thing that has to survive the round trip.
 */
class ScriptReaderTest {

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

    /** Writes, reads the result back, and writes again; the two scripts must be identical. */
    private static void assertRoundTrips(Analysis analysis, String what) {
        String written = ScriptWriter.write(analysis);
        String reloaded = ScriptWriter.write(ScriptReader.read(library, written));
        assertEquals(written, reloaded, what);
    }

    private static void roundTrip(List<String> failures, Analysis analysis, String what) {
        try {
            String written = ScriptWriter.write(analysis);
            String reloaded = ScriptWriter.write(ScriptReader.read(library, written));
            if (!written.equals(reloaded)) {
                failures.add(what + " -> reloaded differently:\n" + written + "\nbecame\n" + reloaded);
            }
        } catch (RuntimeException e) {
            failures.add(what + " -> " + e);
        }
    }

    @Test
    void defaultAnalysisRoundTrips() {
        assertRoundTrips(analysisWithData(), "default analysis");
    }

    @Test
    void everyModelCombinationRoundTrips() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (String substitution : Analysis.SUBSTITUTION_MODELS) {
            for (String clock : Analysis.CLOCK_MODELS) {
                for (String treePrior : Analysis.TREE_PRIORS) {
                    for (boolean gammaRates : new boolean[] {false, true}) {
                        for (int overload = 0; overload < library.overloads(treePrior).size(); overload++) {
                            Analysis analysis = analysisWithData();
                            analysis.substitutionModel().generatorProperty()
                                    .set(library.overloads(substitution).get(0));
                            analysis.clockModel().generatorProperty().set(library.overloads(clock).get(0));
                            analysis.treePrior().generatorProperty()
                                    .set(library.overloads(treePrior).get(overload));
                            if (gammaRates) {
                                analysis.siteRates().generatorProperty()
                                        .set(library.overloads("DiscreteGammaInv").get(0));
                            }
                            checked++;
                            roundTrip(failures, analysis, substitution + " + " + clock + " + "
                                    + treePrior + "#" + overload + (gammaRates ? " + gamma" : ""));
                        }
                    }
                }
            }
        }

        assertTrue(checked > 300, "expected many models, got " + checked);
        assertEquals(List.of(), failures);
    }

    /** Estimating a value adds a statement and replaces a literal, so both directions must survive. */
    @Test
    void everyEstimateAndPriorRoundTrips() {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (String name : List.of("hky", "gtr", "DiscreteGammaInv", "StrictClock", "RelaxedClock",
                "Yule", "BirthDeath", "Coalescent", "FossilizedBirthDeath")) {
            for (Generator generator : library.overloads(name)) {
                for (int index = 0; index < estimableCount(generator); index++) {
                    for (Generator prior : library.priorsFor(supportOf(generator, index))) {
                        Analysis analysis = analysisFor(generator);
                        Param param = estimable(componentFor(analysis, generator)).get(index);
                        param.estimateProperty().set(true);
                        param.priorProperty().set(Component.nested(prior, library, false));
                        checked++;
                        roundTrip(failures, analysis,
                                name + ": " + param.name() + " ~ " + prior.getName());
                    }

                    // And the same value left fixed, which writes a literal instead.
                    Analysis fixed = analysisFor(generator);
                    Param param = estimable(componentFor(fixed, generator)).get(index);
                    param.estimateProperty().set(false);
                    checked++;
                    roundTrip(failures, fixed, name + ": " + param.name() + " fixed");
                }
            }
        }

        assertTrue(checked > 100, "expected many estimate and prior combinations, got " + checked);
        assertEquals(List.of(), failures);
    }

    @Test
    void optionalArgumentsStayOmitted() {
        List<String> failures = new ArrayList<>();
        for (Generator generator : library.lookup(Analysis.TREE_PRIORS)) {
            for (int index = 0; index < estimableCount(generator); index++) {
                Analysis analysis = analysisFor(generator);
                Param param = estimable(componentFor(analysis, generator)).get(index);
                if (param.required()) continue;
                param.includeProperty().set(false);
                roundTrip(failures, analysis, generator.getName() + ": " + param.name() + " omitted");
            }
        }
        assertEquals(List.of(), failures);
    }

    // ----------------------------------------------------------- data and mcmc

    @Test
    void bundledExamplesRoundTrip() {
        for (String file : List.of("Primates.nex", "anolis.nex", "RSV2.nex", "dna.fasta", "aminoacid.fasta")) {
            Analysis analysis = new Analysis(library);
            analysis.addPartition(Path.of("examples", file));
            assertRoundTrips(analysis, file);
        }
    }

    @Test
    void tipDateSettingsSurvive() {
        for (Partition.DateKind kind : Partition.DateKind.values()) {
            for (Partition.ParseMode mode : Partition.ParseMode.values()) {
                Analysis analysis = analysisWithData();
                Partition partition = analysis.partitions().get(0);
                partition.useTipDatesProperty().set(true);
                partition.dateKindProperty().set(kind);
                partition.parseModeProperty().set(mode);
                partition.delimiterProperty().set("|");
                partition.partProperty().set("3");
                partition.regexProperty().set("(\\d+)$");
                assertRoundTrips(analysis, kind + " / " + mode);

                Partition reloaded = ScriptReader
                        .read(library, ScriptWriter.write(analysis)).partitions().get(0);
                assertEquals(kind, reloaded.dateKindProperty().get());
                assertEquals(mode, reloaded.parseModeProperty().get());
                assertTrue(reloaded.useTipDatesProperty().get());
            }
        }
    }

    @Test
    void partitionNamesAndCountsSurvive() {
        Analysis analysis = new Analysis(library);
        analysis.addPartition(Path.of("examples", "dna.nex"));
        analysis.addPartition(Path.of("examples", "dna.fasta"));
        assertRoundTrips(analysis, "two partitions with clashing names");

        Analysis reloaded = ScriptReader.read(library, ScriptWriter.write(analysis));
        assertEquals(List.of("dna", "dna2"),
                reloaded.partitions().stream().map(Partition::name).toList());
        assertEquals(10, reloaded.partitions().get(0).taxaProperty().get());
        assertEquals(705, reloaded.partitions().get(0).sitesProperty().get());
    }

    @Test
    void mcmcSettingsSurvive() {
        Analysis analysis = analysisWithData();
        analysis.chainLengthProperty().set("5000000");
        analysis.logEveryProperty().set("500");
        analysis.logFileProperty().set("run1.log");
        analysis.seedProperty().set("42");
        assertRoundTrips(analysis, "mcmc settings");

        Analysis reloaded = ScriptReader.read(library, ScriptWriter.write(analysis));
        assertEquals("5000000", reloaded.chainLengthProperty().get());
        assertEquals("500", reloaded.logEveryProperty().get());
        assertEquals("run1.log", reloaded.logFileProperty().get());
        assertEquals("42", reloaded.seedProperty().get());
    }

    @Test
    void aRenamedTreeSurvives() {
        Analysis analysis = analysisWithData();
        analysis.treeNameProperty().set("phylogeny");
        assertRoundTrips(analysis, "renamed tree");
        assertEquals("phylogeny",
                ScriptReader.read(library, ScriptWriter.write(analysis)).treeNameProperty().get());
    }

    // --------------------------------------------------------------- refusals

    /** A script the tabs cannot express must be refused outright rather than partly loaded. */
    @Test
    void unsupportedScriptsAreRefused() {
        record Case(String what, String source) {}
        List<Case> cases = List.of(
                new Case("not PhyloSpec at all", "this is not a script"),
                new Case("no alignment", """
                        model {
                            Tree tree ~ Yule(birthRate=1.0, taxa=taxa(missing))
                        }
                        """),
                new Case("an unknown component", """
                        data {
                            Alignment d = fromNexus(file="examples/dna.nex")
                        }
                        model {
                            QMatrix qMatrix = notAModel(kappa=2.0)
                            Tree tree ~ Yule(birthRate=1.0, taxa=taxa(d))
                        }
                        """),
                new Case("an unexpected model assignment", """
                        data {
                            Alignment d = fromNexus(file="examples/dna.nex")
                        }
                        model {
                            Real spare = 1.0
                            QMatrix qMatrix = jc69()
                            Tree tree ~ Yule(birthRate=1.0, taxa=taxa(d))
                        }
                        """),
                new Case("an unknown mcmc setting", """
                        data {
                            Alignment d = fromNexus(file="examples/dna.nex")
                        }
                        model {
                            QMatrix qMatrix = jc69()
                            Tree tree ~ Yule(birthRate=1.0, taxa=taxa(d))
                        }
                        mcmc {
                            Integer burnin = 1000
                        }
                        """));

        for (Case testCase : cases) {
            assertThrows(ScriptReader.Unsupported.class,
                    () -> ScriptReader.read(library, testCase.source()), testCase.what());
        }
    }

    /** Whatever the reader accepts must still be a script the reference implementation accepts. */
    @Test
    void anythingLoadedIsStillValid() {
        Analysis analysis = analysisWithData();
        analysis.treePrior().generatorProperty().set(library.overloads("BirthDeath").get(1));
        String written = ScriptWriter.write(analysis);
        Analysis reloaded = ScriptReader.read(library, written);
        assertEquals(List.of(), Validator.check(library, ScriptWriter.write(reloaded)).all());
    }

    // ---------------------------------------------------------------- helpers

    /** The slot a generator belongs to, so a test can name a component without a tab. */
    private static Component componentFor(Analysis analysis, Generator generator) {
        String produced = Library.head(generator.getGeneratedType());
        if ("QMatrix".equals(produced)) return analysis.substitutionModel();
        String inner = Library.head(Library.inner(generator.getGeneratedType()));
        if ("Tree".equals(inner)) return analysis.treePrior();
        return Analysis.CLOCK_MODELS.contains(generator.getName())
                ? analysis.clockModel()
                : analysis.siteRates();
    }

    private Analysis analysisFor(Generator generator) {
        Analysis analysis = analysisWithData();
        componentFor(analysis, generator).generatorProperty().set(generator);
        return analysis;
    }

    private int estimableCount(Generator generator) {
        return estimable(componentFor(analysisFor(generator), generator)).size();
    }

    private String supportOf(Generator generator, int index) {
        return estimable(componentFor(analysisFor(generator), generator)).get(index).priorSupport();
    }

    private static List<Param> estimable(Component component) {
        List<Param> found = new ArrayList<>();
        for (Param param : component.params()) {
            if (param.estimable()) found.add(param);
        }
        return found;
    }
}
