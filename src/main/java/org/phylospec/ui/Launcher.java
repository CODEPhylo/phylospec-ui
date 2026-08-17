package org.phylospec.ui;

/**
 * Entry point for running from a plain classpath.
 *
 * <p>Launching a class that extends {@code Application} directly makes the JVM insist on the JavaFX
 * modules being on the module path; going through a class that does not, does not.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        PhyloSpecUI.main(args);
    }
}
