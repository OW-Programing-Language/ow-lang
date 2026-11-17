package dev.oreo;

import org.junit.Assume;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.Assert.*;

public class DependencyManagerTest {
    private static void manifest(Path root, String dependencies) throws Exception {
        Files.writeString(root.resolve("dependencies.yml"), """
                format: 1
                repositories:
                  - id: github
                    url: https://github.com
                dependencies:
                """ + dependencies);
    }

    @Test public void initializesProjectManifest() throws Exception {
        Path dir = Files.createTempDirectory("ow-deps-init");
        DependencyManager.command(new String[]{"deps", "init", dir.toString()});
        assertTrue(Files.exists(dir.resolve("dependencies.yml")));
        assertTrue(DependencyManager.parse(dir.resolve("dependencies.yml")).dependencies().isEmpty());
        assertEquals(Map.of(), DependencyManager.forEntry(dir));
    }

    @Test public void parsesVersionedDependencyAndProjectRoot() throws Exception {
        Path dir = Files.createTempDirectory("ow-deps-parse");
        manifest(dir, """
                  - name: math
                    repository: github
                    path: OW-Programing-Language/ow-math-questions
                    version: v0.1.0
                """);
        var parsed = DependencyManager.parse(dir.resolve("dependencies.yml"));
        assertEquals(dir, parsed.root());
        assertEquals("math", parsed.dependencies().get(0).name());
        assertEquals("v0.1.0", parsed.dependencies().get(0).version());
        Path src = Files.createDirectories(dir.resolve("src"));
        assertEquals(dir.resolve("dependencies.yml"), DependencyManager.findManifest(src.resolve("main.ow")));
    }

    @Test public void rejectsDuplicateAliasesAndUnsafeUrls() throws Exception {
        Path dir = Files.createTempDirectory("ow-deps-invalid");
        manifest(dir, """
                  - name: math
                    repository: github
                    path: owner/repo
                    version: v1
                  - name: math
                    repository: github
                    path: owner/repo
                    version: v2
                """);
        assertThrows(IllegalArgumentException.class,
                () -> DependencyManager.parse(dir.resolve("dependencies.yml")));
        Files.writeString(dir.resolve("dependencies.yml"), """
                repositories:
                  - id: bad
                    url: http://example.org
                dependencies: []
                """);
        assertThrows(IllegalArgumentException.class,
                () -> DependencyManager.parse(dir.resolve("dependencies.yml")));
    }

    @Test public void aliasesCannotEscapeTheirDependencyRoot() throws Exception {
        Path dep = Files.createTempDirectory("ow-deps-root");
        Map<String, Path> aliases = Map.of("math", dep);
        Path importer = dep.resolve("main.ow");
        assertEquals(dep.resolve("src/MathQuestions.ow"),
                DependencyManager.resolveImport(importer, "@math/src/MathQuestions.ow", aliases));
        assertThrows(IllegalArgumentException.class,
                () -> DependencyManager.resolveImport(importer, "@math/../../outside.ow", aliases));
        assertThrows(IllegalArgumentException.class,
                () -> DependencyManager.resolveImport(importer, "@unknown/Some.ow", aliases));
    }

    @Test public void existingRelativeImportsStillWorkWithoutManifest() throws Exception {
        Path dir = Files.createTempDirectory("ow-no-deps");
        Files.writeString(dir.resolve("lib.ow"), """
                fn answer():
                  return: 42
                """);
        Files.writeString(dir.resolve("main.ow"), """
                import "lib.ow"
                main:
                  print: answer()
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(Loader.load(dir.resolve("main.ow")));
        assertTrue(out.toString().contains("42"));
    }

    @Test public void importsCachedLockedLibraryWithoutNetwork() throws Exception {
        Path dir = Files.createTempDirectory("ow-local-cache");
        Path dependency = Files.createDirectories(dir.resolve(".ow/deps/math"));
        Files.createDirectories(dependency.resolve("src"));
        Files.writeString(dependency.resolve("src/Math.ow"), """
                fn add(a, b):
                  return: a + b
                """);
        Assume.assumeTrue(gitWorks());
        git(dependency, "init", "--quiet");
        git(dependency, "add", ".");
        git(dependency, "-c", "user.name=OW CI", "-c", "user.email=ow@example.org",
                "commit", "--quiet", "-m", "test");
        String commit = git(dependency, "rev-parse", "HEAD").trim();
        manifest(dir, """
                  - name: math
                    repository: github
                    path: OW-Programing-Language/ow-math-questions
                    version: v0.1.0
                """);
        Files.writeString(dir.resolve("dependencies.lock.yml"),
                "format: 1\n" +
                "dependencies:\n" +
                "  - name: math\n" +
                "    repository: github\n" +
                "    path: OW-Programing-Language/ow-math-questions\n" +
                "    version: v0.1.0\n" +
                "    commit: \"" + commit + "\"\n");
        Files.writeString(dir.resolve("main.ow"), """
                import "@math/src/Math.ow"
                main:
                  print: add(20, 22)
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Interpreter(new PrintStream(out)).run(Loader.load(dir.resolve("main.ow")));
        assertTrue(out.toString().contains("42"));
    }

    private static boolean gitWorks() {
        try { return new ProcessBuilder("git", "--version").start().waitFor() == 0; }
        catch (Exception ignored) { return false; }
    }

    private static String git(Path dir, String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(output, 0, process.waitFor());
        return output;
    }
}
