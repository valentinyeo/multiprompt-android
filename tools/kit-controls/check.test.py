from collections import Counter
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("kit_check", Path(__file__).with_name("check.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

SCREEN = "app/src/main/java/dev/multiprompt/companion/ui/NewScreen.kt"


def found(source, name=SCREEN):
    return check.violations({name: source})


class KitMaterialCheckTest(unittest.TestCase):
    def test_current_screens_use_only_kit_components(self):
        check.check()

    def test_legacy_dialog_menu_switch_button_and_tonal_fill_are_rejected(self):
        source = "\n".join([
            "AlertDialog(onDismissRequest = {}, confirmButton = {})",
            "DropdownMenu(expanded = true, onDismissRequest = {}) {}",
            "DropdownMenuItem(text = { Text(row) }, onClick = {})",
            "Switch(checked = on, onCheckedChange = {})",
            "FilledTonalButton(onClick = {}) { Text(save) }",
            "TonalCard(tonalElevation = 3.dp)",
        ])
        self.assertEqual(Counter({SCREEN + ": AlertDialog": 1,
                                  SCREEN + ": DropdownMenu": 1,
                                  SCREEN + ": DropdownMenuItem": 1,
                                  SCREEN + ": Switch": 1,
                                  SCREEN + ": tonal-fill": 1,
                                  SCREEN + ": tonal-elevation": 1}), found(source))

    def test_material_import_alone_is_rejected(self):
        source = "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.FilledTonalButton"
        self.assertEqual(Counter({SCREEN + ": AlertDialog": 1, SCREEN + ": tonal-fill": 1}), found(source))

    def test_kit_and_theme_may_call_material(self):
        source = "AlertDialog(onDismissRequest = {})\nval Purple80 = Color(0xFFD0BCFF)"
        self.assertEqual(Counter(), check.violations({check.KIT: source, check.THEME: source}))

    def test_kit_components_semantics_roles_and_brand_colours_pass(self):
        source = "\n".join([
            "KitPrimaryButton(onClick = {}) { Text(save) }",
            "KitToggle(checked = on, onCheckedChange = {})",
            "Modifier.clickable(role = Role.Button, onClick = onClick)",
            "Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)",
            "Text(\"Switch AI is not available for this chat\")",
            "val accent = Color(0xFFA77FE0)",
        ])
        self.assertEqual(Counter(), found(source))

    def test_purple_palette_names_and_template_literals_are_rejected(self):
        source = "\n".join(["val accent = Purple40", "val scheme = Color(0xFF6750A4)"])
        self.assertEqual(Counter({SCREEN + ": purple": 2}), found(source))

    def test_debug_and_test_sources_are_out_of_scope(self):
        source = "AlertDialog(onDismissRequest = {}, confirmButton = {})"
        self.assertEqual(Counter(), check.violations({
            "app/src/debug/java/dev/multiprompt/companion/DebugScreen.kt": source,
            "app/src/test/java/dev/multiprompt/companion/ScreenTest.kt": source,
        }))

    def test_real_pr_136_and_pr_392_revisions_are_rejected(self):
        for ref, expected in (("c58a41b6", ": AlertDialog"), ("38878ce0^", ": IconButton")):
            with self.subTest(ref=ref):
                reported = check.violations(check.files(ref=ref))
                self.assertTrue(any(key.endswith(expected) for key in reported), reported)
                self.assertGreaterEqual(sum(reported.values()), 1)


if __name__ == "__main__":
    unittest.main()
