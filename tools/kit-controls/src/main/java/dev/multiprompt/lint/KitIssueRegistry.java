package dev.multiprompt.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.detector.api.Issue;
import java.util.List;

public final class KitIssueRegistry extends IssueRegistry {
    @Override
    public List<Issue> getIssues() {
        return List.of(KitControlDetector.ISSUE);
    }

    @Override
    public int getApi() {
        return com.android.tools.lint.detector.api.ApiKt.CURRENT_API;
    }
}
