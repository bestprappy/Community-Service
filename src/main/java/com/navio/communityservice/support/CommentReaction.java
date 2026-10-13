package com.navio.communityservice.support;

import com.navio.communityservice.exception.GroupException;
import java.util.Arrays;

/** Reactions a member can leave on a comment, in display order. Values match the database check constraint. */
public enum CommentReaction {
    HELPFUL("helpful"), LOVE("love"), FUNNY("funny"), THANKS("thanks"), WANT_TO_GO("want_to_go");

    private final String value;
    CommentReaction(String value) { this.value = value; }
    public String value() { return value; }

    public static CommentReaction parse(String value) {
        return Arrays.stream(values()).filter(reaction -> reaction.value.equals(value)).findFirst()
                .orElseThrow(() -> GroupException.invalid("Unknown reaction"));
    }
}
