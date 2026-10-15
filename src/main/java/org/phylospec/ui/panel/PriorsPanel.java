package org.phylospec.ui.panel;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Button;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Alert;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import org.phylospec.ui.fx.Form;
import org.phylospec.ui.fx.ParamRow;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Param;

/**
 * The Priors tab: every value marked "estimate" elsewhere, with the distribution it is drawn from.
 *
 * <p>The list is derived from the other tabs rather than stored, so it cannot fall out of step with
 * them. It is rebuilt whenever the tab is shown.
 */
public final class PriorsPanel {

    private PriorsPanel() {}

    public static Node build(Analysis analysis, ScrollPane host) {
        host.setFitToWidth(true);
        refresh(analysis, host);
        return host;
    }

    /** Rebuilds the list; called when the tab is selected. */
    public static void refresh(Analysis analysis, ScrollPane host) {
        List<Param> estimated = analysis.estimatedParams();

        VBox body = Form.panel(Form.caption(
                "Prior distributions for the estimated parameters. "
                        + "Tick \"estimate\" on the model tabs to add a parameter here."));
        Button add = new Button("Add shared positive vector…");
        add.setOnAction(event -> {
            TextInputDialog dialog = new TextInputDialog("rates");
            dialog.setHeaderText("Name a Vector<PositiveReal>; choose its prior here, then reference its elements from model inputs.");
            dialog.showAndWait().ifPresent(name -> {
                try {
                    analysis.addSharedVector(name.trim(), "Vector<PositiveReal>");
                    refresh(analysis, host);
                } catch (IllegalArgumentException e) {
                    new Alert(Alert.AlertType.ERROR, e.getMessage()).showAndWait();
                }
            });
        });
        body.getChildren().add(add);

        if (estimated.isEmpty()) {
            body.getChildren().add(Form.note("Nothing is being estimated."));
        }

        for (Param param : estimated) {
            GridPane grid = Form.grid();
            Form.row(grid, param.label(), param.description(),
                    ParamRow.distributionEditor(analysis, param),
                    null);
            Form.span(grid, Form.note(param.variableProperty().get() + " : " + param.type()));
            if (analysis.sharedVectors().contains(param)) {
                Button remove = new Button("Remove shared vector");
                remove.setOnAction(event -> {
                    analysis.sharedVectors().remove(param);
                    refresh(analysis, host);
                });
                Form.span(grid, remove);
            }

            body.getChildren().addAll(grid, new Separator());
        }

        host.setContent(body);
    }
}
