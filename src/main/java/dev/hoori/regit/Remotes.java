package dev.hoori.regit;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.util.*;
import static dev.hoori.regit.GitFiles.Lock;

/** Manages remote-tracking refs ({@code refs/remotes/*}), {@code fetch}, {@code push} options and branch tracking. */
final class Remotes {
    private Remotes() {}

    static List<Remote> list(Layout layout) throws IOException {
        var result = new ArrayList<Remote>();
        for (var e : GitConfig.remotes(layout).entrySet()) {
            result.add(new Remote(layout, e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableList(result);
    }

    static Remote require(Layout layout, String name) throws IOException {
        String url = GitConfig.remoteUrl(layout, Objects.requireNonNull(name, "name"))
                .orElseThrow(() -> new IOException("No such remote: " + name));
        return new Remote(layout, name, url);
    }

    static List<RemoteBranch> remoteBranches(Layout layout) throws IOException {
        var names = new TreeSet<String>();
        Path base = layout.common().resolve("refs/remotes");
        if (Files.isDirectory(base)) {
            try (var stream = Files.walk(base)) {
                for (Path p : stream.filter(Files::isRegularFile).toList()) {
                    String rel = base.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
                    if (!rel.endsWith(".lock") && !rel.endsWith("/HEAD")) names.add(rel);
                }
            }
        }
        Path packed = layout.common().resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            try (var lines = Files.lines(packed)) {
                for (String line : lines.toList()) {
                    if (line.length() > 54 && line.startsWith(" refs/remotes/", 40)) {
                        String rel = line.substring(54);
                        if (!rel.endsWith("/HEAD")) names.add(rel);
                    }
                }
            }
        }
        var result = new ArrayList<RemoteBranch>(names.size());
        for (String full : names) {
            int slash = full.indexOf('/');
            if (slash > 0 && slash + 1 < full.length()) {
                result.add(new RemoteBranch(layout, full.substring(0, slash), full.substring(slash + 1)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    static Map<String, String> trackingTips(Layout layout, String remoteName) throws IOException {
        var tips = new TreeMap<String, String>();
        String prefix = "refs/remotes/" + remoteName + "/";
        Path packed = layout.common().resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            try (var lines = Files.lines(packed)) {
                for (String line : lines.toList()) {
                    if (line.length() > 41 + prefix.length() && line.startsWith(" " + prefix, 40)) {
                        String branch = line.substring(41 + prefix.length());
                        if (!branch.equals("HEAD")) tips.put(branch, GitFiles.id(line.substring(0, 40)));
                    }
                }
            }
        }
        Path dir = layout.common().resolve(prefix);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.walk(dir)) {
                for (Path p : stream.filter(Files::isRegularFile).toList()) {
                    String rel = dir.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/");
                    if (rel.endsWith(".lock") || rel.equals("HEAD")) continue;
                    String content = GitFiles.line(p);
                    if (!content.startsWith("ref: ")) tips.put(rel, GitFiles.id(content));
                }
            }
        }
        return tips;
    }

    static FetchResult fetch(Layout layout, ObjectsDb db, Remote remote, FetchOptions options) throws IOException {
        String remoteName = remote.nameIn(layout);
        Objects.requireNonNull(options, "options");
        Map<String, String> existing = trackingTips(layout, remoteName);
        Map<String, String> remoteHeads;

        if (remote.isHttp()) {
            HttpClient client = options.httpClient() != null ? options.httpClient() : HttpClient.newHttpClient();
            var http = new SmartHttpRemote(URI.create(remote.url()), client);
            remoteHeads = http.fetchAll(existing, db);
        } else {
            Path remotePath = resolveRemotePath(layout, remote.url());
            Layout otherLayout = Layout.open(remotePath);
            ObjectsDb otherDb = new ObjectsDb(otherLayout.common());
            remoteHeads = new TreeMap<>();
            for (String branch : Branches.names(otherLayout)) {
                String tip = otherLayout.tip(branch);
                if (tip == null) continue;
                remoteHeads.put(branch, tip);
                LocalTransfer.copy(tip, existing.get(branch), otherDb, db);
            }
        }

        var updated = new LinkedHashMap<RemoteBranch, String>();
        for (var e : remoteHeads.entrySet()) {
            String branch = e.getKey();
            String tip = e.getValue();
            writeRemoteRef(layout, remoteName, branch, tip);
            if (!tip.equals(existing.get(branch))) {
                updated.put(new RemoteBranch(layout, remoteName, branch), tip);
            }
        }

        var pruned = new LinkedHashSet<RemoteBranch>();
        if (options.isPrune()) {
            for (String localBranch : existing.keySet()) {
                if (!remoteHeads.containsKey(localBranch)) {
                    deleteRemoteRef(layout, remoteName, localBranch);
                    pruned.add(new RemoteBranch(layout, remoteName, localBranch));
                }
            }
        }
        return new FetchResult(remote, updated, pruned);
    }

    static String push(Layout layout, ObjectsDb db, Remote remote, String localBranch, String destination,
                       PushOptions options) throws IOException {
        String remoteName = remote.nameIn(layout);
        Objects.requireNonNull(options, "options");
        GitFiles.branch(destination);

        String expectedLease = null;
        boolean useLease = options.isForceWithLease();
        if (useLease) {
            if (options.expectedLease() != null) {
                expectedLease = new Revisions(layout, db).resolveCommit(options.expectedLease());
            } else {
                expectedLease = trackingTips(layout, remoteName).get(destination);
            }
        }

        String resultTip;
        if (options.isDelete()) {
            if (remote.isHttp()) {
                HttpClient client = options.httpClient() != null ? options.httpClient() : HttpClient.newHttpClient();
                new SmartHttpRemote(URI.create(remote.url()), client)
                        .deleteBranch(destination, useLease, expectedLease);
            } else {
                deleteRemoteBranchLocal(resolveRemotePath(layout, remote.url()), destination, useLease, expectedLease);
            }
            deleteRemoteRef(layout, remoteName, destination);
            resultTip = null;
        } else {
            String tip = layout.require(localBranch);
            if (remote.isHttp()) {
                HttpClient client = options.httpClient() != null ? options.httpClient() : HttpClient.newHttpClient();
                resultTip = new SmartHttpRemote(URI.create(remote.url()), client)
                        .push(destination, tip, db, options.isForce(), useLease, expectedLease);
            } else {
                resultTip = pushLocal(resolveRemotePath(layout, remote.url()), db, destination, tip,
                        options.isForce(), useLease, expectedLease);
            }
            writeRemoteRef(layout, remoteName, destination, tip);
            if (options.isSetUpstream()) {
                GitConfig.setUpstream(layout, localBranch, remoteName, destination);
            }
        }
        return resultTip;
    }

    static String pushLocal(Path remotePath, ObjectsDb localDb, String destination, String tip,
                            boolean force, boolean useLease, String expectedLease) throws IOException {
        Layout otherLayout = Layout.open(remotePath);
        ObjectsDb otherDb = new ObjectsDb(otherLayout.common());
        try (var guard = new Lock(otherLayout.common().resolve("regit-operation"));
             var ref = new Lock(otherLayout.ref(destination))) {
            otherLayout.available(destination);
            String old = otherLayout.tip(destination);
            if (useLease) {
                if (!Objects.equals(old, expectedLease)) {
                    throw new IOException("Stale remote ref (force-with-lease): expected "
                            + Objects.toString(expectedLease, "<absent>") + ", found " + Objects.toString(old, "<absent>"));
                }
            } else if (!force && old != null && !new History(localDb).contains(tip, old)) {
                throw new IOException("Non-fast-forward push");
            }
            if (old == null) Branches.prepareNew(otherLayout, destination);
            LocalTransfer.copy(tip, old, localDb, otherDb);
            ref.publish(tip + "\n");
            return tip;
        }
    }

    static void deleteRemoteBranchLocal(Path remotePath, String destination,
                                        boolean useLease, String expectedLease) throws IOException {
        Layout otherLayout = Layout.open(remotePath);
        try (var guard = new Lock(otherLayout.common().resolve("regit-operation"));
             var ref = new Lock(otherLayout.ref(destination))) {
            otherLayout.available(destination);
            String old = otherLayout.tip(destination);
            if (old == null) throw new IOException("Missing remote branch: " + destination);
            if (useLease && !old.equals(expectedLease)) {
                throw new IOException("Stale remote ref (force-with-lease): expected "
                        + Objects.toString(expectedLease, "<absent>") + ", found " + old);
            }
            deleteRefFileAndPacked(otherLayout, "refs/heads/" + destination, otherLayout.ref(destination));
        }
    }

    static Optional<BranchTracking> tracking(Layout layout, ObjectsDb db, String localBranch) throws IOException {
        var optUpstream = GitConfig.upstream(layout, localBranch);
        if (optUpstream.isEmpty()) return Optional.empty();
        var u = optUpstream.get();
        RemoteBranch rb = new RemoteBranch(layout, u.remote(), u.branch());
        String localTip = layout.tip(localBranch);
        String remoteTip = trackingTips(layout, u.remote()).get(u.branch());
        if (localTip == null || remoteTip == null) return Optional.of(new BranchTracking(rb, 0, 0));
        if (localTip.equals(remoteTip)) return Optional.of(new BranchTracking(rb, 0, 0));
        var history = new History(db);
        int ahead = history.walk(Log.range(remoteTip, localTip), layout).size();
        int behind = history.walk(Log.range(localTip, remoteTip), layout).size();
        return Optional.of(new BranchTracking(rb, ahead, behind));
    }

    static void writeRemoteRef(Layout layout, String remoteName, String branch, String tip) throws IOException {
        Path refFile = layout.common().resolve("refs/remotes/" + remoteName + "/" + GitFiles.branch(branch));
        try (var lock = new Lock(refFile)) {
            lock.publish(tip + "\n");
        }
    }

    static void deleteRemoteRef(Layout layout, String remoteName, String branch) throws IOException {
        String fullRef = "refs/remotes/" + remoteName + "/" + GitFiles.branch(branch);
        Path loose = layout.common().resolve(fullRef);
        deleteRefFileAndPacked(layout, fullRef, loose);
    }

    static void deleteRefFileAndPacked(Layout layout, String fullRef, Path loose) throws IOException {
        Path packed = layout.common().resolve("packed-refs");
        try (var lock = new Lock(packed)) {
            if (Files.exists(packed)) {
                var lines = new ArrayList<>(Files.readAllLines(packed));
                if (lines.removeIf(l -> l.length() > 41 && l.endsWith(" " + fullRef))) {
                    lock.publish(String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"));
                }
            }
            Files.deleteIfExists(loose);
        }
    }

    private static Path resolveRemotePath(Layout layout, String url) {
        Path p = Path.of(url);
        if (p.isAbsolute()) return p;
        Path base = layout.root() != null ? layout.root() : layout.common();
        return base.resolve(p).normalize();
    }
}
