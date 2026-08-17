package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.phylospec.ui.model.Analysis;
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
 * PhyloSpec parser and type resolver.
 */
class ScriptWriterTest {

    private static Library library;

    @BeforeAll
    static void loadLibrary() {
        library = Library.load();
    }

    private Analysis analysisWithData(Path directory) throws IOException {
        Path alignment = directory.resolve("primates.nex");
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
        Analysis analysis = new Analysis(library);
        analysis.partitions().add(new Partition(alignment));
        return analysis;
    }

    @Test
    void readsTaxonAndSiteCountsFromNexus(@TempDir Path directory) throws IOException {
        Partition partition = analysisWithData(directory).partitions().get(0);
        assertEquals(4, partition.taxaProperty().get());
        assertEquals(8, partition.sitesProperty().get());
        assertEquals("fromNexus", partition.loader());
        assertEquals("primates", partition.name());
    }

    @Test
    void defaultAnalysisIsValid(@TempDir Path directory) throws IOException {
        Analysis analysis = analysisWithData(directory);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.validate(library, script));
        assertTrue(script.contains("Tree tree ~ Yule("), script);
        assertTrue(script.contains("observed as primates"), script);
    }

    @Test
    void tipDatesBecomeAParseArgument(@TempDir Path directory) throws IOException {
        Analysis analysis = analysisWithData(directory);
        analysis.partitions().get(0).useTipDatesProperty().set(true);
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("age=parse(delimiter=\"_\", part=2)"), script);
        assertEquals(List.of(), Validator.validate(library, script));
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
            assertEquals(List.of(), Validator.validate(library, ScriptWriter.write(analysis)), expected.file());
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
        assertEquals(List.of(), Validator.validate(library, ScriptWriter.write(analysis)));
    }

    /** Every combination the tabs can express must parse and type-check. */
    @Test
    void everyModelCombinationIsValid(@TempDir Path directory) throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (String substitution : Analysis.SUBSTITUTION_MODELS) {
            for (String clock : Analysis.CLOCK_MODELS) {
                for (String treePrior : Analysis.TREE_PRIORS) {
                    for (boolean gammaRates : new boolean[] {false, true}) {
                        int overloads = library.overloads(treePrior).size();
                        for (int overload = 0; overload < overloads; overload++) {
                            Analysis analysis = analysisWithData(directory);
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
                            List<String> problems = Validator.validate(library, script);
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
}
