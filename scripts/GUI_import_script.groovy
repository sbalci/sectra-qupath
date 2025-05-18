/**
 * Project-Wide Sectra DICOM Annotation Importer for QuPath
 * 
 * This script processes all images in a project, importing Sectra PACS DICOM 
 * annotations with customizable options:
 * - Classification settings with color schemes
 * - Appearance options
 * - Measurement handling
 * 
 * If a DICOM file isn't found for an image, the script simply skips to the next image.
 * 
 * Required:
 * - Python with required libraries (pydicom, geojson, numpy)
 * - sectra_dicom_to_qupath.py script in the QuPath project folder
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.prefs.PathPrefs
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.images.servers.ImageServer
import qupath.lib.images.ImageData
import qupath.lib.projects.Project
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.projects.ProjectIO

import java.awt.Desktop
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
import java.awt.Insets
import javax.swing.*
import javax.swing.border.TitledBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.event.ChangeListener
import javax.swing.event.ChangeEvent
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicBoolean

// Check if we have a project open
def gui = QuPathGUI.getInstance()
if (gui == null) {
    Dialogs.showErrorMessage("Error", "QuPath GUI not available")
    return
}

def project = gui.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Error", "No project is open. Please open a project first.")
    return
}

// Get list of images in the project
def imageList = project.getImageList()
def totalImages = imageList.size()

if (totalImages == 0) {
    Dialogs.showErrorMessage("Error", "Project contains no images!")
    return
}

// =========================== GUI COMPONENTS ===========================

// Create main dialog for options
def dialog = new JDialog()
dialog.setTitle("Project-Wide Sectra DICOM Annotation Importer")
dialog.setModal(true)
dialog.setLayout(new BorderLayout(10, 10))
dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)

// Add window close handler
dialog.addWindowListener(new java.awt.event.WindowAdapter() {
    @Override
    public void windowClosing(java.awt.event.WindowEvent e) {
        // Set cancel flag in case processing is running
        cancelRequested.set(true)
        
        // Close any writers that might be open
        if (masterWriter != null) {
            try { masterWriter.close() } catch (Exception ex) {}
        }
    }
})



// Create content panel with GridBagLayout for more control
def contentPanel = new JPanel(new GridBagLayout())
contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))
def gbc = new GridBagConstraints()
gbc.insets = new Insets(5, 5, 5, 5)
gbc.fill = GridBagConstraints.HORIZONTAL
gbc.weightx = 1.0
gbc.gridwidth = 1
gbc.gridx = 0
gbc.gridy = 0

// Section 1: Project & Python Configuration
def projectPanel = new JPanel(new GridBagLayout())
projectPanel.setBorder(BorderFactory.createTitledBorder(
    BorderFactory.createEtchedBorder(), "Project Configuration", 
    TitledBorder.LEFT, TitledBorder.TOP))

def projectNameLabel = new JLabel("Project: ")
def projectNameField = new JLabel(project.getName())
projectNameField.setFont(projectNameField.getFont().deriveFont(java.awt.Font.BOLD))

def totalImagesLabel = new JLabel("Total images: ")
def totalImagesField = new JLabel(String.valueOf(totalImages))

def pythonExeLabel = new JLabel("Python executable:")
def pythonExeField = new JTextField("python", 20)
def pythonBrowseButton = new JButton("Browse...")

def pythonScriptLabel = new JLabel("DICOM to GeoJSON script:")
def pythonScriptField = new JTextField("sectra_dicom_to_qupath.py", 20)
def pythonScriptBrowseButton = new JButton("Browse...")

def projectGbc = new GridBagConstraints()
projectGbc.insets = new Insets(2, 5, 2, 5)
projectGbc.fill = GridBagConstraints.HORIZONTAL
projectGbc.gridx = 0; projectGbc.gridy = 0; projectGbc.weightx = 0;
projectPanel.add(projectNameLabel, projectGbc)
projectGbc.gridx = 1; projectGbc.weightx = 1; projectGbc.gridwidth = 2;
projectPanel.add(projectNameField, projectGbc)

projectGbc.gridx = 0; projectGbc.gridy = 1; projectGbc.weightx = 0; projectGbc.gridwidth = 1;
projectPanel.add(totalImagesLabel, projectGbc)
projectGbc.gridx = 1; projectGbc.weightx = 1; projectGbc.gridwidth = 2;
projectPanel.add(totalImagesField, projectGbc)

projectGbc.gridx = 0; projectGbc.gridy = 2; projectGbc.weightx = 0; projectGbc.gridwidth = 1;
projectPanel.add(pythonExeLabel, projectGbc)
projectGbc.gridx = 1; projectGbc.weightx = 1;
projectPanel.add(pythonExeField, projectGbc)
projectGbc.gridx = 2; projectGbc.weightx = 0;
projectPanel.add(pythonBrowseButton, projectGbc)

projectGbc.gridx = 0; projectGbc.gridy = 3; projectGbc.weightx = 0;
projectPanel.add(pythonScriptLabel, projectGbc)
projectGbc.gridx = 1; projectGbc.weightx = 1;
projectPanel.add(pythonScriptField, projectGbc)
projectGbc.gridx = 2; projectGbc.weightx = 0;
projectPanel.add(pythonScriptBrowseButton, projectGbc)

// Add Python config panel to main content
gbc.gridwidth = GridBagConstraints.REMAINDER
contentPanel.add(projectPanel, gbc)
gbc.gridy++

// Section 2: Annotation Classification Options
def classificationPanel = new JPanel(new GridBagLayout())
classificationPanel.setBorder(BorderFactory.createTitledBorder(
    BorderFactory.createEtchedBorder(), "Classification Options", 
    TitledBorder.LEFT, TitledBorder.TOP))

def applyClassificationCheckbox = new JCheckBox("Apply classifications based on labels", true)
def includeMeasurementsCheckbox = new JCheckBox("Include measurements in annotation names", true)
def colorSchemeLabel = new JLabel("Color scheme:")

// Create a combo box for color schemes
def colorSchemes = ["Geometry-based", "Label-based", "Random", "Sequential", "Custom"] as String[]
def colorSchemeCombo = new JComboBox<>(colorSchemes)

def classGbc = new GridBagConstraints()
classGbc.insets = new Insets(2, 5, 2, 5)
classGbc.fill = GridBagConstraints.HORIZONTAL
classGbc.gridx = 0; classGbc.gridy = 0; classGbc.weightx = 1; classGbc.gridwidth = 2;
classificationPanel.add(applyClassificationCheckbox, classGbc)
classGbc.gridy = 1;
classificationPanel.add(includeMeasurementsCheckbox, classGbc)
classGbc.gridy = 2; classGbc.gridwidth = 1; classGbc.weightx = 0;
classificationPanel.add(colorSchemeLabel, classGbc)
classGbc.gridx = 1; classGbc.weightx = 1;
classificationPanel.add(colorSchemeCombo, classGbc)

// Add a color preview panel
def colorPreviewPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0))
colorPreviewPanel.setBorder(BorderFactory.createTitledBorder(
    BorderFactory.createEtchedBorder(), "Color Preview", 
    TitledBorder.LEFT, TitledBorder.TOP))

// Add some example color swatches
def createColorSwatch = { Color color, String label ->
    def swatch = new JPanel()
    swatch.setPreferredSize(new Dimension(20, 20))
    swatch.setBackground(color)
    swatch.setBorder(BorderFactory.createLineBorder(Color.BLACK))
    swatch.setToolTipText(label)
    return swatch
}

def swatchesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5))
swatchesPanel.add(createColorSwatch(new Color(255, 215, 0), "Circle"))
swatchesPanel.add(new JLabel("Circle"))
swatchesPanel.add(createColorSwatch(new Color(30, 144, 255), "Polyline"))
swatchesPanel.add(new JLabel("Polyline"))
swatchesPanel.add(createColorSwatch(new Color(255, 69, 0), "Polygon"))
swatchesPanel.add(new JLabel("Polygon"))
swatchesPanel.add(createColorSwatch(new Color(0, 191, 255), "Arrow"))
swatchesPanel.add(new JLabel("Arrow"))

colorPreviewPanel.add(swatchesPanel)

classGbc.gridx = 0; classGbc.gridy = 3; classGbc.gridwidth = 2; classGbc.weightx = 1;
classificationPanel.add(colorPreviewPanel, classGbc)

// Add Classification panel to main content
gbc.gridwidth = GridBagConstraints.REMAINDER
contentPanel.add(classificationPanel, gbc)
gbc.gridy++

// Section 3: Advanced Options
def advancedPanel = new JPanel(new GridBagLayout())
advancedPanel.setBorder(BorderFactory.createTitledBorder(
    BorderFactory.createEtchedBorder(), "Advanced Options", 
    TitledBorder.LEFT, TitledBorder.TOP))

def skipMissingDicomCheckbox = new JCheckBox("Skip images with missing DICOM files", true)
def createReportCheckbox = new JCheckBox("Create detailed import report", true)
def extractFilterLabel = new JLabel("Filter annotations (Optional):")
def extractFilterField = new JTextField("", 20)
extractFilterField.setToolTipText("Enter keywords to only import matching annotations (comma-separated)")
def processAllImagesCheckbox = new JCheckBox("Process all images (ignoring selection)", true)

def advGbc = new GridBagConstraints()
advGbc.insets = new Insets(2, 5, 2, 5)
advGbc.fill = GridBagConstraints.HORIZONTAL
advGbc.gridx = 0; advGbc.gridy = 0; advGbc.weightx = 1; advGbc.gridwidth = 2;
advancedPanel.add(skipMissingDicomCheckbox, advGbc)
advGbc.gridy = 1;
advancedPanel.add(createReportCheckbox, advGbc)
advGbc.gridy = 2;
advancedPanel.add(processAllImagesCheckbox, advGbc)
advGbc.gridy = 3; advGbc.gridwidth = 1; advGbc.weightx = 0;
advancedPanel.add(extractFilterLabel, advGbc)
advGbc.gridx = 1; advGbc.weightx = 1;
advancedPanel.add(extractFilterField, advGbc)

// Add Advanced panel to main content
gbc.gridwidth = GridBagConstraints.REMAINDER
contentPanel.add(advancedPanel, gbc)
gbc.gridy++

// Progress section
def progressPanel = new JPanel(new BorderLayout(5, 5))
progressPanel.setBorder(BorderFactory.createTitledBorder(
    BorderFactory.createEtchedBorder(), "Progress", 
    TitledBorder.LEFT, TitledBorder.TOP))

def progressBar = new JProgressBar(0, totalImages)
progressBar.setStringPainted(true)
progressBar.setString("Ready to process " + totalImages + " images")

progressPanel.add(progressBar, BorderLayout.CENTER)

// Add progress panel to main content
gbc.fill = GridBagConstraints.HORIZONTAL
contentPanel.add(progressPanel, gbc)
gbc.gridy++

// Add buttons panel
def buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT))
def cancelButton = new JButton("Cancel")
def importButton = new JButton("Import Annotations")
importButton.setPreferredSize(new Dimension(150, 30))
buttonsPanel.add(cancelButton)
buttonsPanel.add(importButton)

// Add buttons panel to dialog
dialog.add(contentPanel, BorderLayout.CENTER)
dialog.add(buttonsPanel, BorderLayout.SOUTH)

// =========================== EVENT HANDLERS ===========================

// Handler for Python executable browse button
pythonBrowseButton.addActionListener({ e ->
    def fileChooser = new JFileChooser()
    fileChooser.setDialogTitle("Select Python Executable")
    
    // Set file filter based on OS
    if (System.getProperty("os.name").toLowerCase().contains("win")) {
        fileChooser.setFileFilter(new FileNameExtensionFilter("Executables (*.exe)", "exe"))
    }
    
    def result = fileChooser.showOpenDialog(dialog)
    if (result == JFileChooser.APPROVE_OPTION) {
        pythonExeField.setText(fileChooser.getSelectedFile().getAbsolutePath())
    }
} as java.awt.event.ActionListener)

// Handler for Python script browse button
pythonScriptBrowseButton.addActionListener({ e ->
    def fileChooser = new JFileChooser()
    fileChooser.setDialogTitle("Select DICOM to GeoJSON Script")
    fileChooser.setFileFilter(new FileNameExtensionFilter("Python Scripts (*.py)", "py"))
    
    // Try to get project directory
    def projectDir = project.getPath().getParent().toFile()
    if (projectDir.exists()) {
        fileChooser.setCurrentDirectory(projectDir)
    }
    
    def result = fileChooser.showOpenDialog(dialog)
    if (result == JFileChooser.APPROVE_OPTION) {
        pythonScriptField.setText(fileChooser.getSelectedFile().getAbsolutePath())
    }
} as java.awt.event.ActionListener)

// Handler for color scheme combo box
colorSchemeCombo.addActionListener({ e ->
    def selectedScheme = colorSchemeCombo.getSelectedItem()
    // Update the color preview based on selection
    swatchesPanel.removeAll()
    
    if (selectedScheme == "Geometry-based") {
        swatchesPanel.add(createColorSwatch(new Color(255, 215, 0), "Circle"))
        swatchesPanel.add(new JLabel("Circle"))
        swatchesPanel.add(createColorSwatch(new Color(30, 144, 255), "Polyline"))
        swatchesPanel.add(new JLabel("Polyline"))
        swatchesPanel.add(createColorSwatch(new Color(255, 69, 0), "Polygon"))
        swatchesPanel.add(new JLabel("Polygon"))
        swatchesPanel.add(createColorSwatch(new Color(0, 191, 255), "Arrow"))
        swatchesPanel.add(new JLabel("Arrow"))
    } else if (selectedScheme == "Label-based") {
        swatchesPanel.add(createColorSwatch(new Color(220, 20, 60), "Tumor"))
        swatchesPanel.add(new JLabel("Tumor"))
        swatchesPanel.add(createColorSwatch(new Color(0, 128, 0), "Normal"))
        swatchesPanel.add(new JLabel("Normal"))
        swatchesPanel.add(createColorSwatch(new Color(50, 205, 50), "Stroma"))
        swatchesPanel.add(new JLabel("Stroma"))
        swatchesPanel.add(createColorSwatch(new Color(255, 165, 0), "Measurement"))
        swatchesPanel.add(new JLabel("Measurement"))
    } else if (selectedScheme == "Sequential") {
        swatchesPanel.add(createColorSwatch(new Color(31, 119, 180), "Class 1"))
        swatchesPanel.add(new JLabel("Class 1"))
        swatchesPanel.add(createColorSwatch(new Color(255, 127, 14), "Class 2"))
        swatchesPanel.add(new JLabel("Class 2"))
        swatchesPanel.add(createColorSwatch(new Color(44, 160, 44), "Class 3"))
        swatchesPanel.add(new JLabel("Class 3"))
        swatchesPanel.add(createColorSwatch(new Color(214, 39, 40), "Class 4"))
        swatchesPanel.add(new JLabel("Class 4"))
    } else if (selectedScheme == "Random") {
        swatchesPanel.add(createColorSwatch(new Color(120, 80, 200), "Random 1"))
        swatchesPanel.add(new JLabel("Random 1"))
        swatchesPanel.add(createColorSwatch(new Color(200, 130, 30), "Random 2"))
        swatchesPanel.add(new JLabel("Random 2"))
        swatchesPanel.add(createColorSwatch(new Color(70, 180, 120), "Random 3"))
        swatchesPanel.add(new JLabel("Random 3"))
    } else if (selectedScheme == "Custom") {
        swatchesPanel.add(new JButton("Edit Colors..."))
    }
    
    swatchesPanel.revalidate()
    swatchesPanel.repaint()
} as java.awt.event.ActionListener)


// Handler for cancel button
cancelButton.addActionListener({ e ->
    // Make sure we clean up any resources
    if (masterWriter != null) {
        try { masterWriter.close() } catch (Exception ex) {}
    }
    
    // Close the dialog
    dialog.dispose()
} as java.awt.event.ActionListener)


// Variables for tracking progress
def selectedImages = []
def successCount = 0
def failCount = 0
def skippedCount = 0
def cancelRequested = new AtomicBoolean(false)




// Handler for import button
importButton.addActionListener({ e ->
    // Save the configuration for later use
    def config = [
        pythonExe: pythonExeField.getText(),
        pythonScript: pythonScriptField.getText(),
        applyClassification: applyClassificationCheckbox.isSelected(),
        includeMeasurements: includeMeasurementsCheckbox.isSelected(),
        colorScheme: colorSchemeCombo.getSelectedItem(),
        skipMissingDicom: skipMissingDicomCheckbox.isSelected(),
        createReport: createReportCheckbox.isSelected(),
        filter: extractFilterField.getText()
    ]
    
    // Validate Python script path
    def scriptFile = new File(config.pythonScript)
    if (!scriptFile.exists()) {
        Dialogs.showErrorMessage("Error", "Python script not found: " + config.pythonScript)
        return
    }
    
    // Determine which images to process
    if (processAllImagesCheckbox.isSelected()) {
        selectedImages = imageList
    } else {
        // Get currently selected images
        selectedImages = gui.getImageDataManager().getSelectedImages().collect { imageData ->
            def uri = imageData.getServerURI()
            return imageList.find { it.getURI() == uri }
        }.findAll { it != null }
        
        if (selectedImages.isEmpty()) {
            selectedImages = imageList  // Default to all if no selection
        }
    }
    
    // Create a timestamp for reports
    def timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date())
    
    // Create master report file
    def projectDir = project.getPath().getParent().toFile()
    def masterReportFile = new File(projectDir, "sectra_import_report_" + timestamp + ".txt")
    def masterWriter = new PrintWriter(masterReportFile)
    
    masterWriter.println("=== SECTRA DICOM ANNOTATION IMPORT REPORT ===")
    masterWriter.println("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
    masterWriter.println("Project: " + project.getPath().toString())
    masterWriter.println("Total images: " + selectedImages.size())
    masterWriter.println("Configuration: " + config)
    masterWriter.println("===========================================")
    masterWriter.println()
    
    // Prepare progress tracking
    progressBar.setMinimum(0)
    progressBar.setMaximum(selectedImages.size())
    progressBar.setValue(0)
    progressBar.setString("Processing 0/" + selectedImages.size())
    
    // Create a cancel button for the progress dialog
    def cancelProgressButton = new JButton("Cancel Processing")
    progressPanel.add(cancelProgressButton, BorderLayout.SOUTH)
    cancelProgressButton.addActionListener({ event ->
        cancelRequested.set(true)
        cancelProgressButton.setEnabled(false)
        cancelProgressButton.setText("Cancelling...")
    } as java.awt.event.ActionListener)
    
    // Disable main buttons
    importButton.setEnabled(false)
    cancelButton.setEnabled(false)
    
    // List to keep track of any running processes
    def runningProcesses = Collections.synchronizedList([])
    
    // Start processing in a separate thread
    def processingThread = Thread.start {
        try {
            // Process each image
            selectedImages.eachWithIndex { entry, index ->
                if (cancelRequested.get()) {
                    masterWriter.println("Processing cancelled by user after ${index} images")
                    // Kill any running processes if cancellation requested
                    synchronized(runningProcesses) {
                        runningProcesses.each { proc ->
                            try { proc.destroy() } catch (Exception ex) {}
                        }
                    }
                    return  // Exit the each loop
                }
                
                // Update progress display
                SwingUtilities.invokeLater {
                    progressBar.setValue(index)
                    progressBar.setString("Processing ${index + 1}/${selectedImages.size()}: ${entry.getImageName()}")
                }
                
                masterWriter.println("[${index + 1}/${selectedImages.size()}] Processing: ${entry.getImageName()}")
                println("Processing image ${index + 1}/${selectedImages.size()}: ${entry.getImageName()}")
                
                try {
                    // Process this image
                    def result = processImage(entry, project, config, masterWriter, timestamp, runningProcesses)
                    
                    if (result == 'success') {
                        successCount++
                        masterWriter.println("  Result: SUCCESS")
                    } else if (result == 'skipped') {
                        skippedCount++
                        masterWriter.println("  Result: SKIPPED (No DICOM file)")
                    } else {
                        failCount++
                        masterWriter.println("  Result: FAILED - ${result}")
                    }
                } catch (Exception ex) {
                    failCount++
                    masterWriter.println("  Result: ERROR - ${ex.getMessage()}")
                    ex.printStackTrace(masterWriter)
                    println("Error processing ${entry.getImageName()}: ${ex.getMessage()}")
                }
                
                masterWriter.println("")
                masterWriter.flush()  // Ensure we flush after each image
            }
            
            // Write summary
            masterWriter.println("\n=== SUMMARY ===")
            masterWriter.println("Total images processed: ${selectedImages.size()}")
            masterWriter.println("Successful imports: ${successCount}")
            masterWriter.println("Failed imports: ${failCount}")
            masterWriter.println("Skipped (no DICOM file): ${skippedCount}")
            masterWriter.println("Processing " + (cancelRequested.get() ? "cancelled by user" : "completed"))
            
            // Update UI on EDT
            SwingUtilities.invokeLater {
                progressBar.setValue(selectedImages.size())
                progressBar.setString("Completed: ${successCount} successful, ${failCount} failed, ${skippedCount} skipped")
                
                // Re-enable buttons
                importButton.setEnabled(true)
                cancelButton.setEnabled(true)
                cancelProgressButton.setEnabled(false)
                progressPanel.remove(cancelProgressButton)
                
                // Show completion message
                Dialogs.showPlainMessage("Processing Complete", 
                    "Processed ${selectedImages.size()} images:\n" +
                    "- ${successCount} successful imports\n" +
                    "- ${failCount} failed imports\n" +
                    "- ${skippedCount} skipped (no DICOM file)\n\n" +
                    "Report saved to: ${masterReportFile.getAbsolutePath()}")
                
                // Close the dialog after showing the message
                dialog.dispose()
            }
            
        } catch (Exception ex) {
            masterWriter.println("\nCRITICAL ERROR: " + ex.getMessage())
            ex.printStackTrace(masterWriter)
            
            // Update UI on EDT
            SwingUtilities.invokeLater {
                progressBar.setString("Error: " + ex.getMessage())
                importButton.setEnabled(true)
                cancelButton.setEnabled(true)
                
                // Show error
                Dialogs.showErrorMessage("Processing Error", "Error during batch processing: " + ex.getMessage())
                
                // Close dialog
                dialog.dispose()
            }
        } finally {
            // Final cleanup
            if (masterWriter != null) {
                try { masterWriter.close() } catch (Exception ex) {}
            }
            
            // Make sure all processes are terminated
            synchronized(runningProcesses) {
                runningProcesses.each { proc ->
                    try { proc.destroy() } catch (Exception ex) {}
                }
            }
        }
    }
} as java.awt.event.ActionListener)







// =========================== HELPER FUNCTIONS ===========================

/**
 * Process a single image
 * @return 'success', 'skipped', or error message
 */
def processImage(ProjectImageEntry entry, Project project, Map config, PrintWriter masterWriter, String timestamp, List runningProcesses) {

    // Load the image data
    def imageData = entry.readImageData()
    
    // Get the image file path
    def serverPath = imageData.getServer().getPath()
    def actualPath = serverPath
            .replace("file:/", "")
            .replace("file:\\", "")
            .replace("file:", "")

    // Handle BioFormatsImageServer path format
    if (serverPath.contains("BioFormatsImageServer:")) {
        def matcher = serverPath =~ /file:\/(.+?)\[/
        if (matcher.find()) {
            actualPath = matcher.group(1)
        } else {
            matcher = serverPath =~ /file:(.+?)\[/
            if (matcher.find()) {
                actualPath = matcher.group(1)
            }
        }
    }

    // Get the directory and base file name
    def imageFile = new File(actualPath)
    def directory = imageFile.getParentFile()
    def baseFileName = imageFile.getName()

    masterWriter.println("  Image path: " + imageFile.getAbsolutePath())
    println("Image directory: " + directory.getAbsolutePath())
    println("Image base file name: " + baseFileName)

    // Find DICOM file
    def dicomFile = null
    def dicomFiles = []
    
    // Look for potential DICOM files in the image directory
    if (directory.exists()) {
        // Check for files with .dcm extension or matching the image base name
        directory.listFiles().each { file ->
            if (file.name.endsWith(".dcm") || 
                file.name.contains(baseFileName.replace(".svs", "")) && file.name.contains("graphics")) {
                dicomFiles.add(file)
            }
        }
    }

    // If multiple DICOM files found, use the first one
    if (dicomFiles.size() > 1) {
        dicomFile = dicomFiles[0]
        masterWriter.println("  Found multiple DICOM files, using: " + dicomFile.getName())
    }
    // If only one DICOM file found, use it
    else if (dicomFiles.size() == 1) {
        dicomFile = dicomFiles[0]
        masterWriter.println("  Found DICOM file: " + dicomFile.getName())
    }
    // If no DICOM files found and we're set to skip, return early
    else if (config.skipMissingDicom) {
        masterWriter.println("  No DICOM files found, skipping this image")
        println("No DICOM files found for ${baseFileName}, skipping")
        return 'skipped'
    }
    // Otherwise give an error
    else {
        return "No DICOM files found for this image"
    }

    println("Using DICOM file: " + dicomFile.getAbsolutePath())

    // Create output GeoJSON filename
    def outputFile = new File(directory, baseFileName + ".graphics." + timestamp + ".geojson")
    
    def debugFile = null
    if (config.createReport) {
        debugFile = new File(directory, baseFileName + ".import_report." + timestamp + ".txt")
    }

    println("Output GeoJSON file will be: " + outputFile.getAbsolutePath())

    // Run Python script to convert DICOM to GeoJSON
    println("Running Python script to convert DICOM to GeoJSON")
    
    def cmd = [
        config.pythonExe,
        config.pythonScript,
        "-i", dicomFile.getAbsolutePath(),
        "-o", outputFile.getAbsolutePath(),
        "-s", imageFile.getAbsolutePath()
    ]

    println("Running command: " + cmd.join(" "))
    masterWriter.println("  Command: " + cmd.join(" "))
    
    def writer = config.createReport ? new PrintWriter(debugFile) : null
    
    if (writer) {
        writer.println("=== SECTRA DICOM ANNOTATION IMPORT REPORT ===")
        writer.println("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
        writer.println("Image: " + imageFile.getAbsolutePath())
        writer.println("DICOM: " + dicomFile.getAbsolutePath())
        writer.println("Command: " + cmd.join(" "))
        writer.println("===========================================")
        writer.println()
    }


    try {
        def process = new ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        
        // Add to running processes list
        synchronized(runningProcesses) {
            runningProcesses.add(process)
        }
        
        // Read and print output
        def reader = new BufferedReader(new InputStreamReader(process.getInputStream()))
        def line
        while ((line = reader.readLine()) != null) {
            println(line)
            if (writer) {
                writer.println(line)
            }
        }
        
        def exitCode = process.waitFor()
        println("Python process finished with exit code: " + exitCode)
        
        // Remove from running processes
        synchronized(runningProcesses) {
            runningProcesses.remove(process)
        }


        
        if (exitCode != 0) {
            if (writer) writer.close()
            return "DICOM to GeoJSON conversion failed with exit code: " + exitCode
        }
        
        // Check if output file was created
        if (!outputFile.exists()) {
            if (writer) writer.close()
            return "GeoJSON file was not created"
        }
        
        println("GeoJSON file created successfully!")
        
        // Extract properties from GeoJSON file if needed
        def featureProperties = []
        if (config.applyClassification) {
            featureProperties = extractGeoJSONProperties(outputFile.getAbsolutePath(), writer)
        }
        
        // Import GeoJSON file into QuPath
        println("Importing GeoJSON into QuPath...")
        
        // Create an InputStream from the file
        def inputStream = new FileInputStream(outputFile)
        
        // Import GeoJSON using QuPath's built-in functionality
        def annotations = PathIO.readObjectsFromGeoJSON(inputStream)
        inputStream.close()
        
        println("Found " + annotations.size() + " annotations in GeoJSON file")
        masterWriter.println("  Found " + annotations.size() + " annotations")
        
        // Add all annotations to the current image
        if (annotations && !annotations.isEmpty()) {
            // Apply annotations to hierarchy
            imageData.getHierarchy().addObjects(annotations)
            
            // Apply classifications if we have properties
            if (config.applyClassification && !featureProperties.isEmpty()) {
                println("Applying classifications...")
                
                // Classification tracking
                def classifiedCount = 0
                def labelClasses = [:]
                
                // Only try to apply properties if we found some
                annotations.eachWithIndex { annotation, index ->
                    // Only process if we have properties for this index
                    if (index < featureProperties.size()) {
                        def property = featureProperties[index]
                        
                        if (property) {
                            def label = property.label ?: "Annotation"
                            def geometryType = property.type ?: "UNKNOWN"
                            
                            // Determine if this annotation should be processed based on filter
                            boolean shouldProcess = true
                            if (config.filter && !config.filter.isEmpty()) {
                                def keywords = config.filter.split(",").collect { it.trim().toLowerCase() }
                                def labelLower = label.toString().toLowerCase()
                                shouldProcess = keywords.any { labelLower.contains(it) }
                            }
                            
                            // Only proceed with classification if the annotation matches filter criteria
                            if (shouldProcess) {
                                // Create display name that includes measurements if available
                                def displayName = label
                                if (config.includeMeasurements && property.measurement_value && property.measurement_unit) {
                                    displayName = "${label} [${property.measurement_value} ${property.measurement_unit}]"
                                }
                                
                                // Extract class name
                                def className = ""
                                
                                // Case 1: If label contains measurements, remove them to get the class name
                                if (label.toString().contains("mm")) {
                                    // Handle measurement labels with dashes, em-dashes, etc.
                                    className = label.toString()
                                               .replaceAll(/\s*[\d,.]+\s*(mm²|mm2|mm)\s*.*/, "")
                                               .replaceAll(/\s*[–—-]\s*.*/, "")
                                               .trim()
                                } 
                                // Case 2: Use the label as class name if it's meaningful
                                else if (!label.toString().isEmpty() && label.toString() != "Annotation") {
                                    className = label.toString().trim()
                                } 
                                // Case 3: Use the geometry type as a fallback
                                else {
                                    className = geometryType.toString()
                                }
                                
                                // Skip empty class names
                                if (className.isEmpty()) {
                                    className = "Annotation"
                                }
                                
                                // Get or create the PathClass with the appropriate color scheme
                                def pathClass = findOrCreatePathClass(className, geometryType.toString(), config.colorScheme)
                                annotation.setPathClass(pathClass)
                                
                                // Set display name
                                annotation.setName(displayName.toString())
                                
                                // Add to classified count
                                classifiedCount++
                                
                                // Track classifications for summary
                                if (!labelClasses.containsKey(className)) {
                                    labelClasses[className] = 0
                                }
                                labelClasses[className]++
                            }
                        }
                    }
                }
                
                // Print classification summary
                println("Applied classifications to " + classifiedCount + " annotations")
                masterWriter.println("  Applied classifications to " + classifiedCount + " annotations")
                
                if (writer) {
                    writer.println("\n=== CLASSIFICATION SUMMARY ===")
                    writer.println("Applied classifications to " + classifiedCount + " annotations")
                    labelClasses.each { className, count ->
                        writer.println("  - ${className}: ${count} annotations")
                    }
                }
            }
            
            // Update display
            imageData.getHierarchy().fireHierarchyChangedEvent(null)
            
            // Save the changes to the project
            entry.saveImageData(imageData)
            println("Saved annotations to project")
            
            if (writer) writer.close()
            return 'success'
        } else {
            if (writer) writer.close()
            return "No annotations were found in the GeoJSON file"
        }
    } catch (Exception e) {
        println("Error during import process: " + e.getMessage())
        e.printStackTrace()
        
        if (writer) {
            writer.println("\nERROR: " + e.getMessage())
            e.printStackTrace(writer)
            writer.close()
        }
        
        return "Error: " + e.getMessage()
    }
}

/**
 * Function to find or create a PathClass with appropriate color based on the color scheme
 */
def findOrCreatePathClass(String name, String geometryType, String colorScheme) {
    def pathClass = PathClassFactory.getPathClass(name)

    if (pathClass == null) {
        def color = null
        
        switch (colorScheme) {
            case "Geometry-based":
                // Create colors based on geometry type
                if (geometryType == "CIRCLE") {
                    color = new Color(255, 215, 0)  // Gold
                } else if (geometryType == "POLYLINE") {
                    color = new Color(30, 144, 255) // Dodger Blue
                } else if (geometryType == "POLYGON") {
                    color = new Color(255, 69, 0)   // Orange Red
                } else if (geometryType == "ARROW") {
                    color = new Color(0, 191, 255)  // Deep Sky Blue
                } else if (geometryType == "POINT") {
                    color = new Color(255, 0, 255)  // Magenta
                } else if (geometryType == "RECTANGLE") {
                    color = new Color(255, 105, 180) // Hot Pink
                } else {
                    color = new Color(100, 100, 100) // Default gray
                }
                break
                
            case "Label-based":
                // Color based on common label patterns
                if (name.toLowerCase().contains("adeno")) {
                    color = new Color(220, 20, 60)  // Crimson
                } else if (name.toLowerCase().contains("stroma")) {
                    color = new Color(50, 205, 50)  // Lime Green
                } else if (name.toLowerCase().contains("muscle")) {
                    color = new Color(139, 0, 139)  // Dark Magenta
                } else if (name.toLowerCase().contains("vessel")) {
                    color = new Color(0, 0, 255)    // Blue
                } else if (name.toLowerCase().contains("normal")) {
                    color = new Color(0, 128, 0)    // Green
                } else if (name.toLowerCase().contains("tumor")) {
                    color = new Color(255, 0, 0)    // Red
                } else if (name.toLowerCase().contains("mm") || 
                           name.toLowerCase().contains("measurement")) {
                    color = new Color(255, 165, 0)  // Orange
                } else if (name.toLowerCase().contains("count") || 
                           name.toLowerCase().contains("sayim")) {
                    color = new Color(75, 0, 130)   // Indigo
                } else {
                    // Default color
                    color = new Color(100, 149, 237) // Cornflower Blue
                }
                break
                
            case "Sequential":
                // Use a predefined palette of colors based on class order
                def palette = [
                    new Color(31, 119, 180),   // Blue
                    new Color(255, 127, 14),   // Orange
                    new Color(44, 160, 44),    // Green
                    new Color(214, 39, 40),    // Red
                    new Color(148, 103, 189),  // Purple
                    new Color(140, 86, 75),    // Brown
                    new Color(227, 119, 194),  // Pink
                    new Color(127, 127, 127),  // Gray
                    new Color(188, 189, 34),   // Olive
                    new Color(23, 190, 207)    // Teal
                ]
                
                // Use hashCode to get a consistent index for the same class name
                def index = Math.abs(name.hashCode()) % palette.size()
                color = palette[index]
                break
                
            case "Random":
                // Generate consistent random color based on string hash
                def hash = name.hashCode()
                def r = (hash & 0xFF0000) >> 16
                def g = (hash & 0x00FF00) >> 8
                def b = hash & 0x0000FF
                
                // Make sure colors aren't too dark or light
                r = Math.max(r, 30)
                g = Math.max(g, 30)
                b = Math.max(b, 30)
                
                // Avoid too-light colors
                if (r > 200 && g > 200 && b > 200) {
                    r = Math.min(r, 180)
                    g = Math.min(g, 180)
                    b = Math.min(b, 180)
                }
                
                color = new Color(r, g, b)
                break
                
            case "Custom":
                // For custom, we would ideally have a color map defined
                // For now, use a base color and vary it slightly
                color = new Color(0, 120, 174) // Base blue
                def hash = name.hashCode()
                
                // Adjust hue based on hash
                float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)
                hsb[0] = (hsb[0] + (hash % 10) / 20.0f) % 1.0f // Vary hue by up to 0.5
                color = Color.getHSBColor(hsb[0], hsb[1], hsb[2])
                break
                
            default:
                // Default coloring
                color = new Color(100, 149, 237) // Cornflower Blue
        }
        
        pathClass = PathClassFactory.getPathClass(name, color)
    }
    
    return pathClass
}

/**
 * Extract property information from GeoJSON
 */
def extractGeoJSONProperties(geojsonPath, writer = null) {
    println("Extracting properties from GeoJSON: " + geojsonPath)
    def properties = []
    
    try {
        // Read the GeoJSON file line by line
        def reader = new BufferedReader(new FileReader(geojsonPath))
        def line
        def jsonStr = new StringBuilder()
        
        // First read the entire file (may need to limit for very large files)
        while ((line = reader.readLine()) != null) {
            jsonStr.append(line).append("\n")
        }
        reader.close()
        
        if (writer) {
            writer.println("\n=== GEOJSON PROPERTIES EXTRACTION ===")
        }
        
        // Now extract properties sections using simple string searches
        def fullText = jsonStr.toString()
        def currentPos = 0
        def propIndex = 0
        
        while (true) {
            // Find the next properties block
            def propStart = fullText.indexOf('"properties":', currentPos)
            if (propStart == -1) {
                break
            }
            
            // Find the opening brace after "properties":
            def braceStart = fullText.indexOf('{', propStart)
            if (braceStart == -1) {
                currentPos = propStart + 1
                continue
            }
            
            // Find the matching closing brace
            def braceCount = 1
            def braceEnd = braceStart + 1
            while (braceCount > 0 && braceEnd < fullText.length()) {
                if (fullText.charAt(braceEnd) == '{') {
                    braceCount++
                } else if (fullText.charAt(braceEnd) == '}') {
                    braceCount--
                }
                braceEnd++
            }
            
            if (braceCount == 0) {
                def propsJson = fullText.substring(braceStart, braceEnd)
                propIndex++
                
                if (writer) {
                    writer.println("\n--- PROPERTY #${propIndex} ---")
                    writer.println(propsJson)
                }
                
                // Extract key properties
                def property = [:]
                
                // Extract type
                def typeStart = propsJson.indexOf('"type"')
                if (typeStart != -1) {
                    def typeValueStart = propsJson.indexOf('"', typeStart + 7) // After "type":"
                    if (typeValueStart != -1) {
                        def typeValueEnd = propsJson.indexOf('"', typeValueStart + 1)
                        if (typeValueEnd != -1) {
                            property.type = propsJson.substring(typeValueStart + 1, typeValueEnd)
                            if (writer) writer.println("TYPE: ${property.type}")
                        }
                    }
                }
                
                // Extract label
                def labelStart = propsJson.indexOf('"label"')
                if (labelStart != -1) {
                    def labelValueStart = propsJson.indexOf('"', labelStart + 8) // After "label":"
                    if (labelValueStart != -1) {
                        def labelValueEnd = propsJson.indexOf('"', labelValueStart + 1)
                        if (labelValueEnd != -1) {
                            property.label = propsJson.substring(labelValueStart + 1, labelValueEnd)
                            if (writer) writer.println("LABEL: ${property.label}")
                        }
                    }
                }
                
                // Extract measurement_value (numeric)
                def valueStart = propsJson.indexOf('"measurement_value"')
                if (valueStart != -1) {
                    def numStart = propsJson.indexOf(':', valueStart) + 1
                    if (numStart != 0) { // Not -1 + 1
                        def numEnd = propsJson.indexOf(',', numStart)
                        if (numEnd == -1) {
                            numEnd = propsJson.indexOf('}', numStart)
                        }
                        if (numEnd != -1) {
                            def numStr = propsJson.substring(numStart, numEnd).trim()
                            property.measurement_value = numStr
                            if (writer) writer.println("VALUE: ${property.measurement_value}")
                        }
                    }
                }
                
                // Extract measurement_unit
                def unitStart = propsJson.indexOf('"measurement_unit"')
                if (unitStart != -1) {
                    def unitValueStart = propsJson.indexOf('"', unitStart + 18) // After "measurement_unit":"
                    if (unitValueStart != -1) {
                        def unitValueEnd = propsJson.indexOf('"', unitValueStart + 1)
                        if (unitValueEnd != -1) {
                            property.measurement_unit = propsJson.substring(unitValueStart + 1, unitValueEnd)
                            if (writer) writer.println("UNIT: ${property.measurement_unit}")
                        }
                    }
                }
                
                // Add to properties list
                properties.add(property)
                
                // Move current position past this block
                currentPos = braceEnd
            } else {
                // If we couldn't find a matching brace, move past the current position
                currentPos = propStart + 1
            }
        }
        
        if (writer) {
            writer.println("\nTotal property sections found: ${properties.size()}")
        }
        println("Extracted ${properties.size()} property sections")
        
    } catch (Exception e) {
        println("Error extracting properties: " + e.getMessage())
        if (writer) {
            writer.println("ERROR extracting properties: " + e.getMessage())
            e.printStackTrace(writer)
        }
    }
    
    return properties
}

// =========================== LAUNCH DIALOG ===========================

// Try to set reasonable defaults for Python executable
def pythonExe = "python"
if (System.getProperty("os.name").toLowerCase().contains("win")) {
    // Try to find Python in standard locations on Windows
    def potentialPaths = [
        "C:\\Python310\\python.exe",
        "C:\\Python39\\python.exe",
        "C:\\Python38\\python.exe",
        "C:\\Users\\${System.getProperty('user.name')}\\AppData\\Local\\Programs\\Python\\Python310\\python.exe"
    ]
    
    for (path in potentialPaths) {
        def file = new File(path)
        if (file.exists()) {
            pythonExe = file.getAbsolutePath()
            break
        }
    }
}
pythonExeField.setText(pythonExe)

// Try to locate Python script in project directory
def scriptPath = "sectra_dicom_to_qupath.py"
def projectDir = project.getPath().getParent().toFile()
def scriptFile = new File(projectDir, "sectra_dicom_to_qupath.py")
if (scriptFile.exists()) {
    scriptPath = scriptFile.getAbsolutePath()
}
pythonScriptField.setText(scriptPath)

// Pack and display the dialog
dialog.pack()
dialog.setLocationRelativeTo(null)
dialog.setSize(new Dimension(
    Math.max(600, dialog.getWidth()),
    Math.max(650, dialog.getHeight())
))
dialog.setVisible(true)