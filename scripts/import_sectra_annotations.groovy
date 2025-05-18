/**
 * QuPath GeoJSON Importer
 * 
 * This script uses QuPath's built-in GeoJSON import functionality
 */

import qupath.lib.images.servers.ImageServer
import qupath.lib.objects.PathObjects
import qupath.lib.io.GsonTools
import qupath.lib.io.PathIO
import java.io.File
import java.io.FileInputStream

// Get current image data
def imageData = getCurrentImageData()
if (imageData == null) {
    print("Error: No image is open!")
    return
}

// Extract the file path from BioFormatsImageServer format
def serverPath = imageData.getServer().getPath()
print("Raw server path: " + serverPath)

// Handle BioFormatsImageServer path format
def actualPath = serverPath
if (serverPath.contains("BioFormatsImageServer:")) {
    // Extract the file path part
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

// Clean up the path
actualPath = actualPath
    .replace("file:/", "")
    .replace("file:\\", "")
    .replace("file:", "")

print("Actual file path: " + actualPath)

// Get the directory and base file name
def svsFile = new File(actualPath)
def directory = svsFile.getParentFile()
def baseFileName = svsFile.getName()

print("SVS directory: " + directory.getAbsolutePath())
print("SVS base file name: " + baseFileName)

// Look for matching GeoJSON files
def geojsonFile = null
if (directory.exists()) {
    def files = directory.listFiles()
    
    // Look for GeoJSON files matching the pattern
    for (file in files) {
        if (file.getName().endsWith(".geojson") && file.getName().startsWith(baseFileName)) {
            geojsonFile = file
            print("Found GeoJSON file: " + file.getName())
            break
        }
    }
    
    // If no exact match, look for files containing the base name
    if (geojsonFile == null) {
        for (file in files) {
            if (file.getName().endsWith(".geojson") && file.getName().contains(baseFileName.replace(".svs", ""))) {
                geojsonFile = file
                print("Found GeoJSON file by partial match: " + file.getName())
                break
            }
        }
    }
}

if (geojsonFile == null) {
    print("No GeoJSON files found in " + directory.getAbsolutePath())
    return
}

print("Using GeoJSON file: " + geojsonFile.getAbsolutePath())

// Use QuPath's built-in GeoJSON import
try {
    // Create an InputStream from the file
    def inputStream = new FileInputStream(geojsonFile)
    
    // Import GeoJSON using QuPath's built-in functionality
    def annotations = PathIO.readObjectsFromGeoJSON(inputStream)
    inputStream.close()
    
    print("Found " + annotations.size() + " annotations in GeoJSON file")
    
    // Add all annotations to the current image
    if (annotations) {
        // Use addObjects instead of addPathObjects
        imageData.getHierarchy().addObjects(annotations)
        print("Successfully imported " + annotations.size() + " annotations")
    } else {
        print("No annotations were found in the GeoJSON file")
    }
    
    // Update display
    imageData.getHierarchy().fireHierarchyChangedEvent(this)
} catch (Exception e) {
    print("Error importing GeoJSON: " + e.getMessage())
    e.printStackTrace()
}