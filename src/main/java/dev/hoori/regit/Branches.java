package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.*;

/** Local branch listing, creation, force-update, rename, deletion and query operations. */
final class Branches {
    static SortedSet<String> names(Layout layout) throws IOException {
        var names = new TreeSet<String>();
        Path heads = layout.common().resolve("refs/heads");
        if (Files.isDirectory(heads)) {
            try (var paths = Files.walk(heads)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String name = heads.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                    if (!name.endsWith(".lock")) names.add(branch(name));
                }
            }
        }
        var packed = layout.common().resolve("packed-refs");
        if (Files.exists(packed)) {
            try (var lines = Files.lines(packed)) {
                for (String line : lines.filter(l -> l.startsWith(" refs/heads/", 40)).toList())
                    names.add(branch(line.substring(52)));
            }
        }
        return Collections.unmodifiableSortedSet(names);
    }

    static void checkNew(Layout layout, String name) throws IOException {
        branch(name);
        for (String other : names(layout))
            if (other.equals(name) || other.startsWith(name + "/") || name.startsWith(other + "/"))
                throw new IOException("Branch name collides with " + other);
    }

    static void checkNamespaceForExistingOrNew(Layout layout, String name) throws IOException {
        branch(name);
        for (String other : names(layout))
            if (!other.equals(name) && (other.startsWith(name + "/") || name.startsWith(other + "/")))
                throw new IOException("Branch name collides with " + other);
    }

    static void prepareNew(Layout layout, String name) throws IOException {
        checkNew(layout, name);
        clearEmptyRefDir(layout, name);
    }

    static void clearEmptyRefDir(Layout layout, String name) throws IOException {
        clearEmptyRefDir(layout.ref(name));
    }

    static void clearEmptyRefDir(Path target) throws IOException {
        // Git can leave empty ref directories behind after packing or deleting their children.
        if (Files.isDirectory(target)) {
            try (var paths = Files.walk(target)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Ref namespace in use");
                    Files.delete(path);
                }
            }
        }
    }

    static void createAt(Layout layout, String name, String targetCommitId, boolean force) throws IOException {
        if (force) {
            checkNamespaceForExistingOrNew(layout, name);
            if (layout.tip(name) != null) layout.available(name);
        } else {
            checkNew(layout, name);
        }
        try (var ref = new Lock(layout.ref(name))) {
            clearEmptyRefDir(layout, name);
            ref.publish(GitFiles.id(targetCommitId) + "\n");
        }
    }

    static void delete(Layout layout, String name, ObjectsDb db, boolean force) throws IOException {
        try (var ref = new Lock(layout.ref(name)); var head = new Lock(layout.head())) {
            layout.available(name);
            String tip = layout.require(name);
            if (!force) {
                String headTip = new Revisions(layout, db).resolveHeadOptional();
                if (headTip == null || !new History(db).contains(headTip, tip)) {
                    throw new IOException("Branch has unmerged commits: " + name);
                }
            }
            Remotes.deleteRefFileAndPacked(layout, "refs/heads/" + name, layout.ref(name));
            GitConfig.removeBranchSection(layout, name);
        }
    }

    static void rename(Layout layout, String oldName, String newName) throws IOException {
        GitFiles.branch(oldName);
        GitFiles.branch(newName);
        if (oldName.equals(newName)) return;
        String tip = layout.require(oldName);
        checkNew(layout, newName);
        try (var newRef = new Lock(layout.ref(newName)); var oldRef = new Lock(layout.ref(oldName))) {
            clearEmptyRefDir(layout, newName);
            newRef.publish(tip + "\n");
            // Update HEAD in main worktree and any linked worktree pointing to oldName
            String oldSymbolic = "ref: refs/heads/" + oldName;
            String newSymbolic = "ref: refs/heads/" + newName + "\n";
            updateHeadIfMatching(layout.common().resolve("HEAD"), oldSymbolic, newSymbolic);
            Path linked = layout.common().resolve("worktrees");
            if (Files.isDirectory(linked)) {
                try (var dirs = Files.list(linked)) {
                    for (Path dir : dirs.toList()) {
                        updateHeadIfMatching(dir.resolve("HEAD"), oldSymbolic, newSymbolic);
                    }
                }
            }
            Remotes.deleteRefFileAndPacked(layout, "refs/heads/" + oldName, layout.ref(oldName));
            GitConfig.renameBranchSection(layout, oldName, newName);
        }
    }

    private static void updateHeadIfMatching(Path headFile, String expected, String replacement) throws IOException {
        if (Files.isRegularFile(headFile) && line(headFile).equals(expected)) {
            try (var lock = new Lock(headFile)) {
                lock.publish(replacement);
            }
        }
    }

    static List<BranchInfo> infos(Layout layout, ObjectsDb db) throws IOException {
        Optional<String> current = layout.currentBranchOptional();
        Map<String, Path> branchWorktrees = Worktrees.branchWorktrees(layout);
        var result = new ArrayList<BranchInfo>();
        for (String name : names(layout)) {
            String tipId = layout.tip(name);
            if (tipId == null) continue;
            Commit tip = Revision.parse(db.read(tipId, "commit")).toCommit(tipId);
            Branch branch = new Branch(layout, name);
            boolean isCurrent = current.isPresent() && current.get().equals(name);
            Optional<BranchTracking> tracking = Remotes.tracking(layout, db, name);
            Optional<Path> wt = Optional.ofNullable(branchWorktrees.get(name));
            result.add(new BranchInfo(branch, tip, isCurrent, tracking, wt));
        }
        return Collections.unmodifiableList(result);
    }

    static List<Branch> filterMerged(Layout layout, ObjectsDb db, String targetCommitId, boolean merged) throws IOException {
        var history = new History(db);
        var result = new ArrayList<Branch>();
        for (String name : names(layout)) {
            String tip = layout.tip(name);
            if (tip == null) continue;
            boolean isMerged = history.contains(targetCommitId, tip);
            if (isMerged == merged) result.add(new Branch(layout, name));
        }
        return Collections.unmodifiableList(result);
    }

    static List<Branch> filterContaining(Layout layout, ObjectsDb db, String requiredCommitId) throws IOException {
        var history = new History(db);
        var result = new ArrayList<Branch>();
        for (String name : names(layout)) {
            String tip = layout.tip(name);
            if (tip != null && history.contains(tip, requiredCommitId)) {
                result.add(new Branch(layout, name));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
