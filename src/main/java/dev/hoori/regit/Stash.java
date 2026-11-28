package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Handles {@code git stash} ({@code push}, {@code list}, {@code apply}, {@code pop}, {@code drop}, {@code clear}). */
final class Stash {
    private static final String ZERO_ID = "0".repeat(40);
    private static final String STASH_REF = "refs/stash";

    private Stash() {}

    static Optional<StashEntry> push(Layout layout, ObjectsDb db, Trees trees, Identity who, StashOptions options)
            throws IOException {
        requireWorktree(layout);
        Objects.requireNonNull(who, "who");
        Objects.requireNonNull(options, "options");
        layout.ready();
        var work = new WorkingTree(layout.root(), db);
        try (var indexLock = new Lock(layout.index())) {
            var revisions = new Revisions(layout, db);
            String headId = revisions.resolveHeadOptional();
            if (headId == null) throw new IOException("Cannot stash on an unborn branch");

            List<Index.EntryRecord> rawIndex = Index.readAll(layout.index());
            var stage0 = new TreeMap<String, Index.EntryRecord>(Entry.BYTE_ORDER);
            for (Index.EntryRecord rec : rawIndex) {
                if (rec.stage() != 0) throw new IOException("Unresolved conflict in index: " + rec.path());
                stage0.put(rec.path(), rec);
            }

            Commit headCommit = Revision.parse(db.read(headId, "commit")).toCommit(headId);
            Map<String, Entry> headFiles = trees.flatten(headCommit.tree());
            Status status = Status.scan(layout, work, headFiles, rawIndex);
            // Like git, leave embedded repositories alone: their files are not ours to stash or delete.
            var untracked = new TreeSet<String>(Entry.BYTE_ORDER);
            for (String path : status.untracked()) if (!insideNestedRepository(layout.root(), path)) untracked.add(path);

            boolean hasChanges = !status.staged().isEmpty()
                    || !status.unstaged().isEmpty()
                    || (options.isIncludeUntracked() && !untracked.isEmpty());
            if (!hasChanges) return Optional.empty();

            String branchLabel = layout.currentBranchOptional().orElse("(no branch)");
            String abbrev = revisions.abbreviate(headId, 7);
            String subject = headCommit.subject();
            String custom = options.message() == null ? null : options.message().stripTrailing();
            String wMessage = (custom != null && !custom.isEmpty())
                    ? "On " + branchLabel + ": " + custom
                    : "WIP on " + branchLabel + ": " + abbrev + " " + subject;
            String wLine = wMessage.strip().replaceAll("\\s+", " "); // reflog messages are one normalized line

            // 1. Index commit i
            var indexFiles = new HashMap<String, Entry>(stage0.size());
            for (var e : stage0.entrySet()) {
                if (!e.getValue().intentToAdd()) indexFiles.put(e.getKey(), e.getValue().entry());
            }
            var cache = indexFiles.size() == stage0.size()
                    ? new HashMap<>(Index.cacheTree(layout.index())) : new HashMap<String, String>();
            String iTree = trees.write(indexFiles, cache);
            String iMsg = "index on " + branchLabel + ": " + abbrev + " " + subject + "\n";
            String iId = new Revision(iTree, List.of(headId), who.header(), iMsg).write(db, who);

            // 2. Working tree snapshot for w
            var worktreeRecords = new TreeMap<>(stage0);
            // A HEAD path removed from the index but present on disk is saved too, as git's diff against HEAD does.
            for (var e : headFiles.entrySet())
                worktreeRecords.putIfAbsent(e.getKey(), new Index.EntryRecord(e.getKey(), e.getValue(), 0, false));
            worktreeRecords.keySet().removeIf(path -> {
                try { return Files.isDirectory(work.resolve(path), NOFOLLOW_LINKS); }
                catch (IOException e) { return false; }
            });
            new Staging(layout, db).stageUpdatedUnlocked(work, worktreeRecords, List.of());
            var worktreeFiles = new HashMap<String, Entry>(worktreeRecords.size());
            for (var e : worktreeRecords.entrySet()) {
                worktreeFiles.put(e.getKey(), e.getValue().entry());
            }
            // 3. Optional untracked commit u (files w already holds are not saved twice)
            var untrackedFiles = new TreeMap<String, Entry>(Entry.BYTE_ORDER);
            String uId = null;
            if (options.isIncludeUntracked()) {
                for (String path : untracked) {
                    if (worktreeFiles.containsKey(path)) continue;
                    Entry entry = work.inspect(path, true);
                    if (entry != null) untrackedFiles.put(path, entry);
                }
                String uTree = trees.write(untrackedFiles);
                String uMsg = "untracked files on " + branchLabel + ": " + abbrev + " " + subject + "\n";
                uId = new Revision(uTree, List.of(), who.header(), uMsg).write(db, who);
            }

            String wTree = trees.write(worktreeFiles);
            List<String> parents = uId == null ? List.of(headId, iId) : List.of(headId, iId, uId);
            String wId = new Revision(wTree, parents, who.header(), wMessage).write(db, who); // no newline, as git

            var changed = new HashSet<String>(status.staged().keySet());
            changed.addAll(status.unstaged().keySet());
            var restore = new HashMap<String, Entry>();
            for (String path : changed) if (headFiles.containsKey(path)) restore.put(path, headFiles.get(path));
            var removed = new HashSet<String>(untrackedFiles.keySet()); // what the cleanup and reset delete
            for (String path : changed) if (!headFiles.containsKey(path)) removed.add(path);
            for (String path : restore.keySet()) requireRestorable(layout.root(), work, path, removed);

            // 4. Update refs/stash and append to logs/refs/stash
            Path refPath = stashRef(layout);
            Path logPath = stashLog(layout);
            try (var refLock = new Lock(refPath); var logLock = new Lock(logPath)) {
                String oldId = new Revisions(layout, db).readRef(STASH_REF, 0);
                if (oldId == null) oldId = ZERO_ID;
                String existing = Files.exists(logPath) ? Files.readString(logPath, UTF_8) : "";
                if (!existing.isEmpty() && !existing.endsWith("\n")) existing += "\n";
                String newLine = oldId + " " + wId + " " + who.header() + "\t" + wLine + "\n";
                logLock.publish(existing + newLine);
                refLock.publish(wId + "\n");
            }

            // 5. Remove stashed untracked files (and empty parent dirs), then reset worktree and index to HEAD
            if (!untrackedFiles.isEmpty()) {
                deleteFilesAndEmptyParents(layout.root(), work, untrackedFiles.keySet());
            }
            // A staged-new file that is no longer on disk (a directory or file now stands there) needs no deletion.
            changed.removeIf(path -> !headFiles.containsKey(path) && !isFileOrLink(work, path));
            // Delete staged-new files first (their emptied directories go with them), then the directories
            // left where HEAD has a file, which hold nothing but removed files (checked above).
            var added = new HashSet<String>(changed);
            added.removeAll(restore.keySet());
            work.forceReset(added, Map.of());
            changed.removeAll(added);
            for (String path : restore.keySet()) {
                if (isDirectory(work, path)) deleteEmptyTree(work.resolve(path));
            }
            var written = work.forceReset(changed, restore);
            indexLock.publish(Index.encode(layout, db, headFiles, written));

            Commit wCommit = Revision.parse(db.read(wId, "commit")).toCommit(wId);
            return Optional.of(new StashEntry(0, wCommit, wLine, parseBranch(wLine), who.when().withNano(0)));
        }
    }

    static List<StashEntry> list(Layout layout, ObjectsDb db) throws IOException {
        List<ReflogLine> lines = readLog(stashLog(layout));
        if (lines.isEmpty()) return List.of();
        var entries = new ArrayList<StashEntry>(lines.size());
        for (int i = lines.size() - 1; i >= 0; i--) {
            ReflogLine line = lines.get(i);
            int stashIndex = lines.size() - 1 - i;
            Commit commit = Revision.parse(db.read(line.newId(), "commit")).toCommit(line.newId());
            entries.add(new StashEntry(stashIndex, commit, line.message(), parseBranch(line.message()), line.when()));
        }
        return Collections.unmodifiableList(entries);
    }

    static StashApplyResult apply(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge, int index)
            throws IOException {
        requireWorktree(layout);
        return apply(layout, db, trees, contentMerge, entryId(layout, index));
    }

    private static String entryId(Layout layout, int index) throws IOException {
        List<ReflogLine> logLines = readLog(stashLog(layout));
        if (index < 0 || index >= logLines.size()) throw new IOException("Invalid stash index: " + index);
        return logLines.get(logLines.size() - 1 - index).newId();
    }

    private static StashApplyResult apply(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge, String wId)
            throws IOException {
        requireWorktree(layout);
        layout.ready();
        Revision w = Revision.parse(db.read(wId, "commit"));
        if (w.parents().size() < 2) throw new IOException("Invalid stash commit: " + wId);

        String baseId = w.parents().get(0);
        String uId = w.parents().size() >= 3 ? w.parents().get(2) : null;

        var work = new WorkingTree(layout.root(), db);
        try (var indexLock = new Lock(layout.index())) {
            String headId = new Revisions(layout, db).resolveHeadOptional();
            if (headId == null) throw new IOException("Cannot apply stash on an unborn branch");
            Map<String, Entry> headFiles = trees.flatten(Revision.parse(db.read(headId, "commit")).tree());
            Map<String, Index.EntryRecord> staged = Index.readStage0(layout.index());
            if (!Index.matches(staged, headFiles) || staged.values().stream().anyMatch(Index.EntryRecord::intentToAdd))
                throw new IOException("Index contains staged changes");

            Map<String, Entry> baseFiles = trees.flatten(Revision.parse(db.read(baseId, "commit")).tree());
            Map<String, Entry> theirsFiles = trees.flatten(w.tree());
            Map<String, Entry> untrackedFiles = uId == null
                    ? Map.of() : trees.flatten(Revision.parse(db.read(uId, "commit")).tree());

            var outcome = new ThreeWay(db, contentMerge).mergeWithConflicts(
                    baseFiles, headFiles, theirsFiles, "Updated upstream", "Stashed changes", true);

            // Compute exact worktree delta relative to HEAD so local edits in unrelated files survive
            var toDelete = new HashSet<String>();
            for (String path : headFiles.keySet()) {
                if (!outcome.resolved().containsKey(path) && !outcome.conflicts().containsKey(path)) {
                    toDelete.add(path);
                }
            }
            var toWrite = new HashMap<String, Entry>();
            for (var e : outcome.resolved().entrySet()) {
                if (!Objects.equals(headFiles.get(e.getKey()), e.getValue())) {
                    toWrite.put(e.getKey(), e.getValue());
                }
            }

            // Preflight: every tracked path changed or conflicted must be clean in the working tree,
            // and new paths must not clobber existing untracked files
            var touched = new HashSet<String>(toDelete);
            touched.addAll(toWrite.keySet());
            touched.addAll(outcome.conflicts().keySet());
            for (String path : touched) {
                Entry old = headFiles.get(path);
                if (old != null) {
                    work.requireClean(path, old, staged.get(path));
                } else {
                    preflightNewPath(layout.root(), work, path, toDelete);
                }
            }

            // Preflight: every untracked file restored from u must not exist yet
            var mergedDirectories = new HashSet<String>(); // every directory the merge result keeps a path in
            if (!untrackedFiles.isEmpty()) {
                for (var merged : List.of(outcome.resolved().keySet(), outcome.conflicts().keySet()))
                    for (String tracked : merged)
                        for (int slash = tracked.indexOf('/'); slash >= 0; slash = tracked.indexOf('/', slash + 1))
                            mergedDirectories.add(tracked.substring(0, slash));
            }
            for (String path : untrackedFiles.keySet()) {
                if (outcome.resolved().containsKey(path) || outcome.conflicts().containsKey(path))
                    throw new IOException("Untracked file already exists: " + path);
                if (mergedDirectories.contains(path)) throw new IOException("Untracked file blocked by tracked files in " + path);
                Path file = work.resolve(path);
                for (Path p = file.getParent(); !p.equals(layout.root()); p = p.getParent()) {
                    String rel = layout.root().relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
                    if (outcome.resolved().containsKey(rel) || outcome.conflicts().containsKey(rel))
                        throw new IOException("Untracked file blocked by existing file: " + path);
                }
                preflightNewPath(layout.root(), work, path, toDelete); // existing files only where apply removes them
            }

            // Apply worktree changes: delete removed tracked paths, write modified/added tracked paths.
            // Directories still standing where a file goes are empty now (checked above) and are replaced.
            work.forceReset(toDelete, Map.of());
            var targets = new HashSet<String>(toWrite.keySet());
            targets.addAll(outcome.conflicts().keySet());
            targets.addAll(untrackedFiles.keySet());
            for (String path : targets) if (isDirectory(work, path)) deleteEmptyTree(work.resolve(path));
            var created = new HashSet<>(toWrite.keySet()); // paths not in HEAD were preflighted as absent
            created.removeIf(headFiles::containsKey);
            Map<String, Index.Stat> written = work.forceReset(Set.of(), toWrite, created);

            // Write conflicted files with conflict markers
            for (var e : outcome.conflicts().entrySet()) {
                ThreeWay.Conflict c = e.getValue();
                int mode = c.ours() != null ? c.ours().mode()
                        : (c.theirs() != null ? c.theirs().mode() : Entry.FILE);
                work.writeRaw(e.getKey(), c.worktreeContent(), mode);
            }

            // Restore untracked files from u
            for (var e : untrackedFiles.entrySet()) {
                work.materializeEntry(e.getKey(), e.getValue(), true); // preflighted as absent
            }

            // Build new index: stage-0 = HEAD entries (minus conflicts) + new paths relative to HEAD,
            // plus conflict stages 1..3 for conflicted paths. Only pass written stats for entries whose
            // index blob matches the file just written (i.e. newly added paths), never for HEAD entries
            // whose working-tree file now holds stashed content.
            // On conflict git leaves the cleanly merged paths staged, so callers that stage only the
            // conflicts still commit everything.
            Map<String, Entry> stage0;
            var matchingStats = new HashMap<String, Index.Stat>();
            if (!outcome.conflicts().isEmpty()) {
                stage0 = outcome.resolved();
                matchingStats.putAll(written);
            } else {
                stage0 = new HashMap<>(headFiles);
                var added = new TreeSet<String>(Entry.BYTE_ORDER);
                for (var e : outcome.resolved().entrySet()) {
                    if (!headFiles.containsKey(e.getKey())) {
                        stage0.put(e.getKey(), e.getValue());
                        added.add(e.getKey());
                        Index.Stat stat = written.get(e.getKey());
                        if (stat != null) matchingStats.put(e.getKey(), stat);
                    }
                }
                // A new path that replaces a deleted HEAD file or directory keeps that deletion staged,
                // or the index would hold both d and d/f.
                stage0.keySet().removeIf(path -> !outcome.resolved().containsKey(path) && collides(path, added));
            }

            indexLock.publish(Index.encodeWithTrees(layout, db,
                    Index.combineWithConflicts(stage0, outcome.conflicts(), matchingStats), false).bytes());
            return StashApplyResult.of(outcome.conflicts().keySet());
        }
    }

    static StashApplyResult pop(Layout layout, ObjectsDb db, Trees trees, ContentMerge contentMerge, int index)
            throws IOException {
        requireWorktree(layout);
        // The stash locks stay held from picking the entry until dropping it, so a push from another
        // worktree cannot shift stash@{index} in between.
        try (var refLock = new Lock(stashRef(layout)); var logLock = new Lock(stashLog(layout))) {
            List<ReflogLine> entries = readLog(stashLog(layout));
            if (index < 0 || index >= entries.size()) throw new IOException("Invalid stash index: " + index);
            StashApplyResult result = apply(layout, db, trees, contentMerge, entries.get(entries.size() - 1 - index).newId());
            if (!result.isConflicted()) drop(layout, refLock, logLock, entries, index);
            return result;
        }
    }

    static void drop(Layout layout, int index) throws IOException {
        try (var refLock = new Lock(stashRef(layout)); var logLock = new Lock(stashLog(layout))) {
            List<ReflogLine> entries = readLog(stashLog(layout));
            if (index < 0 || index >= entries.size()) throw new IOException("Invalid stash index: " + index);
            drop(layout, refLock, logLock, entries, index);
        }
    }

    private static void drop(Layout layout, Lock refLock, Lock logLock, List<ReflogLine> entries, int index)
            throws IOException {
        {
            if (entries.size() == 1) {
                Remotes.deleteRefFileAndPacked(layout, STASH_REF, stashRef(layout));
                Files.deleteIfExists(stashLog(layout));
                return;
            }
            int dropIdx = entries.size() - 1 - index;
            ReflogLine dropped = entries.get(dropIdx);
            var kept = new ArrayList<ReflogLine>(entries.size() - 1);
            for (int i = 0; i < entries.size(); i++) {
                if (i == dropIdx) continue;
                ReflogLine line = entries.get(i);
                if (i == dropIdx + 1) {
                    line = new ReflogLine(dropped.oldId(), line.newId(), line.rest(), line.message(), line.when());
                }
                kept.add(line);
            }
            // Log first: git appends to this log under refs/stash.lock, so that lock must outlive the log write.
            logLock.publish(formatLog(kept));
            refLock.publish(kept.getLast().newId() + "\n");
        }
    }

    static void clear(Layout layout, ObjectsDb db) throws IOException {
        Path refPath = stashRef(layout);
        Path logPath = stashLog(layout);
        if (!Files.exists(logPath) && new Revisions(layout, db).readRef(STASH_REF, 0) == null) return;
        try (var refLock = new Lock(refPath); var logLock = new Lock(logPath)) {
            Remotes.deleteRefFileAndPacked(layout, STASH_REF, refPath);
            Files.deleteIfExists(logPath);
        }
    }

    private static void requireWorktree(Layout layout) throws IOException {
        if (layout.root() == null) throw new IOException("Operation requires a worktree");
    }

    private static Path stashRef(Layout layout) { return layout.common().resolve(STASH_REF); }

    private static Path stashLog(Layout layout) { return layout.common().resolve("logs/" + STASH_REF); }

    private static void preflightNewPath(Path root, WorkingTree work, String name, Set<String> toDelete)
            throws IOException {
        Path file = work.resolve(name);
        if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
            requireRemovable(file);
            try (var contents = Files.walk(file)) {
                for (Path child : contents.toList()) {
                    String rel = root.relativize(child).toString().replace(child.getFileSystem().getSeparator(), "/");
                    if (Files.isDirectory(child, NOFOLLOW_LINKS)) continue; // removed below once empty
                    if (!toDelete.contains(rel)) {
                        throw new IOException("Untracked path " + rel);
                    }
                }
            }
        } else if (Files.exists(file, NOFOLLOW_LINKS)) {
            throw new IOException("Untracked path " + name);
        }
        for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) {
            String rel = root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
            if (Files.exists(p, NOFOLLOW_LINKS) && !Files.isDirectory(p, NOFOLLOW_LINKS) && !toDelete.contains(rel)) {
                throw new IOException("Checkout blocked by " + rel);
            }
        }
    }

    private static void deleteFilesAndEmptyParents(Path root, WorkingTree work, Collection<String> paths)
            throws IOException {
        var directories = new TreeSet<Path>(Comparator.comparingInt(Path::getNameCount)
                .reversed().thenComparing(Comparator.naturalOrder()));
        for (String name : paths) {
            Path file = work.resolve(name);
            if (Files.deleteIfExists(file)) {
                for (Path p = file.getParent(); !p.equals(root); p = p.getParent()) directories.add(p);
            }
        }
        for (Path dir : directories) {
            try { WorkingTree.removeDirectory(dir); }
            catch (IOException keep) {} // not empty, gone, no longer a directory, or not safely removable: leave it
        }
    }

    /** Delete a tree of directories; a file found there (it appeared after our checks) is left alone. */
    private static void deleteEmptyTree(Path top) throws IOException {
        try (var dirs = Files.walk(top)) {
            for (Path dir : dirs.sorted(Comparator.reverseOrder()).toList()) WorkingTree.removeDirectory(dir);
        }
    }

    /** Fail before any change when {@link WorkingTree#removeDirectory} could not remove {@code dir} later. */
    private static void requireRemovable(Path dir) throws IOException {
        try (var parent = Files.newDirectoryStream(dir.getParent())) {
            if (!(parent instanceof SecureDirectoryStream)) throw new IOException("Cannot safely remove directory " + dir);
        }
    }

    private static boolean isFileOrLink(WorkingTree work, String path) {
        try {
            Path file = work.resolve(path);
            return Files.exists(file, NOFOLLOW_LINKS) && !Files.isDirectory(file, NOFOLLOW_LINKS);
        } catch (IOException e) { return false; }
    }

    private static boolean isDirectory(WorkingTree work, String path) {
        try { return Files.isDirectory(work.resolve(path), NOFOLLOW_LINKS); }
        catch (IOException e) { return false; }
    }

    /** The reset can write HEAD's {@code path}: nothing but files about to be removed is in its way. */
    private static void requireRestorable(Path root, WorkingTree work, String path, Set<String> removed)
            throws IOException {
        Path file = work.resolve(path);
        if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
            requireRemovable(file);
            try (var contents = Files.walk(file)) {
                for (Path child : contents.filter(c -> !Files.isDirectory(c, NOFOLLOW_LINKS)).toList()) {
                    String rel = root.relativize(child).toString().replace(child.getFileSystem().getSeparator(), "/");
                    if (!removed.contains(rel)) throw new IOException("Cannot stash: " + rel + " is in the way of " + path);
                }
            }
        }
        for (Path dir = file.getParent(); !dir.equals(root); dir = dir.getParent()) {
            String rel = root.relativize(dir).toString().replace(dir.getFileSystem().getSeparator(), "/");
            if (Files.exists(dir, NOFOLLOW_LINKS) && !Files.isDirectory(dir, NOFOLLOW_LINKS) && !removed.contains(rel))
                throw new IOException("Cannot stash: " + rel + " is in the way of " + path);
        }
    }

    private static boolean insideNestedRepository(Path root, String path) {
        for (Path dir = root.resolve(path).getParent(); dir != null && !dir.equals(root); dir = dir.getParent())
            if (Files.exists(dir.resolve(".git"), NOFOLLOW_LINKS)) return true;
        return false;
    }

    /** {@code path} is an ancestor directory of an added path, or lies inside an added file's path. */
    private static boolean collides(String path, NavigableSet<String> added) {
        String under = added.ceiling(path + "/");
        if (under != null && under.startsWith(path + "/")) return true;
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1))
            if (added.contains(path.substring(0, slash))) return true;
        return false;
    }

    static Optional<String> parseBranch(String message) {
        String rest;
        if (message.startsWith("WIP on ")) rest = message.substring(7);
        else if (message.startsWith("On ")) rest = message.substring(3);
        else return Optional.empty();
        int colon = rest.indexOf(':');
        if (colon <= 0) return Optional.empty();
        String branch = rest.substring(0, colon);
        return "(no branch)".equals(branch) ? Optional.empty() : Optional.of(branch);
    }

    private record ReflogLine(String oldId, String newId, String rest, String message, OffsetDateTime when) {}

    private static List<ReflogLine> readLog(Path logPath) throws IOException {
        if (!Files.isRegularFile(logPath)) return List.of();
        var result = new ArrayList<ReflogLine>();
        for (String raw : Files.readAllLines(logPath, UTF_8)) {
            if (raw.isEmpty()) continue;
            result.add(parseReflogLine(raw));
        }
        return result;
    }

    private static ReflogLine parseReflogLine(String line) throws IOException {
        if (line.length() < 83 || line.charAt(40) != ' ' || line.charAt(81) != ' ') {
            throw new IOException("Invalid stash reflog entry: " + line);
        }
        String oldId = GitFiles.id(line.substring(0, 40));
        String newId = GitFiles.id(line.substring(41, 81));
        String rest = line.substring(82);
        int tab = rest.indexOf('\t');
        String identityHeader = tab < 0 ? rest : rest.substring(0, tab);
        String message = tab < 0 ? "" : rest.substring(tab + 1);
        OffsetDateTime when = parseReflogTimestamp(identityHeader);
        return new ReflogLine(oldId, newId, rest, message, when);
    }

    private static OffsetDateTime parseReflogTimestamp(String identityHeader) throws IOException {
        int close = identityHeader.lastIndexOf('>');
        if (close < 0 || close + 2 >= identityHeader.length() || identityHeader.charAt(close + 1) != ' ') {
            throw new IOException("Invalid reflog identity: " + identityHeader);
        }
        String tail = identityHeader.substring(close + 2);
        int space = tail.indexOf(' ');
        if (space <= 0 || space + 6 != tail.length()) {
            throw new IOException("Invalid reflog timestamp: " + identityHeader);
        }
        try {
            long epoch = Long.parseLong(tail.substring(0, space));
            String tz = tail.substring(space + 1);
            if ((tz.charAt(0) != '+' && tz.charAt(0) != '-')
                    || !tz.substring(1).chars().allMatch(Character::isDigit)) {
                throw new IOException("Invalid reflog timezone: " + identityHeader);
            }
            return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epoch), ZoneOffset.of(tz));
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IOException("Invalid reflog timestamp: " + identityHeader, e);
        }
    }

    private static String formatLog(List<ReflogLine> lines) {
        var out = new StringBuilder();
        for (ReflogLine line : lines) {
            out.append(line.oldId()).append(' ').append(line.newId()).append(' ').append(line.rest()).append('\n');
        }
        return out.toString();
    }
}
