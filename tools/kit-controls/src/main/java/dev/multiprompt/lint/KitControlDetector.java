package dev.multiprompt.lint;

import com.android.tools.lint.client.api.UElementHandler;
import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.Context;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.android.tools.lint.detector.api.SourceCodeScanner;
import com.intellij.psi.PsiMethod;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UastUtils;

public final class KitControlDetector extends Detector implements SourceCodeScanner {
    public static final Issue ISSUE = Issue.create(
        "KitControlRequired",
        "Use the design kit for buttons and menus",
        "Material controls compete with DesignKit.kt. Use the existing Kit component named in the diagnostic.",
        Category.CORRECTNESS,
        6,
        Severity.ERROR,
        new Implementation(KitControlDetector.class, Scope.JAVA_FILE_SCOPE)
    );

    private static final Map<String, String> REPLACEMENTS = Map.ofEntries(
        Map.entry("AlertDialog", "KitSheet with KitPrimaryButton/KitSecondaryButton"),
        Map.entry("BasicAlertDialog", "KitSheet with KitPrimaryButton/KitSecondaryButton"),
        Map.entry("DropdownMenu", "KitSheet (or KitMenu) with KitMenuRow"),
        Map.entry("ExposedDropdownMenu", "KitSheet (or KitMenu) with KitMenuRow"),
        Map.entry("DropdownMenuItem", "KitMenuRow"),
        Map.entry("Button", "KitPrimaryButton"),
        Map.entry("ElevatedButton", "KitPrimaryButton"),
        Map.entry("FilledTonalButton", "KitPrimaryButton"),
        Map.entry("OutlinedButton", "KitSecondaryButton"),
        Map.entry("TextButton", "KitSecondaryButton"),
        Map.entry("IconButton", "KitRoundButton"),
        Map.entry("FloatingActionButton", "KitRoundButton or KitSecondaryButton"),
        Map.entry("SmallFloatingActionButton", "KitRoundButton"),
        Map.entry("LargeFloatingActionButton", "KitRoundButton"),
        Map.entry("ExtendedFloatingActionButton", "KitSecondaryButton"),
        Map.entry("FilledIconButton", "KitRoundButton"),
        Map.entry("FilledTonalIconButton", "KitRoundButton"),
        Map.entry("OutlinedIconButton", "KitRoundButton"),
        Map.entry("IconToggleButton", "KitToggle"),
        Map.entry("FilledIconToggleButton", "KitToggle"),
        Map.entry("FilledTonalIconToggleButton", "KitToggle"),
        Map.entry("OutlinedIconToggleButton", "KitToggle"),
        Map.entry("Switch", "KitToggle"),
        Map.entry("ModalBottomSheet", "KitSheet")
    );

    private final Properties baseline = new Properties();
    private final Map<String, Integer> seen = new HashMap<>();

    public KitControlDetector() {
        try (var stream = KitControlDetector.class.getResourceAsStream("/kit-controls-baseline.properties")) {
            if (stream == null) throw new IllegalStateException("Missing kit control baseline");
            baseline.load(stream);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    KitControlDetector(Properties allowances) {
        baseline.putAll(allowances);
    }

    @Override
    public void beforeCheckRootProject(Context context) {
        seen.clear();
    }

    @Override
    public List<Class<? extends UElement>> getApplicableUastTypes() {
        return List.of(UCallExpression.class);
    }

    @Override
    public UElementHandler createUastHandler(JavaContext context) {
        if (context.isTestSource() || context.file.toPath().toString().replace('\\', '/').contains("/src/debug/")) return null;
        return new UElementHandler() {
            @Override
            public void visitCallExpression(UCallExpression node) {
                PsiMethod method = node.resolve();
                if (method == null || method.getContainingClass() == null) return;
                String owner = method.getContainingClass().getQualifiedName();
                if (owner == null) return;
                String name = method.getName().split("-", 2)[0];
                String replacement = REPLACEMENTS.get(name);
                if (replacement == null) return;
                String library;
                if (owner.startsWith("androidx.compose.material3.")) library = "androidx.compose.material3";
                else if (owner.startsWith("androidx.compose.material.")) library = "androidx.compose.material";
                else if (owner.equals("dev.multiprompt.companion.ui.ControlsKt") && List.of("Button", "FilledTonalButton", "OutlinedButton").contains(name)) library = "dev.multiprompt.companion.ui";
                else return;
                UMethod enclosing = UastUtils.getParentOfType(node, UMethod.class);
                if (name.equals("ModalBottomSheet") && context.file.getName().equals("DesignKit.kt") && enclosing != null && enclosing.getName().equals("KitSheet") && context.getUastFile().getPackageName().equals("dev.multiprompt.companion.ui")) return;
                String function = enclosing == null ? "<initializer>" : enclosing.getName() + "(" + String.join(",", java.util.Arrays.stream(enclosing.getJavaPsi().getParameterList().getParameters()).map(parameter -> parameter.getType().getCanonicalText()).toList()) + ")";
                String key = library + "." + name + "|" + context.getUastFile().getPackageName() + "." + function;
                int count = seen.merge(key, 1, Integer::sum);
                if (count <= Integer.parseInt(baseline.getProperty(key, "0"))) return;
                context.report(ISSUE, node, context.getNameLocation(node), "KIT_CONTROL_REQUIRED: calls " + library + "." + name + ". Use " + replacement + " from ui/DesignKit.kt.");
            }
        };
    }
}
