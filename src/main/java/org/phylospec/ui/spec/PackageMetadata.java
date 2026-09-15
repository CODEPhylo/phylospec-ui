package org.phylospec.ui.spec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.phylospec.components.ComponentLibrary;
import org.phylospec.components.EngineSpecificationSchema;
import org.phylospec.tiling.TileLibrary;

/** Component and engine metadata supplied by installed tile-library adapters. */
public final class PackageMetadata {

    private final List<ComponentLibrary> components;
    private final List<EngineSpecificationSchema> engines;

    private PackageMetadata(
            List<ComponentLibrary> components,
            List<EngineSpecificationSchema> engines) {
        this.components = List.copyOf(components);
        this.engines = List.copyOf(engines);
    }

    /** Discovers every tile-library adapter visible on the runtime classpath. */
    public static PackageMetadata discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    /** Discovers adapters visible to a caller-supplied runtime class loader. */
    public static PackageMetadata discover(ClassLoader classLoader) {
        List<TileLibrary<?>> libraries = new ArrayList<>();
        try {
            ServiceLoader.load(TileLibrary.class, Objects.requireNonNull(classLoader))
                    .forEach(libraries::add);
        } catch (ServiceConfigurationError | LinkageError failure) {
            throw new LoadFailure(message(failure), failure);
        }
        return fromLibraries(libraries);
    }

    private static String message(Throwable failure) {
        String missing = missingClass(failure);
        String detail = missing == null ? "" : ": missing runtime class " + missing;
        return "Could not load a package adapter" + detail
                + ". Include the adapter and all of its runtime dependencies in --adapter-path.";
    }

    private static String missingClass(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if ((current instanceof NoClassDefFoundError
                    || current instanceof ClassNotFoundException)
                    && current.getMessage() != null) {
                return current.getMessage().replace('/', '.');
            }
        }
        return null;
    }

    /** A discovered adapter could not be linked or instantiated. */
    public static final class LoadFailure extends RuntimeException {
        private LoadFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Discovers adapters from additional JAR files or compiled-classes directories. */
    public static PackageMetadata discover(List<Path> adapterPaths) {
        List<Path> paths = expand(List.copyOf(adapterPaths));

        URL[] urls = paths.stream().map(PackageMetadata::url).toArray(URL[]::new);
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader adapters = new URLClassLoader(urls, parent)) {
            return discover(adapters);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not close the package adapter loader", exception);
        }
    }

    /** Includes a classes directory itself and every JAR immediately inside it. */
    private static List<Path> expand(List<Path> adapterPaths) {
        List<Path> expanded = new ArrayList<>();
        for (Path supplied : adapterPaths) {
            Path path = supplied.toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new IllegalArgumentException("Adapter path does not exist: " + supplied);
            }
            expanded.add(path);

            if (!Files.isDirectory(path)) continue;
            try (var entries = Files.list(path)) {
                entries.filter(Files::isRegularFile)
                        .filter(entry -> entry.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(expanded::add);
            } catch (IOException exception) {
                throw new UncheckedIOException(
                        "Could not inspect package adapter directory: " + path, exception);
            }
        }
        return expanded.stream().distinct().toList();
    }

    private static URL url(Path path) {
        try {
            return path.toAbsolutePath().normalize().toUri().toURL();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load package adapter path: " + path, exception);
        }
    }

    /** Loads metadata from an already selected set of adapters. */
    public static PackageMetadata fromLibraries(
            List<? extends TileLibrary<?>> libraries) {
        try {
            return new PackageMetadata(
                    TileLibrary.loadComponentLibraries(libraries),
                    TileLibrary.loadEngineSpecifications(libraries));
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load installed package metadata", exception);
        }
    }

    public List<ComponentLibrary> components() {
        return components;
    }

    public List<EngineSpecificationSchema> engines() {
        return engines;
    }

    public Library library() {
        return Library.fromComponents(components);
    }

    public EngineSupport support() {
        return EngineSupport.fromSpecifications(engines);
    }
}
