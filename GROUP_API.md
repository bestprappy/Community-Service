# Community group API

Gateway base: `http://localhost:8080`. Public URLs have `/v1`, without `/api`.
Use `Authorization: Bearer <JWT>` at the gateway. The private service on port 8084 accepts
only gateway-asserted `X-User-Id` UUID identity; never send identity in caller-operation bodies.
The optional-identity reads also accept authenticated callers to populate their membership flags.
`X-User-Roles` does not authorize group moderation: an active group membership with `moderator`
or `admin` role is required. The gateway strips spoofed identity headers even for guests.

## Endpoints

| Method | Path | Identity | Group moderator | Purpose |
| --- | --- | --- | --- | --- |
| `POST` | `/v1/groups` | Required | No | Create a group |
| `GET` | `/v1/groups` | Optional | No | Discover active groups |
| `GET` | `/v1/groups/mine` | Required | No | List caller joined/muted groups |
| `GET` | `/v1/groups/search?q=ev%20charging` | Optional | No | Search active groups |
| `GET` | `/v1/groups/{slug}` | Optional | No | Read detail and caller membership |
| `POST` | `/v1/groups/{slug}/members/me` | Required | No | Join idempotently |
| `DELETE` | `/v1/groups/{slug}/members/me` | Required | No | Leave idempotently |
| `PATCH` | `/v1/groups/{slug}/members/me` | Required | No | Mute or unmute |
| `GET` | `/v1/groups/{slug}/members` | Required | Yes | List active members |
| `PUT` | `/v1/groups/{slug}/moderators` | Required | Yes | Replace moderator set |
| `PATCH` | `/v1/groups/{slug}/profile` | Required | Yes | Update supplied profile fields |
| `PUT` | `/v1/groups/{slug}/rules` | Required | Yes | Replace ordered rules |
| `PUT` | `/v1/groups/{slug}/flairs` | Required | Yes | Replace both flair collections |
| `PUT` | `/v1/groups/{slug}/resources` | Required | Yes | Replace sidebar resources |

## Contract and behavior

- `name` and `slug` are immutable. Slugs are lowercase ASCII letters/digits separated by
  single hyphens. Duplicate slugs return 409 using the database UNIQUE constraint;
  no numeric suffix is added. Names without an ASCII letter/digit and the reserved
  names `mine` / `search` return 400.
- Creation trims the name/description/country and normalizes place/tag arrays by trimming,
  dropping blanks, and deduplicating. Missing place/tag arrays become empty. The creator
  is an admin and joined member, the count starts at one, and the profile is created with
  its summary initialized from the description. Ordinary users cannot mark groups official.
- `page` and `size` default to 0 and 20; page must be nonnegative and size must be 1?100.
  Responses use Spring Data `Page` (`content`, `totalElements`, `totalPages`, etc.). Sorting
  is fixed by the endpoint; the page `sort` metadata may be empty because ordering lives
  in the SQL/JPQL query. Discovery sorts official groups first, then member count, then ID.
  Mine sorts membership update time descending, then ID. Search sorts rank descending, then ID.
- Discovery/search expose active groups. Hidden groups return 404 except to their active
  moderators, who can also see them in mine. Archived groups remain directly readable and
  appear in mine; this slice adds no archive lifecycle or extra archived-write restriction.
- Search uses PostgreSQL `plainto_tsquery('simple', q)` against the generated search vector.
  Omitted, blank, or whitespace-only `q` returns an empty page. No Java filtering is used.
- Guest detail/list/search responses have `joined=false`, `muted=false`, `role=null`.
  A retained left/banned membership can still expose its stored role, but has `joined=false`;
  role alone never grants authorization. Member lists include joined/muted members only.
- Join inserts a member or reactivates a left membership, preserving any existing role.
  Joining an already joined/muted member is a no-op, including preserving mute state.
  Leave retains the row and role, and only changes active membership to left.
  Banned members cannot join, leave to erase a ban, or change mute state (403).
- Muted members still have `joined=true`. Mute/unmute requires active membership and never
  changes `memberCount`. Join increments once; leaving joined/muted decrements once.
  This resolves the prompt's counter wording in favor of counting both joined and muted.
- Moderator replacement requires the complete desired UUID list. Duplicates are harmless.
  Every candidate must already be joined/muted; an invalid candidate rejects all changes
  with 400. An empty set returns 409. Removed moderators/admins become members; new
  moderators are promoted; retained admins remain admins. The active moderator UUID array
  is updated in the same transaction. A moderator leaving/rejoining also refreshes that array.
- The last active moderator cannot leave (409: hand over moderation first). There is no
  owner hierarchy or ownership-transfer operation. Group writes serialize on the group row
  to prevent concurrent membership, counter, and moderator races.
- Profile PATCH accepts only `description`, `country`, `places`, `tags`, `summary`, `bannerUrl`,
  `bannerMediaId`. Omitted fields remain unchanged. Explicit null clears country/banner URL/
  media ID; null description/summary/arrays is rejected. Empty arrays clear places/tags.
  Description cannot be blank; summary may be empty. Unknown/immutable fields return 400.
  URLs, when supplied, must use HTTP(S). Media bytes are handled by a separate module.
- Rules/flairs/resources use wrapper objects shown below. PUT replaces the complete collection,
  and an empty array clears it. IDs are newly generated; display order comes from array position.
  Flairs PUT replaces post and user flairs together. Allowed tones: `reliable`, `question`,
  `unsourced`, `speculation`, `itinerary`, `food`, `ev`.
- Detail uses `resources` for sidebar links, corresponding to the frontend mock's `bookmarks`.
  `createdById` is included. The schema has no group avatar field; clients should use their
  existing avatar fallback. This change supplies the API and does not rewire frontend atoms.

## Errors

All endpoints use the same `ErrorResponse` fields. Missing required identity is 401;
malformed JSON/UUIDs/validation failures are 400; insufficient group rights are 403;
unknown/hidden groups are 404; duplicate slug or moderation conflicts are 409;
other storage constraint failures are 422. Unexpected errors return a generic 500.
Responses never include database messages, SQL, or stack traces.

```json
{
  "timestamp": "2026-09-09T05:00:00Z",
  "status": 409,
  "message": "Hand over moderation before leaving: you are the last moderator",
  "error": "Conflict",
  "validationErrors": null
}
```

Validation errors populate `validationErrors` with field-to-message entries.

## Examples

Each example is independent. A is the creator/admin. Membership mutation examples act as B;
B has joined before any moderator replacement that includes B. UUIDs and timestamps below
are illustrative. For private service verification, the request's `X-User-Id` is the gateway
assertion; public clients send a bearer token instead. Guest examples omit identity.

### `POST /v1/groups` ? Create a group

Request:

```http
POST /v1/groups HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "name": "Thailand EV Charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ]
}
```

Response: **201** with `Location: /v1/groups/thailand-ev-charging`.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `GET /v1/groups` ? Discover active groups

Request:

```http
GET /v1/groups HTTP/1.1
Host: localhost:8084

```

Response: **200**.

```json
{
  "content": [
    {
      "id": "10000000-0000-4000-8000-000000000001",
      "name": "Thailand EV Charging",
      "slug": "thailand-ev-charging",
      "description": "EV charging tips across Thailand.",
      "country": "Thailand",
      "places": [
        "Bangkok"
      ],
      "tags": [
        "ev"
      ],
      "isOfficial": false,
      "status": "active",
      "memberCount": 1,
      "postCount": 0,
      "joined": false,
      "muted": false,
      "role": null,
      "createdAt": "2026-09-09T05:00:00Z",
      "updatedAt": "2026-09-09T05:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "empty": true,
      "sorted": false,
      "unsorted": true
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": false,
    "unsorted": true
  },
  "numberOfElements": 1,
  "first": true,
  "empty": false
}
```

### `GET /v1/groups/mine` ? List caller joined/muted groups

Request:

```http
GET /v1/groups/mine HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001

```

Response: **200**.

```json
{
  "content": [
    {
      "id": "10000000-0000-4000-8000-000000000001",
      "name": "Thailand EV Charging",
      "slug": "thailand-ev-charging",
      "description": "EV charging tips across Thailand.",
      "country": "Thailand",
      "places": [
        "Bangkok"
      ],
      "tags": [
        "ev"
      ],
      "isOfficial": false,
      "status": "active",
      "memberCount": 1,
      "postCount": 0,
      "joined": true,
      "muted": false,
      "role": "admin",
      "createdAt": "2026-09-09T05:00:00Z",
      "updatedAt": "2026-09-09T05:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "empty": true,
      "sorted": false,
      "unsorted": true
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": false,
    "unsorted": true
  },
  "numberOfElements": 1,
  "first": true,
  "empty": false
}
```

### `GET /v1/groups/search?q=ev%20charging` ? Search active groups

Request:

```http
GET /v1/groups/search?q=ev%20charging HTTP/1.1
Host: localhost:8084

```

Response: **200**.

```json
{
  "content": [
    {
      "id": "10000000-0000-4000-8000-000000000001",
      "name": "Thailand EV Charging",
      "slug": "thailand-ev-charging",
      "description": "EV charging tips across Thailand.",
      "country": "Thailand",
      "places": [
        "Bangkok"
      ],
      "tags": [
        "ev"
      ],
      "isOfficial": false,
      "status": "active",
      "memberCount": 1,
      "postCount": 0,
      "joined": false,
      "muted": false,
      "role": null,
      "createdAt": "2026-09-09T05:00:00Z",
      "updatedAt": "2026-09-09T05:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "empty": true,
      "sorted": false,
      "unsorted": true
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": false,
    "unsorted": true
  },
  "numberOfElements": 1,
  "first": true,
  "empty": false
}
```

### `GET /v1/groups/{slug}` ? Read detail and caller membership

Request:

```http
GET /v1/groups/thailand-ev-charging HTTP/1.1
Host: localhost:8084

```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [],
  "joined": false,
  "muted": false,
  "role": null,
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `POST /v1/groups/{slug}/members/me` ? Join idempotently

Request:

```http
POST /v1/groups/thailand-ev-charging/members/me HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000002

```

Response: **200**.

```json
{
  "groupId": "10000000-0000-4000-8000-000000000001",
  "joined": true,
  "muted": false,
  "role": "member",
  "state": "joined",
  "memberCount": 2
}
```

### `DELETE /v1/groups/{slug}/members/me` ? Leave idempotently

Request:

```http
DELETE /v1/groups/thailand-ev-charging/members/me HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000002

```

Response: **200**.

```json
{
  "groupId": "10000000-0000-4000-8000-000000000001",
  "joined": false,
  "muted": false,
  "role": "member",
  "state": "left",
  "memberCount": 1
}
```

### `PATCH /v1/groups/{slug}/members/me` ? Mute or unmute

Request:

```http
PATCH /v1/groups/thailand-ev-charging/members/me HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000002
Content-Type: application/json

{
  "muted": true
}
```

Response: **200**.

```json
{
  "groupId": "10000000-0000-4000-8000-000000000001",
  "joined": true,
  "muted": true,
  "role": "member",
  "state": "muted",
  "memberCount": 2
}
```

### `GET /v1/groups/{slug}/members` ? List active members

Request:

```http
GET /v1/groups/thailand-ev-charging/members HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001

```

Response: **200**.

```json
{
  "content": [
    {
      "userId": "00000000-0000-0000-0000-000000000001",
      "role": "admin",
      "state": "joined",
      "joinedAt": "2026-09-09T05:00:00Z",
      "updatedAt": "2026-09-09T05:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": {
      "empty": true,
      "sorted": false,
      "unsorted": true
    },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": {
    "empty": true,
    "sorted": false,
    "unsorted": true
  },
  "numberOfElements": 1,
  "first": true,
  "empty": false
}
```

### `PUT /v1/groups/{slug}/moderators` ? Replace moderator set

Request:

```http
PUT /v1/groups/thailand-ev-charging/moderators HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "userIds": [
    "00000000-0000-0000-0000-000000000001",
    "00000000-0000-0000-0000-000000000002"
  ]
}
```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 2,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001",
    "00000000-0000-0000-0000-000000000002"
  ],
  "rules": [],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `PATCH /v1/groups/{slug}/profile` ? Update supplied profile fields

Request:

```http
PATCH /v1/groups/thailand-ev-charging/profile HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "bannerUrl": "https://example.com/banner.jpg"
}
```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": "https://example.com/banner.jpg",
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `PUT /v1/groups/{slug}/rules` ? Replace ordered rules

Request:

```http
PUT /v1/groups/thailand-ev-charging/rules HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "rules": [
    {
      "title": "Be kind",
      "description": "Respect other members."
    }
  ]
}
```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [
    {
      "id": "20000000-0000-4000-8000-000000000001",
      "title": "Be kind",
      "description": "Respect other members.",
      "displayOrder": 0
    }
  ],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `PUT /v1/groups/{slug}/flairs` ? Replace both flair collections

Request:

```http
PUT /v1/groups/thailand-ev-charging/flairs HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "flairs": [
    {
      "flairType": "post",
      "label": "EV",
      "tone": "ev"
    }
  ]
}
```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [],
  "postFlairs": [
    {
      "id": "30000000-0000-4000-8000-000000000001",
      "label": "EV",
      "tone": "ev",
      "displayOrder": 0
    }
  ],
  "userFlairs": [],
  "resources": [],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

### `PUT /v1/groups/{slug}/resources` ? Replace sidebar resources

Request:

```http
PUT /v1/groups/thailand-ev-charging/resources HTTP/1.1
Host: localhost:8084
X-User-Id: 00000000-0000-0000-0000-000000000001
Content-Type: application/json

{
  "resources": [
    {
      "label": "Charging map",
      "url": "https://example.com/map"
    }
  ]
}
```

Response: **200**.

```json
{
  "id": "10000000-0000-4000-8000-000000000001",
  "name": "Thailand EV Charging",
  "slug": "thailand-ev-charging",
  "description": "EV charging tips across Thailand.",
  "country": "Thailand",
  "places": [
    "Bangkok"
  ],
  "tags": [
    "ev"
  ],
  "createdById": "00000000-0000-0000-0000-000000000001",
  "isOfficial": false,
  "status": "active",
  "memberCount": 1,
  "postCount": 0,
  "bannerUrl": null,
  "bannerMediaId": null,
  "summary": "EV charging tips across Thailand.",
  "weeklyVisitorCount": 0,
  "weeklyContributionCount": 0,
  "moderatorIds": [
    "00000000-0000-0000-0000-000000000001"
  ],
  "rules": [],
  "postFlairs": [],
  "userFlairs": [],
  "resources": [
    {
      "id": "40000000-0000-4000-8000-000000000001",
      "label": "Charging map",
      "url": "https://example.com/map",
      "displayOrder": 0
    }
  ],
  "joined": true,
  "muted": false,
  "role": "admin",
  "createdAt": "2026-09-09T05:00:00Z",
  "updatedAt": "2026-09-09T05:00:00Z"
}
```

