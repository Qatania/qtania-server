package com.example.cataniaunited.player;

import com.example.cataniaunited.dto.MessageDTO;
import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class for managing {@link Player} objects.
 * Handles adding, retrieving, and removing players, and associating them with WebSocket connections.
 * Also provides utility methods related to player state, like checking for win conditions.
 * <br>
 * Important: This Service is Application Scoped which means it is a Singleton that handles
 * all existing Players, there should be no lengthy calculations in this Class to ensure that
 * different Clients don't experience long waits.
 */
@ApplicationScoped
public class PlayerService {

    private static final Logger logger = Logger.getLogger(PlayerService.class);
    private final ConcurrentHashMap<String, Player> playersByConnectionId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Player> playersById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketConnection> connectionsByPlayerId = new ConcurrentHashMap<>();

    /**
     * Adds a new player associated with a WebSocket connection.
     * The player is stored in maps indexed by connection ID and their unique player ID.
     *
     * @param connection The {@link WebSocketConnection} of the new player.
     * @return The newly created {@link Player} object.
     */
    public Player addPlayer(WebSocketConnection connection) {
        Player player = new Player(connection);
        playersByConnectionId.put(connection.id(), player);
        playersById.put(player.getUniqueId(), player);
        connectionsByPlayerId.put(player.getUniqueId(), connection);
        return player;
    }

    public void addPlayerWithoutConnection(Player player) {
        playersById.put(player.getUniqueId(), player);
    }

    /**
     * Retrieves a player by their associated WebSocket connection.
     *
     * @param connection The {@link WebSocketConnection}.
     * @return The {@link Player} associated with the connection, or null if not found.
     */
    public Player getPlayerByConnection(WebSocketConnection connection) {
        return playersByConnectionId.get(connection.id());
    }

    /**
     * Retrieves a player by their unique player ID.
     *
     * @param id The unique ID of the player.
     * @return The {@link Player} with the given ID, or null if not found.
     */
    public Player getPlayerById(String id) {
        Player player = playersById.get(id);
        if (player == null) {
            logger.warnf("Player not found: id=%s", id);
        }
        return player;
    }

    /**
     * Gets a list of all currently managed players.
     *
     * @return A list of {@link Player} objects. The list is a snapshot at the time of calling.
     */
    public List<Player> getAllPlayers() {
        return playersByConnectionId.values().stream().toList();
    }

    /**
     * Removes a player based on their WebSocket connection ID.
     * The player is removed from all internal tracking maps.
     *
     * @param connection The {@link WebSocketConnection} of the player to remove.
     */
    public void removePlayerByConnectionId(WebSocketConnection connection) {
        Player player = playersByConnectionId.remove(connection.id());
        if (player == null)
            return;

        playersById.remove(player.getUniqueId());
        connectionsByPlayerId.remove(player.getUniqueId());
    }

    /**
     * Clears all player data from the service.
     * Intended for testing purposes to reset state.
     */
    public void clearAllPlayersForTesting() {
        playersByConnectionId.clear();
        playersById.clear();
    }

    public void setUsername(String playerId, String username) throws GameException {
        Player player = getPlayerById(playerId);
        if (player == null) {
            logger.errorf("Update of username failed, no player found: playerId = %s, username = %s", playerId, username);
            throw new GameException("Player with id %s not found", playerId);
        }
        player.setUsername(username);
    }

    /**
     * Retrieves the WebSocket connection associated with a given player ID.
     * If the connection exists but is no longer open, it is removed from tracking and null is returned.
     *
     * @param playerId The unique ID of the player.
     * @return The {@link WebSocketConnection} for the player, or null if not found or not open.
     */
    public WebSocketConnection getConnectionByPlayerId(String playerId) {
        if (playerId == null) {
            logger.warnf("Cannot retrieve web socket connection of player, id is null");
            return null;
        }

        WebSocketConnection conn = connectionsByPlayerId.get(playerId);
        if (conn != null && !conn.isOpen()) {
            logger.warnf("Web socket connection of player not open: playerId = %s", playerId);
            return null;
        }
        return conn;
    }

    public Uni<Void> sendMessageToPlayer(String playerId, MessageDTO message) {
        WebSocketConnection connection = getConnectionByPlayerId(playerId);
        if (connection == null) {
            logger.warnf("No web socket connection for player %s – message dropped!", playerId);
            return Uni.createFrom().voidItem();
        }
        logger.debugf("Sending message to player: playerId=%s, message=%s", playerId, message);
        return connection.sendText(message)
                .onItem().invoke(v -> logger.debugf("Message sent: player=%s message=%s", playerId, message))
                .onFailure().invoke(err -> logger.errorf(err, "Failed to send message: player=%s", playerId));
    }

    /**
     * Initializes the player's resources map
     */
    public void initializePlayerResources(String playerId) throws GameException {
        Player player = getPlayerById(playerId);
        if (player == null) {
            logger.errorf("Initialize player resources failed, no player found: playerId = %s", playerId);
            throw new GameException("Player with id %s not found", playerId);
        }

        for (TileType resource : TileType.values()) {
            if (resource == TileType.WASTE)
                continue; // No waste resource

            //Set the amount of each resource to 0
            int resourceCount = player.getResourceCount(resource);
            player.removeResource(resource, resourceCount);
        }
    }
}
