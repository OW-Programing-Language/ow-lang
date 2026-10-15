package dev.oreo;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Ide extends JFrame {

    private static final Set<String> KEYWORDS = Set.of(
            "main", "set", "print", "if", "else", "while", "fn", "return", "call", "true", "false",
            "switch", "case", "default", "enum", "interface", "class", "field", "new", "this",
            "private", "public"
    );

    private static final String STARTER_OW = "main:\n  print: \"hello, ow!\"\n";

    private File projectRoot;
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("(no folder open)");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final JTree projectTree = new JTree(treeModel);
    private final JTabbedPane editorTabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JTextArea console = new JTextArea();
    private final JLabel statusBar = new JLabel(" Ready");
    private final List<EditorTab> tabs = new ArrayList<>();

    private final SimpleAttributeSet sDefault = new SimpleAttributeSet();
    private final SimpleAttributeSet sKeyword = new SimpleAttributeSet();
    private final SimpleAttributeSet sString  = new SimpleAttributeSet();
    private final SimpleAttributeSet sNumber  = new SimpleAttributeSet();
    private final SimpleAttributeSet sComment = new SimpleAttributeSet();

    private static class EditorTab {
        final JTextPane editor = new JTextPane();
        JScrollPane scroll;
        File file;
        boolean dirty;
        boolean highlighting;
    }

    public Ide() {
        super("Ow IDE");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 820);
        setLocationRelativeTo(null);

        configureStyles();
        configureConsole();
        configureProjectTree();

        JScrollPane treeScroll = new JScrollPane(projectTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Project"));

        JScrollPane consoleScroll = new JScrollPane(console);
        consoleScroll.setBorder(BorderFactory.createTitledBorder("Output"));

        JSplitPane vSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorTabs, consoleScroll);
        vSplit.setResizeWeight(0.72);
        vSplit.setDividerSize(6);

        JSplitPane hSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, vSplit);
        hSplit.setDividerLocation(240);
        hSplit.setDividerSize(6);

        setLayout(new BorderLayout());
        setJMenuBar(buildMenuBar());
        add(buildToolbar(), BorderLayout.NORTH);
        add(hSplit, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        File defaultFolder = new File("src/main/java/dev/oreo");
        if (defaultFolder.isDirectory()) {
            openProject(defaultFolder);
        } else {
            newUntitledTab();
        }
        setStatus("Welcome — Open a folder, create a new .ow file, or just hit Run.");
    }

    // ------------------------------------------------------------------
    // setup
    // ------------------------------------------------------------------

    private void configureStyles() {
        StyleConstants.setForeground(sDefault, new Color(40, 40, 40));
        StyleConstants.setForeground(sKeyword, new Color(0, 90, 200));
        StyleConstants.setBold(sKeyword, true);
        StyleConstants.setForeground(sString, new Color(20, 130, 60));
        StyleConstants.setForeground(sNumber, new Color(150, 30, 30));
        StyleConstants.setForeground(sComment, new Color(120, 120, 120));
        StyleConstants.setItalic(sComment, true);
    }

    private void configureConsole() {
        console.setEditable(false);
        console.setFont(new Font("Consolas", Font.PLAIN, 13));
        console.setBackground(new Color(30, 30, 30));
        console.setForeground(new Color(220, 220, 220));
        console.setMargin(new Insets(6, 8, 6, 8));
    }

    private void configureProjectTree() {
        projectTree.setRootVisible(true);
        projectTree.setShowsRootHandles(true);
        projectTree.setCellRenderer(new FileTreeRenderer());
        projectTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybePopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybePopup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    File f = selectedFile();
                    if (f != null && f.isFile()) openFileInTab(f);
                }
            }
            private void maybePopup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = projectTree.getRowForLocation(e.getX(), e.getY());
                if (row >= 0) projectTree.setSelectionRow(row);
                buildTreePopup().show(projectTree, e.getX(), e.getY());
            }
        });
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("New File…",       e -> newFileInProject(null)));
        file.add(menuItem("New Folder…",     e -> newFolderInProject(null)));
        file.add(menuItem("Open Folder…",    e -> doOpenFolder()));
        file.add(menuItem("Open File…",      e -> doOpenFile()));
        file.addSeparator();
        file.add(menuItem("Save",            e -> saveCurrent(false)));
        file.add(menuItem("Save As…",        e -> saveCurrent(true)));
        file.add(menuItem("Close Tab",       e -> closeCurrentTab()));
        file.addSeparator();
        file.add(menuItem("Exit",            e -> dispatchEvent(new java.awt.event.WindowEvent(this, java.awt.event.WindowEvent.WINDOW_CLOSING))));
        bar.add(file);

        JMenu run = new JMenu("Run");
        run.add(menuItem("Run ▶  (interpret)", e -> doRun()));
        run.add(menuItem("Run UI ▶  (web)", e -> doRunUi("web")));
        run.add(menuItem("Run UI ▶  (native window)", e -> doRunUi("native")));
        run.add(menuItem("Stop UI", e -> stopUi()));
        run.add(menuItem("Build .exe (LLVM/clang)", e -> doBuild()));
        run.add(menuItem("Clear Output", e -> console.setText("")));
        bar.add(run);

        JMenu git = new JMenu("Git");
        git.add(menuItem("Init Repo Here",          e -> gitInit()));
        git.add(menuItem("Clone From URL…",         e -> gitClone()));
        git.addSeparator();
        git.add(menuItem("Status",                  e -> gitRunSeq(new String[]{"status"})));
        git.add(menuItem("Pull",                    e -> gitRunSeq(new String[]{"pull"})));
        git.add(menuItem("Commit All…",             e -> gitCommit()));
        git.add(menuItem("Push",                    e -> gitPush()));
        git.addSeparator();
        git.add(menuItem("Set GitHub Remote…",      e -> gitSetRemote()));
        git.add(menuItem("Show Remotes",            e -> gitRunSeq(new String[]{"remote", "-v"})));
        git.add(menuItem("Configure Author…",       e -> gitConfigUser()));
        bar.add(git);
        bar.add(buildHelpMenu());
        return bar;
    }

    private JMenu buildHelpMenu() {
        JMenu help = new JMenu("Help");
        help.add(menuItem("Install LLVM (for Build .exe)…", e -> installLlvm()));
        help.add(menuItem("About Ow", e -> JOptionPane.showMessageDialog(this,
                "Ow IDE\n\nA tiny YAML-shaped programming language with an LLVM/clang backend.\n" +
                "Run ▶ uses the in-process interpreter (no extra tools).\n" +
                "Build .exe needs LLVM/clang installed (see Help → Install LLVM).",
                "About", JOptionPane.INFORMATION_MESSAGE)));
        return help;
    }

    private void installLlvm() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Build .exe needs LLVM/clang installed.\n\n" +
                        "This will download the latest LLVM Windows installer (~370 MB)\n" +
                        "and launch it. Follow the installer prompts and tick\n" +
                        "\"Add LLVM to the system PATH\" when offered.\n\n" +
                        "Continue?",
                "Install LLVM",
                JOptionPane.YES_NO_OPTION);
        if (choice != JOptionPane.YES_OPTION) return;

        console.setText("");
        appendConsole("[llvm] Looking up latest release on github.com/llvm/llvm-project ...\n");
        setStatus("Downloading LLVM installer...");

        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                try {
                    String url = findLatestLlvmWin64Url();
                    if (url == null) {
                        publish("[llvm] Could not find LLVM-*-win64.exe in latest release.\n");
                        publish("[hint] Open https://github.com/llvm/llvm-project/releases/latest manually.\n");
                        return null;
                    }
                    publish("[llvm] URL: " + url + "\n");

                    java.io.File tmp = java.io.File.createTempFile("LLVM-installer-", ".exe");
                    tmp.deleteOnExit();
                    long total = downloadTo(url, tmp, this::publish);
                    publish(String.format("[llvm] Downloaded %.1f MB → %s%n",
                            total / 1_048_576.0, tmp.getAbsolutePath()));

                    publish("[llvm] Launching installer (interactive — follow its prompts)...\n");
                    new ProcessBuilder(tmp.getAbsolutePath()).start();
                    publish("[llvm] After install completes, restart Ow IDE.\n");
                } catch (Exception ex) {
                    publish("[error] " + ex.getMessage() + "\n");
                    publish("[hint] Manual install: https://github.com/llvm/llvm-project/releases/latest\n");
                }
                return null;
            }
            @Override protected void process(java.util.List<String> chunks) {
                for (String c : chunks) appendConsole(c);
            }
            @Override protected void done() { setStatus("LLVM installer launched"); }
        }.execute();
    }

    private static String findLatestLlvmWin64Url() throws java.io.IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("https://api.github.com/repos/llvm/llvm-project/releases/latest")
                        .openConnection();
        conn.setRequestProperty("User-Agent", "OwIDE");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\"browser_download_url\":\\s*\"(https://[^\"]*LLVM-[0-9.]+-win64\\.exe)\"")
                .matcher(sb);
        return m.find() ? m.group(1) : null;
    }

    private static long downloadTo(String url, java.io.File dest,
                                   java.util.function.Consumer<String> progress) throws java.io.IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "OwIDE");
        conn.setInstanceFollowRedirects(true);
        long total = conn.getContentLengthLong();
        long downloaded = 0;
        long lastReport = 0;
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                downloaded += n;
                if (downloaded - lastReport > 10_000_000L) {
                    if (total > 0) {
                        progress.accept(String.format("[llvm] %.1f / %.1f MB (%.0f%%)%n",
                                downloaded / 1_048_576.0, total / 1_048_576.0,
                                100.0 * downloaded / total));
                    } else {
                        progress.accept(String.format("[llvm] %.1f MB%n", downloaded / 1_048_576.0));
                    }
                    lastReport = downloaded;
                }
            }
        }
        return downloaded;
    }

    private JMenuItem menuItem(String label, ActionListener a) {
        JMenuItem m = new JMenuItem(label);
        m.addActionListener(a);
        return m;
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(button("New File",   e -> newFileInProject(null)));
        bar.add(button("Open Folder",e -> doOpenFolder()));
        bar.add(button("Save",       e -> saveCurrent(false)));
        bar.addSeparator();
        bar.add(button("Run ▶",      e -> doRun()));
        bar.add(button("UI Web ▶",    e -> doRunUi("web")));
        bar.add(button("UI Native ▶", e -> doRunUi("native")));
        bar.add(button("Stop UI",     e -> stopUi()));
        bar.add(button("Build .exe", e -> doBuild()));
        bar.addSeparator();
        bar.add(button("Git Status", e -> gitRunSeq(new String[]{"status"})));
        bar.add(button("Commit",     e -> gitCommit()));
        bar.add(button("Push",       e -> gitPush()));
        return bar;
    }

    private JButton button(String label, ActionListener listener) {
        JButton b = new JButton(label);
        b.setFocusPainted(false);
        b.addActionListener(listener);
        return b;
    }

    // ------------------------------------------------------------------
    // tabs
    // ------------------------------------------------------------------

    private EditorTab currentTab() {
        int i = editorTabs.getSelectedIndex();
        if (i < 0 || i >= tabs.size()) return null;
        return tabs.get(i);
    }

    private EditorTab newUntitledTab() {
        EditorTab tab = createTab(null, STARTER_OW);
        addTabComponent(tab, "untitled.ow");
        editorTabs.setSelectedIndex(tabs.indexOf(tab));
        return tab;
    }

    private EditorTab createTab(File file, String content) {
        EditorTab tab = new EditorTab();
        tab.file = file;
        tab.editor.setFont(new Font("Consolas", Font.PLAIN, 15));
        tab.editor.setMargin(new Insets(6, 8, 6, 8));
        tab.editor.setText(content);
        tab.editor.setCaretPosition(0);

        tab.editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { onDocChange(tab); }
            public void removeUpdate(DocumentEvent e)  { onDocChange(tab); }
            public void changedUpdate(DocumentEvent e) {}
        });

        tab.scroll = new JScrollPane(tab.editor);
        tab.scroll.setRowHeaderView(new LineNumberGutter(tab.editor));
        tabs.add(tab);
        applyHighlight(tab);
        return tab;
    }

    private void addTabComponent(EditorTab tab, String displayName) {
        editorTabs.addTab(displayName, tab.scroll);
        int idx = editorTabs.indexOfComponent(tab.scroll);
        editorTabs.setTabComponentAt(idx, new TabHeader(tab, this));
    }

    private void onDocChange(EditorTab tab) {
        if (!tab.dirty) {
            tab.dirty = true;
            updateTabTitle(tab);
        }
        SwingUtilities.invokeLater(() -> applyHighlight(tab));
    }

    private void updateTabTitle(EditorTab tab) {
        int idx = editorTabs.indexOfComponent(tab.scroll);
        if (idx < 0) return;
        Component header = editorTabs.getTabComponentAt(idx);
        if (header instanceof TabHeader th) th.refresh();
    }

    String tabDisplayName(EditorTab tab) {
        String base = tab.file != null ? tab.file.getName() : "untitled.ow";
        return tab.dirty ? base + " *" : base;
    }

    void closeTab(EditorTab tab) {
        if (tab.dirty) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Save changes to " + tabDisplayName(tab) + "?",
                    "Close tab",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) return;
            if (choice == JOptionPane.YES_OPTION) {
                if (!saveTab(tab, false)) return;
            }
        }
        int idx = editorTabs.indexOfComponent(tab.scroll);
        if (idx >= 0) editorTabs.removeTabAt(idx);
        tabs.remove(tab);
        if (tabs.isEmpty()) newUntitledTab();
    }

    private void closeCurrentTab() {
        EditorTab t = currentTab();
        if (t != null) closeTab(t);
    }

    private void openFileInTab(File f) {
        for (EditorTab t : tabs) {
            if (t.file != null && t.file.equals(f)) {
                editorTabs.setSelectedIndex(tabs.indexOf(t));
                return;
            }
        }
        try {
            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            EditorTab tab = createTab(f, content);
            addTabComponent(tab, f.getName());
            editorTabs.setSelectedIndex(tabs.indexOf(tab));
            setStatus("Opened " + f.getAbsolutePath());
        } catch (IOException ex) {
            error("Could not open file: " + ex.getMessage());
        }
    }

    private boolean saveTab(EditorTab tab, boolean forceChooser) {
        File target = tab.file;
        if (target == null || forceChooser) {
            JFileChooser fc = new JFileChooser();
            if (projectRoot != null) fc.setCurrentDirectory(projectRoot);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ow source (*.ow)", "ow"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            target = fc.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".ow")) {
                target = new File(target.getAbsolutePath() + ".ow");
            }
        }
        try {
            Files.writeString(target.toPath(), tab.editor.getText(), StandardCharsets.UTF_8);
            tab.file = target;
            tab.dirty = false;
            updateTabTitle(tab);
            setStatus("Saved " + target.getAbsolutePath());
            refreshTree();
            return true;
        } catch (IOException ex) {
            error("Could not save: " + ex.getMessage());
            return false;
        }
    }

    private void saveCurrent(boolean forceChooser) {
        EditorTab t = currentTab();
        if (t != null) saveTab(t, forceChooser);
    }

    // ------------------------------------------------------------------
    // project tree
    // ------------------------------------------------------------------

    private void doOpenFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openProject(fc.getSelectedFile());
    }

    private void doOpenFile() {
        JFileChooser fc = new JFileChooser();
        if (projectRoot != null) fc.setCurrentDirectory(projectRoot);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Ow source (*.ow)", "ow"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openFileInTab(fc.getSelectedFile());
    }

    private void openProject(File dir) {
        projectRoot = dir;
        setTitle("Ow IDE — " + dir.getAbsolutePath());
        refreshTree();
        setStatus("Opened folder: " + dir.getAbsolutePath());
    }

    private void refreshTree() {
        treeRoot.removeAllChildren();
        if (projectRoot != null) {
            treeRoot.setUserObject(projectRoot);
            populate(treeRoot, projectRoot);
        } else {
            treeRoot.setUserObject("(no folder open)");
        }
        treeModel.reload(treeRoot);
        for (int i = 0; i < projectTree.getRowCount(); i++) projectTree.expandRow(i);
    }

    private void populate(DefaultMutableTreeNode parent, File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File c : children) {
            if (c.getName().equals(".git")) continue;
            DefaultMutableTreeNode n = new DefaultMutableTreeNode(c);
            parent.add(n);
            if (c.isDirectory()) populate(n, c);
        }
    }

    private File selectedFile() {
        TreePath path = projectTree.getSelectionPath();
        if (path == null) return null;
        Object node = path.getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof File f) {
            return f;
        }
        return null;
    }

    private JPopupMenu buildTreePopup() {
        JPopupMenu m = new JPopupMenu();
        File sel = selectedFile();
        File targetDir = (sel == null ? projectRoot : (sel.isDirectory() ? sel : sel.getParentFile()));

        JMenuItem newFile = new JMenuItem("New .ow File…");
        newFile.addActionListener(e -> newFileInProject(targetDir));
        m.add(newFile);

        JMenuItem newFolder = new JMenuItem("New Folder…");
        newFolder.addActionListener(e -> newFolderInProject(targetDir));
        m.add(newFolder);

        if (sel != null && sel.isFile()) {
            m.addSeparator();
            JMenuItem open = new JMenuItem("Open");
            open.addActionListener(e -> openFileInTab(sel));
            m.add(open);

            JMenuItem rename = new JMenuItem("Rename…");
            rename.addActionListener(e -> renameFile(sel));
            m.add(rename);

            JMenuItem delete = new JMenuItem("Delete");
            delete.addActionListener(e -> deleteFile(sel));
            m.add(delete);
        } else if (sel != null && sel.isDirectory() && !sel.equals(projectRoot)) {
            m.addSeparator();
            JMenuItem rename = new JMenuItem("Rename…");
            rename.addActionListener(e -> renameFile(sel));
            m.add(rename);
            JMenuItem delete = new JMenuItem("Delete (recursive)");
            delete.addActionListener(e -> deleteFile(sel));
            m.add(delete);
        }

        m.addSeparator();
        JMenuItem refresh = new JMenuItem("Refresh");
        refresh.addActionListener(e -> refreshTree());
        m.add(refresh);
        return m;
    }

    private void newFileInProject(File parentDir) {
        if (parentDir == null) parentDir = projectRoot;
        if (parentDir == null) {
            JOptionPane.showMessageDialog(this,
                    "No folder open. Use File → Open Folder first, or use Save As to create a file.");
            EditorTab t = newUntitledTab();
            saveTab(t, true);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New .ow file name:", "newfile.ow");
        if (name == null || name.trim().isEmpty()) return;
        if (!name.toLowerCase().endsWith(".ow")) name = name + ".ow";
        File f = new File(parentDir, name);
        try {
            if (f.exists()) {
                int c = JOptionPane.showConfirmDialog(this, f.getName() + " exists. Overwrite?",
                        "Confirm", JOptionPane.OK_CANCEL_OPTION);
                if (c != JOptionPane.OK_OPTION) return;
            }
            Files.writeString(f.toPath(), STARTER_OW, StandardCharsets.UTF_8);
            refreshTree();
            openFileInTab(f);
        } catch (IOException ex) {
            error("Could not create file: " + ex.getMessage());
        }
    }

    private void newFolderInProject(File parentDir) {
        if (parentDir == null) parentDir = projectRoot;
        if (parentDir == null) { error("No folder open."); return; }
        String name = JOptionPane.showInputDialog(this, "New folder name:");
        if (name == null || name.trim().isEmpty()) return;
        File f = new File(parentDir, name);
        if (!f.mkdirs()) error("Could not create folder.");
        refreshTree();
    }

    private void renameFile(File f) {
        String name = JOptionPane.showInputDialog(this, "New name:", f.getName());
        if (name == null || name.trim().isEmpty() || name.equals(f.getName())) return;
        File target = new File(f.getParentFile(), name);
        if (!f.renameTo(target)) { error("Rename failed."); return; }
        for (EditorTab t : tabs) {
            if (t.file != null && t.file.equals(f)) {
                t.file = target;
                updateTabTitle(t);
            }
        }
        refreshTree();
    }

    private void deleteFile(File f) {
        int choice = JOptionPane.showConfirmDialog(this,
                "Delete " + f.getName() + "?", "Confirm", JOptionPane.OK_CANCEL_OPTION);
        if (choice != JOptionPane.OK_OPTION) return;
        if (!recursiveDelete(f)) { error("Delete failed."); return; }
        for (EditorTab t : new ArrayList<>(tabs)) {
            if (t.file != null && (t.file.equals(f) || isUnder(t.file, f))) {
                int idx = editorTabs.indexOfComponent(t.scroll);
                if (idx >= 0) editorTabs.removeTabAt(idx);
                tabs.remove(t);
            }
        }
        if (tabs.isEmpty()) newUntitledTab();
        refreshTree();
    }

    private static boolean isUnder(File child, File parent) {
        File p = child.getParentFile();
        while (p != null) {
            if (p.equals(parent)) return true;
            p = p.getParentFile();
        }
        return false;
    }

    private static boolean recursiveDelete(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!recursiveDelete(k)) return false;
        }
        return f.delete();
    }

    // ------------------------------------------------------------------
    // run / build
    // ------------------------------------------------------------------

    /** The interpreter backing the currently running UI, so the IDE can stop it. */
    private volatile Interpreter activeUiInterp;

    private void doRun() { runSource(false); }

    private void doRunUi(String mode) {
        stopUi(); // only one UI window at a time
        System.setProperty("ow.ui.mode", mode); // honoured by ui_run (plain programs)
        runSource(true);
    }

    private void stopUi() {
        Interpreter i = activeUiInterp;
        if (i != null) {
            i.stopUi();
            activeUiInterp = null;
            appendConsole("[run-ui] stopped.\n");
            setStatus("UI stopped");
        }
    }

    private void runSource(boolean isUi) {
        EditorTab tab = currentTab();
        if (tab == null) return;
        console.setText("");
        String name = (tab.file != null ? tab.file.getName() : "untitled");
        appendConsole(isUi ? "[run-ui] launching UI for " + name + "\n"
                           : "[run] interpreting " + name + "\n");
        String source = tab.editor.getText();
        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                try {
                    Lexer lexer = new Lexer(source);
                    List<Lexer.Token> tokens = lexer.lexAll();
                    Ast.Program program = new Parser(tokens).parseProgram();

                    PipedOutputStream pout = new PipedOutputStream();
                    PipedInputStream pin = new PipedInputStream(pout, 1 << 16);
                    PrintStream ps = new PrintStream(pout, true, StandardCharsets.UTF_8);

                    Interpreter interp = new Interpreter(ps, Ide.this::showInputDialog);
                    if (isUi) activeUiInterp = interp;

                    Thread t = new Thread(() -> interp.run(program), "ow-interp");
                    t.start();

                    BufferedReader br = new BufferedReader(new InputStreamReader(pin, StandardCharsets.UTF_8));
                    Thread reader = new Thread(() -> {
                        try {
                            String line;
                            while ((line = br.readLine()) != null) publish(line + "\n");
                        } catch (IOException ignored) {}
                    }, "ow-interp-out");
                    reader.start();
                    t.join();
                    ps.close();
                    reader.join();
                } catch (Exception ex) {
                    publish("[error] " + ex.getMessage() + "\n");
                }
                return null;
            }
            @Override protected void process(List<String> chunks) {
                for (String c : chunks) appendConsole(c);
            }
            @Override protected void done() {
                if (isUi) activeUiInterp = null;
                appendConsole(isUi ? "[run-ui] finished.\n" : "[run] finished.\n");
                setStatus(isUi ? "UI finished" : "Run finished");
            }
        }.execute();
    }

    private String showInputDialog(String prompt) {
        final String[] answer = new String[1];
        Runnable task = () -> answer[0] = JOptionPane.showInputDialog(
                Ide.this,
                prompt == null || prompt.isEmpty() ? "Input:" : prompt,
                "Ow Input",
                JOptionPane.QUESTION_MESSAGE
        );
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Input interrupted", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("Failed to show input dialog", e.getCause());
        }
        return answer[0] != null ? answer[0] : "";
    }

    private void doBuild() {
        EditorTab tab = currentTab();
        if (tab == null) return;
        if (tab.file == null) {
            int c = JOptionPane.showConfirmDialog(this,
                    "Save the file before building?", "Build", JOptionPane.OK_CANCEL_OPTION);
            if (c != JOptionPane.OK_OPTION) return;
            if (!saveTab(tab, true)) return;
        } else if (tab.dirty) {
            if (!saveTab(tab, false)) return;
        }
        File source = tab.file;
        File exe = new File(source.getAbsolutePath().replaceAll("\\.ow$", "") + ".exe");
        console.setText("");
        appendConsole("[build] " + source.getName() + " → " + exe.getName() + "\n");
        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                try {
                    new OwCompiler().compileToExe(source.toPath(), exe.toPath());
                    publish("[build] OK → " + exe.getAbsolutePath() + "\n");
                    publish("[run-exe] launching...\n");
                    ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) publish(line + "\n");
                    int code = p.waitFor();
                    publish("[run-exe] exit code " + code + "\n");
                } catch (Exception ex) {
                    publish("[error] " + ex.getMessage() + "\n");
                    publish("[hint] " + OwCompiler.clangSearchHint() + "\n");
                }
                return null;
            }
            @Override protected void process(List<String> chunks) {
                for (String c : chunks) appendConsole(c);
            }
            @Override protected void done() { setStatus("Build finished"); }
        }.execute();
    }

    // ------------------------------------------------------------------
    // git integration
    // ------------------------------------------------------------------

    private void gitInit() {
        if (projectRoot == null) {
            error("Open a folder first (File → Open Folder).");
            return;
        }
        gitRunSeq(new String[]{"init"},
                  new String[]{"branch", "-M", "main"});
    }

    private void gitClone() {
        String url = JOptionPane.showInputDialog(this, "Repository URL (HTTPS or SSH):");
        if (url == null || url.trim().isEmpty()) return;

        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose folder to clone into");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File parent = fc.getSelectedFile();

        console.setText("");
        appendConsole("[git] cloning into " + parent.getAbsolutePath() + "\n");
        new SwingWorker<Void, String>() {
            File cloned;
            @Override protected Void doInBackground() {
                try {
                    ProcessBuilder pb = new ProcessBuilder("git", "clone", url);
                    pb.directory(parent);
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) publish(line + "\n");
                    int code = p.waitFor();
                    publish("[git] exit " + code + "\n");
                    if (code == 0) {
                        String repoName = url.replaceAll("\\.git$", "");
                        repoName = repoName.substring(repoName.lastIndexOf('/') + 1);
                        cloned = new File(parent, repoName);
                    }
                } catch (Exception ex) {
                    publish("[error] " + ex.getMessage() + "\n");
                    publish("[hint] Make sure 'git' is on PATH.\n");
                }
                return null;
            }
            @Override protected void process(List<String> chunks) {
                for (String c : chunks) appendConsole(c);
            }
            @Override protected void done() {
                if (cloned != null && cloned.isDirectory()) openProject(cloned);
            }
        }.execute();
    }

    private void gitCommit() {
        if (projectRoot == null) { error("Open a folder first."); return; }
        String msg = JOptionPane.showInputDialog(this, "Commit message:");
        if (msg == null || msg.trim().isEmpty()) return;
        gitRunSeq(new String[]{"add", "-A"},
                  new String[]{"commit", "-m", msg});
    }

    private void gitPush() {
        if (projectRoot == null) { error("Open a folder first."); return; }
        gitRunSeq(new String[]{"push", "-u", "origin", "HEAD"});
    }

    private void gitSetRemote() {
        if (projectRoot == null) { error("Open a folder first."); return; }
        String url = JOptionPane.showInputDialog(this,
                "GitHub URL (e.g. https://github.com/USER/REPO.git):");
        if (url == null || url.trim().isEmpty()) return;
        // Try add first; if it fails (already exists), set-url.
        gitRunSeq(new String[]{"remote", "remove", "origin"},
                  new String[]{"remote", "add", "origin", url});
    }

    private void gitConfigUser() {
        if (projectRoot == null) { error("Open a folder first."); return; }
        String name = JOptionPane.showInputDialog(this, "Author name (for commits):");
        if (name == null || name.trim().isEmpty()) return;
        String email = JOptionPane.showInputDialog(this, "Author email:");
        if (email == null || email.trim().isEmpty()) return;
        gitRunSeq(new String[]{"config", "user.name", name},
                  new String[]{"config", "user.email", email});
    }

    /** Runs one or more git commands sequentially in this project root. */
    private void gitRunSeq(String[]... commands) {
        if (projectRoot == null) {
            error("Open a folder first (File → Open Folder).");
            return;
        }
        console.setText("");
        new SwingWorker<Void, String>() {
            @Override protected Void doInBackground() {
                for (String[] cmd : commands) {
                    List<String> full = new ArrayList<>();
                    full.add("git");
                    full.addAll(Arrays.asList(cmd));
                    publish("[git] " + String.join(" ", full) + "\n");
                    try {
                        ProcessBuilder pb = new ProcessBuilder(full);
                        pb.directory(projectRoot);
                        pb.redirectErrorStream(true);
                        Process p = pb.start();
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) publish(line + "\n");
                        int code = p.waitFor();
                        publish("[git] exit " + code + "\n");
                    } catch (Exception ex) {
                        publish("[error] " + ex.getMessage() + "\n");
                        publish("[hint] Make sure 'git' is on PATH.\n");
                        return null;
                    }
                }
                return null;
            }
            @Override protected void process(List<String> chunks) {
                for (String c : chunks) appendConsole(c);
            }
            @Override protected void done() {
                refreshTree();
                setStatus("Git command finished");
            }
        }.execute();
    }

    // ------------------------------------------------------------------
    // syntax highlighting (per tab)
    // ------------------------------------------------------------------

    private void applyHighlight(EditorTab tab) {
        if (tab.highlighting) return;
        tab.highlighting = true;
        try {
            StyledDocument doc = tab.editor.getStyledDocument();
            String text = tab.editor.getText();
            doc.setCharacterAttributes(0, text.length(), sDefault, true);

            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (c == '#') {
                    int start = i;
                    while (i < n && text.charAt(i) != '\n') i++;
                    doc.setCharacterAttributes(start, i - start, sComment, true);
                } else if (c == '"') {
                    int start = i++;
                    while (i < n && text.charAt(i) != '"' && text.charAt(i) != '\n') i++;
                    if (i < n && text.charAt(i) == '"') i++;
                    doc.setCharacterAttributes(start, i - start, sString, true);
                } else if (Character.isDigit(c)) {
                    int start = i;
                    while (i < n && Character.isDigit(text.charAt(i))) i++;
                    doc.setCharacterAttributes(start, i - start, sNumber, true);
                } else if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < n) {
                        char ch = text.charAt(i);
                        if (!(Character.isLetterOrDigit(ch) || ch == '_')) break;
                        i++;
                    }
                    String word = text.substring(start, i);
                    if (KEYWORDS.contains(word)) {
                        doc.setCharacterAttributes(start, i - start, sKeyword, true);
                    }
                } else {
                    i++;
                }
            }
        } finally {
            tab.highlighting = false;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void appendConsole(String s) {
        console.append(s);
        console.setCaretPosition(console.getDocument().getLength());
    }

    private void setStatus(String s) { statusBar.setText(" " + s); }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new Ide().setVisible(true);
        });
    }

    // ------------------------------------------------------------------
    // tab header with close button
    // ------------------------------------------------------------------

    private static class TabHeader extends JPanel {
        private final JLabel label = new JLabel();
        private final EditorTab tab;
        private final Ide ide;

        TabHeader(EditorTab tab, Ide ide) {
            super(new FlowLayout(FlowLayout.LEFT, 0, 0));
            this.tab = tab;
            this.ide = ide;
            setOpaque(false);
            label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
            JButton close = new JButton("×");
            close.setFont(close.getFont().deriveFont(Font.BOLD));
            close.setMargin(new Insets(0, 4, 0, 4));
            close.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            close.setContentAreaFilled(false);
            close.setFocusable(false);
            close.addActionListener(e -> ide.closeTab(tab));
            add(label);
            add(close);
            refresh();
        }

        void refresh() { label.setText(ide.tabDisplayName(tab)); }
    }

    // ------------------------------------------------------------------
    // gutter
    // ------------------------------------------------------------------

    static class LineNumberGutter extends JComponent {
        private final JTextPane host;

        LineNumberGutter(JTextPane host) {
            this.host = host;
            setFont(host.getFont());
            setForeground(new Color(150, 150, 150));
            setBackground(new Color(245, 245, 245));
            setPreferredSize(new Dimension(48, 1));
            host.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { repaint(); }
                public void removeUpdate(DocumentEvent e) { repaint(); }
                public void changedUpdate(DocumentEvent e) {}
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Rectangle clip = g.getClipBounds();
            g.setColor(getBackground());
            g.fillRect(clip.x, clip.y, clip.width, clip.height);

            FontMetrics fm = host.getFontMetrics(host.getFont());
            int lineHeight = fm.getHeight();
            int ascent = fm.getAscent();

            String text = host.getText();
            int totalLines = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') totalLines++;

            g.setFont(host.getFont());
            g.setColor(getForeground());
            int y0 = host.getInsets().top;
            for (int i = 0; i < totalLines; i++) {
                String num = Integer.toString(i + 1);
                int x = getWidth() - fm.stringWidth(num) - 6;
                g.drawString(num, x, y0 + i * lineHeight + ascent);
            }
            int needed = y0 + totalLines * lineHeight + 16;
            if (needed > getPreferredSize().height) {
                setPreferredSize(new Dimension(48, needed));
                revalidate();
            }
        }
    }

    // ------------------------------------------------------------------
    // tree renderer
    // ------------------------------------------------------------------

    private static class FileTreeRenderer extends DefaultTreeCellRenderer {
        private final Color folderColor = new Color(170, 120, 0);
        private final Color owColor = new Color(0, 90, 200);

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                      boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode n) {
                Object obj = n.getUserObject();
                if (obj instanceof File f) {
                    setText(f.getName().isEmpty() ? f.getAbsolutePath() : f.getName());
                    if (!sel) {
                        if (f.isDirectory()) setForeground(folderColor);
                        else if (f.getName().endsWith(".ow")) setForeground(owColor);
                        else setForeground(Color.DARK_GRAY);
                    }
                } else {
                    setText(String.valueOf(obj));
                }
            }
            return this;
        }
    }
}
