# coordination-service

A small, self-hostable Ktor backend that solves remote event coordination for
PingRing: it lets a device create a short-lived "Ping-Ring" event, lets other
devices join/leave it by peer id, enforces one active event per peer id, and
lets the host end the event (clearing membership). All state is kept
in-memory with automatic expiry cleanup, so no database is required to run
it. State is persisted locally in a JSON snapshot so a restart does not discard
active events, membership, or UWB exchange material.

This module is independent of the Android app module; it only shares plain
Kotlin serialization DTOs at the JSON boundary (the Android app talks to it
over HTTP, e.g. via Retrofit/OkHttp).

## Running locally

From the repository root:

```powershell
.\gradlew.bat :coordination-service:run
```

```bash
./gradlew :coordination-service:run
```

The server listens on port `8080` by default. Override with the `PORT`
environment variable:

```powershell
$env:PORT = "9090"; .\gradlew.bat :coordination-service:run
```

Set `COORDINATION_STATE_FILE` to place the durable state snapshot elsewhere;
it defaults to `coordination-state.json` in the process working directory.

## Running tests

```powershell
.\gradlew.bat :coordination-service:test
```

## API

All request/response bodies are JSON. Errors are returned as
`{"error": "<code>", "message": "<human readable>"}` with an appropriate
HTTP status code.

| Method | Path                                  | Description                                   |
|--------|---------------------------------------|------------------------------------------------|
| GET    | `/health`                              | Liveness check.                               |
| POST   | `/api/events`                          | Create an event; the host is added as a member. |
| GET    | `/api/events/{eventId}`                | Get event details and current membership.     |
| GET    | `/api/events/{eventId}/members`        | List current members.                         |
| POST   | `/api/events/{eventId}/members`        | Join the event with a peer id (idempotent).   |
| DELETE | `/api/events/{eventId}/members/{peerId}` | Leave the event as that peer.               |
| POST   | `/api/events/{eventId}/end`            | Host-only: end the event and clear membership. |
| GET    | `/api/events/{eventId}/uwb`            | Read UWB out-of-band parameters.            |
| POST   | `/api/events/{eventId}/uwb/controller` | Host publishes controller parameters.        |
| POST   | `/api/events/{eventId}/uwb/peers`      | Member publishes its UWB address.            |

### `POST /api/events`

Request:

```json
{
  "id": "PICNIC42",
  "name": "Picnic",
  "description": "Park meetup",
  "ttlSeconds": 3600,
  "hostPeerId": "peer-abc",
  "hostName": "Alex",
  "trackHost": false
}
```

Response `201 Created`:

```json
{
  "event": {
    "id": "PICNIC42",
    "name": "Picnic",
    "description": "Park meetup",
    "expiryTimestamp": 1737400000000,
    "hostPeerId": "peer-abc",
    "trackHost": false
  },
  "members": [
    { "peerId": "peer-abc", "name": "Alex", "joinedAt": 1737396400000, "isHost": true }
  ]
}
```

Errors: `400` (blank name/host fields, non-positive `ttlSeconds`), `409` if
`hostPeerId` is already active in another (non-expired) event.

### `POST /api/events/{eventId}/members`

Request: `{ "peerId": "peer-xyz", "name": "Jordan" }`

Response `200 OK`: the same `event`/`members` shape as create. Re-joining the
same event with the same `peerId` is idempotent (updates the display name).

Errors: `404` if the event does not exist or has expired, `409` if `peerId`
is already an active member of a **different** event.

### `DELETE /api/events/{eventId}/members/{peerId}`

Response `204 No Content`. Errors: `404` if the event or the member does not
exist.

### `POST /api/events/{eventId}/end`

Request: `{ "hostPeerId": "peer-abc" }`

Host-only: removes the event and all of its membership, freeing every
member's peer id to join/host another event. Response `204 No Content`.

Errors: `404` if the event does not exist, `403` if `hostPeerId` does not
match the event's host.

### UWB out-of-band exchange

The host publishes its UWB controller address, complex channel, session ID,
and static STS material to `/uwb/controller`. Each active member publishes its
local UWB address to `/uwb/peers`; `/uwb` returns the complete exchange
snapshot. The Android client uses this data to begin a real
`RangingParameters.CONFIG_UNICAST_DS_TWR` session whenever both devices
support UWB. Exchange state is event-scoped and is removed when an event ends
or expires.

### `GET /api/events/{eventId}` / `GET /api/events/{eventId}/members`

Response `200 OK` with the current event/membership snapshot. Errors: `404`
if the event does not exist or has expired.

## Design notes

- **Storage**: a single `EventCoordinationService` holds events and
  membership in in-memory maps guarded by one `kotlinx.coroutines.sync.Mutex`,
  and snapshots mutations to a JSON state file. Create/join/leave/end and the
  one-active-event-per-peer invariant are atomic with respect to each other.
- **Expiry**: every request lazily purges expired events before acting, and a
  background coroutine sweeps expired events every 30 seconds so idle events
  are still reclaimed even with no further traffic.
- **Errors**: domain exceptions (`ValidationException`,
  `EventNotFoundException`, `MemberNotFoundException`,
  `PeerAlreadyActiveException`, `ForbiddenHostActionException`) are mapped to
  `400`/`404`/`409`/`403` respectively via Ktor `StatusPages`.
