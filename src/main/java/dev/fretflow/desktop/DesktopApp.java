package dev.fretflow.desktop;

import dev.fretflow.ConversionService;
import dev.fretflow.Main;
import dev.fretflow.model.ConversionResult;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopApp {
    private static final long MAX_INPUT_BYTES = 8L * 1024 * 1024;
    private static final Color ACCENT = new Color(42, 98, 201);

    private final JFrame frame = new JFrame("FretFlow AI " + Main.VERSION);
    private final JTextField inputField = new JTextField();
    private final JComboBox<Choice> instrumentBox = new JComboBox<>(new Choice[] {
            new Choice("Electric guitar", "guitar"), new Choice("Bass guitar", "bass")
    });
    private final JComboBox<Choice> tuningBox = new JComboBox<>();
    private final JComboBox<Choice> styleBox = new JComboBox<>(new Choice[] {
            new Choice("Balanced", "balanced"), new Choice("Beginner", "beginner"),
            new Choice("Low position", "low-position"), new Choice("Compact", "compact")
    });
    private final JComboBox<Choice> providerBox = new JComboBox<>(new Choice[] {
            new Choice("None — local algorithm", "none"), new Choice("DeepSeek", "deepseek"),
            new Choice("OpenAI-compatible", "openai")
    });
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JTextField baseUrlField = new JTextField();
    private final JTextField modelField = new JTextField();
    private final JButton chooseButton = new JButton("Choose score…");
    private final JButton convertButton = new JButton("Generate playable TAB");
    private final JButton saveXmlButton = new JButton("Save annotated MusicXML");
    private final JButton saveTabButton = new JButton("Save ASCII TAB");
    private final JTextArea previewArea = new JTextArea();
    private final JTextArea summaryArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Choose a MusicXML score to begin.");

    private Path inputPath;
    private ConversionResult currentResult;

    private DesktopApp() {
        configureFrame();
        updateTunings();
        updateAiFields();
        clearResult();
    }

    public static void launch() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new DesktopApp().show());
    }

    private void show() {
        frame.setVisible(true);
    }

    private void configureFrame() {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 640));
        frame.setSize(1120, 760);
        frame.setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 18));
        root.add(header(), BorderLayout.NORTH);
        root.add(content(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        frame.setContentPane(root);
    }

    private JPanel header() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("FretFlow AI");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 25f));
        JLabel subtitle = new JLabel("Compile MusicXML into ergonomic guitar or bass TAB — entirely on this computer");
        subtitle.setForeground(new Color(85, 85, 85));
        panel.add(title);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitle);
        return panel;
    }

    private JSplitPane content() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, optionsPanel(), resultsPanel());
        split.setResizeWeight(0.34);
        split.setDividerLocation(360);
        split.setBorder(null);
        return split;
    }

    private JPanel optionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(215, 215, 215)),
                BorderFactory.createEmptyBorder(14, 14, 14, 14)));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(3, 0, 4, 0);
        constraints.gridy = -1;

        chooseButton.addActionListener(event -> chooseInput());
        inputField.setEditable(false);
        inputField.setToolTipText("Selected .musicxml, .xml, or .mxl score");

        addField(form, constraints, "Input score", inputField);
        constraints.gridy++;
        form.add(chooseButton, constraints);
        addField(form, constraints, "Instrument", instrumentBox);
        addField(form, constraints, "Tuning", tuningBox);
        addField(form, constraints, "Optimization", styleBox);
        addField(form, constraints, "AI refinement", providerBox);
        addField(form, constraints, "API key (never saved)", apiKeyField);
        addField(form, constraints, "Compatible API base URL (optional)", baseUrlField);
        addField(form, constraints, "Model (optional)", modelField);

        instrumentBox.addActionListener(event -> updateTunings());
        providerBox.addActionListener(event -> updateAiFields());
        convertButton.setBackground(ACCENT);
        convertButton.setForeground(Color.WHITE);
        convertButton.addActionListener(event -> convert());

        panel.add(form, BorderLayout.CENTER);
        panel.add(convertButton, BorderLayout.SOUTH);
        return panel;
    }

    private void addField(JPanel panel, GridBagConstraints constraints, String label, java.awt.Component field) {
        constraints.gridy++;
        JLabel fieldLabel = new JLabel(label);
        fieldLabel.setFont(fieldLabel.getFont().deriveFont(Font.BOLD, 12f));
        panel.add(fieldLabel, constraints);
        constraints.gridy++;
        panel.add(field, constraints);
    }

    private JPanel resultsPanel() {
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        previewArea.setMargin(new Insets(10, 10, 10, 10));

        summaryArea.setEditable(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setRows(6);
        summaryArea.setMargin(new Insets(8, 8, 8, 8));
        summaryArea.setBackground(new Color(247, 247, 247));

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        JLabel label = new JLabel("TAB preview");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(label, BorderLayout.NORTH);
        panel.add(new JScrollPane(previewArea), BorderLayout.CENTER);
        panel.add(new JScrollPane(summaryArea), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel footer() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        saveXmlButton.addActionListener(event -> saveXml());
        saveTabButton.addActionListener(event -> saveTab());
        actions.add(saveTabButton);
        actions.add(saveXmlButton);
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.EAST);
        return panel;
    }

    private void chooseInput() {
        JFileChooser chooser = new JFileChooser(inputPath == null ? null : inputPath.toFile());
        chooser.setDialogTitle("Choose a MusicXML score");
        chooser.setFileFilter(new FileNameExtensionFilter("MusicXML scores", "musicxml", "xml", "mxl"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        inputPath = chooser.getSelectedFile().toPath();
        inputField.setText(inputPath.toAbsolutePath().toString());
        clearResult();
        statusLabel.setText("Ready to convert " + inputPath.getFileName());
    }

    private void convert() {
        if (inputPath == null) {
            JOptionPane.showMessageDialog(frame, "Choose a MusicXML score first.", "No input score", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!selectedValue(providerBox).equals("none") && apiKeyField.getPassword().length == 0) {
            JOptionPane.showMessageDialog(frame, "Enter an API key or select the local algorithm.",
                    "AI key required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        convertButton.setEnabled(false);
        chooseButton.setEnabled(false);
        instrumentBox.setEnabled(false);
        tuningBox.setEnabled(false);
        styleBox.setEnabled(false);
        providerBox.setEnabled(false);
        apiKeyField.setEnabled(false);
        baseUrlField.setEnabled(false);
        modelField.setEnabled(false);
        saveXmlButton.setEnabled(false);
        saveTabButton.setEnabled(false);
        statusLabel.setText("Converting…");
        char[] password = apiKeyField.getPassword();
        String apiKey = new String(password);
        java.util.Arrays.fill(password, '\0');
        var options = new ConversionService.RequestOptions(
                selectedValue(instrumentBox), selectedValue(tuningBox), selectedValue(styleBox),
                selectedValue(providerBox), apiKey, baseUrlField.getText().trim(), modelField.getText().trim());
        Path sourcePath = inputPath;

        new SwingWorker<ConversionResult, Void>() {
            @Override
            protected ConversionResult doInBackground() throws Exception {
                if (!Files.isRegularFile(sourcePath)) throw new IllegalArgumentException("The input score no longer exists.");
                if (Files.size(sourcePath) > MAX_INPUT_BYTES) throw new IllegalArgumentException("The input score is larger than 8 MB.");
                return new ConversionService().convert(Files.readAllBytes(sourcePath), options);
            }

            @Override
            protected void done() {
                convertButton.setEnabled(true);
                chooseButton.setEnabled(true);
                instrumentBox.setEnabled(true);
                tuningBox.setEnabled(true);
                styleBox.setEnabled(true);
                providerBox.setEnabled(true);
                updateAiFields();
                try {
                    setResult(get());
                } catch (Exception error) {
                    clearResult();
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    statusLabel.setText("Conversion failed.");
                    JOptionPane.showMessageDialog(frame, safeMessage(cause), "Conversion failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void setResult(ConversionResult result) {
        currentResult = result;
        previewArea.setText(result.asciiTab());
        previewArea.setCaretPosition(0);
        int noteCount = result.score().events().stream().mapToInt(event -> event.notes().size()).sum();
        var summary = new StringBuilder();
        summary.append(result.score().title()).append('\n')
                .append(result.score().partName()).append(" · ").append(result.instrument().name()).append('\n')
                .append(result.score().events().size()).append(" events · ").append(noteCount).append(" notes · ")
                .append(result.aiStatus());
        for (String warning : result.warnings()) summary.append("\nWarning: ").append(warning);
        summaryArea.setText(summary.toString());
        summaryArea.setCaretPosition(0);
        saveXmlButton.setEnabled(true);
        saveTabButton.setEnabled(true);
        statusLabel.setText("TAB generated successfully. Save either output when ready.");
    }

    private void clearResult() {
        currentResult = null;
        saveXmlButton.setEnabled(false);
        saveTabButton.setEnabled(false);
        previewArea.setText("Your generated TAB will appear here.");
        summaryArea.setText("");
    }

    private void saveXml() {
        if (currentResult == null) return;
        Path target = chooseOutput("Save annotated MusicXML", defaultOutput("-tab.musicxml"),
                new FileNameExtensionFilter("MusicXML score", "musicxml", "xml"));
        if (target == null) return;
        try {
            Files.write(target, currentResult.annotatedMusicXml());
            statusLabel.setText("Saved " + target.toAbsolutePath());
        } catch (Exception error) {
            showSaveError(error);
        }
    }

    private void saveTab() {
        if (currentResult == null) return;
        Path target = chooseOutput("Save ASCII TAB", defaultOutput("-tab.txt"),
                new FileNameExtensionFilter("Text file", "txt"));
        if (target == null) return;
        try {
            Files.writeString(target, currentResult.asciiTab(), StandardCharsets.UTF_8);
            statusLabel.setText("Saved " + target.toAbsolutePath());
        } catch (Exception error) {
            showSaveError(error);
        }
    }

    private Path chooseOutput(String title, Path suggested, FileNameExtensionFilter filter) {
        JFileChooser chooser = new JFileChooser(suggested.toAbsolutePath().getParent().toFile());
        chooser.setDialogTitle(title);
        chooser.setSelectedFile(suggested.toFile());
        chooser.setFileFilter(filter);
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return null;
        Path selected = chooser.getSelectedFile().toPath();
        if (inputPath.toAbsolutePath().normalize().equals(selected.toAbsolutePath().normalize())) {
            JOptionPane.showMessageDialog(frame, "Choose a different path so the original score is not overwritten.",
                    "Cannot overwrite input", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        if (Files.exists(selected)) {
            int choice = JOptionPane.showConfirmDialog(frame, "Replace " + selected.getFileName() + "?",
                    "File already exists", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return null;
        }
        return selected;
    }

    private Path defaultOutput(String suffix) {
        String fileName = inputPath.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
        return inputPath.toAbsolutePath().getParent().resolve(baseName + suffix);
    }

    private void updateTunings() {
        String instrument = selectedValue(instrumentBox);
        Choice[] choices = instrument.equals("bass")
                ? new Choice[] { new Choice("Standard (E A D G)", "standard"),
                    new Choice("Drop D (D A D G)", "drop-d"), new Choice("5-string (B E A D G)", "five-string") }
                : new Choice[] { new Choice("Standard (E A D G B E)", "standard"),
                    new Choice("Drop D (D A D G B E)", "drop-d"), new Choice("DADGAD", "dadgad") };
        tuningBox.setModel(new DefaultComboBoxModel<>(choices));
    }

    private void updateAiFields() {
        boolean enabled = !selectedValue(providerBox).equals("none");
        apiKeyField.setEnabled(enabled);
        baseUrlField.setEnabled(enabled);
        modelField.setEnabled(enabled);
    }

    private String selectedValue(JComboBox<Choice> box) {
        Choice selected = (Choice) box.getSelectedItem();
        return selected == null ? "" : selected.value();
    }

    private void showSaveError(Exception error) {
        JOptionPane.showMessageDialog(frame, safeMessage(error), "Could not save file", JOptionPane.ERROR_MESSAGE);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private record Choice(String label, String value) {
        @Override public String toString() { return label; }
    }
}
