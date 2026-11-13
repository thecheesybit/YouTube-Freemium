package app.movy.adblock;

/** Immutable outcome of an engine check. */
public final class BlockResult {
    public static final BlockResult ALLOW = new BlockResult(Kind.ALLOW, null, null);
    public static final BlockResult BLOCK = new BlockResult(Kind.BLOCK, null, null);

    public enum Kind { ALLOW, BLOCK, REDIRECT }

    public final Kind kind;
    /** Redirect resource MIME type (REDIRECT only). */
    public final String mime;
    /** Redirect resource body (REDIRECT only). */
    public final byte[] body;

    private BlockResult(Kind kind, String mime, byte[] body) {
        this.kind = kind;
        this.mime = mime;
        this.body = body;
    }

    public static BlockResult redirect(String mime, byte[] body) {
        return new BlockResult(Kind.REDIRECT, mime, body);
    }

    public boolean isAllowed() {
        return kind == Kind.ALLOW;
    }
}
