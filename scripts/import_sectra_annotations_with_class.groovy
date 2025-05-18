/**
 * Sectra DICOM Annotation Importer for QuPath with Simple Property Extraction
 * 
 * This script imports Sectra PACS DICOM annotations into QuPath and
 * extracts annotation properties using basic string operations.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.gui.prefs.PathPrefs
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.images.servers.ImageServer
import qupath.lib.projects.Project
import qupath.lib.projects.ProjectIO
import java.awt.Color

import java.awt.Desktop
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

// Configuration - modify these as needed
def pythonPath = "D:\\DigitalPathologyDrafts\\.venv\\Scripts\\python.exe"  // Path to Python executable
def scriptName = "sectra_dicom_to_qupath.py"  // Name of the Python script in the project folder
def applyClassifications = true  // Set to false if you don't want to apply classifications from labels

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

// Helper function to create RGB color
def getColorRGB(int r, int g, int b) {
    return new Color(r, g, b)
}

// Function to find or create a PathClass with appropriate color
def findOrCreatePathClass(String name, String geometryType) {
    def pathClass = PathClassFactory.getPathClass(name)

    if (pathClass == null) {
        // Create colors based primarily on geometry type, then label patterns
        def color = null
        
        // First color by geometry type
        if (geometryType == "CIRCLE") {
            color = getColorRGB(255, 215, 0)  // Gold
        } else if (geometryType == "POLYLINE") {
            color = getColorRGB(30, 144, 255) // Dodger Blue
        } else if (geometryType == "POLYGON") {
            color = getColorRGB(255, 69, 0)   // Orange Red
        } else if (geometryType == "ARROW") {
            color = getColorRGB(0, 191, 255)  // Deep Sky Blue
        } else if (geometryType == "POINT") {
            color = getColorRGB(255, 0, 255)  // Magenta
        } else if (geometryType == "RECTANGLE") {
            color = getColorRGB(255, 105, 180) // Hot Pink
        } 
        // Then check for common tissue/structure patterns in the name
        else if (name.toLowerCase().contains("adeno")) {
            color = getColorRGB(220, 20, 60)  // Crimson
        } else if (name.toLowerCase().contains("stroma")) {
            color = getColorRGB(50, 205, 50)  // Lime Green
        } else if (name.toLowerCase().contains("muscle")) {
            color = getColorRGB(139, 0, 139)  // Dark Magenta
        } else if (name.toLowerCase().contains("vessel")) {
            color = getColorRGB(0, 0, 255)    // Blue
        } else if (name.toLowerCase().contains("normal")) {
            color = getColorRGB(0, 128, 0)    // Green
        } else if (name.toLowerCase().contains("tumor")) {
            color = getColorRGB(255, 0, 0)    // Red
        } 
        // Check for measurement or count patterns
        else if (name.toLowerCase().contains("mm")) {
            color = getColorRGB(255, 165, 0)  // Orange (measurement)
        } else if (name.toLowerCase().contains("count")) {
            color = getColorRGB(75, 0, 130)   // Indigo (count)
        } else if (name.toLowerCase().contains("sayim")) {
            color = getColorRGB(75, 0, 130)   // Indigo (count in Turkish)
        } else {
            // Generate consistent color based on string hash
            def hash = name.hashCode()
            def r = (hash & 0xFF0000) >> 16
            def g = (hash & 0x00FF00) >> 8
            def b = hash & 0x0000FF
            
            // Make sure colors aren't too dark or light
            r = Math.max(r, 50)
            g = Math.max(g, 50)
            b = Math.max(b, 50)
            r = Math.min(r, 220)
            g = Math.min(g, 220)
            b = Math.min(b, 220)
            
            color = getColorRGB(r, g, b)
        }
        pathClass = PathClassFactory.getPathClass(name, color)
    }
    return pathClass
}

// Simple function to extract property information from GeoJSON
def extractGeoJSONProperties(geojsonPath) {
    println("Extracting properties from GeoJSON: " + geojsonPath)
    def properties = []
    def debugFile = new File(new File(geojsonPath).getParent(), "debug_geojson_output.txt")
    def writer = new PrintWriter(debugFile)
    
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
        
        writer.println("=== FIRST 1000 CHARACTERS OF GEOJSON FILE ===")
        writer.println(jsonStr.toString().take(1000))
        writer.println("============================================")
        writer.println()
        
        // Now extract properties sections using simple string searches
        def fullText = jsonStr.toString()
        def currentPos = 0
        def propIndex = 0
        
        writer.println("=== EXTRACTING PROPERTIES SECTIONS ===")
        
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
                
                writer.println("--- PROPERTY SECTION #${propIndex} ---")
                writer.println(propsJson)
                
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
                            writer.println("TYPE: ${property.type}")
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
                            writer.println("LABEL: ${property.label}")
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
                            writer.println("MEASUREMENT_VALUE: ${property.measurement_value}")
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
                            writer.println("MEASUREMENT_UNIT: ${property.measurement_unit}")
                        }
                    }
                }
                
                // Add to properties list
                properties.add(property)
                writer.println("--------------------------")
                
                // Move current position past this block
                currentPos = braceEnd
            } else {
                // If we couldn't find a matching brace, move past the current position
                currentPos = propStart + 1
            }
        }
        
        writer.println("Total property sections found: ${properties.size()}")
        println("Extracted ${properties.size()} property sections")
        
    } catch (Exception e) {
        writer.println("ERROR during extraction: " + e.getMessage())
        e.printStackTrace(writer)
        println("Error extracting properties: " + e.getMessage())
    } finally {
        writer.close()
    }
    
    return properties
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

// STEP 5: Extract properties from GeoJSON file
println("\n--- STEP 5: EXTRACTING GEOJSON PROPERTIES ---")
def featureProperties = extractGeoJSONProperties(outputFile.getAbsolutePath())

// STEP 6: Import GeoJSON file into QuPath
println("\n--- STEP 6: IMPORTING GEOJSON INTO QUPATH ---")

// Initialize classification counter
def classifiedCount = 0
def labelClasses = [:]

try {
    // Create an InputStream from the file
    def inputStream = new FileInputStream(outputFile)
    
    // Import GeoJSON using QuPath's built-in functionality
    def annotations = PathIO.readObjectsFromGeoJSON(inputStream)
    inputStream.close()
    
    println("Found " + annotations.size() + " annotations in GeoJSON file")
    
    // Add all annotations to the current image
    if (annotations && !annotations.isEmpty()) {
        // Apply annotations to hierarchy
        imageData.getHierarchy().addObjects(annotations)

        // Apply classifications if we have properties
        if (applyClassifications && !featureProperties.isEmpty()) {
            println("\n--- STEP 7: APPLYING CLASSIFICATIONS FROM GEOJSON LABELS ---")
            
            // Only try to apply properties if we found some
            annotations.eachWithIndex { annotation, index ->
                // Only process if we have properties for this index
                if (index < featureProperties.size()) {
                    def property = featureProperties[index]
                    
                    if (property) {
                        def label = property.label ?: "Annotation"
                        def geometryType = property.type ?: "UNKNOWN"
                        
                        // Create display name that includes measurements if available
                        def displayName = label
                        if (property.measurement_value && property.measurement_unit) {
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
                        
                        // Get or create the PathClass, passing the geometry type for better color selection
                        def pathClass = findOrCreatePathClass(className, geometryType.toString())
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
                        
                        println("Applied class ${className} to annotation ${annotation.getID()}")
                    }
                }
            }
            
            // Print classification summary
            println("Applied classifications to " + classifiedCount + " annotations")
            println("Classification summary:")
            labelClasses.each { className, count ->
                println("  - ${className}: ${count} annotations")
            }
        }
        
        // Update display
        imageData.getHierarchy().fireHierarchyChangedEvent(this)
        
        // Show success message
        Dialogs.showPlainMessage("Success", 
            "Successfully imported " + annotations.size() + " annotations from DICOM file.\n" +
            (classifiedCount > 0 ? "Applied classifications to " + classifiedCount + " annotations.\n" : "") +
            "GeoJSON file saved to: " + outputFile.getAbsolutePath() + "\n" +
            "Debug file created to analyze GeoJSON structure.")
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