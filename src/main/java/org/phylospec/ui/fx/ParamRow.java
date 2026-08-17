package org.phylospec.ui.fx;

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
import org.phylospec.ui.model.Component;
import org.phylospec.ui.model.Param;
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
    public static void add(GridPane grid, Library library, Param param) {
        Node control = param.isComponentValued()
                ? componentEditor(library, candidatesFor(library, param), param.priorProperty())
                : valueField(param);

        HBox trailing = new HBox(8);
        trailing.setAlignment(Pos.CENTER_LEFT);

        CheckBox use = null;
        if (!param.required()) {
            use = new CheckBox("use");
            use.selectedProperty().bindBidirectional(param.includeProperty());
            trailing.getChildren().add(use);
        }
        if (param.estimable()) {
            CheckBox estimate = new CheckBox("estimate");
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

    /** A distribution chooser over the distributions that can serve as a prior on {@code support}. */
    public static Node distributionEditor(Library library, String support, ObjectProperty<Component> holder) {
        return componentEditor(library, library.priorsFor(support), holder);
    }

    /**
     * A component chooser plus the chosen component's own arguments. This one widget serves priors
     * on estimated values, distribution-valued arguments, and function-valued arguments alike.
     */
    public static Node componentEditor(Library library, List<Generator> candidates,
                                       ObjectProperty<Component> holder) {
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
                holder.set(Component.nested(now, library, false));
            }
            rebuild(library, hyperparameters, holder.get());
        });

        Component existing = holder.get();
        if (existing != null && existing.generator() != null) {
            combo.setValue(existing.generator());
        } else if (!candidates.isEmpty()) {
            combo.setValue(candidates.get(0));
        }
        rebuild(library, hyperparameters, holder.get());

        return candidates.isEmpty() ? Form.note("Nothing in the library can supply this value.") : box;
    }

    private static void rebuild(Library library, GridPane grid, Component component) {
        grid.getChildren().clear();
        grid.getRowConstraints().clear();
        if (component == null) return;
        for (Param param : component.params()) {
            add(grid, library, param);
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
