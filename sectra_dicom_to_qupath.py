#!/usr/bin/env python3
"""
Convert Sectra PACS DICOM annotations to GeoJSON format for use in QuPath.

This script reads a DICOM file containing Sectra PACS annotations and extracts the 
graphic annotations (polylines, circles, etc.) along with their labels. It then 
converts these annotations to GeoJSON format which can be imported into QuPath.

Requirements:
    - pydicom
    - geojson
    - numpy
    - argparse

Usage:
    python sectra_dicom_to_qupath.py -i input.dcm -o output.geojson [-s source.svs]
"""

import os
import sys
import json
import argparse
import traceback
import numpy as np
from pathlib import Path
import pydicom
from geojson import Feature, FeatureCollection, Point, Polygon, LineString

def read_image_dimensions(image_path):
    """
    Read image dimensions from an SVS file or alternative source.
    If the file is SVS, uses openslide. Otherwise tries to use PIL.
    """
    try:
        import openslide
        slide = openslide.OpenSlide(image_path)
        width, height = slide.dimensions
        slide.close()
        print(f"Successfully read dimensions from SVS: {width}x{height}")
        return width, height
    except (ImportError, FileNotFoundError) as e:
        print(f"OpenSlide error: {e}")
        try:
            from PIL import Image
            with Image.open(image_path) as img:
                width, height = img.width, img.height
                print(f"Successfully read dimensions using PIL: {width}x{height}")
                return width, height
        except (ImportError, FileNotFoundError) as e:
            print(f"PIL error: {e}")
            print(f"Warning: Could not read dimensions from {image_path}. Using default values.")
            return None, None

def extract_coordinates(graphic_data):
    """Extract coordinates from GraphicData attribute."""
    coordinates = []
    # Handle different data types that might be in GraphicData
    try:
        # Convert to list if it's not already
        if not isinstance(graphic_data, (list, tuple)):
            # If it's a string, try to parse it
            if isinstance(graphic_data, str):
                # Handle string format with backslashes
                parts = graphic_data.split('\\')
                data = [float(part) for part in parts if part]
            else:
                # For other types, convert to string and try again
                data_str = str(graphic_data)
                if '\\' in data_str:
                    parts = data_str.split('\\')
                    data = [float(part) for part in parts if part]
                else:
                    # If no backslash, assume it's a single value
                    data = [float(graphic_data)]
        else:
            # Already a list, ensure all elements are float
            data = [float(val) for val in graphic_data]

        # GraphicData is stored as flat array of alternating x,y coordinates
        for i in range(0, len(data), 2):
            if i+1 < len(data):
                coordinates.append([float(data[i]), float(data[i+1])])
    except Exception as e:
        print(f"Error extracting coordinates from {graphic_data}: {e}")
        print(f"Data type: {type(graphic_data)}")
        if isinstance(graphic_data, (list, tuple)):
            print(f"List length: {len(graphic_data)}")
            if len(graphic_data) > 0:
                print(f"First item type: {type(graphic_data[0])}")
    
    return coordinates

def parse_float_from_coordinate(value):
    """Safely parse a float from various formats."""
    if isinstance(value, (int, float)):
        return float(value)
    
    try:
        # Try direct conversion
        return float(value)
    except (ValueError, TypeError):
        # Try parsing as string with potential formatting
        try:
            str_val = str(value).strip()
            return float(str_val)
        except (ValueError, TypeError):
            print(f"Could not parse float from {value}")
            return 0.0

def extract_coordinates_robust(graphic_data):
    """A more robust version of coordinate extraction handling various formats."""
    coordinates = []
    
    try:
        # If it's a single value (e.g., AnchorPoint), it might be a string with backslashes
        if not isinstance(graphic_data, (list, tuple)):
            str_val = str(graphic_data)
            # Check if it's a backslash-separated string
            if '\\' in str_val:
                parts = str_val.split('\\')
                # Create pairs of coordinates
                for i in range(0, len(parts), 2):
                    if i+1 < len(parts):
                        try:
                            x = parse_float_from_coordinate(parts[i])
                            y = parse_float_from_coordinate(parts[i+1])
                            coordinates.append([x, y])
                        except Exception as inner_e:
                            print(f"Error parsing coordinate pair {i}: {inner_e}")
            else:
                # It's just a single value, probably not coordinates
                print(f"Warning: Single value not in coordinate format: {str_val}")
        else:
            # It's a list or tuple, so extract pairs
            for i in range(0, len(graphic_data), 2):
                if i+1 < len(graphic_data):
                    try:
                        x = parse_float_from_coordinate(graphic_data[i])
                        y = parse_float_from_coordinate(graphic_data[i+1])
                        coordinates.append([x, y])
                    except Exception as inner_e:
                        print(f"Error parsing coordinate pair {i}: {inner_e}")
    except Exception as e:
        print(f"Error in extract_coordinates_robust: {e}")
        print(f"Data: {graphic_data}")
        print(f"Type: {type(graphic_data)}")
    
    return coordinates

def circle_to_polygon(center_x, center_y, radius, num_points=36):
    """Convert a circle to a polygon with specified number of points."""
    angles = np.linspace(0, 2*np.pi, num_points, endpoint=False)
    points = []
    for angle in angles:
        x = center_x + radius * np.cos(angle)
        y = center_y + radius * np.sin(angle)
        points.append([x, y])
    # Close the polygon
    points.append(points[0])
    return points

def extract_text_object(seq):
    """Extract text information from TextObjectSequence if available."""
    label = ""
    anchor_point = None
    
    try:
        if hasattr(seq, "TextObjectSequence") and len(seq.TextObjectSequence) > 0:
            text_obj = seq.TextObjectSequence[0]
            
            # Extract the label
            if hasattr(text_obj, "UnformattedTextValue"):
                label = str(text_obj.UnformattedTextValue)
            
            # Handle AnchorPoint specially - it's often stored as a backslash-separated string
            if hasattr(text_obj, "AnchorPoint"):
                anchor_point_data = text_obj.AnchorPoint
                
                # Try to parse the AnchorPoint based on its type
                if isinstance(anchor_point_data, (list, tuple)) and len(anchor_point_data) >= 2:
                    # If it's already a list/tuple with coordinates
                    anchor_point = [float(anchor_point_data[0]), float(anchor_point_data[1])]
                else:
                    # If it's a string or other format, try to parse it
                    anchor_point_str = str(anchor_point_data)
                    if '\\' in anchor_point_str:
                        parts = anchor_point_str.split('\\')
                        if len(parts) >= 2:
                            try:
                                anchor_point = [float(parts[0]), float(parts[1])]
                            except (ValueError, TypeError):
                                print(f"Warning: Could not parse AnchorPoint values: {parts}")
    except Exception as e:
        print(f"Error extracting text object: {e}")
    
    return {
        "label": label.strip() if label else "",
        "anchor_point": anchor_point
    }

def extract_graphic_objects(seq, image_width=None, image_height=None):
    """Extract graphic objects from a sequence."""
    graphic_objects = []
    
    # Extract any text associated with this sequence
    try:
        text_info = extract_text_object(seq)
        label = text_info["label"]
        
        # Check if there's a GraphicObjectSequence
        if hasattr(seq, "GraphicObjectSequence"):
            for graphic_obj in seq.GraphicObjectSequence:
                try:
                    obj_type = str(graphic_obj.GraphicType) if hasattr(graphic_obj, "GraphicType") else "UNKNOWN"
                    
                    # Extract coordinates using the robust method
                    if hasattr(graphic_obj, "GraphicData"):
                        coordinates = extract_coordinates_robust(graphic_obj.GraphicData)
                    else:
                        coordinates = []
                    
                    # Create the base object
                    obj = {
                        "type": obj_type,
                        "filled": hasattr(graphic_obj, "GraphicFilled") and graphic_obj.GraphicFilled == "Y",
                        "coordinates": coordinates,
                        "label": label
                    }
                    
                    # If CIRCLE, extract center and radius
                    if obj["type"] == "CIRCLE" and len(obj["coordinates"]) == 2:
                        center_x, center_y = obj["coordinates"][0]
                        radius_point_x, radius_point_y = obj["coordinates"][1]
                        radius = np.sqrt((radius_point_x - center_x)**2 + (radius_point_y - center_y)**2)
                        obj["center"] = [center_x, center_y]
                        obj["radius"] = radius
                        # Convert circle to polygon for GeoJSON
                        obj["polygon_coordinates"] = circle_to_polygon(center_x, center_y, radius)
                    
                    graphic_objects.append(obj)
                except Exception as e:
                    print(f"Error processing graphic object: {e}")
        
        # Handle compound graphics (arrows, etc.)
        if hasattr(seq, "CompoundGraphicSequence"):
            for compound_obj in seq.CompoundGraphicSequence:
                try:
                    if hasattr(compound_obj, "CompoundGraphicType"):
                        obj_type = str(compound_obj.CompoundGraphicType)
                        
                        # Extract coordinates
                        if hasattr(compound_obj, "GraphicData"):
                            coordinates = extract_coordinates_robust(compound_obj.GraphicData)
                        else:
                            coordinates = []
                        
                        obj = {
                            "type": obj_type,
                            "filled": False,
                            "coordinates": coordinates,
                            "label": label
                        }
                        graphic_objects.append(obj)
                except Exception as e:
                    print(f"Error processing compound graphic: {e}")
    except Exception as e:
        print(f"Error in extract_graphic_objects: {e}")
        traceback.print_exc()
    
    return graphic_objects

def scale_coordinates(coordinates, image_width, image_height):
    """Scale normalized coordinates to pixel coordinates."""
    if image_width is None or image_height is None:
        return coordinates  # Return unscaled if dimensions are unknown
    
    scaled_coords = []
    for x, y in coordinates:
        scaled_coords.append([x * image_width, y * image_height])
    return scaled_coords

def create_geojson_feature(obj, image_width=None, image_height=None):
    """Create a GeoJSON feature from a graphic object."""
    try:
        properties = {
            "type": obj["type"],
            "filled": obj["filled"],
            "label": obj["label"].strip()
        }
        
        # Add measurements if present in the label
        if "mm" in obj["label"]:
            # Parse measurement from label
            import re
            measurements = re.findall(r'(\d+,\d+|\d+\.\d+|\d+)\s*(mm²|mm2|mm)', obj["label"])
            if measurements:
                value, unit = measurements[0]
                value = float(value.replace(',', '.'))
                properties["measurement_value"] = value
                properties["measurement_unit"] = unit
        
        if obj["type"] == "POLYLINE":
            # Create a LineString for polylines
            if len(obj["coordinates"]) > 1:
                coords = scale_coordinates(obj["coordinates"], image_width, image_height)
                geometry = LineString(coords)
                return Feature(geometry=geometry, properties=properties)
        
        elif obj["type"] == "CIRCLE":
            # Create a Polygon for circles
            coords = scale_coordinates(obj["polygon_coordinates"], image_width, image_height)
            geometry = Polygon([coords])
            properties["radius"] = obj["radius"]
            center = scale_coordinates([obj["center"]], image_width, image_height)[0]
            properties["center"] = center
            return Feature(geometry=geometry, properties=properties)
        
        elif obj["type"] == "ARROW":
            # Create a LineString for arrows
            if len(obj["coordinates"]) > 1:
                coords = scale_coordinates(obj["coordinates"], image_width, image_height)
                geometry = LineString(coords)
                return Feature(geometry=geometry, properties=properties)
    except Exception as e:
        print(f"Error creating GeoJSON feature: {e}")
        traceback.print_exc()
    
    return None

def dicom_to_geojson(dicom_file, output_file=None, image_file=None):
    """Convert DICOM annotations to GeoJSON."""
    try:
        # Read DICOM file
        ds = pydicom.dcmread(dicom_file)
        
        # Get image dimensions if image file is provided
        image_width, image_height = None, None
        if image_file and os.path.exists(image_file):
            image_width, image_height = read_image_dimensions(image_file)
        
        # If dimensions are not available from the image file, try to get them from DICOM
        if image_width is None or image_height is None:
            try:
                # Check if DisplayedAreaSelectionSequence has dimensions
                if hasattr(ds, "DisplayedAreaSelectionSequence") and ds.DisplayedAreaSelectionSequence:
                    displayed_area = ds.DisplayedAreaSelectionSequence[0]
                    if hasattr(displayed_area, "DisplayedAreaBottomRightHandCorner"):
                        image_width = displayed_area.DisplayedAreaBottomRightHandCorner[0]
                        image_height = displayed_area.DisplayedAreaBottomRightHandCorner[1]
                        print(f"Using dimensions from DICOM: {image_width}x{image_height}")
            except Exception as e:
                print(f"Warning: Failed to extract dimensions from DICOM: {e}")
        
        features = []
        
        # Extract annotations
        if hasattr(ds, "GraphicAnnotationSequence"):
            for annotation_seq in ds.GraphicAnnotationSequence:
                try:
                    graphic_objects = extract_graphic_objects(annotation_seq)
                    
                    for obj in graphic_objects:
                        feature = create_geojson_feature(obj, image_width, image_height)
                        if feature:
                            features.append(feature)
                except Exception as e:
                    print(f"Error processing annotation sequence: {e}")
                    traceback.print_exc()
        
        # Create the GeoJSON FeatureCollection
        feature_collection = FeatureCollection(features)
        
        # Add metadata
        metadata = {
            "source_file": os.path.basename(dicom_file),
            "image_width": image_width,
            "image_height": image_height,
            "creation_date": str(ds.get("PresentationCreationDate", "")),
            "creation_time": str(ds.get("PresentationCreationTime", "")),
            "content_description": str(ds.get("ContentDescription", ""))
        }
        feature_collection["metadata"] = metadata
        
        # Save to file if requested
        if output_file:
            with open(output_file, 'w') as f:
                json.dump(feature_collection, f, indent=2)
            print(f"GeoJSON saved to {output_file}")
        
        return feature_collection
    except Exception as e:
        print(f"Error in dicom_to_geojson: {e}")
        traceback.print_exc()
        return FeatureCollection([])  # Return empty collection on error

def main():
    # Parse command-line arguments
    parser = argparse.ArgumentParser(description='Convert Sectra PACS DICOM annotations to GeoJSON')
    parser.add_argument('-i', '--input', required=True, help='Input DICOM file')
    parser.add_argument('-o', '--output', help='Output GeoJSON file')
    parser.add_argument('-s', '--source', help='Source image file (SVS) for dimensions')
    parser.add_argument('-v', '--verbose', action='store_true', help='Enable verbose output')
    args = parser.parse_args()
    
    # If output file is not specified, use input filename with .geojson extension
    if not args.output:
        args.output = os.path.splitext(args.input)[0] + '.geojson'
    
    try:
        # Convert DICOM to GeoJSON
        dicom_to_geojson(args.input, args.output, args.source)
    except Exception as e:
        print(f"Error during execution: {e}")
        traceback.print_exc()
        sys.exit(1)

if __name__ == "__main__":
    main()