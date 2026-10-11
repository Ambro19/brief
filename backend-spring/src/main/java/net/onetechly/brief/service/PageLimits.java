package net.onetechly.brief.service;

import net.onetechly.brief.exception.BadRequestException;

/**
 * The paging rules shared by every list that can be read a page at a time (posts, and since
 * 2.3 mentions), kept in one place so the lists cannot drift apart.
 */
public final class PageLimits {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PageLimits() {
    }

    /**
     * Checks the two paging parameters of a request and returns the page size to use.
     *
     * @param limit    how many to return, 1 to MAX_PAGE_SIZE; null means DEFAULT_PAGE_SIZE
     * @param beforeId the cursor (an id; rows with a smaller id are wanted), or null for the newest
     * @throws BadRequestException (400) if limit or beforeId is out of range
     */
    public static int sizeFor(Integer limit, Integer beforeId) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        if (size < 1 || size > MAX_PAGE_SIZE || (beforeId != null && beforeId < 1)) {
            throw new BadRequestException();
        }
        return size;
    }
}
