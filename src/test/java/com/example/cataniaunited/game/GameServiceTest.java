package com.example.cataniaunited.game;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.InvalidTurnException;
import com.example.cataniaunited.exception.ui.MissingRequiredStructuresException;
import com.example.cataniaunited.exception.ui.SetupLimitExceededException;
import com.example.cataniaunited.game.board.BuildingSite;
import com.example.cataniaunited.game.board.GameBoard;
import com.example.cataniaunited.game.board.LongestRoadCalculator;
import com.example.cataniaunited.game.board.Road;
import com.example.cataniaunited.game.board.ports.Port;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.game.buildings.Settlement;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import com.example.cataniaunited.player.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@QuarkusTest
class GameServiceTest {

    @InjectSpy
    GameService gameService;

    @InjectSpy
    LobbyService lobbyService;

    @InjectSpy
    PlayerService playerService;

    GameBoard gameboardMock;

    Lobby lobbyMock;


    @BeforeEach
    void init() {
        gameboardMock = mock(GameBoard.class);
        lobbyMock = mock(Lobby.class);

        when(lobbyMock.getLobbyId()).thenReturn("12345");
        when(lobbyMock.getPlayers()).thenReturn(Set.of("host", "p2"));
    }

    private void injectPlayer(Player player) {
        playerService.addPlayerWithoutConnection(player);
    }

    @Test
    void startGame_setsFlags() throws GameException {
        String hostId = "host";
        String lobbyId = lobbyService.createLobby(hostId);

        Player host = mock(Player.class);
        Player p2 = mock(Player.class);
        when(playerService.getPlayerById(hostId)).thenReturn(host);
        when(playerService.getPlayerById("p2")).thenReturn(p2);
        lobbyService.joinLobbyByCode(lobbyId, "p2");

        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.toggleReady(hostId);
        lobby.toggleReady("p2");
        assertFalse(lobby.isGameStarted());
        assertTrue(lobby.getPlayerOrder().isEmpty());
        assertNull(lobby.getActivePlayer());


        gameService.startGame(lobbyId, hostId);

        assertTrue(lobby.isGameStarted());
        assertFalse(lobby.getPlayerOrder().isEmpty());
        assertTrue(lobby.getPlayerOrder().containsAll(List.of(hostId, "p2")));
        assertNotNull(lobby.getActivePlayer());
        assertNotNull(gameService.getGameboardByLobbyId(lobbyId));
    }

    @Test
    void testSetAndGetLongestRoad() {
        GameBoard gameBoard = new GameBoard(2);
        String expectedPlayerId = "p1_the_winner";
        int expectedLength = 9;
        gameBoard.setLongestRoad(expectedPlayerId, expectedLength);
        assertEquals(expectedLength, gameBoard.getLongestRoadLength(), "The getter for road length should return the value that was set.");
        assertEquals(expectedPlayerId, gameBoard.getLongestRoadPlayerId(), "The getter for the player ID should return the value that was set.");
    }

    @Test
    void testPlaceRoad_CoversLongestRoadLogic() throws GameException {
        String lobbyId = "testLobby";
        String playerId = "p1";
        Player testPlayer = new Player(playerId, null);
        List<BuildingSite> sites = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sites.add(new BuildingSite(i));
        }
        List<Road> realPlayerRoads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Road road = new Road(sites.get(i), sites.get(i + 1), i);
            road.setOwner(testPlayer);
            sites.get(i).addRoad(road);
            sites.get(i + 1).addRoad(road);
            realPlayerRoads.add(road);
        }
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        when(gameboardMock.getRoadList()).thenReturn(realPlayerRoads);
        when(gameboardMock.getLongestRoadLength()).thenReturn(0);
        when(gameboardMock.getLongestRoadPlayerId()).thenReturn(null);
        doReturn(testPlayer).when(playerService).getPlayerById(playerId);
        doReturn(PlayerColor.RED).when(lobbyService).getPlayerColor(anyString(), anyString());
        doReturn(2).when(lobbyService).getRoundsPlayed(anyString());
        doNothing().when(lobbyService).checkPlayerTurn(anyString(), anyString());
        doNothing().when(lobbyService).addVictoryPoints(anyString(), anyString(), anyInt());
        gameService.placeRoad(lobbyId, playerId, 1);
        verify(lobbyService, times(1)).addVictoryPoints(lobbyId, playerId, 2);
        verify(gameboardMock, times(1)).setLongestRoad(playerId, 5);
    }


    @Test
    void whenLongestRoadIsTaken_shouldUpdateVPsAndBoard() throws GameException {
        String lobbyId = "testLobby";
        String playerId = "p1";
        Player testPlayer = new Player(playerId, null);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(testPlayer).when(playerService).getPlayerById(playerId);
        doReturn(PlayerColor.RED).when(lobbyService).getPlayerColor(anyString(), anyString());
        doReturn(2).when(lobbyService).getRoundsPlayed(anyString());
        doNothing().when(lobbyService).checkPlayerTurn(anyString(), anyString());
        doNothing().when(lobbyService).addVictoryPoints(anyString(), anyString(), anyInt());
        List<Road> realRoads = new ArrayList<>();
        List<BuildingSite> sites = new ArrayList<>();
        for (int i = 0; i < 6; i++) sites.add(new BuildingSite(i));
        for (int i = 0; i < 5; i++) {
            Road road = new Road(sites.get(i), sites.get(i + 1), i);
            road.setOwner(testPlayer);
            sites.get(i).addRoad(road);
            sites.get(i + 1).addRoad(road);
            realRoads.add(road);
        }

        when(gameboardMock.getRoadList()).thenReturn(realRoads);
        when(gameboardMock.getLongestRoadLength()).thenReturn(0);
        gameService.placeRoad(lobbyId, playerId, 1);
        verify(lobbyService).addVictoryPoints(lobbyId, playerId, 2);
        verify(gameboardMock).setLongestRoad(playerId, 5);
    }

    @Test
    void startGameShouldThrowExceptionIfGameIsAlreadyStarted() throws GameException {
        String playerId = "player";
        String lobbyId = lobbyService.createLobby(playerId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setGameStarted(true);
        lobby.addPlayer(playerId);
        lobby.addPlayer("Player2");
        GameException ge = assertThrows(GameException.class, () -> gameService.startGame(lobbyId, playerId));
        assertEquals("Starting of game failed", ge.getMessage());
    }

    @Test
    void startGameShouldThrowExceptionIfPlayerCountIsSmallerThanTwo() {
        String playerId = "player";
        String lobbyId = lobbyService.createLobby(playerId);
        GameException ge = assertThrows(GameException.class, () -> gameService.startGame(lobbyId, "Player1"));
        assertEquals("Starting of game failed", ge.getMessage());
    }

    @Test
    void startGameShouldThrowExceptionIfRequestingPlayerIsNotHost() throws GameException {
        String playerId = "player";
        String notHostPlayerId = "notHostPlayer";
        String lobbyId = lobbyService.createLobby(playerId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setGameStarted(false);
        lobby.addPlayer(playerId);
        lobby.addPlayer(notHostPlayerId);
        GameException ge = assertThrows(GameException.class, () -> gameService.startGame(lobbyId, notHostPlayerId));
        assertEquals("Starting of game failed", ge.getMessage());
    }

    @Test
    void testCreateGameBoard() throws GameException {
        doReturn(lobbyMock).when(lobbyService).getLobbyById(anyString());
        GameBoard gameBoard = gameService.createGameboard(lobbyMock.getLobbyId());
        assertNotNull(gameBoard);
        verify(lobbyService).getLobbyById(anyString());
        verify(gameService).addGameboardToList(lobbyMock.getLobbyId(), gameBoard);
    }

    @Test
    void createGameBoardShouldThrowGameException() {
        GameException ge = assertThrows(GameException.class, () -> gameService.createGameboard(lobbyMock.getLobbyId()));
        assertEquals("Lobby with id %s not found".formatted(lobbyMock.getLobbyId()), ge.getMessage());
    }

    @Test
    void getGameboardByLobbyIdShouldThrowGameExceptionForNonExistingLobby() {
        String invalidLobbyId = "invalidLobbyId";
        GameException ge = assertThrows(GameException.class, () -> gameService.getGameboardByLobbyId(invalidLobbyId));
        assertEquals("Gameboard for Lobby not found: id = %s".formatted(invalidLobbyId), ge.getMessage());
    }

    @Test
    void getGameBoardShouldReturnCorrectGameBoard() throws GameException {
        doReturn(lobbyMock).when(lobbyService).getLobbyById(anyString());
        GameBoard expectedGameBoard = gameService.createGameboard(lobbyMock.getLobbyId());
        assertNotNull(expectedGameBoard);

        GameBoard actualGameBoard = gameService.getGameboardByLobbyId(lobbyMock.getLobbyId());
        assertNotNull(actualGameBoard);
        assertEquals(expectedGameBoard, actualGameBoard);
        verify(lobbyService).getLobbyById(lobbyMock.getLobbyId());
        verify(gameService).addGameboardToList(lobbyMock.getLobbyId(), expectedGameBoard);
    }

    @Test
    void placeSettlementShouldThrowGameExceptionForNonExistingLobby() throws GameException {
        String invalidLobbyId = "invalidLobbyId";
        GameException ge = assertThrows(GameException.class, () -> gameService.placeSettlement(invalidLobbyId, "1", 1));
        assertEquals("Lobby with id %s not found".formatted(invalidLobbyId), ge.getMessage());
        verify(gameService, never()).getGameboardByLobbyId(invalidLobbyId);
    }

    @Test
    void placeRoadShouldThrowGameExceptionForNonExistingLobby() throws GameException {
        String invalidLobbyId = "invalidLobbyId";
        GameException ge = assertThrows(GameException.class, () -> gameService.placeRoad(invalidLobbyId, "1", 1));
        assertEquals("Lobby with id %s not found".formatted(invalidLobbyId), ge.getMessage());
        verify(gameService, never()).getGameboardByLobbyId(invalidLobbyId);
    }

    @Test
    void placeSettlementShouldThrowGameExceptionForNotPlayerTurn() throws GameException {
        String playerId = "playerId1";
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(false).when(lobbyMock).isPlayerTurn(playerId);
        InvalidTurnException ite = assertThrows(InvalidTurnException.class, () -> gameService.placeSettlement(lobbyId, playerId, 1));
        assertEquals("It is not your turn!", ite.getMessage());
        verify(gameService, never()).getGameboardByLobbyId(lobbyId);
    }

    @Test
    void placeSettlementShouldThrowExceptionIfSetupLimitIsExceeded() throws GameException {
        Player player = new Player("player1");
        String playerId = player.getUniqueId();
        int settlementPositionId = 15;
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(0).when(lobbyMock).getRoundsPlayed();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(2L).when(gameboardMock).getPlayerStructureCount(playerId, Settlement.class);
        doReturn(player).when(playerService).getPlayerById(playerId);
        assertThrows(SetupLimitExceededException.class, () -> gameService.placeSettlement(lobbyId, playerId, settlementPositionId));

        verify(gameboardMock).getPlayerStructureCount(playerId, Settlement.class);
    }

    @Test
    void upgradeSettlementShouldThrowExceptionIfCalledDuringSetupRound() throws GameException {
        Player player = new Player("player1");
        String playerId = player.getUniqueId();
        int settlementPositionId = 15;
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(0).when(lobbyMock).getRoundsPlayed();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(2L).when(gameboardMock).getPlayerStructureCount(playerId, Settlement.class);
        doReturn(player).when(playerService).getPlayerById(playerId);
        GameException ge = assertThrows(GameException.class, () -> gameService.upgradeSettlement(lobbyId, playerId, settlementPositionId));

        assertEquals("Settlements cannot be upgraded during setup round", ge.getMessage());
        verify(gameService, never()).getGameboardByLobbyId(lobbyId);
    }

    @Test
    void placeRoadShouldThrowGameExceptionForNotPlayerTurn() throws GameException {
        String playerId = "playerId1";
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(false).when(lobbyMock).isPlayerTurn(playerId);
        InvalidTurnException ite = assertThrows(InvalidTurnException.class, () -> gameService.placeRoad(lobbyId, playerId, 1));
        assertEquals("It is not your turn!", ite.getMessage());
        verify(gameService, never()).getGameboardByLobbyId(lobbyId);
    }

    @Test
    void placeRoadShouldThrowExceptionIfSetupLimitIsExceeded() throws GameException {
        Player player = new Player("player1");
        String playerId = player.getUniqueId();
        int roadId = 15;
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(0).when(lobbyMock).getRoundsPlayed();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(2L).when(gameboardMock).getPlayerStructureCount(playerId, Road.class);
        doReturn(player).when(playerService).getPlayerById(playerId);
        assertThrows(SetupLimitExceededException.class, () -> gameService.placeRoad(lobbyId, playerId, roadId));

        verify(gameboardMock).getPlayerStructureCount(playerId, Road.class);
    }

    @Test
    void setSettlementShouldCallPlaceSettlementOnGameboard() throws GameException {
        String playerId = "playerId1";
        Player player = mock(Player.class);
        int settlementPositionId = 15;
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(Set.of(playerId)).when(lobbyMock).getPlayers();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(player).when(playerService).getPlayerById(playerId);
        gameService.placeSettlement(lobbyId, playerId, settlementPositionId);
        verify(gameboardMock).placeSettlement(any(BuildRequest.class));
    }

    @Test
    void placeSettlementShouldAddVictoryPointForPlayer() throws GameException {
        String playerId = "player1";
        Player player = mock(Player.class);
        int settlementPositionId = 5;
        String lobbyId = lobbyMock.getLobbyId();

        doReturn(Set.of(playerId)).when(lobbyMock).getPlayers();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(player).when(playerService).getPlayerById(playerId);

        gameService.addGameboardToList(lobbyId, gameboardMock);
        doNothing().when(gameboardMock).placeSettlement(any(BuildRequest.class));
        gameService.placeSettlement(lobbyId, playerId, settlementPositionId);

        verify(gameboardMock).placeSettlement(any(BuildRequest.class));
        verify(lobbyService).addVictoryPoints(lobbyId, playerId, 1);
    }

    @Test
    void setRoadShouldCallPlaceRoadOnGameboard() throws GameException {
        Player player = new Player("player1");
        String playerId = player.getUniqueId();
        int settlementPositionId = 15;
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(playerId);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(player).when(playerService).getPlayerById(playerId);
        gameService.placeRoad(lobbyId, playerId, settlementPositionId);
        verify(gameboardMock).placeRoad(any(BuildRequest.class));
    }

    @Test
    void placeSettlementAndRoadShouldIgnoreResourcesForFirstTwoRounds() throws GameException {
        int roadId = 1;
        int settlementPositionId = 1;
        Player player = new Player("player1");
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(Set.of(player.getUniqueId())).when(lobbyMock).getPlayers();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(player.getUniqueId());
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(player.getUniqueId());
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(player).when(playerService).getPlayerById(player.getUniqueId());

        doReturn(1).when(lobbyMock).getRoundsPlayed();

        gameService.placeRoad(lobbyId, player.getUniqueId(), roadId);
        gameService.placeSettlement(lobbyId, player.getUniqueId(), settlementPositionId);

        ArgumentCaptor<BuildRequest> roadBuildRequestCaptor = ArgumentCaptor.forClass(BuildRequest.class);
        verify(gameboardMock).placeRoad(roadBuildRequestCaptor.capture());
        assertEquals(player, roadBuildRequestCaptor.getValue().player());
        assertEquals(PlayerColor.BLUE, roadBuildRequestCaptor.getValue().color());
        assertEquals(roadId, roadBuildRequestCaptor.getValue().positionId());
        assertTrue(roadBuildRequestCaptor.getValue().isSetupRound());

        ArgumentCaptor<BuildRequest> settlementBuildRequestCaptor = ArgumentCaptor.forClass(BuildRequest.class);
        verify(gameboardMock).placeSettlement(settlementBuildRequestCaptor.capture());
        assertEquals(player, settlementBuildRequestCaptor.getValue().player());
        assertEquals(PlayerColor.BLUE, settlementBuildRequestCaptor.getValue().color());
        assertEquals(settlementPositionId, settlementBuildRequestCaptor.getValue().positionId());
        assertTrue(settlementBuildRequestCaptor.getValue().isSetupRound());
    }

    @Test
    void placeSettlementAndRoadShouldCheckResourcesAfterSecondRound() throws GameException {
        int roadId = 1;
        int settlementPositionId = 1;
        Player player = new Player("player1");
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(Set.of(player.getUniqueId())).when(lobbyMock).getPlayers();
        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(player.getUniqueId());
        doReturn(PlayerColor.BLUE).when(lobbyMock).getPlayerColor(player.getUniqueId());
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(player).when(playerService).getPlayerById(player.getUniqueId());

        doReturn(2).when(lobbyMock).getRoundsPlayed();

        gameService.placeRoad(lobbyId, player.getUniqueId(), roadId);
        gameService.placeSettlement(lobbyId, player.getUniqueId(), settlementPositionId);

        ArgumentCaptor<BuildRequest> roadBuildRequestCaptor = ArgumentCaptor.forClass(BuildRequest.class);
        verify(gameboardMock).placeRoad(roadBuildRequestCaptor.capture());
        assertEquals(player, roadBuildRequestCaptor.getValue().player());
        assertEquals(PlayerColor.BLUE, roadBuildRequestCaptor.getValue().color());
        assertEquals(roadId, roadBuildRequestCaptor.getValue().positionId());
        assertFalse(roadBuildRequestCaptor.getValue().isSetupRound());

        ArgumentCaptor<BuildRequest> settlementBuildRequestCaptor = ArgumentCaptor.forClass(BuildRequest.class);
        verify(gameboardMock).placeSettlement(settlementBuildRequestCaptor.capture());
        assertEquals(player, settlementBuildRequestCaptor.getValue().player());
        assertEquals(PlayerColor.BLUE, settlementBuildRequestCaptor.getValue().color());
        assertEquals(settlementPositionId, settlementBuildRequestCaptor.getValue().positionId());
        assertFalse(settlementBuildRequestCaptor.getValue().isSetupRound());
    }

    @Test
    void placeRoadShouldDecrementVictoryPointsIfPlayerDoesNotHaveLongestRoadAnymore() throws GameException {
        Player player = new Player("player1");
        Player oldLongestRoadPlayer = new Player("oldLongestRoadPlayer");
        String playerId = player.getUniqueId();
        int settlementPositionId = 15;
        int newLongestRoadLength = 7;
        LongestRoadCalculator longestRoadCalculatorMock = mock(LongestRoadCalculator.class);

        String lobbyId = lobbyService.createLobby(playerId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobbyService.joinLobbyByCode(lobbyId, playerId);
        lobbyService.joinLobbyByCode(lobbyId, oldLongestRoadPlayer.getUniqueId());
        lobby.addVictoryPoints(oldLongestRoadPlayer.getUniqueId(), 2);
        lobby.setActivePlayer(playerId);

        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        doReturn(longestRoadCalculatorMock).when(gameService).getLongestRoadCalculator();
        doReturn(player).when(playerService).getPlayerById(playerId);
        doReturn(oldLongestRoadPlayer).when(playerService).getPlayerById(oldLongestRoadPlayer.getUniqueId());
        doReturn(newLongestRoadLength).when(longestRoadCalculatorMock).calculateFor(anyList());
        doReturn(newLongestRoadLength - 1).when(gameboardMock).getLongestRoadLength();
        doReturn(oldLongestRoadPlayer.getUniqueId()).when(gameboardMock).getLongestRoadPlayerId();

        assertEquals(0, lobby.getVictoryPoints(playerId));
        assertEquals(2, lobby.getVictoryPoints(oldLongestRoadPlayer.getUniqueId()));

        gameService.placeRoad(lobbyId, playerId, settlementPositionId);

        assertEquals(2, lobby.getVictoryPoints(playerId));
        assertEquals(0, lobby.getVictoryPoints(oldLongestRoadPlayer.getUniqueId()));

        verify(gameboardMock).setLongestRoad(playerId, newLongestRoadLength);
        verify(lobbyService).addVictoryPoints(lobbyId, oldLongestRoadPlayer.getUniqueId(), -2);
        verify(lobbyService).addVictoryPoints(lobbyId, playerId, 2);
    }


    @Test
    void testGetJsonByValidLobbyId() throws GameException {
        String lobbyId = lobbyMock.getLobbyId();
        ObjectNode expectedJson = new ObjectMapper().createObjectNode().put("test", "data"); // Create a dummy JSON node
        when(gameboardMock.getJson()).thenReturn(expectedJson);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);
        ObjectNode actualJson = gameService.getGameboardJsonByLobbyId(lobbyId);
        verify(gameService).getGameboardByLobbyId(lobbyId);
        verify(gameboardMock).getJson();
        assertNotNull(actualJson);
        assertSame(expectedJson, actualJson, "The returned JSON should be the one from the GameBoard");
    }

    @Test
    void testGetJsonByInvalidLobbyId() throws GameException {
        String invalidLobbyId = "nonExistentLobby";
        String expectedErrorMessage = "Gameboard for Lobby not found: id = %s".formatted(invalidLobbyId);
        doThrow(new GameException(expectedErrorMessage))
                .when(gameService).getGameboardByLobbyId(invalidLobbyId);
        GameException exception = assertThrows(GameException.class, () -> gameService.getGameboardJsonByLobbyId(invalidLobbyId), "Should throw GameException when gameboard is not found");
        assertEquals(expectedErrorMessage, exception.getMessage());
        verify(gameService).getGameboardByLobbyId(invalidLobbyId);
    }

    @Test
    void placeSettlementWhenPositionHasPortShouldAddPortToPlayer() throws GameException {
        String playerId = "playerWithPort";
        Player mockPlayer = mock(Player.class);
        int settlementPositionId = 10;
        String lobbyId = lobbyMock.getLobbyId();
        Port mockPort = mock(Port.class);

        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.RED).when(lobbyMock).getPlayerColor(playerId);
        doReturn(mockPlayer).when(playerService).getPlayerById(playerId);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);

        when(gameboardMock.getPortOfBuildingSite(settlementPositionId)).thenReturn(mockPort);
        doNothing().when(gameboardMock).placeSettlement(any(BuildRequest.class));
        doNothing().when(lobbyService).addVictoryPoints(anyString(), anyString(), anyInt());


        gameService.placeSettlement(lobbyId, playerId, settlementPositionId);

        verify(gameboardMock).placeSettlement(any(BuildRequest.class));
        verify(gameboardMock).getPortOfBuildingSite(settlementPositionId);
        verify(mockPlayer).addPort(mockPort);
        verify(lobbyService).addVictoryPoints(lobbyId, playerId, 1);
    }

    @Test
    void placeSettlementWhenPositionHasNoPortShouldNotAddPortToPlayer() throws GameException {
        String playerId = "playerWithoutPort";
        Player mockPlayer = mock(Player.class);
        int settlementPositionId = 12;
        String lobbyId = lobbyMock.getLobbyId();

        doReturn(lobbyMock).when(lobbyService).getLobbyById(lobbyId);
        doReturn(true).when(lobbyMock).isPlayerTurn(playerId);
        doReturn(PlayerColor.GREEN).when(lobbyMock).getPlayerColor(playerId);
        doReturn(mockPlayer).when(playerService).getPlayerById(playerId);
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);

        when(gameboardMock.getPortOfBuildingSite(settlementPositionId)).thenReturn(null);
        doNothing().when(gameboardMock).placeSettlement(any(BuildRequest.class));
        doNothing().when(lobbyService).addVictoryPoints(anyString(), anyString(), anyInt());

        gameService.placeSettlement(lobbyId, playerId, settlementPositionId);

        verify(gameboardMock).placeSettlement(any(BuildRequest.class));
        verify(gameboardMock).getPortOfBuildingSite(settlementPositionId);
        verify(mockPlayer, never()).addPort(any(Port.class));
        verify(lobbyService).addVictoryPoints(lobbyId, playerId, 1);
    }

    @Test
    void checkRequiredPlayerStructuresShouldThrowExceptionIfPlayerDidNotBuildEnoughRoads() throws GameException {
        String playerId = "host";
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);

        //First round
        doReturn(0L).when(gameboardMock).getPlayerStructureCount(playerId, Road.class);
        assertThrows(MissingRequiredStructuresException.class, () ->
                gameService.checkRequiredPlayerStructures(lobbyMock.getLobbyId(), playerId, 0)
        );

        //Second round
        doReturn(1L).when(gameboardMock).getPlayerStructureCount(playerId, Road.class);
        assertThrows(MissingRequiredStructuresException.class, () ->
                gameService.checkRequiredPlayerStructures(lobbyMock.getLobbyId(), playerId, 1)
        );

        verify(gameboardMock, times(2)).getPlayerStructureCount(playerId, Road.class);
        verify(gameboardMock, never()).getPlayerStructureCount(playerId, Settlement.class);
    }

    @Test
    void checkRequiredPlayerStructuresShouldThrowExceptionIfPlayerDidNotBuildEnoughSettlements() throws GameException {
        String playerId = "host";
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);

        //First round
        doReturn(1L).when(gameboardMock).getPlayerStructureCount(playerId, Road.class);
        doReturn(0L).when(gameboardMock).getPlayerStructureCount(playerId, Settlement.class);

        assertThrows(MissingRequiredStructuresException.class, () ->
                gameService.checkRequiredPlayerStructures(lobbyMock.getLobbyId(), playerId, 0)
        );

        //Second round
        doReturn(2L).when(gameboardMock).getPlayerStructureCount(playerId, Road.class);
        doReturn(1L).when(gameboardMock).getPlayerStructureCount(playerId, Settlement.class);

        assertThrows(MissingRequiredStructuresException.class, () ->
                gameService.checkRequiredPlayerStructures(lobbyMock.getLobbyId(), playerId, 1)
        );

        verify(gameboardMock, times(2)).getPlayerStructureCount(playerId, Road.class);
        verify(gameboardMock, times(2)).getPlayerStructureCount(playerId, Settlement.class);
    }

    @Test
    void checkRequiredPlayerStructuresShouldSkipCheckAfterSecondRound() throws GameException {
        String playerId = "host";
        String lobbyId = lobbyMock.getLobbyId();
        doReturn(gameboardMock).when(gameService).getGameboardByLobbyId(lobbyId);

        assertDoesNotThrow(() -> gameService.checkRequiredPlayerStructures(lobbyMock.getLobbyId(), playerId, 2));

        verify(gameboardMock, never()).getPlayerStructureCount(playerId, Road.class);
        verify(gameboardMock, never()).getPlayerStructureCount(playerId, Settlement.class);
    }

    @Test
    void handleCheat_shouldThrowIfNoPlayerHasThatResource() throws GameException {
        String cheaterId = "cheater";
        String victimId = "victim";
        String lobbyId = lobbyService.createLobby(cheaterId);
        lobbyService.joinLobbyByCode(lobbyId, victimId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.startGame();

        Player cheater = mock(Player.class);
        Player victim = mock(Player.class);
        when(playerService.getPlayerById(cheaterId)).thenReturn(cheater);
        when(playerService.getPlayerById(victimId)).thenReturn(victim);
        when(victim.getResourceCount(TileType.ORE)).thenReturn(0);

        GameException ex = assertThrows(GameException.class,
                () -> gameService.handleCheat(lobbyId, cheaterId, TileType.ORE));
        assertEquals("No player has that resource to steal.", ex.getMessage());
    }

    @Test
    void handleCheat_shouldThrowIfCheatLimitReached() throws GameException {
        String cheaterId = "cheater";
        String victimId = "victim";
        String lobbyId = lobbyService.createLobby(cheaterId);
        lobbyService.joinLobbyByCode(lobbyId, victimId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.startGame();
        lobby.recordCheat(cheaterId);
        lobby.recordCheat(cheaterId);

        Player cheater = mock(Player.class);
        Player victim = mock(Player.class);
        when(playerService.getPlayerById(cheaterId)).thenReturn(cheater);
        when(playerService.getPlayerById(victimId)).thenReturn(victim);
        when(victim.getResourceCount(TileType.WHEAT)).thenReturn(2);

        GameException ex = assertThrows(GameException.class,
                () -> gameService.handleCheat(lobbyId, cheaterId, TileType.WHEAT));
        assertEquals("You already cheated twice!", ex.getMessage());
    }

    @Test
    void handleCheat_shouldTransferResourceToCheaterAndIncrementCount() throws GameException {
        String cheaterId = "cheater";
        String victimId = "victim";
        String lobbyId = lobbyService.createLobby(cheaterId);
        lobbyService.joinLobbyByCode(lobbyId, victimId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.startGame();

        Player cheater = mock(Player.class);
        Player victim = mock(Player.class);
        when(playerService.getPlayerById(cheaterId)).thenReturn(cheater);
        when(playerService.getPlayerById(victimId)).thenReturn(victim);
        when(victim.getResourceCount(TileType.WOOD)).thenReturn(3);

        gameService.handleCheat(lobbyId, cheaterId, TileType.WOOD);

        verify(victim).removeResource(TileType.WOOD, 1);
        verify(cheater).receiveResource(TileType.WOOD, 1);
        assertEquals(1, lobby.getCheatCount(cheaterId));
    }


    @Test
    void handleReportPlayer_shouldReturnCorrectReportNewAndRemoveHalfResources() throws GameException {
        Player reporter = new Player();
        Player reported = new Player();

        String reporterId = reporter.getUniqueId();
        String reportedId = reported.getUniqueId();

        String lobbyId = lobbyService.createLobby(reporterId);
        lobbyService.joinLobbyByCode(lobbyId, reportedId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);

        lobby.recordCheat(reportedId);

        reported.receiveResource(TileType.WOOD, 2);
        reported.receiveResource(TileType.CLAY, 2);

        playerService.clearAllPlayersForTesting();
        injectPlayer(reporter);
        injectPlayer(reported);

        ReportOutcome outcome = gameService.handleReportPlayer(lobbyId, reporterId, reportedId);

        assertEquals(ReportOutcome.CORRECT_REPORT_NEW, outcome);
        int remaining = reported.getResources().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(2, remaining);
    }


    @Test
    void handleReportPlayer_shouldReturnCorrectReportAlreadyCaughtAndPunishReporter() throws GameException {
        Player reporter = new Player();
        Player reported = new Player();

        String reporterId = reporter.getUniqueId();
        String reportedId = reported.getUniqueId();

        String lobbyId = lobbyService.createLobby(reporterId);
        lobbyService.joinLobbyByCode(lobbyId, reportedId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);

        lobby.recordCheat(reportedId);
        lobby.markCheaterAsCaught(reportedId);

        reporter.receiveResource(TileType.ORE, 1);

        playerService.clearAllPlayersForTesting();
        injectPlayer(reporter);
        injectPlayer(reported);

        ReportOutcome outcome = gameService.handleReportPlayer(lobbyId, reporterId, reportedId);

        assertEquals(ReportOutcome.CORRECT_REPORT_ALREADY_CAUGHT, outcome);
        assertEquals(0, reporter.getResourceCount(TileType.ORE));
    }

    @Test
    void handleReportPlayer_shouldReturnFalseReportAndPunishReporter() throws GameException {
        Player reporter = new Player();
        Player reported = new Player();

        String reporterId = reporter.getUniqueId();
        String reportedId = reported.getUniqueId();

        String lobbyId = lobbyService.createLobby(reporterId);
        lobbyService.joinLobbyByCode(lobbyId, reportedId);

        reporter.receiveResource(TileType.WHEAT, 1);

        playerService.clearAllPlayersForTesting();
        injectPlayer(reporter);
        injectPlayer(reported);

        ReportOutcome outcome = gameService.handleReportPlayer(lobbyId, reporterId, reportedId);

        assertEquals(ReportOutcome.FALSE_REPORT, outcome);
        assertEquals(0, reporter.getResourceCount(TileType.WHEAT));
    }


}
