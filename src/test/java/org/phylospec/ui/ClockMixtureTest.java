package org.phylospec.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/** Uses the real package metadata and, for execution tests, only the packaged runtime JARs. */
@EnabledIfSystemProperty(named = "phylospec.clockAdapter", matches = ".+")
class ClockMixtureTest {

    private static PackageMetadata metadata;
    private static Library library;

    @TempDir
    Path directory;

    @BeforeAll
    static void discoverPackage() {
        metadata = PackageMetadata.discover(List.of(adapter()));
        library = metadata.library();
    }

    private static Path adapter() {
        return Path.of(System.getProperty("phylospec.clockAdapter"));
    }

    private Analysis analysis(boolean gamma, boolean estimatedWeights) throws Exception {
        Path alignment = directory.resolve("data.nex");
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
        analysis.substitutionModel().generatorProperty().set(library.overloads("jc69").getFirst());
        analysis.likelihood().generatorProperty().set(library.overloads("alignmentMixture").getFirst());
        analysis.clockModel().param("meanRate").estimateProperty().set(true);
        analysis.clockModel().param("normalize").valueProperty().set("true");
        Param weights = analysis.likelihood().param("weights");
        weights.estimateProperty().set(estimatedWeights);
        weights.valueProperty().set("[0.3333333333333333, 0.6666666666666666]");
        if (gamma) {
            analysis.siteRates().generatorProperty().set(library.overloads("DiscreteGammaInv").getFirst());
            analysis.siteRates().param("numCategories").valueProperty().set("4");
        }
        analysis.chainLengthProperty().set("50");
        analysis.logEveryProperty().set("1");
        analysis.seedProperty().set("12345");
        analysis.logFileProperty().set(directory.resolve("clock-mixture.log").toString());
        return analysis;
    }

    @Test
    void presetOwnsCompositionButKeepsParametersAndModelTabsEditable() throws Exception {
        Analysis analysis = analysis(false, false);
        assertTrue(analysis.isClockMixture());
        assertEquals("sharedRatesClock", analysis.clockModel().name());
        assertEquals(List.of("sharedRatesClock"), analysis.choicesFor(Library.CLOCK_MODEL)
                .stream().map(generator -> generator.getName()).toList());
        assertTrue(analysis.likelihoodTakes("qMatrix"));
        assertTrue(analysis.likelihoodTakes("siteRates"));
        assertTrue(analysis.likelihoodNeeds("branchRates"));
        assertNull(analysis.likelihood().param("components"));
        assertEquals(2, analysis.likelihood().param("weights").dimension());
        assertTrue(analysis.clockModel().param("rates").isEstimated());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roundTripPreservesSharedParametersAndSeparateSiteRateObjects(boolean estimatedWeights) throws Exception {
        Analysis analysis = analysis(true, estimatedWeights);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(2, occurrences(script, "~ DiscreteGammaInv("), script);
        assertEquals(1, occurrences(script, "Rate meanRate ~"), script);
        assertTrue(script.contains("clockRate=meanRate"), script);
        assertEquals(1, occurrences(script, "PositiveReal shape ~"), script);
        assertTrue(script.contains("siteRates=siteRates"), script);
        assertTrue(script.contains("siteRates=relaxedSiteRates"), script);

        Analysis restored = ScriptReader.read(library, script);
        assertTrue(restored.isClockMixture());
        assertEquals(estimatedWeights, restored.likelihood().param("weights").isEstimated());
        assertEquals("sharedRatesClock", restored.clockModel().name());
        String again = ScriptWriter.write(restored);
        assertEquals(script, again);
    }

    @Test
    void roundTripWithoutSiteRatesOrExplicitMeanRate() throws Exception {
        Analysis analysis = analysis(false, false);
        analysis.clockModel().param("meanRate").includeProperty().set(false);
        String script = ScriptWriter.write(analysis);
        assertFalse(script.contains("DiscreteGammaInv"));
        assertTrue(script.contains("clockRate=1.0"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @Test
    void ordinaryLikelihoodRemainsAnOrdinaryModelAfterLeavingPreset() throws Exception {
        Analysis analysis = analysis(false, false);
        analysis.likelihood().generatorProperty().set(library.overloads("PhyloCTMC").getFirst());
        String script = ScriptWriter.write(analysis);
        assertFalse(script.contains("alignmentMixture"));
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertTrue(analysis.choicesFor(Library.CLOCK_MODEL).stream()
                .anyMatch(generator -> "StrictClock".equals(generator.getName())));
    }

    @Test
    void rejectsSeparateMeanRatesInsteadOfCollapsingThem() throws Exception {
        String script = ScriptWriter.write(analysis(false, false))
                .replace("clockRate=meanRate", "clockRate=0.8");
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertTrue(assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, script)).getMessage().contains("same expression"));
    }

    @Test
    void rejectsInlineWeightDrawInsteadOfChangingItToAnAssignment() throws Exception {
        String script = ScriptWriter.write(analysis(false, false)).replace(
                "weights=[0.3333333333333333, 0.6666666666666666]",
                "weights~Dirichlet(concentration=[1.0, 1.0])");
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertTrue(assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, script)).getMessage().contains("named variables"));
    }

    @Test
    void rejectsDifferentSiteRateParametersInsteadOfDroppingThem() throws Exception {
        String script = ScriptWriter.write(analysis(true, false));
        int split = script.indexOf("Vector<Rate> relaxedSiteRates");
        String different = script.substring(0, split)
                + script.substring(split).replaceFirst("shape=shape", "shape=0.7");
        assertEquals(List.of(), Validator.check(library, different).all(), different);
        assertTrue(assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, different)).getMessage().contains("site-rate"));
    }

    @Test
    void rejectsOneSharedSiteRateDrawInsteadOfSplittingItsSemantics() throws Exception {
        String script = ScriptWriter.write(analysis(true, false))
                .replace("siteRates=relaxedSiteRates", "siteRates=siteRates");
        assertTrue(assertThrows(ScriptReader.Unsupported.class,
                () -> ScriptReader.read(library, script)).getMessage().contains("site-rate"));
    }

    @Test
    void rejectsReversedComponentOrder() throws Exception {
        String script = ScriptWriter.write(analysis(false, false));
        int first = script.indexOf("[PhyloCTMC(") + 1;
        int second = script.indexOf(", PhyloCTMC(", first);
        int end = script.indexOf("]", second);
        String reversed = script.substring(0, first) + script.substring(second + 2, end)
                + ", " + script.substring(first, second) + script.substring(end);
        assertEquals(List.of(), Validator.check(library, reversed).all(), reversed);
        assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(library, reversed));
    }

    @Test
    void linkedPartitionsKeepOneMeanRateAndSeparateLikelihoods() throws Exception {
        Analysis analysis = analysis(true, false);
        Path other = directory.resolve("second.nex");
        Files.copy(directory.resolve("data.nex"), other);
        analysis.addPartition(other);
        String script = ScriptWriter.write(analysis);
        assertEquals(2, occurrences(script, "~ alignmentMixture("));
        assertEquals(1, occurrences(script, "Rate meanRate ~"));
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportedMixtureRunsInPackagedEngineAndWritesResponsibilities(boolean estimatedWeights) throws Exception {
        Analysis analysis = analysis(true, estimatedWeights);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        Path prefix = directory.resolve("clock-mixture");
        run(script, prefix);
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));
        assertFiniteTrace(Path.of(prefix + ".log"));
        Path diagnostic = Path.of(prefix + ".clockmodelaveraging.log");
        assertFiniteTrace(diagnostic);
        String log = Files.readString(diagnostic);
        for (String column : List.of("pStrict", "pRelaxUC", "pRelaxAC", "rb_ucld_num", "rb_sigma2_num")) {
            assertTrue(log.contains(column), log);
        }
        assertResponsibilities(diagnostic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ac", "ucln", "strict"})
    void exportedFixedClockRunsWithoutModelSwitching(String mode) throws Exception {
        Analysis analysis = analysis(true, false);
        analysis.likelihood().generatorProperty().set(library.overloads("PhyloCTMC").getFirst());
        if ("strict".equals(mode)) {
            analysis.clockModel().generatorProperty().set(library.overloads("StrictClock").getFirst());
            analysis.clockModel().param("clockRate").estimateProperty().set(false);
            analysis.clockModel().param("clockRate").valueProperty().set("1.0");
        } else {
            Component prior = analysis.clockModel().param("rates").priorProperty().get();
            prior.param("indicator").estimateProperty().set(false);
            prior.param("indicator").valueProperty().set("ac".equals(mode) ? "1" : "0");
            prior.param("rootLogRate").estimateProperty().set(false);
            prior.param("rootLogRate").valueProperty().set("0.0");
            prior.param("ac".equals(mode) ? "ucldStdev" : "sigma2").estimateProperty().set(false);
        }
        Path prefix = directory.resolve("clock-" + mode);
        analysis.logFileProperty().set(prefix + ".log");
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, script)));
        String output = run(script, prefix);
        assertFalse(output.contains("IndicatorGibbsOperator"), output);
        assertFalse(output.contains("UCACSwitchBridgeOperator"), output);
        assertFiniteTrace(Path.of(prefix + ".log"));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));
    }

    private String run(String script, Path prefix) throws Exception {
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
                                List.of("clockmodelaveraging", "beast2")).runPhyloSpec(args[1]);
                    }
                }
                """);
        Path output = directory.resolve("runner-output.txt");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", adapter().resolve("*").toString(), launcher.toString(),
                source.toString(), prefix.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Packaged analysis exceeded 30 seconds");
        assertEquals(0, process.exitValue(), Files.readString(output) + "\n" + script);
        assertTrue(Files.readString(output).contains("End likelihood:"), Files.readString(output));
        return Files.readString(output);
    }

    private static void assertFiniteTrace(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), file.toString());
        List<String> samples = Files.readAllLines(file).stream()
                .filter(line -> line.matches("\\s*\\d+\\s+.*")).toList();
        assertTrue(samples.size() >= 2, file.toString());
        for (String sample : samples) {
            for (String value : sample.trim().split("\\s+")) {
                assertTrue(Double.isFinite(Double.parseDouble(value)), sample);
            }
        }
    }

    private static long occurrences(String script, String text) {
        return script.split(java.util.regex.Pattern.quote(text), -1).length - 1;
    }

    private static void assertResponsibilities(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        List<String> columns = List.of(lines.stream().filter(line -> line.startsWith("Sample\t"))
                .findFirst().orElseThrow().split("\t"));
        List<Integer> indices = List.of("pStrict", "pRelaxUC", "pRelaxAC").stream()
                .map(name -> columns.indexOf(columns.stream().filter(column -> column.endsWith("." + name))
                        .findFirst().orElseThrow())).toList();
        for (String line : lines) {
            if (!line.matches("\\s*\\d+\\s+.*")) continue;
            String[] values = line.trim().split("\\s+");
            double total = 0.0;
            for (int index : indices) {
                double probability = Double.parseDouble(values[index]);
                assertTrue(probability >= 0.0 && probability <= 1.0, line);
                total += probability;
            }
            assertEquals(1.0, total, 2e-6, line);
        }
    }
}
