package com.navio.communityservice.repository;
import java.time.Instant;
import java.util.UUID;
public interface GroupSearchRow {
    UUID getId(); String getName(); String getSlug(); String getDescription(); String getCountry();
    String[] getPlaces(); String[] getTags(); boolean getOfficial(); String getStatus();
    int getMemberCount(); int getPostCount(); String getMembershipState(); String getRole();
    Instant getCreatedAt(); Instant getUpdatedAt();
}
