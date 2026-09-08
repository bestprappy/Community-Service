# Navio Community Service — Group Module Implementation Prompt

You are a senior Java/Spring Boot backend engineer working inside the existing **Navio** capstone monorepo (TripPlanner + EV + Community platform).

Implement the **group module** of `server/community-service`: groups, group membership, moderation roles, and the moderator-managed group content (profile, rules, flairs, resources).

This service is currently a skeleton. Only the application class, `application.yml`, and a schema-comment migration exist. Everything below is greenfield inside an established set of conventions — follow the conventions, do not invent new ones.

---

## 1. Read before writing code

Mandatory:

- `.claude/rules/server/code-style.md` — hard backend rules (Java 25, SOLID, layering, global exception handler, `.yml` only).
- `.claude/rules/git-commit.md` — commit/branch format if you commit.
- `docs/database/Navio Database.md` — section 11 (`social` schema) is the authoritative target schema, including the reference DDL near the end of the file.
- `docs/api/Navio Api Documentation.md` — global API rules, service routing table, Community Service section.
- `server/trip-planning-service/` — the reference implementation for controller/service/repository/DTO/exception style.
- `client/app/feature/community/_components/data.ts` — the frontend mock types the API must be able to feed (`CommunityGroup`, `CommunityGroupProfile`, `CommunityRule`, `CommunityFlair`, `CommunityBookmark`, `CreateGroupDraft`).
- `client/app/feature/community/_components/community-atoms.ts` — `joinedGroupIdsAtom`, `mutedGroupIdsAtom` show the client state this API must replace.

Note: `CLAUDE.md` also lists `.claude/rules/server/api-conventions.md` and `.claude/rules/server/testing.md`. Those files do not exist yet — do not wait for them; use `code-style.md` plus the trip-planning-service conventions.

Report briefly what already exists and what you intend to reuse **before** writing code.

---

## 2. Fixed project facts (do not re-derive, do not change)

| Fact | Value |
| --- | --- |
| Java | 25 |
| Spring Boot | 4.1.1 (parent in `server/community-service/pom.xml`) |
| Spring Cloud | 2025.1.3 |
| Base package | `com.navio.communityservice` |
| Database | PostgreSQL, one instance, schema-per-service |
| Schemas owned | `social` (groups/posts/moderation/outbox), `notif`, `media` |
| Default JPA schema | `social` (`hibernate.default_schema` in config server) |
| Migrations | Flyway, `classpath:db/migration`, `create-schemas: true`, existing file is `V1__initialize_community_schemas.sql` — your work starts at `V2__` |
| Hibernate DDL | `validate` — the migration is the source of truth, entities must match it |
| Service port | 8084 |
| Gateway prefixes | `/v1/groups/**`, `/v1/community/**`, `/v1/posts/**`, `/v1/feed/**`, `/v1/notifications/**`, `/v1/media/**` |
| Runtime config | `server/configuration-server/src/main/resources/config/community-service.yml` (Config Server). Service-local `application.yml` stays minimal. |

Dependencies already on the classpath: webmvc, restclient, data-jpa, flyway (+ `flyway-database-postgresql`), postgresql, validation, cache + caffeine, Eureka client, Spring Cloud Config, bus-kafka, spring-kafka, resilience4j circuit breaker, actuator, zipkin, prometheus, Lombok, and for tests H2 + `spring-boot-starter-webmvc-test`.

**Do not add dependencies** unless a requirement genuinely cannot be met without one. If you must, verify Java 25 / Spring Boot 4.1.1 compatibility first and say why in your final report.

---

## 3. Conventions to copy from trip-planning-service

- Packages: `controller`, `service`, `repository`, `model` (JPA entities live in `model`, **not** `entity`), `dto` (flat, no request/response subpackages), `exception`, `config`, `integration`, `support`.
- Controllers: `@RestController`, `@RequestMapping("/v1/groups")`, `@RequiredArgsConstructor`, constructor injection only, thin — no queries, no business rules.
- Current user: `@RequestHeader(name = "X-User-Id") UUID userId`. See section 5.
- Entities: Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`, `@Table(name = "...", schema = "social")`, `Instant` timestamps set in `@PrePersist` / `@PreUpdate`, `@Version` where optimistic locking is wanted.
- DTOs: prefer Java `record`s with Jakarta validation annotations (newer planner DTOs use records); Lombok `@Data @Builder` classes are acceptable where they read better. Never expose entities from controllers.
- Services: `@Service @RequiredArgsConstructor @Transactional(readOnly = true)` on the class, `@Transactional` on mutating methods. Domain exceptions as nested static classes on the service or standalone classes in `exception`.
- Errors: one `@RestControllerAdvice GlobalExceptionHandler` plus an `ErrorResponse` record/builder with `timestamp`, `status`, `message`, `error`, and `validationErrors` for bean-validation failures. Mirror `trip-planning-service/exception/`.
- Paging: `Page<T>` with `PageRequest.of(page, size)`, `page`/`size` query params defaulting to `0`/`20`.

---

## 4. Scope

### In scope

1. Create a group.
2. Read a group by slug, including the caller's membership state.
3. Discover/list groups (paginated, active only, official and larger groups first).
4. List the caller's joined groups.
5. Full-text group search over `social.groups.search_vector`.
6. Join, leave, mute, unmute a group.
7. List group members (moderator-only).
8. Replace the group's moderator set (moderator-only).
9. Edit the group profile — banner, summary, description, country, places, tags (moderator-only).
10. Manage group rules, flairs, and resources (moderator-only).
11. Keep `member_count` accurate.

### Out of scope for this task

Posts, comments, votes, bookmarks, views, reports, feed, notifications, media upload, search over posts, Kafka/outbox publishing, and the AI planning service. Do not create tables, controllers, or clients for them. `social.outbox` stays empty in this slice; if group lifecycle events are wanted later, they go through the outbox, never a direct Kafka produce from a request thread.

Do not build authentication, a Keycloak client, or a local `users` table. Do not add features the schema does not define: private/invite-only groups, ownership transfer, group deletion, bans beyond the existing `state = 'banned'` value, or a moderator hierarchy.

---

## 5. Identity and authorization

Identity arrives as gateway-asserted headers. `server/api-gateway/.../IdentityPropagationFilter` strips any client-supplied copy and re-injects values from the validated Keycloak JWT:

- `X-User-Id` — the Navio user id (Keycloak subject, UUID).
- `X-User-Roles` — comma-separated global roles (e.g. `MODERATOR`, `ADMIN`).
- `X-User-Email`.

Rules:

- Never accept a user id in a request body or path for an operation about the caller. Use `.../members/me`, never `{ "userId": "..." }`.
- Authenticated endpoints: `@RequestHeader(name = "X-User-Id") UUID userId`. A missing header raises `ServletRequestBindingException`, which the global handler must map to **401 Unauthorized** for this service (the trip service returns 400 today; for group endpoints the guest case is a real auth case and 401 is the correct signal).
- Publicly readable endpoints (group detail, discovery list, search): `@RequestHeader(name = "X-User-Id", required = false) UUID userId`. A guest gets the group with `joined = false`, `muted = false`, `role = null`.
- Guests cannot create, join, leave, mute, or moderate.
- **Group-scoped** moderator rights come from `social.group_memberships.role` (`moderator` or `admin`), not from `X-User-Roles`. Platform staff roles in `X-User-Roles` are a separate concern; do not conflate them. Per the API docs, resource roles such as group moderator stay in the owning domain service.
- Implement one reusable guard — e.g. `requireModerator(UUID groupId, UUID userId)` in the service layer — and use it for every moderator operation. Never rely on the frontend hiding a button.

---

## 6. Data model

`docs/database/Navio Database.md` section 11 defines the target tables. Create them in `V2__create_social_group_tables.sql` (add further numbered migrations if you split the work). Match the documented DDL — column names, types, checks, defaults, and indexes — rather than reinventing it.

Tables in this slice:

- `social.groups` — `id`, `name`, `slug` (UNIQUE), `description`, `country`, `places TEXT[]`, `tags TEXT[]`, `created_by_user_id`, `is_official`, `status` (`active`/`archived`/`hidden`), cached counters `member_count`, `post_count`, `weekly_visitor_count`, `weekly_contribution_count`, generated `search_vector TSVECTOR`, timestamps.
- `social.group_profiles` — one row per group: `banner_media_id`, `banner_url`, `summary`, `moderator_ids UUID[]`, `metadata_jsonb`, timestamps.
- `social.group_memberships` — PK `(group_id, user_id)`, `role` in (`member`, `moderator`, `admin`), `state` in (`joined`, `muted`, `banned`, `left`), `joined_at`, `updated_at`.
- `social.group_rules`, `social.group_flairs`, `social.group_resources` — ordered child rows with `display_order`.

Constraints and integrity:

- Every child table has `group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE`.
- `user_id` columns are **soft references** to `iam.users`. No cross-schema foreign key — the API documentation and database design both forbid it.
- Keep the documented indexes: GIN on `search_vector` and `tags`, the discovery index `(status, is_official DESC, member_count DESC)`, `group_memberships(user_id, state, updated_at DESC)`, and `group_memberships(group_id, state)`.
- `social.groups` is spelled `social.groups` everywhere; `groups` is a PostgreSQL keyword in window-frame syntax, so always schema-qualify it and let Hibernate use `@Table(name = "groups", schema = "social")`.

Two decisions the schema forces, which the implementation must respect:

1. **`group_profiles.moderator_ids` is a denormalized display array.** `group_memberships.role` is authoritative. Any moderator change must rewrite both in the same transaction, or the sidebar will show stale moderators.
2. **`member_count` is a cached counter, not a live COUNT.** Maintain it inside the same transaction as the membership change. Use `COUNT(*) WHERE state = 'joined'` only for a reconciliation/backfill path, never per read request.

`slug` is generated from `name` at creation (lowercase, non-alphanumerics to `-`, collapsed, trimmed) and is **immutable** — the client routes to `/community/{groupname}` using a slugified name, so a changing slug breaks links and shared URLs. `name` is likewise immutable in this slice. Say so explicitly in the API docs you produce.

---

## 7. API surface

All paths are gateway-visible under `/v1`. There is no `/api` prefix in this project.

| Method | Path | Auth | Moderator | Purpose |
| --- | --- | --- | --- | --- |
| `POST` | `/v1/groups` | required | – | Create a group; creator becomes `role = admin`, `state = joined` |
| `GET` | `/v1/groups` | optional | – | Discovery list, paginated, `status = active` |
| `GET` | `/v1/groups/mine` | required | – | Groups the caller has joined |
| `GET` | `/v1/groups/search?q=` | optional | – | Full-text group search |
| `GET` | `/v1/groups/{slug}` | optional | – | Group detail + caller membership |
| `POST` | `/v1/groups/{slug}/members/me` | required | – | Join (idempotent) |
| `DELETE` | `/v1/groups/{slug}/members/me` | required | – | Leave (idempotent) |
| `PATCH` | `/v1/groups/{slug}/members/me` | required | – | Mute / unmute |
| `GET` | `/v1/groups/{slug}/members` | required | yes | Member list for moderator tools, paginated |
| `PUT` | `/v1/groups/{slug}/moderators` | required | yes | Replace the moderator set |
| `PATCH` | `/v1/groups/{slug}/profile` | required | yes | Edit description/country/places/tags/summary/banner |
| `PUT` | `/v1/groups/{slug}/rules` | required | yes | Replace ordered rules |
| `PUT` | `/v1/groups/{slug}/flairs` | required | yes | Replace post/user flairs |
| `PUT` | `/v1/groups/{slug}/resources` | required | yes | Replace sidebar resources |

Routing warning: `/mine` and `/search` must not be swallowed by `/{slug}`. Declare the literal mappings first and constrain the slug pattern if needed.

Suggested detail response (align field names with the frontend `CommunityGroup` + `CommunityGroupProfile`):

```json
{
  "id": "0f1c…",
  "name": "Thailand Restaurants",
  "slug": "thailand-restaurants",
  "description": "Where to eat across Thailand.",
  "country": "Thailand",
  "places": ["Bangkok", "Chiang Mai"],
  "tags": ["food", "restaurants"],
  "isOfficial": true,
  "status": "active",
  "memberCount": 1280,
  "postCount": 342,
  "bannerUrl": "https://…",
  "summary": "Local food routes and honest recommendations.",
  "weeklyVisitorCount": 4100,
  "weeklyContributionCount": 87,
  "moderatorIds": ["…"],
  "rules": [{ "id": "…", "title": "…", "description": "…", "displayOrder": 0 }],
  "postFlairs": [{ "id": "…", "label": "Itinerary", "tone": "itinerary", "displayOrder": 0 }],
  "userFlairs": [],
  "resources": [{ "id": "…", "label": "Bangkok food map", "url": "https://…", "displayOrder": 0 }],
  "joined": true,
  "muted": false,
  "role": "member",
  "createdAt": "2026-05-07T05:00:00Z",
  "updatedAt": "2026-05-07T05:00:00Z"
}
```

List and discovery responses may return a lighter projection (no rules/flairs/resources) to avoid over-fetching; keep `joined`, `muted`, `memberCount`.

---

## 8. Behavior rules

### Create group

Validation: `name` `@NotBlank @Size(max = 120)`, trimmed before persistence (bean validation does not trim — trim in code, and reject a name that is blank after trimming); `description` required; `country` optional `@Size(max = 120)`; `places`/`tags` default to empty arrays, each element trimmed, blanks dropped, duplicates removed.

One transaction must produce: the `social.groups` row, its `social.group_profiles` row, a `social.group_memberships` row for the creator with `role = 'admin'` and `state = 'joined'`, `member_count = 1`, and `moderator_ids = {creator}`. It must never be possible to end up with a group whose creator is not a member.

Slug uniqueness: `existsBySlug` is a race, not a guarantee. The `UNIQUE` constraint is authoritative — catch `DataIntegrityViolationException` and map it to **409 Conflict**. Either reject the duplicate or append a numeric suffix, but pick one behavior and document it.

Returns **201 Created**.

### Join / leave / mute

Because `(group_id, user_id)` is the primary key, membership is an **upsert over a state machine**, not an insert/delete:

- Join: no row → insert `role = 'member'`, `state = 'joined'`; row with `state = 'left'` → set `state = 'joined'`, keep the existing `role`; row already `joined`/`muted` → no-op. Increment `member_count` **only** on a real transition into `joined`.
- Leave: set `state = 'left'`; decrement `member_count` only on a real transition out of `joined`/`muted`. Leaving never deletes the row and never changes `role`.
- Mute/unmute: toggle between `joined` and `muted`. A muted member is still a member — `member_count` does not change.
- `state = 'banned'` is set only by moderator tooling (out of scope here); a banned user's join attempt must be rejected with **403**, not silently re-joined.
- Joining never grants `moderator`/`admin`.
- A member whose `role` is `moderator` or `admin` may not leave while they are the group's last moderator — return **409 Conflict** with a message telling them to hand over moderation first. Do not build ownership transfer.

All three return **200 OK** with the updated membership view, or **204**; be consistent.

### Replace moderators

`PUT` with the complete desired set — replacement semantics, not append:

```json
{ "userIds": ["…", "…"] }
```

1. Caller must currently be `moderator` or `admin` in this group.
2. Normalize the request into a `Set` so duplicate ids are harmless.
3. Every requested user must already have a membership row in this group with `state` in (`joined`, `muted`). If any does not, reject the whole request with **400** and change nothing — never auto-join them.
4. The set may not be empty; a group must keep at least one moderator (**409**).
5. Demote members no longer in the set to `role = 'member'`, promote the new ones to `role = 'moderator'`, leave the existing `admin` alone unless explicitly removed.
6. Rewrite `group_profiles.moderator_ids` in the same transaction.

Given current `[A, B]` and request `[B, C]`, the result is exactly `[B, C]`.

### Edit profile

`PATCH`, all fields optional, absent fields unchanged. Editable: `description`, `country`, `places`, `tags`, `summary`, `bannerUrl`, `bannerMediaId`. **Not** editable: `id`, `name`, `slug`, `status`, `isOfficial`, any counter, membership, moderators. Do not let the DTO carry those fields at all.

Media bytes are not this endpoint's problem — store the URL/media id only. `navio.community.media.storage-path` and the `media` schema exist for the separate media module; do not build upload handling here.

### Rules / flairs / resources

`PUT` replaces the whole ordered collection for the group, matching how the frontend create/edit screens submit them. Assign `display_order` from array position. Flairs carry `flairType` (`post` or `user`) and `tone`; validate `tone` against the values the frontend `CommunityFlair` type allows.

### Discovery, mine, search

- Discovery: `status = 'active'` only, ordered `is_official DESC, member_count DESC`, paginated — this is exactly what `idx_social_groups_discovery` supports.
- Mine: join through `group_memberships` where `user_id = :callerId AND state IN ('joined','muted')`, ordered by `updated_at DESC`. One JOIN query, not one query per group.
- Search: `search_vector @@ plainto_tsquery('simple', :q)` ordered by `ts_rank(...) DESC`, using the GIN index. A blank or missing `q` returns an empty page — it must not fall through to "all groups". Never load groups into Java and filter there.

---

## 9. Errors

Extend `GlobalExceptionHandler` with the group domain exceptions and keep the `ErrorResponse` shape identical across endpoints:

| Status | Cause |
| --- | --- |
| 400 | Validation failure, malformed body, moderator candidate who is not a member, blank search query where the contract rejects it |
| 401 | Missing `X-User-Id` on an endpoint that requires identity |
| 403 | Authenticated but not a group moderator; banned user attempting to join |
| 404 | Unknown slug, or a group whose `status` hides it from the caller |
| 409 | Duplicate slug, last moderator leaving, empty moderator set |
| 422 | Persistable-but-invalid state, matching how the trip service uses it |

Never leak SQL, Hibernate types, or stack traces. Log with context (operation, group slug, caller id) at the point of failure.

---

## 10. Performance

- No `FetchType.EAGER` collections on `Group`. Memberships are potentially thousands of rows.
- Membership checks are `EXISTS` queries; counts are the cached column, never `getMembers().size()`.
- `/mine`, discovery, and search each execute a single paginated query — no N+1.
- Batch the rules/flairs/resources replacement (delete-then-insert in one transaction) rather than row-by-row round trips.
- Caffeine cache is available and configured; use it only if a read is measurably hot, and never cache caller-specific membership flags in a shared entry.

---

## 11. Testing

Follow the project's existing test setup rather than importing a new framework:

- Controller tests: `@WebMvcTest(GroupController.class)` with `@MockitoBean` services, like `TripControllerTest`. Cover status codes, header handling (present/absent `X-User-Id`), and validation failures.
- Service tests: plain JUnit 5 + Mockito over mocked repositories. Cover the state machine and counter arithmetic.
- Repository/constraint tests: the current `src/test/resources/application.yml` runs H2 with `flyway.enabled: false` and `ddl-auto: none`, so no schema exists in tests today. The group schema uses PostgreSQL-only features (`TEXT[]`, generated `TSVECTOR`, GIN indexes, `pg_trgm`) that H2 cannot run. So either:
  - add a Postgres Testcontainers profile for repository/integration tests (a new dependency — justify it), or
  - keep repository coverage at the service level with mocks and state the gap explicitly in your report.

  Do not "fix" this by weakening the migration to H2-compatible SQL.

Minimum cases:

1. Creator becomes `admin` + member; `member_count = 1`; profile row created; `moderator_ids` contains the creator.
2. Name trimmed; blank-after-trim rejected; over-120-character name rejected.
3. Duplicate slug maps to 409 via the DB constraint, not just the pre-check.
4. Guest (no `X-User-Id`) cannot create/join/mute/moderate; guest can read a group with `joined = false`.
5. Join is idempotent; joining twice increments `member_count` once; join does not grant moderator.
6. Leave sets `state = 'left'` and decrements once; leaving twice decrements once.
7. Mute keeps `member_count` unchanged and keeps `joined` semantics correct.
8. Last moderator cannot leave → 409.
9. Moderator replacement: `[A,B]` + `[B,C]` → `[B,C]`; duplicates in the request are harmless; a non-member in the request rejects the whole call and changes nothing; empty set → 409.
10. `moderator_ids` on the profile matches `group_memberships` after every moderator change.
11. Profile PATCH updates only supplied fields; name/slug cannot be changed through it.
12. Non-moderator member and non-member both get 403 on every moderator endpoint.
13. `/mine` returns only joined/muted groups.
14. Search: matching query returns hits; blank query returns an empty page, not everything.

---

## 12. Final verification scenario

Run this end to end before declaring done:

1. User A creates "Thailand EV Charging" → slug `thailand-ev-charging`, A is `admin`+`joined`, `member_count = 1`, `moderator_ids = [A]`.
2. User B joins → `member_count = 2`, B is `member`, not a moderator.
3. A replaces moderators with `[A, B]` → both `moderator`/`admin`, `moderator_ids = [A, B]`, `member_count` unchanged.
4. A replaces moderators with `[B]` → A demoted to `member`, `moderator_ids = [B]`.
5. User C, never a member, is submitted as a moderator → rejected, C is still not a member, moderator set unchanged.
6. B mutes the group → still a member, `member_count` unchanged, `muted = true`.
7. B updates only `bannerUrl` → summary, description, name, and slug all unchanged.
8. B, now the only moderator, tries to leave → 409.
9. Search `q=ev charging` finds the group; `q=` returns an empty page.
10. `/v1/groups/mine` returns the group for both A and B.

---

## 13. Implementation order

1. Inspect the repo and report what exists / what you will reuse.
2. `V2__` Flyway migration for the six group tables, constraints, and indexes.
3. Entities in `model/` matching the migration exactly (`ddl-auto: validate` will fail otherwise).
4. Repositories with derived and `@Query` methods; no logic.
5. DTOs + validation.
6. `GroupService` (+ `GroupMembershipService` / `GroupModerationService` if the class grows past one responsibility) with transaction boundaries and the `requireModerator` guard.
7. `GroupController` (split moderator tooling into `GroupModerationController` if the controller grows).
8. Exceptions + `GlobalExceptionHandler` + `ErrorResponse`.
9. Tests.
10. `mvn -pl community-service test` (or the module's own `mvnw`) until green.
11. Re-check transaction boundaries, counter arithmetic, and the `moderator_ids` mirror.

---

## 14. Final report

After implementation, produce:

1. Summary of what was implemented.
2. Files created and modified.
3. Migration contents: tables, constraints, indexes.
4. Endpoint table: method, path, auth, moderator requirement, purpose.
5. One example request/response per endpoint.
6. How each of these works: current-user identity, guest restriction, slug uniqueness, membership state machine, `member_count` maintenance, moderator replacement, `moderator_ids` mirroring.
7. Test results, including anything you could not cover and why.
8. Assumptions made and any deviation from `docs/database/Navio Database.md`, with justification.

Do not stop at pseudocode. Write the real code in the real module and make it compile and pass tests.
