package com.example.cataniaunited.player;

import com.example.cataniaunited.dto.MessageDTO;
import com.example.cataniaunited.dto.MessageType;
import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class PlayerServiceTest {

    @Inject
    PlayerService playerService;

    private WebSocketConnection mockConnection1;
    private WebSocketConnection mockConnection2;
    private final String mockConnId1 = "connId_1";
    private final String mockConnId2 = "connId_2";

    @BeforeEach
    void setUp() {
        playerService.clearAllPlayersForTesting();

        mockConnection1 = mock(WebSocketConnection.class);
        when(mockConnection1.id()).thenReturn(mockConnId1);
        when(mockConnection1.isOpen()).thenReturn(true);

        mockConnection2 = mock(WebSocketConnection.class);
        when(mockConnection2.id()).thenReturn(mockConnId2);
        when(mockConnection2.isOpen()).thenReturn(true);
    }

    @Test
    void addPlayerShouldCreateAndStorePlayer() {
        Player addedPlayer = playerService.addPlayer(mockConnection1);

        assertNotNull(addedPlayer, "Added player should not be null.");
        assertEquals(mockConnId1, addedPlayer.getConnection().id(), "Player's connection ID should match.");
        assertTrue(addedPlayer.getUsername().startsWith("RandomPlayer_"), "Player should have a random default username.");
        assertNotNull(addedPlayer.getUniqueId(), "Player should have a unique ID.");

        Player retrievedByConn = playerService.getPlayerByConnection(mockConnection1);
        Player retrievedById = playerService.getPlayerById(addedPlayer.getUniqueId());

        assertSame(addedPlayer, retrievedByConn, "Player retrieved by connection should be the same instance.");
        assertSame(addedPlayer, retrievedById, "Player retrieved by ID should be the same instance.");
        assertEquals(1, playerService.getAllPlayers().size(), "Should be one player in the service.");
    }

    @Test
    void addPlayerWithSameConnectionShouldOverwritePlayerForConnectionIdButCreateNewPlayerObject() {
        Player player1 = playerService.addPlayer(mockConnection1);
        String player1UniqueId = player1.getUniqueId();

        Player player2 = playerService.addPlayer(mockConnection1);
        String player2UniqueId = player2.getUniqueId();

        assertNotSame(player1, player2, "Adding with same connection should create a new Player object.");
        assertNotEquals(player1UniqueId, player2UniqueId, "The new player object should have a different unique ID.");

        Player retrievedByConn = playerService.getPlayerByConnection(mockConnection1);
        assertSame(player2, retrievedByConn, "Player retrieved by connection should be the latest one added.");

        assertNotNull(playerService.getPlayerById(player1UniqueId), "Original player1 should still be in playersById map.");
        assertSame(player1, playerService.getPlayerById(player1UniqueId));
        assertNotNull(playerService.getPlayerById(player2UniqueId), "New player2 should be in playersById map.");
        assertSame(player2, playerService.getPlayerById(player2UniqueId));

        assertEquals(1, playerService.getAllPlayers().size(), "getAllPlayers should return 1 player (the one associated with mockConnId1).");
        assertTrue(playerService.getAllPlayers().contains(player2));
    }


    @Test
    void getPlayerByConnectionShouldReturnCorrectPlayerOrNull() {
        assertNull(playerService.getPlayerByConnection(mockConnection1), "Should return null if player not found.");

        Player addedPlayer = playerService.addPlayer(mockConnection1);
        Player retrievedPlayer = playerService.getPlayerByConnection(mockConnection1);
        assertSame(addedPlayer, retrievedPlayer, "Should return the added player.");
    }

    @Test
    void getPlayerByIdShouldReturnCorrectPlayerOrNull() {
        assertNull(playerService.getPlayerById("nonExistentId"), "Should return null if player ID not found.");

        Player addedPlayer = playerService.addPlayer(mockConnection1);
        Player retrievedPlayer = playerService.getPlayerById(addedPlayer.getUniqueId());
        assertSame(addedPlayer, retrievedPlayer, "Should return the added player by its unique ID.");
    }

    @Test
    void getAllPlayersShouldReturnAllPlayersFromConnectionMap() {
        assertTrue(playerService.getAllPlayers().isEmpty(), "Initially, getAllPlayers should return an empty list.");

        Player player1 = playerService.addPlayer(mockConnection1);
        Player player2 = playerService.addPlayer(mockConnection2);

        List<Player> allPlayers = playerService.getAllPlayers();
        assertEquals(2, allPlayers.size(), "Should return two players.");
        assertTrue(allPlayers.contains(player1), "List should contain player1.");
        assertTrue(allPlayers.contains(player2), "List should contain player2.");
    }

    @Test
    void removePlayerByConnectionIdShouldRemovePlayerFromBothMaps() {
        Player addedPlayer = playerService.addPlayer(mockConnection1);
        String uniqueId = addedPlayer.getUniqueId();

        assertNotNull(playerService.getPlayerByConnection(mockConnection1));
        assertNotNull(playerService.getPlayerById(uniqueId));
        assertEquals(1, playerService.getAllPlayers().size());

        playerService.removePlayerByConnectionId(mockConnection1);

        assertNull(playerService.getPlayerByConnection(mockConnection1), "Player should be removed from connection map.");
        assertNull(playerService.getPlayerById(uniqueId), "Player should be removed from ID map.");
        assertTrue(playerService.getAllPlayers().isEmpty(), "getAllPlayers should return an empty list after removal.");
    }

    @Test
    void removePlayerByConnectionIdForNonExistentPlayerShouldDoNothing() {
        playerService.addPlayer(mockConnection1);

        playerService.removePlayerByConnectionId(mockConnection2);

        assertEquals(1, playerService.getAllPlayers().size(), "Size should remain 1 if removing a non-existent player.");
        assertNotNull(playerService.getPlayerByConnection(mockConnection1), "Existing player should not be affected.");
    }

    @Test
    void removePlayerRemovesFromBothMaps() {
        Player player = playerService.addPlayer(mockConnection1);
        playerService.removePlayerByConnectionId(mockConnection1);
        assertNull(playerService.getPlayerByConnection(mockConnection1));
        assertNull(playerService.getPlayerById(player.getUniqueId()));
    }

    @Test
    void removePlayerNonExistingConnection() {
        Player player = playerService.addPlayer(mockConnection1);
        playerService.removePlayerByConnectionId(mockConnection1);

        assertDoesNotThrow(() -> playerService.removePlayerByConnectionId(mockConnection1));

        assertNull(playerService.getPlayerByConnection(mockConnection1));
        assertNull(playerService.getPlayerById(player.getUniqueId()));
    }

    @Test
    void getAllPlayersReturnsListOfAllPlayers() {
        playerService.addPlayer(mockConnection1);
        List<Player> players = playerService.getAllPlayers();
        assertEquals(1, players.size());
    }

    @Test
    void getConnectionByPlayerIdReturnsNullForNullPlayerId() {
        assertNull(playerService.getConnectionByPlayerId(null), "Should return null if playerId is null.");
    }

    @Test
    void getConnectionByPlayerIdReturnsNullForNonExistentPlayerId() {
        assertNull(playerService.getConnectionByPlayerId("nonExistentUniqueId"), "Should return null for a non-existent player ID.");
    }

    @Test
    void getConnectionByPlayerIdReturnsCorrectConnectionForExistingPlayer() {
        Player player1 = playerService.addPlayer(mockConnection1);
        Player player2 = playerService.addPlayer(mockConnection2);

        WebSocketConnection retrievedConn1 = playerService.getConnectionByPlayerId(player1.getUniqueId());
        WebSocketConnection retrievedConn2 = playerService.getConnectionByPlayerId(player2.getUniqueId());

        assertSame(mockConnection1, retrievedConn1, "Should retrieve mockConnection1 for player1's ID.");
        assertSame(mockConnection2, retrievedConn2, "Should retrieve mockConnection2 for player2's ID.");
    }

    @Test
    void getConnectionByPlayerIdReturnsNullAndRemovesMappingIfConnectionIsClosed() {
        Player player = playerService.addPlayer(mockConnection1);
        String playerId = player.getUniqueId();

        assertSame(mockConnection1, playerService.getConnectionByPlayerId(playerId));
        when(mockConnection1.isOpen()).thenReturn(false);

        assertNull(playerService.getConnectionByPlayerId(playerId),
                "Should return null for a closed connection.");

        verify(mockConnection1, atLeastOnce()).isOpen();

        assertNull(playerService.getConnectionByPlayerId(playerId),
                "Should still return null after the mapping for the closed connection was removed.");
    }

    @Test
    void getConnectionByPlayerIdWithOpenConnection() {
        Player player = playerService.addPlayer(mockConnection1);
        when(mockConnection1.isOpen()).thenReturn(true);

        WebSocketConnection conn = playerService.getConnectionByPlayerId(player.getUniqueId());
        assertSame(mockConnection1, conn);
        verify(mockConnection1, atLeastOnce()).isOpen();
    }

    @Test
    void sendMessageToPlayerShouldNotThrowExceptionIfSendTextFails() {
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        RuntimeException simulatedException = new RuntimeException("Simulated network error during send");
        MessageDTO testMessage = new MessageDTO(MessageType.DICE_RESULT, JsonNodeFactory.instance.objectNode());

        when(mockConnection.isOpen()).thenReturn(true);
        when(mockConnection.id()).thenReturn(UUID.randomUUID().toString());
        when(mockConnection.sendText(any(MessageDTO.class)))
                .thenReturn(Uni.createFrom().failure(simulatedException));

        Player newPlayer = playerService.addPlayer(mockConnection);
        Uni<Void> sendUni = playerService.sendMessageToPlayer(newPlayer.getUniqueId(), testMessage);
        sendUni.subscribe().withSubscriber(UniAssertSubscriber.create())
                .assertFailedWith(RuntimeException.class, "Simulated network error during send")
                .assertSubscribed()
                .assertTerminated();

        verify(mockConnection).sendText(testMessage);
    }

    @Test
    void sendMessageToPlayerShouldNotThrowExceptionIfConnectionIsClosed() {
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        RuntimeException simulatedException = new RuntimeException("Simulated network error during send");
        MessageDTO testMessage = new MessageDTO(MessageType.DICE_RESULT, JsonNodeFactory.instance.objectNode());

        when(mockConnection.isOpen()).thenReturn(false);
        when(mockConnection.id()).thenReturn(UUID.randomUUID().toString());
        when(mockConnection.sendText(any(MessageDTO.class)))
                .thenReturn(Uni.createFrom().failure(simulatedException));

        Player newPlayer = playerService.addPlayer(mockConnection);
        playerService.sendMessageToPlayer(newPlayer.getUniqueId(), testMessage);

        verify(mockConnection, never()).sendText(testMessage);
    }

    @Test
    void sendMessageToPlayerShouldNotThrowExceptionForEmptyPlayerId() {
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        MessageDTO testMessage = new MessageDTO(MessageType.DICE_RESULT, JsonNodeFactory.instance.objectNode());

        playerService.sendMessageToPlayer(null, testMessage);

        verify(mockConnection, never()).sendText(testMessage);
    }

    @Test
    void initializePlayerResourcesShouldThrowExceptionIfPlayerIsNotExisting() {
        String nonExistentPlayerId = "non-existent-player-id-123";
        var exception = assertThrows(GameException.class, () -> playerService.initializePlayerResources(nonExistentPlayerId));
        assertEquals("Player with id %s not found".formatted(nonExistentPlayerId), exception.getMessage());

    }

    @Test
    void initializePlayerResourcesShouldResetResourcesToZero() throws GameException {
        Player player = playerService.addPlayer(mockConnection1);
        player.receiveResource(TileType.WOOD, 35);
        player.receiveResource(TileType.CLAY, 70);
        player.receiveResource(TileType.WHEAT, 120);
        player.receiveResource(TileType.SHEEP, 10);
        player.receiveResource(TileType.ORE, 47);

        assertEquals(35, player.getResourceCount(TileType.WOOD));
        assertEquals(70, player.getResourceCount(TileType.CLAY));
        assertEquals(120, player.getResourceCount(TileType.WHEAT));
        assertEquals(10, player.getResourceCount(TileType.SHEEP));
        assertEquals(47, player.getResourceCount(TileType.ORE));

        playerService.initializePlayerResources(player.getUniqueId());

        assertEquals(0, player.getResourceCount(TileType.WOOD));
        assertEquals(0, player.getResourceCount(TileType.CLAY));
        assertEquals(0, player.getResourceCount(TileType.WHEAT));
        assertEquals(0, player.getResourceCount(TileType.SHEEP));
        assertEquals(0, player.getResourceCount(TileType.ORE));
    }

}

