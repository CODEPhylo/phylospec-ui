package org.phylospec.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayList;
import java.util.List;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.phylospec.components.Generator;

import org.phylospec.ui.model.Analysis;
import org.phylospec.ui.model.TreeModel;
import org.phylospec.ui.model.Component;
import org.phylospec.ui.panel.ComponentPanel;
import org.phylospec.ui.panel.McmcPanel;
import org.phylospec.ui.panel.PartitionsPanel;
import org.phylospec.ui.panel.PriorsPanel;
import org.phylospec.ui.panel.TipDatesPanel;
import org.phylospec.ui.spec.EngineSupport;
import org.phylospec.ui.spec.Library;
import org.phylospec.ui.spec.ScriptReader;
import org.phylospec.ui.spec.ScriptWriter;
import org.phylospec.ui.spec.Validator;

/**
 * A BEAUti-shaped model builder that writes PhyloSpec.
 *
 * <p>The tabs follow BEAUti's flow — data, then dates, then the site, clock and tree models, then
 * priors and run settings — but the output is a PhyloSpec script rather than BEAST XML, and the
 * script is checked by the reference parser as you build it.
 */
public class PhyloSpecUI extends Application {

    /** How often the preview is regenerated. Cheap enough to poll, and it cannot miss an edit. */
    private static final Duration REFRESH = Duration.millis(300);

    /** Names an extra component library to load beside core. */
    private static final String LIBRARY_FLAG = "--library";
    private static final String ENGINE_FLAG = "--engine";

    private CommandLine commandLine;
    private Analysis analysis;
    private TextArea script;
    private Label status;
    private ScrollPane priorsHost;
    private List<Tab> allTabs;
    private Tab siteModelTab;
    private Tab clockModelTab;
    private SplitPane split;
    private javafx.scene.Node scriptPane;
    private Path savedTo;
    private String lastScript = "";

    @Override
    public void start(Stage stage) {
        commandLine = CommandLine.parse(getParameters().getRaw());
        analysis = new Analysis(Library.load(commandLine.libraries()),
                EngineSupport.load(commandLine.engines()));
        loadAlignmentsNamedOnTheCommandLine();

        TabPane tabs = buildTabs();
        scriptPane = buildScriptPane();

        split = new SplitPane(tabs, scriptPane);
        split.setDividerPositions(0.62);
        SplitPane.setResizableWithParent(scriptPane, Boolean.TRUE);

        status = new Label();
        status.getStyleClass().add("status-bar");
        status.setMaxWidth(Double.MAX_VALUE);

        BorderPane root = new BorderPane();
        root.setTop(buildMenu(stage));
        root.setCenter(split);
        root.setBottom(status);

        Scene scene = new Scene(root, 1120, 760);
        scene.getStylesheets().add(getClass().getResource("/org/phylospec/ui/phylospec.css").toExternalForm());

        stage.setTitle("PhyloSpec");
        stage.setScene(scene);
        stage.show();

        startRefreshing();
    }

    /** Alignments can be named on the command line, the way BEAUti accepts them. */
    private void loadAlignmentsNamedOnTheCommandLine() {
        for (String argument : commandLine.alignments()) {
            Path path = Path.of(argument);
            if (Files.isRegularFile(path)) analysis.addPartition(path);
        }
    }

    /**
     * What the command line asked for: engine component libraries, given as {@code --library <file>}
     * or {@code --library=<file>}, engine specifications, given the same two ways as
     * {@code --engine}, and alignments, given bare.
     */
    private record CommandLine(List<Path> libraries, List<Path> engines, List<String> alignments) {

        static CommandLine parse(List<String> raw) {
            List<Path> libraries = new ArrayList<>();
            List<Path> engines = new ArrayList<>();
            List<String> alignments = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                String argument = raw.get(i);
                if (argument.startsWith(LIBRARY_FLAG + "=")) {
                    libraries.add(Path.of(argument.substring(LIBRARY_FLAG.length() + 1)));
                } else if (argument.equals(LIBRARY_FLAG) && i + 1 < raw.size()) {
                    libraries.add(Path.of(raw.get(++i)));
                } else if (argument.startsWith(ENGINE_FLAG + "=")) {
                    engines.add(Path.of(argument.substring(ENGINE_FLAG.length() + 1)));
                } else if (argument.equals(ENGINE_FLAG) && i + 1 < raw.size()) {
                    engines.add(Path.of(raw.get(++i)));
                } else if (!argument.startsWith("--")) {
                    alignments.add(argument);
                }
            }
            return new CommandLine(libraries, engines, alignments);
        }
    }

    private TabPane buildTabs() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        priorsHost = new ScrollPane();

        siteModelTab = tab("Site Model", ComponentPanel.build(analysis,
                "The substitution process. Unlink a partition on the Partitions tab to give it one "
                        + "of its own.",
                analysis.siteModels(),
                model -> model,
                (owner, model) -> "Site model for " + named(owner.partitionsUsing(model)),
                model -> List.of(
                        ComponentPanel.Choice.of("Substitution model",
                                Library.SUBSTITUTION_MODEL, model.substitutionModel()),
                        new ComponentPanel.Choice("Site rates",
                                Library.SITE_RATES, model.siteRates(), true))));
        clockModelTab = tab("Clock Model", ComponentPanel.build(analysis,
                "How substitution rates vary along the branches of the tree.",
                analysis.clockModels(),
                clock -> clock,
                (owner, clock) -> "Clock model for " + named(owner.partitionsUsing(clock)),
                clock -> List.of(ComponentPanel.Choice.of("Clock model",
                        Library.CLOCK_MODEL, clock))));

        allTabs = List.of(
                tab("Partitions", PartitionsPanel.build(analysis)),
                tab("Tip Dates & Species", TipDatesPanel.build(analysis)),
                tab("Likelihood", ComponentPanel.build(analysis,
                        "The distribution the alignments are drawn from. Every other model tab "
                                + "supplies one of its arguments, so this is what decides which of "
                                + "them apply.",
                        List.of(ComponentPanel.Choice.of("Likelihood",
                                Library.TREE_LIKELIHOOD, analysis.likelihood())))),
                siteModelTab,
                clockModelTab,
                tab("Tree Prior", ComponentPanel.build(analysis,
                        "The process assumed to have generated the tree. Trees sharing a prior "
                                + "share its parameters, which is how one population size is "
                                + "estimated across several loci.",
                        analysis.treePriors(),
                        prior -> prior,
                        (owner, prior) -> "Tree prior for "
                                + String.join(", ", owner.treesUsing(prior).stream()
                                        .map(TreeModel::name).toList()),
                        prior -> List.of(ComponentPanel.Choice.of("Tree prior",
                                Library.TREE_PRIOR, prior)))),
                tab("Priors", PriorsPanel.build(analysis, priorsHost)),
                tab("MCMC", McmcPanel.build(analysis)));

        analysis.likelihood().generatorProperty()
                .addListener((observable, was, now) -> showWhatTheLikelihoodNeeds(tabs));
        showWhatTheLikelihoodNeeds(tabs);

        // The Priors tab is derived from the others, so it is rebuilt each time it is shown.
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, was, now) -> {
            if (now != null && "Priors".equals(now.getText())) PriorsPanel.refresh(analysis, priorsHost);
        });
        return tabs;
    }

    /** Lists partitions the way a heading should read: "gene1 and gene2", or "no partition". */
    private static String named(List<org.phylospec.ui.model.Partition> partitions) {
        List<String> names = partitions.stream()
                .map(org.phylospec.ui.model.Partition::name)
                .toList();
        if (names.isEmpty()) return "no partition yet";
        if (names.size() == 1) return names.get(0);
        return String.join(", ", names.subList(0, names.size() - 1))
                + " and " + names.get(names.size() - 1);
    }

    /**
     * Shows only what the chosen likelihood asks for. SNAPP takes no rate matrix and no branch
     * rates, so choosing it leaves neither tab standing; PhyloBM takes site rates but no rate
     * matrix, so the Site Model tab keeps one chooser and loses the other.
     *
     * <p>A model whose chooser goes away is cleared with it, or the script would keep a rate matrix
     * that nothing asks for. One whose chooser comes back is given a component, so the script stays
     * complete rather than waiting for the user to notice a blank tab.
     */
    private void showWhatTheLikelihoodNeeds(TabPane tabs) {
        boolean matrix = analysis.likelihoodTakes("qMatrix");
        boolean rates = analysis.likelihoodTakes("siteRates");
        boolean clock = analysis.likelihoodTakes("branchRates");

        require(analysis.substitutionModel(), Library.SUBSTITUTION_MODEL, matrix);
        require(analysis.siteRates(), Library.SITE_RATES, rates && analysis.likelihoodNeeds("siteRates"));
        require(analysis.clockModel(), Library.CLOCK_MODEL, clock && analysis.likelihoodNeeds("branchRates"));
        if (!rates) analysis.siteRates().generatorProperty().set(null);
        if (!clock) analysis.clockModel().generatorProperty().set(null);

        List<ComponentPanel.Choice> siteChoices = new ArrayList<>();
        if (matrix) {
            siteChoices.add(ComponentPanel.Choice.of("Substitution model",
                    Library.SUBSTITUTION_MODEL, analysis.substitutionModel()));
        }
        if (rates) {
            siteChoices.add(new ComponentPanel.Choice("Site rates", Library.SITE_RATES,
                    analysis.siteRates(), !analysis.likelihoodNeeds("siteRates")));
        }
        if (!siteChoices.isEmpty()) {
            siteModelTab.setContent(ComponentPanel.build(analysis,
                    "The substitution process, shared by every partition.", siteChoices));
        }
        if (clock) {
            clockModelTab.setContent(ComponentPanel.build(analysis,
                    "How substitution rates vary along the branches of the tree.",
                    List.of(new ComponentPanel.Choice("Clock model", Library.CLOCK_MODEL,
                            analysis.clockModel(), !analysis.likelihoodNeeds("branchRates")))));
        }

        List<Tab> wanted = allTabs.stream()
                .filter(tab -> (tab != siteModelTab || !siteChoices.isEmpty())
                        && (tab != clockModelTab || clock))
                .toList();
        if (wanted.equals(tabs.getTabs())) return;

        Tab selected = tabs.getSelectionModel().getSelectedItem();
        tabs.getTabs().setAll(wanted);
        if (wanted.contains(selected)) tabs.getSelectionModel().select(selected);
    }

    /** Puts a component on a chooser that has just appeared, so the script stays complete. */
    private void require(Component component, String role, boolean wanted) {
        if (!wanted || component.generator() != null) return;
        List<Generator> choices = analysis.choicesFor(role);
        if (!choices.isEmpty()) component.generatorProperty().set(choices.get(0));
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private javafx.scene.Node buildScriptPane() {
        script = new TextArea();
        script.setEditable(false);
        script.getStyleClass().add("script");
        VBox.setVgrow(script, Priority.ALWAYS);

        Label heading = new Label("PhyloSpec script");
        heading.getStyleClass().add("script-heading");

        VBox box = new VBox(heading, script);
        box.getStyleClass().add("script-pane");
        return box;
    }

    private MenuBar buildMenu(Stage stage) {
        MenuItem open = new MenuItem("Open…");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(event -> open(stage));

        MenuItem save = new MenuItem("Save");
        save.setAccelerator(KeyCombination.keyCombination("Shortcut+S"));
        save.setOnAction(event -> save(stage, false));

        MenuItem saveAs = new MenuItem("Save As…");
        saveAs.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+S"));
        saveAs.setOnAction(event -> save(stage, true));

        MenuItem copy = new MenuItem("Copy script to clipboard");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(script.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });

        MenuItem quit = new MenuItem("Quit");
        quit.setAccelerator(KeyCombination.keyCombination("Shortcut+Q"));
        quit.setOnAction(event -> stage.close());

        Menu file = new Menu("File");
        file.getItems().addAll(open, new SeparatorMenuItem(), save, saveAs, copy,
                new SeparatorMenuItem(), quit);

        CheckMenuItem showScript = new CheckMenuItem("Show PhyloSpec script");
        showScript.setSelected(true);
        showScript.setOnAction(event -> {
            if (showScript.isSelected()) {
                if (!split.getItems().contains(scriptPane)) {
                    split.getItems().add(scriptPane);
                    split.setDividerPositions(0.62);
                }
            } else {
                split.getItems().remove(scriptPane);
            }
        });

        Menu view = new Menu("View");
        view.getItems().add(showScript);

        MenuItem about = new MenuItem("About PhyloSpec UI");
        about.setOnAction(event -> new Alert(Alert.AlertType.INFORMATION,
                "PhyloSpec UI builds a phylogenetic model in the shape BEAUti uses, "
                        + "and writes it out as a PhyloSpec script.").showAndWait());

        Menu help = new Menu("Help");
        help.getItems().add(about);

        MenuBar bar = new MenuBar(file, view, help);
        bar.setUseSystemMenuBar(true);
        return bar;
    }

    /** Regenerates the script, and revalidates it whenever it has actually changed. */
    private void startRefreshing() {
        Timeline timeline = new Timeline(new KeyFrame(REFRESH, event -> {
            String current = ScriptWriter.write(analysis);
            if (current.equals(lastScript)) return;
            lastScript = current;
            script.setText(current);
            report(Validator.check(analysis.library(), current));
        }));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.playFromStart();
    }

    /**
     * Shows what the reference implementation made of the script.
     *
     * <p>A refusal is shown ahead of a doubt, since a script the resolver rejects is wrong whatever
     * else is said about it. A doubt on its own is still worth showing — a constraint the types say
     * does not hold is usually a wrong-length vector — but it is not styled as an error, because
     * the resolver did not refuse the script and an engine may well run it.
     */
    private void report(Validator.Report reported) {
        List<String> problems = reported.problems();
        List<String> warnings = reported.warnings();
        List<String> shown = problems.isEmpty() ? warnings : problems;

        status.getStyleClass().removeAll("status-ok", "status-error", "status-warning");
        if (shown.isEmpty()) {
            status.setText("Script is valid.");
            status.getStyleClass().add("status-ok");
        } else {
            String more = shown.size() > 1 ? "  (+" + (shown.size() - 1) + " more)" : "";
            status.setText(shown.get(0) + more);
            status.getStyleClass().add(problems.isEmpty() ? "status-warning" : "status-error");
        }

        List<String> all = reported.all();
        status.setTooltip(all.isEmpty() ? null
                : new javafx.scene.control.Tooltip(String.join("\n", all)));
    }

    /**
     * Loads a script back onto the tabs. A script the tabs cannot express is refused outright rather
     * than partly loaded, since a partly loaded analysis looks complete and the next save would
     * quietly drop whatever could not be read.
     */
    private void open(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open PhyloSpec script");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("PhyloSpec script", "*.phylospec"));
        File chosen = chooser.showOpenDialog(stage);
        if (chosen == null) return;

        Path path = chosen.toPath();
        Analysis loaded;
        try {
            loaded = ScriptReader.read(analysis.library(), Files.readString(path));
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not read " + path.getFileName() + ": "
                    + e.getMessage()).showAndWait();
            return;
        } catch (ScriptReader.Unsupported e) {
            new Alert(Alert.AlertType.ERROR, "Could not open " + path.getFileName() + ".\n\n"
                    + e.getMessage()).showAndWait();
            return;
        }

        analysis = loaded;
        split.getItems().set(0, buildTabs());
        savedTo = path;
        lastScript = "";
        stage.setTitle("PhyloSpec — " + path.getFileName());
        warnAboutMissingAlignments();
    }

    /** The script names its alignments by path, so opening it elsewhere can leave them unreadable. */
    private void warnAboutMissingAlignments() {
        List<String> missing = analysis.partitions().stream()
                .map(partition -> partition.fileProperty().get())
                .filter(file -> !Files.isRegularFile(Path.of(file)))
                .toList();
        if (missing.isEmpty()) return;
        new Alert(Alert.AlertType.WARNING,
                "The script refers to alignments that are not where it says they are:\n\n"
                        + String.join("\n", missing)
                        + "\n\nThe model has loaded, but these paths need correcting.").showAndWait();
    }

    private void save(Stage stage, boolean askForPath) {
        Path target = savedTo;
        if (target == null || askForPath) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save PhyloSpec script");
            chooser.setInitialFileName("analysis.phylospec");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("PhyloSpec script", "*.phylospec"));
            File chosen = chooser.showSaveDialog(stage);
            if (chosen == null) return;
            target = chosen.toPath();
        }
        try {
            Files.writeString(target, script.getText());
            savedTo = target;
            stage.setTitle("PhyloSpec — " + target.getFileName());
        } catch (IOException e) {
            new Alert(Alert.AlertType.ERROR, "Could not save: " + e.getMessage()).showAndWait();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
