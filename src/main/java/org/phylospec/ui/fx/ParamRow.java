package org.phylospec.ui.fx;

import java.util.ArrayList;
import java.util.List;

import javafx.beans.property.ObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import org.phylospec.components.Generator;
import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
import org.phylospec.ui.model.Partition;
import org.phylospec.ui.spec.Library;

/**
 * Renders one {@link Param} as a form row.
 *
 * <p>Every argument of every component in the library goes through here. BEAUti needs a subclass per
 * input type because its editors also own the model plumbing; here a param is just a value plus two
 * flags, so one renderer covers all of them.
 */
public final class ParamRow {

    private ParamRow() {}

    /** Adds the row (and, for distribution-valued arguments, its nested editor) to {@code grid}. */
    public static void add(GridPane grid, Analysis analysis, Param param) {
        Library library = analysis.library();
        Node control = editorFor(analysis, param);

        HBox trailing = new HBox(8);
        trailing.setAlignment(Pos.CENTER_LEFT);

        CheckBox use = null;
        if (!param.required()) {
            use = new CheckBox("use");
            use.selectedProperty().bindBidirectional(param.includeProperty());
            trailing.getChildren().add(use);
        }
        if (param.estimable()) {
            // An indicator is not measured, it is summed over, so the tick says so.
            CheckBox estimate = new CheckBox(param.isIndicator() ? "average over" : "estimate");
            estimate.selectedProperty().bindBidirectional(param.estimateProperty());
            trailing.getChildren().add(estimate);
        }

        control.disableProperty().bind(param.includeProperty().not().or(param.estimateProperty()));
        if (use != null) {
            for (Node node : trailing.getChildren()) {
                if (node != use) node.disableProperty().bind(param.includeProperty().not());
            }
        }

        Form.row(grid, param.label(), tip(param), control, trailing.getChildren().isEmpty() ? null : trailing);
    }

    private static String tip(Param param) {
        String description = param.description();
        String type = "Type: " + param.type();
        return description == null || description.isBlank() ? type : description + "\n" + type;
    }

    /**
     * The right editor for an argument.
     *
     * <p>An alignment argument names one of the loaded partitions, and a vector of them names
     * several, so both are chosen from what is loaded rather than typed or picked from the loaders
     * that could produce one. That is the same knowledge the Partitions tab has, and it keeps the
     * expressions a model needs, such as the taxa of several loci together, out of the keyboard.
     */
    private static Node editorFor(Analysis analysis, Param param) {
        String head = Library.head(param.type());
        String inner = Library.inner(param.type());
        if ("Alignment".equals(head)) return partitionChooser(analysis, param);
        if ("Vector".equals(head) && inner != null && "Alignment".equals(Library.head(inner))) {
            return partitionList(analysis, param);
        }
        Library library = analysis.library();
        return param.isComponentValued()
                ? componentEditor(analysis, candidatesFor(library, param), param.priorProperty(),
                        chosen -> Component.nested(chosen, library, false))
                : valueField(param);
    }

    /** One of the loaded alignments, by name. */
    private static Node partitionChooser(Analysis analysis, Param param) {
        ComboBox<Partition> combo = new ComboBox<>(analysis.partitions());
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Partition partition) {
                return partition == null ? "" : partition.name();
            }

            @Override
            public Partition fromString(String name) {
                return null;
            }
        });
        analysis.partitions().stream()
                .filter(partition -> partition.name().equals(param.valueProperty().get()))
                .findFirst()
                .or(() -> analysis.partitions().stream().findFirst())
                .ifPresent(combo::setValue);
        if (combo.getValue() != null) param.valueProperty().set(combo.getValue().name());
        combo.valueProperty().addListener((observable, was, now) -> {
            if (now != null) param.valueProperty().set(now.name());
        });
        return combo;
    }

    /**
     * Several of the loaded alignments, as a vector of their names.
     *
     * <p>All of them to begin with, which is what a value spanning the loci means and what makes
     * this a tick rather than something to write out.
     */
    private static Node partitionList(Analysis analysis, Param param) {
        VBox box = new VBox(2);
        List<String> chosen = new ArrayList<>(named(param.valueProperty().get()));
        boolean fresh = chosen.isEmpty();

        for (Partition partition : analysis.partitions()) {
            CheckBox tick = new CheckBox(partition.name());
            tick.setSelected(fresh || chosen.contains(partition.name()));
            tick.selectedProperty().addListener((observable, was, now) -> write(analysis, box, param));
            box.getChildren().add(tick);
        }
        if (fresh) write(analysis, box, param);
        return box.getChildren().isEmpty() ? Form.note("No alignments loaded.") : box;
    }

    private static void write(Analysis analysis, VBox box, Param param) {
        List<String> ticked = box.getChildren().stream()
                .filter(node -> node instanceof CheckBox tick && tick.isSelected())
                .map(node -> ((CheckBox) node).getText())
                .toList();
        param.valueProperty().set("[" + String.join(", ", ticked) + "]");
    }

    /** The names inside a written vector, so a reloaded value ticks the right boxes. */
    private static List<String> named(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.replace("[", "").replace("]", "").split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    private static Node valueField(Param param) {
        TextField field = new TextField();
        field.textProperty().bindBidirectional(param.valueProperty());
        field.setPromptText(param.type());
        return field;
    }

    /** The components that can supply this argument: distributions for a prior, functions otherwise. */
    private static List<Generator> candidatesFor(Library library, Param param) {
        return param.isDistributionValued()
                ? library.priorsFor(param.priorSupport())
                : library.producing(param.type());
    }

    /**
     * A distribution chooser over the distributions that can serve as a prior on {@code param}.
     *
     * <p>What it builds when the user picks one has to be a prior, not a nested component. A nested
     * component is given the library, which turns any argument the library can produce into a slot
     * to fill rather than a value to type: a Yule's {@code taxa} would become a component chooser,
     * and the script would refer to a variable that was never declared. A prior also carries the
     * length of the value it is drawn from, so a Dirichlet over a twenty-element simplex keeps its
     * twenty concentrations when the user changes their mind about the distribution.
     */
    public static Node distributionEditor(Analysis analysis, Param param) {
        Library library = analysis.library();
        return componentEditor(analysis, library.priorsFor(param.priorSupport()), param.priorProperty(),
                chosen -> Component.prior(chosen, param.dimension(), library));
    }

    /**
     * A component chooser plus the chosen component's own arguments. This one widget serves priors
     * on estimated values, distribution-valued arguments, and function-valued arguments alike.
     */
    public static Node componentEditor(Analysis analysis, List<Generator> candidates,
                                       ObjectProperty<Component> holder,
                                       java.util.function.Function<Generator, Component> make) {
        ComboBox<Generator> combo = new ComboBox<>();
        combo.getItems().setAll(candidates);
        combo.setCellFactory(view -> describeCell(candidates));
        combo.setButtonCell(describeCell(candidates));
        combo.setMaxWidth(Double.MAX_VALUE);

        GridPane hyperparameters = Form.grid();
        hyperparameters.setPadding(new Insets(2, 0, 2, 12));

        VBox box = new VBox(6, combo, hyperparameters);

        combo.valueProperty().addListener((observable, was, now) -> {
            if (now == null) {
                holder.set(null);
            } else if (holder.get() == null || holder.get().generator() != now) {
                holder.set(make.apply(now));
            }
            rebuild(analysis, hyperparameters, holder.get());
        });

        Component existing = holder.get();
        if (existing != null && existing.generator() != null) {
            combo.setValue(existing.generator());
        } else if (!candidates.isEmpty()) {
            combo.setValue(candidates.get(0));
        }
        rebuild(analysis, hyperparameters, holder.get());

        return candidates.isEmpty() ? Form.note("Nothing in the library can supply this value.") : box;
    }

    private static void rebuild(Analysis analysis, GridPane grid, Component component) {
        grid.getChildren().clear();
        grid.getRowConstraints().clear();
        if (component == null) return;
        for (Param param : component.params()) {
            add(grid, analysis, param);
        }
    }

    private static ListCell<Generator> describeCell(List<Generator> among) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Generator item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : Component.describe(item, among));
            }
        };
    }
}
