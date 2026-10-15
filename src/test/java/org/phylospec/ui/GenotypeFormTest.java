package org.phylospec.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.components.Generator;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

/** Run display tests in separate forks (-DreuseForks=false): JavaFX cannot restart after exit. */
@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class GenotypeFormTest {
    @TempDir Path directory;

    @Test
    void selectingObservationAndEditingEstimateControlsExportsARunnableModel() throws Exception {
        var analysis = GenotypeErrorTest.analysis(directory, false, false);
        analysis.likelihood().generatorProperty().set(analysis.library().overloads("PhyloCTMC").getFirst());
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> edit = new FutureTask<>(() -> {
            Parent panel = (Parent) ComponentPanel.build(analysis, "Observation model", List.of(
                    ComponentPanel.Choice.of("Likelihood", Library.TREE_LIKELIHOOD, analysis.likelihood())));
            Stage stage = new Stage();
            stage.setScene(new Scene(panel, 850, 550));
            stage.show();
            try {
                ComboBox<Generator> selector = chooser(panel, "GT16ErrorModel");
                selector.setValue(analysis.library().overloads("GT16ErrorModel").getFirst());
                assertTrue(analysis.likelihoodTakes("qMatrix"));
                assertNull(analysis.likelihood().param("alignment"));
                List<CheckBox> estimates = descendants(panel).stream().filter(CheckBox.class::isInstance)
                        .map(CheckBox.class::cast).filter(box -> "estimate".equals(box.getText())).toList();
                assertEquals(2, estimates.size());
                // Defaults are estimated; exercise fixed -> estimated on both real controls.
                for (String name : List.of("epsilon", "delta")) {
                    Param param = analysis.likelihood().param(name);
                    CheckBox box = estimateBox(panel, param);
                    assertTrue(box.isSelected());
                    assertTrue(param.isEstimated(), name);
                    box.fire();
                    assertFalse(box.isSelected());
                    assertFalse(param.isEstimated(), name);
                    box.fire();
                    assertTrue(param.isEstimated(), name);
                }
                assertTrue(analysis.likelihood().param("epsilon").isEstimated());
                assertTrue(analysis.likelihood().param("delta").isEstimated());
                String script = ScriptWriter.write(analysis);
                assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
                return script;
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(edit);
            GenotypeErrorTest.run(edit.get(30, TimeUnit.SECONDS), directory);
        } finally {
            Platform.exit();
        }
    }

    static CheckBox estimateBox(Parent root, Param param) {
        Label caption = descendants(root).stream().filter(Label.class::isInstance).map(Label.class::cast)
                .filter(label -> (param.label() + ":").equals(label.getText())).findFirst().orElseThrow();
        return ((GridPane) caption.getParent()).getChildren().stream()
                .filter(node -> Objects.equals(GridPane.getRowIndex(node), GridPane.getRowIndex(caption)))
                .filter(Parent.class::isInstance).map(Parent.class::cast)
                .flatMap(parent -> descendants(parent).stream()).filter(CheckBox.class::isInstance)
                .map(CheckBox.class::cast).filter(box -> box.getText().startsWith("estimate"))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static ComboBox<Generator> chooser(Parent root, String name) {
        return (ComboBox<Generator>) descendants(root).stream().filter(ComboBox.class::isInstance)
                .map(ComboBox.class::cast).filter(combo -> combo.getItems().stream()
                        .anyMatch(item -> item instanceof Generator generator && name.equals(generator.getName())))
                .findFirst().orElseThrow();
    }

    static List<Node> descendants(Parent root) {
        var nodes = new java.util.ArrayList<Node>();
        for (Node child : root.getChildrenUnmodifiable()) {
            nodes.add(child);
            if (child instanceof Parent parent) nodes.addAll(descendants(parent));
        }
        return nodes;
    }
}
