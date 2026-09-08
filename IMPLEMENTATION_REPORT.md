# Group module implementation report

Implemented all 14 group endpoints in the existing community-service module: creation, public
reads/discovery/search, caller joined groups, membership transitions, moderator replacement,
member tools, profile PATCH, and ordered rules/flairs/resources replacement. PostgreSQL is the
source of truth, with cached counters and active moderator display IDs maintained transactionally.

[Endpoint table and complete request/response examples](GROUP_API.md) cover every endpoint.
[Implementation prompt](Spring%20Boot%20Reddit%20Community%20Service%20Implementation%20Prompt.md)
is the requested scope. Posts, feeds, notifications, media upload, and events were not implemented.

## Files and architecture

The existing application class, minimal service-local application.yml, POM, and V1 migration
are unchanged. No dependencies were added or upgraded. Java 25, Spring Boot 4.1.1, and Spring
Cloud 2025.1.3 remain as supplied. Compilation and both application contexts passed on Java 25.0.1.

Created inside community-service:

```text
src/main/java/com/navio/communityservice/controller/GroupController.java
src/main/java/com/navio/communityservice/dto/CreateGroupRequest.java
src/main/java/com/navio/communityservice/dto/FlairRequest.java
src/main/java/com/navio/communityservice/dto/FlairResponse.java
src/main/java/com/navio/communityservice/dto/GroupDetailResponse.java
src/main/java/com/navio/communityservice/dto/GroupListItem.java
src/main/java/com/navio/communityservice/dto/MemberResponse.java
src/main/java/com/navio/communityservice/dto/MembershipResponse.java
src/main/java/com/navio/communityservice/dto/MuteGroupRequest.java
src/main/java/com/navio/communityservice/dto/ReplaceFlairsRequest.java
src/main/java/com/navio/communityservice/dto/ReplaceModeratorsRequest.java
src/main/java/com/navio/communityservice/dto/ReplaceResourcesRequest.java
src/main/java/com/navio/communityservice/dto/ReplaceRulesRequest.java
src/main/java/com/navio/communityservice/dto/ResourceRequest.java
src/main/java/com/navio/communityservice/dto/ResourceResponse.java
src/main/java/com/navio/communityservice/dto/RuleRequest.java
src/main/java/com/navio/communityservice/dto/RuleResponse.java
src/main/java/com/navio/communityservice/dto/UpdateGroupProfileRequest.java
src/main/java/com/navio/communityservice/exception/ErrorResponse.java
src/main/java/com/navio/communityservice/exception/GlobalExceptionHandler.java
src/main/java/com/navio/communityservice/exception/GroupException.java
src/main/java/com/navio/communityservice/model/Group.java
src/main/java/com/navio/communityservice/model/GroupFlair.java
src/main/java/com/navio/communityservice/model/GroupMembership.java
src/main/java/com/navio/communityservice/model/GroupProfile.java
src/main/java/com/navio/communityservice/model/GroupResource.java
src/main/java/com/navio/communityservice/model/GroupRule.java
src/main/java/com/navio/communityservice/model/MembershipId.java
src/main/java/com/navio/communityservice/repository/GroupFlairRepository.java
src/main/java/com/navio/communityservice/repository/GroupMembershipRepository.java
src/main/java/com/navio/communityservice/repository/GroupProfileRepository.java
src/main/java/com/navio/communityservice/repository/GroupRepository.java
src/main/java/com/navio/communityservice/repository/GroupResourceRepository.java
src/main/java/com/navio/communityservice/repository/GroupRuleRepository.java
src/main/java/com/navio/communityservice/repository/GroupSearchRow.java
src/main/java/com/navio/communityservice/service/GroupAccessService.java
src/main/java/com/navio/communityservice/service/GroupContentService.java
src/main/java/com/navio/communityservice/service/GroupMembershipService.java
src/main/java/com/navio/communityservice/service/GroupModerationService.java
src/main/java/com/navio/communityservice/service/GroupService.java
src/main/java/com/navio/communityservice/service/GroupViewService.java
src/main/java/com/navio/communityservice/support/GroupValues.java
src/main/resources/db/migration/V2__create_social_group_tables.sql
src/test/java/com/navio/communityservice/GroupPostgresIntegrationTest.java
src/test/java/com/navio/communityservice/controller/GroupControllerTest.java
src/test/java/com/navio/communityservice/service/GroupContentServiceTest.java
src/test/java/com/navio/communityservice/service/GroupMembershipServiceTest.java
src/test/java/com/navio/communityservice/service/GroupModerationServiceTest.java
src/test/java/com/navio/communityservice/service/GroupServiceTest.java
src/test/resources/application-postgres.yml
GROUP_API.md
IMPLEMENTATION_REPORT.md
```

Modified:

- `.deploy/config/community-service.yml`: matching JDBC batching in the VM Config Server configuration.
- `.deploy/scripts/deploy.sh`: production edge checks for group discovery/search and protected mine/create routes.

- `community-service/src/test/resources/application.yml`: quieter test logging and disabled Zipkin export.
- `configuration-server/src/main/resources/config/community-service.yml`: JDBC batch size 50,
  ordered inserts/updates for whole-collection replacements.
- `api-gateway/src/main/java/com/navio/apigateway/security/GatewaySecurityConfig.java`: permit
  guest GET discovery/detail/search, preserving authentication for mine, member tools, and writes.
- `docs/api/Navio Api Documentation.md`: group endpoint table and link to the complete contract;
  clarified public group reads and group-scoped moderation authorization.

Created outside community-service:

- `api-gateway/src/test/java/com/navio/apigateway/security/GroupPublicRoutesTest.java`:
  security-chain test for public reads and protected operations.

Package responsibilities:

| Package | Responsibility |
| --- | --- |
| `controller` | Validated HTTP binding, gateway identity, status codes, pagination |
| `dto` | Records for requests/responses; presence-aware profile PATCH DTO |
| `service` | Separate group reads/creation, membership, moderation, content, access guard, response assembly |
| `repository` | JPA persistence, joins/projections, EXISTS authorization, locks, native full-text search |
| `model` | Six table entities and composite membership key, without eager collections |
| `exception` | Domain status mapping and one safe, consistent ErrorResponse handler |
| `support` | Shared normalization and slug generation |

Controllers depend on services, services depend on repository interfaces, and authorization is
centralized in `GroupAccessService.requireModerator`. `GroupViewService` assembles detail DTOs
without exposing entities. List queries bypass detail assembly. Transaction boundaries live on
public mutating service methods; all group writers acquire the same pessimistic group-row lock.
No shared cache holds caller membership flags.

## Migration

`V2__create_social_group_tables.sql` creates exactly these six tables:

- `social.groups`: UUID PK, immutable name/unique slug, text/array attributes, soft creator UUID,
  official/status values, nonnegative cached counters, generated search vector, timestamps.
- `social.group_profiles`: group PK/FK, URL/media UUID, non-null summary, moderator UUID array,
  JSONB metadata, timestamps.
- `social.group_memberships`: composite `(group_id,user_id)` PK, constrained role/state, timestamps.
- `social.group_rules`: UUID PK, group FK, title/description/order/creation time.
- `social.group_flairs`: UUID PK, group FK, constrained post/user type, label/tone/order/creation time.
- `social.group_resources`: UUID PK, group FK, label/nullable URL/order/creation time.

All child group references use `ON DELETE CASCADE`; there are no cross-schema user/media FKs.
The documented types, defaults, length limits, checks, PKs, unique constraint, and eight explicit
indexes are preserved: search-vector GIN, tags GIN, discovery ordering, membership user/state/time,
membership group/state, rules order, flairs group/type/order, and resources order.
V1 still enables pg_trgm and owns the schema comments; Flyway creates social/notif/media schemas.
No post, outbox, media, or notification tables were added.

**One necessary DDL correction:** the documented generated search expression calls polymorphic
`array_to_string`, which PostgreSQL marks stable. The migration adds
`social.group_search_text(TEXT[])`, an immutable text-only helper, and uses it for places/tags.
This preserves the documented search contents and generated TSVECTOR/GIN design.
PostgreSQL requires immutable functions in generated expressions. [PostgreSQL generated-column documentation](https://www.postgresql.org/docs/16/ddl-generated-columns.html)
The migration ran successfully on PostgreSQL 16 and Hibernate `ddl-auto: validate` passed.
The authoritative database document is otherwise unchanged.

## Identity and integrity

- Required endpoints bind the gateway's UUID `X-User-Id` header, with missing identity mapped
  to 401. Optional reads bind a nullable UUID and return guest flags. No caller-operation
  request body/path accepts a caller user ID. Candidate moderator UUIDs identify targets only.
- The gateway continues stripping inbound identity headers and derives trusted values from
  validated JWTs. Group rights use active membership role through an EXISTS query, ignoring
  platform role headers. The existing header-spoofing tests remain green.
- Creation saves/flushes the group before children, but all rows commit together. The database
  `groups_slug_key` constraint determines duplicates; its metadata maps to 409 without exposing SQL.
- Joined/muted are both active. Join from no row/left adds one; repeated join is a no-op.
  Leave changes the existing row to left and subtracts once; mute/unmute changes no count.
  Leaving never changes the stored role. Banned membership cannot be reactivated or cleared here.
- All membership/moderator mutations lock `social.groups` first. This serializes concurrent
  inserts and updates, protecting both the cached count and the last-moderator invariant.
- Moderator replacement validates the entire deduplicated set before writes, rejects nonmembers,
  preserves retained admins, demotes removed roles, and promotes new members. The profile mirror
  is rewritten in the same transaction. Moderator leave/rejoin also refreshes the active mirror.
- Rules/flairs/resources use a bulk delete followed by saveAll and configured JDBC batching.
- Discovery/mine/search each use one paginated content query with membership included; Spring
  Data may issue its usual separate total-count query for Page metadata. There are no per-row
  detail/membership queries. This interprets the prompt's ?single paginated query? as no N+1,
  while preserving the expressly required Page contract.

## Verification results

Executed with the existing Maven configuration and no additional testing dependencies:

| Suite | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: |
| Community context | 1 | 0 | 0 |
| GroupControllerTest | 6 | 0 | 0 |
| GroupServiceTest | 6 | 0 | 0 |
| GroupMembershipServiceTest | 7 | 0 | 0 |
| GroupModerationServiceTest | 4 | 0 | 0 |
| GroupContentServiceTest | 1 | 0 | 0 |
| GroupPostgresIntegrationTest | 8 | 0 | 0 |
| Gateway identity propagation | 5 | 0 | 0 |
| Gateway group public routes | 1 | 0 | 0 |
| Existing gateway full-context test | 0 | 0 | 1 |

**39 passed, zero failures; one pre-existing disabled gateway test.** Community Maven execution
and gateway Maven execution both exited 0. Whitespace checks passed for the changed repositories.

The PostgreSQL suite runs the real Flyway migrations and Hibernate validation, then exercises
MockMvc through real services/repositories/transactions. Its eight tests cover:

1. The requested A/B/C scenario: creator admin, join, promote A/B, demote A, reject nonmember C,
   mute B, banner-only PATCH, last-moderator 409, search hit/blank, and mine for A/B.
2. Actual unique-slug constraint failure mapped to 409, preserving the original group and membership.
3. Every moderator endpoint rejecting both an ordinary member and nonmember, even with global staff headers.
4. Ordered collection replacement, clearing nullable profile fields, and generated search updates.
5. Mine excluding left memberships, banned join rejection, and hidden-group visibility.
6. Twelve concurrent joins then twelve concurrent leaves for the same user: counts remain 2 then 1.
7. Two moderators concurrently leaving: exactly one succeeds; one moderator and one member remain.
8. Pagination totals and caller flags for discovery/mine/search, official ordering, and mine recency.

Unit/MVC tests also cover normalization, reserved/blank/long names, state-machine arithmetic,
rejoin role preservation, moderator-mirror changes, empty replacement, duplicate targets,
absent headers, malformed bodies/UUIDs, pagination bounds, immutable PATCH fields, and flair tones.

Limits: the full production path through a running gateway, Keycloak, Config Server, and Eureka
was not launched. The existing gateway application-context test is disabled because it requires
Keycloak/Config Server; the security chain and identity filter were tested independently.
No load benchmark was run. PostgreSQL tests are opt-in via COMMUNITY_TEST_DB_URL and use a
supplied disposable database; without that variable, those eight tests are skipped, while
controller/service tests and the existing H2 no-DDL context test still run. H2 does not execute
the migration. A standalone Docker PostgreSQL instance was used instead of adding Testcontainers.

## Reproduce

From the repository root in PowerShell, normal tests:

```powershell
mvn -f server/community-service/pom.xml test
mvn -f server/api-gateway/pom.xml test
```

For complete PostgreSQL verification, use an empty disposable PostgreSQL database:

```powershell
docker run --detach --rm --name navio-community-verification --publish 127.0.0.1:15484:5432 --env POSTGRES_USER=community_test --env POSTGRES_PASSWORD=community_test --env POSTGRES_DB=community_test postgres:16-alpine
$env:COMMUNITY_TEST_DB_URL = 'jdbc:postgresql://localhost:15484/community_test'
# Wait until docker exec ... pg_isready reports accepting connections.
docker exec navio-community-verification pg_isready -U community_test -d community_test
mvn -f server/community-service/pom.xml test
# After testing:
docker stop navio-community-verification
Remove-Item Env:COMMUNITY_TEST_DB_URL
```

`COMMUNITY_TEST_DB_USERNAME` and `COMMUNITY_TEST_DB_PASSWORD` may override the disposable defaults.
Each PostgreSQL test deletes only the groups it created; the test database retains the Flyway schema.

## Assumptions and deviations

The only database DDL deviation is the immutable search helper described above. There is no new
version column; pessimistic locks preserve concurrency guarantees without altering the schema.
The generated search vector is database-owned and queried natively, so it has no writable entity field.

Additional API choices are explicit in [GROUP_API.md](GROUP_API.md): reject duplicate slugs;
reserve mine/search; initialize summary from description; count muted members as members;
200 membership responses; active moderator mirror; hidden groups visible only to moderators;
archived direct reads/membership operations remain available; page size capped at 100; explicit
null clearing for nullable PATCH fields; wrapper objects for collection replacement; HTTP(S) URLs;
resource-to-frontend-bookmark mapping and avatar fallback. No existing frontend code was changed.

The gateway public-read allowance is a necessary integration change beyond the module: without
it, the prompt's guest reads would be rejected before reaching the new controllers. Its authorization
test verifies mine and writes stay protected. All unrelated pre-existing working-tree edits were preserved.
