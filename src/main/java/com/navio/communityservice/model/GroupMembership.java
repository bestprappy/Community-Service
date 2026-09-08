package com.navio.communityservice.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "group_memberships", schema = "social")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupMembership {
    @EmbeddedId
    private MembershipId id;
    @Builder.Default @Column(nullable = false, length = 30)
    private String role = "member";
    @Builder.Default @Column(nullable = false, length = 30)
    private String state = "joined";
    @Column(nullable = false, updatable = false)
    private Instant joinedAt;
    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() { joinedAt = updatedAt = Instant.now(); }
    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
    public boolean isActive() { return "joined".equals(state) || "muted".equals(state); }
    public boolean isModerator() { return "moderator".equals(role) || "admin".equals(role); }
}
