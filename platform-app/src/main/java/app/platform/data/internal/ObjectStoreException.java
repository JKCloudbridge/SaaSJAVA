package app.platform.data.internal;

/**
 * The object store could not do what was asked. It carries no message and no cause on purpose: the text of a failed
 * request can name a key or a host (ADR-0012), so it is neither logged nor passed on; the caller logs the kind of
 * failure it knows and answers 503.
 */
final class ObjectStoreException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ObjectStoreException() {
        super("the object store failed", null, false, false);
    }
}
