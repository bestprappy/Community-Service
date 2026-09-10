package com.navio.communityservice.repository;
import com.navio.communityservice.model.GroupMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface GroupMediaRepository extends JpaRepository<GroupMedia, UUID> { }
