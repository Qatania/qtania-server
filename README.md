# Catania United – Server

Backend for **Catania United**, a multiplayer browser-based adaptation of the Settlers of Catan board game. Built with [Quarkus](https://quarkus.io/) and communicates with clients entirely over WebSockets.

---

## Table of Contents

- [Running the Application](#running-the-application)
- [Packaging](#packaging)
- [Native Executable](#native-executable)
- [WebSocket API](#websocket-api)
  - [Common Schemas](#common-schemas)
  - [MessageType Reference](#messagetype-reference)
  - [Lifecycle Events](#lifecycle-events)
  - [Client → Server Messages](#client--server-messages)
  - [Server → Client Events](#server--client-events)

---

## Running the Application

### Dev mode (live reload)

```shell
./gradlew quarkusDev
```

> The Quarkus Dev UI is available in dev mode at <http://localhost:8080/q/dev/>.

---

## Packaging

```shell
./gradlew build
```

Produces `build/quarkus-app/quarkus-run.jar`. Dependencies are placed in `build/quarkus-app/lib/`.

```shell
java -jar build/quarkus-app/quarkus-run.jar
```

To build an über-jar instead:

```shell
./gradlew build -Dquarkus.package.jar.type=uber-jar
java -jar build/*-runner.jar
```

---

## Native Executable

```shell
./gradlew build -Dquarkus.native.enabled=true
```

Without GraalVM installed, use a container build:

```shell
./gradlew build -Dquarkus.native.enabled=true -Dquarkus.native.container-build=true
./build/catania-united-1.0.0-SNAPSHOT-runner
```

See the [Quarkus Gradle tooling guide](https://quarkus.io/guides/gradle-tooling) for more details.

---

## WebSocket API

All communication is handled over a single persistent WebSocket connection. Every message is a JSON object conforming to the `MessageDTO` envelope described below.

| Property | Value |
|---|---|
| Protocol | WebSocket |
| Encoding | JSON (UTF-8) |
| Direction | Bidirectional |

---

### Common Schemas

#### MessageDTO

The envelope for **all** messages in both directions.

```json
{
  "type": "string",
  "player": "string",
  "lobbyId": "string",
  "players": {
    "<playerId>": { }
  },
  "message": { }
}
```

| Field | Type | Direction | Description |
|---|---|---|---|
| `type` | `MessageType` | Both | Identifies the message kind |
| `player` | `string` | Both | UUID of the sending / addressed player |
| `lobbyId` | `string` | Both | Lobby code / ID |
| `players` | `Map<string, PlayerInfo>` | Server → Client | Current state of all players in the lobby |
| `message` | `object` | Both | Message-type-specific payload (see each message below) |

---

#### PlayerInfo

Embedded inside the `players` map on most server responses.

```json
{
  "id": "string",
  "username": "string",
  "color": "#RRGGBB",
  "isHost": true,
  "isReady": false,
  "isActivePlayer": false,
  "canRollDice": false,
  "isSetupRound": true,
  "victoryPoints": 0,
  "resources": {
    "WHEAT": 0,
    "SHEEP": 0,
    "WOOD": 0,
    "CLAY": 0,
    "ORE": 0
  }
}
```

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Player UUID |
| `username` | `string` | Display name |
| `color` | `string` | Assigned hex color code |
| `isHost` | `boolean` | Whether this player is the lobby host |
| `isReady` | `boolean` | Whether this player marked themselves as ready |
| `isActivePlayer` | `boolean` | Whether it is currently this player's turn |
| `canRollDice` | `boolean` | Whether this player may roll dice this turn |
| `isSetupRound` | `boolean` | Whether the game is still in setup phase |
| `victoryPoints` | `integer` | Current victory point count |
| `resources` | `Map<TileType, integer>` | Resource inventory (only keys with quantity > 0 need be present) |

---

#### LobbyInfo

Used inside the `LOBBY_LIST` response.

```json
{
  "id": "string",
  "hostPlayer": "string",
  "playerCount": 2
}
```

| Field | Type | Description |
|---|---|---|
| `id` | `string` | Lobby code |
| `hostPlayer` | `string` | UUID of the host player |
| `playerCount` | `integer` | Number of players currently in the lobby |

---

#### TradeRequest

Used as the payload for bank and player-to-player trades.

```json
{
  "offeredResources": {
    "WHEAT": 4
  },
  "targetResources": {
    "ORE": 1
  }
}
```

| Field | Type | Description |
|---|---|---|
| `offeredResources` | `Map<TileType, integer>` | Resources the player gives away |
| `targetResources` | `Map<TileType, integer>` | Resources the player wants to receive |

---

#### PlayerTradeRequest

Wraps a `TradeRequest` with source and target player IDs.

```json
{
  "sourcePlayerId": "string",
  "targetPlayerId": "string",
  "trade": {
    "offeredResources": { "WHEAT": 2 },
    "targetResources": { "ORE": 1 }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `sourcePlayerId` | `string` | UUID of the player initiating the trade |
| `targetPlayerId` | `string` | UUID of the player receiving the offer |
| `trade` | `TradeRequest` | The trade details |

---

#### TileType (Resource Types)

Used as keys in resource maps.

| Value | Description |
|---|---|
| `WHEAT` | Grain / wheat |
| `SHEEP` | Wool / sheep |
| `WOOD` | Lumber / wood |
| `CLAY` | Brick / clay |
| `ORE` | Ore |
| `WASTE` | Desert (no resource, never appears in resource maps) |

---

### MessageType Reference

#### Client → Server

| Type | Description |
|---|---|
| `CREATE_LOBBY` | Create a new lobby |
| `GET_LOBBIES` | Request a list of available lobbies |
| `JOIN_LOBBY` | Join an existing lobby by code |
| `LEAVE_LOBBY` | Leave the current lobby |
| `SET_USERNAME` | Set or update display name |
| `SET_READY` | Toggle ready state |
| `START_GAME` | Start the game (host only) |
| `PLACE_SETTLEMENT` | Place a settlement on the board |
| `UPGRADE_SETTLEMENT` | Upgrade a settlement to a city |
| `PLACE_ROAD` | Place a road on the board |
| `ROLL_DICE` | Roll the dice |
| `END_TURN` | End the current turn |
| `TRADE_WITH_BANK` | Trade resources with the bank |
| `CREATE_PLAYER_TRADE_REQUEST` | Offer a trade to another player |
| `ACCEPT_TRADE_REQUEST` | Accept a pending trade offer |
| `REJECT_TRADE_REQUEST` | Reject a pending trade offer |
| `CHEAT_ATTEMPT` | Secretly steal a resource |
| `REPORT_PLAYER` | Report a player suspected of cheating |

#### Server → Client

| Type | Description |
|---|---|
| `CONNECTION_SUCCESSFUL` | Sent after the WebSocket connection is established |
| `ERROR` | Generic error response |
| `ALERT` | Non-critical notification sent to a single player |
| `LOBBY_CREATED` | Sent to host after lobby is created |
| `LOBBY_UPDATED` | Broadcast when lobby state changes |
| `LOBBY_CLOSED` | Broadcast when the host leaves, closing the lobby |
| `LOBBY_LIST` | List of available lobbies |
| `PLAYER_JOINED` | Broadcast when a player joins the lobby |
| `GAME_STARTED` | Broadcast when the game begins |
| `GAME_WON` | Broadcast when a player reaches the victory condition |
| `ROLL_DICE` | Broadcast that a player started rolling |
| `DICE_RESULT` | Broadcast with the dice outcome |
| `NEXT_TURN` | Broadcast when the turn advances |
| `PLAYER_RESOURCE_UPDATE` | Broadcast when any player's resources change |
| `PLACE_SETTLEMENT` | Broadcast when a settlement is successfully placed |
| `UPGRADE_SETTLEMENT` | Broadcast when a settlement is upgraded to a city |
| `PLACE_ROAD` | Broadcast when a road is successfully placed |
| `TRADE_OFFER` | Sent to the target player when a trade offer is created |

---

### Lifecycle Events

#### Connection Established

Triggered automatically when a client opens the WebSocket.

**Server → Client:**
```json
{
  "type": "CONNECTION_SUCCESSFUL",
  "message": {
    "playerId": "uuid-string"
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.playerId` | `string` | The UUID assigned to this player session |

---

#### Client Disconnected

When a player's connection drops, the server removes them from all lobbies and notifies remaining members.

**Server → Remaining lobby members:**
```json
{
  "type": "LOBBY_UPDATED",
  "player": "disconnected-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

> If the disconnected player was the lobby host, `type` is `LOBBY_CLOSED` instead of `LOBBY_UPDATED`.

---

### Client → Server Messages

#### `CREATE_LOBBY`

Create a new lobby. The requesting player automatically becomes the host.

**Request:**
```json
{
  "type": "CREATE_LOBBY",
  "player": "player-uuid"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `CREATE_LOBBY` |
| `player` | Yes | Requesting player UUID |

**Response (to sender):** `LOBBY_CREATED`
```json
{
  "type": "LOBBY_CREATED",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

---

#### `GET_LOBBIES`

Retrieve all currently available lobbies, sorted by creation time (newest first).

**Request:**
```json
{
  "type": "GET_LOBBIES",
  "player": "player-uuid"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `GET_LOBBIES` |
| `player` | Yes | Requesting player UUID |

**Response (to sender):** `LOBBY_LIST`
```json
{
  "type": "LOBBY_LIST",
  "message": {
    "lobbies": [
      {
        "id": "lobby-code",
        "hostPlayer": "host-player-uuid",
        "playerCount": 2
      }
    ]
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.lobbies` | `LobbyInfo[]` | Array of available lobbies |

---

#### `JOIN_LOBBY`

Join an existing lobby by its code.

**Request:**
```json
{
  "type": "JOIN_LOBBY",
  "player": "player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `JOIN_LOBBY` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code to join |

**Response (broadcast to all lobby members):** `PLAYER_JOINED`
```json
{
  "type": "PLAYER_JOINED",
  "player": "joining-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "color": "#A3C4BC"
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.color` | `string` | Hex color assigned to the joining player |

**Errors:**
| Error message | Cause |
|---|---|
| `"Failed to join lobby: lobby session not found or full"` | Lobby does not exist or has no open spots |

---

#### `LEAVE_LOBBY`

Leave the current lobby.

**Request:**
```json
{
  "type": "LEAVE_LOBBY",
  "player": "player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `LEAVE_LOBBY` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code to leave |

**Response (broadcast to remaining lobby members):**

- `LOBBY_UPDATED` if the leaving player is not the host
- `LOBBY_CLOSED` if the leaving player is the host

```json
{
  "type": "LOBBY_UPDATED | LOBBY_CLOSED",
  "player": "leaving-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

---

#### `SET_USERNAME`

Set or update the player's display name.

**Request:**
```json
{
  "type": "SET_USERNAME",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "username": "MyName"
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `SET_USERNAME` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby the player is in |
| `message.username` | Yes | New display name |

**Response (broadcast to all lobby members):** `LOBBY_UPDATED`
```json
{
  "type": "LOBBY_UPDATED",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

---

#### `SET_READY`

Toggle the player's ready state. No payload required.

**Request:**
```json
{
  "type": "SET_READY",
  "player": "player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `SET_READY` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby the player is in |

**Response (broadcast to all lobby members):** `LOBBY_UPDATED`
```json
{
  "type": "LOBBY_UPDATED",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

---

#### `START_GAME`

Start the game. Only the host player may send this.

**Request:**
```json
{
  "type": "START_GAME",
  "player": "host-player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `START_GAME` |
| `player` | Yes | Host player UUID |
| `lobbyId` | Yes | Lobby code |

**Response (broadcast to all lobby members):** `GAME_STARTED`
```json
{
  "type": "GAME_STARTED",
  "player": "host-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "gameboard": { }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.gameboard` | `object` | Full game board JSON representation |

---

#### `PLACE_SETTLEMENT`

Place a settlement at a board vertex position.

**Request:**
```json
{
  "type": "PLACE_SETTLEMENT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "settlementPositionId": 42
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `PLACE_SETTLEMENT` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.settlementPositionId` | Yes | Integer index of the board vertex position |

**Response (broadcast to all lobby members):** `PLACE_SETTLEMENT`
```json
{
  "type": "PLACE_SETTLEMENT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "gameboard": { }
  }
}
```

> If placement triggers the win condition, `GAME_WON` is broadcast instead (see [`GAME_WON`](#game_won)).

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid settlement position id: id = <value>"` | Non-integer or out-of-range position |

---

#### `UPGRADE_SETTLEMENT`

Upgrade an existing settlement at a board vertex to a city.

**Request:**
```json
{
  "type": "UPGRADE_SETTLEMENT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "settlementPositionId": 42
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `UPGRADE_SETTLEMENT` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.settlementPositionId` | Yes | Integer index of the vertex with the settlement to upgrade |

**Response (broadcast to all lobby members):** `UPGRADE_SETTLEMENT`
```json
{
  "type": "UPGRADE_SETTLEMENT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "gameboard": { }
  }
}
```

> If upgrade triggers the win condition, `GAME_WON` is broadcast instead.

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid settlement position id: id = <value>"` | Non-integer or out-of-range position |

---

#### `PLACE_ROAD`

Place a road at a board edge position.

**Request:**
```json
{
  "type": "PLACE_ROAD",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "roadId": 17
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `PLACE_ROAD` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.roadId` | Yes | Integer index of the board edge position |

**Response (broadcast to all lobby members):** `PLACE_ROAD`
```json
{
  "type": "PLACE_ROAD",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "gameboard": { }
  }
}
```

> If placement triggers the win condition, `GAME_WON` is broadcast instead.

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid road id: id = <value>"` | Non-integer or out-of-range road position |

---

#### `ROLL_DICE`

Roll the dice for the current turn. The server sends two messages: a rolling announcement followed by the actual result.

**Request:**
```json
{
  "type": "ROLL_DICE",
  "player": "player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `ROLL_DICE` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |

**Intermediate broadcast (to all lobby members):** `ROLL_DICE`

Sent first to announce that a roll is in progress.

```json
{
  "type": "ROLL_DICE",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "rollingUsername": "PlayerName",
    "player": "player-uuid"
  }
}
```

**Response (broadcast to all lobby members):** `DICE_RESULT`

Sent after the roll is resolved and resources have been distributed.

```json
{
  "type": "DICE_RESULT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "rollingUsername": "PlayerName",
    "player": "player-uuid"
  }
}
```

> Additional fields in `message` (e.g. individual die values, total) are populated by the game engine.

---

#### `END_TURN`

End the current player's turn and advance to the next.

**Request:**
```json
{
  "type": "END_TURN",
  "player": "player-uuid",
  "lobbyId": "lobby-code"
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `END_TURN` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |

**Response (broadcast to all lobby members):** `NEXT_TURN`
```json
{
  "type": "NEXT_TURN",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "gameboard": { }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.gameboard` | `object` | Updated game board state |

---

#### `TRADE_WITH_BANK`

Trade resources with the bank. Must be the player's turn. Default ratio is 4:1 (may be lower with harbour ports).

**Request:**
```json
{
  "type": "TRADE_WITH_BANK",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "offeredResources": {
      "WHEAT": 4
    },
    "targetResources": {
      "ORE": 1
    }
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `TRADE_WITH_BANK` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.offeredResources` | Yes | `Map<TileType, integer>` – resources to give |
| `message.targetResources` | Yes | `Map<TileType, integer>` – resources to receive |

**Response (broadcast to all lobby members):** `PLAYER_RESOURCE_UPDATE`
```json
{
  "type": "PLAYER_RESOURCE_UPDATE",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid trade request format."` | Malformed JSON payload |
| `"Invalid turn"` | Not the player's turn |

---

#### `CREATE_PLAYER_TRADE_REQUEST`

Send a trade offer to another player. It must be either the source or the target player's turn.

**Request:**
```json
{
  "type": "CREATE_PLAYER_TRADE_REQUEST",
  "player": "source-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "sourcePlayerId": "source-player-uuid",
    "targetPlayerId": "target-player-uuid",
    "trade": {
      "offeredResources": {
        "WHEAT": 2
      },
      "targetResources": {
        "ORE": 1
      }
    }
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `CREATE_PLAYER_TRADE_REQUEST` |
| `player` | Yes | Sending player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.sourcePlayerId` | Yes | UUID of the player making the offer |
| `message.targetPlayerId` | Yes | UUID of the player receiving the offer |
| `message.trade.offeredResources` | Yes | Resources offered by the source player |
| `message.trade.targetResources` | Yes | Resources requested from the target player |

**Server → Target player:** `TRADE_OFFER`
```json
{
  "type": "TRADE_OFFER",
  "player": "target-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "tradeId": "uuid-string",
    "tradeRequest": {
      "sourcePlayerId": "source-player-uuid",
      "targetPlayerId": "target-player-uuid",
      "trade": {
        "offeredResources": { "WHEAT": 2 },
        "targetResources": { "ORE": 1 }
      }
    }
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.tradeId` | `string` | Unique ID for this trade (used in accept / reject) |
| `message.tradeRequest` | `PlayerTradeRequest` | Full trade request payload |

**Response (to source player):** `ALERT`
```json
{
  "type": "ALERT",
  "player": "source-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "message": "Sent trade request to TargetUsername",
    "severity": "success"
  }
}
```

**Errors:**
| Error message | Cause |
|---|---|
| `"Trade request format is invalid"` | Malformed JSON payload |
| `"Trade request is invalid"` | Validation by TradingService failed |
| `"Invalid turn"` | Neither the source nor target player's turn |

---

#### `ACCEPT_TRADE_REQUEST`

Accept a pending trade offer addressed to the player.

**Request:**
```json
{
  "type": "ACCEPT_TRADE_REQUEST",
  "player": "accepting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "tradeId": "uuid-string"
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `ACCEPT_TRADE_REQUEST` |
| `player` | Yes | Accepting player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.tradeId` | Yes | ID of the trade to accept (received in `TRADE_OFFER`) |

**Server → Source player:** `ALERT`
```json
{
  "type": "ALERT",
  "player": "accepting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "message": "Trade request was accepted by AcceptingUsername",
    "severity": "success"
  }
}
```

**Response (broadcast to all lobby members):** `PLAYER_RESOURCE_UPDATE`
```json
{
  "type": "PLAYER_RESOURCE_UPDATE",
  "player": "accepting-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

---

#### `REJECT_TRADE_REQUEST`

Reject a pending trade offer.

**Request:**
```json
{
  "type": "REJECT_TRADE_REQUEST",
  "player": "rejecting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "tradeId": "uuid-string"
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `REJECT_TRADE_REQUEST` |
| `player` | Yes | Rejecting player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.tradeId` | Yes | ID of the trade to reject (received in `TRADE_OFFER`) |

**Server → Source player:** `ALERT`
```json
{
  "type": "ALERT",
  "player": "rejecting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "message": "Trade request was rejected by RejectingUsername",
    "severity": "error"
  }
}
```

> No broadcast to other lobby members.

---

#### `CHEAT_ATTEMPT`

Secretly try to steal one resource from the bank. If caught by another player via `REPORT_PLAYER`, the resource is taken back.

**Request:**
```json
{
  "type": "CHEAT_ATTEMPT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "resource": "WHEAT"
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `CHEAT_ATTEMPT` |
| `player` | Yes | Player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.resource` | Yes | `TileType` enum value of the desired resource |

**Response (broadcast to all lobby members):** `PLAYER_RESOURCE_UPDATE`
```json
{
  "type": "PLAYER_RESOURCE_UPDATE",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid resource type"` | Unrecognized `TileType` value |

---

#### `REPORT_PLAYER`

Report another player suspected of cheating. The outcome depends on whether the accusation is correct.

**Request:**
```json
{
  "type": "REPORT_PLAYER",
  "player": "reporting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "reportedId": "suspected-player-uuid"
  }
}
```

| Field | Required | Description |
|---|---|---|
| `type` | Yes | `REPORT_PLAYER` |
| `player` | Yes | Reporting player UUID |
| `lobbyId` | Yes | Lobby code |
| `message.reportedId` | Yes | UUID of the player being reported |

**Response (private to the reporting player):** `ALERT`

| Outcome | `message.message` | `message.severity` |
|---|---|---|
| Correct report, first catch | `"<username> got caught cheating!"` | `"success"` |
| Correct report, already caught | `"<username> was already caught cheating! You lost 1 resource."` | `"error"` |
| False accusation | `"You falsely accused <username> of cheating! You lost 1 resource."` | `"error"` |

```json
{
  "type": "ALERT",
  "player": "reporting-player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "message": "...",
    "severity": "success | error"
  }
}
```

**Response (broadcast to all lobby members):** `PLAYER_RESOURCE_UPDATE`
```json
{
  "type": "PLAYER_RESOURCE_UPDATE",
  "player": "reporting-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  }
}
```

**Errors:**
| Error message | Cause |
|---|---|
| `"Invalid player to report."` | Reported player ID not found |

---

### Server → Client Events

#### `ERROR`

Sent to the originating client when a handled exception occurs.

```json
{
  "type": "ERROR",
  "message": {
    "error": "Human-readable error description"
  }
}
```

---

#### `ALERT`

A non-critical notification sent to a single player.

```json
{
  "type": "ALERT",
  "player": "player-uuid",
  "lobbyId": "lobby-code",
  "message": {
    "message": "Notification text",
    "severity": "success | error"
  }
}
```

| `severity` | Meaning |
|---|---|
| `"success"` | Positive outcome (e.g. trade accepted) |
| `"error"` | Negative outcome (e.g. false accusation penalty) |

---

#### `GAME_WON`

Broadcast to all lobby members when a player reaches the required victory points.

```json
{
  "type": "GAME_WON",
  "player": "winner-player-uuid",
  "lobbyId": "lobby-code",
  "players": {
    "<playerId>": { }
  },
  "message": {
    "winner": "WinnerUsername",
    "leaderboard": [
      {
        "id": "string",
        "username": "string",
        "color": "#RRGGBB",
        "isHost": false,
        "isReady": true,
        "isActivePlayer": false,
        "canRollDice": false,
        "isSetupRound": false,
        "victoryPoints": 10,
        "resources": { }
      }
    ]
  }
}
```

| Field | Type | Description |
|---|---|---|
| `message.winner` | `string` | Display name of the winning player |
| `message.leaderboard` | `PlayerInfo[]` | All players sorted by `victoryPoints` descending |
