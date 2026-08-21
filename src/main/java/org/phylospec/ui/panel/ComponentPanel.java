package org.phylospec.ui.panel;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import org.phylospec.components.Generator;
import org.phylospec.ui.fx.Form;
import org.phylospec.ui.fx.ParamRow;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;

/**
 * The panel behind the Site Model, Clock Model and Tree Prior tabs.
 *
 * <p>Each of those tabs is the same thing: pick a component, then edit its arguments. The tabs
 * differ only in which components they offer, so they share one implementation.
 */
public final class ComponentPanel {

    /** One chooser within a panel — the Site Model tab has two, for substitution and rates. */
    public record Choice(String label, String role, Component component, boolean optional) {

        public static Choice of(String label, String role, Component component) {
            return new Choice(label, role, component, false);
        }
    }

    private ComponentPanel() {}

    public static Node build(Analysis analysis, String caption, List<Choice> choices) {
        VBox body = Form.panel(Form.caption(caption));
        for (Choice choice : choices) {
            body.getChildren().add(section(analysis, choice));
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        return scroll;
    }

    private static Node section(Analysis analysis, Choice choice) {
        Library library = analysis.library();
        List<Generator> candidates = analysis.choicesFor(choice.role());

        ComboBox<Generator> combo = new ComboBox<>();
        combo.getItems().setAll(candidates);
        if (choice.optional()) combo.getItems().add(0, null);
        EngineSupport support = analysis.support();
        combo.setCellFactory(view -> cell(candidates, support));
        combo.setButtonCell(cell(candidates, support));
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.valueProperty().bindBidirectional(choice.component().generatorProperty());

        GridPane arguments = Form.grid();
        rebuild(library, arguments, choice.component());
        choice.component().generatorProperty()
                .addListener((observable, was, now) -> rebuild(library, arguments, choice.component()));

        GridPane header = Form.grid();
        Form.row(header, choice.label(), null, combo, null);
        return new VBox(8, header, arguments);
    }

    private static void rebuild(Library library, GridPane grid, Component component) {
        grid.getChildren().clear();
        grid.getRowConstraints().clear();
        for (Param param : component.params()) {
            ParamRow.add(grid, library, param);
        }
    }

    /**
     * One row of a chooser, greyed out and unselectable where no loaded engine implements it.
     *
     * <p>Disabled rather than hidden. A component an engine cannot run is still worth seeing: it
     * says the model exists and this engine is the wrong one for it, and the tooltip says where to
     * get an engine that is right. Hiding it would leave a user wondering why the model they read
     * about is absent, and would make someone else's script unreadable here.
     */
    private static ListCell<Generator> cell(List<Generator> among, EngineSupport support) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Generator item, boolean empty) {
                super.updateItem(item, empty);
                setDisable(false);
                setTooltip(null);
                getStyleClass().remove("unsupported");

                if (empty) {
                    setText(null);
                    return;
                }
                if (item == null) {
                    setText("None");
                    return;
                }

                String described = Component.describe(item, among);
                EngineSupport.Verdict verdict = support.verdictFor(item);
                if (verdict.supported()) {
                    setText(described);
                    return;
                }

                setText(described + "  (not available)");
                setDisable(true);
                getStyleClass().add("unsupported");

                List<String> advice = new ArrayList<>();
                advice.add(verdict.reason());
                advice.addAll(support.installationAdvice());
                setTooltip(new Tooltip(String.join("\n", advice)));
            }
        };
    }
}
