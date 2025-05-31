#!/usr/bin/env python3
"""Convert a DICOM file to a simple XML representation.

The script reads a `.dcm` file using ``pydicom`` and writes the contents to an
XML file. Nested sequences are preserved so the resulting XML mirrors the
structure of the DICOM dataset.

Usage::

    python sectra_dicom_to_xml.py -i path/to/file.dcm -o output.xml

If no output file is given the name of the input file is used with the
``.xml`` extension.
"""

import argparse
import os
import xml.etree.ElementTree as ET
from xml.dom import minidom

import pydicom
from pydicom.dataset import Dataset


def _add_dataset_to_element(dataset: Dataset, parent: ET.Element) -> None:
    """Recursively append dataset contents as XML elements."""
    for element in dataset:
        tag = f"({element.tag.group:04X},{element.tag.element:04X})"
        keyword = element.keyword or tag
        attrs = {"tag": tag, "vr": element.VR, "name": keyword}
        child = ET.SubElement(parent, "Element", attrs)
        if element.VR == "SQ":
            for item in element.value:
                item_elem = ET.SubElement(child, "Item")
                _add_dataset_to_element(item, item_elem)
        else:
            child.text = str(element.value)


def dataset_to_xml(dataset: Dataset) -> ET.Element:
    root = ET.Element("DicomDataset")
    _add_dataset_to_element(dataset, root)
    return root


def dicom_to_xml(dicom_file: str, output_file: str | None = None) -> None:
    ds = pydicom.dcmread(dicom_file)
    root = dataset_to_xml(ds)
    xml_str = minidom.parseString(ET.tostring(root)).toprettyxml(indent="  ")
    if output_file is None:
        output_file = os.path.splitext(dicom_file)[0] + ".xml"
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(xml_str)


def main() -> None:
    parser = argparse.ArgumentParser(description="Convert DICOM to XML")
    parser.add_argument("-i", "--input", required=True, help="Input DICOM file")
    parser.add_argument("-o", "--output", help="Output XML file")
    args = parser.parse_args()
    dicom_to_xml(args.input, args.output)


if __name__ == "__main__":
    main()
