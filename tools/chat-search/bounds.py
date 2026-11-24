#!/usr/bin/env python3
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def target_bounds(xml, target, package='dev.multiprompt.companion.debug'):
    root = ET.parse(xml).getroot()
    nodes = [node for node in root.iter('node') if node.get('package') == package]
    matches = [node for node in nodes if node.get('text') == target]
    if len(matches) != 1:
        raise LookupError(f'Missing or ambiguous search destination: {target}')
    bounds = re.findall(r'-?\d+', matches[0].get('bounds', ''))
    if len(bounds) != 4:
        raise LookupError(f'Search destination has no usable bounds: {target}')
    return ','.join(bounds)


if __name__ == '__main__':
    xml, target, *package = sys.argv[1:]
    try:
        print(target_bounds(Path(xml), target, *package))
    except LookupError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
