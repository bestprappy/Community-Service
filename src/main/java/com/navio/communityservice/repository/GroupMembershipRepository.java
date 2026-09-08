package com.navio.communityservice.repository;
import com.navio.communityservice.model.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, MembershipId> {
    boolean existsByIdGroupIdAndIdUserIdAndRoleInAndStateIn(UUID groupId, UUID userId, Collection<String> roles, Collection<String> states);
    List<GroupMembership> findByIdGroupIdAndRoleIn(UUID groupId, Collection<String> roles);
    List<GroupMembership> findByIdGroupIdAndIdUserIdIn(UUID groupId, Collection<UUID> userIds);
    Page<GroupMembership> findByIdGroupIdAndStateInOrderByUpdatedAtDescIdUserIdAsc(UUID groupId, Collection<String> states, Pageable pageable);
}
