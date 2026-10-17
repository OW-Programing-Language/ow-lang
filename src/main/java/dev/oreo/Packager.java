package dev.oreo;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Builds a self-contained, launchable native app (.exe + optional .msi) from an
 * Ow program, by bundling the Ow runtime jar + the program's .ow files + a JRE
 * with jpackage. Powers the {@code ow package} command.
 *
 * Requires a full JDK (for jpackage). Run via {@code java -jar OwIDE.jar} — the
 * native {@code ow.exe} ships only a runtime and cannot package.
 */
public final class Packager {
    private Packager() {}

    public static void build(Path entry, String name, String type,
                             Path outDir, Path icon, String version) throws Exception {
        entry = entry.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry)) throw new RuntimeException("No such file: " + entry);
        Path srcDir = entry.getParent();
        if (name == null) name = srcDir != null ? srcDir.getFileName().toString() : "OwApp";
        if (type == null) type = "gui";
        if (version == null) version = "1.0.0";
        if (outDir == null) outDir = Path.of("dist");

        String jpackage = findJpackage();
        Path runtimeJar = runtimeJar();

        // Stage: <tmp>/input/OwIDE.jar  and  <tmp>/input/game/** (.ow files)
        Path tmp = Files.createTempDirectory("ow-package-");
        Path input = tmp.resolve("input");
        Path game = input.resolve("game");
        Files.createDirectories(game);
        Files.copy(runtimeJar, input.resolve("OwIDE.jar"), StandardCopyOption.REPLACE_EXISTING);

        // Copy all .ow files under the entry's directory, preserving structure,
        // so relative imports resolve. Ensure the entry lands at game/main.ow.
        if (srcDir != null) {
            try (Stream<Path> walk = Files.walk(srcDir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(p) && p.toString().toLowerCase(Locale.ROOT).endsWith(".ow")) {
                        Path rel = srcDir.relativize(p);
                        Path dst = game.resolve(rel.toString());
                        Files.createDirectories(dst.getParent());
                        Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Path mainOw = game.resolve("main.ow");
        if (!Files.exists(mainOw)) {
            Files.copy(entry, mainOw, StandardCopyOption.REPLACE_EXISTING);
        }

        Files.createDirectories(outDir);
        // jpackage refuses to overwrite an existing app-image.
        Path existing = outDir.resolve(name);
        if (Files.exists(existing)) deleteTree(existing);

        List<String> args = new ArrayList<>(List.of(
                jpackage, "--type", "app-image",
                "--name", name, "--app-version", version,
                "--input", input.toString(),
                "--main-jar", "OwIDE.jar", "--main-class", "dev.oreo.GameMain",
                "--vendor", "Oreo Studios",
                "--dest", outDir.toString()));
        if (icon != null && Files.exists(icon)) { args.add("--icon"); args.add(icon.toString()); }
        if (type.equals("gui")) { args.add("--java-options"); args.add("-Dow.ui.mode=native"); }
        else { args.add("--win-console"); } // console + web

        System.out.println("[package] building " + name + " (" + type + ") with jpackage ...");
        run(args);

        String exeSuffix = isWindows() ? ".exe" : "";
        Path exe = outDir.resolve(name).resolve(name + exeSuffix);
        System.out.println("[package] done.");
        System.out.println("  App:  " + outDir.resolve(name).toAbsolutePath());
        System.out.println("  Exe:  " + exe.toAbsolutePath());

        // Optional installer if WiX (Windows) is available.
        if (isWindows() && onPath("candle")) {
            List<String> msi = new ArrayList<>(args);
            msi.set(msi.indexOf("app-image"), "msi");
            msi.add("--win-menu"); msi.add("--win-menu-group"); msi.add("Ow Apps");
            msi.add("--win-shortcut"); msi.add("--win-per-user-install");
            System.out.println("[package] building installer (.msi) ...");
            run(msi);
            System.out.println("  Installer: " + outDir.resolve(name + "-" + version + ".msi").toAbsolutePath());
        } else if (isWindows()) {
            System.out.println("[package] (install WiX 3.14 on PATH to also produce a .msi installer)");
        }
    }

    private static void run(List<String> args) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(args).inheritIO();
        int code = pb.start().waitFor();
        if (code != 0) throw new RuntimeException("jpackage failed with exit code " + code);
    }

    private static String findJpackage() {
        String exe = "jpackage" + (isWindows() ? ".exe" : "");
        // 1) the running JDK
        Path home = Path.of(System.getProperty("java.home"));
        Path p = home.resolve("bin").resolve(exe);
        if (Files.isRegularFile(p)) return p.toString();
        // 2) JAVA_HOME
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) {
            Path c = Path.of(jh).resolve("bin").resolve(exe);
            if (Files.isRegularFile(c)) return c.toString();
        }
        // 3) newest JDK under Program Files\Java (Windows)
        if (isWindows()) {
            Path base = Path.of("C:\\Program Files\\Java");
            if (Files.isDirectory(base)) {
                try (Stream<Path> s = Files.list(base)) {
                    Path best = s.map(d -> d.resolve("bin").resolve(exe))
                            .filter(Files::isRegularFile)
                            .sorted((a, b) -> b.toString().compareTo(a.toString()))
                            .findFirst().orElse(null);
                    if (best != null) return best.toString();
                } catch (IOException ignored) {}
            }
        }
        // 4) PATH
        if (onPath("jpackage")) return "jpackage";
        throw new RuntimeException("jpackage not found. 'ow package' needs a full JDK 17+ "
                + "(the native ow.exe can't package). Install a JDK, or run via: java -jar OwIDE.jar package ...");
    }

    private static Path runtimeJar() {
        try {
            Path loc = Path.of(Packager.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(loc) && loc.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return loc;
            }
            throw new RuntimeException("Could not locate the Ow runtime jar (are you running from classes?). "
                    + "Run 'ow package' from the installed ow/OwIDE, or: java -jar OwIDE.jar package ...");
        } catch (Exception e) {
            throw new RuntimeException("Could not locate the Ow runtime jar: " + e.getMessage(), e);
        }
    }

    private static boolean onPath(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        String exe = cmd + (isWindows() ? ".exe" : "");
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && Files.isRegularFile(Path.of(dir).resolve(exe))) return true;
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
