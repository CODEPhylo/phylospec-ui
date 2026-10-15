package org.phylospec.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** Complements the native-XML density comparison in CalibratedCPP's CatsReferenceIT. */
@EnabledIfSystemProperty(named = "phylospec.catsAnalysis", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.calibratedAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.clockJars", matches = ".+")
class CatsWorkflowTest {
    private static Analysis example() throws Exception {
        var metadata = PackageMetadata.discover(CalibratedClockTest.adapters());
        Path source = Path.of(System.getProperty("phylospec.catsAnalysis")).toAbsolutePath();
        assertTrue(Files.isRegularFile(source), "Run CatsReferenceIT to prepare the real analysis and data.");
        return ScriptReader.read(metadata.library(), metadata.support(), source);
    }

    @Test
    void realConfigurationRoundTripsAndRunsWithBothPackages() throws Exception {
        Analysis analysis = example();
        assertConfiguration(analysis);
        String original = ScriptWriter.write(analysis);
        assertEquals(original, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), original)));
        // An explicit UI edit, not an implicit change to the priors or clock discretisation.
        analysis.chainLengthProperty().set("30");
        Path directory = run(analysis, null);
        Analysis restored = ScriptReader.read(analysis.library(), analysis.support(), directory.resolve("analysis.phylospec"));
        assertConfiguration(restored);
    }

    @ParameterizedTest
    @CsvSource({"dimension,dimension", "taxon,absent from the tree"})
    void invalidUiEditsAreRejectedRatherThanSilentlyRepaired(String kind, String diagnostic) throws Exception {
        Analysis analysis = example();
        if (kind.equals("dimension")) {
            analysis.clockModel().param("rates").priorProperty().get().param("num").valueProperty().set("35");
        } else {
            Param clades = analysis.treePrior().param("calibrations").priorProperty().get().param("clades");
            var taxon = Pattern.compile("\"([^\"]+)\"").matcher(clades.valueProperty().get());
            assertTrue(taxon.find());
            clades.valueProperty().set(clades.valueProperty().get().replace(taxon.group(), "\"Missing_taxon\""));
        }
        run(analysis, diagnostic);
    }

    @Test
    @EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
    void existingTreeAndClockControlsPreserveTheRealConfiguration() throws Exception {
        Analysis analysis = example();
        String original = ScriptWriter.write(analysis);
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            BorderPane root = new BorderPane(ComponentPanel.build(analysis, "Cats calibrations", List.of(
                    ComponentPanel.Choice.of("Tree prior", Library.TREE_PRIOR, analysis.treePrior()))));
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1100, 800));
            stage.show();
            try {
                root.applyCss();
                root.layout();
                TextField clades = field(root, "Clades:");
                String originalClades = clades.getText();
                assertEquals(4, originalClades.split("calibrationClade\\(", -1).length - 1);
                clades.setText(originalClades + " ");
                assertEquals(originalClades + " ", analysis.treePrior().param("calibrations")
                        .priorProperty().get().param("clades").valueProperty().get());
                clades.setText(originalClades);
                TextField rho = field(root, "Rho:");
                rho.setText("0.02");
                assertEquals("0.02", analysis.treePrior().param("rho").valueProperty().get());
                rho.setText("0.01");
                assertFalse(analysis.treePrior().param("rho").isEstimated());
                root.setCenter(ComponentPanel.build(analysis, "Continuous clock", List.of(
                        ComponentPanel.Choice.of("Clock", Library.CLOCK_MODEL, analysis.clockModel()))));
                root.applyCss();
                root.layout();
                CheckBox estimate = row(root, "Mean rate:").filter(CheckBox.class::isInstance)
                        .map(CheckBox.class::cast).filter(box -> "estimate".equals(box.getText()))
                        .findFirst().orElseThrow();
                assertTrue(estimate.isSelected());
                estimate.fire();
                assertFalse(analysis.clockModel().param("meanRate").isEstimated());
                estimate.fire();
                assertConfiguration(analysis);
                return ScriptWriter.write(analysis);
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(interaction);
            assertEquals(original, interaction.get(30, TimeUnit.SECONDS));
            run(analysis, null);
        } finally {
            Platform.exit();
        }
    }

    private static void assertConfiguration(Analysis analysis) {
        assertEquals(19, analysis.partitions().getFirst().taxaProperty().get());
        assertEquals(30141, analysis.partitions().getFirst().sitesProperty().get());
        assertTrue(Files.isRegularFile(Path.of(analysis.partitions().getFirst().fileProperty().get())));
        Component tree = analysis.treePrior();
        assertEquals("CalibratedBirthDeathSkyline", tree.name());
        assertValue(tree, "rho", "0.01");
        assertValue(tree, "calibrationMode", "independentUniform");
        assertValue(tree, "conditionOnCalibrations", "false");
        assertTrue(tree.param("initialNewick").valueProperty().get().contains("DomesticCat_Felis_catus"));
        Component forest = tree.param("calibrations").priorProperty().get();
        assertEquals("calibrationForest", forest.name());
        String clades = forest.param("clades").valueProperty().get();
        assertEquals(4, clades.split("calibrationClade\\(", -1).length - 1);
        for (String range : List.of("lower=29.0, upper=35.0", "lower=5.3, upper=10.3",
                "lower=3.8, upper=8.2", "lower=1.5, upper=3.7")) assertTrue(clades.contains(range), clades);
        Param diversification = tree.param("diversificationRate").priorProperty().get().param("values").elements().getFirst();
        assertPrior(diversification, "LogNormal", "logMean", "0.05", "logSd", "1.0");
        Param turnover = tree.param("turnover").priorProperty().get().param("values").elements().getFirst();
        assertPrior(turnover, "Beta", "alpha", "0.05", "beta", "1.0");

        Component substitution = analysis.substitutionModel();
        assertEquals("gtr", substitution.name());
        for (String rate : List.of("rateAC", "rateAG", "rateAT", "rateCG", "rateCT")) {
            assertPrior(substitution.param(rate), "LogNormal", "logMean", "0.0", "logSd", "1.0");
        }
        assertValue(substitution, "rateGT", "1.0");
        Param frequencies = substitution.param("baseFrequencies");
        assertTrue(frequencies.isEstimated());
        assertEquals("Dirichlet", frequencies.priorProperty().get().name());
        assertValue(frequencies.priorProperty().get(), "concentration", "[3.0, 3.0, 3.0, 3.0]");
        assertEquals("DiscreteGammaInv", analysis.siteRates().name());
        assertPrior(analysis.siteRates().param("shape"), "LogNormal", "logMean", "0.0", "logSd", "2.0");
        assertValue(analysis.siteRates(), "numCategories", "4");
        assertValue(analysis.siteRates(), "invariantProportion", "0.0");
        assertValue(analysis.likelihood(), "useAmbiguities", "true");

        Component clock = analysis.clockModel();
        assertEquals("sharedRatesClock", clock.name());
        assertValue(clock, "normalize", "false");
        assertPrior(clock.param("meanRate"), "LogNormal", "logMean", "-9.0", "logSd", "5.0");
        assertTrue(clock.param("rates").isEstimated());
        Component iid = clock.param("rates").priorProperty().get();
        assertEquals("IID", iid.name());
        assertValue(iid, "num", "36");
        Component base = iid.param("base").priorProperty().get();
        assertEquals("LogNormal", base.name());
        assertValue(base, "mean", "1.0");
        assertPrior(base.param("logSd"), "LogNormal", "logMean", "-0.693", "logSd", "0.5");
        assertEquals(12, analysis.estimatedParams().size(), "Do not add, duplicate or drop sampled parameters.");
        assertTrue(analysis.support().supports(tree.generator()));
        assertTrue(analysis.support().supports(clock.generator()));
    }

    private static void assertPrior(Param param, String name, String a, String x, String b, String y) {
        assertTrue(param.isEstimated(), param.name());
        assertEquals(name, param.priorProperty().get().name(), param.name());
        assertValue(param.priorProperty().get(), a, x);
        assertValue(param.priorProperty().get(), b, y);
    }

    private static void assertValue(Component component, String name, String value) {
        Param param = component.param(name);
        assertNotNull(param, component.name() + "." + name);
        assertTrue(param.includeProperty().get(), name);
        assertFalse(param.isEstimated(), name);
        assertEquals(value, param.valueProperty().get(), name);
    }

    private static Path run(Analysis analysis, String diagnostic) throws Exception {
        Path parent = Files.createDirectories(Path.of("target", "cats-ui"));
        Path directory = Files.createTempDirectory(parent, diagnostic == null ? "accepted-" : "rejected-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), source)));
        String classpath = CalibratedClockTest.adapters().stream()
                .map(path -> Files.isDirectory(path) ? path.resolve("*") : path)
                .map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-Dbeast.debug=true", "-cp", classpath, "runner.PhyloSpecCli", "--library",
                "calibratedcpp,clockmodelaveraging,beast2", source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        String console = Files.readString(output);
        assertTrue(finished, "Timed out: " + output + "\n" + console);
        if (diagnostic != null) {
            assertNotEquals(0, process.exitValue(), console);
            assertTrue(console.contains(diagnostic), console);
            assertFalse(Files.exists(directory.resolve("analysis.state.xml")));
            return directory;
        }
        assertEquals(0, process.exitValue(), console);
        assertTrue(console.contains("End likelihood:"), console);
        int length = Integer.parseInt(analysis.chainLengthProperty().get());
        List<String> lines = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(length + 2, lines.size());
        List<String> header = List.of(lines.getFirst().trim().split("\\s+"));
        assertTrue(header.containsAll(List.of("posterior", "prior", "likelihood")), header.toString());
        for (int sample = 0; sample <= length; sample++) {
            String[] row = lines.get(sample + 1).trim().split("\\s+");
            assertEquals(header.size(), row.length);
            assertEquals(sample, Integer.parseInt(row[0]));
            for (int i = 1; i < row.length; i++) assertTrue(Double.isFinite(Double.parseDouble(row[i])), lines.get(sample + 1));
            assertEquals(Double.parseDouble(row[header.indexOf("prior")]) + Double.parseDouble(row[header.indexOf("likelihood")]),
                    Double.parseDouble(row[header.indexOf("posterior")]), 1e-6);
        }
        assertTrue(lines.stream().skip(1).map(line -> line.trim().split("\\s+")[header.indexOf("posterior")])
                .distinct().count() > 1, "The chain must accept a proposal.");
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_" + length));
        assertEquals(13, Files.readString(directory.resolve("analysis.state.xml")).split("<statenode ", -1).length - 1);
        return directory;
    }

    private static Stream<Node> row(Node root, String caption) {
        Label label = nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(node -> caption.equals(node.getText())).findFirst().orElseThrow();
        return ((GridPane) label.getParent()).getChildren().stream()
                .filter(node -> java.util.Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(label)))
                .flatMap(CatsWorkflowTest::nodes);
    }

    private static TextField field(Node root, String caption) {
        return row(root, caption).filter(TextField.class::isInstance).map(TextField.class::cast)
                .findFirst().orElseThrow();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(CatsWorkflowTest::nodes))
                : Stream.of(node);
    }
}
