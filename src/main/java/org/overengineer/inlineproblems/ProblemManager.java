package org.overengineer.inlineproblems;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import org.overengineer.inlineproblems.entities.DrawDetails;
import org.overengineer.inlineproblems.entities.InlineProblem;
import org.overengineer.inlineproblems.settings.SettingsState;

import java.util.*;
import java.util.stream.Collectors;


public class ProblemManager implements Disposable {
    private final List<InlineProblem> activeProblems = new ArrayList<>();

    private final InlineDrawer inlineDrawer = new InlineDrawer();

    private final SettingsState settingsState = SettingsState.getInstance();

    private final Logger logger = Logger.getInstance(ProblemManager.class);

    public void dispose() {
        reset();
    }

    public void removeProblem(InlineProblem problem) {
        InlineProblem problemToRemove = findActiveProblemByRangeHighlighterHashCode(problem.getRangeHighlighterHashCode());
        if (problemToRemove == null) {
            logger.warn("Removal of problem failed, not found by RangeHighlighterHashCode");
            resetForEditor(problem.getTextEditor().getEditor());
            return;
        }

        List<InlineProblem> problemsInLine = null;
        if (settingsState.isShowAnyGutterIcons()) {
            problemsInLine = getProblemsInLineForProblemSorted(problem);
        }

        inlineDrawer.undrawErrorLineHighlight(problem, problemsInLine);
        inlineDrawer.undrawInlineProblemLabel(problem);

        if (!activeProblems.remove(problemToRemove)) {
            logger.warn("Removal of problem failed, resetting");
            resetForEditor(problemToRemove.getTextEditor().getEditor());
            return;
        }
    }

    /**
     * To add problems, if there are existing problems in the same line, they will be removed and re-added to ensure the
     * correct order (ordered by severity)
     * @param problem problem to add
     */
    public void addProblem(InlineProblem problem) {
        problem.setDrawDetails(new DrawDetails(problem, problem.getTextEditor().getEditor()));

        List<InlineProblem> problemsInLine = getProblemsInLineForProblem(problem);
        problemsInLine.add(problem);

        problemsInLine = problemsInLine.stream()
                .sorted((p1, p2) -> Integer.compare(p2.getSeverity(), p1.getSeverity()))
                .collect(Collectors.toList());

        problemsInLine.forEach(p -> {
            if (p != problem)
                removeProblem(p);
        });

        inlineDrawer.drawLineHighlighterAndGutterIcon(problemsInLine);

        /* This only works when using a method reference, if we move the code from the addProblemPrivate func into a lambda
        *  it does not work like expected, that is because there are differences the evaluation and the way it is called */
        problemsInLine.forEach(this::addProblemPrivate);
    }

    private void addProblemPrivate(InlineProblem problem) {
        applyCustomSeverity(problem);

        if (problem.getTextEditor().getEditor().getDocument().getLineCount() <= problem.getLine()) {
            logger.warn("Line count is less or equal than problem line, problem not added");
            return;
        }

        inlineDrawer.drawProblemLabel(problem);
        Collections.synchronizedList(activeProblems).add(problem);
    }

    private void applyCustomSeverity(InlineProblem problem) {
        int severity = problem.getSeverity();

        if (severity >= HighlightSeverity.ERROR.myVal ||
                settingsState.getAdditionalErrorSeverities().stream().anyMatch(s -> s == severity)
        ) {
            problem.setSeverity(HighlightSeverity.ERROR.myVal);
        }
        else if (severity >= HighlightSeverity.WARNING.myVal ||
                settingsState.getAdditionalWarningSeverities().stream().anyMatch(s -> s == severity)
        ) {
            problem.setSeverity(HighlightSeverity.WARNING.myVal);
        }
        else if (severity >= HighlightSeverity.WEAK_WARNING.myVal ||
                settingsState.getAdditionalWeakWarningSeverities().stream().anyMatch(s -> s == severity)
        ) {
            problem.setSeverity(HighlightSeverity.WEAK_WARNING.myVal);
        }
        else if (severity >= HighlightSeverity.INFORMATION.myVal ||
                settingsState.getAdditionalInfoSeverities().stream().anyMatch(s -> s == severity)
        ) {
            problem.setSeverity(HighlightSeverity.INFORMATION.myVal);
        }
    }

    public void reset() {
        final List<InlineProblem> activeProblemSnapShot = List.copyOf(activeProblems);
        activeProblemSnapShot.forEach(this::removeProblem);
    }

    public void resetForEditor(Editor editor) {
        final List<InlineProblem> activeProblemsSnapShot = List.copyOf(activeProblems);

        activeProblemsSnapShot.stream()
                .filter(aP -> aP.getTextEditor().getEditor().equals(editor))
                .forEach(this::removeProblem);
    }

    public void updateFromNewActiveProblems(List<InlineProblem> problems) {
        final List<InlineProblem> activeProblemsSnapShot = List.copyOf(activeProblems);

        updateFromNewActiveProblems(problems, activeProblemsSnapShot);
    }

    public void updateFromNewActiveProblemsForProjectAndFile(List<InlineProblem> problems, Project project, String filePath) {
        final List<InlineProblem> activeProblemsSnapShot = activeProblems.stream()
                .filter(p -> p.getProject().equals(project) && p.getFile().equals(filePath))
                .collect(Collectors.toList());

        updateFromNewActiveProblems(problems, activeProblemsSnapShot);
    }

    private List<InlineProblem> getProblemsInLineForProblem(InlineProblem problem) {
        return activeProblems.stream()
                .filter(p -> Objects.equals(p.getTextEditor(), problem.getTextEditor()) && p.getLine() == problem.getLine())
                .collect(Collectors.toList());
    }

    private List<InlineProblem> getProblemsInLineForProblemSorted(InlineProblem problem) {
        return activeProblems.stream()
                .filter(p -> Objects.equals(p.getTextEditor(), problem.getTextEditor()) && p.getLine() == problem.getLine())
                .sorted((p1, p2) -> Integer.compare(p2.getSeverity(), p1.getSeverity()))
                .collect(Collectors.toList());
    }

    /**
     * Updates the active problems based on a list of new problems, problems can also be added and removed one by one,
     * like the MarkupModelProblemListener does, but if the feature "Show only highest severity per line" is enabled,
     * this function needs to be used.
     */
    private void updateFromNewActiveProblems(List<InlineProblem> newProblems, List<InlineProblem> activeProblemsSnapShot) {
        final List<InlineProblem> processedProblems = new ArrayList<>();
        List<InlineProblem> usedProblems;

        if (settingsState.isShowOnlyHighestSeverityPerLine()) {

            Map<String, InlineProblem> filteredMap = new HashMap<>();

            for (InlineProblem problem : newProblems) {
                String key = problem.getTextEditor().getFile().getPath() + problem.getLine();

                if (filteredMap.containsKey(key)) {
                    if (filteredMap.get(key).getSeverity() < problem.getSeverity()) {
                        filteredMap.replace(key, problem);
                    }
                }
                else {
                    filteredMap.put(key, problem);
                }
            }

            usedProblems = new ArrayList<>(filteredMap.values());
        }
        else {
            usedProblems = newProblems;
        }

        activeProblemsSnapShot.stream()
                .filter(p -> !usedProblems.contains(p))
                .forEach(p -> {processedProblems.add(p); removeProblem(p);});

        usedProblems.stream()
                .filter(p -> !activeProblemsSnapShot.contains(p) && !processedProblems.contains(p))
                .forEach(this::addProblem);
    }

    private InlineProblem findActiveProblemByRangeHighlighterHashCode(int hashCode) {
        return activeProblems.stream()
                .filter(p -> p.getRangeHighlighterHashCode() == hashCode)
                .findFirst()
                .orElse(null);
    }
}
