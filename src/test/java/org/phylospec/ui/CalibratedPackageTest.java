package org.phylospec.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** The actual UI model/editor path, executed in a separate JVM using only the packaged adapter. */
@EnabledIfSystemProperty(named = "phylospec.calibratedAdapter", matches = ".+")
class CalibratedPackageTest {
    private static final String FOREST =
            "((A,B)AB[&lower=1.0,upper=2.0],C,D)ABCD[&lower=2.5,upper=4.0];";
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"constant,true", "constant,false", "skyline,true", "skyline,false",
            "estimated,true", "estimated,false", "mixed,true", "weibull,true", "gamma,false",
            "erlang,true", "exponential,false"})
    void uiConfigurationRoundTripsAndRuns(String kind, boolean conditioned) throws Exception {
        Analysis analysis = analysis(kind, conditioned);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        String reloaded = ScriptWriter.write(ScriptReader.read(analysis.library(), script));
        assertEquals(script, reloaded, "Saving and reopening must preserve parameters and priors.");
        assertTrue(script.contains("calibrationForest("), script);
        assertTrue(script.contains("conditionOnCalibrations=" + conditioned), script);
        if (kind.equals("estimated") || kind.equals("mixed")) {
            assertTrue(script.contains("~ Normal("), script);
            assertTrue(script.contains("~ OrderedUniform("), script);
        }
        String output = run(script, true);
        assertTrue(output.contains("End likelihood:"), output);
        if (kind.equals("estimated") || kind.equals("mixed")) {
            assertTrue(output.contains("RealRandomWalkOperator"), output);
            assertTrue(output.contains("ChangeTimeOperator"), output);
        }
        Path prefix = directory.resolve("analysis");
        assertTrue(Files.isRegularFile(Path.of(prefix + ".trees")));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));
        var lines = Files.readAllLines(Path.of(prefix + ".log"));
        var header = List.of(lines.stream().filter(line -> line.startsWith("Sample\t"))
                .findFirst().orElseThrow().trim().split("\\s+"));
        var rows = lines.stream().filter(line -> line.matches("\\s*\\d+\\s+.*"))
                .map(line -> line.trim().split("\\s+")).toList();
        assertEquals(21, rows.size());
        assertEquals("200", rows.getLast()[0]);
        for (var row : rows) {
            double posterior = Double.parseDouble(row[header.indexOf("posterior")]);
            double prior = Double.parseDouble(row[header.indexOf("prior")]);
            double likelihood = Double.parseDouble(row[header.indexOf("likelihood")]);
            assertTrue(Double.isFinite(posterior) && Double.isFinite(prior) && Double.isFinite(likelihood));
            assertEquals(prior + likelihood, posterior, 1e-6);
        }
        if (kind.equals("estimated") || kind.equals("mixed")) {
            for (Param param : analysis.estimatedParams()) {
                if (!param.name().startsWith("values")) continue;
                int column = header.indexOf(param.variableProperty().get());
                assertTrue(column >= 0, header.toString());
                assertTrue(rows.stream().map(row -> row[column]).distinct().count() > 1, param.name());
            }
        }
        String export = System.getProperty("phylospec.calibratedOutput");
        if (export != null) {
            Path folder = Path.of(export).resolve(kind + "-" + conditioned);
            Files.createDirectories(folder);
            Files.copy(directory.resolve("four-taxa.nex"), folder.resolve("four-taxa.nex"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(folder.resolve("analysis.phylospec"),
                    script.replace(directory.resolve("four-taxa.nex").toString(),
                            folder.resolve("four-taxa.nex").toString()));
        }
    }

    @ParameterizedTest
    @CsvSource({"rates,Exactly TWO", "reciprocal,Cannot specify both",
            "negative,birthRate", "times,dimension equal", "rho,0 < rho",
            "calibration,absent from the tree", "lifetime,exactly one", "grid,between 4 and 20000"})
    void backendRejectsInvalidUiConfigurations(String problem, String message) throws Exception {
        String kind = problem.equals("lifetime") || problem.equals("grid") ? "weibull" : "skyline";
        Analysis analysis = analysis(kind, true);
        Component tree = analysis.treePrior();
        switch (problem) {
            case "rates" -> tree.param("diversificationRate").includeProperty().set(false);
            case "reciprocal" -> {
                tree.param("birthRate").includeProperty().set(false);
                tree.param("diversificationRate").includeProperty().set(false);
                tree.param("reproductiveNumber").includeProperty().set(true);
                tree.param("turnover").includeProperty().set(true);
            }
            case "negative" -> fixed(tree.param("birthRate").priorProperty().get(), "values", "[-1.0]");
            case "times" -> fixed(tree.param("diversificationRate").priorProperty().get(), "changeTimes", "[1.5,1.0]");
            case "rho" -> fixed(tree, "rho", "0.0");
            case "calibration" -> fixed(tree.param("calibrations").priorProperty().get(), "newick",
                    FOREST.replace("D)", "Missing)"));
            case "lifetime" -> fixed(tree, "reproductiveNumber", "1.0");
            case "grid" -> fixed(tree, "gridSize", "3");
            default -> throw new IllegalArgumentException(problem);
        }
        String output = run(ScriptWriter.write(analysis), false);
        assertTrue(output.contains(message), output);
        assertFalse(Files.exists(directory.resolve("analysis.state.xml")), output);
    }

    @Test
    void metadataExposesOnlyImplementedTreeArgumentsAndFixedControls() throws Exception {
        var metadata = PackageMetadata.discover(List.of(adapter()));
        var library = metadata.library();
        for (String name : List.of("CalibratedBirthDeath", "CalibratedBirthDeathSkyline", "CalibratedAgeDependentExtinction")) {
            var generator = library.overloads(name).getFirst();
            assertTrue(metadata.support().supports(generator));
            var component = Component.estimable(library, metadata.support());
            component.generatorProperty().set(generator);
            assertNotNull(component.param("origin"));
            assertTrue(component.param("origin").estimable());
            assertFalse(component.param("initialOrigin").estimable());
            assertFalse(component.param("calibrationMode").estimable());
            assertFalse(component.param("conditionOnCalibrations").estimable());
            assertFalse(component.param("initialNewick").estimable());
            assertEquals("calibrationForest", component.param("calibrations").priorProperty().get().name());
        }
        Analysis analysis = analysis("erlang", true);
        assertFalse(analysis.treePrior().param("lifetime").priorProperty().get().param("shape").estimable());
        assertFalse(analysis.treePrior().param("gridSize").estimable());
    }

    @Test
    void unlinkingCopiesEntryPriorsWithoutSharingMutableEditors() throws Exception {
        var analysis = analysis("estimated", true);
        Component original = analysis.treePrior();
        Component copy = Component.estimable(analysis.library(), analysis.support());
        Component.copy(original, copy);
        Param source = original.param("diversificationRate").priorProperty().get().param("values").elements().getFirst();
        Param target = copy.param("diversificationRate").priorProperty().get().param("values").elements().getFirst();
        assertNotSame(source, target);
        assertNotSame(source.priorProperty().get(), target.priorProperty().get());
        assertTrue(target.isEstimated());
        target.priorProperty().get().param("mean").valueProperty().set("-0.8");
        assertEquals("-0.3", source.priorProperty().get().param("mean").valueProperty().get());
        Component.copy(original, copy);
        assertEquals(2, copy.param("diversificationRate").priorProperty().get().param("values").elements().size());
    }

    @Test
    void openingAFilePreservesCapabilitiesAndResolvesRelativeData() throws Exception {
        var analysis = analysis("estimated", true);
        String script = ScriptWriter.write(analysis).replace(directory.resolve("four-taxa.nex").toString(), "four-taxa.nex");
        Path file = directory.resolve("relative.phylospec");
        Files.writeString(file, script);
        var loaded = ScriptReader.read(analysis.library(), analysis.support(), file);
        assertSame(analysis.support(), loaded.support());
        assertEquals(directory.resolve("four-taxa.nex").toString(), loaded.partitions().getFirst().fileProperty().get());
        assertEquals(ScriptWriter.write(analysis), ScriptWriter.write(loaded));
    }

    @Test
    void repeatedSampledEntryIsNotSilentlyConvertedIntoIndependentPriors() throws Exception {
        var analysis = analysis("estimated", true);
        String script = ScriptWriter.write(analysis).replace("values=[values1, values2]", "values=[values1, values1]");
        var error = assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(analysis.library(), script));
        assertTrue(error.getMessage().contains("Repeated sampled vector entry"), error.getMessage());
    }

    @Test
    void aSampledEntrySharedAcrossVectorsIsNotDuplicated() throws Exception {
        var analysis = analysis("estimated", true);
        String script = ScriptWriter.write(analysis)
                .replace("    SkylineParameter birthRate = skylineParameter(values=[1.2])\n", "")
                .replace("    Vector<NonNegativeReal> changeTimes",
                        "    SkylineParameter birthRate = skylineParameter(values=[values1])\n"
                                + "    Vector<NonNegativeReal> changeTimes");
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        var error = assertThrows(ScriptReader.Unsupported.class, () -> ScriptReader.read(analysis.library(), script));
        assertTrue(error.getMessage().contains("Repeated sampled vector entry values1"), error.getMessage());
    }

    private Analysis analysis(String kind, boolean conditioned) throws Exception {
        var metadata = PackageMetadata.discover(List.of(adapter()));
        Library library = metadata.library();
        Path alignment = directory.resolve("four-taxa.nex");
        Files.copy(Path.of("examples/calibratedcpp/four-taxa.nex"), alignment,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Analysis analysis = new Analysis(library, metadata.support());
        analysis.addPartition(alignment);
        analysis.substitutionModel().generatorProperty().set(library.overloads("jc69").getFirst());
        analysis.clockModel().generatorProperty().set(library.overloads("StrictClock").getFirst());
        fixed(analysis.clockModel(), "clockRate", "0.1");
        String name = kind.equals("constant") ? "CalibratedBirthDeath"
                : List.of("skyline", "estimated", "mixed").contains(kind) ? "CalibratedBirthDeathSkyline"
                : "CalibratedAgeDependentExtinction";
        var generator = library.overloads(name).getFirst();
        assertTrue(analysis.choicesFor(Library.TREE_PRIOR).contains(generator));
        Component tree = analysis.treePrior();
        tree.generatorProperty().set(generator);
        fixed(tree.param("calibrations").priorProperty().get(), "newick", FOREST);
        fixed(tree, "rho", "1.0");
        fixed(tree, "conditionOnCalibrations", String.valueOf(conditioned));
        if (kind.equals("constant")) {
            fixed(tree, "birthRate", "1.0");
            fixed(tree, "deathRate", "0.2");
        } else if (List.of("skyline", "estimated", "mixed").contains(kind)) {
            tree.param("birthRate").includeProperty().set(true);
            fixed(tree.param("birthRate").priorProperty().get(), "values", "[1.2]");
            tree.param("diversificationRate").includeProperty().set(true);
            Component rates = tree.param("diversificationRate").priorProperty().get();
            fixed(rates, "values", "[-0.3, -0.5]");
            fixed(rates, "changeTimes", "[0.5]");
            fixed(rates, "timesAreRelative", "true");
            if (kind.equals("estimated") || kind.equals("mixed")) {
                rates.param("values").editElements(library);
                for (Param entry : rates.param("values").elements()) {
                    entry.estimateProperty().set(true);
                    assertEquals("Normal", entry.priorProperty().get().name());
                    fixed(entry.priorProperty().get(), "mean", "-0.3");
                    fixed(entry.priorProperty().get(), "sd", "0.4");
                }
                if (kind.equals("mixed")) rates.param("values").elements().get(1).estimateProperty().set(false);
                Param times = rates.param("changeTimes");
                times.estimateProperty().set(true);
                times.priorProperty().set(Component.prior(library.overloads("OrderedUniform").getFirst(),
                        null, library, times.priorSupport()));
                fixed(times.priorProperty().get(), "count", "1");
                fixed(times.priorProperty().get(), "upper", "1.0");
            }
        } else {
            fixed(tree, "birthRate", "0.8");
            Param lifetime = tree.param("lifetime");
            lifetime.priorProperty().set(lifetime.nestedChoice(library.overloads(kind + "Lifetime").getFirst(),
                    library, metadata.support()));
            Component law = lifetime.priorProperty().get();
            if (kind.equals("exponential")) fixed(law, "mean", "1.4");
            else {
                fixed(law, "shape", kind.equals("erlang") ? "2" : "0.7");
                fixed(law, "scale", "1.4");
            }
        }
        analysis.chainLengthProperty().set("200");
        analysis.logEveryProperty().set("10");
        analysis.seedProperty().set("12345");
        analysis.logFileProperty().set("analysis.log");
        return analysis;
    }

    private static void fixed(Component component, String name, String value) {
        Param param = component.param(name);
        assertNotNull(param, name);
        param.includeProperty().set(true);
        param.estimateProperty().set(false);
        param.valueProperty().set(value);
    }

    private static Path adapter() {
        return Path.of(System.getProperty("phylospec.calibratedAdapter")).toAbsolutePath();
    }

    private String run(String script, boolean success) throws Exception {
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", adapter().resolve("*").toString(), "runner.PhyloSpecCli",
                "--library", "calibratedcpp,beast2", "--run-name", directory.resolve("analysis").toString(),
                source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Packaged runner exceeded 60 seconds; output: " + Files.readString(output));
        String result = Files.readString(output);
        if (success) assertEquals(0, process.exitValue(), result);
        else assertNotEquals(0, process.exitValue(), result);
        return result;
    }
}
