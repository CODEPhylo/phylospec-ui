package org.phylospec.ui.panel;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import org.phylospec.ui.fx.Form;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Partition;

/** The Partitions tab: the alignments the analysis is built on. */
public final class PartitionsPanel {

    private static final List<String> EXTENSIONS = List.of("*.nex", "*.nexus", "*.fa", "*.fasta", "*.fst");

    private PartitionsPanel() {}

    public static Node build(Analysis analysis) {
        TableView<Partition> table = new TableView<>(analysis.partitions());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Form.note("No alignments loaded. Use + below, or drop files here."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<Partition, String> name = new TableColumn<>("Name");
        name.setCellValueFactory(row -> row.getValue().nameProperty());
        name.setCellFactory(TextFieldTableCell.forTableColumn());
        name.setEditable(true);

        TableColumn<Partition, String> file = new TableColumn<>("File");
        file.setCellValueFactory(row -> row.getValue().fileProperty());

        TableColumn<Partition, String> taxa = new TableColumn<>("Taxa");
        taxa.setCellValueFactory(row -> row.getValue().taxaProperty().asString());
        taxa.setPrefWidth(70);

        TableColumn<Partition, String> sites = new TableColumn<>("Sites");
        sites.setCellValueFactory(row -> row.getValue().sitesProperty().asString());
        sites.setPrefWidth(70);

        TableColumn<Partition, String> loader = new TableColumn<>("Loader");
        loader.setCellValueFactory(row -> new SimpleStringProperty(row.getValue().loader()));
        loader.setPrefWidth(100);

        table.getColumns().setAll(List.of(name, file, taxa, sites, loader));
        table.setEditable(true);

        Button add = new Button("+");
        add.setTooltip(new javafx.scene.control.Tooltip("Add an alignment file"));
        add.setOnAction(event -> choose(table.getScene().getWindow(), analysis));

        Button remove = new Button("−");
        remove.setTooltip(new javafx.scene.control.Tooltip("Remove the selected alignments"));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event ->
                analysis.partitions().removeAll(List.copyOf(table.getSelectionModel().getSelectedItems())));

        HBox buttons = new HBox(6, add, remove);
        buttons.setPadding(new Insets(6, 0, 0, 0));

        enableDropping(table, analysis);

        VBox body = Form.panel(
                Form.caption("Alignments to analyse. The model on the following tabs is shared by every partition."),
                table,
                buttons);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    private static void choose(Window owner, Analysis analysis) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Add alignment");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Alignments", EXTENSIONS),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> chosen = chooser.showOpenMultipleDialog(owner);
        if (chosen != null) chosen.forEach(file -> analysis.addPartition(file.toPath()));
    }

    /** Dropping alignment files onto the table is how BEAUti expects them to arrive. */
    private static void enableDropping(TableView<Partition> table, Analysis analysis) {
        table.setOnDragOver((DragEvent event) -> {
            if (event.getDragboard().hasFiles()) event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        });
        table.setOnDragDropped((DragEvent event) -> {
            boolean hasFiles = event.getDragboard().hasFiles();
            if (hasFiles) event.getDragboard().getFiles().forEach(file -> analysis.addPartition(file.toPath()));
            event.setDropCompleted(hasFiles);
            event.consume();
        });
    }

}
