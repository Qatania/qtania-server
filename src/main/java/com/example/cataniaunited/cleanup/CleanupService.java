package com.example.cataniaunited.cleanup;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.game.GameService;
import com.example.cataniaunited.game.trade.TradingService;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerService;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@ApplicationScoped
public class CleanupService {

    @Inject
    LobbyService lobbyService;

    @Inject
    GameService gameService;

    @Inject
    PlayerService playerService;

    @Inject
    TradingService tradingService;

    @ConfigProperty(name = "qatania.cleanup.threshold-hours")
    Integer cleanupThresholdHours;

    /**
     * Job that removes all lobbies which are older than 2 days
     */
    @Scheduled(every = "24h")
    void cleanupOldLobbies() {
        Log.debugf("Starting cleanup job for old lobbies");
        Instant cleanupThreshold = Instant.now().minus(cleanupThresholdHours, ChronoUnit.HOURS);
        lobbyService.getOpenLobbies().stream()
                .filter(lobby -> lobby.getCreatedAt().isBefore(cleanupThreshold))
                .forEach(this::cleanupLobby);
    }

    @Scheduled(every = "2h")
    void cleanupFinishedGames() {
        Log.debugf("Starting cleanup job for finished games");
        lobbyService.getOpenLobbies().stream()
                .filter(Lobby::isGameEnded)
                .forEach(this::cleanupLobby);
    }

    void cleanupLobby(Lobby lobby) {
        String lobbyId = lobby.getLobbyId();
        Log.debugf("Starting cleanup of lobby %s", lobbyId);
        gameService.removeGameBoardForLobby(lobbyId);
        lobby.getPlayers().forEach(playerId -> {
            try {
                lobbyService.removePlayerFromLobby(lobbyId, playerId);
            } catch (GameException e) {
                Log.warnf(e, "Error while removing player %s from lobby %s during cleanup", playerId, lobbyId);
            }
        });
        tradingService.removeAllOpenTradeRequestForLobbyId(lobbyId);
        lobbyService.removeLobby(lobbyId);
    }

    /**
     * Removes all already disconnected players
     */
    @Scheduled(every = "24h")
    void cleanupDisconnectedPlayers() {
        Log.debugf("Starting cleanup job for disconnected players");
        playerService.getAllPlayers().forEach(this::cleanupDisconnectedPlayer);
    }

    void cleanupDisconnectedPlayer(Player player) {
        var connection = player.getConnection();
        if (connection != null && connection.isClosed()) {
            Log.debugf("Removing player %s", player.getUniqueId());
            playerService.removePlayerByConnectionId(connection);
        }
    }

}
