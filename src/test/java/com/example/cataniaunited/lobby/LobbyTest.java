package com.example.cataniaunited.lobby;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.player.PlayerColor;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LobbyTest {

    Lobby testLobby;

    @BeforeEach
    void setUpExtraLobby() {
        testLobby = new Lobby("L-extra", "host");
        testLobby.addPlayer("p2");
        testLobby.addPlayer("p3");
    }

    @Test
    void getLobbyId_shouldReturnCorrectId() {
        String expectedLobbyId = "1";
        String hostPlayer = "Player 1";
        Lobby lobby = new Lobby(expectedLobbyId, hostPlayer);

        String actualLobbyId = lobby.getLobbyId();

        assertEquals(expectedLobbyId, actualLobbyId);
    }

    @Test
    void getPlayers_shouldReturnHostPlayer() {
        String lobbyId = "1";
        String hostPlayer = "Player 1";
        Lobby lobby = new Lobby(lobbyId, hostPlayer);

        Set<String> players = lobby.getPlayers();

        assertEquals(1, players.size());
        assertTrue(players.contains(hostPlayer));
    }

    @Test
    void testRestoreColor_shouldOnlyAddColorIfNotPresent() {
        Lobby lobby = new Lobby("555xyz", "HostPlayer");

        PlayerColor color = lobby.assignAvailableColor();
        assertNotNull(color);

        lobby.restoreColor(color);

        int countBefore = (int) lobby.getAvailableColors().stream().filter(c -> c == color).count();
        lobby.restoreColor(color);
        int countAfter = (int) lobby.getAvailableColors().stream().filter(c -> c == color).count();

        assertEquals(1, countAfter);
        assertEquals(countBefore, countAfter);
    }

    @Test
    void isPlayerTurnShouldReturnTrueForPlayerTurn() {
        String playerId = "player1";
        Lobby lobby = new Lobby("555xyz", playerId);
        lobby.setActivePlayer(playerId);

        assertTrue(lobby.isPlayerTurn(playerId));
    }

    @Test
    void isPlayerTurnShouldReturnFalseForNotPlayerTurn() {
        String playerId = "player1";
        Lobby lobby = new Lobby("555xyz", playerId);
        lobby.setActivePlayer("anotherPlayer");

        assertFalse(lobby.isPlayerTurn(playerId));
    }

    @Test
    void isPlayerTurnShouldReturnFalseIfPlayerIsNull() {
        Lobby lobby = new Lobby("555xyz", "player1");
        lobby.setActivePlayer("player1");

        assertFalse(lobby.isPlayerTurn(null));
    }

    @Test
    void getActivePlayerShouldReturnPlayer() {
        String playerId = "player1";
        Lobby lobby = new Lobby("555xyz", playerId);
        lobby.setActivePlayer(playerId);

        assertEquals(playerId, lobby.getActivePlayer());
    }

    @Test
    void nextPlayerShouldThrowExceptionIfPlayerOrderIsEmpty() {
        testLobby.setPlayerOrder(List.of());
        GameException ge = assertThrows(GameException.class, () -> testLobby.nextPlayerTurn());
        assertEquals("Executing next turn failed", ge.getMessage());
    }

    @Test
    void nextPlayerShouldThrowExceptionIfActivePlayerIsNull() {
        testLobby.setActivePlayer(null);
        GameException ge = assertThrows(GameException.class, () -> testLobby.nextPlayerTurn());
        assertEquals("Executing next turn failed", ge.getMessage());
    }

    @Test
    void testNextPlayerTurnForThreeOrMorePlayers() throws GameException {
        List<String> playerOrder = List.of("p2", "host", "p3");
        testLobby.setPlayerOrder(playerOrder);
        testLobby.setActivePlayer("p2");
        assertEquals(0, testLobby.getRoundsPlayed());

        //First round in order
        testLobby.nextPlayerTurn();
        assertEquals(0, testLobby.getRoundsPlayed());
        assertEquals("host", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(0, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        //Second round in reverse order
        testLobby.nextPlayerTurn();
        assertEquals(1, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(1, testLobby.getRoundsPlayed());
        assertEquals("host", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(1, testLobby.getRoundsPlayed());
        assertEquals("p2", testLobby.getActivePlayer());

        //Third and subsequent rounds in order again
        testLobby.nextPlayerTurn();
        assertEquals(2, testLobby.getRoundsPlayed());
        assertEquals("host", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(2, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(3, testLobby.getRoundsPlayed());
        assertEquals("p2", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(3, testLobby.getRoundsPlayed());
        assertEquals("host", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(3, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(4, testLobby.getRoundsPlayed());
        assertEquals("p2", testLobby.getActivePlayer());
    }

    @Test
    void testNextPlayerTurnForTwoPlayers() throws GameException {
        List<String> playerOrder = List.of("p2", "p3");
        testLobby.setPlayerOrder(playerOrder);
        testLobby.setActivePlayer("p2");
        assertEquals(0, testLobby.getRoundsPlayed());

        //First round in order
        testLobby.nextPlayerTurn();
        assertEquals(0, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        //Second round reversed
        testLobby.nextPlayerTurn();
        assertEquals(1, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(1, testLobby.getRoundsPlayed());
        assertEquals("p2", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(2, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals("p2", testLobby.getActivePlayer());
        assertEquals(3, testLobby.getRoundsPlayed());

        testLobby.nextPlayerTurn();
        assertEquals(3, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(4, testLobby.getRoundsPlayed());
        assertEquals("p2", testLobby.getActivePlayer());

        testLobby.nextPlayerTurn();
        assertEquals(4, testLobby.getRoundsPlayed());
        assertEquals("p3", testLobby.getActivePlayer());

    }

    @Test
    void startGameChangesOrderButKeepsSameElements() {
        assertTrue(testLobby.getPlayerOrder().isEmpty());
        assertEquals(3, testLobby.getPlayers().size());
        testLobby.startGame();
        assertEquals(testLobby.getPlayers().size(), testLobby.getPlayerOrder().size());
        assertTrue(testLobby.getPlayerOrder().containsAll(testLobby.getPlayers()));
    }

    @Test
    void canStartGame_requiresTwoPlayersAndGameNotYetStarted() {
        // host can start when ≥2 players
        testLobby.toggleReady("host");
        testLobby.toggleReady("p2");
        testLobby.toggleReady("p3");
        assertTrue(testLobby.canStartGame("host"));

        // once started, cannot start again
        testLobby.setGameStarted(true);
        assertFalse(testLobby.canStartGame("host"));
    }

    @Test
    void canStartGame_doesRequireTwoPlayers() throws GameException {
        testLobby.removePlayer("p2");
        testLobby.removePlayer("p3");
        testLobby.toggleReady("host");

        assertFalse(testLobby.canStartGame("host"));
    }

    @Test
    void canStartGame_returnsFalseForNonHostEvenWithEnoughPlayers() {
        assertFalse(testLobby.canStartGame("p2"), "only the host may start the game");
        assertFalse(testLobby.canStartGame("p3"), "only the host may start the game");
    }

    @Test
    void testResetForNewGame_resetsActivePlayerAndStartedFlag() {
        testLobby.setActivePlayer("p2");
        testLobby.setGameStarted(true);

        testLobby.resetForNewGame();

        assertFalse(testLobby.isGameStarted(), "gameStarted should be reset to false");
        assertNull(testLobby.getActivePlayer(), "activePlayer should be reset to null");
    }

    @Test
    void testAssignAvailableColor_removesColorAndRestoreAddsBack() {
        Lobby colorLobby = new Lobby("L-color", "host");
        int before = colorLobby.getAvailableColors().size();

        PlayerColor picked = colorLobby.assignAvailableColor();
        assertNotNull(picked);
        assertEquals(before - 1, colorLobby.getAvailableColors().size());

        colorLobby.restoreColor(picked);
        assertTrue(colorLobby.getAvailableColors().contains(picked));
        assertEquals(before, colorLobby.getAvailableColors().size());
    }

    @Test
    void testSetGameStarted_flagToggles() {
        Lobby fLobby = new Lobby("L-flag", "host");
        assertFalse(fLobby.isGameStarted(), "should start false");

        fLobby.setGameStarted(true);
        assertTrue(fLobby.isGameStarted(), "should now be true");

        fLobby.setGameStarted(false);
        assertFalse(fLobby.isGameStarted(), "can turn back off");
    }

    @Test
    void assignAvailableColor_exhaustsToNullThenRestores() {
        Lobby colorLobby = new Lobby("L-col", "host");

        int totalColors = colorLobby.getAvailableColors().size();
        for (int i = 0; i < totalColors; i++) {
            assertNotNull(colorLobby.assignAvailableColor(), "should still have colors");
        }
        // now exhausted
        assertNull(colorLobby.assignAvailableColor(), "no colors left → should be null");

        // put one back
        PlayerColor comeback = PlayerColor.RED;
        colorLobby.restoreColor(comeback);

        PlayerColor got = colorLobby.assignAvailableColor();
        assertEquals(comeback, got, "restored color must come back immediately");
    }

    @Test
    void recordCheat_shouldIncrementCheatCountForPlayer() {
        Lobby lobby = new Lobby("cheatLobby", "cheater");
        String player = "cheater";

        assertEquals(0, lobby.getCheatCount(player));
        assertTrue(lobby.getCheatCounts().isEmpty());

        lobby.recordCheat(player);
        assertEquals(1, lobby.getCheatCount(player));
        assertEquals(1, lobby.getCheatCounts().size());

        lobby.recordCheat(player);
        assertEquals(2, lobby.getCheatCount(player));
        assertEquals(1, lobby.getCheatCounts().size());
    }

    @Test
    void getCheatCounts_shouldReturnUnmodifiableMapWithCorrectValues() {
        Lobby lobby = new Lobby("cheatLobby", "host");
        String player1 = "host";
        String player2 = "p2";

        lobby.addPlayer(player2);

        lobby.recordCheat(player1);
        lobby.recordCheat(player1);
        lobby.recordCheat(player2);

        Map<String, Integer> cheats = lobby.getCheatCounts();
        assertEquals(2, cheats.get(player1));
        assertEquals(1, cheats.get(player2));
        assertEquals(2, cheats.size());
    }

    @Test
    void getCheatCount_returnsZeroForUnknownPlayer() {
        Lobby lobby = new Lobby("cheatLobby", "host");
        assertEquals(0, lobby.getCheatCount("unknown"));
    }

    @Test
    void recordReport_shouldIncrementReportCountAndAddReportRecord() {
        Lobby lobby = new Lobby("reportLobby", "host");
        String reporter = "host";
        String reported = "p2";
        lobby.addPlayer(reported);
        assertEquals(0, lobby.getReportCount(reporter));
        assertTrue(lobby.getReportCounts().isEmpty());
        assertTrue(lobby.getReportRecords().isEmpty());

        lobby.recordReport(reporter, reported);
        assertEquals(1, lobby.getReportCount(reporter));
        assertEquals(1, lobby.getReportCounts().size());

        List<ReportRecord> records = lobby.getReportRecords();
        assertEquals(1, records.size());
        assertEquals(reporter, records.get(0).reporterId());
        assertEquals(reported, records.get(0).reportedId());

        String reported2 = "p3";
        lobby.addPlayer(reported2);
        lobby.recordReport(reporter, reported2);
        assertEquals(2, lobby.getReportCount(reporter));
        assertEquals(1, lobby.getReportCounts().size());

        records = lobby.getReportRecords();
        assertEquals(2, records.size());
        assertEquals(reported2, records.get(1).reportedId());
    }

    @Test
    void getReportCounts_shouldReturnUnmodifiableMapWithCorrectValues() {
        Lobby lobby = new Lobby("reportMap", "host");
        lobby.addPlayer("p2");
        assertTrue(lobby.getReportCounts().isEmpty());

        lobby.recordReport("host", "p2");
        lobby.recordReport("host", "p2");
        lobby.recordReport("p2", "host");

        Map<String, Integer> reportCounts = lobby.getReportCounts();
        assertEquals(2, reportCounts.get("host"));
        assertEquals(1, reportCounts.get("p2"));
        assertEquals(2, reportCounts.size());
    }

    @Test
    void getReportRecords_shouldReturnUnmodifiableListWithCorrectValues() {
        Lobby lobby = new Lobby("reportRecords", "host");
        lobby.addPlayer("p2");
        lobby.recordReport("host", "p2");
        lobby.recordReport("host", "p2");
        lobby.recordReport("p2", "host");

        List<ReportRecord> records = lobby.getReportRecords();
        assertEquals(3, records.size());
        assertEquals("host", records.get(0).reporterId());
        assertEquals("p2", records.get(0).reportedId());
        assertEquals("host", records.get(1).reporterId());
        assertEquals("p2", records.get(1).reportedId());
        assertEquals("p2", records.get(2).reporterId());
        assertEquals("host", records.get(2).reportedId());
    }

    @Test
    void getReportCount_returnsZeroForUnknownPlayer() {
        Lobby lobby = new Lobby("reportLobby", "host");
        assertEquals(0, lobby.getReportCount("unknown"));
    }

    @Test
    void isCheaterAlreadyCaught_shouldReturnFalseWhenCheaterIsActive() {
        Lobby lobby = new Lobby("cheaterTest", "host");
        String playerId = "player1";

        lobby.recordCheat(playerId);
        assertFalse(lobby.isCheaterAlreadyCaught(playerId));
    }

    @Test
    void markCheaterAsCaught_shouldRemovePlayerFromActiveCheaters() {
        Lobby lobby = new Lobby("cheaterTest", "host");
        String playerId = "player1";

        lobby.recordCheat(playerId);
        assertFalse(lobby.isCheaterAlreadyCaught(playerId));

        lobby.markCheaterAsCaught(playerId);
        assertTrue(lobby.isCheaterAlreadyCaught(playerId));
    }

    @Test
    void resetVictoryPointsShouldNotFailOnNonExistingPlayer() {
        assertDoesNotThrow(() -> testLobby.resetVictoryPoints("non-existent-player-id-123"));
    }

    @Test
    void resetVictoryPointsShouldSetVictoryPointsOfPlayerToZero() {
        String playerId = "TestPlayer";
        int currentVictoryPoints = testLobby.getVictoryPoints(playerId);
        testLobby.addVictoryPoints(playerId, 5);
        assertEquals(currentVictoryPoints + 5, testLobby.getVictoryPoints(playerId));
        testLobby.resetVictoryPoints(playerId);
        assertEquals(0, testLobby.getVictoryPoints(playerId));
    }

}
