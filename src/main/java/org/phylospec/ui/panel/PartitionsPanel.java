package org.phylospec.ui.panel;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
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

        // What each partition uses, named after the first partition using it. Two rows showing the
        // same name is what linked looks like, which is the question these columns exist to answer.
        TableColumn<Partition, String> siteModel = grouping(analysis, "Site Model",
                partition -> firstName(analysis.partitionsUsing(partition.siteModel())));
        TableColumn<Partition, String> clockModel = grouping(analysis, "Clock Model",
                partition -> firstName(analysis.partitionsUsing(partition.clockModel())));
        TableColumn<Partition, String> tree = grouping(analysis, "Tree",
                partition -> partition.tree().name());
        TableColumn<Partition, String> treePrior = grouping(analysis, "Tree Prior",
                partition -> analysis.treesUsing(partition.tree().prior()).get(0).name());

        table.getColumns().setAll(
                List.of(name, file, taxa, sites, loader, siteModel, clockModel, tree, treePrior));
        table.setEditable(true);

        Button add = new Button("+");
        add.setTooltip(new javafx.scene.control.Tooltip("Add an alignment file"));
        add.setOnAction(event -> choose(table.getScene().getWindow(), analysis));

        Button remove = new Button("−");
        remove.setTooltip(new javafx.scene.control.Tooltip("Remove the selected alignments"));
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(event ->
                analysis.partitions().removeAll(List.copyOf(table.getSelectionModel().getSelectedItems())));

        HBox buttons = new HBox(6, add, remove, new Separator(), linking(analysis, table));
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(6, 0, 0, 0));

        enableDropping(table, analysis);

        VBox body = Form.panel(
                Form.caption("Alignments to analyse. The last four columns say what each partition "
                        + "shares: select rows and use Link or Unlink to change it."),
                table,
                buttons);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    /** A column showing which group a partition belongs to, refreshed when the grouping changes. */
    private static TableColumn<Partition, String> grouping(
            Analysis analysis, String title, java.util.function.Function<Partition, String> of) {
        TableColumn<Partition, String> column = new TableColumn<>(title);
        column.setCellValueFactory(row -> Bindings.createStringBinding(
                () -> of.apply(row.getValue()),
                row.getValue().siteModelProperty(),
                row.getValue().clockModelProperty(),
                row.getValue().treeProperty(),
                analysis.siteModels(),
                analysis.clockModels(),
                analysis.trees()));
        column.setPrefWidth(110);
        return column;
    }

    private static String firstName(List<Partition> sharing) {
        return sharing.isEmpty() ? "" : sharing.get(0).name();
    }

    /**
     * BEAUti's Link and Unlink, over the selected rows and one thing at a time.
     *
     * <p>Unlink gives each selected partition a copy of what it shares now; link points them all at
     * the first selected partition's. The tree prior is on the list because it is separate from the
     * tree: two trees sharing a prior share its parameters, which is a model BEAUti cannot express.
     */
    private static Node linking(Analysis analysis, TableView<Partition> table) {
        ChoiceBox<Axis> axis = new ChoiceBox<>();
        axis.getItems().setAll(Axis.values());
        axis.setValue(Axis.SITE_MODEL);

        Button unlink = new Button("Unlink");
        unlink.setOnAction(event -> {
            for (Partition partition : List.copyOf(table.getSelectionModel().getSelectedItems())) {
                axis.getValue().unlink(analysis, partition);
            }
            table.refresh();
        });

        Button link = new Button("Link");
        link.setOnAction(event -> {
            List<Partition> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
            if (selected.size() < 2) return;
            for (Partition partition : selected.subList(1, selected.size())) {
                axis.getValue().link(analysis, partition, selected.get(0));
            }
            table.refresh();
        });

        BooleanBinding nothingSelected =
                Bindings.isEmpty(table.getSelectionModel().getSelectedItems());
        unlink.disableProperty().bind(nothingSelected);
        link.disableProperty().bind(Bindings.size(table.getSelectionModel().getSelectedItems()).lessThan(2));

        unlink.setTooltip(new javafx.scene.control.Tooltip(
                "Give each selected partition one of its own"));
        link.setTooltip(new javafx.scene.control.Tooltip(
                "Make the selected partitions share the first one's"));

        HBox box = new HBox(6, axis, link, unlink);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** What a link or unlink applies to. */
    private enum Axis {
        SITE_MODEL("Site model") {
            @Override
            void unlink(Analysis analysis, Partition partition) {
                analysis.unlinkSiteModel(partition);
            }

            @Override
            void link(Analysis analysis, Partition partition, Partition with) {
                analysis.linkSiteModel(partition, with.siteModel());
            }
        },
        CLOCK_MODEL("Clock model") {
            @Override
            void unlink(Analysis analysis, Partition partition) {
                analysis.unlinkClockModel(partition);
            }

            @Override
            void link(Analysis analysis, Partition partition, Partition with) {
                analysis.linkClockModel(partition, with.clockModel());
            }
        },
        TREE("Tree") {
            @Override
            void unlink(Analysis analysis, Partition partition) {
                analysis.unlinkTree(partition);
            }

            @Override
            void link(Analysis analysis, Partition partition, Partition with) {
                analysis.linkTree(partition, with.tree());
            }
        },
        TREE_PRIOR("Tree prior") {
            @Override
            void unlink(Analysis analysis, Partition partition) {
                analysis.unlinkTreePrior(partition.tree());
            }

            @Override
            void link(Analysis analysis, Partition partition, Partition with) {
                analysis.linkTreePrior(partition.tree(), with.tree());
            }
        };

        private final String label;

        Axis(String label) {
            this.label = label;
        }

        abstract void unlink(Analysis analysis, Partition partition);

        abstract void link(Analysis analysis, Partition partition, Partition with);

        @Override
        public String toString() {
            return label;
        }
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
