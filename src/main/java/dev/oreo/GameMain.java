package dev.oreo;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point for a packaged, self-contained Ow application (e.g. a game .exe
 * produced with jpackage). It runs the bundled program at
 * the entry recorded in {@code <appdir>/game/entry.txt} through the interpreter, so the shipped .exe
 * just opens the app — no `ow run`, no Java install needed.
 *
 * The game's .ow files are placed under an `app/game/` folder next to the jar
 * by jpackage; this class locates them relative to its own code source.
 */
public class GameMain {

    public static void main(String[] args) throws Exception {
        runBundled(appDir().resolve("game"));
    }

    static void runBundled(Path game) throws Exception {
        game = game.toAbsolutePath().normalize();
        Path manifest = game.resolve("entry.txt");
        Path entry = game.resolve(Files.exists(manifest) ? Files.readString(manifest) : "main.ow").normalize();
        if (!entry.startsWith(game)) throw new IllegalArgumentException("Entry is outside bundled game: " + entry);
        if (!Files.exists(entry)) {
            System.err.println("Bundled game not found at: " + entry);
            System.exit(1);
        }
        // Media and native UI builtins explicitly resolve relative paths against user.dir.
        System.setProperty("user.dir", game.toString());
        new Interpreter(System.out).run(Loader.load(entry));
    }

    private static Path appDir() {
        try {
            Path loc = Path.of(GameMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isRegularFile(loc) ? loc.getParent() : loc;
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath();
        }
    }
}
