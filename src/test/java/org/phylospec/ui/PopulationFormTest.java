package org.phylospec.ui;

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
import javafx.scene.control.MenuButton;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.ui.fx.ParamRow;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.popfuncAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.ancestralAnalysis", matches = ".+")
class PopulationFormTest {
    @Test
    void editSharedPopulationInputsAndPriorVectorThroughTheRealControls() throws Exception {
        var analysis = PopulationSelectionTest.example();
        String original = ScriptWriter.write(analysis);
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            BorderPane root = new BorderPane(ComponentPanel.build(analysis, "Population selection", List.of(
                    ComponentPanel.Choice.of("Tree prior", Library.TREE_PRIOR, analysis.treePrior()))));
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1200, 850));
            stage.show();
            try {
                root.applyCss();
                root.layout();
                var models = PopulationSelectionTest.models(analysis);
                nodes(root).filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> "+".equals(button.getText())).findFirst().orElseThrow().fire();
                assertEquals(3, models.elements().size());
                assertNotNull(models.elements().getLast().priorProperty().get().generator());
                nodes(root).filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> "−".equals(button.getText())).findFirst().orElseThrow().fire();
                assertEquals(2, models.elements().size());
                var first = models.elements().getFirst().priorProperty().get();
                var second = models.elements().getLast().priorProperty().get();
                for (String name : List.of("initialProportion", "growthRate", "initialPopulationSize")) {
                    reconnect(root, second.param(name), first.param(name));
                }
                Param indicator = PopulationSelectionTest.selection(analysis).param("indicator");
                root.setCenter(ParamRow.distributionEditor(analysis, indicator));
                root.applyCss();
                root.layout();
                reconnect(root, indicator.priorProperty().get().param("models"), models);
                PopulationSelectionTest.assertShared(analysis);
                return ScriptWriter.write(analysis);
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(interaction);
            assertEquals(original, interaction.get(30, TimeUnit.SECONDS));
            PopulationSelectionTest.run(analysis);
        } finally {
            Platform.exit();
        }
    }

    private static void reconnect(Node root, Param input, Param target) {
        MenuButton menu = nodes(root).filter(MenuButton.class::isInstance).map(MenuButton.class::cast)
                .filter(button -> button.getUserData() == input).findFirst().orElseThrow();
        menu.show();
        menu.getItems().getFirst().fire();
        menu.hide();
        assertNull(input.referenceProperty().get());
        menu.show();
        menu.getItems().stream().filter(item -> item.getUserData() == target).findFirst().orElseThrow().fire();
        menu.hide();
        assertSame(target, input.referenceProperty().get());
        assertFalse(input.isEstimated());
    }

    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(PopulationFormTest::nodes))
                : Stream.of(node);
    }
}
