package dev.oreo;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class PackagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private Path write(Path root, String name, String content) throws Exception {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    @Test public void selectedEntryAndProjectResourcesSurviveStaging() throws Exception {
        Path project = temp.newFolder("project with spaces").toPath();
        write(project, "src/main.ow", "main:\n  print: \"WRONG ENTRY\"\n");
        write(project, "src/lib/message.ow", "fn message():\n  return: \"GUI ENTRY\"\n");
        Path selected = write(project, "src/main_ui.ow", "import \"lib/message.ow\"\nmain:\n  print: message()\n  print: exists(\"assets/nested/image.png\")\n  print: exists(\"models/vosk-en/am/final.mdl\")\n");
        write(project, "assets/nested/image.png", "image bytes");
        write(project, "models/vosk-en/am/final.mdl", "model bytes");
        write(project, "save/progress.json", "private progress");
        Path game = temp.newFolder("staged").toPath();
        Packager.stageGame(selected, game);
        assertEquals("image bytes", Files.readString(game.resolve("assets/nested/image.png")));
        assertEquals("model bytes", Files.readString(game.resolve("models/vosk-en/am/final.mdl")));
        assertFalse(Files.exists(game.resolve("save")));
        assertTrue(Files.readString(game.resolve("main.ow")).contains("WRONG ENTRY"));
        String cwd = System.getProperty("user.dir");
        PrintStream stdout = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setProperty("user.dir", temp.newFolder("unrelated").toString());
            System.setOut(new PrintStream(output));
            GameMain.runBundled(game);
            assertEquals("GUI ENTRY\n1\n1", output.toString().trim().replace("\r\n", "\n"));
            assertEquals(game.toAbsolutePath().toString(), System.getProperty("user.dir"));
        } finally {
            System.setOut(stdout);
            System.setProperty("user.dir", cwd);
        }
    }

    @Test public void flatLayoutAndNoResourcesAreSupported() throws Exception {
        Path project = temp.newFolder("flat").toPath();
        Path entry = write(project, "hello.ow", "main:\n  print: \"hello\"\n");
        Path game = temp.newFolder("flat-stage").toPath();
        Packager.stageGame(entry, game);
        assertEquals("hello.ow", Files.readString(game.resolve("entry.txt")));
        assertFalse(Files.exists(game.resolve("assets")));
        write(project, "assets/sound.wav", "audio bytes");
        Packager.stageGame(entry, game);
        assertEquals("audio bytes", Files.readString(game.resolve("assets/sound.wav")));
    }
}
