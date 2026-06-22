package com.example.cataniaunited.game;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.MissingRequiredStructuresException;
import com.example.cataniaunited.exception.ui.SetupLimitExceededException;
import com.example.cataniaunited.game.board.GameBoard;
import com.example.cataniaunited.game.board.LongestRoadCalculator;
import com.example.cataniaunited.game.board.Road;
import com.example.cataniaunited.game.board.ports.Port;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.game.buildings.Settlement;
import com.example.cataniaunited.game.robber.Robber;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import com.example.cataniaunited.player.PlayerService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service class for managing game logic, including game board creation,
 * player actions (placing settlements, roads, cities), dice rolling,
 * and game state transitions like starting a game and determining a winner.
 * <br>
 * Important: This Service is Application Scoped which means it is a Singleton that handles
 * all Games, there should be no lengthy calculations in this Class to ensure that different
 * Clients don't experience long waits.
 */
@ApplicationScoped
public class GameService {

    private static final Logger logger = Logger.getLogger(GameService.class);
    private static final ConcurrentHashMap<String, GameBoard> lobbyToGameboardMap = new ConcurrentHashMap<>();
    private static final int MIN_LONGEST_ROAD_LENGTH = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();


    @Inject
    LobbyService lobbyService;

    @Inject
    PlayerService playerService;


    /**
     * Creates a new game board for the specified lobby.
     * The size of the game board is determined by the number of players in the lobby.
     *
     * @param lobbyId The ID of the lobby for which to create the game board.
     * @return The newly created {@link GameBoard}.
     * @throws GameException if the lobby is not found or an error occurs during board creation.
     */
    public GameBoard createGameboard(String lobbyId) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        GameBoard gameboard = new GameBoard(lobby.getPlayers().size());
        addGameboardToList(lobby.getLobbyId(), gameboard);
        return gameboard;
    }

    public void placeRobber(String lobbyId, double[] coordinates) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        Robber robber = lobbyToGameboardMap.get(lobbyId).getRobber();
    }

    /**
     * Allows a player to place a settlement on the game board.
     *
     * @param lobbyId              The ID of the lobby.
     * @param playerId             The ID of the player placing the settlement.
     * @param settlementPositionId The ID of the position where the settlement is to be placed.
     * @throws GameException if it's not the player's turn, the position is invalid,
     *                       or other game rules are violated.
     */
    public void placeSettlement(String lobbyId, String playerId, int settlementPositionId) throws GameException {
        lobbyService.checkPlayerTurn(lobbyId, playerId);
        GameBoard gameboard = getGameboardByLobbyId(lobbyId);
        BuildRequest buildRequest = createBuildRequest(lobbyId, playerId, settlementPositionId);
        if (exceedsSetupLimit(buildRequest, gameboard.getPlayerStructureCount(playerId, Settlement.class))) {
            throw new SetupLimitExceededException();
        }
        gameboard.placeSettlement(buildRequest);

        Player player = buildRequest.player();
        Port port = gameboard.getPortOfBuildingSite(settlementPositionId);
        if (port != null) {
            logger.infof("Port found at settlementPositionId=%s", settlementPositionId);
            player.addPort(port);
        } else {
            logger.debug("No Port found at settlementPositionId=%s".formatted(settlementPositionId));
        }
        playerService.addVictoryPoints(playerId, 1);
    }

    /**
     * Allows a player to upgrade an existing settlement to a city.
     *
     * @param lobbyId              The ID of the lobby.
     * @param playerId             The ID of the player upgrading the settlement.
     * @param settlementPositionId The ID of the position of the settlement to be upgraded.
     * @throws GameException if it's not the player's turn, the position is invalid,
     *                       no settlement exists, or other game rules are violated.
     */
    public void upgradeSettlement(String lobbyId, String playerId, int settlementPositionId) throws GameException {
        lobbyService.checkPlayerTurn(lobbyId, playerId);
        BuildRequest buildRequest = createBuildRequest(lobbyId, playerId, settlementPositionId);
        if (buildRequest.isSetupRound()) {
            throw new GameException("Settlements cannot be upgraded during setup round");
        }
        GameBoard gameboard = getGameboardByLobbyId(lobbyId);
        gameboard.placeCity(buildRequest);
        playerService.addVictoryPoints(playerId, 1); // Only add one additional Point
    }

    /**
     * Allows a player to place a road on the game board.
     *
     * @param lobbyId  The ID of the lobby.
     * @param playerId The ID of the player placing the road.
     * @param roadId   The ID of the road to be placed.
     * @throws GameException if it's not the player's turn, the road ID is invalid,
     *                       or other game rules are violated.
     */
    public void placeRoad(String lobbyId, String playerId, int roadId) throws GameException {
        lobbyService.checkPlayerTurn(lobbyId, playerId);
        GameBoard gameboard = getGameboardByLobbyId(lobbyId);
        BuildRequest buildRequest = createBuildRequest(lobbyId, playerId, roadId);
        if (exceedsSetupLimit(buildRequest, gameboard.getPlayerStructureCount(playerId, Road.class))) {
            throw new SetupLimitExceededException();
        }
        gameboard.placeRoad(buildRequest);
        Player player = buildRequest.player();
        List<Road> playerRoads = gameboard.getRoadList().stream()
                .filter(r -> player.equals(r.getOwner()))
                .toList();

        LongestRoadCalculator calculator = getLongestRoadCalculator();
        int newLength = calculator.calculateFor(playerRoads);
        if (newLength >= MIN_LONGEST_ROAD_LENGTH && newLength > gameboard.getLongestRoadLength()) {
            String oldLongestRoadPlayerId = gameboard.getLongestRoadPlayerId();
            if (oldLongestRoadPlayerId != null && !oldLongestRoadPlayerId.equals(playerId)) {
                playerService.addVictoryPoints(oldLongestRoadPlayerId, -2);
            }

            if (!playerId.equals(oldLongestRoadPlayerId)) {
                playerService.addVictoryPoints(playerId, 2);
            }

            gameboard.setLongestRoad(playerId, newLength);
        }
    }

    protected LongestRoadCalculator getLongestRoadCalculator() {
        return new LongestRoadCalculator();
    }

    private boolean exceedsSetupLimit(BuildRequest buildRequest, long structureCount) {
        Optional<Integer> maximumStructureCount = buildRequest.maximumStructureCount();
        return buildRequest.isSetupRound()
                && maximumStructureCount.isPresent()
                && structureCount >= maximumStructureCount.get();
    }

    private BuildRequest createBuildRequest(String lobbyId, String playerId, int positionId) throws GameException {
        Player player = playerService.getPlayerById(playerId);
        PlayerColor color = lobbyService.getPlayerColor(lobbyId, playerId);
        int roundsPlayed = lobbyService.getRoundsPlayed(lobbyId);
        boolean isSetupRound = roundsPlayed <= 1;
        Optional<Integer> maximumStructureCount = isSetupRound ? Optional.of(roundsPlayed + 1) : Optional.empty();
        return new BuildRequest(
                player,
                color,
                positionId,
                isSetupRound,
                maximumStructureCount
        );
    }

    /**
     * Starts the game in the specified lobby.
     * This involves creating a game board, setting player order, and notifying players.
     *
     * @param lobbyId The ID of the lobby where the game is to be started.
     * @throws GameException if the game cannot be started (e.g., already started, not enough players).
     */
    public void startGame(String lobbyId, String hostPlayerId) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        if (!lobby.canStartGame(hostPlayerId)) {
            throw new GameException("Starting of game failed");
        }

        createGameboard(lobbyId);
        for (String playerId : lobby.getPlayers()) {
            playerService.initializePlayerResources(playerId);
        }

        lobby.startGame();
        logger.infof("Game started in lobby: lobbyId=%s, order=%s", lobbyId, lobby.getPlayerOrder());
    }

    /**
     * Retrieves the JSON representation of the game board for a given lobby.
     *
     * @param lobbyId The ID of the lobby.
     * @return An {@link ObjectNode} containing the game board's JSON structure.
     * @throws GameException if the game board for the lobby is not found.
     */
    public ObjectNode getGameboardJsonByLobbyId(String lobbyId) throws GameException {
        GameBoard gameBoard = getGameboardByLobbyId(lobbyId);
        return gameBoard.getJson();
    }

    /**
     * Retrieves the {@link GameBoard} instance for a given lobby.
     *
     * @param lobbyId The ID of the lobby.
     * @return The {@link GameBoard} associated with the lobby.
     * @throws GameException if the game board for the lobby is not found.
     */
    public GameBoard getGameboardByLobbyId(String lobbyId) throws GameException {
        GameBoard gameboard = lobbyToGameboardMap.get(lobbyId);
        if (gameboard == null) {
            logger.errorf("Gameboard for Lobby not found: id = %s", lobbyId);
            throw new GameException("Gameboard for Lobby not found: id = %s", lobbyId);
        }
        return gameboard;
    }

    /**
     * Adds a game board to the internal map, associating it with a lobby ID.
     *
     * @param lobbyId   The ID of the lobby.
     * @param gameboard The {@link GameBoard} to add.
     */
    void addGameboardToList(String lobbyId, GameBoard gameboard) {
        lobbyToGameboardMap.put(lobbyId, gameboard);
    }


    /**
     * Simulates a dice roll for the game in the specified lobby.
     * The result of the roll determines resource distribution.
     *
     * @param lobbyId The ID of the lobby where the dice are rolled.
     * @return An {@link ObjectNode} containing the results of the two dice and their total.
     * @throws GameException if the game board for the lobby is not found.
     */
    public ObjectNode rollDice(String lobbyId, String playerId) throws GameException {
        lobbyService.checkPlayerDiceRoll(lobbyId, playerId);
        GameBoard gameboard = getGameboardByLobbyId(lobbyId);
        ObjectNode result = gameboard.rollDice();
        lobbyService.updateLatestDiceRoll(lobbyId, playerId);
        return result;
    }

    public void checkRequiredPlayerStructures(String lobbyId, String playerId, int currentRound) throws GameException {
        if (currentRound > 1) {
            logger.debugf("Skipping required structure check, since the game is past second round: lobbyId=%s, currentRound=%s", lobbyId, currentRound);
            return;
        }

        GameBoard gameBoard = getGameboardByLobbyId(lobbyId);
        long roadCount = gameBoard.getPlayerStructureCount(playerId, Road.class);
        if (roadCount < currentRound + 1) {
            logger.errorf("Player did not build enough roads in this round: lobbyId=%s, playerId=%s, currentRound=%d", lobbyId, playerId, currentRound);
            throw new MissingRequiredStructuresException();
        }

        long settlementCount = gameBoard.getPlayerStructureCount(playerId, Settlement.class);
        if (settlementCount < currentRound + 1) {
            logger.errorf("Player did not build enough settlements in this round: lobbyId=%s, playerId=%s, currentRound=%d", lobbyId, playerId, currentRound);
            throw new MissingRequiredStructuresException();
        }

    }

    /**
     * Clears all game boards from the memory. Intended for testing purposes.
     */
    public void clearGameBoardsForTesting() {
        lobbyToGameboardMap.clear();
        logger.info("All game boards have been cleared for testing.");
    }

    public void handleCheat(String lobbyId, String playerId, TileType resource) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        Player cheater = playerService.getPlayerById(playerId);

        int currentCheatCount = lobby.getCheatCount(playerId);
        if (currentCheatCount >= 2) {
            throw new GameException("You already cheated twice!");
        }

        List<Player> potentialVictims = lobby.getPlayerOrder().stream()
                .filter(pid -> !pid.equals(playerId))
                .map(playerService::getPlayerById)
                .filter(Objects::nonNull)
                .filter(player -> player.getResourceCount(resource) > 0)
                .toList();

        if (potentialVictims.isEmpty()) {
            throw new GameException("No player has that resource to steal.");
        }

        Player victim = potentialVictims.stream()
                .max(Comparator.comparingInt(player -> player.getResourceCount(resource)))
                .orElseThrow();

        victim.removeResource(resource, 1);
        cheater.receiveResource(resource, 1);

        lobby.recordCheat(playerId);
    }


    public ReportOutcome handleReportPlayer(String lobbyId, String reporterId, String reportedId) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(lobbyId);

        lobby.recordReport(reporterId, reportedId);

        Player reported = playerService.getPlayerById(reportedId);
        Player reporter = playerService.getPlayerById(reporterId);

        boolean hasCheated = lobby.getCheatCount(reportedId) > 0;
        boolean alreadyCaught = lobby.isCheaterAlreadyCaught(reportedId);

        if (!hasCheated) {
            punishReporter(reporter);
            return ReportOutcome.FALSE_REPORT;
        }

        if (alreadyCaught) {
            punishReporter(reporter);
            return ReportOutcome.CORRECT_REPORT_ALREADY_CAUGHT;
        }

        punishCheater(reported);
        lobby.markCheaterAsCaught(reportedId);
        return ReportOutcome.CORRECT_REPORT_NEW;
    }

    private void punishCheater(Player reported) throws GameException {
        List<TileType> resourceList = new ArrayList<>();
        for (TileType type : TileType.values()) {
            if (type == TileType.WASTE) continue;
            int count = reported.getResourceCount(type);
            for (int i = 0; i < count; i++) {
                resourceList.add(type);
            }
        }

        int toLose = resourceList.size() / 2;
        Collections.shuffle(resourceList);
        for (int i = 0; i < toLose; i++) {
            reported.removeResource(resourceList.get(i), 1);
        }
    }

    private void punishReporter(Player reporter) throws GameException {
        List<TileType> availableResources = reporter.getResources().entrySet().stream()
                .filter(e -> e.getKey() != TileType.WASTE && e.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();

        if (!availableResources.isEmpty()) {
            TileType random = availableResources.get(SECURE_RANDOM.nextInt(availableResources.size()));
            reporter.removeResource(random, 1);
        }
    }

    public void removeGameBoardForLobby(String lobbyId) {
        logger.debugf("Removing game board for lobbyId=%s", lobbyId);
        lobbyToGameboardMap.remove(lobbyId);
    }

}