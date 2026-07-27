package com.example.cataniaunited.lobby;

import com.example.cataniaunited.dto.MessageDTO;
import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.DiceRollException;
import com.example.cataniaunited.exception.ui.InvalidTurnException;
import com.example.cataniaunited.fi.LobbyAction;
import com.example.cataniaunited.player.PlayerColor;
import com.example.cataniaunited.player.PlayerService;
import com.example.cataniaunited.util.Util;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of the {@link LobbyService} interface. Manages game lobbies
 * using a concurrent map for storage.
 * <br>
 * Important: This Service is Application Scoped which means it is a Singleton
 * that handles all existing Lobbies, there should be no lengthy calculations in
 * this Class to ensure that different Clients don't experience long waits.
 */
@ApplicationScoped
public class LobbyServiceImpl implements LobbyService {

    private static final Logger logger = Logger.getLogger(LobbyServiceImpl.class);
    private final Map<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private static final SecureRandom secureRandom = new SecureRandom();
    public static final int WIN_THRESHOLD = 10;

    @Inject
    PlayerService playerService;

    /**
     * {@inheritDoc} Creates a new lobby, assigns a color to the host, and
     * stores the lobby.
     */
    @Override
    public String createLobby(String hostPlayer) {
        String lobbyId;
        do {
            lobbyId = generateLobbyId();
        } while (lobbies.containsKey(lobbyId));
        Lobby lobby = new Lobby(lobbyId, hostPlayer);
        setPlayerColor(lobby, hostPlayer); // Assign a color to the host
        lobbies.put(lobbyId, lobby);
        logger.infof("Lobby created: ID=%s, Host=%s", lobbyId, hostPlayer);
        return lobbyId;
    }

    /**
     * {@inheritDoc} Generates a 6-character ID consisting of 3 random letters
     * and 3 random numbers, in a random order.
     */
    @Override
    public String generateLobbyId() {
        String letters = getRandomCharacters("abcdefghijklmnopqrstuvwxyz", 3);
        String numbers = getRandomCharacters("0123456789", 3);
        return secureRandom.nextBoolean() ? letters + numbers : numbers + letters;
    }

    /**
     * Generates a random string of a specified length from a given set of
     * characters.
     *
     * @param characters The string of characters to choose from.
     * @param length     The desired length of the random string.
     * @return A randomly generated string.
     */
    private String getRandomCharacters(String characters, int length) {
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int index = secureRandom.nextInt(characters.length());
            stringBuilder.append(characters.charAt(index));
        }
        return stringBuilder.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Lobby> getOpenLobbies() {
        List<Lobby> openLobbies = new ArrayList<>(lobbies.values());
        logger.infof("Current open lobbies: %s", openLobbies);
        return openLobbies;
    }

    @Override
    public List<Lobby> getAvailableLobbies() {
        return lobbies.values().stream()
                .filter(lobby -> !lobby.isGameStarted())
                .toList();
    }

    /**
     * {@inheritDoc} If joining is successful, a color is assigned to the
     * player.
     *
     * @throws GameException if the lobby is not found.
     */
    @Override
    public boolean joinLobbyByCode(String lobbyId, String player) {
        try {
            Lobby lobby = getLobbyById(lobbyId);
            if (lobby.isGameStarted()) {
                return false;
            }
            PlayerColor assignedColor = setPlayerColor(lobby, player);
            if (assignedColor == null) {
                return false;
            }
            lobby.addPlayer(player);
            logger.infof("Player %s joined lobby %s with color %s", player, lobbyId, assignedColor);
            return true;
        } catch (GameException ge) {
            logger.errorf(ge, "Invalid or expired lobby ID: %s", lobbyId);
        }
        return false;
    }

    @Override
    public void leaveLobby(String lobbyId, String playerId) throws GameException {
        removePlayerFromLobby(lobbyId, playerId);
    }

    /**
     * Removes the player with the given ID from all their lobbies.
     * If the player is the host of a lobby, the lobby gets closed and removed
     *
     * @return A set of lobbies the player was part of
     */
    @Override
    public Set<Lobby> removePlayerFromLobbies(String playerId) {
        Set<Lobby> affectedLobbies = lobbies.values().stream()
                .filter(Objects::nonNull)
                .filter(lobby -> lobby.getPlayers().contains(playerId))
                .collect(Collectors.toSet());

        affectedLobbies.forEach(lobby -> {
            try {
                leaveLobby(lobby.getLobbyId(), playerId);
                if (lobby.getHostPlayer().equals(playerId)) {
                    removeLobby(lobby.getLobbyId());
                }
            } catch (GameException e) {
                logger.warnf(e, "Failed to remove player %s from lobby %s", playerId, lobby.getLobbyId());
            }
        });

        return affectedLobbies;
    }

    /**
     * Assigns an available color to a player within a specific lobby.
     *
     * @param lobby  The {@link Lobby} where the player is.
     * @param player The ID of the player to assign a color to.
     * @return The assigned {@link PlayerColor}, or null if no colors are
     * available.
     */
    protected PlayerColor setPlayerColor(Lobby lobby, String player) {
        PlayerColor assignedColor = lobby.assignAvailableColor();
        if (assignedColor == null) {
            logger.warnf("No colors available for new players in lobby %s.", lobby.getLobbyId());
            return null;
        }
        lobby.setPlayerColor(player, assignedColor);
        return assignedColor;
    }

    /**
     * {@inheritDoc} Restores the player's color to the available pool if they
     * had one.
     */
    @Override
    public void removePlayerFromLobby(String lobbyId, String player) throws GameException {
        logger.infof("Removing player %s from lobby %s", player, lobbyId);
        Lobby lobby = getLobbyById(lobbyId);
        PlayerColor color = lobby.getPlayerColor(player);
        if (color != null) {
            lobby.restoreColor(color);
            logger.infof("Color %s returned to pool from player %s", color, player);
        }
        lobby.removePlayer(player);
        logger.infof("Player %s removed from lobby %s", player, lobbyId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Lobby getLobbyById(String lobbyId) throws GameException {
        if (Util.isEmpty(lobbyId)) {
            logger.errorf("Lobby not found because given lobbyId is empty or null: id = %s", lobbyId);
            throw new GameException("ID of Lobby must not be empty");
        }

        Lobby lobby = lobbies.get(lobbyId);
        if (lobby == null) {
            logger.errorf("Lobby not found: id = %s", lobbyId);
            throw new GameException("Lobby with id %s not found", lobbyId);
        }
        return lobby;
    }

    private void executeLobbyCheck(String lobbyId, LobbyAction action) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        action.execute(lobby);
    }

    /**
     * {@inheritDoc}
     *
     * @throws GameException if the lobby is not found or it is not the players
     *                       turn.
     */
    @Override
    public void checkPlayerTurn(String lobbyId, String playerId) throws GameException {
        executeLobbyCheck(lobbyId, lobby -> checkPlayerTurn(lobby, playerId));
    }

    private void checkPlayerTurn(Lobby lobby, String playerId) throws GameException {
        if (!lobby.isPlayerTurn(playerId)) {
            logger.errorf("It is not the players turn: playerId=%s, lobbyId=%s", playerId, lobby.getLobbyId());
            throw new InvalidTurnException();
        }
    }

    @Override
    public void checkPlayerDiceRoll(String lobbyId, String playerId) throws GameException {
        executeLobbyCheck(lobbyId, lobby -> {
            checkPlayerTurn(lobby, playerId);
            if (!lobby.canRollDice(playerId)) {
                throw new DiceRollException();
            }
        });
    }

    @Override
    public void updateLatestDiceRoll(String lobbyId, String playerId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        lobby.updateLatestDiceRollOfPlayer(playerId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws GameException if the lobby is not found or the player has no
     *                       assigned color.
     */
    @Override
    public PlayerColor getPlayerColor(String lobbyId, String playerId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        PlayerColor playerColor = lobby.getPlayerColor(playerId);
        if (playerColor == null) {
            throw new GameException("No color for player found: playerId=%s, lobbyId=%s", playerId, lobbyId);
        }
        return playerColor;
    }

    /**
     * {@inheritDoc} Uses {@link PlayerService} to get actual Player objects to
     * send messages.
     */
    @Override
    public Uni<MessageDTO> notifyPlayers(String lobbyId, MessageDTO dto, String excludePlayerId) {
        logger.debugf("Notifying players in lobby: lobbyId=%s, message=%s", lobbyId, dto);
        try {
            Lobby lobby = getLobbyById(lobbyId);
            return notifyPlayers(lobby, dto, excludePlayerId);
        } catch (GameException ge) {
            logger.errorf(ge, "Error notifying players: lobbyId = %s, error = %s", lobbyId, ge.getMessage());
            return Uni.createFrom().failure(ge);
        }
    }

    @Override
    public Uni<MessageDTO> notifyPlayers(Lobby lobby, MessageDTO dto, String excludePlayerId) {
        String lobbyId = lobby.getLobbyId();
        if (Util.isEmpty(lobby.getPlayers())) {
            logger.warnf("No players in lobby - dropped message: lobbyId=%s", lobbyId);
            return Uni.createFrom().item(dto);
        }

        List<Uni<Void>> sendUnis = lobby.getPlayers()
                .stream()
                .filter(playerId -> !playerId.equals(excludePlayerId))
                .map(playerId -> playerService.sendMessageToPlayer(playerId, dto))
                .toList();

        if (sendUnis.isEmpty()) {
            logger.warnf("No players to notify after applying exclude filter: lobbyId=%s, excludePlayerId=%s", lobbyId, excludePlayerId);
            return Uni.createFrom().item(dto);
        }

        return Uni.join().all(sendUnis)
                .andFailFast()
                .onFailure()
                .invoke(err -> logger.errorf(err, "One or more messages failed to send in lobby: lobbyId = %s, error = %s", lobbyId, err.getMessage()))
                .replaceWith(dto);
    }

    @Override
    public String nextTurn(String lobbyId, String playerId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        lobby.nextPlayerTurn();
        String activePlayerId = lobby.getActivePlayer();
        logger.debugf("Player ended turn: lobbyId=%s, previousActivePlayer=%s, nextActivePlayer=%s", lobbyId, playerId, activePlayerId);
        return activePlayerId;
    }

    @Override
    public int getRoundsPlayed(String lobbyId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        return lobby.getRoundsPlayed();
    }

    @Override
    public void toggleReady(String lobbyId, String playerId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        lobby.toggleReady(playerId);
    }

    @Override
    public boolean checkForWin(String lobbyId, String playerId) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        if (!lobby.getPlayers().contains(playerId)) {
            logger.errorf("Check for win failed -> Player is not in lobby: lobbyId = %s, playerId = %s", lobbyId, playerId);
            throw new GameException("Player %s not part of lobby %s", playerId, lobbyId);
        }
        if (lobby.getVictoryPoints(playerId) >= WIN_THRESHOLD) {
            lobby.setGameEnded(true);
            return true;
        }
        return false;
    }

    @Override
    public void addVictoryPoints(String lobbyId, String playerId, int points) throws GameException {
        Lobby lobby = getLobbyById(lobbyId);
        if (!lobby.getPlayers().contains(playerId)) {
            logger.errorf("Check for win failed -> Player is not in lobby: lobbyId = %s, playerId = %s", lobbyId, playerId);
            throw new GameException("Player %s not part of lobby %s", playerId, lobbyId);
        }
        lobby.addVictoryPoints(playerId, points);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clearLobbies() {
        lobbies.clear();
        logger.info("All lobbies have been cleared.");
    }

    @Override
    public void removeLobby(String lobbyId) {
        logger.infof("Removed lobby: lobbyId=%s", lobbyId);
        lobbies.remove(lobbyId);
    }
}
