package org.phylospec.ui.panel;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import org.phylospec.components.Generator;
import org.phylospec.ui.fx.Form;
import org.phylospec.ui.fx.ParamRow;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.spec.Library;

/**
 * The panel behind the Site Model, Clock Model and Tree Prior tabs.
 *
 * <p>Each of those tabs is the same thing: pick a component, then edit its arguments. The tabs
 * differ only in which components they offer, so they share one implementation.
 */
public final class ComponentPanel {

    /** One chooser within a panel — the Site Model tab has two, for substitution and rates. */
    public record Choice(String label, List<String> generators, Component component, boolean optional) {

        public static Choice of(String label, List<String> generators, Component component) {
            return new Choice(label, generators, component, false);
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
        List<Generator> candidates = analysis.choicesFor(choice.generators());

        ComboBox<Generator> combo = new ComboBox<>();
        combo.getItems().setAll(candidates);
        if (choice.optional()) combo.getItems().add(0, null);
        combo.setCellFactory(view -> cell(candidates));
        combo.setButtonCell(cell(candidates));
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

    private static ListCell<Generator> cell(List<Generator> among) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Generator item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                } else {
                    setText(item == null ? "None" : Component.describe(item, among));
                }
            }
        };
    }
}
