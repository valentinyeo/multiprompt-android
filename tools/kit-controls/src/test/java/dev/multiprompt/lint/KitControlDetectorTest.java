package dev.multiprompt.lint;

import com.android.tools.lint.checks.infrastructure.LintDetectorTest;
import com.android.tools.lint.checks.infrastructure.TestFile;
import com.android.tools.lint.checks.infrastructure.TestMode;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Issue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

public final class KitControlDetectorTest extends LintDetectorTest {
    private final Properties allowances = new Properties();

    @Override
    protected Detector getDetector() {
        return new KitControlDetector(allowances);
    }

    @Override
    protected List<Issue> getIssues() {
        return List.of(KitControlDetector.ISSUE);
    }

    private final TestFile material = kotlin("""
        package androidx.compose.material3
        fun AlertDialog(onDismissRequest: () -> Unit, title: () -> Unit = {}, text: () -> Unit = {}, confirmButton: () -> Unit = {}, dismissButton: () -> Unit = {}) {}
        fun DropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: () -> Unit) {}
        fun DropdownMenuItem(text: () -> Unit, onClick: () -> Unit) {}
        fun Button(onClick: () -> Unit, content: () -> Unit = {}) {}
        fun TextButton(onClick: () -> Unit, modifier: Any? = null, content: () -> Unit) {}
        fun OutlinedButton(onClick: () -> Unit) {}
        fun FilledTonalButton(onClick: () -> Unit) {}
        fun IconButton(onClick: () -> Unit) {}
        fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {}
        fun ModalBottomSheet(onDismissRequest: () -> Unit, content: () -> Unit) {}
        fun Text(text: String, modifier: Any? = null, textAlign: Any? = null) {}
        """);

    public void testAliasesQualifiedCallsAndWildcardImportsResolve() {
        lint().files(material, kotlin("""
            package screens
            import androidx.compose.material3.AlertDialog as Harmless
            import androidx.compose.material3.*
            fun Example() {
                Harmless(onDismissRequest = {})
                androidx.compose.material3.AlertDialog(onDismissRequest = {})
                Button(onClick = {})
                OutlinedButton(onClick = {})
                FilledTonalButton(onClick = {})
                TextButton(onClick = {}) {}
                IconButton(onClick = {})
                Switch(false, {})
                DropdownMenu(true, {}) { DropdownMenuItem({}, {}) }
            }
            "")).testModes(TestMode.DEFAULT).run().expectErrorCount(10)
            .expectContains("KIT_CONTROL_REQUIRED: calls androidx.compose.material3.AlertDialog. Use KitSheet with KitPrimaryButton/KitSecondaryButton")
            .expectContains("Use KitPrimaryButton from ui/DesignKit.kt")
            .expectContains("Use KitRoundButton from ui/DesignKit.kt")
            .expectContains("Use KitToggle from ui/DesignKit.kt");
    }

    public void testUnrelatedSameNamedFunctionsAndKitCallsAreAllowed() {
        lint().files(material, kotlin("""
            package screens
            fun AlertDialog(onDismissRequest: () -> Unit) {}
            fun Button(onClick: () -> Unit) {}
            fun KitPrimaryButton(text: String, onClick: () -> Unit) {}
            fun Example() {
                AlertDialog({})
                Button({})
                KitPrimaryButton("Save", {})
                val text = "androidx.compose.material3.AlertDialog(onDismissRequest = {})"
            }
            "")).run().expectClean();
    }

    public void testMaterial2AndDeletedWrappersCannotReturn() {
        lint().files(kotlin("""
            package androidx.compose.material
            fun AlertDialog(onDismissRequest: () -> Unit) {}
            """), kotlin("src/dev/multiprompt/companion/ui/Controls.kt", """
            package dev.multiprompt.companion.ui
            fun Button(onClick: () -> Unit) {}
            """), kotlin("""
            package screens
            import dev.multiprompt.companion.ui.Button as OldButton
            fun Example() {
                androidx.compose.material.AlertDialog({})
                OldButton({})
            }
            """)).testModes(TestMode.DEFAULT).run().expectErrorCount(2);
    }

    public void testOnlyKitSheetMayImplementTheMaterialSheet() {
        lint().files(material, kotlin("src/dev/multiprompt/companion/ui/DesignKit.kt", """
            package dev.multiprompt.companion.ui
            fun KitSheet() { androidx.compose.material3.ModalBottomSheet({}) {} }
            fun Other() { androidx.compose.material3.ModalBottomSheet({}) {} }
            fun BadKitButton() { androidx.compose.material3.Button({}) }
            """)).testModes(TestMode.DEFAULT).run().expectErrorCount(2);
    }

    public void testDiagnosticAndTestSourcesAreExplicitlyOutsideShippingBoundary() {
        lint().files(material,
            kotlin("src/debug/kotlin/screens/Diagnostic.kt", """
                package screens
                fun Diagnostic() { androidx.compose.material3.AlertDialog({}) }
                """),
            kotlin("test/screens/Fixture.kt", """
                package screens
                fun Fixture() { androidx.compose.material3.AlertDialog({}) }
                """)).testModes(TestMode.DEFAULT).run().expectClean();
    }

    public void testBaselineCountsSymbolsAndFunctionsNotLinesOrFileNames() {
        allowances.setProperty("androidx.compose.material3.AlertDialog|screens.Example()", "1");
        lint().files(material, kotlin("src/screens/Moved.kt", """
            package screens
            import androidx.compose.material3.AlertDialog as Renamed


            fun Example() {
                Renamed({})
                androidx.compose.material3.AlertDialog({})
            }
            fun NewScreen() { Renamed({}) }
            "")).testModes(TestMode.DEFAULT).run().expectErrorCount(2);
    }

    private String historical(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void testRemovedDialogFromPr136IsRejected() throws IOException {
        lint().files(material, kotlin("""
            package screens
            import androidx.compose.material3.*
            object Arrangement { fun spacedBy(value: Int) = value }
            val Int.dp get() = this
            fun Column(verticalArrangement: Any? = null, content: () -> Unit) {}
            fun OutlinedTextField(value: String, onValueChange: (String) -> Unit, label: () -> Unit, singleLine: Boolean) {}
            fun InlineError(message: String) {}
            fun onCreateWorkspace(name: String): String? = null
            fun RemovedDialog() {
                var workspaceDialogVisible = true
                var workspaceName = ""
                var workspaceError: String? = null
            """ + historical("dialog-136.txt") + "\n}\n"))
            .testModes(TestMode.DEFAULT).run().expectErrorCount(3)
            .expectContains("KIT_CONTROL_REQUIRED: calls androidx.compose.material3.AlertDialog. Use KitSheet with KitPrimaryButton/KitSecondaryButton");
    }

    public void testRemovedMenuFromPr132IsRejected() throws IOException {
        lint().files(material, kotlin("""
            package screens
            import androidx.compose.material3.*
            enum class AppSection { SESSIONS, UPDATE }
            fun RemovedMenu(section: AppSection, onSelect: (AppSection) -> Unit) {
                var menuExpanded = true
            """ + historical("menu-132.txt") + "\n}\n"))
            .testModes(TestMode.DEFAULT).run().expectErrorCount(2)
            .expectContains("KIT_CONTROL_REQUIRED: calls androidx.compose.material3.DropdownMenu. Use KitSheet (or KitMenu) with KitMenuRow");
    }
}
