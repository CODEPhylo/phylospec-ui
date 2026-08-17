package org.phylospec.ui.fx;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The handful of layout primitives every panel is built from.
 *
 * <p>BEAUti's panels are a column of right-aligned labels against a column of controls; this gives
 * the same shape in one place instead of once per editor.
 */
public final class Form {

    private static final double LABEL_WIDTH = 170;

    private Form() {}

    /** A three-column grid: label, control, trailing control (usually an "estimate" box). */
    public static GridPane grid() {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(8);
        grid.setVgap(6);

        ColumnConstraints labels = new ColumnConstraints(LABEL_WIDTH);
        labels.setHalignment(HPos.RIGHT);
        ColumnConstraints controls = new ColumnConstraints();
        controls.setHgrow(Priority.ALWAYS);
        controls.setFillWidth(true);
        ColumnConstraints trailing = new ColumnConstraints();
        trailing.setHalignment(HPos.LEFT);

        grid.getColumnConstraints().addAll(labels, controls, trailing);
        return grid;
    }

    /** Appends a labelled row, returning the index it was placed at. */
    public static int row(GridPane grid, String label, String tip, Node control, Node trailing) {
        int index = grid.getRowCount();
        Label caption = new Label(label == null ? "" : label + ":");
        caption.getStyleClass().add("form-label");
        if (tip != null && !tip.isBlank()) {
            Tooltip tooltip = new Tooltip(tip);
            Tooltip.install(caption, tooltip);
            if (control != null) Tooltip.install(control, tooltip);
        }
        grid.add(caption, 0, index);
        if (control != null) {
            grid.add(control, 1, index);
            if (control instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        }
        if (trailing != null) grid.add(trailing, 2, index);
        return index;
    }

    /** A row spanning the control and trailing columns, for notes and nested content. */
    public static void span(GridPane grid, Node content) {
        grid.add(content, 1, grid.getRowCount(), 2, 1);
    }

    /** The italic explanatory line each BEAUti panel carries under its tab. */
    public static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("panel-caption");
        label.setWrapText(true);
        return label;
    }

    public static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("panel-note");
        label.setWrapText(true);
        return label;
    }

    /** The standard panel body: a padded vertical stack. */
    public static VBox panel(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }
}
