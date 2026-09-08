package com.navio.communityservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_flairs", schema = "social")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupFlair {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private UUID groupId;
    @Column(nullable = false, length = 20) private String flairType;
    @Column(nullable = false, length = 80) private String label;
    @Column(nullable = false, length = 30) private String tone;
    @Column(nullable = false) private int displayOrder;

    @Column(nullable = false, updatable = false) private Instant createdAt;
    @PrePersist
    void onCreate() { createdAt = Instant.now(); }
}
