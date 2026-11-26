package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;

/** Handles {@code git merge} modes ({@code --no-ff}, {@code --ff-only}, {@code --no-commit}, conflicts, {@code --abort}) and {@code --ours}/{@code --theirs}. */
final class Merges {
    private Merges() {}

    static MergeResult merge(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge,
                             String sourceId, String sourceLabel, Identity who, String message,
                             MergeOptions options) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        Objects.requireNonNull(who, "committer");
        Objects.requireNonNull(options, "options");
        var work = new WorkingTree(layout.root(), db);
        Optional<String> attached = layout.currentBranchOptional();
        try (var head = new Lock(layout.head());
             var ref = attached.isPresent() ? new Lock(layout.ref(attached.get())) : null) {
            String old = attached.isPresent()
                    ? layout.tip(attached.get())
                    : new Revisions(layout, db).resolveHeadOptional();
            var history = new History(db);

            // Unborn branch
            if (old == null) {
                if (options.mode() == MergeOptions.Mode.NO_FF) {
                    throw new IOException("Cannot create non-fast-forward merge on unborn branch");
                }
                checkoutAndPublish(layout, db, trees, work, null, sourceId, () -> publishHead(head, ref, sourceId));
                return MergeResult.of(MergeResult.Status.FAST_FORWARD,
                        Optional.of(Revision.parse(db.read(sourceId, "commit")).toCommit(sourceId)), Set.of());
            }

            // Already up to date
            if (old.equals(sourceId) || history.contains(old, sourceId)) {
                return MergeResult.of(MergeResult.Status.ALREADY_UP_TO_DATE,
                        Optional.of(Revision.parse(db.read(old, "commit")).toCommit(old)), Set.of());
            }

            // Fast-forward possible?
            boolean canFastForward = history.contains(sourceId, old);
            if (canFastForward && options.mode() != MergeOptions.Mode.NO_FF) {
                checkoutAndPublish(layout, db, trees, work, old, sourceId, () -> publishHead(head, ref, sourceId));
                return MergeResult.of(MergeResult.Status.FAST_FORWARD,
                        Optional.of(Revision.parse(db.read(sourceId, "commit")).toCommit(sourceId)), Set.of());
            }
            if (!canFastForward && options.mode() == MergeOptions.Mode.FF_ONLY) {
                throw new IOException("Not possible to fast-forward, aborting");
            }

            Map<String, Entry> oursMap = snapshot(db, trees, old);
            Map<String, Entry> theirsMap = snapshot(db, trees, sourceId);
            Map<String, Entry> baseMap = canFastForward
                    ? oursMap
                    : snapshot(db, trees, history.base(old, sourceId));

            var outcome = new ThreeWay(db, contentMerge).mergeWithConflicts(
                    baseMap, oursMap, theirsMap, "HEAD", sourceLabel, options.isAllowConflicts());

            String mergeMsg = message != null ? message : ("Merge " + sourceLabel + "\n");
            if (outcome.hasConflicts()) {
                writeConflictState(layout, db, work, oursMap, outcome, old, sourceId, mergeMsg);
                return MergeResult.of(MergeResult.Status.CONFLICTED, Optional.empty(), outcome.conflicts().keySet());
            }

            if (options.isNoCommit()) {
                checkoutAndPublish(layout, db, trees, work, old, outcome.resolved(), () -> {
                    Files.writeString(layout.admin().resolve("ORIG_HEAD"), old + "\n");
                    Files.writeString(layout.admin().resolve("MERGE_HEAD"), sourceId + "\n");
                    Files.writeString(layout.admin().resolve("MERGE_MSG"), mergeMsg);
                });
                return MergeResult.of(MergeResult.Status.STAGED_NO_COMMIT, Optional.empty(), Set.of());
            }

            String tip = new Revision(trees.write(outcome.resolved()), List.of(old, sourceId),
                    who.header(), mergeMsg).write(db, who);
            checkoutAndPublish(layout, db, trees, work, old, tip, () -> publishHead(head, ref, tip));
            return MergeResult.of(MergeResult.Status.MERGED,
                    Optional.of(Revision.parse(db.read(tip, "commit")).toCommit(tip)), Set.of());
        }
    }

    static void abortMerge(Layout layout, ObjectsDb db, Trees trees) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        Path mergeHead = layout.admin().resolve("MERGE_HEAD");
        if (!Files.exists(mergeHead)) throw new IOException("There is no merge to abort");
        var work = new WorkingTree(layout.root(), db);
        try (var index = new Lock(layout.index())) {
            String headId = new Revisions(layout, db).resolveHeadOptional();
            Map<String, Entry> headFiles = snapshot(db, trees, headId);
            var tracked = new HashSet<String>(headFiles.keySet());
            for (Index.EntryRecord rec : Index.readAll(layout.index())) tracked.add(rec.path());
            work.forceReset(tracked, headFiles);
            index.publish(Index.encode(layout, db, headFiles));
            Files.deleteIfExists(mergeHead);
            Files.deleteIfExists(layout.admin().resolve("MERGE_MSG"));
            Files.deleteIfExists(layout.admin().resolve("MERGE_MODE"));
            Files.deleteIfExists(layout.admin().resolve("ORIG_HEAD"));
        }
    }

    /** Materialize stage 2 ({@code --ours}) or stage 3 ({@code --theirs}) into the working tree. */
    static void checkoutConflictSide(Layout layout, ObjectsDb db, int stage, List<String> paths) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
        var work = new WorkingTree(layout.root(), db);
        String label = stage == 2 ? "ours" : "theirs";
        var entries = new LinkedHashMap<String, Entry>();
        for (String path : paths) {
            Map<Integer, Entry> stages = Index.conflictStages(layout.index(), path);
            Entry side = stages.get(stage);
            if (side == null) throw new IOException("Path does not have " + label + " conflict stage: " + path);
            entries.put(path, side);
        }
        for (var e : entries.entrySet()) {
            work.materializeEntry(e.getKey(), e.getValue());
        }
    }

    private static void writeConflictState(Layout layout, ObjectsDb db, WorkingTree work, Map<String, Entry> oursMap,
                                           ThreeWay.Outcome outcome, String oldHead, String mergeHead,
                                           String mergeMsg) throws IOException {
        try (var index = new Lock(layout.index())) {
            var staged = Index.read(layout.index());
            if (!staged.equals(oursMap)) throw new IOException("Index contains staged changes");
            // Preflight clean working files for all modified/deleted paths before mutating
            var touched = new HashSet<String>(oursMap.keySet());
            touched.addAll(outcome.resolved().keySet());
            touched.addAll(outcome.conflicts().keySet());
            for (String path : touched) {
                Entry old = oursMap.get(path);
                if (old != null && (!Objects.equals(old, outcome.resolved().get(path)) || outcome.conflicts().containsKey(path))) {
                    if (!old.equals(work.inspect(path, false))) throw new IOException("Local changes at " + path);
                }
            }
            // Apply resolved entries and remove deleted entries
            var trackedToReset = new HashSet<String>(oursMap.keySet());
            trackedToReset.removeAll(outcome.conflicts().keySet());
            work.forceReset(trackedToReset, outcome.resolved());
            for (var e : outcome.conflicts().entrySet()) {
                ThreeWay.Conflict c = e.getValue();
                int mode = c.ours() != null ? c.ours().mode()
                        : (c.theirs() != null ? c.theirs().mode() : Entry.FILE);
                work.writeRaw(e.getKey(), c.worktreeContent(), mode);
            }
            index.publish(Index.encodeRecords(layout, db, Index.combineWithConflicts(outcome.resolved(), outcome.conflicts())));
            Files.writeString(layout.admin().resolve("ORIG_HEAD"), oldHead + "\n");
            Files.writeString(layout.admin().resolve("MERGE_HEAD"), mergeHead + "\n");
            Files.writeString(layout.admin().resolve("MERGE_MSG"), mergeMsg);
        }
    }

    private static void publishHead(Lock head, Lock ref, String tip) throws IOException {
        if (ref != null) ref.publish(tip + "\n");
        else head.publish(tip + "\n");
    }

    private static void checkoutAndPublish(Layout layout, ObjectsDb db, Trees trees, WorkingTree work,
                                           String oldTip, String newTip, WorkingTree.Publish publish) throws IOException {
        checkoutAndPublish(layout, db, trees, work, oldTip, snapshot(db, trees, newTip), publish);
    }

    private static void checkoutAndPublish(Layout layout, ObjectsDb db, Trees trees, WorkingTree work,
                                           String oldTip, Map<String, Entry> to, WorkingTree.Publish publish) throws IOException {
        var from = snapshot(db, trees, oldTip);
        try (var index = new Lock(layout.index())) {
            var staged = Index.read(layout.index());
            if (!staged.equals(from)) throw new IOException("Index contains staged changes");
            work.checkout(from, to, () -> {
                byte[] backup = Files.exists(layout.index()) ? Files.readAllBytes(layout.index()) : null;
                index.publish(Index.encode(layout, db, to)); // after checkout, so stat data matches the new files
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
}
