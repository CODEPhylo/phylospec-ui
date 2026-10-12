package org.phylospec.ui;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.phylospec.components.Generator;
import org.phylospec.ui.fx.ParamRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in because JavaFX controls require a desktop/display, unlike the model tests. */
@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.calibratedAdapter", matches = ".+")
class CalibratedFormTest {
    @TempDir Path directory;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertTrue(started.await(20, TimeUnit.SECONDS), "JavaFX did not start.");
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    @EnabledIfSystemProperty(named = "phylospec.clockJars", matches = ".+")
    void continuousClockControlsKeepTheBaseScaleEstimated() throws Exception {
        FutureTask<String> interaction = new FutureTask<>(() -> {
            var analysis = CalibratedClockTest.example();
            var rates = analysis.clockModel().param("rates");
            BorderPane root = new BorderPane(ParamRow.distributionEditor(analysis, rates));
            Stage stage = new Stage();
            stage.setTitle("CalibratedCPP continuous clock verification");
            stage.setScene(new Scene(root, 900, 650));
            stage.show();
            try {
                ComboBox<Generator> chooser = generatorChooser(root, "IID");
                chooser.setValue(analysis.library().overloads("svsRawBranchRates").getFirst());
                chooser.setValue(analysis.library().overloads("IID").getFirst());
                ComboBox<Generator> baseChooser = generatorChooser(root, "LogNormal");
                baseChooser.setValue(analysis.library().overloads("LogNormal").stream()
                        .filter(g -> g.getArguments().stream().anyMatch(a -> a.getName().equals("mean")))
                        .findFirst().orElseThrow());
                var law = rates.priorProperty().get().param("base").priorProperty().get();
                CheckBox mean = estimateFor(root, "Mean:");
                assertTrue(mean.isSelected());
                mean.fire();
                assertFalse(law.param("mean").isEstimated());
                CheckBox sigma = estimateFor(root, "Log sd:");
                assertTrue(sigma.isSelected());
                sigma.fire();
                assertFalse(law.param("logSd").isEstimated());
                sigma.fire();
                assertTrue(law.param("logSd").isEstimated());
                TextField count = nodes(root).filter(TextField.class::isInstance).map(TextField.class::cast)
                        .filter(field -> field.getText().equals("numBranches(tree)")).findFirst().orElseThrow();
                count.setText("6");
                String script = ScriptWriter.write(analysis);
                assertEquals(List.of(), Validator.check(analysis.library(), script).all());
                assertTrue(script.contains("num=6"), script);
                assertTrue(script.contains("useAmbiguities=true"), script);
                assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), script)));
                return script;
            } finally {
                stage.close();
            }
        });
        Platform.runLater(interaction);
        String script = interaction.get(30, TimeUnit.SECONDS);
        Path source = directory.resolve("continuous.phylospec");
        Files.writeString(source, script);
        Path output = directory.resolve("runner.txt");
        String classpath = CalibratedClockTest.adapters().stream()
                .map(path -> Files.isDirectory(path) ? path.resolve("*") : path)
                .map(Path::toString).collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        Path prefix = directory.resolve("analysis");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.only=true", "-cp", classpath, "runner.PhyloSpecCli",
                "--library", "calibratedcpp,clockmodelaveraging,beast2", "--run-name", prefix.toString(), source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, Files.readString(output));
        assertEquals(0, process.exitValue(), Files.readString(output));
        var samples = Files.readAllLines(Path.of(prefix + ".log")).stream()
                .filter(line -> line.matches("[0-9]+\\s+.*")).toList();
        assertEquals(21, samples.size());
        assertTrue(samples.getLast().startsWith("200\t"));
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Generator> generatorChooser(Node root, String offered) {
        return nodes(root).filter(ComboBox.class::isInstance).map(node -> (ComboBox<Generator>) node)
                .filter(combo -> combo.getItems().stream().anyMatch(g -> g.getName().equals(offered)))
                .findFirst().orElseThrow();
    }

    private static CheckBox estimateFor(Node root, String text) {
        Label label = nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(node -> text.equals(node.getText())).findFirst().orElseThrow();
        GridPane grid = (GridPane) label.getParent();
        return grid.getChildren().stream()
                .filter(node -> java.util.Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(label)))
                .flatMap(CalibratedFormTest::nodes).filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                .filter(box -> "estimate".equals(box.getText())).findFirst().orElseThrow();
    }

    @Test
    void controlsEditTheModelThatThePackagedBackendRuns() throws Exception {
        Path adapter = Path.of(System.getProperty("phylospec.calibratedAdapter"));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            var metadata = PackageMetadata.discover(List.of(adapter));
            var analysis = ScriptReader.read(metadata.library(), metadata.support(),
                    Path.of("examples/calibratedcpp/skyline.phylospec"));
            var tree = analysis.treePrior();
            Node panel = ComponentPanel.build(analysis, "CalibratedCPP tree prior",
                    List.of(ComponentPanel.Choice.of("Tree prior", Library.TREE_PRIOR, tree)));
            BorderPane root = new BorderPane(panel);
            Stage stage = new Stage();
            stage.setTitle("CalibratedCPP integration verification");
            stage.setScene(new Scene(root, 1000, 800));
            stage.getScene().getStylesheets().add(getClass()
                    .getResource("/org/phylospec/ui/phylospec.css").toExternalForm());
            stage.show();
            try {
                root.applyCss();
                root.layout();
                // The first raw numeric vector is birthRate.values = [1.2].
                Button edit = nodes(root).filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getText().equals("Edit entries")).findFirst().orElseThrow();
                edit.fire();
                assertEquals(1, tree.param("birthRate").priorProperty().get().param("values").elements().size());
                TextField birth = nodes(root).filter(TextField.class::isInstance).map(TextField.class::cast)
                        .filter(field -> field.getText().equals("1.2")).findFirst().orElseThrow();
                birth.setText("1.3");
                assertEquals("1.3", tree.param("birthRate").priorProperty().get().param("values")
                        .elements().getFirst().valueProperty().get());
                var entries = tree.param("diversificationRate").priorProperty().get().param("values").elements();
                assertTrue(entries.getFirst().isEstimated());
                CheckBox estimate = nodes(root).filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                        .filter(box -> box.getText().equals("estimate") && box.isSelected())
                        .findFirst().orElseThrow();
                estimate.fire();
                assertFalse(entries.getFirst().isEstimated());
                estimate.fire();
                assertTrue(entries.getFirst().isEstimated());
                String script = ScriptWriter.write(analysis);
                assertTrue(script.contains("values=[1.3]"), script);
                assertTrue(script.contains("~ Normal("), script);
                assertTrue(script.contains("~ OrderedUniform("), script);
                assertEquals(List.of(), Validator.check(analysis.library(), script).all());
                root.applyCss();
                root.layout();
                String snapshot = System.getProperty("phylospec.fxSnapshot");
                if (snapshot != null) {
                    var image = root.snapshot(null, null);
                    BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(),
                            BufferedImage.TYPE_INT_ARGB);
                    var pixels = image.getPixelReader();
                    for (int y = 0; y < png.getHeight(); y++) {
                        for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, pixels.getArgb(x, y));
                    }
                    ImageIO.write(png, "png", Path.of(snapshot).toFile());
                }
                return script;
            } finally {
                stage.close();
            }
        });
        Platform.runLater(interaction);
        String script = interaction.get(30, TimeUnit.SECONDS);
        Path source = directory.resolve("form.phylospec");
        Files.writeString(source, script);
        Path output = directory.resolve("runner.txt");
        Path prefix = directory.resolve("analysis");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", adapter.resolve("*").toString(), "runner.PhyloSpecCli", "--library", "calibratedcpp,beast2",
                "--run-name", prefix.toString(), source.toString())
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, Files.readString(output));
        assertEquals(0, process.exitValue(), Files.readString(output));
        assertTrue(Files.readString(output).contains("End likelihood:"));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".state.xml")));
        assertTrue(Files.isRegularFile(Path.of(prefix + ".trees")));
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(CalibratedFormTest::nodes))
                : Stream.of(node);
    }
}
