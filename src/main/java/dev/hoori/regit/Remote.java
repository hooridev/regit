package dev.hoori.regit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** A configured named remote (such as {@code origin}), scoped to its owning repository. */
public final class Remote {
    private final Path common;
    private final String name;
    private final String url;

    Remote(Layout layout, String name, String url) {
        this.common = layout.common();
        this.name = Objects.requireNonNull(name, "name");
        this.url = Objects.requireNonNull(url, "url");
    }

    public String name() { return name; }
    public String url() { return url; }
    public boolean isHttp() { return url.startsWith("http://") || url.startsWith("https://"); }

    String nameIn(Layout layout) throws IOException {
        if (!common.equals(layout.common())) throw new IOException("Remote belongs to another repository: " + name);
        return name;
    }

    @Override public boolean equals(Object other) {
        return other instanceof Remote r && name.equals(r.name) && url.equals(r.url) && common.equals(r.common);
    }

    @Override public int hashCode() { return Objects.hash(common, name, url); }
    @Override public String toString() { return name; }
}
