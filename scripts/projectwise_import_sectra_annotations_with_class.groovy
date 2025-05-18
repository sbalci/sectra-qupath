/**
 * Batch Sectra DICOM Annotation Importer for QuPath
 * 
 * This script processes all images in a project, finding corresponding DICOM files
 * and importing annotations with proper classifications.
 */

import qupath.lib.gui.dialogs.Dialogs
import qupath.lib.gui.QuPathGUI
import qupath.lib.io.PathIO
import qupath.lib.objects.PathObjects
import qupath.lib.objects.classes.PathClassFactory
import qupath.lib.projects.ProjectImageEntry
import qupath.lib.images.ImageData
import java.awt.Color

import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import java.io.File
import java.io.FileInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

// ============ MAIN EXECUTION BEGINS HERE ============

// Configuration parameters
def PYTHON_PATH = "D:\\DigitalPathologyDrafts\\.venv\\Scripts\\python.exe"  // Path to Python executable
def PYTHON_SCRIPT_NAME = "sectra_dicom_to_qupath.py"  // Name of the Python script in the project folder
def APPLY_CLASSIFICATIONS = true  // Set to false if you don't want to apply classifications from labels

// Get the GUI and project
def gui = QuPathGUI.getInstance()
if (gui == null) {
    Dialogs.showErrorMessage("Error", "Cannot find QuPath GUI instance. Please run from within QuPath.")
    return
}

def project = gui.getProject()
if (project == null) {
    Dialogs.showErrorMessage("Error", "No project is open. Please open a project first.")
    return
}

// Initialize reporting
def startTime = System.currentTimeMillis()
def projectPath = project.getPath()
def projectDir = projectPath.getParent().toFile()
def timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date())
def reportPath = new File(projectDir, "sectra_import_report_${timestamp}.txt").getAbsolutePath()
def reportWriter = new PrintWriter(reportPath)

reportWriter.println("=== SECTRA DICOM ANNOTATION IMPORT REPORT ===")
reportWriter.println("Date: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
reportWriter.println("Project: " + projectPath.toString())
reportWriter.println("===========================================")
reportWriter.println()

try {
    // Look for Python script in project directory
    def scriptFile = new File(projectDir, PYTHON_SCRIPT_NAME)
    if (!scriptFile.exists()) {
        reportWriter.println("Python script not found in project directory. Prompting user to select.")
        println("Python script not found in project directory: " + projectDir.getAbsolutePath())
        
        // Prompt the user to select the script
        def fileChooser = new JFileChooser()
        fileChooser.setDialogTitle("Select " + PYTHON_SCRIPT_NAME)
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY)
        fileChooser.setMultiSelectionEnabled(false)
        fileChooser.setFileFilter(new FileNameExtensionFilter("Python Scripts (*.py)", "py"))
        fileChooser.setCurrentDirectory(projectDir)
        
        def result = fileChooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) {
            scriptFile = fileChooser.getSelectedFile()
        } else {
            reportWriter.println("ERROR: No Python script selected. Aborting.")
            reportWriter.close()
            Dialogs.showErrorMessage("Error", "No Python script selected. Aborting.")
            return
        }
    }
    
    reportWriter.println("Using Python script: " + scriptFile.getAbsolutePath())
    println("Using Python script: " + scriptFile.getAbsolutePath())
    
    // Get list of images in the project
    def imageList = project.getImageList()
    def totalImages = imageList.size()
    
    reportWriter.println("Found ${totalImages} images in project")
    println("Found ${totalImages} images in project")
    
    if (totalImages == 0) {
        reportWriter.println("ERROR: No images found in project")
        reportWriter.close()
        Dialogs.showErrorMessage("Error", "No images found in project")
        return
    }
    
    // Confirm with user
    if (!Dialogs.showConfirmDialog("Batch Processing", 
            "Process all ${totalImages} images in the project?\n" +
            "This will look for DICOM files with matching names and import annotations.")) {
        reportWriter.println("User cancelled batch processing")
        reportWriter.close()
        println("User cancelled batch processing")
        return
    }
    
    // Statistics tracking
    def successfulImages = 0
    def totalAnnotations = 0
    def totalClassified = 0
    
    // Process each image
    reportWriter.println("\n=== PROCESSING IMAGES ===\n")
    
    for (int i = 0; i < imageList.size(); i++) {
        def entry = imageList.get(i)
        reportWriter.println("[${i+1}/${totalImages}] ${entry.getImageName()}")
        println("\n=== IMAGE ${i+1}/${totalImages}: ${entry.getImageName()} ===")
        
        try {
            // Load the image data
            def imageData = entry.readImageData()
            
            // Get the image server path
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
            
            def imageFile = new File(actualPath)
            def directory = imageFile.getParentFile()
            def baseFileName = imageFile.getName()
            
            // Find potential DICOM files
            def dicomFiles = []
            if (directory && directory.exists()) {
                // Check for files with .dcm extension or matching the image base name
                directory.listFiles().each { file ->
                    if (file.name.endsWith(".dcm") || 
                        file.name.contains(baseFileName.replace(".svs", "")) && file.name.contains("graphics")) {
                        dicomFiles.add(file)
                    }
                }
            }
            
            if (dicomFiles.isEmpty()) {
                reportWriter.println("  No DICOM files found for ${baseFileName}")
                println("No DICOM files found for ${baseFileName}")
                continue
            }
            
            // Use the first DICOM file found
            def dicomFile = dicomFiles[0]
            reportWriter.println("  Using DICOM file: " + dicomFile.getName())
            println("Using DICOM file: " + dicomFile.getAbsolutePath())
            
            // Generate output GeoJSON path
            def outputFile = new File(directory, baseFileName + ".graphics." + 
                              new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date()) + 
                              ".geojson")
            
            reportWriter.println("  Output GeoJSON: " + outputFile.getName())
            println("Output GeoJSON will be: " + outputFile.getAbsolutePath())
            
            // Run Python script to convert DICOM to GeoJSON
            reportWriter.println("  Converting DICOM to GeoJSON...")
            println("Converting DICOM to GeoJSON...")
            
            def cmd = [
                PYTHON_PATH,
                scriptFile.getAbsolutePath(),
                "-i", dicomFile.getAbsolutePath(),
                "-o", outputFile.getAbsolutePath(),
                "-s", imageFile.getAbsolutePath()
            ]
            
            def process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            
            // Read and print output
            def reader = new BufferedReader(new InputStreamReader(process.getInputStream()))
            def line
            while ((line = reader.readLine()) != null) {
                // Only print key lines to reduce log clutter
                if (line.contains("error") || line.contains("GeoJSON") || line.contains("dimensions")) {
                    println(line)
                }
            }
            
            def exitCode = process.waitFor()
            if (exitCode != 0) {
                reportWriter.println("  ERROR: DICOM to GeoJSON conversion failed with exit code: ${exitCode}")
                println("DICOM to GeoJSON conversion failed with exit code: ${exitCode}")
                continue
            }
            
            // Check if output file was created
            if (!outputFile.exists()) {
                reportWriter.println("  ERROR: GeoJSON file was not created")
                println("GeoJSON file was not created")
                continue
            }
            
            reportWriter.println("  GeoJSON file created successfully")
            println("GeoJSON file created successfully!")
            
            // Extract properties from GeoJSON file
            def featureProperties = extractGeoJSONProperties(outputFile.getAbsolutePath())
            reportWriter.println("  Extracted ${featureProperties.size()} property sections")
            
            // Import GeoJSON file into QuPath
            reportWriter.println("  Importing GeoJSON into QuPath...")
            println("Importing GeoJSON into QuPath...")
            
            // Create an InputStream from the file
            def inputStream = new FileInputStream(outputFile)
            
            // Import GeoJSON using QuPath's built-in functionality
            def annotations = PathIO.readObjectsFromGeoJSON(inputStream)
            inputStream.close()
            
            reportWriter.println("  Found ${annotations.size()} annotations in GeoJSON file")
            println("Found " + annotations.size() + " annotations in GeoJSON file")
            
            // Add all annotations to the image
            if (annotations && !annotations.isEmpty()) {
                // Apply annotations to hierarchy
                imageData.getHierarchy().addObjects(annotations)
                
                // Apply classifications if we have properties
                if (APPLY_CLASSIFICATIONS && !featureProperties.isEmpty()) {
                    reportWriter.println("  Applying classifications...")
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
                                
                                // Get or create the PathClass
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
                            }
                        }
                    }
                    
                    totalClassified += classifiedCount
                    
                    // Print classification summary
                    reportWriter.println("  Applied classifications to ${classifiedCount} annotations")
                    println("Applied classifications to " + classifiedCount + " annotations")
                    reportWriter.println("  Classification summary:")
                    labelClasses.each { className, count ->
                        reportWriter.println("    - ${className}: ${count} annotations")
                        println("  - ${className}: ${count} annotations")
                    }
                }
                
                // Update display
                imageData.getHierarchy().fireHierarchyChangedEvent(null)
                
                // Write the data to the project
                entry.saveImageData(imageData)
                reportWriter.println("  Saved changes to project")
                println("Saved changes to project")
                
                // Update statistics
                successfulImages++
                totalAnnotations += annotations.size()
            } else {
                reportWriter.println("  WARNING: No annotations were found in the GeoJSON file")
                println("Warning: No annotations were found in the GeoJSON file")
            }
        } catch (Exception e) {
            reportWriter.println("  ERROR: " + e.getMessage())
            println("Error processing image: " + e.getMessage())
            e.printStackTrace()
        }
        
        reportWriter.println("")
    }
    
    // Write summary
    def endTime = System.currentTimeMillis()
    def totalTimeSeconds = (endTime - startTime) / 1000.0
    
    reportWriter.println("\n=== SUMMARY ===")
    reportWriter.println("Total images processed: ${totalImages}")
    reportWriter.println("Successfully processed: ${successfulImages}")
    reportWriter.println("Failed: ${totalImages - successfulImages}")
    reportWriter.println("Total annotations imported: ${totalAnnotations}")
    reportWriter.println("Total annotations classified: ${totalClassified}")
    reportWriter.println("Total processing time: ${totalTimeSeconds} seconds")
    
    // Show final message
    Dialogs.showPlainMessage("Batch Processing Complete", 
        "Processed ${totalImages} images\n" +
        "Successfully imported annotations for ${successfulImages} images\n" +
        "Total annotations imported: ${totalAnnotations}\n" +
        "Report saved to: ${reportPath}")
        
} catch (Exception e) {
    def message = "Error during batch processing: " + e.getMessage()
    if (reportWriter != null) {
        reportWriter.println("\nERROR: " + message)
        e.printStackTrace(reportWriter)
    }
    println(message)
    e.printStackTrace()
    Dialogs.showErrorMessage("Batch Processing Error", message)
} finally {
    if (reportWriter != null) {
        reportWriter.close()
    }
}

println("\n--- BATCH PROCESSING COMPLETED ---")

// ============ HELPER FUNCTIONS ============

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