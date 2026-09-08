package com.navio.communityservice.repository;
import com.navio.communityservice.model.GroupProfile;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupProfileRepository extends JpaRepository<GroupProfile, UUID> {
}
