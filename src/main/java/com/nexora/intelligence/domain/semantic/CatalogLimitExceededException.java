package com.nexora.intelligence.domain.semantic;

/** A catalog larger than the supported limit. It is rejected as a whole; it is never truncated. */
public class CatalogLimitExceededException extends IllegalArgumentException {

    private final String part;
    private final int limit;

    public CatalogLimitExceededException(String part, int limit) {
        super("the catalog has more than " + limit + " " + part + "; it is not truncated");
        this.part = part;
        this.limit = limit;
    }

    public String part() {
        return part;
    }

    public int limit() {
        return limit;
    }
}
