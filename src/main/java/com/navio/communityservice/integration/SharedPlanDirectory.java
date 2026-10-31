package com.navio.communityservice.integration;

/**
 * Answers whether a shared-plan token may be attached to a post: only plans their
 * owners listed on Explore, because a post is public and the owner opted into
 * public discovery only by listing.
 */
public interface SharedPlanDirectory {

    /**
     * @return true when the token belongs to a plan listed on Explore right now;
     *         false when it is unknown, revoked or unlisted
     * @throws SharedPlanDirectoryUnavailableException when trip planning cannot answer
     */
    boolean isListedOnExplore(String token);

    /**
     * Plans this account listed on Explore under its own name, newest first. A
     * plan listed anonymously (no byline) is never attributed to its owner here.
     *
     * @throws SharedPlanDirectoryUnavailableException when trip planning cannot answer
     */
    java.util.List<ListedPlan> listedByAuthor(java.util.UUID author);

    /** The two facts a notification needs about a listed plan. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record ListedPlan(String token, String title) { }

    class SharedPlanDirectoryUnavailableException extends RuntimeException {
        public SharedPlanDirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
