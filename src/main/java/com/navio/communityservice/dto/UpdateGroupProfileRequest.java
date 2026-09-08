package com.navio.communityservice.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import lombok.Getter;
import java.util.*;

/** Tracks field presence so omitted values survive PATCH and explicit null can clear nullable fields. */
public class UpdateGroupProfileRequest {
    private final Set<String> supplied = new HashSet<>();
    public boolean has(String field) { return supplied.contains(field); }
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unknown profile field");
    }
    @Getter 
    private String description;
    public void setDescription(String value) { supplied.add("description"); description = value; }
    @Getter @Size(max = 120)
    private String country;
    public void setCountry(String value) { supplied.add("country"); country = value; }
    @Getter 
    private List<@NotNull String> places;
    public void setPlaces(List<@NotNull String> value) { supplied.add("places"); places = value; }
    @Getter 
    private List<@NotNull String> tags;
    public void setTags(List<@NotNull String> value) { supplied.add("tags"); tags = value; }
    @Getter 
    private String summary;
    public void setSummary(String value) { supplied.add("summary"); summary = value; }
    @Getter @Pattern(regexp = "https?://[^\\s]+", message = "must be an HTTP(S) URL")
    private String bannerUrl;
    public void setBannerUrl(String value) { supplied.add("bannerUrl"); bannerUrl = value; }
    @Getter 
    private UUID bannerMediaId;
    public void setBannerMediaId(UUID value) { supplied.add("bannerMediaId"); bannerMediaId = value; }
}
