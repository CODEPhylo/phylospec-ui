package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/** Checks that a packaged clock adapter can drive the UI's general model editor. */
@EnabledIfSystemProperty(named = "phylospec.clockAdapter", matches = ".+")
class ClockModelAveragingPackageTest {

    @Test
    @EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
    void discoversBothPackagesFromOneAdapterPath() {
        PackageMetadata metadata = PackageMetadata.discover(List.of(
                Path.of(System.getProperty("phylospec.clockAdapter")),
                Path.of(System.getProperty("phylospec.phyloncoAdapter"))));
        Library library = metadata.library();
        assertTrue(metadata.support().supports(library.overloads("sharedRatesClock").getFirst()));
        assertTrue(metadata.support().supports(library.overloads("siFit3").getFirst()));
    }

    @Test
    void runsGuiGeneratedClockThroughPackagedAdapter(@TempDir Path directory) throws Exception {
        Path adapter = Path.of(System.getProperty("phylospec.clockAdapter"));
        PackageMetadata metadata = PackageMetadata.discover(List.of(adapter));
        Library library = metadata.library();
        Generator clock = library.overloads("sharedRatesClock").getFirst();
        assertTrue(metadata.support().supports(clock));

        Path alignment = directory.resolve("three-taxa.nex");
        Files.writeString(alignment, """
                #NEXUS
                begin data;
                    dimensions ntax=3 nchar=12;
                    format datatype=dna missing=? gap=-;
                    matrix
                        A ACGTACGTACGT
                        B ACGTACGTACGA
                        C ACGTACGTACGG
                    ;
                end;
                """);

        Analysis analysis = new Analysis(library, metadata.support());
        analysis.addPartition(alignment);
        assertTrue(analysis.choicesFor(Library.CLOCK_MODEL).contains(clock));
        assertTrue(analysis.choicesFor(Library.CLOCK_MODEL).stream()
                .noneMatch(candidate -> "svsRawBranchRates".equals(candidate.getName())));

        analysis.clockModel().generatorProperty().set(clock);
        Param rates = analysis.clockModel().param("rates");
        assertTrue(rates.isEstimated());
        Component prior = rates.priorProperty().get();
        assertEquals("svsRawBranchRates", prior.name());
        Param indicator = prior.param("indicator");
        assertTrue(indicator.isEstimated());
        assertEquals("0", indicator.priorProperty().get().param("lower").valueProperty().get());
        assertEquals("1", indicator.priorProperty().get().param("upper").valueProperty().get());

        analysis.chainLengthProperty().set("50");
        analysis.logEveryProperty().set("1");
        analysis.seedProperty().set("12345");
        Path outputPrefix = directory.resolve("clock-svs");
        analysis.logFileProperty().set(outputPrefix + ".log");

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("use clockmodelaveraging.distributions.clock"), script);
        assertTrue(script.contains("use clockmodelaveraging.functions.clock"), script);
        assertTrue(script.contains("Vector<Rate> rates ~ svsRawBranchRates("), script);
        assertTrue(script.contains("tree=tree"), script);
        assertTrue(script.contains("Vector<Rate> branchRates = sharedRatesClock("), script);
        assertEquals(List.of(), Validator.check(library, script).all(), script);

        Analysis reloaded = ScriptReader.read(library, script);
        assertEquals("sharedRatesClock", reloaded.clockModel().name());
        assertEquals("svsRawBranchRates",
                reloaded.clockModel().param("rates").priorProperty().get().name());

        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        Path launcher = directory.resolve("RunPackagedPhyloSpec.java");
        Files.writeString(launcher, """
                import java.nio.file.Path;
                import java.util.List;
                import runner.PhyloSpecRunner;

                class RunPackagedPhyloSpec {
                    public static void main(String[] args) throws Exception {
                        new PhyloSpecRunner(Path.of(args[0]),
                                List.of("clockmodelaveraging", "beast2"))
                                .runPhyloSpec(args[1]);
                    }
                }
                """);
        Path processOutput = directory.resolve("runner-output.txt");
        Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", adapter.resolve("*").toString(),
                        launcher.toString(), source.toString(), outputPrefix.toString())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(processOutput.toFile())
                .start();

        boolean finished = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Packaged clock analysis did not finish within 30 seconds");
        String output = Files.readString(processOutput);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("End likelihood:"), output);
        assertTrue(output.contains("IndicatorGibbsOperator"), output);
        assertTrue(output.contains("UCACSwitchBridgeOperator"), output);

        Path trace = Path.of(outputPrefix + ".log");
        assertTrue(Files.isRegularFile(trace));
        assertTrue(Files.isRegularFile(Path.of(outputPrefix + ".state.xml")));
        List<String> samples = Files.readAllLines(trace).stream()
                .filter(line -> line.matches("\\s*\\d+\\s+.*"))
                .toList();
        assertTrue(samples.size() >= 2, "The short chain must write posterior samples");
        for (String sample : samples) {
            assertTrue(Double.isFinite(Double.parseDouble(sample.trim().split("\\s+")[1])), sample);
        }
    }
}
