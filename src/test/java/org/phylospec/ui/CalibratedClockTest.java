package org.phylospec.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** UI model round-trip and actual packaged execution; no engine classes on Maven's test classpath. */
@EnabledIfSystemProperty(named = "phylospec.calibratedAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.clockJars", matches = ".+")
class CalibratedClockTest {
    @TempDir Path directory;

    static List<Path> adapters() {
        List<Path> paths = new ArrayList<>();
        paths.add(Path.of(System.getProperty("phylospec.calibratedAdapter")));
        Arrays.stream(System.getProperty("phylospec.clockJars").split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).forEach(paths::add);
        return paths;
    }

    static Analysis example() throws Exception {
        var metadata = PackageMetadata.discover(adapters());
        return ScriptReader.read(metadata.library(), metadata.support(),
                Path.of("examples/calibratedcpp/continuous.phylospec"));
    }

    @ParameterizedTest
    @CsvSource({"joint,false", "independentUniform,false", "joint,true", "independentUniform,true"})
    void continuousClockAndCalibrationModesRoundTripAndRun(String mode, boolean origin) throws Exception {
        Analysis analysis = example();
        var tree = analysis.treePrior();
        fixed(tree, "calibrationMode", mode);
        fixed(tree, "conditionOnCalibrations", String.valueOf(mode.equals("joint")));
        if (origin) {
            fixed(tree.param("calibrations").priorProperty().get(), "newick", "(D,B)DB[&lower=1.0,upper=2.0];");
            Param age = tree.param("origin");
            age.includeProperty().set(true);
            age.estimateProperty().set(true);
            fixed(age.priorProperty().get(), "logMean", "1.6");
            fixed(age.priorProperty().get(), "logSd", "0.5");
            fixed(tree, "initialOrigin", "5.0");
        }
        assertTrue(analysis.likelihood().param("useAmbiguities").includeProperty().get());
        assertEquals("true", analysis.likelihood().param("useAmbiguities").valueProperty().get());
        Param rates = analysis.clockModel().param("rates");
        assertEquals("IID", rates.priorProperty().get().name());
        Param sigma = rates.priorProperty().get().param("base").priorProperty().get().param("logSd");
        assertTrue(sigma.isEstimated());
        assertTrue(analysis.estimatedParams().contains(sigma));
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        var restored = ScriptReader.read(analysis.library(), analysis.support(), write(script));
        assertEquals(script, ScriptWriter.write(restored));
        assertTrue(script.contains("num=6"), script);
        assertTrue(script.contains("normalize=false"), script);
        run(script, true);
    }

    @Test
    void selectingIidAndItsBasePreservesEstimationPolicyAndExplicitDimension() throws Exception {
        Analysis analysis = example();
        Param rates = analysis.clockModel().param("rates");
        var iid = analysis.library().overloads("IID").getFirst();
        rates.priorProperty().set(Component.distributionFor(iid, rates, analysis.library(), analysis.support()));
        // The backend requires a fixed IID dimension; do not infer binary-tree semantics in the UI.
        fixed(rates.priorProperty().get(), "num", "6");
        Param base = rates.priorProperty().get().param("base");
        var realMean = analysis.library().overloads("LogNormal").stream()
                .filter(g -> g.getArguments().stream().anyMatch(a -> a.getName().equals("mean")))
                .findFirst().orElseThrow();
        base.priorProperty().set(Component.distributionFor(realMean, base, analysis.library(), analysis.support()));
        Component law = base.priorProperty().get();
        fixed(law, "mean", "1.0");
        Param sigma = law.param("logSd");
        assertTrue(sigma.estimable());
        sigma.estimateProperty().set(true);
        fixed(sigma.priorProperty().get(), "logMean", "-0.693");
        fixed(sigma.priorProperty().get(), "logSd", "0.5");
        assertFalse(sigma.priorProperty().get().param("logSd").estimable(),
                "Ordinary parameter priors must not acquire recursive estimation defaults");
        String script = ScriptWriter.write(analysis);
        assertTrue(script.contains("num=6"), script);
        assertFalse(script.contains("num=numBranches(tree)"), script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), script)));
        Component copy = Component.estimable(analysis.library(), analysis.support());
        Component.copy(analysis.clockModel(), copy);
        Param copied = copy.param("rates").priorProperty().get().param("base").priorProperty().get().param("logSd");
        assertTrue(copied.isEstimated());
        assertNotSame(sigma, copied);
        run(script, true);
    }

    @ParameterizedTest
    @CsvSource({"dimension,5", "mode,unknown", "origin,1.0"})
    void invalidConfigurationsAreNotSilentlyRepaired(String kind, String value) throws Exception {
        Analysis analysis = example();
        if (kind.equals("dimension")) fixed(analysis.clockModel().param("rates").priorProperty().get(), "num", value);
        if (kind.equals("mode")) fixed(analysis.treePrior(), "calibrationMode", value);
        if (kind.equals("origin")) {
            fixed(analysis.treePrior().param("calibrations").priorProperty().get(), "newick", "(D,B)DB[&lower=1.0,upper=2.0];");
            fixed(analysis.treePrior(), "origin", value);
        }
        String output = run(ScriptWriter.write(analysis), false);
        assertTrue(output.contains(switch (kind) {
            case "dimension" -> "dimension";
            case "mode" -> "calibrationMode";
            default -> "origin must be greater";
        }), output);
    }

    private Path write(String script) throws Exception {
        Path file = directory.resolve("analysis.phylospec");
        Files.writeString(file, script);
        return file;
    }

    private String run(String script, boolean success) throws Exception {
        String classpath = adapters().stream().map(path -> Files.isDirectory(path) ? path.resolve("*") : path)
                .map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path output = directory.resolve("runner.txt");
        Path prefix = directory.resolve("analysis");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-cp", classpath, "runner.PhyloSpecCli", "--library",
                "calibratedcpp,clockmodelaveraging,beast2", "--run-name", prefix.toString(), write(script).toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "Runner timed out: " + Files.readString(output));
        String result = Files.readString(output);
        if (!success) {
            assertNotEquals(0, process.exitValue(), result);
            assertFalse(Files.exists(Path.of(prefix + ".state.xml")));
            return result;
        }
        assertEquals(0, process.exitValue(), result + "\n" + script);
        var rows = Files.readAllLines(Path.of(prefix + ".log")).stream()
                .filter(line -> line.matches("[0-9]+\\s+.*")).map(line -> line.split("\\s+")).toList();
        assertEquals(21, rows.size());
        assertEquals("200", rows.getLast()[0]);
        for (var row : rows) for (int i = 1; i < row.length; i++) assertTrue(Double.isFinite(Double.parseDouble(row[i])));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".trees")));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));
        return result;
    }

    private static void fixed(Component component, String name, String value) {
        Param param = component.param(name);
        assertNotNull(param, name);
        param.includeProperty().set(true);
        param.estimateProperty().set(false);
        param.valueProperty().set(value);
    }
}
