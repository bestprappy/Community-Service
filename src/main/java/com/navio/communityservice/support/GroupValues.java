package com.navio.communityservice.support;
import com.navio.communityservice.exception.GroupException;
import java.util.*;
public final class GroupValues {
    private GroupValues() { }
    public static String required(String value, String field) {
        if (value == null || value.strip().isBlank()) throw GroupException.invalid(field + " must not be blank");
        return value.strip();
    }
    public static String[] labels(List<String> values) {
        if (values == null) return new String[0];
        if (values.stream().anyMatch(Objects::isNull)) throw GroupException.invalid("Labels cannot contain null");
        return values.stream().map(String::strip).filter(s -> !s.isBlank()).distinct().toArray(String[]::new);
    }
    public static String slug(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) throw GroupException.invalid("Name must contain an ASCII letter or digit for its slug");
        if (Set.of("mine", "search").contains(slug)) throw GroupException.invalid("This group name is reserved");
        return slug;
    }
}
