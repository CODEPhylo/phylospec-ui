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
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class GenotypeErrorTest {
    @TempDir Path directory;

    static Analysis analysis(Path directory, boolean estimated, boolean gamma) throws Exception {
        var metadata = PackageMetadata.discover(List.of(adapter()));
        var library = metadata.library();
        Path data = directory.resolve("genotypes.nex");
        Files.writeString(data, """
                #NEXUS
                begin data;
                  dimensions ntax=4 nchar=20;
                  format datatype=nucleotideDiploid16 missing=? gap=-;
                  matrix
                    cell_1 0123456789ABCDEF0123
                    cell_2 0123456789ABCDEF0124
                    cell_3 1123456789ABCDEF0123
                    cell_4 1123456789ABCDEF0124
                  ;
                end;
                """);
        Analysis analysis = new Analysis(library, metadata.support());
        analysis.addPartition(data);
        analysis.substitutionModel().generatorProperty().set(library.overloads("gt16").getFirst());
        var rates = analysis.substitutionModel().param("rates");
        rates.estimateProperty().set(false);
        rates.valueProperty().set("[1.0, 1.0, 1.0, 1.0, 1.0, 1.0]");
        analysis.likelihood().generatorProperty().set(library.overloads("GT16ErrorModel").getFirst());
        for (String name : List.of("epsilon", "delta")) {
            var param = analysis.likelihood().param(name);
            param.estimateProperty().set(estimated);
            param.valueProperty().set(name.equals("epsilon") ? "0.01" : "0.05");
        }
        if (gamma) {
            analysis.siteRates().generatorProperty().set(library.overloads("DiscreteGammaInv").getFirst());
            analysis.siteRates().param("numCategories").valueProperty().set("4");
        }
        analysis.chainLengthProperty().set("30");
        analysis.logEveryProperty().set("1");
        analysis.seedProperty().set("12345");
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        return analysis;
    }

    static Path adapter() {
        return Path.of(System.getProperty("phylospec.phyloncoAdapter"));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void roundTripsAndRunsTheLayeredObservation(boolean estimated, boolean gamma) throws Exception {
        Analysis analysis = analysis(directory, estimated, gamma);
        assertTrue(analysis.isGenotypeError());
        assertTrue(analysis.likelihoodTakes("qMatrix"));
        assertTrue(analysis.likelihoodTakes("branchRates"));
        assertTrue(analysis.likelihoodTakes("siteRates"));
        assertNull(analysis.likelihood().param("alignment"), "Observed data must not substitute for the latent draw.");
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        assertEquals(1, script.split("~ PhyloCTMC\\(", -1).length - 1);
        assertEquals(1, script.split("observed as", -1).length - 1);
        assertTrue(script.contains("alignment=genotypesLatent"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
        run(script, directory);
    }

    @Test
    void partitionsKeepSeparateLatentDrawsAndSharedErrorPriors() throws Exception {
        Analysis analysis = analysis(directory, true, false);
        Path second = directory.resolve("second.nex");
        Files.copy(directory.resolve("genotypes.nex"), second);
        analysis.addPartition(second);
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("alignment=genotypesLatent"), script);
        assertTrue(script.contains("alignment=secondLatent"), script);
        assertEquals(1, script.split("Probability epsilon ~", -1).length - 1);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
        run(script, directory);
        String shared = script.replace("alignment=secondLatent", "alignment=genotypesLatent");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), shared));
        assertTrue(error.getMessage().contains("Sharing one latent alignment"), error.getMessage());
    }

    @Test
    void refusesObservedDataInsteadOfLatentDraw() throws Exception {
        Analysis analysis = analysis(directory, false, false);
        String invalid = ScriptWriter.write(analysis).replace("alignment=genotypesLatent", "alignment=genotypes");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), invalid));
        assertTrue(error.getMessage().contains("named latent alignment"), error.getMessage());
    }

    @Test
    void refusesOneSampledParameterForBothErrorProbabilities() throws Exception {
        Analysis analysis = analysis(directory, true, false);
        String script = ScriptWriter.write(analysis).replace("delta=delta", "delta=epsilon");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), script));
        assertTrue(error.getMessage().contains("between epsilon and delta"), error.getMessage());
    }

    @Test
    void suppliedExampleResolvesRelativeDataAndRuns() throws Exception {
        var metadata = PackageMetadata.discover(List.of(adapter()));
        var analysis = ScriptReader.read(metadata.library(), metadata.support(),
                Path.of("examples/phylonco/gt16-error.phylospec"));
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        String script = ScriptWriter.write(analysis);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(metadata.library(), metadata.support(), script)));
        run(script, directory);
    }

    @Test
    void refusesAdditionalLatentSettingsRatherThanDroppingThem() throws Exception {
        Analysis analysis = analysis(directory, false, false);
        String script = ScriptWriter.write(analysis).replace("~ PhyloCTMC(", "~ PhyloCTMC(useAmbiguities=true,");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), script));
        assertTrue(error.getMessage().contains("additional inputs"), error.getMessage());
    }

    @Test
    void refusesPartitionSpecificErrorParametersRatherThanKeepingOnlyTheLast() throws Exception {
        Analysis analysis = analysis(directory, false, false);
        Path second = directory.resolve("second.nex");
        Files.copy(directory.resolve("genotypes.nex"), second);
        analysis.addPartition(second);
        String script = ScriptWriter.write(analysis);
        int last = script.lastIndexOf("epsilon=0.01");
        assertTrue(last >= 0, script);
        String different = script.substring(0, last) + script.substring(last).replace("epsilon=0.01", "epsilon=0.02");
        var error = assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(analysis.library(), analysis.support(), different));
        assertTrue(error.getMessage().contains("share the same error parameters"), error.getMessage());
    }

    static void run(String script, Path directory) throws Exception {
        run(script, directory, Map.of());
    }

    static void run(String script, Path directory, Map<String, String> genotypeColumns) throws Exception {
        Path file = directory.resolve("analysis.phylospec");
        Files.writeString(file, script);
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-cp", adapter().resolve("*").toString(),
                "runner.PhyloSpecCli", "--library", "phylonco,beast2", file.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertTrue(finished, "Runner timed out: " + Files.readString(output));
        assertEquals(0, process.exitValue(), Files.readString(output));
        assertTrue(Files.readString(output).contains("End likelihood:"), Files.readString(output));
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_30"));
        assertTrue(Files.size(directory.resolve("analysis.state.xml")) > 0);
        List<String> samples = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(32, samples.size());
        String[] columns = samples.getFirst().trim().split("\\s+");
        assertTrue(List.of(columns).containsAll(List.of("posterior", "prior", "likelihood")), samples.getFirst());
        assertTrue(List.of(columns).containsAll(genotypeColumns.keySet()), samples.getFirst());
        for (int sample = 0; sample <= 30; sample++) {
            String line = samples.get(sample + 1);
            String[] values = line.trim().split("\\s+");
            assertEquals(columns.length, values.length, line);
            assertEquals(sample, Integer.parseInt(values[0]), line);
            for (int i = 0; i < values.length; i++) {
                String pattern = genotypeColumns.get(columns[i]);
                if (pattern == null) assertTrue(Double.isFinite(Double.parseDouble(values[i])), line);
                else assertTrue(values[i].matches(pattern), line);
            }
        }
    }
}
