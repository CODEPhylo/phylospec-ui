package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

/** Exercises composition, not just discovery, using two independently packaged adapters. */
@EnabledIfSystemProperty(named = "phylospec.clockAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class MultiPackageTest {

    @ParameterizedTest(name = "mixture={0}, clock adapter first={1}")
    @CsvSource({"false, true", "false, false", "true, true", "true, false"})
    void runsPhyloncoSubstitutionWithRelaxClock(
            boolean mixture, boolean clockFirst, @TempDir Path directory) throws Exception {
        Path clock = Path.of(System.getProperty("phylospec.clockAdapter"));
        Path phylonco = Path.of(System.getProperty("phylospec.phyloncoAdapter"));
        List<Path> adapters = clockFirst ? List.of(clock, phylonco) : List.of(phylonco, clock);
        PackageMetadata metadata = PackageMetadata.discover(adapters);
        Library library = metadata.library();

        // SiFit3 describes three states; DNA data would not test a valid model composition.
        Path alignment = directory.resolve("ternary.nex");
        Files.writeString(alignment, """
                #NEXUS
                begin data;
                    dimensions ntax=4 nchar=20;
                    format datatype=ternary missing=? gap=-;
                    matrix
                        cell_1  01020112021001220120
                        cell_2  01020112021011220120
                        cell_3  22101200102122001201
                        cell_4  22101200102122011201
                    ;
                end;
                """);

        Analysis analysis = new Analysis(library, metadata.support());
        analysis.addPartition(alignment);
        var substitution = library.overloads("siFit3").getFirst();
        var branchRates = library.overloads("sharedRatesClock").getFirst();
        assertTrue(analysis.choicesFor(Library.SUBSTITUTION_MODEL).contains(substitution));
        assertTrue(analysis.choicesFor(Library.CLOCK_MODEL).contains(branchRates));
        analysis.substitutionModel().generatorProperty().set(substitution);
        analysis.substitutionModel().param("mutationPath").valueProperty().set("true");
        if (mixture) {
            analysis.likelihood().generatorProperty().set(library.overloads("alignmentMixture").getFirst());
            analysis.likelihood().param("weights").estimateProperty().set(false);
            analysis.likelihood().param("weights").valueProperty()
                    .set("[0.3333333333333333, 0.6666666666666666]");
        } else {
            analysis.clockModel().generatorProperty().set(branchRates);
        }
        analysis.chainLengthProperty().set("50");
        analysis.logEveryProperty().set("1");
        analysis.seedProperty().set("24680");
        Path prefix = directory.resolve("combined");
        analysis.logFileProperty().set(prefix + ".log");

        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("use phylonco.functions.substitution"), script);
        assertTrue(script.contains("use clockmodelaveraging.functions.clock"), script);
        assertTrue(script.contains("QMatrix qMatrix = siFit3("), script);
        assertTrue(script.contains("~ svsRawBranchRates("), script);
        assertTrue(script.contains("= sharedRatesClock("), script);
        assertEquals(mixture, script.contains("~ alignmentMixture("), script);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));

        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        String classPath = adapters.stream().map(path -> path.resolve("*").toString())
                .collect(Collectors.joining(File.pathSeparator));
        Path outputFile = directory.resolve("runner-output.txt");
        // A fresh JVM prevents Maven's own classpath from supplying missing package dependencies.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classPath, "runner.PhyloSpecCli",
                "--library", "clockmodelaveraging,phylonco,beast2",
                "--run-name", prefix.toString(), source.toString())
                .directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Combined package analysis exceeded 30 seconds");
        String output = Files.readString(outputFile);
        assertEquals(0, process.exitValue(), output + "\n" + script);
        assertTrue(output.contains("Alignment(ternary)"), output);
        assertTrue(output.contains("End likelihood:"), output);
        assertTrue(output.contains("IndicatorGibbsOperator"), output);
        assertTrue(output.contains("UCACSwitchBridgeOperator"), output);
        assertTrue(Files.isRegularFile(Path.of(prefix + ".trees")));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));

        List<String> samples = Files.readAllLines(Path.of(prefix + ".log")).stream()
                .filter(line -> line.matches("\\s*\\d+\\s+.*")).toList();
        assertTrue(samples.size() >= 2, "The combined model must write posterior samples");
        assertEquals(50L, Long.parseLong(samples.getLast().trim().split("\\s+")[0]));
        for (String sample : samples) {
            for (String value : sample.trim().split("\\s+")) {
                assertTrue(Double.isFinite(Double.parseDouble(value)), sample);
            }
        }
    }
}
