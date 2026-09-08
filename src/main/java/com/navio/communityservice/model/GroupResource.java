package com.navio.communityservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_resources", schema = "social")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupResource {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID groupId;
    @Column(nullable = false, length = 120) private String label;
    @Column(columnDefinition = "text") private String url;
    @Column(nullable = false) private int displayOrder;

    @Column(nullable = false, updatable = false) private Instant createdAt;
    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
