# Using Sectra PACS Annotations in QuPath: A Comprehensive Guide

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.15542395.svg)](https://doi.org/10.5281/zenodo.15542395)  

[OSF DOI 10.17605/OSF.IO/5KR7Q](https://osf.io/5kr7q/)  

![QuPath](https://img.shields.io/badge/QuPath-0.6%2B-blue)
![License](https://img.shields.io/badge/License-MIT-green)
![Digital Pathology](https://img.shields.io/badge/Digital%20Pathology-Workflow-orange)

## Acknowledgments

This project was developed with the assistance of [Claude](https://www.anthropic.com/claude), an AI assistant by Anthropic.

[![Developed with Claude](https://img.shields.io/badge/🤖Developed%20with-Claude%20AI-orange)](https://www.anthropic.com/claude)



**This guide provides detailed instructions on how to convert Sectra PACS annotations from DICOM files to QuPath-compatible format and work with them effectively in your pathology workflow.**

## Overview

The workflow consists of two main steps:
1. Using Python to convert the DICOM annotation file to GeoJSON format
2. Using a Groovy script within QuPath to import and visualize the GeoJSON annotations

## Requirements

### Python Requirements
- Python 3.6 or higher
- Required packages:
  - pydicom (for reading DICOM files)
  - geojson (for creating GeoJSON output)
  - numpy (for calculations)
  - openslide-python (optional, for reading SVS dimensions)
  - Pillow (optional, as a fallback for reading image dimensions)

```bash
pip install pydicom geojson numpy openslide-python Pillow
```

### QuPath Requirements
- QuPath 0.3.0 or higher
- Basic familiarity with running scripts in QuPath

## Step 1: Converting DICOM Annotations to GeoJSON

### Installation
Save the `sectra_dicom_to_qupath.py` script to a convenient location on your computer.

### Basic Usage
Open a terminal or command prompt and run:

```bash
python sectra_dicom_to_qupath.py -i path/to/your/annotations.dcm -o output.geojson
```

```bash
D:\\DigitalPathologyDrafts\\.venv\\Scripts\\python.exe ./sectra-qupath/sectra_dicom_to_qupath.py -i "G:\svss-wsis\annotation\all_annotations_label\ANONHR1KQJ17P_1_1.svs.graphics.2025-02-10_14-04-34.dcm" -o "G:\svss-wsis\annotation\all_annotations_label\ANONHR1KQJ17P_1_1.svs.graphics.2025-02-10_14-04-34.geojson"
```





### Advanced Usage
For more accurate coordinate mapping, provide the source image file:

```bash
python sectra_dicom_to_qupath.py -i path/to/your/annotations.dcm -o output.geojson -s path/to/source.svs
```


```bash
D:\\DigitalPathologyDrafts\\.venv\\Scripts\\python.exe ./sectra-qupath/sectra_dicom_to_qupath.py -i "G:\svss-wsis\annotation\all_annotations_label\ANONHR1KQJ17P_1_1.svs.graphics.2025-02-10_14-04-34.dcm" -o "G:\svss-wsis\annotation\all_annotations_label\ANONHR1KQJ17P_1_1.svs.graphics.2025-02-10_14-04-34.geojson" -s "G:\svss-wsis\annotation\all_annotations_label\ANONHR1KQJ17P_1_1-004.svs"
```




### Command-line Options
- `-i, --input`: Required. Path to the input DICOM file.
- `-o, --output`: Optional. Path to the output GeoJSON file. If not specified, the output file will be created in the same location as the input file with a .geojson extension.
- `-s, --source`: Optional. Path to the source image file. This helps in accurately scaling the coordinates.

## Step 2: Importing Annotations into QuPath

### Installation
1. Open QuPath
2. Select "Automate" > "Show Script Editor"
3. Copy the contents of `import_sectra_annotations_enhanced.groovy` into the script editor
4. Select "Run" to execute the script

### Usage
1. Open your slide image in QuPath
2. Run the script as described above
3. When prompted, select the GeoJSON file created in Step 1
4. The script will import all annotations, applying appropriate styles and preserving measurements

### Features
- All annotation types from Sectra PACS are supported (polylines, circles, polygons, arrows)
- Original measurements are preserved and available in QuPath
- Annotations are color-coded based on their type or content
- A summary of imported annotations is displayed after import

## Working with Annotations in QuPath

### Viewing Measurements
1. Select an annotation in QuPath
2. Open the "Measurements" tab to view any associated measurements
3. Original measurement values from Sectra (e.g., length in mm) are preserved

### Using Annotations for Tile Extraction
To use annotations for tile extraction:

1. Select the annotation(s) you want to use for tile extraction
2. Use QuPath's "Tiles & Superpixels" commands to extract tiles from annotation regions
3. You can also use scripting for more customized tile extraction:

```groovy
// Example script to extract tiles from annotations
import qupath.lib.objects.PathAnnotationObject
import qupath.lib.regions.RegionRequest
import qupath.lib.images.servers.ImageServer

// Get the image server
def server = getCurrentImageData().getServer()

// Get all annotations
def annotations = getAnnotationObjects()

// Define tile size
int tileWidth = 512
int tileHeight = 512
double downsample = 1.0

// Create tile directory
def path = buildFilePath(PROJECT_BASE_DIR, 'tiles')
mkdirs(path)

// Extract tiles for each annotation
annotations.eachWithIndex { annotation, i ->
    def roi = annotation.getROI()
    def region = RegionRequest.createInstance(server.getPath(), downsample, roi)
    def img = server.readRegion(region)
    def tilePath = buildFilePath(path, "tile_${i}.png")
    writeImage(img, tilePath)
}
```

## Troubleshooting

### Common Python Script Issues

1. **Module not found errors**:
   - Ensure you've installed all required packages with `pip install pydicom geojson numpy`

2. **Cannot read DICOM file**:
   - Verify the file is a valid DICOM file from Sectra PACS
   - Check file permissions
   - Try using the `dcminfo` command to verify the file: `python -c "import pydicom; print(pydicom.dcmread('your_file.dcm'))"`

3. **Coordinate scaling issues**:
   - If annotations appear in the wrong location, provide the source image for correct scaling: `-s path/to/source.svs`
   - Check if the coordinate system in the DICOM file matches your expectations (normalized vs. pixel coordinates)

### Common QuPath Script Issues

1. **Script errors**:
   - Check the script console for specific error messages
   - Verify you're using a compatible version of QuPath (0.3.0 or higher)

2. **Annotations not visible**:
   - Ensure annotations are within the visible area of the slide
   - Check the "Annotations" panel to see if they were imported but are hidden

3. **Annotations in wrong position**:
   - This usually indicates a coordinate scaling issue. The enhanced script tries to handle both normalized (0-1) and pixel coordinates, but you might need to modify the scaling logic if your specific DICOM files use a different coordinate system.

## Advanced Customization

### Modifying the Python Script
- You can customize the `extract_graphic_objects` function to handle additional graphic types
- Add support for other measurement types by modifying the regex patterns in `create_geojson_feature`

### Modifying the QuPath Script
- Customize the color scheme by modifying the `getColorForAnnotation` function
- Add additional processing for specific annotation types
- Integrate with other QuPath workflows or plugins

## Batch Processing

For processing multiple DICOM files, you can create a simple batch script:

```bash
#!/bin/bash
# Batch process multiple DICOM files
for dcm_file in /path/to/dicom/files/*.dcm; do
    base_name=$(basename "$dcm_file" .dcm)
    svs_file="/path/to/svs/files/${base_name}.svs"
    output_file="/path/to/output/folder/${base_name}.geojson"
    
    python sectra_dicom_to_qupath.py -i "$dcm_file" -o "$output_file" -s "$svs_file"
done
```

In QuPath, you can modify the script to batch process entire projects by iterating through all images in the project.

## Conclusion

By following this guide, you can seamlessly integrate Sectra PACS annotations into your QuPath workflow. These tools allow you to preserve all the annotation information including measurements, types, and labels, making them available for further analysis in QuPath.

The provided scripts handle the most common annotation types and measurements, but can be customized to meet specific needs if required.
