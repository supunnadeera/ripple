package com.ripple.cellpose;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;
import ij.ImagePlus;
import ij.io.Opener;

/**
 * Standalone Cellpose Frontend (No Fiji/ImageJ Plugin Dependencies)
 * Runs Cellpose Backend models directly without requiring a separate server.
 */
public class CellposeFrontendUI {

    private static final int LEFT_PANEL_WIDTH = 360;
    private static final int LEFT_PANEL_MIN_WIDTH = 360;
    
    private JFrame frame;
    private JLabel imageLabel;
    private JLabel statusLabel;
    private BufferedImage originalImage;
    private BufferedImage displayImage;
    private BufferedImage maskImage;
    private ImagePlus imagePlus;
    
    // Display settings
    private double zoomFactor = 1.0;
    private boolean showMask = true;
    private float maskOpacity = 0.5f;
    
    // Backend paths
    private Path backendDir;
    private Path modelsDir;
    
    // UI Components
    private JComboBox<String> modelTypeCombo;
    private JComboBox<String> modelNameCombo;
    private JSpinner diameterSpinner;
    private JSpinner flowThresholdSpinner;
    private JSpinner cellprobThresholdSpinner;
    private JTextField channelsField;
    private JCheckBox useGpuCheckbox;
    private JCheckBox showMaskCheckbox;
    private JSlider opacitySlider;
    private JButton segmentButton;
    private JProgressBar progressBar;
    
    // Color map for mask visualization
    private Color[] colorMap;
    
    public CellposeFrontendUI(String backendUrl) {
        // Find backend directory
        this.backendDir = findBackendDirectory();
        if (this.backendDir != null) {
            this.modelsDir = backendDir.resolve("cellpose backend").resolve("models");
            System.out.println("[Cellpose] Backend directory found: " + backendDir);
            System.out.println("[Cellpose] Models directory: " + modelsDir);
        } else {
            System.err.println("[Cellpose] WARNING: Backend directory not found!");
            System.err.println("[Cellpose] Working directory: " + System.getProperty("user.dir"));
        }
        initializeColorMap();
    }
    
    /**
     * Initialize the UI.
     */
    public void initUI() {
        frame = new JFrame("Cellpose - Cell Segmentation");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1400, 900);
        frame.setLayout(new BorderLayout());
        
        // Create menu bar
        createMenuBar();
        
        // Create main split pane
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setDividerLocation(LEFT_PANEL_WIDTH);
        mainSplit.setResizeWeight(0.0);
        
        // Left: Control panel
        JPanel controlPanel = createControlPanel();
        JScrollPane controlScroll = new JScrollPane(controlPanel);
        controlScroll.setPreferredSize(new Dimension(LEFT_PANEL_WIDTH, 0));
        controlScroll.setMinimumSize(new Dimension(LEFT_PANEL_MIN_WIDTH, 0));
        controlScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        controlScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        mainSplit.setLeftComponent(controlScroll);
        
        // Right: Image display
        JPanel displayPanel = createDisplayPanel();
        mainSplit.setRightComponent(displayPanel);
        
        frame.add(mainSplit, BorderLayout.CENTER);
        
        // Status bar
        statusLabel = new JLabel(" Ready - Load an image to start segmentation");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusLabel.setFont(new Font("Arial", Font.PLAIN, 12));
        frame.add(statusLabel, BorderLayout.SOUTH);
        
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        
        // Load available models
        loadModels();
    }
    
    /**
     * Create menu bar.
     */
    private void createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // File menu
        JMenu fileMenu = new JMenu("File");
        JMenuItem openItem = new JMenuItem("Open Image...");
        openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        openItem.addActionListener(e -> openImage());
        fileMenu.add(openItem);
        
        fileMenu.addSeparator();
        
        JMenuItem exportMaskItem = new JMenuItem("Export Mask...");
        exportMaskItem.addActionListener(e -> exportMask());
        fileMenu.add(exportMaskItem);
        
        JMenuItem exportOverlayItem = new JMenuItem("Export Overlay...");
        exportOverlayItem.addActionListener(e -> exportOverlay());
        fileMenu.add(exportOverlayItem);
        
        fileMenu.addSeparator();
        
        JMenuItem closeItem = new JMenuItem("Close");
        closeItem.addActionListener(e -> frame.dispose());
        fileMenu.add(closeItem);
        
        // View menu
        JMenu viewMenu = new JMenu("View");
        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, InputEvent.CTRL_DOWN_MASK));
        zoomInItem.addActionListener(e -> zoomIn());
        viewMenu.add(zoomInItem);
        
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
        zoomOutItem.addActionListener(e -> zoomOut());
        viewMenu.add(zoomOutItem);
        
        JMenuItem zoomFitItem = new JMenuItem("Fit to Window");
        zoomFitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
        zoomFitItem.addActionListener(e -> zoomFit());
        viewMenu.add(zoomFitItem);
        
        menuBar.add(fileMenu);
        menuBar.add(viewMenu);
        
        frame.setJMenuBar(menuBar);
    }
    
    /**
     * Create control panel.
     */
    private JPanel createControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Model selection
        JPanel modelPanel = createSection("Model Selection");
        modelTypeCombo = new JComboBox<>(new String[]{"CellposeSAM","Cellpose3.1"});
        modelTypeCombo.addActionListener(e -> {
            updateChannelSelectionState();
            loadModels();
        });
        modelNameCombo = new JComboBox<>();
        
        addLabeledComponent(modelPanel, "Model Type:", modelTypeCombo);
        addLabeledComponent(modelPanel, "Model:", modelNameCombo);
        
        JButton refreshBtn = new JButton("Refresh Models");
        refreshBtn.addActionListener(e -> loadModels());
        refreshBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        modelPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        modelPanel.add(refreshBtn);
        
        panel.add(modelPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        
        // Segmentation parameters
        JPanel paramPanel = createSection("Segmentation Parameters");
        
        diameterSpinner = new JSpinner(new SpinnerNumberModel(30.0, 0.0, 500.0, 5.0));
        flowThresholdSpinner = new JSpinner(new SpinnerNumberModel(0.4, 0.0, 3.0, 0.1));
        cellprobThresholdSpinner = new JSpinner(new SpinnerNumberModel(0.0, -6.0, 6.0, 0.5));
        channelsField = new JTextField("0,0", 10);
        useGpuCheckbox = new JCheckBox("Use GPU");
        
        addLabeledComponent(paramPanel, "Cell Diameter (px):", diameterSpinner);
        addLabeledComponent(paramPanel, "Flow Threshold:", flowThresholdSpinner);
        addLabeledComponent(paramPanel, "Cell Prob Threshold:", cellprobThresholdSpinner);
        addLabeledComponent(paramPanel, "Channels:", channelsField);
        paramPanel.add(useGpuCheckbox);

        // Channel flags are only used by Cellpose 3.1 in CLI mode.
        updateChannelSelectionState();
        
        panel.add(paramPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        
        // Display settings
        JPanel displaySettingsPanel = createSection("Display Settings");
        
        showMaskCheckbox = new JCheckBox("Show Mask Overlay", true);
        showMaskCheckbox.addActionListener(e -> {
            showMask = showMaskCheckbox.isSelected();
            updateDisplay();
        });
        displaySettingsPanel.add(showMaskCheckbox);
        
        displaySettingsPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        displaySettingsPanel.add(new JLabel("Mask Opacity:"));
        opacitySlider = new JSlider(0, 100, 50);
        opacitySlider.setMajorTickSpacing(25);
        opacitySlider.setPaintTicks(true);
        opacitySlider.setPaintLabels(true);
        opacitySlider.addChangeListener(e -> {
            maskOpacity = opacitySlider.getValue() / 100.0f;
            updateDisplay();
        });
        displaySettingsPanel.add(opacitySlider);
        
        panel.add(displaySettingsPanel);
        panel.add(Box.createRigidArea(new Dimension(0, 10)));
        
        // Run segmentation
        JPanel actionPanel = createSection("Actions");
        
        segmentButton = new JButton("Run Segmentation");
        segmentButton.setFont(new Font("Arial", Font.BOLD, 14));
        segmentButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        segmentButton.addActionListener(e -> runSegmentation());
        actionPanel.add(segmentButton);
        
        actionPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionPanel.add(progressBar);
        
        panel.add(actionPanel);
        panel.add(Box.createVerticalGlue());
        
        return panel;
    }
    
    /**
     * Create display panel.
     */
    private JPanel createDisplayPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Image display
        imageLabel = new JLabel("No image loaded", SwingConstants.CENTER);
        imageLabel.setFont(new Font("Arial", Font.PLAIN, 16));
        imageLabel.setForeground(Color.GRAY);
        
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        scrollPane.setBackground(Color.DARK_GRAY);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Zoom controls
        JPanel zoomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton zoomInBtn = new JButton("+");
        JButton zoomOutBtn = new JButton("-");
        JButton zoomFitBtn = new JButton("Fit");
        JLabel zoomLabel = new JLabel("100%");
        
        zoomInBtn.addActionListener(e -> { zoomIn(); zoomLabel.setText(String.format("%.0f%%", zoomFactor * 100)); });
        zoomOutBtn.addActionListener(e -> { zoomOut(); zoomLabel.setText(String.format("%.0f%%", zoomFactor * 100)); });
        zoomFitBtn.addActionListener(e -> { zoomFit(); zoomLabel.setText("Fit"); });
        
        zoomPanel.add(new JLabel("Zoom:"));
        zoomPanel.add(zoomOutBtn);
        zoomPanel.add(zoomLabel);
        zoomPanel.add(zoomInBtn);
        zoomPanel.add(zoomFitBtn);
        
        panel.add(zoomPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * Create a titled section panel.
     */
    private JPanel createSection(String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title,
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Arial", Font.BOLD, 12)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }
    
    /**
     * Add a labeled component to a panel.
     */
    private void addLabeledComponent(JPanel panel, String label, JComponent component) {
        JPanel row = new JPanel(new BorderLayout(5, 0));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel jLabel = new JLabel(label);
        jLabel.setPreferredSize(new Dimension(150, 25));
        row.add(jLabel, BorderLayout.WEST);
        row.add(component, BorderLayout.CENTER);
        panel.add(row);
        panel.add(Box.createRigidArea(new Dimension(0, 5)));
    }
    
    /**
     * Initialize color map for mask visualization.
     */
    private void initializeColorMap() {
        colorMap = new Color[256];
        for (int i = 0; i < 256; i++) {
            float hue = (i * 137.508f) % 360 / 360.0f;  // Golden angle for distinct colors
            colorMap[i] = Color.getHSBColor(hue, 0.8f, 0.9f);
        }
        colorMap[0] = new Color(0, 0, 0, 0);  // Transparent background
    }
    
    /**
     * Find the backend directory from project root.
     */
    private Path findBackendDirectory() {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path backendDir = projectRoot.resolve("cellpose backend");
        
        if (backendDir.toFile().exists() && backendDir.toFile().isDirectory()) {
            return backendDir;
        }
        return null;
    }
    
    /**
     * Get Python executable path based on model type.
     */
    private String getPythonExecutable(String modelType) {
        if (backendDir == null) {
            return null;
        }
        
        String venvName = modelType.equals("Cellpose3.1") ? "venv_v3" : "venv_v4";
        Path pythonPath = backendDir.resolve("cellpose backend")
                                    .resolve(venvName)
                                    .resolve("Scripts")
                                    .resolve("python.exe");
        
        if (pythonPath.toFile().exists()) {
            return pythonPath.toString();
        }
        
        // Fallback to system python
        return "python";
    }
    
    /**
     * Load available models by scanning backend directories.
     */
    private void loadModels() {
        SwingWorker<JSONObject, Void> worker = new SwingWorker<>() {
            @Override
            protected JSONObject doInBackground() throws Exception {
                JSONObject models = new JSONObject();
                
                // Use LinkedHashSet to maintain order and avoid duplicates
                java.util.LinkedHashSet<String> cellpose31Set = new java.util.LinkedHashSet<>();
                java.util.LinkedHashSet<String> cellposeSAMSet = new java.util.LinkedHashSet<>();
                
                // Add built-in Cellpose 3.1 models first
                cellpose31Set.add("cyto3");  // Built-in cytoplasm model
                
                // Scan for custom Cellpose 3.1 models if directory exists
                if (modelsDir != null) {
                    Path cellpose31Dir = modelsDir.resolve("Cellpose 3.1");
                    if (cellpose31Dir.toFile().exists()) {
                        File[] files = cellpose31Dir.toFile().listFiles();
                        if (files != null) {
                            for (File file : files) {
                                // Add model files (with or without .pth extension, but skip README and directories)
                                if (file.isFile() && !file.getName().equals("README.md")) {
                                    cellpose31Set.add(file.getName());
                                }
                            }
                        }
                    }
                }
                
                // Convert Set to JSONArray
                JSONArray cellpose31Models = new JSONArray();
                for (String model : cellpose31Set) {
                    cellpose31Models.put(model);
                }
                models.put("Cellpose3.1", cellpose31Models);
                
                // Add built-in CellposeSAM models first
                cellposeSAMSet.add("cpsam");  // Built-in SAM model
                
                // Scan for custom CellposeSAM models if directory exists
                if (modelsDir != null) {
                    Path cellposeSAMDir = modelsDir.resolve("CellposeSAM");
                    if (cellposeSAMDir.toFile().exists()) {
                        File[] files = cellposeSAMDir.toFile().listFiles();
                        if (files != null) {
                            for (File file : files) {
                                // Add model files (with or without .pth extension, but skip README and directories)
                                if (file.isFile() && !file.getName().equals("README.md")) {
                                    cellposeSAMSet.add(file.getName());
                                }
                            }
                        }
                    }
                }
                
                // Convert Set to JSONArray
                JSONArray cellposeSAMModels = new JSONArray();
                for (String model : cellposeSAMSet) {
                    cellposeSAMModels.put(model);
                }
                models.put("CellposeSAM", cellposeSAMModels);
                
                return models;
            }
            
            @Override
            protected void done() {
                try {
                    JSONObject models = get();
                    String modelType = (String) modelTypeCombo.getSelectedItem();
                    JSONArray modelList = models.getJSONArray(modelType);
                    
                    modelNameCombo.removeAllItems();
                    for (int i = 0; i < modelList.length(); i++) {
                        modelNameCombo.addItem(modelList.getString(i));
                    }
                    
                    int modelCount = modelList.length();
                    if (modelCount > 0) {
                        statusLabel.setText(" " + modelCount + " model(s) available");
                    } else {
                        statusLabel.setText(" No models found");
                    }
                } catch (Exception e) {
                    statusLabel.setText(" Error loading models: " + e.getMessage());
                    // Add at least a default model so the UI is usable
                    modelNameCombo.removeAllItems();
                    modelNameCombo.addItem("cyto3");
                }
            }
        };
        worker.execute();
    }
    
    /**
     * Open an image file.
     */
    private void openImage() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter(
            "Image Files", "tif", "tiff", "png", "jpg", "jpeg", "bmp"));
        
        if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try {
                // Try ImageJ first for better TIFF support
                Opener opener = new Opener();
                imagePlus = opener.openImage(file.getAbsolutePath());
                if (imagePlus != null) {
                    originalImage = imagePlus.getBufferedImage();
                } else {
                    // Fallback to standard ImageIO
                    originalImage = ImageIO.read(file);
                }
                
                if (originalImage != null) {
                    maskImage = null;
                    zoomFit();
                    statusLabel.setText(" Loaded: " + file.getName());
                } else {
                    JOptionPane.showMessageDialog(frame,
                        "Failed to load image: " + file.getName(),
                        "Load Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame,
                    "Error loading image: " + e.getMessage(),
                    "Load Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    /**
     * Run segmentation by directly executing Cellpose CLI.
     */
    private void runSegmentation() {
        if (originalImage == null) {
            JOptionPane.showMessageDialog(frame,
                "Please load an image first.",
                "No Image", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        segmentButton.setEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Running segmentation...");
        statusLabel.setText(" Running segmentation...");
        
        SwingWorker<BufferedImage, String> worker = new SwingWorker<>() {
            @Override
            protected BufferedImage doInBackground() throws Exception {
                // Save image to temp file
                File tempFile = File.createTempFile("cellpose_input_", ".png");
                ImageIO.write(originalImage, "png", tempFile);

                // CLI requires an existing output directory.
                File outputDir = new File(System.getProperty("java.io.tmpdir"), "cellpose_out_" + System.nanoTime());
                if (!outputDir.mkdirs() && !outputDir.exists()) {
                    tempFile.delete();
                    throw new Exception("Failed to create temporary output directory: " + outputDir);
                }
                
                try {
                    // Get parameters
                    String modelType = (String) modelTypeCombo.getSelectedItem();
                    String modelName = (String) modelNameCombo.getSelectedItem();
                    String diameter = String.valueOf(diameterSpinner.getValue());
                    String channels = channelsField.getText();
                    boolean useGpu = useGpuCheckbox.isSelected();
                    String flowThreshold = String.valueOf(flowThresholdSpinner.getValue());
                    String cellprobThreshold = String.valueOf(cellprobThresholdSpinner.getValue());

                    // Get Python executable
                    String pythonExe = getPythonExecutable(modelType);
                    if (pythonExe == null) {
                        throw new Exception("Python executable not found for " + modelType);
                    }

                    String pretrainedModelArg = resolvePretrainedModelArg(modelType, modelName);
                    int[] parsedChannels = parseChannels(channels);

                    publish("Initializing " + modelType + "/" + modelName + "...");

                    // Build CLI command: python -m cellpose ...
                    List<String> command = new ArrayList<>();
                    command.add(pythonExe);
                    command.add("-m");
                    command.add("cellpose");
                    command.add("--image_path");
                    command.add(tempFile.getAbsolutePath());
                    command.add("--pretrained_model");
                    command.add(pretrainedModelArg);
                    command.add("--diameter");
                    command.add(diameter);
                    command.add("--flow_threshold");
                    command.add(flowThreshold);
                    command.add("--cellprob_threshold");
                    command.add(cellprobThreshold);
                    command.add("--save_png");
                    command.add("--savedir");
                    command.add(outputDir.getAbsolutePath());
                    command.add("--no_npy");

                    // Channels are meaningful for Cellpose 3.1; SAM marks these as deprecated.
                    if ("Cellpose3.1".equals(modelType)) {
                        command.add("--chan");
                        command.add(String.valueOf(parsedChannels[0]));
                        command.add("--chan2");
                        command.add(String.valueOf(parsedChannels[1]));
                    }

                    if (useGpu) {
                        command.add("--use_gpu");
                    }

                    // Debug: Log the command
                    System.out.println("[Cellpose CLI] Executing command:");
                    System.out.println("[Cellpose CLI] " + String.join(" ", command));

                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(backendDir.resolve("cellpose backend").toFile());
                    pb.redirectErrorStream(true);

                    System.out.println("[Cellpose CLI] Working directory: " + pb.directory());

                    publish("Running inference...");

                    Process process = pb.start();

                    BufferedReader processReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                    StringBuilder processLog = new StringBuilder();
                    String line;
                    while ((line = processReader.readLine()) != null) {
                        processLog.append(line).append("\n");
                        System.out.println("[Cellpose CLI] " + line);
                    }
                    processReader.close();

                    int exitCode = process.waitFor();
                    System.out.println("[Cellpose CLI] Process exited with code: " + exitCode);

                    if (exitCode != 0) {
                        throw new Exception("Segmentation failed with exit code " + exitCode + "\n\nCLI output:\n" + processLog);
                    }

                    File maskFile = findMaskOutput(outputDir, tempFile);
                    if (maskFile == null || !maskFile.exists()) {
                        throw new Exception(
                            "Segmentation completed but mask file was not found in: " + outputDir +
                            "\nExpected suffix: _cp_masks.png\n\nCLI output:\n" + processLog
                        );
                    }

                    BufferedImage rawMask = ImageIO.read(maskFile);
                    if (rawMask == null) {
                        throw new Exception("Could not read generated mask file: " + maskFile);
                    }

                    int[] numCellsOut = new int[]{0};
                    BufferedImage coloredMask = createColoredMaskFromLabels(rawMask, numCellsOut);
                    publish("Found " + numCellsOut[0] + " cells");

                    return coloredMask;
                } finally {
                    tempFile.delete();
                    deleteRecursively(outputDir);
                }
            }
            
            @Override
            protected void process(java.util.List<String> chunks) {
                for (String msg : chunks) {
                    statusLabel.setText(" " + msg);
                }
            }
            
            @Override
            protected void done() {
                try {
                    maskImage = get();
                    updateDisplay();
                    statusLabel.setText(" Segmentation completed");
                    progressBar.setString("Completed");
                } catch (Exception e) {
                    statusLabel.setText(" Segmentation failed: " + e.getMessage());
                    progressBar.setString("Failed");
                    JOptionPane.showMessageDialog(frame,
                        "Segmentation error: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
                progressBar.setIndeterminate(false);
                segmentButton.setEnabled(true);
            }
        };
        worker.execute();
    }

    private String resolvePretrainedModelArg(String modelType, String modelName) throws Exception {
        // Built-in names can be passed directly to CLI.
        if ("cyto3".equals(modelName) || "cpsam".equals(modelName)) {
            return modelName;
        }

        if (modelsDir == null) {
            throw new Exception("Models directory not found.");
        }

        Path modelSubdir = "Cellpose3.1".equals(modelType)
            ? modelsDir.resolve("Cellpose 3.1")
            : modelsDir.resolve("CellposeSAM");
        Path modelPath = modelSubdir.resolve(modelName).toAbsolutePath();

        if (!modelPath.toFile().exists()) {
            throw new Exception("Selected model file not found: " + modelPath);
        }

        return modelPath.toString();
    }

    private int[] parseChannels(String channelsText) {
        int chan = 0;
        int chan2 = 0;

        if (channelsText != null) {
            String[] parts = channelsText.split(",");
            if (parts.length > 0) {
                chan = parseChannelIndex(parts[0], 0);
            }
            if (parts.length > 1) {
                chan2 = parseChannelIndex(parts[1], 0);
            }
        }

        return new int[]{chan, chan2};
    }

    private int parseChannelIndex(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(0, Math.min(3, parsed));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void updateChannelSelectionState() {
        if (channelsField == null || modelTypeCombo == null) {
            return;
        }

        String modelType = (String) modelTypeCombo.getSelectedItem();
        boolean enableChannels = "Cellpose3.1".equals(modelType);
        channelsField.setEnabled(enableChannels);

        if (enableChannels) {
            channelsField.setToolTipText("Cellpose3.1: 0=GRAY, 1=RED, 2=GREEN, 3=BLUE (format: chan,chan2)");
        } else {
            channelsField.setToolTipText("CellposeSAM ignores --chan/--chan2 in CLI (v4.0.1+)");
        }
    }

    private File findMaskOutput(File outputDir, File inputFile) {
        String inputName = inputFile.getName();
        int dot = inputName.lastIndexOf('.');
        String baseName = dot > 0 ? inputName.substring(0, dot) : inputName;

        File expected = new File(outputDir, baseName + "_cp_masks.png");
        if (expected.exists()) {
            return expected;
        }

        File[] candidates = outputDir.listFiles((dir, name) -> name.endsWith("_cp_masks.png"));
        if (candidates != null && candidates.length > 0) {
            return candidates[0];
        }

        return null;
    }

    private BufferedImage createColoredMaskFromLabels(BufferedImage labelImage, int[] numCellsOut) {
        int width = labelImage.getWidth();
        int height = labelImage.getHeight();

        BufferedImage colored = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        HashSet<Integer> labels = new HashSet<>();

        java.awt.image.Raster raster = labelImage.getRaster();
        int bands = raster.getNumBands();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int label;
                if (bands >= 1) {
                    label = raster.getSample(x, y, 0);
                } else {
                    label = 0;
                }

                if (label <= 0) {
                    colored.setRGB(x, y, 0);
                    continue;
                }

                labels.add(label);
                Color color = colorMap[Math.floorMod(label, colorMap.length)];
                int argb = (255 << 24)
                    | (color.getRed() << 16)
                    | (color.getGreen() << 8)
                    | color.getBlue();
                colored.setRGB(x, y, argb);
            }
        }

        if (numCellsOut != null && numCellsOut.length > 0) {
            numCellsOut[0] = labels.size();
        }

        return colored;
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }

        if (!file.delete()) {
            file.deleteOnExit();
        }
    }
    
    /**
     * Update the display with current image and mask.
     */
    private void updateDisplay() {
        if (originalImage == null) {
            return;
        }
        
        // Create composite image
        displayImage = new BufferedImage(originalImage.getWidth(), originalImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = displayImage.createGraphics();
        g.drawImage(originalImage, 0, 0, null);
        
        // Overlay mask if available and enabled
        if (maskImage != null && showMask) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, maskOpacity));
            g.drawImage(maskImage, 0, 0, null);
        }
        g.dispose();
        
        // Apply zoom
        int width = (int) (displayImage.getWidth() * zoomFactor);
        int height = (int) (displayImage.getHeight() * zoomFactor);
        Image scaledImage = displayImage.getScaledInstance(width, height, Image.SCALE_SMOOTH);
        
        imageLabel.setIcon(new ImageIcon(scaledImage));
        imageLabel.setText(null);
    }
    
    // Zoom methods
    private void zoomIn() {
        zoomFactor *= 1.25;
        updateDisplay();
    }
    
    private void zoomOut() {
        zoomFactor /= 1.25;
        updateDisplay();
    }
    
    private void zoomFit() {
        if (originalImage != null) {
            Dimension viewportSize = imageLabel.getParent().getSize();
            double scaleX = (double) viewportSize.width / originalImage.getWidth();
            double scaleY = (double) viewportSize.height / originalImage.getHeight();
            zoomFactor = Math.min(scaleX, scaleY) * 0.95;
            updateDisplay();
        }
    }
    
    // Export methods
    private void exportMask() {
        if (maskImage == null) {
            JOptionPane.showMessageDialog(frame, "No mask to export", "Export", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG Images", "png"));
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }
            try {
                ImageIO.write(maskImage, "png", file);
                statusLabel.setText(" Mask exported: " + file.getName());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void exportOverlay() {
        if (displayImage == null) {
            JOptionPane.showMessageDialog(frame, "No image to export", "Export", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG Images", "png"));
        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".png")) {
                file = new File(file.getAbsolutePath() + ".png");
            }
            try {
                ImageIO.write(displayImage, "png", file);
                statusLabel.setText(" Overlay exported: " + file.getName());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Export failed: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
