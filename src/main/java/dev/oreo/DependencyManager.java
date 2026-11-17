package dev.oreo;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Project-scoped OW dependencies.yml resolver.
 *
 * Libraries are fetched from explicitly declared HTTPS Git repositories and
 * imported using "import \"@alias/path/to/File.ow\"". The lockfile records
 * immutable Git commits; install reuses those commits, and update refreshes them.
 * Existing OW projects without a manifest retain relative-import semantics.
 */
public final class DependencyManager {
    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+");
    private static final Pattern SHA = Pattern.compile("[a-fA-F0-9]{40}");

    record Repository(String id, String url) {}
    record Dependency(String name, String repository, String path, String version) {}
    record Manifest(Path root, List<Repository> repositories, List<Dependency> dependencies) {}
    record Locked(String repository, String path, String version, String commit) {}

    private DependencyManager() {}

    /** Locate the nearest project manifest, starting at the entry's directory. */
    public static Path findManifest(Path entryOrDirectory) {
        Path at = entryOrDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(at)) at = at.getParent();
        while (at != null) {
            Path manifest = at.resolve("dependencies.yml");
            if (Files.isRegularFile(manifest)) return manifest;
            at = at.getParent();
        }
        return null;
    }

    /** Backwards-compatible: a project without dependencies.yml has no aliases. */
    public static Map<String, Path> forEntry(Path entryOrDirectory) throws IOException {
        Path manifest = findManifest(entryOrDirectory);
        return manifest == null ? Map.of() : install(manifest, false);
    }

    public static Path resolveImport(Path importingFile, String imported, Map<String, Path> aliases) {
        if (!imported.startsWith("@")) {
            return importingFile.toAbsolutePath().normalize().getParent().resolve(imported).normalize();
        }
        int slash = imported.indexOf('/');
        if (slash < 2 || slash == imported.length() - 1) {
            throw new IllegalArgumentException("Dependency import must be @alias/path/to/File.ow: " + imported);
        }
        String alias = imported.substring(1, slash);
        Path library = aliases.get(alias);
        if (library == null) {
            throw new IllegalArgumentException("Unknown OW dependency @" + alias +
                    ". Add it to dependencies.yml and run 'ow deps install'.");
        }
        Path file = library.resolve(imported.substring(slash + 1)).normalize();
        if (!file.startsWith(library) || Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("Dependency import escapes @" + alias + ": " + imported);
        }
        return file;
    }

    static Manifest parse(Path manifestFile) throws IOException {
        Object object = loadYaml(manifestFile);
        Map<?, ?> root = asMap(object, "dependencies.yml");
        Object formatValue = root.containsKey("format") ? root.get("format") : 1;
        int format = Integer.parseInt(String.valueOf(formatValue));
        if (format != 1) throw new IllegalArgumentException("Unsupported dependencies.yml format: " + format);

        List<Repository> repositories = new ArrayList<>();
        Map<String, String> ids = new LinkedHashMap<>();
        for (Object entry : asList(root.get("repositories"), "repositories")) {
            Map<?, ?> repo = asMap(entry, "repository");
            String id = field(repo, "id");
            String url = field(repo, "url");
            checkName(id, "repository id");
            validateBaseUrl(url);
            if (ids.putIfAbsent(id, url) != null) {
                throw new IllegalArgumentException("Duplicate repository: " + id);
            }
            repositories.add(new Repository(id, url));
        }

        List<Dependency> dependencies = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object entry : asList(root.get("dependencies"), "dependencies")) {
            Map<?, ?> dep = asMap(entry, "dependency");
            String name = field(dep, "name");
            String repository = field(dep, "repository");
            String path = field(dep, "path");
            String version = field(dep, "version");
            checkName(name, "dependency name");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate dependency: " + name);
            if (!ids.containsKey(repository)) {
                throw new IllegalArgumentException("Unknown repository '" + repository + "' for " + name);
            }
            if (!PATH.matcher(path).matches() || Arrays.stream(path.split("/"))
                    .anyMatch(s -> s.equals(".") || s.equals(".."))) {
                throw new IllegalArgumentException("Invalid repository path: " + path);
            }
            if (version.isBlank() || version.startsWith("-") || version.contains("..") ||
                    !version.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) {
                throw new IllegalArgumentException("Invalid dependency version/ref: " + version);
            }
            dependencies.add(new Dependency(name, repository, path, version));
        }
        return new Manifest(manifestFile.toAbsolutePath().normalize().getParent(),
                List.copyOf(repositories), List.copyOf(dependencies));
    }

    private static Object loadYaml(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(200_000);
        try (var stream = Files.newInputStream(file)) {
            return new Yaml(new SafeConstructor(options)).load(stream);
        } catch (RuntimeException e) {
            throw new IOException("Invalid YAML in " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<?, ?> asMap(Object value, String context) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(context + " must be a YAML mapping");
        }
        return map;
    }

    private static List<?> asList(Object value, String context) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(context + " must be a YAML list");
        }
        return list;
    }

    private static String field(Map<?, ?> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("Missing/invalid '" + key + "' in dependencies.yml");
        }
        return s.trim();
    }

    private static void checkName(String name, String description) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + description + ": " + name);
        }
    }

    private static void validateBaseUrl(String url) {
        URI uri;
        try { uri = URI.create(url); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid repository URL: " + url, e); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Repository URL must use HTTPS without credentials: " + url);
        }
    }

    private static String remote(Dependency dep, Map<String, String> repositories) {
        return repositories.get(dep.repository()).replaceAll("/+$", "") + "/" + dep.path() + ".git";
    }

    private static Map<String, Locked> readLock(Path root) throws IOException {
        Path file = root.resolve("dependencies.lock.yml");
        if (!Files.isRegularFile(file)) return Map.of();
        Map<?, ?> doc = asMap(loadYaml(file), "dependencies.lock.yml");
        Map<String, Locked> locks = new HashMap<>();
        for (Object item : asList(doc.get("dependencies"), "locked dependencies")) {
            Map<?, ?> row = asMap(item, "lock entry");
            String name = field(row, "name");
            String commit = field(row, "commit");
            if (!SHA.matcher(commit).matches()) {
                throw new IllegalArgumentException("Invalid locked Git SHA for @" + name);
            }
            if (locks.putIfAbsent(name, new Locked(field(row, "repository"), field(row, "path"),
                    field(row, "version"), commit)) != null) {
                throw new IllegalArgumentException("Duplicate dependency lock for @" + name);
            }
        }
        return locks;
    }

    /** Resolve all direct dependencies; network is avoided for valid cached locks. */
    public static Map<String, Path> install(Path manifestFile, boolean update) throws IOException {
        Manifest manifest = parse(manifestFile);
        Map<String, String> repos = new HashMap<>();
        for (Repository repo : manifest.repositories()) repos.put(repo.id(), repo.url());

        Path cache = manifest.root().resolve(".ow").resolve("deps");
        Map<String, Locked> old = update ? Map.of() : readLock(manifest.root());
        Map<String, Locked> resolved = new LinkedHashMap<>();
        Map<String, Path> installed = new LinkedHashMap<>();
        for (Dependency dep : manifest.dependencies()) {
            Locked locked = old.get(dep.name());
            if (locked != null && (!locked.repository().equals(dep.repository()) ||
                    !locked.path().equals(dep.path()) || !locked.version().equals(dep.version()))) {
                throw new IllegalArgumentException("dependencies.yml changed for @" + dep.name() +
                        "; run 'ow deps update' to refresh dependencies.lock.yml");
            }
            Path directory = cache.resolve(dep.name()).normalize();
            String commit = locked == null ? null : locked.commit();
            if (Files.isSymbolicLink(directory)) {
                throw new IllegalArgumentException("Symlinked dependency directory is unsafe: " + directory);
            }
            if (!update && commit != null && Files.isDirectory(directory) &&
                    commit.equals(gitHead(directory))) {
                resolved.put(dep.name(), locked);
                installed.put(dep.name(), directory);
                continue;
            }

            Files.createDirectories(cache);
            Path staging = Files.createTempDirectory(cache, ".ow-fetch-");
            try {
                String url = remote(dep, repos);
                String wanted = commit == null ? dep.version() : commit;
                if (SHA.matcher(wanted).matches()) {
                    git(null, "init", "--quiet", staging.toString());
                    git(staging, "remote", "add", "origin", url);
                    git(staging, "fetch", "--quiet", "--depth", "1", "origin", wanted);
                    git(staging, "checkout", "--quiet", "--detach", "FETCH_HEAD");
                } else {
                    git(null, "clone", "--quiet", "--depth", "1", "--branch", wanted,
                            url, staging.toString());
                }
                String actual = gitHead(staging);
                if (commit != null && !actual.equals(commit)) {
                    throw new IOException("Lockfile mismatch for @" + dep.name() +
                            ": expected " + commit + " but fetched " + actual);
                }
                replaceCache(staging, directory);
                commit = actual;
            } finally {
                if (Files.exists(staging)) deleteTree(staging);
            }
            resolved.put(dep.name(), new Locked(dep.repository(), dep.path(), dep.version(), commit));
            installed.put(dep.name(), directory);
        }
        writeLock(manifest.root(), resolved);
        return Collections.unmodifiableMap(installed);
    }

    private static String gitHead(Path directory) throws IOException {
        if (!Files.isDirectory(directory.resolve(".git"))) return "";
        return git(directory, "rev-parse", "HEAD").trim();
    }

    private static String git(Path directory, String... arguments) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        if (directory != null) {
            cmd.add("-C");
            cmd.add(directory.toString());
        }
        cmd.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("Git is required for OW dependencies (install Git and add it to PATH)", e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Git dependency command failed: " + String.join(" ", cmd) +
                        "\n" + output.strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving OW dependencies", e);
        }
        return output;
    }

    private static void replaceCache(Path staging, Path dest) throws IOException {
        Path backup = dest.resolveSibling("." + dest.getFileName() + ".previous");
        if (Files.exists(backup)) throw new IOException("Stale dependency backup: " + backup);
        boolean hadExisting = Files.exists(dest);
        if (hadExisting) Files.move(dest, backup);
        try {
            Files.move(staging, dest);
        } catch (IOException e) {
            if (hadExisting) Files.move(backup, dest);
            throw e;
        }
        if (hadExisting) deleteTree(backup);
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static void writeLock(Path root, Map<String, Locked> locks) throws IOException {
        StringBuilder content = new StringBuilder("format: 1\n");
        content.append("dependencies:\n");
        for (var entry : locks.entrySet()) {
            Locked d = entry.getValue();
            content.append("  - name: ").append(yamlString(entry.getKey())).append("\n");
            content.append("    repository: ").append(yamlString(d.repository())).append("\n");
            content.append("    path: ").append(yamlString(d.path())).append("\n");
            content.append("    version: ").append(yamlString(d.version())).append("\n");
            content.append("    commit: ").append(yamlString(d.commit())).append("\n");
        }
        Path dest = root.resolve("dependencies.lock.yml");
        if (Files.isRegularFile(dest) && content.toString().equals(Files.readString(dest))) return;
        Path tmp = Files.createTempFile(root, ".ow-lock-", ".yml");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String yamlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static void command(String[] args) throws IOException {
        String op = args.length > 1 ? args[1] : "install";
        Path directory = Path.of(args.length > 2 ? args[2] : ".").toAbsolutePath().normalize();
        if ("init".equals(op)) {
            Files.createDirectories(directory);
            Path manifest = directory.resolve("dependencies.yml");
            if (Files.exists(manifest)) throw new IOException("Already exists: " + manifest);
            Files.writeString(manifest, """
                    format: 1
                    repositories:
                      - id: github
                        url: https://github.com
                    dependencies: []
                    """, StandardCharsets.UTF_8);
            System.out.println("Created " + manifest);
            return;
        }
        Path manifest = findManifest(directory);
        if (manifest == null) throw new IOException("No dependencies.yml found; run 'ow deps init'.");
        if ("list".equals(op)) {
            for (Dependency dep : parse(manifest).dependencies()) {
                System.out.println("@" + dep.name() + " " + dep.path() + " " + dep.version());
            }
        } else if ("install".equals(op) || "update".equals(op)) {
            Map<String, Path> installed = install(manifest, "update".equals(op));
            System.out.println("Resolved " + installed.size() + " OW dependencies in " + manifest.getParent());
        } else {
            throw new IllegalArgumentException("Usage: ow deps [init|install|update|list] [project-directory]");
        }
    }
}
