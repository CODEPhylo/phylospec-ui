package org.phylospec.ui.panel;

import javafx.scene.Node;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import org.phylospec.ui.fx.Form;
import org.phylospec.ui.model.Analysis;

/**
 * The MCMC tab, written into the script's {@code mcmc} block.
 *
 * <p>PhyloSpec keeps run settings out of the model, and leaves engines to pick defaults for anything
 * not given — so this tab is deliberately short. Operators have no equivalent at all: they are
 * machinery an engine chooses, not part of the model description.
 */
public final class McmcPanel {

    private McmcPanel() {}

    public static Node build(Analysis analysis) {
        GridPane grid = Form.grid();

        TextField chainLength = new TextField();
        chainLength.textProperty().bindBidirectional(analysis.chainLengthProperty());
        Form.row(grid, "Chain length", "Number of MCMC states to run", chainLength, null);

        TextField logEvery = new TextField();
        logEvery.textProperty().bindBidirectional(analysis.logEveryProperty());
        Form.row(grid, "Log every", "How often to sample the chain", logEvery, null);

        TextField logFile = new TextField();
        logFile.textProperty().bindBidirectional(analysis.logFileProperty());
        Form.row(grid, "Log file", null, logFile, null);

        TextField seed = new TextField();
        seed.textProperty().bindBidirectional(analysis.seedProperty());
        seed.setPromptText("engine default");
        Form.row(grid, "Random seed", "Leave blank to let the engine choose", seed, null);

        TextField treeName = new TextField();
        treeName.textProperty().bindBidirectional(analysis.treeNameProperty());
        Form.row(grid, "Tree variable", "Name of the tree in the generated script", treeName, null);

        return Form.panel(
                Form.caption("Run settings, written to the mcmc block. Engines fill in anything left out."),
                grid);
    }
}
