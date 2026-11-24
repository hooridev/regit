package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;

/**
 * Handles {@code git rebase} including {@code --onto}, {@code -i --autosquash}, custom plans,
 * Git-compatible {@code .git/rebase-merge/} conflict pauses, {@code --continue} and {@code --abort}.
 */
final class Rebaser {
    private static final String STATE_DIR = "rebase-merge";

    private Rebaser() {}

    static RebaseResult rebase(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge,
                               String upstreamId, Identity who, RebaseOptions options) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        Objects.requireNonNull(who, "committer");
        Objects.requireNonNull(options, "options");
        var revisions = new Revisions(layout, db);
        var history = new History(db);
        String ontoId = options.ontoTarget() != null ? revisions.resolveCommit(options.ontoTarget()) : upstreamId;

        Optional<String> attached = layout.currentBranchOptional();
        String headName = attached.map(b -> "refs/heads/" + b).orElse("detached HEAD");
        String origHead = attached.isPresent() ? layout.require(attached.get()) : revisions.resolveHeadOptional();
        if (origHead == null) throw new IOException("Cannot rebase an unborn branch");

        // Build step plan
        List<RebaseStep> steps;
        boolean explicitPlan = options.planSteps() != null;
        if (explicitPlan) {
            steps = new ArrayList<>(options.planSteps());
        } else {
            steps = collectLinearSteps(origHead, upstreamId, history);
            if (options.isAutosquash()) {
                steps = applyAutosquash(steps);
            }
        }

        boolean customized = explicitPlan || (options.isAutosquash() && hasReorderedOrSquashed(steps));
        if (!customized && ontoId.equals(upstreamId)) {
            if (origHead.equals(ontoId) || history.contains(origHead, ontoId)) {
                return RebaseResult.of(RebaseResult.Status.UP_TO_DATE,
                        Optional.of(history.read(origHead).toCommit(origHead)), Optional.empty(), Set.of());
            }
            if (history.contains(ontoId, origHead)) {
                // Fast-forward rebase
                var work = new WorkingTree(layout.root(), db);
                try (var head = new Lock(layout.head());
                     var ref = attached.isPresent() ? new Lock(layout.ref(attached.get())) : null) {
                    checkoutClean(layout, db, trees, work, origHead, ontoId, () -> {
                        if (ref != null) ref.publish(ontoId + "\n");
                        else head.publish(ontoId + "\n");
                    });
                }
                return RebaseResult.of(RebaseResult.Status.COMPLETED,
                        Optional.of(history.read(ontoId).toCommit(ontoId)), Optional.empty(), Set.of());
            }
        }

        // Preflight clean worktree & index before mutating anything
        var work = new WorkingTree(layout.root(), db);
        Map<String, Entry> origSnapshot = snapshot(db, trees, origHead);
        ensureCleanWorktree(layout, work, origSnapshot);

        return executePlan(layout, db, trees, contentMerge, headName, origHead, ontoId, ontoId,
                steps, List.of(), 0, steps.size(), who, options.isAllowConflicts(), false);
    }

    static RebaseResult continueRebase(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge,
                                       Identity who) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        Objects.requireNonNull(who, "committer");
        Path stateDir = layout.admin().resolve(STATE_DIR);
        if (!Files.isDirectory(stateDir)) throw new IOException("No rebase in progress");
        if (!Index.conflictedPaths(layout.index()).isEmpty()) {
            throw new IOException("Resolve all conflicts and stage changes before continuing rebase");
        }

        String headName = GitFiles.line(stateDir.resolve("head-name"));
        String origHead = GitFiles.id(GitFiles.line(stateDir.resolve("orig-head")));
        String ontoId = GitFiles.id(GitFiles.line(stateDir.resolve("onto")));
        String currentTip = new Revisions(layout, db).resolveHeadOptional();
        String stoppedSha = Files.exists(stateDir.resolve("stopped-sha"))
                ? GitFiles.id(GitFiles.line(stateDir.resolve("stopped-sha"))) : null;
        String message = Files.readString(stateDir.resolve("message"));
        String authorHeader = parseAuthorScript(Files.readString(stateDir.resolve("author-script")));
        RebaseStep.Action stoppedAction = Files.exists(stateDir.resolve("regit-action"))
                ? RebaseStep.Action.valueOf(GitFiles.line(stateDir.resolve("regit-action")))
                : RebaseStep.Action.PICK;
        int msgnum = Integer.parseInt(GitFiles.line(stateDir.resolve("msgnum")));
        int total = Integer.parseInt(GitFiles.line(stateDir.resolve("end")));
        List<String> doneLines = Files.exists(stateDir.resolve("done"))
                ? new ArrayList<>(Files.readAllLines(stateDir.resolve("done"))) : new ArrayList<>();
        List<RebaseStep> remaining = parseTodo(stateDir.resolve("git-rebase-todo"), db);

        // Commit the resolved index state for the stopped commit
        Map<String, Entry> stagedFiles = Index.read(layout.index());
        String newTree = trees.write(stagedFiles);
        Revision currentRev = Revision.parse(db.read(currentTip, "commit"));
        String nextTip = currentTip;

        if (stoppedAction == RebaseStep.Action.FIXUP || stoppedAction == RebaseStep.Action.SQUASH) {
            String combinedMsg = stoppedAction == RebaseStep.Action.FIXUP
                    ? currentRev.message()
                    : (currentRev.message() + "\n" + message);
            nextTip = new Revision(newTree, currentRev.parents(), currentRev.author(), combinedMsg).write(db, who);
        } else if (!newTree.equals(currentRev.tree()) || (stoppedSha != null && isOriginallyEmpty(db, stoppedSha))) {
            nextTip = new Revision(newTree, List.of(currentTip), authorHeader, message).write(db, who);
        }

        // Advance detached HEAD to nextTip before continuing remaining todo steps
        try (var head = new Lock(layout.head())) {
            head.publish(nextTip + "\n");
        }
        return executePlan(layout, db, trees, contentMerge, headName, origHead, ontoId, nextTip,
                remaining, doneLines, msgnum, total, who, true, true);
    }

    static void abortRebase(Layout layout, ObjectsDb db, Trees trees) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        Path stateDir = layout.admin().resolve(STATE_DIR);
        if (!Files.isDirectory(stateDir)) throw new IOException("No rebase in progress");
        String headName = GitFiles.line(stateDir.resolve("head-name"));
        String origHead = GitFiles.id(GitFiles.line(stateDir.resolve("orig-head")));
        var work = new WorkingTree(layout.root(), db);

        try (var head = new Lock(layout.head()); var index = new Lock(layout.index())) {
            Map<String, Entry> origFiles = snapshot(db, trees, origHead);
            var tracked = new HashSet<String>(origFiles.keySet());
            for (Index.EntryRecord rec : Index.readAll(layout.index())) tracked.add(rec.path());
            work.forceReset(tracked, origFiles);
            index.publish(Index.encode(origFiles));
            if (headName.startsWith("refs/heads/")) {
                String branch = headName.substring(11);
                try (var ref = new Lock(layout.ref(branch))) {
                    ref.publish(origHead + "\n");
                }
                head.publish("ref: " + headName + "\n");
            } else {
                head.publish(origHead + "\n");
            }
        }
        deleteRecursive(stateDir);
    }

    private static RebaseResult executePlan(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge,
                                            String headName, String origHead, String ontoId, String startingTip,
                                            List<RebaseStep> steps, List<String> priorDone, int doneCount, int totalSteps,
                                            Identity who, boolean allowConflicts, boolean inProgress) throws IOException {
        var history = new History(db);
        String currentTip = startingTip;
        Map<String, Entry> currentTree = snapshot(db, trees, currentTip);
        var done = new ArrayList<>(priorDone);
        int stepNum = doneCount;

        for (int i = 0; i < steps.size(); i++) {
            RebaseStep step = steps.get(i);
            stepNum++;
            if (step.action() == RebaseStep.Action.DROP) {
                done.add(formatTodoLine(step));
                continue;
            }
            Revision rev = history.read(step.commit().id());
            if (rev.parents().size() > 1) throw new IOException("Rebase requires a linear topic history");
            Map<String, Entry> parentTree = rev.parents().isEmpty()
                    ? Map.of() : snapshot(db, trees, rev.parents().getFirst());
            Map<String, Entry> commitTree = trees.flatten(rev.tree());
            String theirsLabel = step.commit().shortId() + " (" + step.commit().subject() + ")";

            var outcome = new ThreeWay(db, contentMerge).mergeWithConflicts(
                    parentTree, currentTree, commitTree, "HEAD", theirsLabel, allowConflicts);

            if (outcome.hasConflicts()) {
                done.add(formatTodoLine(step));
                List<RebaseStep> rest = steps.subList(i + 1, steps.size());
                writeRebasePauseState(layout, db, trees, headName, origHead, ontoId, currentTip, currentTree,
                        step, rev, outcome, done, rest, stepNum, totalSteps);
                return RebaseResult.of(RebaseResult.Status.CONFLICTED,
                        Optional.of(history.read(currentTip).toCommit(currentTip)),
                        Optional.of(step.commit()),
                        outcome.conflicts().keySet());
            }

            Map<String, Entry> merged = outcome.resolved();
            String msg = step.message() != null ? step.message() : rev.message();
            switch (step.action()) {
                case PICK, REWORD -> {
                    if (!merged.equals(currentTree) || parentTree.equals(commitTree)
                            || step.action() == RebaseStep.Action.REWORD) {
                        currentTip = new Revision(trees.write(merged), List.of(currentTip), rev.author(), msg)
                                .write(db, who);
                    }
                }
                case FIXUP -> {
                    Revision prev = Revision.parse(db.read(currentTip, "commit"));
                    currentTip = new Revision(trees.write(merged), prev.parents(), prev.author(), prev.message())
                            .write(db, who);
                }
                case SQUASH -> {
                    Revision prev = Revision.parse(db.read(currentTip, "commit"));
                    String combined = step.message() != null
                            ? step.message() : (prev.message() + "\n" + rev.message());
                    currentTip = new Revision(trees.write(merged), prev.parents(), prev.author(), combined)
                            .write(db, who);
                }
                case DROP -> {}
            }
            currentTree = merged;
            done.add(formatTodoLine(step));
        }

        // Publish final tip and clean up rebase state if any
        String finalTip = currentTip;
        var work = new WorkingTree(layout.root(), db);
        try (var head = new Lock(layout.head());
             var ref = headName.startsWith("refs/heads/") ? new Lock(layout.ref(headName.substring(11))) : null) {
            if (inProgress) {
                try (var index = new Lock(layout.index())) {
                    var tracked = new HashSet<String>(currentTree.keySet());
                    for (Index.EntryRecord rec : Index.readAll(layout.index())) tracked.add(rec.path());
                    work.forceReset(tracked, currentTree);
                    index.publish(Index.encode(currentTree));
                    if (ref != null) {
                        ref.publish(finalTip + "\n");
                        head.publish("ref: " + headName + "\n");
                    } else {
                        head.publish(finalTip + "\n");
                    }
                }
                deleteRecursive(layout.admin().resolve(STATE_DIR));
            } else {
                checkoutClean(layout, db, trees, work, origHead, finalTip, () -> {
                    if (ref != null) ref.publish(finalTip + "\n");
                    else head.publish(finalTip + "\n");
                });
            }
        }
        return RebaseResult.of(RebaseResult.Status.COMPLETED,
                Optional.of(history.read(finalTip).toCommit(finalTip)), Optional.empty(), Set.of());
    }

    private static void writeRebasePauseState(Layout layout, ObjectsDb db, Trees trees,
                                              String headName, String origHead, String ontoId,
                                              String currentTip, Map<String, Entry> currentTree,
                                              RebaseStep stoppedStep, Revision stoppedRev, ThreeWay.Outcome outcome,
                                              List<String> doneLines, List<RebaseStep> remaining,
                                              int msgnum, int total) throws IOException {
        var work = new WorkingTree(layout.root(), db);
        try (var head = new Lock(layout.head()); var index = new Lock(layout.index())) {
            var tracked = new HashSet<String>(currentTree.keySet());
            for (Index.EntryRecord rec : Index.readAll(layout.index())) tracked.add(rec.path());
            tracked.removeAll(outcome.conflicts().keySet());
            work.forceReset(tracked, outcome.resolved());
            for (var e : outcome.conflicts().entrySet()) {
                ThreeWay.Conflict c = e.getValue();
                int mode = c.ours() != null ? c.ours().mode()
                        : (c.theirs() != null ? c.theirs().mode() : Entry.FILE);
                work.writeRaw(e.getKey(), c.worktreeContent(), mode);
            }
            index.publish(Index.encodeRecords(Index.combineWithConflicts(outcome.resolved(), outcome.conflicts())));
            head.publish(currentTip + "\n");

            Path stateDir = layout.admin().resolve(STATE_DIR);
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve("head-name"), headName + "\n");
            Files.writeString(stateDir.resolve("orig-head"), origHead + "\n");
            Files.writeString(stateDir.resolve("onto"), ontoId + "\n");
            Files.writeString(stateDir.resolve("stopped-sha"), stoppedStep.commit().id() + "\n");
            Files.writeString(stateDir.resolve("message"),
                    stoppedStep.message() != null ? stoppedStep.message() : stoppedRev.message());
            Files.writeString(stateDir.resolve("author-script"), formatAuthorScript(Identity.parse(stoppedRev.author())));
            Files.writeString(stateDir.resolve("msgnum"), msgnum + "\n");
            Files.writeString(stateDir.resolve("end"), total + "\n");
            Files.writeString(stateDir.resolve("interactive"), "");
            Files.writeString(stateDir.resolve("regit-action"), stoppedStep.action().name() + "\n");
            Files.writeString(stateDir.resolve("done"),
                    doneLines.isEmpty() ? "" : String.join("\n", doneLines) + "\n");
            var todoText = new StringBuilder();
            for (RebaseStep s : remaining) todoText.append(formatTodoLine(s)).append('\n');
            Files.writeString(stateDir.resolve("git-rebase-todo"), todoText.toString());
        }
    }

    private static List<RebaseStep> collectLinearSteps(String headId, String upstreamId, History history)
            throws IOException {
        List<String> bases = history.mergeBases(headId, upstreamId);
        Set<String> stopSet = new HashSet<>(bases);
        stopSet.add(upstreamId);
        var stack = new ArrayDeque<RebaseStep>();
        String cur = headId;
        while (cur != null && !stopSet.contains(cur)) {
            Revision rev = history.read(cur);
            if (rev.parents().size() > 1) throw new IOException("Rebase requires a linear topic history");
            stack.push(RebaseStep.pick(rev.toCommit(cur)));
            cur = rev.parents().isEmpty() ? null : rev.parents().getFirst();
        }
        return new ArrayList<>(stack);
    }

    /** Reorder {@code fixup!} and {@code squash!} commits matching {@code git rebase -i --autosquash}. */
    static List<RebaseStep> applyAutosquash(List<RebaseStep> original) {
        var result = new ArrayList<RebaseStep>(original.size());
        for (RebaseStep step : original) {
            String subject = step.commit().subject();
            RebaseStep.Action autoAction = null;
            String needle = null;
            if (subject.startsWith("fixup! ")) {
                autoAction = RebaseStep.Action.FIXUP;
                needle = subject.substring(7);
            } else if (subject.startsWith("squash! ")) {
                autoAction = RebaseStep.Action.SQUASH;
                needle = subject.substring(8);
            }
            if (autoAction != null && !needle.isEmpty()) {
                int targetPos = findAutosquashTarget(result, needle);
                if (targetPos >= 0) {
                    int insertAt = targetPos + 1;
                    // Place after any already-attached fixup/squash for the same target
                    while (insertAt < result.size()
                            && (result.get(insertAt).action() == RebaseStep.Action.FIXUP
                                || result.get(insertAt).action() == RebaseStep.Action.SQUASH)) {
                        insertAt++;
                    }
                    result.add(insertAt, new RebaseStep(autoAction, step.commit(), null));
                    continue;
                }
            }
            result.add(step);
        }
        return result;
    }

    private static int findAutosquashTarget(List<RebaseStep> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            RebaseStep candidate = list.get(i);
            if (candidate.action() == RebaseStep.Action.FIXUP || candidate.action() == RebaseStep.Action.SQUASH) continue;
            Commit c = candidate.commit();
            if (c.subject().startsWith(needle)
                    || (needle.length() >= 4 && c.id().startsWith(needle.toLowerCase(Locale.ROOT)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasReorderedOrSquashed(List<RebaseStep> steps) {
        for (RebaseStep s : steps) {
            if (s.action() != RebaseStep.Action.PICK) return true;
        }
        return false;
    }

    private static String formatTodoLine(RebaseStep step) {
        String cmd = switch (step.action()) {
            case PICK -> "pick";
            case REWORD -> "reword";
            case SQUASH -> "squash";
            case FIXUP -> "fixup";
            case DROP -> "drop";
        };
        return cmd + " " + step.commit().id() + " " + step.commit().subject();
    }

    private static List<RebaseStep> parseTodo(Path todoFile, ObjectsDb db) throws IOException {
        var steps = new ArrayList<RebaseStep>();
        if (!Files.exists(todoFile)) return steps;
        for (String raw : Files.readAllLines(todoFile)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) continue;
            RebaseStep.Action action = switch (parts[0]) {
                case "pick", "p" -> RebaseStep.Action.PICK;
                case "reword", "r" -> RebaseStep.Action.REWORD;
                case "squash", "s" -> RebaseStep.Action.SQUASH;
                case "fixup", "f" -> RebaseStep.Action.FIXUP;
                case "drop", "d" -> RebaseStep.Action.DROP;
                default -> throw new IOException("Unsupported rebase todo command: " + parts[0]);
            };
            String sha = resolveTodoSha(parts[1], db);
            Commit commit = Revision.parse(db.read(sha, "commit")).toCommit(sha);
            steps.add(new RebaseStep(action, commit, null));
        }
        return steps;
    }

    private static String resolveTodoSha(String token, ObjectsDb db) throws IOException {
        if (token.length() == 40) return GitFiles.id(token);
        Set<String> matches = db.findByPrefix(token.toLowerCase(Locale.ROOT));
        if (matches.size() != 1) throw new IOException("Cannot resolve rebase todo commit: " + token);
        return matches.iterator().next();
    }

    private static String formatAuthorScript(Identity author) {
        String date = "@" + author.when().toEpochSecond() + " "
                + author.when().format(DateTimeFormatter.ofPattern("xx"));
        return "GIT_AUTHOR_NAME='" + escapeShell(author.name()) + "'\n"
                + "GIT_AUTHOR_EMAIL='" + escapeShell(author.email()) + "'\n"
                + "GIT_AUTHOR_DATE='" + date + "'\n";
    }

    private static String parseAuthorScript(String content) throws IOException {
        String name = null, email = null, date = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("GIT_AUTHOR_NAME=")) name = unquoteShell(line.substring(16));
            else if (line.startsWith("GIT_AUTHOR_EMAIL=")) email = unquoteShell(line.substring(17));
            else if (line.startsWith("GIT_AUTHOR_DATE=")) date = unquoteShell(line.substring(16));
        }
        if (name == null || email == null || date == null) throw new IOException("Invalid rebase author-script");
        String normalizedDate = date.startsWith("@") ? date.substring(1) : date;
        return name + " <" + email + "> " + normalizedDate;
    }

    private static String escapeShell(String value) { return value.replace("'", "'\\''"); }

    private static String unquoteShell(String value) {
        String s = value.strip();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("'\\''", "'");
        }
        return s;
    }

    private static boolean isOriginallyEmpty(ObjectsDb db, String commitId) throws IOException {
        Revision rev = Revision.parse(db.read(commitId, "commit"));
        if (rev.parents().isEmpty()) return false;
        Revision parent = Revision.parse(db.read(rev.parents().getFirst(), "commit"));
        return rev.tree().equals(parent.tree());
    }

    private static void ensureCleanWorktree(Layout layout, WorkingTree work, Map<String, Entry> headFiles) throws IOException {
        Map<String, Entry> staged = Index.read(layout.index());
        if (!staged.equals(headFiles)) throw new IOException("Index contains staged changes");
        for (var e : headFiles.entrySet()) {
            if (!e.getValue().equals(work.inspect(e.getKey(), false))) {
                throw new IOException("Local changes at " + e.getKey());
            }
        }
    }

    private static void checkoutClean(Layout layout, ObjectsDb db, Trees trees, WorkingTree work,
                                      String oldTip, String newTip, WorkingTree.Publish publish) throws IOException {
        var from = snapshot(db, trees, oldTip);
        try (var index = new Lock(layout.index())) {
            var staged = Index.read(layout.index());
            if (!staged.equals(from)) throw new IOException("Index contains staged changes");
            var to = snapshot(db, trees, newTip);
            byte[] encoded = Index.encode(to);
            work.checkout(from, to, () -> {
                byte[] backup = Files.exists(layout.index()) ? Files.readAllBytes(layout.index()) : null;
                index.publish(encoded);
                try { publish.run(); }
                catch (IOException failure) {
                    try (var restore = new Lock(layout.index())) {
                        if (backup == null) Files.delete(layout.index());
                        else restore.publish(backup);
                    }
                    throw failure;
                }
            });
        }
    }

    private static Map<String, Entry> snapshot(ObjectsDb db, Trees trees, String tip) throws IOException {
        return tip == null ? Map.of() : trees.flatten(Revision.parse(db.read(tip, "commit")).tree());
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
