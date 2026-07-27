package com.example.cataniaunited.cleanup;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.game.GameService;
import com.example.cataniaunited.game.trade.TradingService;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@QuarkusTest
public class CleanupServiceTest {

    @Inject
    CleanupService cleanupService;

    @InjectSpy
    LobbyService lobbyService;

    @InjectSpy
    GameService gameService;

    @InjectSpy
    PlayerService playerService;

    @InjectSpy
    TradingService tradingService;

    @ConfigProperty(name = "qatania.cleanup.threshold-hours")
    Integer cleanupThresholdHours;

    @BeforeEach
    void setUp() {
        lobbyService.clearLobbies();
        playerService.clearAllPlayersForTesting();
    }

    @Test
    public void testLobbyCleanup() throws GameException {
        Player player = new Player("Player1");
        Player player2 = new Player("Player2");
        String lobbyId = "lob001";
        Lobby lobby = spy(new Lobby(lobbyId, player.getUniqueId()));
        doReturn(Instant.now().minus(cleanupThresholdHours + 1, ChronoUnit.HOURS)).when(lobby).getCreatedAt();
        doReturn(List.of(lobby)).when(lobbyService).getOpenLobbies();
        doReturn(lobby).when(lobbyService).getLobbyById(lobbyId);
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());
        gameService.createGameboard(lobbyId);

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobby.getPlayers().size());

        cleanupService.cleanupOldLobbies();

        assertThrows(GameException.class, () -> gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(0, lobby.getPlayers().size());

        verify(lobbyService).getOpenLobbies();
        verify(lobbyService).removePlayerFromLobby(lobbyId, player.getUniqueId());
        verify(gameService).removeGameBoardForLobby(lobbyId);
        verify(lobbyService).removeLobby(lobbyId);
        verify(tradingService).removeAllOpenTradeRequestForLobbyId(lobbyId);
    }

    @Test
    void testFinishedGamesCleanup() throws GameException {
        Player player = new Player("Player1");
        Player player2 = new Player("Player2");
        String lobbyId = "lob001";
        Lobby lobby = new Lobby(lobbyId, player.getUniqueId());
        doReturn(List.of(lobby)).when(lobbyService).getOpenLobbies();
        doReturn(lobby).when(lobbyService).getLobbyById(lobbyId);
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());
        gameService.createGameboard(lobbyId);
        lobby.setGameEnded(true);

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobby.getPlayers().size());

        cleanupService.cleanupFinishedGames();

        assertThrows(GameException.class, () -> gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(0, lobby.getPlayers().size());

        verify(lobbyService).getOpenLobbies();
        verify(lobbyService).removePlayerFromLobby(lobbyId, player.getUniqueId());
        verify(lobbyService).removePlayerFromLobby(lobbyId, player2.getUniqueId());
        verify(gameService).removeGameBoardForLobby(lobbyId);
        verify(lobbyService).removeLobby(lobbyId);
        verify(tradingService).removeAllOpenTradeRequestForLobbyId(lobbyId);
    }

    @Test
    void lobbyCleanupShouldWorkIfNoLobbiesExist() {
        assertDoesNotThrow(() -> cleanupService.cleanupOldLobbies());
    }

    @Test
    void finishedGamesCleanupShouldWorkIfNoLobbiesExist() {assertDoesNotThrow(() -> cleanupService.cleanupFinishedGames());}

    @Test
    void lobbyCleanupShouldNotRemoveNewLobby() throws GameException {
        Player player = new Player("Player1");
        Player player2 = new Player("Player2");
        String lobbyId = lobbyService.createLobby(player.getUniqueId());
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());
        gameService.createGameboard(lobbyId);

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobbyService.getLobbyById(lobbyId).getPlayers().size());

        cleanupService.cleanupOldLobbies();

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobbyService.getLobbyById(lobbyId).getPlayers().size());

        verify(lobbyService, never()).removeLobby(anyString());
    }

    @Test
    void lobbyCleanupShouldNotFailIfOnePlayerCannotBeRemovedFromLobby() throws GameException {
        Player player = new Player("Player1");
        Player player2 = new Player("Player2");
        String lobbyId = "lob001";
        Lobby lobby = spy(new Lobby(lobbyId, player.getUniqueId()));
        doReturn(Instant.now().minus(cleanupThresholdHours + 1, ChronoUnit.HOURS)).when(lobby).getCreatedAt();
        doReturn(List.of(lobby)).when(lobbyService).getOpenLobbies();
        doReturn(lobby).when(lobbyService).getLobbyById(lobbyId);
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());
        gameService.createGameboard(lobbyId);

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobby.getPlayers().size());

        doThrow(new GameException("Test Exception")).when(lobbyService).removePlayerFromLobby(lobbyId, player.getUniqueId());

        cleanupService.cleanupOldLobbies();

        assertThrows(GameException.class, () -> gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(1, lobby.getPlayers().size());

        verify(lobbyService).getOpenLobbies();
        verify(lobbyService).removePlayerFromLobby(lobbyId, player.getUniqueId());
        verify(lobbyService).removePlayerFromLobby(lobbyId, player2.getUniqueId());
        verify(gameService).removeGameBoardForLobby(lobbyId);
        verify(lobbyService).removeLobby(lobbyId);
        verify(tradingService).removeAllOpenTradeRequestForLobbyId(lobbyId);

    }

    @Test
    void finishedGamesCleanupShouldNotRemoveNewLobby() throws GameException {
        Player player = new Player("Player1");
        Player player2 = new Player("Player2");
        String lobbyId = lobbyService.createLobby(player.getUniqueId());
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());
        gameService.createGameboard(lobbyId);

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobbyService.getLobbyById(lobbyId).getPlayers().size());

        cleanupService.cleanupFinishedGames();

        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
        assertEquals(2, lobbyService.getLobbyById(lobbyId).getPlayers().size());

        verify(lobbyService, never()).removeLobby(anyString());
    }

    @Test
    void testPlayerCleanup() {
        String connection1Id = UUID.randomUUID().toString();
        String connection2Id = UUID.randomUUID().toString();
        WebSocketConnection connection1 = mock(WebSocketConnection.class);
        doReturn(connection1Id).when(connection1).id();
        doReturn(true).when(connection1).isClosed();

        WebSocketConnection connection2 = mock(WebSocketConnection.class);
        doReturn(connection2Id).when(connection2).id();
        doReturn(false).when(connection2).isClosed();

        Player player = playerService.addPlayer(connection1);
        Player player2 = playerService.addPlayer(connection2);

        assertEquals(2, playerService.getAllPlayers().size());
        assertEquals(player, playerService.getPlayerById(player.getUniqueId()));
        assertEquals(player2, playerService.getPlayerById(player2.getUniqueId()));

        cleanupService.cleanupDisconnectedPlayers();

        assertEquals(1, playerService.getAllPlayers().size());
        assertNull(playerService.getPlayerById(player.getUniqueId()));
        assertEquals(player2, playerService.getPlayerById(player2.getUniqueId()));

        verify(playerService).removePlayerByConnectionId(connection1);
        verify(playerService, never()).removePlayerByConnectionId(connection2);
    }

    @Test
    void playerCleanupShouldWorkIfNoPlayerExists() {
        assertDoesNotThrow(() -> cleanupService.cleanupDisconnectedPlayers());
    }

    @Test
    void playerCleanupShouldWorkIfConnectionIsNull() {
        Player player = new Player("Player1");
        assertDoesNotThrow(() -> cleanupService.cleanupDisconnectedPlayer(player));
    }

}
