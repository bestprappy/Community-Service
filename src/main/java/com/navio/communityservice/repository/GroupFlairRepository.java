package com.navio.communityservice.repository;
import com.navio.communityservice.model.GroupFlair;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupFlairRepository extends JpaRepository<GroupFlair, UUID> {
    List<GroupFlair> findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(UUID groupId);
    @Modifying
    @Query("delete from GroupFlair row where row.groupId = :groupId")
    void deleteAllByGroupId(UUID groupId);
}
