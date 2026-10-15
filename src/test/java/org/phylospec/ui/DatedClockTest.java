package org.phylospec.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.panel.TipDatesPanel;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the full dated-data fixture verified by the clock adapter's DatedTreeWorkflowTest. */
@EnabledIfSystemProperty(named = "phylospec.clockAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.datedAnalysis", matches = ".+")
class DatedClockTest {
    private static Analysis example() throws Exception {
        Path source = Path.of(System.getProperty("phylospec.datedAnalysis")).toAbsolutePath();
        assertTrue(Files.isRegularFile(source), "Run DatedTreeWorkflowTest to prepare the reference input.");
        var metadata = PackageMetadata.discover(List.of(adapter()));
        return ScriptReader.read(metadata.library(), metadata.support(), source);
    }

    private static Path adapter() {
        return Path.of(System.getProperty("phylospec.clockAdapter")).toAbsolutePath();
    }

    @Test
    void referenceConfigurationRoundTripsAndRunsFromPackagedJars() throws Exception {
        Analysis analysis = example();
        assertConfiguration(analysis);
        Path directory = run(analysis, false);
        assertConfiguration(ScriptReader.read(analysis.library(), analysis.support(),
                directory.resolve("analysis.phylospec")));
    }

    @Test
    void unmatchedDateParserFailsWithoutSilentlyDroppingTheDates() throws Exception {
        Analysis analysis = example();
        Partition partition = analysis.partitions().getFirst();
        partition.parseModeProperty().set(Partition.ParseMode.REGEX);
        partition.regexProperty().set("^(Missing_taxon)$");
        run(analysis, true);
    }

    @Test
    @EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
    void existingTipDateControlsCanRecreateTheReferenceConfiguration() throws Exception {
        Analysis analysis = example();
        String original = ScriptWriter.write(analysis);
        Partition partition = analysis.partitions().getFirst();
        partition.useTipDatesProperty().set(false);
        partition.dateKindProperty().set(Partition.DateKind.DATE);
        partition.parseModeProperty().set(Partition.ParseMode.REGEX);
        partition.delimiterProperty().set("-");
        partition.partProperty().set("1");

        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            BorderPane root = new BorderPane(TipDatesPanel.build(analysis));
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1000, 750));
            stage.show();
            try {
                root.applyCss();
                root.layout();
                CheckBox use = nodes(root).filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                        .filter(box -> "Use tip dates".equals(box.getText())).findFirst().orElseThrow();
                assertFalse(use.isSelected());
                use.fire();
                DatedClockTest.<Partition.DateKind>chooser(root, "Interpret as:").setValue(Partition.DateKind.AGE);
                DatedClockTest.<Partition.ParseMode>chooser(root, "Read date by:").setValue(Partition.ParseMode.SPLIT);
                field(root, "Delimiter:").setText("_");
                field(root, "Part:").setText("2");
                assertConfiguration(analysis);
                return ScriptWriter.write(analysis);
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(interaction);
            assertEquals(original, interaction.get(30, TimeUnit.SECONDS),
                    "Reconfiguring dates must not change the priors, shared clock or likelihood.");
            run(analysis, false);
        } finally {
            Platform.exit();
        }
    }

    private static void assertConfiguration(Analysis analysis) {
        assertEquals(1, analysis.partitions().size());
        Partition partition = analysis.partitions().getFirst();
        assertEquals(31, partition.taxaProperty().get());
        assertEquals(600, partition.sitesProperty().get());
        Path expectedData = Path.of(System.getProperty("phylospec.datedAnalysis")).toAbsolutePath()
                .getParent().resolve("dated.nex");
        assertEquals(expectedData.normalize(), Path.of(partition.fileProperty().get()).normalize());
        assertTrue(partition.useTipDatesProperty().get());
        assertEquals(Partition.DateKind.AGE, partition.dateKindProperty().get());
        assertEquals(Partition.ParseMode.SPLIT, partition.parseModeProperty().get());
        assertEquals("_", partition.delimiterProperty().get());
        assertEquals("2", partition.partProperty().get());
        assertFalse(partition.useSpeciesProperty().get());

        assertTrue(analysis.isClockMixture());
        assertEquals("Coalescent", analysis.treePrior().name());
        Component population = analysis.treePrior().param("populationSize").priorProperty().get();
        assertEquals("constantPopulationFunction", population.name());
        assertLogNormal(population.param("populationSize"), "1.2", "0.6");
        assertEquals("jc69", analysis.substitutionModel().name());
        assertEquals("DiscreteGammaInv", analysis.siteRates().name());
        assertLogNormal(analysis.siteRates().param("shape"), "-1.0", "0.7");
        assertValue(analysis.siteRates(), "numCategories", "4");
        Param invariant = analysis.siteRates().param("invariantProportion");
        assertFalse(invariant.includeProperty().get(), "Keep the reference's omitted, default-zero invariant proportion.");
        assertFalse(invariant.isEstimated());
        assertEquals(0.0, Double.parseDouble(invariant.valueProperty().get()));
        assertValue(analysis.likelihood(), "weights", "[0.3333333333333333, 0.6666666666666666]");
        assertValue(analysis.likelihood(), "useAmbiguities", "true");

        Component clock = analysis.clockModel();
        assertEquals("sharedRatesClock", clock.name());
        assertValue(clock, "normalize", "true");
        assertLogNormal(clock.param("meanRate"), "-5.0", "0.5");
        assertTrue(clock.param("rates").isEstimated());
        Component rates = clock.param("rates").priorProperty().get();
        assertEquals("svsRawBranchRates", rates.name());
        assertLogNormal(rates.param("sigma2"), "-4.0", "1.0");
        assertLogNormal(rates.param("ucldStdev"), "-1.1", "0.6");
        assertValue(rates, "rootLogRate", "0.0");
        assertTrue(rates.param("indicator").isEstimated());
        Component indicator = rates.param("indicator").priorProperty().get();
        assertEquals("DiscreteUniform", indicator.name());
        assertValue(indicator, "lower", "0");
        assertValue(indicator, "upper", "1");
        assertEquals(7, analysis.estimatedParams().size(), "Keep seven parameter states plus the estimated tree.");
    }

    private static void assertLogNormal(Param param, String mean, String sd) {
        assertTrue(param.isEstimated(), param.name());
        Component prior = param.priorProperty().get();
        assertEquals("LogNormal", prior.name());
        assertValue(prior, "logMean", mean);
        assertValue(prior, "logSd", sd);
    }

    private static void assertValue(Component component, String name, String expected) {
        Param param = component.param(name);
        assertNotNull(param, component.name() + "." + name);
        assertTrue(param.includeProperty().get(), name);
        assertFalse(param.isEstimated(), name);
        assertEquals(expected, param.valueProperty().get(), name);
    }

    private static Path run(Analysis analysis, boolean invalidDates) throws Exception {
        Path parent = Files.createDirectories(Path.of("target", "dated-clock"));
        Path directory = Files.createTempDirectory(parent, invalidDates ? "rejected-" : "accepted-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        Path source = directory.resolve("analysis.phylospec");
        Files.writeString(source, script);
        assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), source)));
        assertEquals(1, script.split("Rate meanRate ~", -1).length - 1, script);
        assertTrue(script.contains("clockRate=meanRate"), script);
        assertEquals(2, script.split("~ DiscreteGammaInv\\(", -1).length - 1, script);
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-Dbeast.debug=true", "-cp", adapter().resolve("*").toString(),
                "runner.PhyloSpecCli", "--library", "clockmodelaveraging,beast2", source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(60, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        String console = Files.readString(output);
        assertTrue(finished, "Timed out: " + output + "\n" + console);
        if (invalidDates) {
            assertNotEquals(0, process.exitValue(), console);
            assertTrue(console.contains("Regex cannot be matched"), console);
            assertFalse(Files.exists(directory.resolve("analysis.state.xml")));
            return directory;
        }
        assertEquals(0, process.exitValue(), console);
        assertTrue(console.contains("End likelihood:"), console);
        for (String operator : List.of("IndicatorGibbsOperator", "UCACSwitchBridgeOperator",
                "ACSigma2NonCenteredOperator", "UCLDStdevNonCenteredOperator", "UpDownOperator")) {
            assertTrue(console.contains(operator), console);
        }
        int length = Integer.parseInt(analysis.chainLengthProperty().get());
        List<String> lines = trace(directory.resolve("analysis.log"), length);
        List<String> header = List.of(lines.getFirst().trim().split("\\s+"));
        for (String row : lines.subList(1, lines.size())) {
            String[] values = row.trim().split("\\s+");
            assertEquals(Double.parseDouble(values[header.indexOf("prior")])
                            + Double.parseDouble(values[header.indexOf("likelihood")]),
                    Double.parseDouble(values[header.indexOf("posterior")]), 1e-6);
        }
        assertTrue(lines.stream().skip(1).map(line -> line.trim().split("\\s+")[header.indexOf("posterior")])
                .distinct().count() > 1, "The short chain must accept a proposal.");
        List<String> diagnostics = trace(directory.resolve("analysis.clockmodelaveraging.log"), length);
        List<String> columns = List.of(diagnostics.getFirst().trim().split("\\s+"));
        List<Integer> probabilities = List.of("pStrict", "pRelaxUC", "pRelaxAC").stream()
                .map(name -> columns.indexOf(columns.stream().filter(column -> column.endsWith("." + name))
                        .findFirst().orElseThrow())).toList();
        for (String row : diagnostics.subList(1, diagnostics.size())) {
            String[] values = row.trim().split("\\s+");
            double total = 0.0;
            for (int index : probabilities) {
                double probability = Double.parseDouble(values[index]);
                assertTrue(probability >= 0.0 && probability <= 1.0, row);
                total += probability;
            }
            assertEquals(1.0, total, 2e-6, row);
        }
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_" + length));
        assertEquals(8, Files.readString(directory.resolve("analysis.state.xml")).split("<statenode ", -1).length - 1);
        return directory;
    }

    private static List<String> trace(Path file, int length) throws Exception {
        List<String> lines = Files.readAllLines(file).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(length + 2, lines.size(), file.toString());
        int columns = lines.getFirst().trim().split("\\s+").length;
        for (int sample = 0; sample <= length; sample++) {
            String[] values = lines.get(sample + 1).trim().split("\\s+");
            assertEquals(columns, values.length);
            assertEquals(sample, Integer.parseInt(values[0]));
            for (String value : values) assertTrue(Double.isFinite(Double.parseDouble(value)), lines.get(sample + 1));
        }
        return lines;
    }

    private static Stream<Node> row(Node root, String caption) {
        Label label = nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(node -> caption.equals(node.getText())).findFirst().orElseThrow();
        return ((GridPane) label.getParent()).getChildren().stream()
                .filter(node -> java.util.Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(label)))
                .flatMap(DatedClockTest::nodes);
    }

    private static TextField field(Node root, String caption) {
        return row(root, caption).filter(TextField.class::isInstance).map(TextField.class::cast)
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> chooser(Node root, String caption) {
        return (ComboBox<T>) row(root, caption).filter(ComboBox.class::isInstance).findFirst().orElseThrow();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(DatedClockTest::nodes))
                : Stream.of(node);
    }
}
