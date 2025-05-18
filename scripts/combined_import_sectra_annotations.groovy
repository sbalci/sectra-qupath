/**
 * Sectra DICOM Annotation Importer for QuPath
 * 
 * This script provides a user-friendly interface to import Sectra PACS DICOM annotations
 * directly into QuPath by:
 * 1. Finding the appropriate DICOM file (or allowing user to select it)
 * 2. Converting it to GeoJSON using the sectra_dicom_to_qupath.py script
 * 3. Importing the generated GeoJSON into the current image
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
import qupath.lib.images.servers.ImageServer
import qupath.lib.projects.Project
import qupath.lib.projects.ProjectIO

import java.awt.Desktop
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

// Configuration - modify these as needed
def pythonPath = "D:\\DigitalPathologyDrafts\\.venv\\Scripts\\python.exe"  // Path to Python executable
def scriptName = "sectra_dicom_to_qupath.py"  // Name of the Python script in the project folder

// Get current image data
def imageData = getCurrentImageData()
if (imageData == null) {
    Dialogs.showErrorMessage("Error", "No image is open! Please open an image first.")
    return
}

// Function to prompt user to select a file
def promptForFile(title, description, extensions, startDir = null) {
    def fileChooser = new JFileChooser()
    fileChooser.setDialogTitle(title)
    fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY)
    fileChooser.setMultiSelectionEnabled(false)
    
    if (extensions && extensions.length > 0) {
        def filter = new FileNameExtensionFilter(description, extensions)
        fileChooser.setFileFilter(filter)
    }
    
    // Set initial directory if provided
    if (startDir && startDir.exists()) {
        fileChooser.setCurrentDirectory(startDir)
    }
    
    def result = fileChooser.showOpenDialog(null)
    if (result == JFileChooser.APPROVE_OPTION) {
        return fileChooser.getSelectedFile()
    }
    return null
}

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

println("Image directory: " + directory.getAbsolutePath())
println("Image base file name: " + baseFileName)

// STEP 1: Find the Python script in the project directory
println("\n--- STEP 1: LOCATING PYTHON SCRIPT ---")

// Try to get the current QuPath project
def scriptFile = null
def gui = QuPathGUI.getInstance()
def projectDir = null

if (gui != null) {
    def project = gui.getProject()
    if (project != null) {
        try {
            projectDir = project.getPath().getParent().toFile()
            println("Project directory: " + projectDir.getAbsolutePath())
            
            // Look for the script in the project directory
            scriptFile = new File(projectDir, scriptName)
            if (scriptFile.exists()) {
                println("Found Python script in project directory: " + scriptFile.getAbsolutePath())
            } else {
                println("Python script not found in project directory")
                scriptFile = null
            }
        } catch (Exception e) {
            println("Could not determine project directory: " + e.getMessage())
        }
    } else {
        println("No project is currently open")
    }
} else {
    println("Could not access QuPath GUI instance")
}

// If script not found in project directory, prompt the user
if (scriptFile == null || !scriptFile.exists()) {
    println("Prompting user to select Python script")
    
    scriptFile = promptForFile(
        "Select " + scriptName,
        "Python Scripts (*.py)",
        ["py"] as String[],
        projectDir != null ? projectDir : directory
    )
    
    if (scriptFile == null) {
        Dialogs.showErrorMessage("Error", "No Python script selected. Aborting.")
        return
    }
}

println("Using Python script: " + scriptFile.getAbsolutePath())

// STEP 2: Find or prompt for DICOM file
println("\n--- STEP 2: LOCATING DICOM FILE ---")

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

// If multiple DICOM files found, let user select one
if (dicomFiles.size() > 1) {
    def options = dicomFiles.collect { it.getName() }
    def selected = Dialogs.showChoiceDialog("Select DICOM file", "Multiple DICOM files found. Please select one:", options, options[0])
    if (selected == null) {
        Dialogs.showErrorMessage("Error", "No DICOM file selected. Aborting.")
        return
    }
    dicomFile = dicomFiles[options.indexOf(selected)]
}
// If only one DICOM file found, use it
else if (dicomFiles.size() == 1) {
    dicomFile = dicomFiles[0]
    if (!Dialogs.showConfirmDialog("Confirm DICOM file", "Use DICOM file: " + dicomFile.getName() + "?")) {
        dicomFile = null
    }
}

// If no DICOM files found or user declined the found one, prompt for selection
if (dicomFile == null) {
    dicomFile = promptForFile(
        "Select DICOM annotation file",
        "DICOM Files (*.dcm)",
        ["dcm"] as String[],
        directory
    )
    if (dicomFile == null) {
        Dialogs.showErrorMessage("Error", "No DICOM file selected. Aborting.")
        return
    }
}

println("Using DICOM file: " + dicomFile.getAbsolutePath())

// STEP 3: Generate output GeoJSON path
println("\n--- STEP 3: SETTING UP OUTPUT PATHS ---")

// Create output GeoJSON filename based on image filename
def outputFile = new File(directory, baseFileName + ".graphics." + 
                          new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + 
                          ".geojson")

println("Output GeoJSON file will be: " + outputFile.getAbsolutePath())

// STEP 4: Run Python script to convert DICOM to GeoJSON
println("\n--- STEP 4: CONVERTING DICOM TO GEOJSON ---")

def cmd = [
    pythonPath,
    scriptFile.getAbsolutePath(),
    "-i", dicomFile.getAbsolutePath(),
    "-o", outputFile.getAbsolutePath(),
    "-s", imageFile.getAbsolutePath()
]

println("Running command: " + cmd.join(" "))

try {
    def process = new ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    
    // Read and print output
    def reader = new BufferedReader(new InputStreamReader(process.getInputStream()))
    def line
    while ((line = reader.readLine()) != null) {
        println(line)
    }
    
    def exitCode = process.waitFor()
    println("Python process finished with exit code: " + exitCode)
    
    if (exitCode != 0) {
        Dialogs.showErrorMessage("Error", "DICOM to GeoJSON conversion failed. Check the log for details.")
        return
    }
    
    // Check if output file was created
    if (!outputFile.exists()) {
        Dialogs.showErrorMessage("Error", "GeoJSON file was not created. Check the log for details.")
        return
    }
    
    println("GeoJSON file created successfully!")
} catch (Exception e) {
    Dialogs.showErrorMessage("Error", "Failed to run Python script: " + e.getMessage())
    println("Error details: " + e.getMessage())
    e.printStackTrace()
    return
}


// STEP 5: Import GeoJSON file into QuPath
println("\n--- STEP 5: IMPORTING GEOJSON INTO QUPATH ---")

try {
    // Create an InputStream from the file
    def inputStream = new FileInputStream(outputFile)
    
    // Import GeoJSON using QuPath's built-in functionality
    def annotations = PathIO.readObjectsFromGeoJSON(inputStream)
    inputStream.close()
    
    println("Found " + annotations.size() + " annotations in GeoJSON file")
    
    // Add all annotations to the current image
    if (annotations && !annotations.isEmpty()) {
        // Use addObjects instead of addPathObjects
        imageData.getHierarchy().addObjects(annotations)
        println("Successfully imported " + annotations.size() + " annotations")
        
        // Update display
        imageData.getHierarchy().fireHierarchyChangedEvent(this)
        
        // Fixed: Use showPlainMessage instead of showInfoMessage
        Dialogs.showPlainMessage("Success", 
            "Successfully imported " + annotations.size() + " annotations from DICOM file.\n" +
            "GeoJSON file saved to: " + outputFile.getAbsolutePath())
    } else {
        Dialogs.showMessageDialog("Warning", "No annotations were found in the GeoJSON file")
        println("No annotations were found in the GeoJSON file")
    }
} catch (Exception e) {
    Dialogs.showErrorMessage("Error", "Failed to import GeoJSON: " + e.getMessage())
    println("Error importing GeoJSON: " + e.getMessage())
    e.printStackTrace()
}



println("\n--- PROCESS COMPLETED ---")