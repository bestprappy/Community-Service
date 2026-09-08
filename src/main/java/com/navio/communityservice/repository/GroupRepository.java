package com.navio.communityservice.repository;
import com.navio.communityservice.model.Group;
import com.navio.communityservice.dto.GroupListItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface GroupRepository extends JpaRepository<Group, UUID> {
    Optional<Group> findBySlug(String slug);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Group g where g.slug = :slug")
    Optional<Group> lockBySlug(String slug);

    @Query(value = """
        select new com.navio.communityservice.dto.GroupListItem(g, m.state, m.role)
        from Group g left join GroupMembership m on m.id.groupId = g.id and m.id.userId = :userId
        where g.status = 'active' order by g.isOfficial desc, g.memberCount desc, g.id
        """, countQuery = "select count(g) from Group g where g.status = 'active'")
    Page<GroupListItem> discover(UUID userId, Pageable pageable);

    @Query(value = """
        select new com.navio.communityservice.dto.GroupListItem(g, m.state, m.role)
        from Group g join GroupMembership m on m.id.groupId = g.id
        where m.id.userId = :userId and m.state in ('joined','muted')
          and (g.status <> 'hidden' or m.role in ('moderator','admin'))
        order by m.updatedAt desc, g.id
        """, countQuery = """
        select count(g) from Group g join GroupMembership m on m.id.groupId = g.id
        where m.id.userId = :userId and m.state in ('joined','muted')
          and (g.status <> 'hidden' or m.role in ('moderator','admin'))
        """)
    Page<GroupListItem> mine(UUID userId, Pageable pageable);

    @Query(value = """
        select g.id, g.name, g.slug, g.description, g.country, g.places, g.tags,
          g.is_official as official, g.status, g.member_count as memberCount, g.post_count as postCount,
          m.state as membershipState, m.role, g.created_at as createdAt, g.updated_at as updatedAt
        from social.groups g left join social.group_memberships m
          on m.group_id = g.id and m.user_id = :userId
        where g.status = 'active' and g.search_vector @@ plainto_tsquery('simple', :q)
        order by ts_rank(g.search_vector, plainto_tsquery('simple', :q)) desc, g.id
        """, countQuery = """
        select count(*) from social.groups g
        where g.status = 'active' and g.search_vector @@ plainto_tsquery('simple', :q)
        """, nativeQuery = true)
    Page<GroupSearchRow> search(UUID userId, String q, Pageable pageable);
}
