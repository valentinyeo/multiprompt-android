#!/usr/bin/env python3
import copy
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

SCRIPT = Path(__file__).with_name('reactions.py')


class ReactionOracleTest(unittest.TestCase):
    def check_case(self, mutation=None, expect=True, light=False):
        nodes = [
            dict(text='Same prefix FIRST REPLY', bounds='[10,10][210,90]'),
            dict(text='Same prefix SECOND REPLY', bounds='[10,150][210,230]'),
            {'content-desc': 'Reactions: 👍', 'bounds': '[10,95][50,120]'},
            {'class': 'android.widget.EditText', 'text': 'draft stays here', 'bounds': '[10,250][210,290]'},
        ]
        image = Image.new('RGB', (300, 300), '#ffffff' if light else '#101010')
        draw = ImageDraw.Draw(image)
        draw.rectangle((10, 95, 49, 119), fill='#e1e1e1' if light else '#353535', outline='#9b9b9b' if light else '#4a4a4a')
        draw.rectangle((20, 100, 29, 114), fill='#ffff00')
        draw.rectangle((25, 102, 32, 108), fill='#000000')
        draw.rectangle((23, 110, 29, 114), fill='#ffffff')
        if mutation:
            mutation(nodes, image)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tree = ET.Element('hierarchy')
            for node in nodes:
                ET.SubElement(tree, 'node', node)
            ET.ElementTree(tree).write(root / 'ui.xml', encoding='utf-8')
            image.save(root / 'screen.png')
            result = subprocess.run([sys.executable, str(SCRIPT), 'check', str(root / 'ui.xml'), str(root / 'screen.png'), 'badge', str(root / 'result.json')], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, expect, result.stdout + result.stderr)

    def test_mixed_wire_is_rejected_and_clean_words_are_accepted(self):
        for wire, expected in [(True, False), (False, True)]:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                tree = ET.Element('hierarchy')
                for text in ['My follow-up stays visible', 'Answer to the follow-up']:
                    ET.SubElement(tree, 'node', text=text)
                ET.SubElement(tree, 'node', {'content-desc': 'Reactions: 👍'})
                if wire:
                    ET.SubElement(tree, 'node', text='👍 to: Quote [mp:6d8e353d25c5fe23]')
                ET.ElementTree(tree).write(root / 'ui.xml', encoding='utf-8')
                result = subprocess.run([sys.executable, str(SCRIPT), 'mixed', str(root / 'ui.xml'), str(root / 'result.json')], capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, expected, result.stdout + result.stderr)

    def test_valid_badge(self):
        self.check_case()

    def test_valid_light_badge(self):
        self.check_case(light=True)

    def test_dark_badge_on_light_chat_is_rejected(self):
        self.check_case(lambda nodes, image: ImageDraw.Draw(image).rectangle((10, 95, 49, 119), fill='#353535', outline='#4a4a4a'), False, light=True)

    def test_tinted_material_badge_is_rejected(self):
        self.check_case(lambda nodes, image: ImageDraw.Draw(image).rectangle((10, 95, 49, 119), fill='#5c4132'), False)

    def test_wrong_grey_fill_is_rejected(self):
        self.check_case(lambda nodes, image: ImageDraw.Draw(image).rectangle((10, 95, 49, 119), fill='#dddddd', outline='#4a4a4a'), False)

    def test_missing_grey_outline_is_rejected(self):
        self.check_case(lambda nodes, image: ImageDraw.Draw(image).rectangle((10, 95, 49, 119), fill='#353535'), False)

    def test_missing_badge(self):
        self.check_case(lambda nodes, image: nodes.pop(2), False)

    def test_duplicate_badge(self):
        self.check_case(lambda nodes, image: nodes.append(copy.copy(nodes[2])), False)

    def test_wrong_same_prefix_target(self):
        self.check_case(lambda nodes, image: nodes[2].update(bounds='[10,235][50,260]'), False)

    def test_empty_glyph(self):
        self.check_case(lambda nodes, image: ImageDraw.Draw(image).rectangle((10, 95, 49, 119), fill='#dddddd'), False)

    def test_extra_reaction_bubble(self):
        self.check_case(lambda nodes, image: nodes.append(dict(text='👍 to: Same prefix [mp:6d8e353d25c5fe23]', bounds='[10,235][210,245]')), False)

    def test_shown_session_reply(self):
        self.check_case(lambda nodes, image: nodes.append(dict(text='Reaction received.', bounds='[10,235][210,245]')), False)

    def test_cleared_composer(self):
        self.check_case(lambda nodes, image: nodes[3].update(text=''), False)


if __name__ == '__main__':
    unittest.main()
