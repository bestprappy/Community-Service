package com.navio.communityservice.repository;
import com.navio.communityservice.model.GroupRule;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupRuleRepository extends JpaRepository<GroupRule, UUID> {
    List<GroupRule> findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(UUID groupId);
    @Modifying
    @Query("delete from GroupRule row where row.groupId = :groupId")
    void deleteAllByGroupId(UUID groupId);
}
