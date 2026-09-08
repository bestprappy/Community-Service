package com.navio.communityservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "group_profiles", schema = "social")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupProfile {
    @Id
    private UUID groupId;
    private UUID bannerMediaId;
    @Column(columnDefinition = "text")
    private String bannerUrl;
    @Column(nullable = false, columnDefinition = "text")
    private String summary;
    @Builder.Default @JdbcTypeCode(SqlTypes.ARRAY) @Column(nullable = false, columnDefinition = "uuid[]")
    private UUID[] moderatorIds = new UUID[0];
    @Builder.Default @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadataJsonb = new HashMap<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @PrePersist
    void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
