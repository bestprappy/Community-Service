package com.navio.communityservice.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "group_media", schema = "media")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class GroupMedia {
    @Id private UUID id;
    @Column(nullable = false, updatable = false) private UUID groupId;
    @Column(nullable = false, updatable = false) private UUID uploadedByUserId;
    @Column(nullable = false, unique = true, updatable = false) private String objectKey;
    @Column(nullable = false, updatable = false) private String contentType;
    @Column(nullable = false, updatable = false) private long sizeBytes;
    @Column(nullable = false, updatable = false) private Instant createdAt;
}
