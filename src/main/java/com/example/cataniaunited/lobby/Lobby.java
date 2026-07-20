package com.example.cataniaunited.lobby;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.player.PlayerColor;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Represents a game lobby where players can gather before starting a Catan game.
 * This class manages the list of players in the lobby, their assigned colors,
 * the game state (e.g., whether the game has started), and the active player.
 * It uses concurrent collections for thread-safe operations.
 */
public class Lobby {

    private static final Logger logger = Logger.getLogger(Lobby.class);

    private final String lobbyId;
    private final Instant createdAt;
    private final String hostPlayer; // ID of the player who created the lobby
    private final Set<String> players = new CopyOnWriteArraySet<>(); // Set of player IDs in the lobby
    private final List<String> playerOrder = new CopyOnWriteArrayList<>();
    private final Map<String, PlayerColor> playerColors = new ConcurrentHashMap<>(); // Maps player ID to their assigned color
    private final List<PlayerColor> availableColors = new CopyOnWriteArrayList<>(); // List of colors not yet assigned
    private final Map<String, Integer> leaderboard = new ConcurrentHashMap<>(); //Map which stores the victory points per player
    private volatile String activePlayer; // ID of the player whose turn it is
    private volatile boolean gameStarted = false; // Flag indicating if the game has started
    private volatile boolean gameEnded = false; // Flag indicating if the game has ended
    private int roundsPlayed = 0;
    private final Map<String, Integer> latestDiceRollOfPlayer = new ConcurrentHashMap<>();
    private final Map<String, Boolean> readyState = new ConcurrentHashMap<>();
    private final Map<String, Integer> cheatCounts = new HashMap<>();
    private final Map<String, Integer> reportCounts = new HashMap<>();
    private final List<ReportRecord> reportRecords = new ArrayList<>();
    private final Set<String> activeCheaters = new HashSet<>();


    /**
     * Constructs a new Lobby instance.
     * Initializes the lobby with a unique ID and the host player.
     * The host player is automatically added to the lobby's player set.
     * All standard {@link PlayerColor} values are initially added to the list of available colors.
     *
     * @param lobbyId    The unique identifier for this lobby.
     * @param hostPlayer The ID of the player who created (hosts) the lobby.
     */
    public Lobby(String lobbyId, String hostPlayer) {
        this.lobbyId = lobbyId;
        this.createdAt = Instant.now();
        this.hostPlayer = hostPlayer;
        Collections.addAll(availableColors, PlayerColor.values());
        players.add(hostPlayer);
        setPlayerColor(hostPlayer, assignAvailableColor());
    }

    /**
     * Gets the unique identifier of this lobby.
     *
     * @return The lobby ID string.
     */
    public String getLobbyId() {
        return lobbyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getHostPlayer() {
        return hostPlayer;
    }

    /**
     * Gets the set of player IDs currently in this lobby.
     * The returned set is a thread-safe {@link CopyOnWriteArraySet}.
     *
     * @return A {@link Set} of player ID strings.
     */
    public Set<String> getPlayers() {
        return Set.copyOf(players);
    }

    /**
     * Adds a player to this lobby by their ID.
     * The player ID is added to the thread-safe set of players.
     *
     * @param player The ID of the player to add.
     */
    public void addPlayer(String player) {
        players.add(player);
    }

    /**
     * Removes a player from this lobby by their ID.
     * This also removes the player's color assignment from the lobby's internal map.
     *
     * @param playerId The ID of the player to remove.
     *                 Note: {@code playerColors.remove(player)} does not return a boolean indicating removal success directly in this context.
     */
    public void removePlayer(String playerId) throws GameException {
        playerColors.remove(playerId);
        readyState.remove(playerId);

        boolean isLastInOrder = !playerOrder.isEmpty() && playerOrder.getLast().equals(playerId);
        playerOrder.remove(playerId);
        players.remove(playerId);
        resetVictoryPoints(playerId);

        if (isGameStarted() && !isGameEnded() && Objects.equals(getActivePlayer(), playerId)) {
            //The game is active, and it is the players turn
            // so the turn has to be ended and the next player should be active
            if (isLastInOrder && roundsPlayed == 0) {
                //It is setup round and current player is last player, therefore
                //the previous player should get control
                activePlayer = playerOrder.getLast();
                roundsPlayed++;
            } else {
                nextPlayerTurn();
            }
        }
    }

    /**
     * Gets the ID of the player whose turn it is currently.
     *
     * @return The ID of the active player, or {@code null} if no player is active or the game has not started.
     */
    public String getActivePlayer() {
        return activePlayer;
    }

    /**
     * Sets the active player for the current turn.
     *
     * @param activePlayer The ID of the player to be set as active.
     */
    public void setActivePlayer(String activePlayer) {
        this.activePlayer = activePlayer;
    }

    /**
     * Checks if it is currently the specified player's turn.
     *
     * @param player The ID of the player to check.
     * @return {@code true} if the specified player's ID matches the active player's ID and the player ID is not null,
     * {@code false} otherwise.
     */
    public boolean isPlayerTurn(String player) {
        return player != null && player.equals(activePlayer);
    }

    /**
     * Gets the list of player colors that are currently available for assignment to new players.
     * The returned list is a thread-safe {@link CopyOnWriteArrayList}.
     *
     * @return A {@link List} of available {@link PlayerColor}s.
     */
    public List<PlayerColor> getAvailableColors() {
        return availableColors;
    }

    /**
     * Assigns a specific color to a player in this lobby.
     * The mapping from player ID to color is stored in a thread-safe map.
     *
     * @param player The ID of the player.
     * @param color  The {@link PlayerColor} to assign to the player.
     */
    public void setPlayerColor(String player, PlayerColor color) {
        playerColors.put(player, color);
    }

    /**
     * Gets the color assigned to a specific player in this lobby.
     *
     * @param player The ID of the player.
     * @return The {@link PlayerColor} assigned to the player, or {@code null} if no color is assigned or player not found in the color map.
     */
    public PlayerColor getPlayerColor(String player) {
        return playerColors.get(player);
    }

    /**
     * Removes the color assignment for a specific player from the lobby's records.
     *
     * @param player The ID of the player whose color assignment is to be removed from the map.
     */
    public void removePlayerColor(String player) {
        playerColors.remove(player);
    }

    /**
     * Assigns an available color from the pool to a player.
     * The list of available colors is shuffled before a color is removed to provide some randomness.
     * The chosen color is removed from the available pool.
     *
     * @return The assigned {@link PlayerColor}, or {@code null} if no colors are available in the pool.
     */
    public PlayerColor assignAvailableColor() {
        if (availableColors.isEmpty()) return null;
        Collections.shuffle(availableColors);
        return availableColors.remove(0);
    }

    /**
     * Restores a player color back to the pool of available colors.
     * The color is only added if it's not null and not already present in the available colors list
     * to prevent duplicates.
     *
     * @param color The {@link PlayerColor} to restore to the available pool.
     */
    public void restoreColor(PlayerColor color) {
        if (color != null && !availableColors.contains(color)) {
            availableColors.add(color);
        }
    }

    /**
     * Checks if the game in this lobby has been marked as started.
     *
     * @return {@code true} if the game has started, {@code false} otherwise.
     */
    public boolean isGameStarted() {
        return gameStarted;
    }

    /**
     * Sets the game started status of this lobby.
     *
     * @param started {@code true} to mark the game as started, {@code false} otherwise.
     */
    public void setGameStarted(boolean started) {
        this.gameStarted = started;
    }

    public boolean isGameEnded() {
        return gameEnded;
    }

    public void setGameEnded(boolean gameEnded) {
        this.gameEnded = gameEnded;
    }

    /**
     * Determines if the game can be started by the requesting player.
     * The conditions for starting are:
     * <ol>
     *     <li>The {@code requestingPlayer} must be the {@code hostPlayer} of the lobby.</li>
     *     <li>There must be at least 2 players in the lobby ({@code players.size() >= 2}).</li>
     *     <li>The game must not have already started ({@code !gameStarted}).</li>
     * </ol>
     *
     * @param requestingPlayer The ID of the player attempting to start the game.
     * @return {@code true} if all conditions are met, {@code false} otherwise.
     */
    public boolean canStartGame(String requestingPlayer) {
        logger.debugf("Trying to start game in lobby: lobbyId=%s, hostPlayer=%s, players = %s, isGameStarted = %s", lobbyId, hostPlayer, players, gameStarted);
        return hostPlayer.equals(requestingPlayer)
                && players.size() >= 2
                && players.stream().allMatch(this::isReady)
                && !gameStarted;
    }

    /**
     * Randomizes the order of players currently in the lobby to determine the turn sequence for the game.
     * The current set of players is converted to a list, shuffled, and then the {@code players} set is updated
     * to reflect this new order (though {@link CopyOnWriteArraySet} does not strictly guarantee order,
     * this operation effectively re-populates it based on the shuffled list).
     * The first player in the shuffled order is set as the {@code activePlayer}.
     * Finally, the game is marked as {@code gameStarted = true}.
     */
    public synchronized void startGame() {
        setPlayerOrder(players.stream().toList());
        Collections.shuffle(playerOrder);
        activePlayer = playerOrder.get(0);
        gameStarted = true;
    }


    /**
     * Advances the turn to the next player in the sequence.
     * The sequence is determined by the current order of players in the {@code players} set
     * (which was established by {@link #startGame()} or {@link #setPlayerOrder(List)}).
     * If the lobby has no players or no active player is set, this method returns early.
     * The turn wraps around to the first player after the last player in the sequence.
     */
    public synchronized void nextPlayerTurn() throws GameException {
        if (playerOrder.isEmpty() || activePlayer == null) {
            logger.errorf("Executing next turn failed, player order is empty or activePlayer is null: lobbyId=%s, playerOrder = %s, activePlayer = %s", lobbyId, playerOrder, activePlayer);
            throw new GameException("Executing next turn failed");
        }
        int currentIdx = playerOrder.indexOf(activePlayer);
        int nextIdx;
        if ((currentIdx == playerOrder.size() - 1 && roundsPlayed == 0)) {
            //After the first round, the next round is played in reverse, and
            //it is again the turn of the last player
            nextIdx = currentIdx;
            roundsPlayed++;
        } else if (roundsPlayed == 1) {
            //Special handling when starting the second round to ensure the last/first player also
            //gets his second setup round
            nextIdx = currentIdx - 1;
            if (nextIdx < 0) {
                nextIdx = 1;
                roundsPlayed++;
            }
        } else {
            nextIdx = (currentIdx + 1) % playerOrder.size();
            if (nextIdx == 0) {
                roundsPlayed++;
            }
        }

        activePlayer = playerOrder.get(nextIdx);
    }

    /**
     * Resets the lobby state for a new game session.
     * This method marks the game as not started ({@code gameStarted = false})
     * and clears the current {@code activePlayer} by setting it to {@code null}.
     * The list of players and their color assignments remain unchanged.
     */
    public void resetForNewGame() {
        this.gameStarted = false;
        this.activePlayer = null;
        this.playerOrder.clear();
        this.readyState.clear();
        this.leaderboard.clear();
        this.roundsPlayed = 0;
    }

    public int getRoundsPlayed() {
        return roundsPlayed;
    }

    public List<String> getPlayerOrder() {
        return List.copyOf(playerOrder);
    }

    public boolean canRollDice(String playerId) {
        return latestDiceRollOfPlayer.getOrDefault(playerId, 0) <= roundsPlayed;
    }

    public void updateLatestDiceRollOfPlayer(String playerId) {
        latestDiceRollOfPlayer.put(playerId, roundsPlayed + 1);
    }

    /**
     * Sets a specific player order for the game.
     * This method clears the current {@code players} set and then re-populates it
     * with the players from the provided {@code order} list, in that sequence.
     * This is used to establish a fixed turn order.
     *
     * @param order A {@link List} of player IDs representing the desired turn order.
     *              The order of players in this list will determine the sequence of turns.
     */
    public void setPlayerOrder(List<String> order) {
        playerOrder.clear();
        playerOrder.addAll(order);
    }

    public void toggleReady(String playerId) {
        boolean isReady = readyState.getOrDefault(playerId, false);
        readyState.put(playerId, !isReady);
    }

    public boolean isReady(String playerId) {
        return readyState.getOrDefault(playerId, false);
    }

    public Map<String, Integer> getCheatCounts() {
        return cheatCounts;
    }

    public void recordCheat(String playerId) {
        int current = cheatCounts.getOrDefault(playerId, 0);
        cheatCounts.put(playerId, current + 1);
        activeCheaters.add(playerId);
    }

    public int getCheatCount(String playerId) {
        return cheatCounts.getOrDefault(playerId, 0);
    }

    public Map<String, Integer> getReportCounts() {
        return reportCounts;
    }

    public void recordReport(String reporterId, String reportedId) {
        int current = reportCounts.getOrDefault(reporterId, 0);
        reportCounts.put(reporterId, current + 1);
        reportRecords.add(new ReportRecord(reporterId, reportedId));
    }

    public List<ReportRecord> getReportRecords() {
        return Collections.unmodifiableList(reportRecords);
    }

    public int getReportCount(String playerId) {
        return reportCounts.getOrDefault(playerId, 0);
    }

    public boolean isCheaterAlreadyCaught(String playerId) {
        return !activeCheaters.contains(playerId);
    }

    public void markCheaterAsCaught(String playerId) {
        activeCheaters.remove(playerId);
    }

    public void addVictoryPoints(String playerId, int points) {
        int currentPoints = getVictoryPoints(playerId);
        this.leaderboard.put(playerId, currentPoints + points);
    }

    public int getVictoryPoints(String playerId) {
        return this.leaderboard.getOrDefault(playerId, 0);
    }

    public void resetVictoryPoints(String playerId) {
        this.leaderboard.remove(playerId);
    }
}