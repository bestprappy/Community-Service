package com.navio.communityservice.model;
import jakarta.persistence.*;
import lombok.*;
import java.io.Serializable;
import java.util.UUID;
@Embeddable
@Data @NoArgsConstructor @AllArgsConstructor
public class MembershipId implements Serializable {
    @Column(nullable = false) private UUID groupId;
    @Column(nullable = false) private UUID userId;
}
