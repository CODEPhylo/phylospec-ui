package org.phylospec.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class ReadCountTest {
    @TempDir Path directory;

    static Analysis analysis(Path directory, String substitution, boolean estimated, boolean gamma) throws Exception {
        var metadata = PackageMetadata.discover(List.of(GenotypeErrorTest.adapter()));
        Analysis analysis = new Analysis(metadata.library(), metadata.support());
        Files.copy(Path.of("examples/phylonco/read-counts.nex"), directory.resolve("counts.nex"));
        analysis.addPartition(directory.resolve("counts.nex"));
        var library = analysis.library();
        analysis.substitutionModel().generatorProperty().set(library.overloads(substitution).getFirst());
        var rates = analysis.substitutionModel().param("rates");
        rates.estimateProperty().set(false);
        rates.valueProperty().set("[1.0, 1.0, 1.0, 1.0, 1.0, 1.0]");
        analysis.likelihood().generatorProperty().set(library.overloads("ReadCountModel").getFirst());
        for (String name : List.of("epsilon", "delta", "t", "v", "w1", "w2")) {
            var param = analysis.likelihood().param(name);
            param.estimateProperty().set(estimated);
            param.valueProperty().set(switch (name) {
                case "epsilon" -> "0.01"; case "delta" -> "0.05"; case "t" -> "10.0";
                case "v" -> "2.0"; case "w1" -> "20.0"; default -> "5.0";
            });
        }
        if (gamma) {
            analysis.siteRates().generatorProperty().set(library.overloads("DiscreteGammaInv").getFirst());
            analysis.siteRates().param("numCategories").valueProperty().set("4");
        }
        analysis.chainLengthProperty().set("30");
        analysis.logEveryProperty().set("1");
        analysis.seedProperty().set("24680");
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        return analysis;
    }

    @ParameterizedTest
    @CsvSource({"gt10,false,false", "gt16,false,false", "gt10,true,false", "gt16,true,true"})
    void loadsConfiguresRoundTripsAndRuns(String model, boolean estimated, boolean gamma) throws Exception {
        Analysis analysis = analysis(directory, model, estimated, gamma);
        assertEquals(4, analysis.partitions().getFirst().taxaProperty().get());
        assertEquals(2, analysis.partitions().getFirst().sitesProperty().get());
        assertEquals("fromReadCountNexus", analysis.partitions().getFirst().loader());
        assertEquals(List.of("ReadCountModel"), analysis.choicesFor(Library.TREE_LIKELIHOOD).stream().map(g -> g.getName()).toList());
        assertNull(analysis.likelihood().param("alignment"));
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("ReadCountData counts = fromReadCountNexus("), script);
        assertTrue(script.contains("readCountTaxa(counts=counts)"), script);
        assertTrue(script.contains("Alignment<Character> countsLatent ~ PhyloCTMC("), script);
        assertTrue(script.contains("s=[1.0, 1.0, 1.0, 1.0]"), script);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
        run(script, directory, model);
        String output = Files.readString(directory.resolve("runner.txt"));
        assertTrue(output.contains("GibbsSiteOperator"), output);
        assertTrue(Files.readString(directory.resolve("analysis.state.xml")).contains("latentAlignment"));
    }

    static void run(String script, Path directory, String model) throws Exception {
        String pattern = "gt10".equals(model) ? "[AMRWCSYGKT]{2}" : "[0-9A-F]{2}";
        GenotypeErrorTest.run(script, directory,
                Map.of("cell_1", pattern, "cell_2", pattern, "cell_3", pattern, "cell_4", pattern));
    }

    @Test
    void exampleLoadsFromItsOwnDirectoryAndRunsAfterExport() throws Exception {
        var metadata = PackageMetadata.discover(List.of(GenotypeErrorTest.adapter()));
        var analysis = ScriptReader.read(metadata.library(), metadata.support(),
                Path.of("examples/phylonco/read-count.phylospec"));
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all());
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
        run(script, directory, "gt10");
    }

    @Test
    void preservesExplicitSizeFactorsAndCanEstimateThem() throws Exception {
        Analysis analysis = analysis(directory, "gt10", false, false);
        var factors = analysis.likelihood().param("s");
        factors.valueProperty().set("[0.5, 1.0, 1.5, 2.0]");
        String fixed = ScriptWriter.write(analysis);
        assertTrue(fixed.contains("s=[0.5, 1.0, 1.5, 2.0]"), fixed);
        assertEquals(fixed, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), fixed)));
        factors.estimateProperty().set(true);
        assertEquals("4", factors.priorProperty().get().param("num").valueProperty().get());
        String estimated = ScriptWriter.write(analysis);
        assertEquals(estimated, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), estimated)));
        run(estimated, directory, "gt10");
    }

    @Test
    void preservesExistingPriorDimensionRatherThanSilentlyResizingIt() throws Exception {
        Analysis analysis = analysis(directory, "gt10", false, false);
        var factors = analysis.likelihood().param("s");
        factors.estimateProperty().set(true);
        var prior = factors.priorProperty().get();
        prior.param("num").valueProperty().set("2");
        factors.estimateProperty().set(false);
        factors.valueProperty().set("[1.0, 2.0, 3.0]");
        factors.estimateProperty().set(true);
        assertSame(prior, factors.priorProperty().get());
        assertEquals("2", prior.param("num").valueProperty().get());
        String script = ScriptWriter.write(analysis);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
    }

    @Test
    void mixedFixedAndEstimatedSizeFactorsRoundTripAndRun() throws Exception {
        Analysis analysis = analysis(directory, "gt10", false, false);
        var factors = analysis.likelihood().param("s");
        factors.valueProperty().set("[0.5, 1.0, 1.5, 2.0]");
        factors.editElements(analysis.library());
        factors.elements().get(1).estimateProperty().set(true);
        String script = ScriptWriter.write(analysis);
        var restored = ScriptReader.read(analysis.library(), analysis.support(), script);
        assertEquals(List.of(false, true, false, false), restored.likelihood().param("s").elements().stream()
                .map(param -> param.isEstimated()).toList());
        assertEquals(script, ScriptWriter.write(restored));
        run(script, directory, "gt10");
    }

    @Test
    void refusesMismatchedLoaderAndForeignTaxonSource() throws Exception {
        Analysis analysis = analysis(directory, "gt10", false, false);
        String script = ScriptWriter.write(analysis);
        var wrongLoader = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), script.replace("fromReadCountNexus", "fromNexus")));
        assertTrue(wrongLoader.getMessage().contains("ReadCountData"), wrongLoader.getMessage());
        var wrongTaxa = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), script.replace("readCountTaxa(counts=counts)", "taxa(counts)")));
        assertTrue(wrongTaxa.getMessage().toLowerCase().contains("taxa"), wrongTaxa.getMessage());
    }

    @Test
    void rejectsWrongSizeFactorsInBackend() throws Exception {
        Analysis analysis = analysis(directory, "gt10", false, false);
        analysis.likelihood().param("s").valueProperty().set("[1.0, 1.0]");
        Path script = directory.resolve("invalid.phylospec");
        Files.writeString(script, ScriptWriter.write(analysis));
        Path output = directory.resolve("invalid.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", GenotypeErrorTest.adapter().resolve("*").toString(), "runner.PhyloSpecCli",
                "--library", "phylonco,beast2", script.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean done;
        try {
            done = process.waitFor(30, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertTrue(done);
        assertNotEquals(0, process.exitValue(), Files.readString(output));
        assertTrue(Files.readString(output).contains("one s value per taxon"), Files.readString(output));
    }

    @Test
    void refusesDatesMultiplePartitionsAndSharedSampledParameters() throws Exception {
        Analysis analysis = analysis(directory, "gt10", true, false);
        String shared = ScriptWriter.write(analysis).replace("v=v", "v=t");
        assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(analysis.library(), shared));
        analysis.partitions().getFirst().useTipDatesProperty().set(true);
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
        analysis.partitions().getFirst().useTipDatesProperty().set(false);
        Files.copy(directory.resolve("counts.nex"), directory.resolve("second.nex"));
        analysis.addPartition(directory.resolve("second.nex"));
        assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
    }
}
