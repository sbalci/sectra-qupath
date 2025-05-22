#!/usr/bin/env python3
"""Convert DICOM annotations to Aperio ImageScope XML.

This script reads annotation data from a DICOM file and
writes a minimal ImageScope-compatible XML file. Only basic
annotation types (polylines/polygons and circles) are handled.
"""

import argparse
import os
import xml.etree.ElementTree as ET
from typing import Iterable, List, Tuple

import pydicom
from pydicom.dataset import Dataset


def _parse_float(val: object) -> float:
    """Safely parse a value as float."""
    try:
        return float(val)
    except Exception:
        return 0.0


def _extract_coordinates(data: object) -> List[Tuple[float, float]]:
    """Extract (x, y) pairs from a DICOM GraphicData item."""
    if data is None:
        return []
    # Convert to list of strings
    if isinstance(data, (list, tuple)):
        parts = list(data)
    else:
        text = str(data)
        parts = text.split("\\")
    coords: List[Tuple[float, float]] = []
    for i in range(0, len(parts), 2):
        if i + 1 < len(parts):
            x = _parse_float(parts[i])
            y = _parse_float(parts[i + 1])
            coords.append((x, y))
    return coords


def _extract_graphics(seq: Dataset) -> Iterable[List[Tuple[float, float]]]:
    """Yield coordinate lists for graphic objects in a sequence."""
    if hasattr(seq, "GraphicObjectSequence"):
        for obj in seq.GraphicObjectSequence:
            coords = _extract_coordinates(getattr(obj, "GraphicData", None))
            if coords:
                yield coords


def dicom_to_imagescope_xml(ds: Dataset) -> ET.Element:
    """Convert a DICOM dataset to an ImageScope Annotations element."""
    root = ET.Element("Annotations")
    ann_id = 0
    region_id = 0
    for seq in getattr(ds, "GraphicAnnotationSequence", []):
        polygons = list(_extract_graphics(seq))
        if not polygons:
            continue
        ann_id += 1
        ann = ET.SubElement(
            root,
            "Annotation",
            {
                "Id": str(ann_id),
                "Name": f"Annotation {ann_id}",
                "ReadOnly": "0",
            },
        )
        regions = ET.SubElement(ann, "Regions")
        for poly in polygons:
            region_id += 1
            reg = ET.SubElement(
                regions,
                "Region",
                {
                    "Id": str(region_id),
                    "Type": "0",
                    "LineColor": "65280",
                },
            )
            verts = ET.SubElement(reg, "Vertices")
            for x, y in poly:
                ET.SubElement(verts, "Vertex", {"X": str(x), "Y": str(y)})
    return root


def dicom_file_to_imagescope(dicom_path: str, output: str | None = None) -> None:
    ds = pydicom.dcmread(dicom_path)
    root = dicom_to_imagescope_xml(ds)
    xml_bytes = ET.tostring(root, encoding="utf-8")
    if output is None:
        output = os.path.splitext(dicom_path)[0] + "_aperio.xml"
    with open(output, "wb") as f:
        f.write(xml_bytes)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert DICOM annotations to Aperio ImageScope XML"
    )
    parser.add_argument("-i", "--input", required=True, help="Input DICOM file")
    parser.add_argument("-o", "--output", help="Output XML file")
    args = parser.parse_args()
    dicom_file_to_imagescope(args.input, args.output)


if __name__ == "__main__":
    main()
