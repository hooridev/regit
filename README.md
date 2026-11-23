<img src="assets/icon.svg" width="64" height="64" alt="">

# regit

Regit is a Java 25 library for staging files, committing, managing branches and linked worktrees, pushing, pulling, merging and rebasing in SHA-1 Git repositories. It has no runtime dependencies. Applications call its Java API directly.

## Build and verify

Run `mvn clean verify` with JDK 25 and Maven. The integration tests require a Git executable with `http-backend` and a loopback interface. They create temporary repositories, compare object IDs with native Git, exercise both sides of the working-tree/index boundary and exchange data with a native smart HTTP server.

## One branch per agent

```java
import io.github.regit.Identity;
import io.github.regit.Repository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

var main = Repository.init(Path.of("project"), "main");
var author = new Identity("Alix", "alix@example.org", OffsetDateTime.now());
Files.writeString(Path.of("project/README.md"), "First version\n");
main.stage("README.md");
main.commit("Start project\n", author, author);

main.createBranch("agent/docs");
var agent = main.addWorktree(Path.of("docs-checkout"), "agent/docs");
Files.writeString(Path.of("docs-checkout/README.md"), "Updated version\n");
agent.stage("README.md");
String tip = agent.commit("Update documentation\n", author, author);

main.merge("agent/docs", author, "Merge documentation\n");
main.removeWorktree(Path.of("docs-checkout"));
main.deleteBranch("agent/docs");
```

`Repository.open(path)` reopens a worktree or bare repository. `branches()`, `branch()`, `head()` and `worktrees()` expose its current state. An unborn branch has an empty head. A linked checkout has its own HEAD, index and operation lock. Different agents can commit concurrently on separate branches. Branch deletion checks reachability from the current HEAD and checkout ownership.

Stage exact repository-relative file names. Staging a missing tracked file records deletion. Commits consume the index and store the supplied message verbatim. Include the trailing newline for parity with an ordinary `git commit -m`. The author and committer carry explicit timestamps and numeric UTC offsets.

Use `repo.status().unstaged()` for tracked files changed since staging. A single `status()` call also returns `staged()` for changes ready to commit and `untracked()` for individual new paths. A file edited after staging can occur in both change maps. Status and staging honor worktree-local `.gitignore` files from the root down to the file’s directory, plus the shared repository’s `info/exclude`. A deeper rule can override a parent rule while the parent directory remains visible. Already indexed files remain eligible for staging, including deletions. If any new path passed to `stage(...)` is ignored, the call raises `IOException` before writing any blobs or updating the index. Rules are reread on each operation. User-global excludes and `core.excludesFile` are outside the current scope.

## Synchronization and history

```java
var bare = Path.of("central.git");
Repository.initBare(bare, "main");
main.push(bare, "main");

var second = Repository.init(Path.of("second"), "main");
second.pull(bare, "main", author, "Merge remote main\n");
```

For HTTP(S), pass a `SmartHttpRemote` constructed with the repository URI and a `java.net.http.HttpClient` to the same push and pull methods. The caller owns client authentication and TLS configuration. The transport uses Git smart HTTP v0, validates the received pack and checks the server's ref-update status. Push requires a fast-forward. Pull fetches an explicitly named remote branch and merges into the current branch. An empty local branch can receive its initial history via pull.

`merge(source, committer, message)` fast-forwards when possible and otherwise writes a two-parent merge commit. `rebase(onto, committer)` replays a linear topic in oldest-first order, preserving original authors and messages. Both handle additions, deletions, executable bits and separated edits in UTF-8 text. An unresolved path raises `MergeConflict` before publishing any ref or changing the checkout. The default content strategy bounds its diff calculation to one million cells per side after trimming common lines.

## Host a bare repository

A bare repository stores shared objects and refs and has no working directory or staging area of its own. Use `Repository.initBare` once to provision it, then `Repository.open` on restart. `isBare()` identifies a bare handle. Each HTTP context maps directly to one repository handle.

```java
import com.sun.net.httpserver.HttpServer;
import io.github.regit.GitHttpHandler;
import io.github.regit.Repository;
import java.net.InetSocketAddress;
import java.nio.file.Path;

var central = Repository.initBare(Path.of("repos/team.git"), "main");
var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8080), 0);
server.createContext("/team.git", central.httpHandler(GitHttpHandler.Access.READ_WRITE));
server.start();
```

From an existing checkout with a commit on `main`, populate the server and clone it with native Git:

```sh
git push http://127.0.0.1:8080/team.git main
git clone http://127.0.0.1:8080/team.git team-copy
```

The handler serves smart HTTP v0 ref advertisements, upload-pack for clone/fetch and receive-pack for push. A clone discovers the default branch from HEAD. Select `READ_ONLY` for a fetch-only context. `READ_WRITE` grants everyone admitted to that context permission to push. Applications exposing the service to other machines supply authentication and TLS via the JDK HTTP context/server or a reverse proxy. The application owns the executor, server lifetime and repository backups.

Push accepts one branch creation or fast-forward per request. Received objects are unpacked in a temporary database. The handler checks the expected old tip, linked-worktree ownership and the new commit graph before publishing the ref. A configured repository lifecycle receives a PUSH event for the destination branch. The server currently hosts SHA-1 branch refs. Tags, remote branch deletion, multi-ref pushes, shallow clones and SSH serving are outside its scope. Receive requests allow up to 256 MiB of decoded HTTP body, 100,000 pack records and 1 GiB of reconstructed object data per push.

## Extension points

`withLifecycle(hook)` and `withContentMerge(strategy)` return configured handles. New linked worktrees inherit these settings. A lifecycle event includes the operation, repository location and target. A policy can veto in `before` by throwing `IOException`. The `after` callback runs after successful completion and lock release. An exception from that callback reaches the caller after the operation has taken effect. Internal fetch and merge steps of a pull produce a single pull event.

A `ContentMerge` strategy receives the path and the base, current and incoming bytes for concurrent content edits. An application can, for example, merge a structured manifest by key and defer other files to `ContentMerge.text()`. It can also raise `MergeConflict`. The same policy applies during merge, pull and replay.

Internally, the repository facade coordinates object storage, tree/index codecs, graph traversal, worktree materialization and transport. Immutable object IDs allow directory-level pruning during transfer. Ordinary loose-file staging, checkout and filesystem transfer stream blob data. HTTP packs are spooled to disk, with individual objects and their deltas processed in memory. Packed-object lookup uses v2 index fan-out and binary search.

## Compatibility envelope

- Validated on Linux with case-sensitive paths, regular files, executable mode, UTF-8 names, repository format 0, SHA-1, index v2, loose and packed refs, and pack v2/v3 with OFS and REF deltas. Pack records and reconstructed delta results have a 256 MiB input limit. Working-directory operations require an attached local branch.
- Staging takes literal file bytes. Attributes, clean/smudge filters, autocrlf, symlinks, submodules, sparse checkouts, partial/shallow clones and alternate object databases are outside the supported workflow. Use ordinary full repositories with trusted local metadata. Git configuration and native hook scripts are not application extension mechanisms in this version.
- Merge uses a single merge base and path-based three-way comparison. Rename detection, recursive merge-base synthesis, conflict-marker/index-stage workflows and interactive or merge-preserving rebase are outside this release. On conflict, retry with a suitable content strategy or resolve using another tool.
- Remotes are explicit filesystem paths or smart HTTP(S) endpoints. Named remote/upstream configuration, SSH transport, force pushes, reflog maintenance and remote-tracking refs are outside this version. Local ref updates and index writes use Git-style lock files and atomic file replacement. Concurrent operations that need the same lock can fail and be retried.
- Checkout checks the staged snapshot and the affected working files before replacing them. It attempts rollback on I/O failure. Publication across the index, working files and refs is a multi-file operation. Applications should reopen and inspect the repository following process termination, power loss or an ambiguous network response.

## Clean-room basis

The implementation was written from public Git format and protocol documentation. Native Git serves as a black-box interoperability oracle in the tests. The production library contains no Git or JGit dependency and launches no Git processes.

References include the [repository layout](https://git-scm.com/docs/gitrepository-layout), [loose object format](https://git-scm.com/docs/gitformat-loose), [index format](https://git-scm.com/docs/gitformat-index), [pack format](https://git-scm.com/docs/gitformat-pack), [pack protocol](https://git-scm.com/docs/gitprotocol-pack) and [HTTP protocol](https://git-scm.com/docs/gitprotocol-http).

Created in [T3 Code](https://t3.codes).
