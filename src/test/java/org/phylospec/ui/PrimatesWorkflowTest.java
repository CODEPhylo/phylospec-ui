package org.phylospec.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** UI preservation and execution of the selected primates uniform-rep1 analysis, not convergence. */
@EnabledIfSystemProperty(named = "phylospec.primatesAnalysis", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.calibratedAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.clockJars", matches = ".+")
class PrimatesWorkflowTest {
    private static Analysis example() throws Exception {
        var metadata = PackageMetadata.discover(CalibratedClockTest.adapters());
        Path source = Path.of(System.getProperty("phylospec.primatesAnalysis")).toAbsolutePath();
        assertTrue(Files.isRegularFile(source), "Run PrimatesReferenceIT to prepare the selected real data.");
        return ScriptReader.read(metadata.library(), metadata.support(), source);
    }

    @Test
    void preservesTheSelectedThreePartitionConfiguration() throws Exception {
        Analysis analysis = example();
        assertConfiguration(analysis);
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
        Analysis reloaded = ScriptReader.read(analysis.library(), analysis.support(), script);
        assertConfiguration(reloaded);
        assertEquals(script, ScriptWriter.write(reloaded));
    }

    @Test
    @EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
    void actualTabsExposeAllPartitionsAndEditableVectorReferences() throws Exception {
        Analysis analysis = example();
        String original = ScriptWriter.write(analysis);
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            // Exercise the application's tab assembly too: it must not replace grouped forms
            // with a form for only the first partition when the likelihood is selected.
            PhyloSpecUI app = new PhyloSpecUI();
            var field = PhyloSpecUI.class.getDeclaredField("analysis");
            field.setAccessible(true);
            field.set(app, analysis);
            var builder = PhyloSpecUI.class.getDeclaredMethod("buildTabs");
            builder.setAccessible(true);
            TabPane tabs = (TabPane) builder.invoke(app);
            Stage stage = new Stage();
            stage.setScene(new Scene(tabs, 1100, 800));
            stage.show();
            try {
                var siteTab = tabs.getTabs().stream().filter(tab -> tab.getText().equals("Site Model")).findFirst().orElseThrow();
                tabs.getSelectionModel().select(siteTab);
                tabs.applyCss();
                tabs.layout();
                assertEquals(3, descendants(siteTab.getContent()).filter(Label.class::isInstance)
                        .map(Label.class::cast).filter(label -> label.getText().startsWith("Site model for codon_")).count());
                for (int i = 0; i < 3; i++) {
                    Param rate = analysis.partitions().get(i).siteModel().mutationRate();
                    TextField index = descendants(siteTab.getContent()).filter(TextField.class::isInstance)
                            .map(TextField.class::cast).filter(input -> input.getUserData() == rate).findFirst().orElseThrow();
                    assertEquals(Integer.toString(i + 1), index.getText());
                    assertFalse(index.isDisabled());
                }
                Param firstRate = analysis.siteModels().getFirst().mutationRate();
                MenuButton menu = descendants(siteTab.getContent()).filter(MenuButton.class::isInstance)
                        .map(MenuButton.class::cast).filter(button -> button.getUserData() == firstRate).findFirst().orElseThrow();
                menu.show();
                var item = menu.getItems().stream().filter(choice -> choice.getUserData() == firstRate.referenceProperty().get())
                        .findFirst().orElseThrow();
                assertTrue(item.getText().contains("[index]"));
                item.fire();
                menu.hide();
                TextField index = descendants(siteTab.getContent()).filter(TextField.class::isInstance)
                        .map(TextField.class::cast).filter(input -> input.getUserData() == firstRate).findFirst().orElseThrow();
                index.setText("2");
                assertTrue(ScriptWriter.write(analysis).contains("mutationRate=r[2]"));
                index.setText("0");
                assertThrows(IllegalStateException.class, () -> ScriptWriter.write(analysis));
                index.setText("1");
                var priors = tabs.getTabs().stream().filter(tab -> tab.getText().equals("Priors")).findFirst().orElseThrow();
                tabs.getSelectionModel().select(priors);
                tabs.applyCss();
                tabs.layout();
                assertConfiguration(analysis);
                return ScriptWriter.write(analysis);
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(interaction);
            assertEquals(original, interaction.get(30, TimeUnit.SECONDS));
        } finally {
            Platform.exit();
        }
        run(analysis);
    }

    private static Stream<Node> descendants(Node node) {
        return Stream.concat(Stream.of(node), node instanceof Parent parent
                ? parent.getChildrenUnmodifiable().stream().flatMap(PrimatesWorkflowTest::descendants) : Stream.empty());
    }

    private static void assertConfiguration(Analysis analysis) {
        assertEquals(3, analysis.partitions().size());
        assertEquals(3, analysis.siteModels().size());
        assertEquals(1, analysis.clockModels().size());
        assertEquals(1, analysis.trees().size());
        assertEquals(2, analysis.sharedVectors().size());
        assertEquals(11, analysis.estimatedParams().size(), "The tree is structural; retain all 11 other sampled values.");
        Param kappa = analysis.sharedVectors().stream().filter(p -> p.name().equals("kappa")).findFirst().orElseThrow();
        Param rates = analysis.sharedVectors().stream().filter(p -> p.name().equals("r")).findFirst().orElseThrow();
        assertEquals("IID", kappa.priorProperty().get().name());
        assertEquals("3", kappa.priorProperty().get().param("num").valueProperty().get());
        Component kappaBase = kappa.priorProperty().get().param("base").priorProperty().get();
        assertEquals("LogNormal", kappaBase.name());
        assertEquals("1.0", kappaBase.param("logMean").valueProperty().get());
        assertEquals("1.25", kappaBase.param("logSd").valueProperty().get());
        assertEquals("WeightedDirichlet", rates.priorProperty().get().name());
        assertEquals("[1.0, 1.0, 1.0]", rates.priorProperty().get().param("concentration").valueProperty().get());
        assertEquals("[133154, 133154, 133154]", rates.priorProperty().get().param("weights").valueProperty().get());
        Param shape = analysis.siteModels().getFirst().siteRates().param("shape");
        for (int i = 0; i < 3; i++) {
            var partition = analysis.partitions().get(i);
            assertEquals(29, partition.taxaProperty().get());
            assertEquals(133154, partition.sitesProperty().get());
            assertTrue(Files.isRegularFile(Path.of(partition.fileProperty().get())));
            var site = partition.siteModel();
            assertSame(kappa, site.substitutionModel().param("kappa").referenceProperty().get());
            assertEquals(i + 1, site.substitutionModel().param("kappa").referenceIndexProperty().get());
            assertSame(rates, site.mutationRate().referenceProperty().get());
            assertEquals(i + 1, site.mutationRate().referenceIndexProperty().get());
            assertEquals("Dirichlet", site.substitutionModel().param("baseFrequencies").priorProperty().get().name());
            assertEquals("[3.0, 3.0, 3.0, 3.0]", site.substitutionModel().param("baseFrequencies")
                    .priorProperty().get().param("concentration").valueProperty().get());
            assertEquals("DiscreteGammaInv", site.siteRates().name());
            assertEquals("4", site.siteRates().param("numCategories").valueProperty().get());
            assertEquals("0.0", site.siteRates().param("invariantProportion").valueProperty().get());
            if (i > 0) assertSame(shape, site.siteRates().param("shape").referenceProperty().get());
        }
        assertEquals("CalibratedBirthDeathSkyline", analysis.treePrior().name());
        assertPrior(shape, "LogNormal", "logMean", "0.0", "logSd", "2.0");
        assertPrior(analysis.treePrior().param("diversificationRate").priorProperty().get().param("values").elements().getFirst(),
                "LogNormal", "logMean", "0.0", "logSd", "1.25");
        assertPrior(analysis.treePrior().param("turnover").priorProperty().get().param("values").elements().getFirst(),
                "Beta", "alpha", "1.0", "beta", "1.0");
        assertEquals("0.05", analysis.treePrior().param("rho").valueProperty().get());
        assertEquals("true", analysis.treePrior().param("conditionOnCalibrations").valueProperty().get());
        assertEquals("independentUniform", analysis.treePrior().param("calibrationMode").valueProperty().get());
        String newick = analysis.treePrior().param("calibrations").priorProperty().get().param("newick").valueProperty().get();
        assertEquals(9, newick.split("lower", -1).length - 1);
        assertEquals("sharedRatesClock", analysis.clockModel().name());
        assertEquals("false", analysis.clockModel().param("normalize").valueProperty().get());
        assertEquals("56", analysis.clockModel().param("rates").priorProperty().get().param("num").valueProperty().get());
        Component branchBase = analysis.clockModel().param("rates").priorProperty().get().param("base").priorProperty().get();
        assertEquals("LogNormal", branchBase.name());
        assertEquals("1.0", branchBase.param("mean").valueProperty().get());
        assertPrior(branchBase.param("logSd"), "LogNormal", "logMean", "-0.693", "logSd", "0.5");
        assertPrior(analysis.clockModel().param("meanRate"), "LogNormal", "logMean", "-9.0", "logSd", "5.0");
        assertEquals("true", analysis.likelihood().param("useAmbiguities").valueProperty().get());
    }

    private static void assertPrior(Param param, String name, String a, String x, String b, String y) {
        assertTrue(param.isEstimated(), param.name());
        Component prior = param.priorProperty().get();
        assertEquals(name, prior.name());
        assertEquals(x, prior.param(a).valueProperty().get());
        assertEquals(y, prior.param(b).valueProperty().get());
    }

    @Test
    void exportedRealDataRunsWithBothPackages() throws Exception {
        run(example());
    }

    private static void run(Analysis analysis) throws Exception {
        assertConfiguration(analysis);
        analysis.chainLengthProperty().set("10");
        analysis.logEveryProperty().set("1");
        Path directory = Files.createTempDirectory(Files.createDirectories(Path.of("target", "primates-ui")), "run-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        Path source = directory.resolve("analysis.phylospec");
        String script = ScriptWriter.write(analysis);
        assertEquals(List.of(), Validator.check(analysis.library(), script).all());
        Files.writeString(source, script);
        Analysis restored = ScriptReader.read(analysis.library(), analysis.support(), source);
        assertConfiguration(restored);
        assertEquals(script, ScriptWriter.write(restored));
        String classpath = CalibratedClockTest.adapters().stream()
                .map(path -> Files.isDirectory(path) ? path.resolve("*") : path)
                .map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        Path output = directory.resolve("runner.txt");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx3g", "-Djava.only=true", "-Dbeast.debug=true", "-cp", classpath,
                "runner.PhyloSpecCli", "--library", "calibratedcpp,clockmodelaveraging,beast2", source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(120, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        String console = Files.readString(output);
        assertTrue(finished, "Timed out: " + output);
        assertEquals(0, process.exitValue(), console);
        assertTrue(console.contains("End likelihood:"), console);
        List<String> lines = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        assertEquals(12, lines.size());
        List<String> header = List.of(lines.getFirst().trim().split("\\s+"));
        assertTrue(header.containsAll(List.of("posterior", "prior", "likelihood", "r.1", "r.2", "r.3")),
                header.toString());
        for (int sample = 0; sample <= 10; sample++) {
            String[] row = lines.get(sample + 1).trim().split("\\s+");
            assertEquals(header.size(), row.length, lines.get(sample + 1));
            assertEquals(sample, Integer.parseInt(row[0]));
            for (int i = 1; i < row.length; i++) assertTrue(Double.isFinite(Double.parseDouble(row[i])), lines.get(sample + 1));
            assertEquals(Double.parseDouble(row[header.indexOf("prior")]) + Double.parseDouble(row[header.indexOf("likelihood")]),
                    Double.parseDouble(row[header.indexOf("posterior")]), 1e-6);
            double total = 0;
            for (int i = 1; i <= 3; i++) {
                double rate = Double.parseDouble(row[header.indexOf("r." + i)]);
                assertTrue(rate > 0);
                total += rate;
            }
            assertEquals(3, total, 1e-10, "Equal partition weights retain a unit weighted mean.");
        }
        assertTrue(Files.readString(directory.resolve("analysis.trees")).contains("STATE_10"));
        assertTrue(Files.size(directory.resolve("analysis.state.xml")) > 0);
        assertEquals(12, Files.readString(directory.resolve("analysis.state.xml")).split("<statenode ", -1).length - 1);
    }
}
