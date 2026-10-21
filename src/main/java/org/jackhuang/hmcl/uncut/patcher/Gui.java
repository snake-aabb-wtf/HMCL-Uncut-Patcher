package org.jackhuang.hmcl.uncut.patcher;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * 图形界面：选文件 → 点按钮 → 生成 -uncut 副本。只做外壳，真正的补丁逻辑全部复用 Patcher。
 */
final class Gui {
    private static final Color SUCCESS = new Color(0x1B7F3A);
    private static final Color FAILURE = new Color(0xB3261E);

    private final JFrame frame = new JFrame("HMCL Uncut Patcher " + Main.VERSION);
    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JButton pickInputButton = new JButton("选择…");
    private final JButton pickOutputButton = new JButton("另存为…");
    private final JButton patchButton = new JButton("开始打补丁");
    private final JButton checkButton = new JButton("仅检查");
    private final JButton openFolderButton = new JButton("打开所在文件夹");
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea logArea = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final Color statusForeground = status.getForeground();

    private boolean running;
    private boolean outputAuto = true;
    private boolean updatingOutput;
    private Path lastOutput;
    private Path lastDirectory;

    static void launch(Path preset) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 用默认外观继续
        }
        SwingUtilities.invokeLater(() -> new Gui().show(preset));
    }

    private void show(Path preset) {
        frame.setIconImage(appIcon());
        frame.setContentPane(buildContent());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(660, 500));
        frame.setSize(780, 600);
        frame.getRootPane().setDefaultButton(patchButton);
        frame.setLocationRelativeTo(null);
        appendLog("把 HMCL 程序（jar / exe / sh / deb）拖进窗口，或者点「选择…」。");
        appendLog("只读取你选的文件，在旁边生成 -uncut 副本，原文件不动。");
        frame.setVisible(true);
        if (preset != null) {
            setInput(preset);
        }
    }

    private static Image appIcon() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x1F6FEB));
            g.fillRoundRect(2, 2, 60, 60, 18, 18);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            FontMetrics metrics = g.getFontMetrics();
            String text = "U";
            int x = (64 - metrics.stringWidth(text)) / 2;
            int y = (64 - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(text, x, y);
        } finally {
            g.dispose();
        }
        return image;
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(14, 16, 12, 16));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildLog(), BorderLayout.CENTER);
        root.add(buildFooter(), BorderLayout.SOUTH);

        TransferHandler dropHandler = new FileDropHandler();
        root.setTransferHandler(dropHandler);
        logArea.setTransferHandler(dropHandler);
        return root;
    }

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));

        JPanel titles = new JPanel();
        titles.setLayout(new javax.swing.BoxLayout(titles, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = new JLabel("HMCL Uncut Patcher");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6f));
        JLabel subtitle = new JLabel("恢复官方 HMCL 中被删减的下载功能 · 只读取你选的文件，结果另存为新文件");
        subtitle.setForeground(muted());
        titles.add(title);
        titles.add(javax.swing.Box.createVerticalStrut(4));
        titles.add(subtitle);
        panel.add(titles, BorderLayout.NORTH);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;

        JLabel inputLabel = new JLabel("源文件");
        inputLabel.setLabelFor(inputField);
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 6, 10);
        form.add(inputLabel, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 6, 8);
        form.add(inputField, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(pickInputButton, c);

        JLabel outputLabel = new JLabel("输出到");
        outputLabel.setLabelFor(outputField);
        c.gridx = 0;
        c.gridy = 1;
        c.insets = new Insets(0, 0, 0, 10);
        form.add(outputLabel, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 8);
        form.add(outputField, c);

        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(0, 0, 0, 0);
        form.add(pickOutputButton, c);

        JLabel hint = new JLabel("支持 jar / exe / sh / deb，也可以把文件直接拖进窗口");
        hint.setForeground(muted());
        c.gridx = 1;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new Insets(8, 0, 0, 0);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        form.add(hint, c);

        panel.add(form, BorderLayout.CENTER);

        inputField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            void changed() {
                if (outputAuto) {
                    setOutputText(derivedOutputText());
                }
            }
        });
        outputField.getDocument().addDocumentListener(new SimpleDocumentListener() {
            @Override
            void changed() {
                if (!updatingOutput) {
                    outputAuto = outputField.getText().trim().equals(derivedOutputText());
                }
            }
        });

        pickInputButton.addActionListener(e -> chooseInput());
        pickOutputButton.addActionListener(e -> chooseOutput());
        return panel;
    }

    private JComponent buildLog() {
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setPreferredSize(new Dimension(700, 260));
        return scroll;
    }

    private JPanel buildFooter() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        progress.setIndeterminate(true);
        progress.setVisible(false);
        panel.add(progress, BorderLayout.NORTH);

        JPanel line = new JPanel(new BorderLayout(12, 0));
        line.add(status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        openFolderButton.setEnabled(false);
        buttons.add(openFolderButton);
        buttons.add(checkButton);
        buttons.add(patchButton);
        line.add(buttons, BorderLayout.EAST);
        panel.add(line, BorderLayout.CENTER);

        JLabel note = new JLabel("按下去之后：签名会失效，HMCL 会提示「非官方构建」，检查更新也会失败——这是预期行为。");
        note.setForeground(muted());
        panel.add(note, BorderLayout.SOUTH);

        patchButton.addActionListener(e -> start(false));
        checkButton.addActionListener(e -> start(true));
        openFolderButton.addActionListener(e -> openOutputFolder());
        return panel;
    }

    private void start(boolean checkOnly) {
        if (running) {
            return;
        }
        Path input = inputPath();
        if (input == null) {
            showError("请先选择要打补丁的 HMCL 文件。");
            return;
        }
        if (!Files.isRegularFile(input)) {
            showError("文件不存在：" + input);
            return;
        }

        Path output = null;
        if (!checkOnly) {
            output = resolvedOutputPath();
            if (output == null) {
                showError("请指定输出文件路径。");
                return;
            }
            if (samePath(input, output)) {
                showError("输出文件不能和源文件相同。");
                return;
            }
            if (Files.isDirectory(output)) {
                showError("输出路径是一个目录：" + output);
                return;
            }
            Path parent = output.toAbsolutePath().normalize().getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                showError("输出目录不存在：" + parent);
                return;
            }
            if (Files.exists(output)) {
                int answer = JOptionPane.showConfirmDialog(frame,
                        "输出文件已经存在，要覆盖吗？\n" + output,
                        "确认覆盖", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (answer != JOptionPane.YES_OPTION) {
                    return;
                }
            }
        }

        final Path target = output;
        final boolean onlyCheck = checkOnly;
        setRunning(true);
        appendLog(onlyCheck ? "开始检查…" : "开始打补丁…");

        new SwingWorker<Outcome, String>() {
            @Override
            protected Outcome doInBackground() throws Exception {
                byte[] data = Files.readAllBytes(input);
                publish("读取：" + input.getFileName() + "（" + String.format("%,d", data.length) + " 字节）");
                Patcher.Result result = Patcher.patch(data, line -> publish(line));
                publish("格式：" + Patcher.describe(result));
                if (result.version() != null) {
                    publish("版本：" + result.version());
                }
                publish("门禁：RegionUtils." + ClassPatcher.METHOD_NAME + ClassPatcher.METHOD_DESC
                        + " 已改为恒返回 false");
                if (onlyCheck) {
                    return new Outcome(null, data.length, 0, null);
                }
                Files.write(target, result.data());
                return new Outcome(target, data.length, result.data().length, Util.sha256(result.data()));
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    appendLog("  " + line);
                }
            }

            @Override
            protected void done() {
                setRunning(false);
                try {
                    Outcome outcome = get();
                    if (outcome.output() == null) {
                        appendLog("检查通过（未写出文件）。");
                        setStatus("检查通过，这个文件可以打补丁。", SUCCESS);
                        return;
                    }
                    lastOutput = outcome.output();
                    openFolderButton.setEnabled(true);
                    appendLog("输出：" + outcome.output());
                    appendLog("大小：" + String.format("%,d", outcome.outputSize()) + " 字节");
                    appendLog("SHA-256：" + outcome.sha256());
                    appendLog("完成，原文件没有被修改。");
                    setStatus("完成：" + outcome.output().getFileName(), SUCCESS);
                    showSuccess(outcome);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("操作被中断。");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    fail(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                }
            }

            private void fail(String message) {
                appendLog("× " + message);
                setStatus("失败：" + message, FAILURE);
                showError(message);
            }
        }.execute();
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 HMCL 程序文件");
        chooser.setFileFilter(hmclFilter());
        chooser.setAcceptAllFileFilterUsed(true);
        Path current = inputPath();
        if (current != null && current.toAbsolutePath().getParent() != null
                && Files.isDirectory(current.toAbsolutePath().getParent())) {
            chooser.setCurrentDirectory(current.toAbsolutePath().getParent().toFile());
            chooser.setSelectedFile(current.toFile());
        } else if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory.toFile());
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file.getParentFile() != null) {
                lastDirectory = file.getParentFile().toPath();
            }
            setInput(file.toPath());
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存补丁后的文件");
        chooser.setFileFilter(hmclFilter());
        chooser.setAcceptAllFileFilterUsed(true);

        Path candidate = resolvedOutputPath();
        Path input = inputPath();
        if (candidate == null && input != null) {
            candidate = Patcher.defaultOutput(input);
        }
        if (candidate != null) {
            Path parent = candidate.toAbsolutePath().normalize().getParent();
            if (parent != null && Files.isDirectory(parent)) {
                chooser.setCurrentDirectory(parent.toFile());
            }
            chooser.setSelectedFile(candidate.toFile());
        } else if (lastDirectory != null) {
            chooser.setCurrentDirectory(lastDirectory.toFile());
        }

        if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            setOutputText(chooser.getSelectedFile().toPath().toString());
            outputAuto = false;
        }
    }

    private void openOutputFolder() {
        if (lastOutput == null) {
            return;
        }
        Path directory = lastOutput.toAbsolutePath().normalize().getParent();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile());
            } else {
                JOptionPane.showMessageDialog(frame, "当前系统不支持打开文件夹，路径：\n" + directory,
                        "所在文件夹", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (IOException | UnsupportedOperationException e) {
            JOptionPane.showMessageDialog(frame, "无法打开文件夹：" + e.getMessage() + "\n" + directory,
                    "出错了", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showSuccess(Outcome outcome) {
        String message = "补丁完成\n\n"
                + "输出：" + outcome.output() + "\n"
                + "大小：" + String.format("%,d", outcome.outputSize()) + " 字节\n"
                + "SHA-256：" + outcome.sha256() + "\n\n"
                + "注意：签名已失效，HMCL 会提示「非官方构建」，检查更新会失败，这是预期行为。";
        Object[] options = {"打开所在文件夹", "关闭"};
        int choice = JOptionPane.showOptionDialog(frame, message, "补丁完成",
                JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[1]);
        if (choice == 0) {
            openOutputFolder();
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(frame, message, "出错了", JOptionPane.ERROR_MESSAGE);
    }

    private void setRunning(boolean value) {
        running = value;
        inputField.setEnabled(!value);
        outputField.setEnabled(!value);
        pickInputButton.setEnabled(!value);
        pickOutputButton.setEnabled(!value);
        patchButton.setEnabled(!value);
        checkButton.setEnabled(!value);
        progress.setVisible(value);
        frame.setCursor(value ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
        if (value) {
            setStatus("正在处理，请稍候…", null);
        }
    }

    private void setStatus(String text, Color color) {
        status.setText(text);
        status.setForeground(color != null ? color : statusForeground);
    }

    private void appendLog(String line) {
        logArea.append(line + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void setInput(Path path) {
        inputField.setText(path.toAbsolutePath().normalize().toString());
        inputField.setCaretPosition(0);
    }

    private void setOutputText(String text) {
        updatingOutput = true;
        try {
            outputField.setText(text);
            outputField.setCaretPosition(0);
        } finally {
            updatingOutput = false;
        }
    }

    private Path inputPath() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Path.of(text);
        } catch (Exception e) {
            return null;
        }
    }

    private Path resolvedOutputPath() {
        String text = outputField.getText().trim();
        if (!text.isEmpty()) {
            try {
                return Path.of(text);
            } catch (Exception e) {
                return null;
            }
        }
        Path input = inputPath();
        return input == null ? null : Patcher.defaultOutput(input);
    }

    private String derivedOutputText() {
        Path input = inputPath();
        return input == null ? "" : Patcher.defaultOutput(input).toString();
    }

    private static boolean samePath(Path a, Path b) {
        Path left = a.toAbsolutePath().normalize();
        Path right = b.toAbsolutePath().normalize();
        try {
            if (Files.exists(left) && Files.exists(right)) {
                return Files.isSameFile(left, right);
            }
        } catch (IOException ignored) {
            // 退回到字符串比较
        }
        String leftText = left.toString();
        String rightText = right.toString();
        return File.separatorChar == '\\' ? leftText.equalsIgnoreCase(rightText) : leftText.equals(rightText);
    }

    private static FileNameExtensionFilter hmclFilter() {
        return new FileNameExtensionFilter("HMCL 程序（jar / exe / sh / deb）", "jar", "exe", "sh", "deb");
    }

    private Color muted() {
        return UIManager.getColor("Label.disabledForeground") != null
                ? UIManager.getColor("Label.disabledForeground")
                : new Color(0x777777);
    }

    private record Outcome(Path output, int inputSize, int outputSize, String sha256) {
    }

    private abstract static class SimpleDocumentListener implements DocumentListener {
        abstract void changed();

        @Override
        public void insertUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            changed();
        }
    }

    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                if (files.isEmpty()) {
                    return false;
                }
                File file = files.get(0);
                if (!file.isFile()) {
                    return false;
                }
                setInput(file.toPath());
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
