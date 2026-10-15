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
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.components.Generator;
import org.phylospec.ui.fx.ParamRow;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.PackageMetadata;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the unchanged L86 data prepared by the adapter's L86ReferenceIT, not a smaller alignment. */
@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.l86Analysis", matches = ".+")
class SkylineFormTest {
    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopFx() {
        Platform.exit();
    }

    @Test
    void configureCorrelatedSkylineThroughControlsThenRunTheRealAlignment() throws Exception {
        var metadata = PackageMetadata.discover(List.of(GenotypeErrorTest.adapter()));
        Path original = Path.of(System.getProperty("phylospec.l86Analysis")).toAbsolutePath();
        assertTrue(Files.isRegularFile(original), "Run L86ReferenceIT to prepare the L86 analysis and data.");
        Analysis analysis = ScriptReader.read(metadata.library(), metadata.support(), original);
        assertEquals(86, analysis.partitions().getFirst().taxaProperty().get());
        assertEquals(598, analysis.partitions().getFirst().sitesProperty().get());
        var imported = ParameterReferenceTest.entries(analysis);
        assertEquals(4, imported.size());
        for (int i = 1; i < 4; i++) {
            assertSame(imported.get(i - 1), ParameterReferenceTest.mean(imported.get(i)).referenceProperty().get());
        }
        String importedScript = ScriptWriter.write(analysis);
        assertEquals(importedScript, ScriptWriter.write(ScriptReader.read(
                metadata.library(), metadata.support(), importedScript)));

        // Rebuild just the tree prior through the real controls. Keep all other L86 settings.
        analysis.treePrior().generatorProperty().set(null);
        Path results = Files.createDirectories(Path.of("target", "l86-ui"));
        Path directory = Files.createTempDirectory(results, "form-").toAbsolutePath();
        analysis.logFileProperty().set(directory.resolve("analysis.log").toString());
        FutureTask<String> interaction = new FutureTask<>(() -> {
            BorderPane root = new BorderPane(ComponentPanel.build(analysis, "L86 skyline", List.of(
                    ComponentPanel.Choice.of("Tree prior", Library.TREE_PRIOR, analysis.treePrior()))));
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1000, 750));
            stage.show();
            try {
                chooser(root).setValue(analysis.library().overloads("BayesianSkyline").getFirst());
                Button edit = nodes(root).filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> "Edit entries".equals(button.getText())).findFirst().orElseThrow();
                edit.fire();
                var entries = ParameterReferenceTest.entries(analysis);
                assertEquals(4, entries.size());
                for (int i = 1; i <= 4; i++) estimate(root, "Population sizes" + i + ":").fire();
                estimate(root, "Group sizes:").fire();
                assertTrue(entries.stream().allMatch(Param::isEstimated));

                for (int i = 0; i < entries.size(); i++) {
                    Param entry = entries.get(i);
                    root.setCenter(new ScrollPane(ParamRow.distributionEditor(analysis, entry)));
                    if (i == 0) {
                        chooser(root).setValue(generator(analysis.library(), "LogNormal", "logMean"));
                        field(root, "Log mean:").setText("-2.3");
                        field(root, "Log sd:").setText("1.8");
                    } else {
                        chooser(root).setValue(generator(analysis.library(), "Exponential", "mean"));
                        ScriptWriter.write(analysis); // Assign the same visible names as the live preview.
                        Param mean = ParameterReferenceTest.mean(entry);
                        MenuButton reference = nodes(root).filter(MenuButton.class::isInstance)
                                .map(MenuButton.class::cast).filter(menu -> menu.getUserData() == mean)
                                .findFirst().orElseThrow();
                        reference.show();
                        Param previous = entries.get(i - 1);
                        assertTrue(reference.getItems().stream().noneMatch(item ->
                                item.getUserData() == analysis.likelihood().param("epsilon")),
                                "A Probability is not a PositiveReal reference.");
                        reference.getItems().stream().filter(item -> item.getUserData() == previous)
                                .findFirst().orElseThrow().fire();
                        reference.hide();
                        assertSame(previous, mean.referenceProperty().get());
                    }
                }
                Param groups = analysis.treePrior().param("groupSizes");
                root.setCenter(new ScrollPane(ParamRow.distributionEditor(analysis, groups)));
                chooser(root).setValue(generator(analysis.library(), "UniformComposition", "total"));
                field(root, "Total:").setText("85");
                field(root, "Num:").setText("4");
                String script = ScriptWriter.write(analysis);
                assertEquals(List.of(), Validator.check(analysis.library(), script).all(), script);
                assertEquals(script, ScriptWriter.write(ScriptReader.read(
                        analysis.library(), analysis.support(), script)));
                // Equivalent after canonical UI naming: no change to any L86 model/prior settings.
                assertEquals(importedScript, script.replace(directory.resolve("analysis").toString(), "l86-skyline"));
                return script;
            } finally {
                stage.close();
            }
        });
        Platform.runLater(interaction);
        String script = interaction.get(30, TimeUnit.SECONDS);
        GenotypeErrorTest.run(script, directory);
        List<String> trace = Files.readAllLines(directory.resolve("analysis.log")).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
        List<String> header = List.of(trace.getFirst().trim().split("\\s+"));
        List<Integer> groupColumns = java.util.stream.IntStream.range(0, header.size())
                .filter(i -> header.get(i).startsWith("groupSizes.")).boxed().toList();
        assertEquals(4, groupColumns.size(), header.toString());
        for (String row : trace.subList(1, trace.size())) {
            String[] values = row.trim().split("\\s+");
            int total = 0;
            for (int column : groupColumns) {
                double value = Double.parseDouble(values[column]);
                assertTrue(value >= 1 && value == Math.rint(value), row);
                total += (int) value;
            }
            assertEquals(85, total, row);
        }
    }

    private static Generator generator(Library library, String name, String argument) {
        return library.overloads(name).stream()
                .filter(g -> g.getArguments().stream().anyMatch(a -> argument.equals(a.getName())))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Generator> chooser(Node root) {
        root.applyCss();
        if (root instanceof Parent parent) parent.layout();
        return (ComboBox<Generator>) nodes(root).filter(ComboBox.class::isInstance).findFirst().orElseThrow();
    }

    private static Stream<Node> row(Node root, String caption) {
        Label label = nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(node -> caption.equals(node.getText())).findFirst().orElseThrow();
        return ((GridPane) label.getParent()).getChildren().stream()
                .filter(node -> java.util.Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(label)))
                .flatMap(SkylineFormTest::nodes);
    }

    private static CheckBox estimate(Node root, String caption) {
        return row(root, caption).filter(CheckBox.class::isInstance).map(CheckBox.class::cast)
                .filter(box -> box.getText().startsWith("estimate")).findFirst().orElseThrow();
    }

    private static TextField field(Node root, String caption) {
        return row(root, caption).filter(TextField.class::isInstance).map(TextField.class::cast)
                .findFirst().orElseThrow();
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(SkylineFormTest::nodes))
                : Stream.of(node);
    }
}
