package dev.hoori.regit;

/** Aggregate diff statistics matching Git's {@code --shortstat}. */
public record DiffStat(int filesChanged, int insertions, int deletions) {
    public boolean isEmpty() { return filesChanged == 0; }

    /** Format matching {@code git diff --shortstat}. */
    public String formatShortStat() {
        if (filesChanged == 0) return "";
        var out = new StringBuilder(" ");
        out.append(filesChanged).append(filesChanged == 1 ? " file changed" : " files changed");
        if (insertions > 0) {
            out.append(", ").append(insertions).append(insertions == 1 ? " insertion(+)" : " insertions(+)");
        }
        if (deletions > 0) {
            out.append(", ").append(deletions).append(deletions == 1 ? " deletion(-)" : " deletions(-)");
        }
        return out.toString();
    }
}
