package com.navio.communityservice.dto;
import com.navio.communityservice.model.GroupMembership;
import java.util.UUID;
public record MembershipResponse(UUID groupId, boolean joined, boolean muted, String role, String state, int memberCount) {
    public static MembershipResponse from(UUID groupId, GroupMembership m, int count) {
        return new MembershipResponse(groupId, m != null && m.isActive(), m != null && "muted".equals(m.getState()),
                m == null ? null : m.getRole(), m == null ? "left" : m.getState(), count);
    }
}
