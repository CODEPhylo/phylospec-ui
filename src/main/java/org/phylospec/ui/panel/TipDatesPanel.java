package org.phylospec.ui.panel;

import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import org.phylospec.ui.fx.Form;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Partition;

/**
 * The Tip Dates tab.
 *
 * <p>PhyloSpec has no separate date trait: a sampling time is a {@code parse(...)} argument on the
 * loader, so this tab configures how the date is pulled out of each taxon name.
 */
public final class TipDatesPanel {

    private TipDatesPanel() {}

    public static Node build(Analysis analysis) {
        ComboBox<Partition> partitions = new ComboBox<>(analysis.partitions());
        partitions.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Partition partition) {
                return partition == null ? "" : partition.name();
            }

            @Override
            public Partition fromString(String string) {
                return null;
            }
        });

        VBox editor = new VBox(10);
        partitions.valueProperty().addListener((observable, was, now) -> {
            editor.getChildren().clear();
            if (now != null) editor.getChildren().add(editorFor(now));
        });
        analysis.partitions().addListener((javafx.collections.ListChangeListener<Partition>) change -> {
            if (partitions.getValue() == null && !analysis.partitions().isEmpty()) {
                partitions.setValue(analysis.partitions().get(0));
            }
        });
        if (!analysis.partitions().isEmpty()) partitions.setValue(analysis.partitions().get(0));

        GridPane chooser = Form.grid();
        Form.row(chooser, "Partition", null, partitions, null);

        return Form.panel(
                Form.caption("Sampling times read from the taxon names. Leave off for contemporaneous data."),
                chooser,
                editor);
    }

    private static Node editorFor(Partition partition) {
        CheckBox use = new CheckBox("Use tip dates");
        use.selectedProperty().bindBidirectional(partition.useTipDatesProperty());

        ComboBox<Partition.DateKind> kind = new ComboBox<>();
        kind.getItems().setAll(Partition.DateKind.values());
        kind.valueProperty().bindBidirectional(partition.dateKindProperty());
        kind.setMaxWidth(Double.MAX_VALUE);

        ComboBox<Partition.ParseMode> mode = new ComboBox<>();
        mode.getItems().setAll(Partition.ParseMode.values());
        mode.valueProperty().bindBidirectional(partition.parseModeProperty());
        mode.setMaxWidth(Double.MAX_VALUE);

        TextField delimiter = new TextField();
        delimiter.textProperty().bindBidirectional(partition.delimiterProperty());

        TextField part = new TextField();
        part.textProperty().bindBidirectional(partition.partProperty());

        TextField regex = new TextField();
        regex.textProperty().bindBidirectional(partition.regexProperty());

        GridPane grid = Form.grid();
        Form.row(grid, "Interpret as", "Whether the number is an age before present or a forward-time date",
                kind, null);
        Form.row(grid, "Read date by", null, mode, null);

        GridPane splitFields = Form.grid();
        Form.row(splitFields, "Delimiter", "The taxon name is split on this string", delimiter, null);
        Form.row(splitFields, "Part", "Which piece of the split name holds the date, counting from 1", part, null);
        showWhen(splitFields, mode, Partition.ParseMode.SPLIT);

        GridPane regexFields = Form.grid();
        Form.row(regexFields, "Regular expression", "The first capturing group is used as the date", regex, null);
        showWhen(regexFields, mode, Partition.ParseMode.REGEX);

        Label preview = new Label();
        preview.getStyleClass().add("wired-value");
        preview.textProperty().bind(Bindings.createStringBinding(
                () -> partition.dateKindProperty().get().argument() + "=" + parseCall(partition),
                partition.dateKindProperty(), partition.parseModeProperty(),
                partition.delimiterProperty(), partition.partProperty(), partition.regexProperty()));

        GridPane previewGrid = Form.grid();
        Form.row(previewGrid, "Generated argument", null, preview, null);

        VBox fields = new VBox(6, grid, splitFields, regexFields, previewGrid);
        fields.disableProperty().bind(use.selectedProperty().not());
        return new VBox(10, use, fields);
    }

    private static void showWhen(Node node, ComboBox<Partition.ParseMode> mode, Partition.ParseMode wanted) {
        node.visibleProperty().bind(mode.valueProperty().isEqualTo(wanted));
        node.managedProperty().bind(node.visibleProperty());
    }

    private static String parseCall(Partition partition) {
        if (partition.parseModeProperty().get() == Partition.ParseMode.SPLIT) {
            return "parse(delimiter=\"" + partition.delimiterProperty().get() + "\", part="
                    + partition.partProperty().get() + ")";
        }
        return "parse(regex=\"" + partition.regexProperty().get() + "\")";
    }
}
