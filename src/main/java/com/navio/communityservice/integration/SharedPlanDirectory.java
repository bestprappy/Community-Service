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

    class SharedPlanDirectoryUnavailableException extends RuntimeException {
        public SharedPlanDirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
