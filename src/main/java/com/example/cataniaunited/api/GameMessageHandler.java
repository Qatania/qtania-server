package com.example.cataniaunited.api;

import com.example.cataniaunited.dto.LobbyInfo;
import com.example.cataniaunited.dto.MessageDTO;
import com.example.cataniaunited.dto.MessageType;
import com.example.cataniaunited.dto.PlayerInfo;
import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.InvalidTurnException;
import com.example.cataniaunited.fi.BuildingAction;
import com.example.cataniaunited.game.GameService;
import com.example.cataniaunited.game.ReportOutcome;
import com.example.cataniaunited.game.board.GameBoard;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.game.trade.PlayerTradeRequest;
import com.example.cataniaunited.game.trade.TradeRequest;
import com.example.cataniaunited.game.trade.TradingService;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.mapper.LobbyMapper;
import com.example.cataniaunited.mapper.PlayerMapper;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import com.example.cataniaunited.player.PlayerService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.example.cataniaunited.dto.MessageType.LOBBY_CLOSED;
import static com.example.cataniaunited.dto.MessageType.LOBBY_LIST;
import static com.example.cataniaunited.dto.MessageType.LOBBY_UPDATED;

@ApplicationScoped
public class GameMessageHandler {

    private static final Logger logger = Logger.getLogger(GameMessageHandler.class);
    private static final String COLOR_FIELD = "color";
    private static final String ERROR = "error";
    private static final String SEVERITY = "severity";
    private static final String MESSAGE = "message";
    private static final String SUCCESS = "success";
    private static final String TRADE_ID_FIELD = "tradeId";

    @Inject
    LobbyService lobbyService;

    @Inject
    PlayerService playerService;

    @Inject
    GameService gameService;

    @Inject
    PlayerMapper playerMapper;

    @Inject
    TradingService tradingService;

    @Inject
    ObjectMapper objectMapper;

    public Uni<MessageDTO> handleInitialConnection(WebSocketConnection connection) {
        Player player = playerService.addPlayer(connection);
        ObjectNode message = JsonNodeFactory.instance.objectNode().put("playerId", player.getUniqueId());
        return Uni.createFrom().item(new MessageDTO(MessageType.CONNECTION_SUCCESSFUL, message));
    }

    public Uni<Void> handleDisconnect(WebSocketConnection connection) {
        Player player = playerService.getPlayerByConnection(connection);
        List<Uni<MessageDTO>> sendUnis = new ArrayList<>();
        if (player != null) {
            String playerId = player.getUniqueId();
            logger.infof("Player %s disconnected from server", playerId);
            //Remove player from lobbies
            sendUnis = lobbyService.removePlayerFromLobbies(playerId).stream().map(lobby -> {
                        try {
                            return notifyLobbyAboutLeavingPlayer(lobby, playerId);
                        } catch (GameException e) {
                            logger.warnf(e, "Could not notify lobby %s about leaving of player %s", lobby.getLobbyId(), playerId);
                            return null;
                        }
                    })
                    .filter(Objects::nonNull).toList();
        }
        playerService.removePlayerByConnectionId(connection);
        return Uni.join().all(sendUnis)
                .andFailFast()
                .onFailure()
                .invoke(err -> logger.errorf(err, "One or more messages failed on user disconnect"))
                .replaceWith(Uni.createFrom().voidItem());
    }

    Uni<MessageDTO> notifyLobbyAboutLeavingPlayer(Lobby lobby, String playerId) throws GameException {
        String lobbyId = lobby.getLobbyId();
        //Close lobby if host player leaves
        MessageType type = Objects.equals(lobby.getHostPlayer(), playerId)
                ? LOBBY_CLOSED
                : LOBBY_UPDATED;
        MessageDTO dto = new MessageDTO(
                type,
                playerId,
                lobbyId,
                getLobbyPlayerInformation(lobby)
        );
        return lobbyService.notifyPlayers(lobby, dto, playerId);
    }

    public Uni<MessageDTO> handleGameMessage(MessageDTO message) {
        try {
            logger.infof("Handle message: message = %s", message);
            return switch (message.getType()) {
                case CREATE_LOBBY -> createLobby(message);
                case GET_LOBBIES -> getLobbies();
                case JOIN_LOBBY -> joinLobby(message);
                case LEAVE_LOBBY -> leaveLobby(message);
                case SET_USERNAME -> setUsername(message);
                case PLACE_SETTLEMENT -> placeSettlement(message);
                case UPGRADE_SETTLEMENT -> upgradeSettlement(message);
                case PLACE_ROAD -> placeRoad(message);
                case ROLL_DICE -> handleDiceRoll(message);
                case START_GAME -> handleStartGame(message);
                case SET_READY -> setReady(message);
                case TRADE_WITH_BANK -> handleTradeWithBank(message);
                case CREATE_PLAYER_TRADE_REQUEST -> createPlayerTradeRequest(message);
                case ACCEPT_TRADE_REQUEST -> acceptTradeRequest(message);
                case REJECT_TRADE_REQUEST -> rejectTradeRequest(message);
                case CHEAT_ATTEMPT -> handleCheatAttempt(message);
                case REPORT_PLAYER -> handleReportPlayer(message);
                case END_TURN -> endTurn(message);
                case PLACE_ROBBER -> placeRobber(message);
                default -> throw new GameException("Invalid client command");
            };
        } catch (GameException ge) {
            logger.errorf("Unexpected Error occurred: message = %s, error = %s", message, ge.getMessage());
            return Uni.createFrom().item(createErrorMessage(ge.getMessage()));
        }
    }

    Uni<MessageDTO> getLobbies() {
        List<LobbyInfo> lobbyList = lobbyService.getAvailableLobbies()
                .stream()
                .sorted(Comparator.comparingLong(
                        (Lobby lobby) -> lobby.getCreatedAt().toEpochMilli()
                ).reversed())
                .map(this::getLobbyInfo)
                .filter(info -> info.hostPlayer() != null && info.playerCount() > 0)
                .toList();

        ObjectNode message = JsonNodeFactory.instance.objectNode();
        message.set("lobbies", objectMapper.valueToTree(lobbyList));

        MessageDTO messageDTO = new MessageDTO(LOBBY_LIST, message);
        return Uni.createFrom().item(messageDTO);

    }

    LobbyInfo getLobbyInfo(Lobby lobby) {
        Player hostPlayer = playerService.getPlayerById(lobby.getHostPlayer());
        return LobbyMapper.INSTANCE.toDto(lobby, hostPlayer);
    }

    Uni<MessageDTO> createPlayerTradeRequest(MessageDTO message) throws GameException {
        // Check if player is active player -> else can't trade
        String lobbyId = message.getLobbyId();

        PlayerTradeRequest playerTradeRequest;
        try {
            // Deserialize the JSON payload into the TradeRequest record.
            playerTradeRequest = objectMapper.treeToValue(message.getMessage(), PlayerTradeRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.errorf("Failed to parse player trade request: %s", e.getMessage());
            throw new GameException("Trade request format is invalid");
        }

        if (!tradingService.verifyPlayerTradeRequest(playerTradeRequest)) {
            throw new GameException("Trade request is invalid");
        }

        String targetPlayerId = playerTradeRequest.targetPlayerId();

        //It must either be the source or the target players turn
        try {
            lobbyService.checkPlayerTurn(lobbyId, playerTradeRequest.sourcePlayerId());
        } catch (InvalidTurnException ite) {
            logger.warnf("Not the players turn while trading, try the target player: %s", targetPlayerId);
            lobbyService.checkPlayerTurn(lobbyId, targetPlayerId);
        }

        String tradeId = tradingService.createPlayerTradeRequest(lobbyId, playerTradeRequest);
        Player targetPlayer = playerService.getPlayerById(targetPlayerId);

        ObjectNode tradeRequestJson = JsonNodeFactory.instance.objectNode();
        tradeRequestJson.put(TRADE_ID_FIELD, tradeId);
        tradeRequestJson.set("tradeRequest", message.getMessage());

        MessageDTO tradeResponse = new MessageDTO(
                MessageType.TRADE_OFFER,
                targetPlayer.getUniqueId(),
                message.getLobbyId(),
                getLobbyPlayerInformation(lobbyId),
                tradeRequestJson
        );

        ObjectNode alertPayload = JsonNodeFactory.instance.objectNode();
        alertPayload.put(MESSAGE, "Sent trade request to " + targetPlayer.getUsername());
        alertPayload.put(SEVERITY, SUCCESS);

        MessageDTO tradeRequestNotification = new MessageDTO(
                MessageType.ALERT,
                message.getPlayer(),
                lobbyId,
                alertPayload
        );

        // Sent trade request to the player and notify the sender about the success
        return targetPlayer.getConnection().sendText(tradeResponse)
                .chain(() -> Uni.createFrom().item(tradeRequestNotification));
    }

    Uni<MessageDTO> acceptTradeRequest(MessageDTO message) throws GameException {
        String tradeId = message.getMessageNode(TRADE_ID_FIELD).asText();
        PlayerTradeRequest tradeRequest = tradingService.acceptPlayerTradeRequest(message.getPlayer(), tradeId);
        MessageDTO updateResponse = new MessageDTO(
                MessageType.PLAYER_RESOURCE_UPDATE,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId())
        );

        Player sourcePlayer = playerService.getPlayerById(tradeRequest.sourcePlayerId());
        Player targetPlayer = playerService.getPlayerById(tradeRequest.targetPlayerId());

        ObjectNode alertPayload = JsonNodeFactory.instance.objectNode();
        alertPayload.put(MESSAGE, "Trade request was accepted by " + targetPlayer.getUsername());
        alertPayload.put(SEVERITY, SUCCESS);

        MessageDTO tradeRequestNotification = new MessageDTO(
                MessageType.ALERT,
                message.getPlayer(),
                message.getLobbyId(),
                alertPayload
        );

        logger.infof("Player %s accepted trade request %s (id=%s).", message.getPlayer(), tradeRequest, tradeId);
        //Notify the source player that his trade request was accepted and then update player resources
        return sourcePlayer.getConnection().sendText(tradeRequestNotification)
                .chain(() -> lobbyService.notifyPlayers(message.getLobbyId(), updateResponse, message.getPlayer()));
    }

    Uni<MessageDTO> rejectTradeRequest(MessageDTO message) throws GameException {
        String tradeId = message.getMessageNode(TRADE_ID_FIELD).asText();
        PlayerTradeRequest tradeRequest = tradingService.rejectPlayerTradeRequest(message.getPlayer(), tradeId);

        Player sourcePlayer = playerService.getPlayerById(tradeRequest.sourcePlayerId());
        Player targetPlayer = playerService.getPlayerById(tradeRequest.targetPlayerId());

        ObjectNode alertPayload = JsonNodeFactory.instance.objectNode();
        alertPayload.put(MESSAGE, "Trade request was rejected by " + targetPlayer.getUsername());
        alertPayload.put(SEVERITY, ERROR);

        MessageDTO tradeRequestNotification = new MessageDTO(
                MessageType.ALERT,
                message.getPlayer(),
                message.getLobbyId(),
                alertPayload
        );

        return sourcePlayer.getConnection().sendText(tradeRequestNotification).chain(() -> Uni.createFrom().nullItem());
    }

    Uni<MessageDTO> placeRobber(MessageDTO message) throws GameException {


        JsonNode tileIdNode = message.getMessageNode("tileId");
        if (tileIdNode.isMissingNode()) {
            throw new GameException("Missing required 'tileId' field for placing the robber.");
        }

        int targetTileId;
        try {
            targetTileId = Integer.parseInt(tileIdNode.toString());
        } catch (NumberFormatException e) {
            throw new GameException("Invalid tile ID format provided: %s", tileIdNode.toString());
        }

        gameService.placeRobber(message.getLobbyId(), targetTileId);
        ObjectNode updatedBoardJson = getGameBoardInformation(message.getLobbyId());

        MessageDTO updateResponse = new MessageDTO(
                MessageType.PLACE_ROBBER,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                updatedBoardJson
        );

        // Broadcast layout mutations to all connected websocket sessions in the room
        return lobbyService.notifyPlayers(message.getLobbyId(), updateResponse, message.getPlayer())
                .chain(() -> Uni.createFrom().item(updateResponse));
    }
    Uni<MessageDTO> endTurn(MessageDTO message) throws GameException {
        Lobby lobby = lobbyService.getLobbyById(message.getLobbyId());
        gameService.checkRequiredPlayerStructures(message.getLobbyId(), message.getPlayer(), lobby.getRoundsPlayed());
        lobbyService.nextTurn(message.getLobbyId(), message.getPlayer());
        ObjectNode payload = getGameBoardInformation(message.getLobbyId());
        var response = new MessageDTO(MessageType.NEXT_TURN, message.getPlayer(), message.getLobbyId(), getLobbyPlayerInformation(message.getLobbyId()), payload);
        return lobbyService.notifyPlayers(message.getLobbyId(), response, message.getPlayer());

    }

    /**
     * Handles a request to place a road on the game board.
     *
     * @param message The {@link MessageDTO} containing the lobby ID, player ID,
     *                and road ID.
     * @return A Uni emitting a {@link MessageDTO} with the updated game board
     * and player resources, which is also broadcast to other players in the
     * lobby.
     * @throws GameException if the road ID is invalid or if the game service
     *                       encounters an error.
     */
    Uni<MessageDTO> placeRoad(MessageDTO message) throws GameException {
        JsonNode roadId = message.getMessageNode("roadId");
        try {
            int position = Integer.parseInt(roadId.toString());
            gameService.placeRoad(message.getLobbyId(), message.getPlayer(), position);
        } catch (NumberFormatException e) {
            throw new GameException("Invalid road id: id = %s", roadId.toString());
        }

        if (lobbyService.checkForWin(message.getLobbyId(), message.getPlayer())) {
            return broadcastWin(message.getLobbyId(), message.getPlayer());
        }

        ObjectNode root = getGameBoardInformation(message.getLobbyId());

        MessageDTO update = new MessageDTO(
                MessageType.PLACE_ROAD,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                root
        );

        return lobbyService.notifyPlayers(message.getLobbyId(), update, message.getPlayer())
                .chain(() -> Uni.createFrom().item(update));
    }

    /**
     * Creates a JSON object representing the game board along with details of
     * all players in the specified lobby. The player details include their
     * username, victory points, and assigned color.
     *
     * @param lobbyId The ID of the lobby for which to retrieve the game board
     *                and player data.
     * @return An {@link ObjectNode} containing the "gameboard" (JSON
     * representation of the game board) and a "players" object mapping player
     * IDs to their details.
     * @throws GameException if the lobby or game board cannot be found, or if a
     *                       player in the lobby cannot be retrieved.
     */
    ObjectNode getGameBoardInformation(String lobbyId) throws GameException {
        GameBoard gameboard = gameService.getGameboardByLobbyId(lobbyId);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("gameboard", gameboard.getJson());
        return root;
    }

    /**
     * Handles a request from a client to place a settlement on the game board.
     * This method uses the {@link #handleSettlementAction} generic handler to
     * process the request.
     *
     * @param message The {@link MessageDTO} containing the lobby ID, player ID,
     *                and building site ID.
     * @return A Uni emitting a {@link MessageDTO} with the updated game state
     * (or win message), which is also broadcast to other players in the lobby.
     * @throws GameException if the building site ID is invalid or if the game
     *                       service encounters an error during settlement placement (e.g., rules
     *                       violation, insufficient resources).
     */
    Uni<MessageDTO> placeSettlement(MessageDTO message) throws GameException {
        BuildingAction placeAction = positionId -> gameService.placeSettlement(message.getLobbyId(), message.getPlayer(), positionId);
        return handleSettlementAction(message, placeAction);
    }

    /**
     * Handles a request to upgrade a settlement to a city on the game board.
     *
     * @param message The {@link MessageDTO} containing the lobby ID, player ID,
     *                and building site ID.
     * @return A Uni emitting a {@link MessageDTO} with the updated game state,
     * which is also broadcast to other players in the lobby.
     * @throws GameException if the game service encounters an error during
     *                       settlement upgrade.
     */
    Uni<MessageDTO> upgradeSettlement(MessageDTO message) throws GameException {
        BuildingAction upgradeAction = positionId -> gameService.upgradeSettlement(message.getLobbyId(), message.getPlayer(), positionId);
        return handleSettlementAction(message, upgradeAction);
    }

    /**
     * Generic handler for settlement actions (place or upgrade). It parses the
     * building site, executes the provided action, checks for a win condition,
     * and then broadcasts the updated game state.
     *
     * @param message The {@link MessageDTO} containing action details.
     * @param action  The {@link BuildingAction} to execute (e.g., place or
     *                upgrade).
     * @return A Uni emitting a {@link MessageDTO} with the updated game state
     * or a win message.
     * @throws GameException if the building site ID is invalid or the action
     *                       fails.
     */
    Uni<MessageDTO> handleSettlementAction(MessageDTO message, BuildingAction action) throws GameException {
        JsonNode settlementPosition = message.getMessageNode("settlementPositionId");
        try {
            int position = Integer.parseInt(settlementPosition.toString());
            action.execute(position);
        } catch (NumberFormatException e) {
            throw new GameException("Invalid settlement position id: id = %s", settlementPosition.toString());
        }

        if (lobbyService.checkForWin(message.getLobbyId(), message.getPlayer())) {
            return broadcastWin(message.getLobbyId(), message.getPlayer());
        }

        ObjectNode payload = getGameBoardInformation(message.getLobbyId());
        MessageDTO update = new MessageDTO(
                message.getType(),
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                payload
        );

        return lobbyService.notifyPlayers(message.getLobbyId(), update, message.getPlayer())
                .chain(() -> Uni.createFrom().item(update));
    }

    /**
     * Handles a request for a player to join an existing lobby.
     *
     * @param message The {@link MessageDTO} containing the lobby ID (code) and
     *                player ID.
     * @return A Uni emitting a {@link MessageDTO} confirming the player joined
     * and their assigned color, which is also broadcast to other players in the
     * lobby.
     * @throws GameException if the lobby is not found or the player cannot
     *                       join.
     */
    Uni<MessageDTO> joinLobby(MessageDTO message) throws GameException {
        boolean joined = lobbyService.joinLobbyByCode(message.getLobbyId(), message.getPlayer());

        if (!joined) {
            throw new GameException("Failed to join lobby: lobby session not found or full");
        }

        PlayerColor color = lobbyService.getPlayerColor(message.getLobbyId(), message.getPlayer());

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put(COLOR_FIELD, color.getHexCode());
        MessageDTO playerJoinedMessage = new MessageDTO(
                MessageType.PLAYER_JOINED,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                payload);
        return lobbyService.notifyPlayers(message.getLobbyId(), playerJoinedMessage, message.getPlayer());
    }

    Uni<MessageDTO> leaveLobby(MessageDTO message) throws GameException {
        lobbyService.leaveLobby(message.getLobbyId(), message.getPlayer());
        return notifyLobbyAboutLeavingPlayer(lobbyService.getLobbyById(message.getLobbyId()), message.getPlayer());
    }

    /**
     * Handles a request to create a new lobby.
     *
     * @param message The {@link MessageDTO} containing the host player's ID.
     * @return A Uni emitting a {@link MessageDTO} with the new lobby's ID and
     * the host's assigned color.
     * @throws GameException if lobby creation fails.
     */
    Uni<MessageDTO> createLobby(MessageDTO message) throws GameException {
        String lobbyId = lobbyService.createLobby(message.getPlayer());
        return Uni.createFrom().item(
                new MessageDTO(MessageType.LOBBY_CREATED, message.getPlayer(), lobbyId, getLobbyPlayerInformation(lobbyId)));
    }

    /**
     * Handles a request to set or update a player's username.
     *
     * @param message The {@link MessageDTO} containing the new username. The
     *                player ID is inferred from the connection.
     * @return A Uni emitting a {@link MessageDTO} confirming the username
     * update, which is also broadcast to other players.
     * @throws GameException if the player session is not found.
     */
    Uni<MessageDTO> setUsername(MessageDTO message) throws GameException {
        String username = message.getMessageNode("username").asText();
        playerService.setUsername(message.getPlayer(), username);
        MessageDTO update = new MessageDTO(MessageType.LOBBY_UPDATED, message.getPlayer(), message.getLobbyId(), getLobbyPlayerInformation(message.getLobbyId()));
        return lobbyService.notifyPlayers(message.getLobbyId(), update, message.getPlayer());
    }

    /**
     * Creates a {@link MessageDTO} for sending an error message to a client.
     *
     * @param errorMessage The error message string.
     * @return A {@link MessageDTO} of type ERROR containing the error message.
     */
    MessageDTO createErrorMessage(String errorMessage) {
        ObjectNode errorNode = JsonNodeFactory.instance.objectNode();
        errorNode.put(ERROR, errorMessage);
        return new MessageDTO(MessageType.ERROR, errorNode);
    }

    /**
     * Handles a dice roll request from a client. This method processes the dice
     * roll, broadcasts the result to all players in the lobby, and then sends
     * updated resource information individually to each player in that lobby.
     *
     * @param message The {@link MessageDTO} containing the player ID and lobby
     *                ID.
     * @return A Uni emitting the {@link MessageDTO} containing the dice roll
     * result. This DTO is the one that was broadcast. The primary purpose of
     * the returned Uni is to chain asynchronous operations.
     * @throws GameException if an error occurs during dice rolling or
     *                       retrieving lobby/player information.
     */
    public Uni<MessageDTO> handleDiceRoll(MessageDTO message) throws GameException {
        Player rollingPlayer = playerService.getPlayerById(message.getPlayer());
        String username = rollingPlayer.getUsername();
        String playerId = rollingPlayer.getUniqueId();

        ObjectNode rollingNode = JsonNodeFactory.instance.objectNode();
        rollingNode.put("rollingUsername", username);
        rollingNode.put("player", playerId);

        MessageDTO rollingMessage = new MessageDTO(
                MessageType.ROLL_DICE,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                rollingNode
        );

        return lobbyService.notifyPlayers(message.getLobbyId(), rollingMessage, message.getPlayer())
                .chain(() -> {
                    try {
                        ObjectNode diceResult = gameService.rollDice(message.getLobbyId(), message.getPlayer());

                        diceResult.put("rollingUsername", username);
                        diceResult.put("player", playerId);

                        MessageDTO resultMessage = new MessageDTO(
                                MessageType.DICE_RESULT,
                                message.getPlayer(),
                                message.getLobbyId(),
                                getLobbyPlayerInformation(message.getLobbyId()),
                                diceResult
                        );

                        return lobbyService.notifyPlayers(message.getLobbyId(), resultMessage, message.getPlayer());
                    } catch (GameException e) {
                        return Uni.createFrom().item(createErrorMessage(e.getMessage()));
                    }
                });
    }

    /**
     * Handles a request to start the game in a lobby. Initializes the game,
     * sets player order, creates the game board, and notifies all players.
     *
     * @param message The {@link MessageDTO} containing the lobby ID.
     * @return A Uni emitting the {@link MessageDTO} confirming the game start.
     * @throws GameException if the game cannot be started (e.g., not enough
     *                       players, game already started).
     */
    private Uni<MessageDTO> handleStartGame(MessageDTO message) throws GameException {
        gameService.startGame(message.getLobbyId(), message.getPlayer());
        ObjectNode payload = getGameBoardInformation(message.getLobbyId());
        MessageDTO response = new MessageDTO(
                MessageType.GAME_STARTED,
                message.getPlayer(),
                message.getLobbyId(),
                getLobbyPlayerInformation(message.getLobbyId()),
                payload);
        return lobbyService.notifyPlayers(message.getLobbyId(), response, message.getPlayer());

    }

    /**
     * Broadcasts a game win message to all players in the lobby. The message
     * includes the winner's username and a leaderboard.
     *
     * @param lobbyId        The ID of the lobby where the game was won.
     * @param winnerPlayerId The ID of the player who won the game.
     * @return A Uni emitting a {@link MessageDTO} of type GAME_WON.
     */
    Uni<MessageDTO> broadcastWin(String lobbyId, String winnerPlayerId) throws GameException {
        ObjectNode message = JsonNodeFactory.instance.objectNode();
        Player winner = playerService.getPlayerById(winnerPlayerId);
        message.put("winner", winner.getUsername());

        var players = getLobbyPlayerInformation(lobbyId);

        //Build leaderboard
        ArrayNode leaderboard = message.putArray("leaderboard");
        players.values().stream()
                .sorted(Comparator.comparingInt(PlayerInfo::victoryPoints).reversed())
                .forEach(leaderboard::addPOJO);

        MessageDTO messageDTO = new MessageDTO(MessageType.GAME_WON, winnerPlayerId, lobbyId, players, message);
        logger.infof("Player %s has won the game in lobby %s", winnerPlayerId, lobbyId);
        return lobbyService.notifyPlayers(lobbyId, messageDTO, winnerPlayerId);

    }

    Uni<MessageDTO> setReady(MessageDTO message) throws GameException {
        logger.infof("Toggle ready state of player: lobbyId = %s, playerId = %s", message.getLobbyId(), message.getPlayer());
        lobbyService.toggleReady(message.getLobbyId(), message.getPlayer());
        var response = new MessageDTO(MessageType.LOBBY_UPDATED, message.getPlayer(), message.getLobbyId(), getLobbyPlayerInformation(message.getLobbyId()));
        return lobbyService.notifyPlayers(message.getLobbyId(), response, message.getPlayer());
    }

    Map<String, PlayerInfo> getLobbyPlayerInformation(String lobbyId) throws GameException {
        return getLobbyPlayerInformation(lobbyService.getLobbyById(lobbyId));
    }

    Map<String, PlayerInfo> getLobbyPlayerInformation(Lobby lobby) {
        return lobby.getPlayers().stream()
                .map(pid -> playerService.getPlayerById(pid))
                .filter(Objects::nonNull)
                .map(player -> playerMapper.toDto(player, lobby))
                .collect(
                        Collectors.toMap(
                                PlayerInfo::id,
                                playerInfo -> playerInfo
                        )
                );
    }

    /**
     * Handles a request from a player to trade resources with the bank. This
     * method now deserializes the message payload into a TradeRequest object
     * before passing it to the TradingService.
     *
     * @param message The {@link MessageDTO} containing trade details.
     * @return A Uni emitting a {@link MessageDTO} of type
     * {@link MessageType#PLAYER_RESOURCE_UPDATE} containing the updated player
     * information, broadcast to all players in the lobby.
     * @throws GameException if the trade is invalid (e.g., bad format, not
     *                       player's turn, insufficient resources, or other issues from
     *                       {@link TradingService}).
     */
    Uni<MessageDTO> handleTradeWithBank(MessageDTO message) throws GameException {
        // Check if player is active player -> else can't trade
        lobbyService.checkPlayerTurn(message.getLobbyId(), message.getPlayer());

        TradeRequest tradeRequest;
        try {
            // Deserialize the JSON payload into the TradeRequest record.
            tradeRequest = objectMapper.treeToValue(message.getMessage(), TradeRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            logger.errorf("Failed to parse trade request: %s", e.getMessage());
            throw new GameException("Invalid trade request format.");
        }

        // Try to trade with the clean TradeRequest object -> if not successful GameException
        tradingService.handleBankTradeRequest(message.getPlayer(), tradeRequest);

        // Trade successful, get updated player information (which includes resources)
        Map<String, PlayerInfo> updatedPlayerInfos = getLobbyPlayerInformation(message.getLobbyId());

        MessageDTO updateResponse = new MessageDTO(
                MessageType.PLAYER_RESOURCE_UPDATE,
                message.getPlayer(),
                message.getLobbyId(),
                updatedPlayerInfos
        );

        logger.infof("Player %s completed trade with bank in lobby %s. Broadcasting PLAYER_RESOURCE_UPDATE.", message.getPlayer(), message.getLobbyId());

        // Notify all players in the lobby about the new Resource Distribution
        return lobbyService.notifyPlayers(message.getLobbyId(), updateResponse, message.getPlayer())
                .chain(() -> Uni.createFrom().item(updateResponse));
    }

    Uni<MessageDTO> handleCheatAttempt(MessageDTO message) {
        try {
            String lobbyId = message.getLobbyId();
            String playerId = message.getPlayer();
            String resourceStr = message.getMessageNode("resource").asText();
            TileType resource = TileType.valueOf(resourceStr);

            gameService.handleCheat(lobbyId, playerId, resource);

            MessageDTO update = new MessageDTO(
                    MessageType.PLAYER_RESOURCE_UPDATE,
                    playerId,
                    lobbyId,
                    getLobbyPlayerInformation(lobbyId)
            );

            return lobbyService.notifyPlayers(lobbyId, update, playerId)
                    .chain(() -> Uni.createFrom().item(update));
        } catch (GameException e) {
            return Uni.createFrom().item(createErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(createErrorMessage("Invalid resource type"));
        }
    }

    Uni<MessageDTO> handleReportPlayer(MessageDTO message) {
        try {
            String lobbyId = message.getLobbyId();
            String reporterId = message.getPlayer();
            String reportedId = message.getMessageNode("reportedId").asText();

            ReportOutcome outcome = gameService.handleReportPlayer(lobbyId, reporterId, reportedId);

            String reportedUsername = playerService.getPlayerById(reportedId).getUsername();

            ObjectNode alertPayload = JsonNodeFactory.instance.objectNode();
            switch (outcome) {
                case CORRECT_REPORT_NEW -> {
                    alertPayload.put(MESSAGE, reportedUsername + " got caught cheating!");
                    alertPayload.put(SEVERITY, SUCCESS);
                }
                case CORRECT_REPORT_ALREADY_CAUGHT -> {
                    alertPayload.put(MESSAGE, reportedUsername + " was already caught cheating! You lost 1 resource.");
                    alertPayload.put(SEVERITY, ERROR);
                }
                case FALSE_REPORT -> {
                    alertPayload.put(MESSAGE, "You falsely accused " + reportedUsername + " of cheating! You lost 1 resource.");
                    alertPayload.put(SEVERITY, ERROR);
                }
            }

            MessageDTO alert = new MessageDTO(
                    MessageType.ALERT,
                    reporterId,
                    lobbyId,
                    alertPayload
            );

            Uni<Void> privateAlert = playerService.sendMessageToPlayer(reporterId, alert);

            return privateAlert.chain(() -> {
                try {
                    MessageDTO update = new MessageDTO(
                            MessageType.PLAYER_RESOURCE_UPDATE,
                            reporterId,
                            lobbyId,
                            getLobbyPlayerInformation(lobbyId)
                    );
                    return lobbyService.notifyPlayers(lobbyId, update, reporterId)
                            .chain(() -> Uni.createFrom().item(update));
                } catch (GameException e) {
                    return Uni.createFrom().item(createErrorMessage(e.getMessage()));
                }
            });

        } catch (GameException e) {
            return Uni.createFrom().item(createErrorMessage(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return Uni.createFrom().item(createErrorMessage("Invalid player to report."));
        }
    }

}
