package org.phylospec.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
class ReadCountFormTest {
    @TempDir Path directory;

    @Test
    void selectingReadCountAndEditingEstimateControlsProducesARunnableModel() throws Exception {
        var analysis = ReadCountTest.analysis(directory, "gt10", false, false);
        analysis.likelihood().generatorProperty().set(analysis.library().overloads("PhyloCTMC").getFirst());
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(ready::countDown);
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> edit = new FutureTask<>(() -> {
            Parent panel = (Parent) ComponentPanel.build(analysis, "Read-count observation", List.of(
                    ComponentPanel.Choice.of("Likelihood", Library.TREE_LIKELIHOOD, analysis.likelihood())));
            Stage stage = new Stage();
            stage.setScene(new Scene(panel, 850, 700));
            stage.show();
            try {
                var selector = GenotypeFormTest.chooser(panel, "ReadCountModel");
                assertEquals(List.of("ReadCountModel"), selector.getItems().stream().map(g -> g.getName()).toList());
                selector.setValue(analysis.library().overloads("ReadCountModel").getFirst());
                assertNull(analysis.likelihood().param("alignment"));
                assertFalse(analysis.likelihood().param("s").isEstimated());
                assertEquals("[1.0, 1.0, 1.0, 1.0]", analysis.likelihood().param("s").valueProperty().get());
                var estimates = GenotypeFormTest.descendants(panel).stream().filter(CheckBox.class::isInstance)
                        .map(CheckBox.class::cast).filter(box -> box.getText().startsWith("estimate")).toList();
                assertEquals(7, estimates.size());
                for (String name : List.of("epsilon", "delta", "t", "v", "s", "w1", "w2")) {
                    var param = analysis.likelihood().param(name);
                    CheckBox box = GenotypeFormTest.estimateBox(panel, param);
                    boolean selected = box.isSelected();
                    assertEquals(selected, param.isEstimated(), name);
                    box.fire();
                    assertEquals(!selected, box.isSelected());
                    assertEquals(!selected, param.isEstimated(), name);
                    box.fire();
                    assertEquals(selected, param.isEstimated(), name);
                }
                CheckBox vectorEstimate = estimates.stream().filter(box -> "estimate vector".equals(box.getText()))
                        .findFirst().orElseThrow();
                vectorEstimate.fire();
                assertTrue(analysis.likelihood().param("s").isEstimated());
                assertEquals("4", analysis.likelihood().param("s").priorProperty().get()
                        .param("num").valueProperty().get());
                String script = ScriptWriter.write(analysis);
                assertEquals(script, ScriptWriter.write(ScriptReader.read(analysis.library(), analysis.support(), script)));
                return script;
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(edit);
            ReadCountTest.run(edit.get(30, TimeUnit.SECONDS), directory, "gt10");
        } finally {
            Platform.exit();
        }
    }
}
