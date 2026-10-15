package org.phylospec.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** A bounded delivery gate: one editable, runnable analysis per package, not every model variant. */
@EnabledIf("enabled")
class PackageDeliveryTest {
    static boolean enabled() {
        return Boolean.getBoolean("phylospec.deliveryTests") || Boolean.getBoolean("phylospec.acceptance");
    }

    @BeforeAll
    static void requireAllBundles() throws IOException {
        // Enabling this gate must never silently skip a missing package.
        for (String name : List.of("popfunc", "phylonco", "clock", "calibrated")) {
            String property = "phylospec." + name + "Adapter";
            Path bundle = Path.of(requiredProperty(property)).toAbsolutePath().normalize();
            assertTrue(Files.isDirectory(bundle), "Missing adapter bundle for -D" + property + ": " + bundle);
            try (var files = Files.list(bundle)) {
                assertTrue(files.anyMatch(file -> Files.isRegularFile(file) && file.toString().endsWith(".jar")),
                        "No packaged JARs in adapter bundle: " + bundle);
            }
        }
        if (!Boolean.getBoolean("phylospec.acceptance")) return;

        assertTrue(Runtime.version().feature() >= 25, "The package acceptance run requires JDK 25 or later.");
        for (String flag : List.of("phylospec.deliveryTests", "phylospec.fxTests")) {
            assertEquals("true", requiredProperty(flag), "Do not disable " + flag + " in an acceptance run.");
        }
        for (String reference : List.of("l86", "cats", "primates", "dated", "ancestral", "popfuncL86")) {
            String property = "phylospec." + reference + "Analysis";
            Path source = Path.of(requiredProperty(property)).toAbsolutePath().normalize();
            assertTrue(Files.isRegularFile(source), "Missing reference analysis for -D" + property + ": " + source
                    + ". Prepare the reference fixture using the README instructions; do not skip this check.");
        }
        for (String entry : requiredProperty("phylospec.clockJars").split(Pattern.quote(File.pathSeparator), -1)) {
            assertFalse(entry.isBlank(), "Empty classpath entry in phylospec.clockJars");
            assertTrue(Files.isRegularFile(Path.of(entry)), "Missing clock JAR: " + entry);
        }
    }

    private static String requiredProperty(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, "Supply -D" + property + "=<value>; see the acceptance profile in pom.xml");
        assertFalse(value.isBlank(), "Empty property: " + property);
        return value;
    }

    @ParameterizedTest(name = "{0}: UI file round-trip and packaged MCMC")
    @CsvSource({
            "popfunc, popfunc, popfunc/gompertz, gompertzF0PopulationFunction;Coalescent, initialProportion;growthRate;initialPopulationSize;ancestralPopulationSize",
            "phylonco, phylonco, phylonco/gt16-error, gt16;GT16ErrorModel, epsilon;delta",
            "clockmodelaveraging, clock, clockmodelaveraging/mixture, alignmentMixture;sharedRatesClock;svsRawBranchRates, indicator;ucldStdev;sigma2;rootLogRate",
            "calibratedcpp, calibrated, calibratedcpp/skyline, CalibratedBirthDeathSkyline;skylineParameter;calibrationForest, values1;values2"
    })
    void exampleRoundTripsAndRuns(String id, String property, String example,
                                 String generators, String parameters) throws Exception {
        // Keep each run (including failures) inspectable, without overwriting previous evidence.
        Path results = Files.createDirectories(Path.of("target", "package-delivery"));
        Path directory = Files.createTempDirectory(results, id + "-").toAbsolutePath();
        Path bundle = Path.of(System.getProperty("phylospec." + property + "Adapter")).toAbsolutePath();
        var metadata = PackageMetadata.discover(List.of(bundle));
        assertTrue(metadata.engines().stream().anyMatch(engine -> id.equals(engine.getName())),
                "The package must publish its engine capabilities: " + id);
        var library = metadata.library();
        var support = metadata.support();
        Path original = Path.of("examples", example + ".phylospec");
        var analysis = ScriptReader.read(library, support, original);
        for (var partition : analysis.partitions()) {
            assertTrue(Files.isRegularFile(Path.of(partition.fileProperty().get())),
                    "Example data must be available");
        }

        // The same models back the GUI tabs. Exercise an edit before saving and reopening the file.
        analysis.chainLengthProperty().set("30");
        analysis.logEveryProperty().set("1");
        analysis.logFileProperty().set("analysis.log");
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(library, script).all(), script);
        for (String generator : generators.split(";")) {
            assertTrue(script.contains(generator + "("), "Lost " + generator + ":\n" + script);
            assertTrue(library.overloads(generator).stream().anyMatch(support::supports),
                    "Missing supported component: " + generator);
        }
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(library, support, source)));

        Path consoleFile = directory.resolve("runner.txt");
        // No Maven test classes or dependencies: the installed bundle has to stand on its own.
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-cp", bundle.resolve("*").toString(),
                "runner.PhyloSpecCli", "--library", id + ",beast2", source.toString())
                .directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(consoleFile.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        String console = Files.readString(consoleFile);
        assertTrue(finished, "Packaged runner timed out:\n" + console);
        assertEquals(0, process.exitValue(), console + "\n" + script);
        assertTrue(console.contains("End likelihood:"), console);

        assertTrace(directory.resolve("analysis.log"),
                "posterior;prior;likelihood;" + parameters);
        String trees = Files.readString(directory.resolve("analysis.trees"));
        assertTrue(trees.contains("#NEXUS") && trees.contains("STATE_30"), trees);
        assertTrue(Files.size(directory.resolve("analysis.state.xml")) > 0);
        if (id.equals("clockmodelaveraging")) {
            assertTrue(console.contains("IndicatorGibbsOperator"), console);
            assertTrue(console.contains("UCACSwitchBridgeOperator"), console);
            assertTrace(directory.resolve("analysis.clockmodelaveraging.log"),
                    "dataAlignment_likelihood_hierarchy.pStrict;"
                            + "dataAlignment_likelihood_hierarchy.pRelaxUC;"
                            + "dataAlignment_likelihood_hierarchy.pRelaxAC");
        }
    }

    private static void assertTrace(Path path, String requiredColumns) throws Exception {
        List<String> trace = Files.readAllLines(path).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(32, trace.size(), "Expected header and samples 0 through 30: " + path);
        List<String> columns = Arrays.asList(trace.getFirst().trim().split("\\s+"));
        for (String column : requiredColumns.split(";")) {
            assertTrue(columns.contains(column), "Missing " + column + ": " + trace.getFirst());
        }
        for (int sample = 0; sample <= 30; sample++) {
            String row = trace.get(sample + 1);
            String[] values = row.trim().split("\\s+");
            assertEquals(columns.size(), values.length, row);
            assertEquals(sample, Integer.parseInt(values[0]), row);
            for (String column : requiredColumns.split(";")) {
                assertTrue(Double.isFinite(Double.parseDouble(values[columns.indexOf(column)])), row);
            }
        }
    }
}
