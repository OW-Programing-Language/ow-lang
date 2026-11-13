package dev.oreo;

import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class ModuleImportBoundaryTest {
    private Path base() throws IOException {
        return Files.createTempDirectory("ow-modules-");
    }

    private Path write(Path base, String relative, String source) throws IOException {
        Path file = base.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    @Test public void compositionRootMayImportInfrastructure() throws IOException {
        Path root = base();
        write(root, "inventory/api/InventoryApi.ow", """
                module_type INVENTORY:
                  fn get() -> int
                """);
        write(root, "inventory/infra/Inventory.ow", """
                import "../api/InventoryApi.ow"
                module Warehouse: INVENTORY
                  fn get() -> int:
                    return: 5
                """);
        Path entry = write(root, "main.ow", """
                import "inventory/infra/Inventory.ow"
                main:
                  print: Warehouse.get()
                """);
        assertEquals(1, Loader.load(entry).modules.size());
    }

    @Test public void anotherModuleMayImportApiButNotInfra() throws IOException {
        Path root = base();
        write(root, "inventory/api/InventoryApi.ow", """
                module_type INVENTORY:
                  fn get() -> int
                """);
        write(root, "inventory/infra/Inventory.ow", """
                import "../api/InventoryApi.ow"
                module Warehouse: INVENTORY
                  fn get() -> int:
                    return: 5
                """);
        write(root, "orders/infra/Orders.ow", """
                import "../../inventory/api/InventoryApi.ow"
                """);
        Path entry = write(root, "main.ow", """
                import "orders/infra/Orders.ow"
                import "inventory/infra/Inventory.ow"
                main:
                  print: Warehouse.get()
                """);
        assertEquals(1, Loader.load(entry).modules.size());
    }

    @Test public void importingAnotherModulesInfraIsRejectedWithLine() throws IOException {
        Path root = base();
        write(root, "inventory/infra/Inventory.ow", """
                module_type API:
                  fn get() -> int
                """);
        write(root, "orders/infra/Orders.ow", """
                import "../../inventory/infra/Inventory.ow"
                """);
        Path entry = write(root, "main.ow", """
                import "orders/infra/Orders.ow"
                main:
                  print: 1
                """);
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(entry));
        assertTrue(error.getMessage(), error.getMessage().contains("Orders.ow:1:"));
        assertTrue(error.getMessage().contains("module boundary violation"));
    }

    @Test public void publicApiCannotImportOwnInfra() throws IOException {
        Path root = base();
        write(root, "inventory/infra/Inventory.ow", """
                module_type API:
                  fn get() -> int
                """);
        write(root, "inventory/api/Public.ow", """
                import "../infra/Inventory.ow"
                """);
        Path entry = write(root, "main.ow", """
                import "inventory/api/Public.ow"
                main:
                  print: 1
                """);
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(entry));
        assertTrue(error.getMessage().contains("module boundary violation"));
    }

    @Test public void importCycleIsDetected() throws IOException {
        Path root = base();
        write(root, "a.ow", "import \"b.ow\"\n");
        write(root, "b.ow", "import \"a.ow\"\n");
        Path entry = write(root, "main.ow", "import \"a.ow\"\nmain:\n  print: 1\n");
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(entry));
        assertTrue(error.getMessage().contains("Import cycle"));
        assertTrue(error.getMessage().contains("a.ow"));
        assertTrue(error.getMessage().contains("b.ow"));
    }

    @Test public void sharedDependencyIsNotMistakenForCycle() throws IOException {
        Path root = base();
        write(root, "shared.ow", "fn shared():\n  return: 5\n");
        write(root, "a.ow", "import \"shared.ow\"\n");
        write(root, "b.ow", "import \"shared.ow\"\n");
        Path entry = write(root, "main.ow", """
                import "a.ow"
                import "b.ow"
                main:
                  print: shared()
                """);
        assertEquals(1, Loader.load(entry).functions.size());
    }

    @Test public void missingImportErrorIncludesSourceLine() throws IOException {
        Path root = base();
        Path entry = write(root, "main.ow", """
                import "absent.ow"
                main:
                  print: 1
                """);
        RuntimeException error = assertThrows(RuntimeException.class, () -> Loader.load(entry));
        assertTrue(error.getMessage().contains("main.ow:1:"));
        assertTrue(error.getMessage().contains("import not found"));
    }
}
