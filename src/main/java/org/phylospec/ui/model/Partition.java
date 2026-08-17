package org.phylospec.ui.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * One alignment file and how its taxon names should be read.
 *
 * <p>Tip dates are expressed the way PhyloSpec expresses them: as a {@code parse(...)} argument to
 * the loader, rather than as a separate date trait.
 */
public final class Partition {

    /** How a date embedded in the taxon name is interpreted. */
    public enum DateKind {
        /** Time before present, as {@code age=parse(...)}. */
        AGE("age", "Age (time before present)"),
        /** A calendar date, as {@code date=parse(...)}. */
        DATE("date", "Date (forward in time)");

        private final String argument;
        private final String label;

        DateKind(String argument, String label) {
            this.argument = argument;
            this.label = label;
        }

        public String argument() {
            return argument;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** How the date is located within the taxon name. */
    public enum ParseMode {
        SPLIT("Split on delimiter"),
        REGEX("Regular expression");

        private final String label;

        ParseMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty file = new SimpleStringProperty();
    private final IntegerProperty taxa = new SimpleIntegerProperty();
    private final IntegerProperty sites = new SimpleIntegerProperty();

    private final BooleanProperty useTipDates = new SimpleBooleanProperty(false);
    private final javafx.beans.property.ObjectProperty<DateKind> dateKind =
            new javafx.beans.property.SimpleObjectProperty<>(DateKind.AGE);
    private final javafx.beans.property.ObjectProperty<ParseMode> parseMode =
            new javafx.beans.property.SimpleObjectProperty<>(ParseMode.SPLIT);
    private final StringProperty delimiter = new SimpleStringProperty("_");
    private final StringProperty part = new SimpleStringProperty("2");
    private final StringProperty regex = new SimpleStringProperty("(\\d+\\.?\\d*)$");

    public Partition(Path path) {
        this.file.set(path.toString());
        this.name.set(variableName(path));
        Summary summary = summarise(path);
        this.taxa.set(summary.taxa());
        this.sites.set(summary.sites());
    }

    /** The loader function this file needs, chosen from the extension. */
    public String loader() {
        String lower = file.get() == null ? "" : file.get().toLowerCase();
        return lower.endsWith(".nex") || lower.endsWith(".nexus") ? "fromNexus" : "fromFasta";
    }

    /**
     * Turns a file name into a camelCase variable name, as PhyloSpec's naming conventions ask for.
     * A leading run of capitals is an acronym and is lowercased whole, so RSV2 becomes {@code rsv2}
     * rather than {@code rSV2}.
     */
    static String variableName(Path path) {
        String base = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        String cleaned = base.replaceAll("[^A-Za-z0-9]", "");
        if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) return "data" + cleaned;

        int acronym = 0;
        while (acronym < cleaned.length() && Character.isUpperCase(cleaned.charAt(acronym))) acronym++;
        // A capital that begins a word belongs to that word, not to the acronym.
        if (acronym > 1 && acronym < cleaned.length() && Character.isLowerCase(cleaned.charAt(acronym))) acronym--;
        return cleaned.substring(0, Math.max(acronym, 1)).toLowerCase() + cleaned.substring(Math.max(acronym, 1));
    }

    private record Summary(int taxa, int sites) {}

    /**
     * Reads just enough of the file to fill the Partitions table. A failure here is not worth
     * blocking on — the counts are informational, and the engine reads the file for real.
     */
    private static Summary summarise(Path path) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String lower = path.getFileName().toString().toLowerCase();
            return lower.endsWith(".nex") || lower.endsWith(".nexus")
                    ? summariseNexus(lines)
                    : summariseFasta(lines);
        } catch (IOException | RuntimeException e) {
            return new Summary(0, 0);
        }
    }

    private static Summary summariseNexus(List<String> lines) {
        String text = String.join("\n", lines);
        return new Summary(match(text, "ntax\\s*=\\s*(\\d+)"), match(text, "nchar\\s*=\\s*(\\d+)"));
    }

    private static int match(String text, String pattern) {
        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static Summary summariseFasta(List<String> lines) {
        int taxa = 0;
        int sites = 0;
        boolean inFirst = false;
        for (String line : lines) {
            if (line.startsWith(">")) {
                taxa++;
                inFirst = taxa == 1;
            } else if (inFirst) {
                sites += line.trim().length();
            }
        }
        return new Summary(taxa, sites);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty fileProperty() {
        return file;
    }

    public IntegerProperty taxaProperty() {
        return taxa;
    }

    public IntegerProperty sitesProperty() {
        return sites;
    }

    public BooleanProperty useTipDatesProperty() {
        return useTipDates;
    }

    public javafx.beans.property.ObjectProperty<DateKind> dateKindProperty() {
        return dateKind;
    }

    public javafx.beans.property.ObjectProperty<ParseMode> parseModeProperty() {
        return parseMode;
    }

    public StringProperty delimiterProperty() {
        return delimiter;
    }

    public StringProperty partProperty() {
        return part;
    }

    public StringProperty regexProperty() {
        return regex;
    }

    public String name() {
        return name.get();
    }
}
