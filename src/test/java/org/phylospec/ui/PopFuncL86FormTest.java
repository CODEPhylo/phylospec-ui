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
import javafx.scene.control.MenuButton;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptWriter;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "phylospec.fxTests", matches = "true")
@EnabledIfSystemProperty(named = "phylospec.popfuncAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.phyloncoAdapter", matches = ".+")
@EnabledIfSystemProperty(named = "phylospec.popfuncL86Analysis", matches = ".+")
class PopFuncL86FormTest {
    @Test
    void reconnectsSharedIndicatorAndNarrowScalarThroughRealControlsThenRuns() throws Exception {
        var analysis = PopFuncL86Test.example();
        String original = ScriptWriter.write(analysis);
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(20, TimeUnit.SECONDS));
        FutureTask<String> interaction = new FutureTask<>(() -> {
            BorderPane root = new BorderPane(ComponentPanel.build(analysis, "L86 populations", List.of(
                    ComponentPanel.Choice.of("Tree prior", Library.TREE_PRIOR, analysis.treePrior()))));
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1200, 850));
            stage.show();
            try {
                root.applyCss();
                root.layout();
                var outer = PopFuncL86Test.selection(analysis);
                var without = PopFuncL86Test.inner(outer, 0);
                var with = PopFuncL86Test.inner(outer, 1);
                reconnect(root, with.param("indicator"), without.param("indicator"));
                reconnect(root, PopFuncL86Test.inner(with, 3).param("growthRate"),
                        PopFuncL86Test.inner(without, 1).param("growthRate"));
                PopFuncL86Test.assertShared(analysis);
                return ScriptWriter.write(analysis);
            } finally {
                stage.close();
            }
        });
        try {
            Platform.runLater(interaction);
            assertEquals(original, interaction.get(30, TimeUnit.SECONDS));
            PopFuncL86Test.run(analysis);
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
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(PopFuncL86FormTest::nodes))
                : Stream.of(node);
    }
}
