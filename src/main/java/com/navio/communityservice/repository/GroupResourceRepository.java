package com.navio.communityservice.repository;
import com.navio.communityservice.model.GroupResource;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupResourceRepository extends JpaRepository<GroupResource, UUID> {
    List<GroupResource> findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(UUID groupId);
    @Modifying
    @Query("delete from GroupResource row where row.groupId = :groupId")
    void deleteAllByGroupId(UUID groupId);
}
