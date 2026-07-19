package com.example.cataniaunited.api;

import com.example.cataniaunited.dto.LobbyInfo;
import com.example.cataniaunited.dto.MessageDTO;
import com.example.cataniaunited.dto.MessageType;
import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.game.GameService;
import com.example.cataniaunited.game.board.BuildingSite;
import com.example.cataniaunited.game.board.GameBoard;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.game.buildings.City;
import com.example.cataniaunited.game.buildings.Settlement;
import com.example.cataniaunited.game.trade.PlayerTradeRequest;
import com.example.cataniaunited.game.trade.TradingService;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.lobby.LobbyService;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import com.example.cataniaunited.player.PlayerService;
import com.example.cataniaunited.util.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.quarkus.websockets.next.BasicWebSocketConnector;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class GameWebSocketTest {

    @TestHTTPResource
    URI serverUri;

    @Inject
    OpenConnections connections;

    @InjectSpy
    GameMessageHandler gameMessageHandler;

    @InjectSpy
    PlayerService playerService;

    @InjectSpy
    LobbyService lobbyService;

    @InjectSpy
    GameService gameService;

    @InjectSpy
    TradingService tradingService;

    ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        objectMapper = new ObjectMapper();
        lobbyService.clearLobbies();
    }

    @Test
    void testWebSocketOnOpen() throws InterruptedException, JsonProcessingException {
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(1);
        var openConnections = connections.listAll().size();
        BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            receivedMessages.add(message);
            messageLatch.countDown();
        }).connectAndAwait();

        // Wait up to 5 seconds for both messages to arrive
        boolean allMessagesReceived = messageLatch.await(5, TimeUnit.SECONDS);

        assertTrue(allMessagesReceived, "Not all messages were received in time!");

        assertEquals(openConnections + 1, connections.listAll().size());
        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);

        assertEquals(MessageType.CONNECTION_SUCCESSFUL, responseMessage.getType());
        assertNotNull(responseMessage.getMessageNode("playerId").textValue());
        verify(playerService).addPlayer(any());
    }

    @Test
    void onCloseShouldSendLobbyClosedMessageIfHostPlayerLeaves() throws InterruptedException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player2ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player2NotificationLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);


        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_CLOSED) {
                            player2NotificationLatch.countDown();
                            player2ReceivedMessages.add(dto);
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);


        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);

        client1WebSocketClientConnection.close().await().indefinitely();

        assertTrue(player2NotificationLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player2ReceivedMessages.size());

        var sendResponse = player2ReceivedMessages.getFirst();
        assertEquals(MessageType.LOBBY_CLOSED, sendResponse.getType());
        assertEquals(player1ActualId, sendResponse.getPlayer());
        assertEquals(actualLobbyId, sendResponse.getLobbyId());
        assertEquals(1, sendResponse.getPlayers().size());

        verify(lobbyService).removeLobby(actualLobbyId);
    }

    @Test
    void onCloseShouldSendLobbyUpdatedMessageIfPlayerLeaves() throws InterruptedException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player2ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player2NotificationLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);


        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_UPDATED) {
                            player2NotificationLatch.countDown();
                            player2ReceivedMessages.add(dto);
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);


        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player2ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player1ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);

        client1WebSocketClientConnection.close().await().indefinitely();

        assertTrue(player2NotificationLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player2ReceivedMessages.size());

        var sendResponse = player2ReceivedMessages.getFirst();
        assertEquals(MessageType.LOBBY_UPDATED, sendResponse.getType());
        assertEquals(player1ActualId, sendResponse.getPlayer());
        assertEquals(actualLobbyId, sendResponse.getLobbyId());
        assertEquals(1, sendResponse.getPlayers().size());
    }

    @Test
    void testInvalidCommand() throws InterruptedException, JsonProcessingException {
        var unknownMessageDto = new MessageDTO();
        unknownMessageDto.setPlayer("Player 1");
        unknownMessageDto.setType(MessageType.ERROR);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);

        var webSocketClientConnection = BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                receivedMessages.add(message);
                messageLatch.countDown();
            }
        }).connectAndAwait();

        String sentMessage = objectMapper.writeValueAsString(unknownMessageDto);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        boolean allMessagesReceived = messageLatch.await(5, TimeUnit.SECONDS);

        assertTrue(allMessagesReceived, "Not all messages were received in time!");
        assertEquals(2, receivedMessages.size());

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);

        assertEquals(MessageType.ERROR, responseMessage.getType());
        assertEquals("Invalid client command", responseMessage.getMessageNode("error").textValue());
    }

    @Test
    void testInvalidClientMessage() throws InterruptedException, JsonProcessingException {
        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);

        var webSocketClientConnection = BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                receivedMessages.add(message);
                messageLatch.countDown();
            }
        }).connectAndAwait();

        String sentMessage = objectMapper.createObjectNode().put("type", "INVALID").toString();
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        boolean allMessagesReceived = messageLatch.await(5, TimeUnit.SECONDS);

        assertTrue(allMessagesReceived, "Not all messages were received in time!");
        assertEquals(2, receivedMessages.size());

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);

        assertEquals(MessageType.ERROR, responseMessage.getType());
        assertEquals("Unexpected error", responseMessage.getMessageNode("error").textValue());
    }

    @Test
    void testSetUsernameCode() throws InterruptedException, JsonProcessingException {
        //Receiving two messages, since change is broadcast as well as returned directly
        CountDownLatch latch = new CountDownLatch(2);
        List<String> receivedMessages = new CopyOnWriteArrayList<>();

        Player player = new Player("Player 1");
        when(playerService.getPlayerById(player.getUniqueId())).thenReturn(player);
        when(playerService.getAllPlayers()).thenReturn(List.of(player));
        String lobbyId = lobbyService.createLobby("HostPlayer");
        lobbyService.joinLobbyByCode(lobbyId, player.getUniqueId());

        var client = BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                receivedMessages.add(message);
                latch.countDown();
            }
        }).connectAndAwait();

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("username", "Chicken");
        MessageDTO setUsernameMsg = new MessageDTO(MessageType.SET_USERNAME, player.getUniqueId(), lobbyId, payload);
        client.sendTextAndAwait(setUsernameMsg);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Did not receive LOBBY_UPDATED in time");

        MessageDTO received = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);
        assertEquals(MessageType.LOBBY_UPDATED, received.getType());
        assertNotNull(received.getPlayers());
        assertEquals("Chicken", received.getPlayers().get(player.getUniqueId()).username());

    }

    @Test
    void testSetUsernameOfNonExistingPlayer() throws Exception {
        String invalidId = "InvalidId";
        String newUsername = "Chicken";
        String expectedErrorMessage = "Player with id %s not found".formatted(invalidId);

        // We expect one CONNECTION_SUCCESSFUL and one ERROR message
        CountDownLatch connectionLatch = new CountDownLatch(1);
        CountDownLatch errorLatch = new CountDownLatch(1);
        List<MessageDTO> receivedErrorMessages = new CopyOnWriteArrayList<>(); // Store only error messages

        doReturn(null).when(playerService).getPlayerByConnection(any(WebSocketConnection.class));

        var client = BasicWebSocketConnector.create().baseUri(serverUri).path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                System.out.println("Test Client: Received CONNECTION_SUCCESSFUL");
                                connectionLatch.countDown();
                            } else if (dto.getType() == MessageType.ERROR) {
                                System.out.println("Test Client: Received ERROR: " + dto.getMessageNode("error").asText());
                                receivedErrorMessages.add(dto);
                                errorLatch.countDown();
                            } else {
                                System.out.println("Test Client: Received unexpected message type: " + dto.getType());
                            }
                        } catch (JsonProcessingException e) {
                            fail("Test Client: Failed to parse message: " + message, e);
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Did not receive CONNECTION_SUCCESSFUL message");

        // Now send the SET_USERNAME message
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("username", newUsername);
        MessageDTO setUsernameMsg = new MessageDTO(MessageType.SET_USERNAME, invalidId, null, payload);
        System.out.println("Test Client: Sending SET_USERNAME with username: " + newUsername);
        client.sendTextAndAwait(setUsernameMsg);

        // Wait for the ERROR message
        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Did not receive ERROR message after SET_USERNAME");

        assertEquals(1, receivedErrorMessages.size(), "Should have received exactly one ERROR message DTO.");
        MessageDTO errorDto = receivedErrorMessages.get(0);
        assertEquals(MessageType.ERROR, errorDto.getType());
        assertNotNull(errorDto.getMessage());
        assertEquals(expectedErrorMessage, errorDto.getMessageNode("error").textValue());

        verify(playerService, times(1)).getPlayerById(invalidId);

        client.closeAndAwait();
    }

    @Test
    void testJoinLobbySuccess() throws InterruptedException, JsonProcessingException {
        MessageDTO joinLobbyMessage = new MessageDTO(MessageType.JOIN_LOBBY, "Player 1", "abc123");

        doReturn(true).when(lobbyService).joinLobbyByCode("abc123", "Player 1");
        CountDownLatch latch = new CountDownLatch(1);
        BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                latch.countDown();
            }
        }).connectAndAwait().sendTextAndAwait(objectMapper.writeValueAsString(joinLobbyMessage));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Did not receive PLAYER_JOINED message in time");
    }

    @Test
    void testPlayerJoinedLobbySuccess() throws JsonProcessingException, InterruptedException, GameException {
        Player hostPlayer = new Player("HostPlayer");
        when(playerService.getPlayerById(hostPlayer.getUniqueId())).thenReturn(hostPlayer);
        String lobbyId = lobbyService.createLobby(hostPlayer.getUniqueId());

        List<MessageDTO> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);
        CountDownLatch connectionLatch = new CountDownLatch(1);
        List<String> playerIds = new ArrayList<>();

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                System.out.println("Test Client: Received CONNECTION_SUCCESSFUL");
                                playerIds.add(dto.getMessageNode("playerId").asText());
                                receivedMessages.add(dto);
                                messageLatch.countDown();
                                connectionLatch.countDown();
                            } else {
                                receivedMessages.add(dto);
                                messageLatch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Test Client: Failed to parse message: " + message, e);
                        }
                    }
                })
                .connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Did not receive CONNECTION_SUCCESSFUL");
        String playerId = playerIds.get(0);
        Player joiningPlayer = mock(Player.class);
        when(joiningPlayer.getUniqueId()).thenReturn(playerId);
        when(joiningPlayer.getUsername()).thenReturn("JoiningPlayer");
        when(playerService.getPlayerById(playerId)).thenReturn(joiningPlayer);
        MessageDTO joinLobbyMessage = new MessageDTO(MessageType.JOIN_LOBBY, playerId, lobbyId);

        String sentMessage = objectMapper.writeValueAsString(joinLobbyMessage);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Not all messages were received in time!");
        assertEquals(2, receivedMessages.size());

        MessageDTO responseMessage = receivedMessages.stream().filter(m -> m.getType() == MessageType.PLAYER_JOINED).findFirst().get();
        assertNotNull(responseMessage);

        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        Player player = playerService.getPlayerById(playerId);
        assertEquals(MessageType.PLAYER_JOINED, responseMessage.getType());
        assertEquals(player.getUniqueId(), responseMessage.getPlayer());
        assertEquals(lobbyId, responseMessage.getLobbyId());
        assertTrue(lobby.getPlayers().contains(player.getUniqueId()));
        assertEquals(lobby.getPlayerColor(player.getUniqueId()).getHexCode(), responseMessage.getMessageNode("color").asText());
    }

    @Test
    void testJoinLobbyFailure() throws InterruptedException, JsonProcessingException {
        MessageDTO joinLobbyMessage = new MessageDTO(MessageType.JOIN_LOBBY, "Player 1", "invalidLobbyId");

        doReturn(false).when(lobbyService).joinLobbyByCode("invalidLobbyId", "Player 1");
        CountDownLatch latch = new CountDownLatch(1);
        BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                latch.countDown();
            }
        }).connectAndAwait().sendTextAndAwait(objectMapper.writeValueAsString(joinLobbyMessage));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Did not receive ERROR message in time");

    }

    @ParameterizedTest
    @MethodSource("invalidPlaceSettlementMessageNodes")
    void placementOfSettlementShouldFailForInvalidMessageNode(ObjectNode placeSettlementMessageNode) throws GameException, JsonProcessingException, InterruptedException {
        //Setup Players, Lobby and Gameboard
        String player1 = "Player1";
        String player2 = "Player2";
        String lobbyId = lobbyService.createLobby(player1);
        lobbyService.joinLobbyByCode(lobbyId, player2);
        gameService.createGameboard(lobbyId);

        //Create message DTO
        var placeSettlementMessageDTO = new MessageDTO(MessageType.PLACE_SETTLEMENT, player2, lobbyId, placeSettlementMessageNode);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);

        var webSocketClientConnection = BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                receivedMessages.add(message);
                messageLatch.countDown();
            }
        }).connectAndAwait();

        String sentMessage = objectMapper.writeValueAsString(placeSettlementMessageDTO);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        boolean allMessagesReceived = messageLatch.await(5, TimeUnit.SECONDS);

        assertTrue(allMessagesReceived, "Not all messages were received in time!");

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);
        assertEquals(MessageType.ERROR, responseMessage.getType());
        assertEquals("Invalid settlement position id: id = %s".formatted(placeSettlementMessageDTO.getMessageNode("settlementPositionId").toString()), responseMessage.getMessageNode("error").textValue());

        verify(gameService, never()).placeSettlement(anyString(), anyString(), anyInt());
    }

    @Test
    void placeSettlementShouldTriggerBroadcastWinIfPlayerWins() throws Exception {
        ObjectMapper localObjectMapper = new ObjectMapper();
        List<String> messages = new CopyOnWriteArrayList<>();

        CountDownLatch connectionLatch1 = new CountDownLatch(1);
        CountDownLatch connectionLatch2 = new CountDownLatch(1);
        CountDownLatch gameLatch = new CountDownLatch(2);

        final String[] player1IdHolder = new String[1];
        var client1 = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    if (msg.startsWith("{")) {
                        messages.add(msg);
                        try {
                            MessageDTO dto = localObjectMapper.readValue(msg, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                player1IdHolder[0] = dto.getMessageNode("playerId").asText();
                                connectionLatch1.countDown();
                            } else {
                                gameLatch.countDown();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse WebSocket message", e);
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch1.await(5, TimeUnit.SECONDS), "Did not receive player1 connection message");
        String player1Id = player1IdHolder[0];
        assertNotNull(player1Id, "Failed to capture playerId for winning player");

        final String[] player2IdHolder = new String[1];
        BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    if (msg.startsWith("{")) {
                        try {
                            MessageDTO dto = localObjectMapper.readValue(msg, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                player2IdHolder[0] = dto.getMessageNode("playerId").asText();
                                connectionLatch2.countDown();
                            } else {
                                gameLatch.countDown();
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to parse WebSocket message", e);
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch2.await(5, TimeUnit.SECONDS), "Did not receive player2 connection message");
        String player2Id = player2IdHolder[0];
        assertNotNull(player2Id, "Failed to capture playerId for dummy player");

        String lobbyId = lobbyService.createLobby(player1Id);
        lobbyService.joinLobbyByCode(lobbyId, player2Id);

        GameBoard board = gameService.createGameboard(lobbyId);
        int settlementId = board.getBuildingSitePositionGraph().get(0).getId();
        var playerMock = mock(Player.class);
        when(playerMock.getUsername()).thenReturn("Player 1");
        when(playerMock.getUniqueId()).thenReturn(player1Id);
        when(playerMock.getResourceCount(any(TileType.class))).thenReturn(10);
        board.getBuildingSitePositionGraph().get(0).getRoads().get(0).setOwner(playerMock);
        lobbyService.getLobbyById(lobbyId).setActivePlayer(player1Id);

        doReturn(playerMock).when(playerService).getPlayerById(player1Id);
        doReturn(true).when(lobbyService).checkForWin(lobbyId, player1Id);

        ObjectNode msgNode = JsonNodeFactory.instance.objectNode().put("settlementPositionId", settlementId);
        MessageDTO msg = new MessageDTO(MessageType.PLACE_SETTLEMENT, player1Id, lobbyId, msgNode);
        client1.sendTextAndAwait(localObjectMapper.writeValueAsString(msg));

        assertTrue(gameLatch.await(5, TimeUnit.SECONDS), "Expected game messages were not received");

        MessageDTO response = messages.stream()
                .map(m -> {
                    try {
                        return localObjectMapper.readValue(m, MessageDTO.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(m -> m != null && m.getType() == MessageType.GAME_WON)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No GAME_WON message received"));

        assertEquals(MessageType.GAME_WON, response.getType());
        assertEquals(player1Id, response.getPlayer());

        assertEquals(playerMock.getUsername(), response.getMessageNode("winner").asText());

        verify(gameMessageHandler).broadcastWin(lobbyId, player1Id);
    }

    @Test
    void testPlaceSettlementIncludesAllPlayersInResponse() throws GameException, JsonProcessingException, InterruptedException {
        String player1 = "Player1";
        String player2 = "Player2";
        String player3 = "Player3";
        String lobbyId = lobbyService.createLobby(player1);
        lobbyService.joinLobbyByCode(lobbyId, player2);
        lobbyService.joinLobbyByCode(lobbyId, player3);

        Player mockPlayer1 = mock(Player.class);
        when(mockPlayer1.getUniqueId()).thenReturn(player1);
        when(mockPlayer1.getUsername()).thenReturn(player1);
        when(mockPlayer1.getResources()).thenReturn(new EnumMap<>(TileType.class));
        when(mockPlayer1.getResourceCount(any(TileType.class))).thenReturn(10);
        when(playerService.getPlayerById(player1)).thenReturn(mockPlayer1);

        Player mockPlayer2 = mock(Player.class);
        when(mockPlayer2.getUniqueId()).thenReturn(player2);
        when(mockPlayer2.getUsername()).thenReturn(player2);
        when(mockPlayer2.getResources()).thenReturn(new EnumMap<>(TileType.class));
        when(mockPlayer2.getResourceCount(any(TileType.class))).thenReturn(10);
        when(playerService.getPlayerById(player2)).thenReturn(mockPlayer2);

        Player mockPlayer3 = mock(Player.class);
        when(mockPlayer3.getUniqueId()).thenReturn(player3);
        when(mockPlayer3.getUsername()).thenReturn(player3);
        when(mockPlayer3.getResources()).thenReturn(new EnumMap<>(TileType.class));
        when(mockPlayer3.getResourceCount(any(TileType.class))).thenReturn(10);
        when(playerService.getPlayerById(player3)).thenReturn(mockPlayer3);

        lobbyService.toggleReady(lobbyId, player1);
        lobbyService.toggleReady(lobbyId, player2);
        lobbyService.toggleReady(lobbyId, player3);

        gameService.startGame(lobbyId, player1);

        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setActivePlayer(player1);
        GameBoard gameBoard = gameService.getGameboardByLobbyId(lobbyId);
        BuildingSite buildingSite = gameBoard.getBuildingSitePositionGraph().get(0);
        buildingSite.getRoads().get(0).setOwner(mockPlayer1);

        int positionId = buildingSite.getId();
        ObjectNode placeSettlementMessageNode = objectMapper.createObjectNode().put("settlementPositionId", positionId);
        var placeSettlementMessageDTO = new MessageDTO(MessageType.PLACE_SETTLEMENT, player1, lobbyId, placeSettlementMessageNode);

        when(playerService.getPlayerById(player1)).thenReturn(mockPlayer1);
        when(playerService.getPlayerById(player2)).thenReturn(mockPlayer2);
        when(playerService.getPlayerById(player3)).thenReturn(mockPlayer3);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    receivedMessages.add(message);
                    messageLatch.countDown();
                })
                .connectAndAwait();

        String sentMessage = objectMapper.writeValueAsString(placeSettlementMessageDTO);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Message not received in time");

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);

        var playerInfo = responseMessage.getPlayers();
        assertNotNull(playerInfo, "Players node missing from message payload");

        assertTrue(playerInfo.containsKey(player1), "Missing player1 in response");
        assertTrue(playerInfo.containsKey(player2), "Missing player2 in response");
        assertTrue(playerInfo.containsKey(player3), "Missing player3 in response");

        assertEquals(player1, playerInfo.get(player1).username());
        assertEquals(player2, playerInfo.get(player2).username());
        assertEquals(player3, playerInfo.get(player3).username());
    }

    @Test
    void testUpgradeOfSettlement() throws GameException, JsonProcessingException, InterruptedException {
        Player player = new Player("Player1");
        String playerId = player.getUniqueId();
        when(playerService.getPlayerById(playerId)).thenReturn(player);

        String lobbyId = lobbyService.createLobby(playerId);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        doReturn(2).when(lobbyService).getRoundsPlayed(lobbyId);
        lobby.toggleReady(playerId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(3);

        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(1);

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                                connectionLatch.countDown();
                                messageLatch.countDown();
                            } else if (dto.getType() == MessageType.UPGRADE_SETTLEMENT) {
                                receivedMessages.add(message);
                                messageLatch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message");
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        lobbyService.joinLobbyByCode(lobbyId, actualPlayerIds.get(0));
        lobbyService.toggleReady(lobbyId, actualPlayerIds.get(0));
        gameService.startGame(lobbyId, lobby.getHostPlayer());
        lobby.setPlayerOrder(List.of(playerId, lobby.getHostPlayer()));
        player.receiveResource(TileType.WHEAT, 2);
        player.receiveResource(TileType.ORE, 3);
        lobby.setActivePlayer(playerId);
        GameBoard gameBoard = gameService.getGameboardByLobbyId(lobbyId);
        BuildingSite buildingSite = gameBoard.getBuildingSitePositionGraph().get(0);
        buildingSite.getRoads().get(0).setOwner(player);
        buildingSite.setBuilding(new Settlement(player, lobby.getPlayerColor(playerId)));
        int positionId = buildingSite.getId();
        ObjectNode placeSettlementMessageNode = objectMapper.createObjectNode().put("settlementPositionId", positionId);
        var upgradeSettlementMessageDTO = new MessageDTO(MessageType.UPGRADE_SETTLEMENT, playerId, lobbyId, placeSettlementMessageNode);

        String sentMessage = objectMapper.writeValueAsString(upgradeSettlementMessageDTO);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        assertTrue(messageLatch.await(5, TimeUnit.SECONDS), "Message not received in time");

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);
        assertEquals(MessageType.UPGRADE_SETTLEMENT, responseMessage.getType());
        assertEquals(playerId, responseMessage.getPlayer());
        assertEquals(lobbyId, responseMessage.getLobbyId());

        var building = gameService.getGameboardByLobbyId(lobbyId).getJson().get("settlementPositions").get(0).get("building");
        assertEquals(player, buildingSite.getBuildingOwner());
        assertEquals(City.class.getSimpleName(), building.get("type").asText());
        verify(gameService).upgradeSettlement(lobbyId, playerId, buildingSite.getId());
    }

    @Test
    void testPlacementOfRoad() throws Exception {
        String player1 = "Player1";
        String player2 = "Player2";

        Player mockPlayer2 = mock(Player.class);
        when(mockPlayer2.getUniqueId()).thenReturn(player2);
        when(mockPlayer2.getUsername()).thenReturn(player2);
        when(mockPlayer2.getResources()).thenReturn(new EnumMap<>(TileType.class));
        when(mockPlayer2.getResourceCount(any(TileType.class))).thenReturn(10);
        when(playerService.getPlayerById(player2)).thenReturn(mockPlayer2);

        String lobbyId = lobbyService.createLobby(player1);
        lobbyService.joinLobbyByCode(lobbyId, player2);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setActivePlayer(player2);

        GameBoard gameBoard = gameService.createGameboard(lobbyId);
        int roadId = gameBoard.getRoadList().get(0).getId();
        doReturn(gameBoard).when(gameService).getGameboardByLobbyId(lobbyId);

        ObjectNode mergedBoardJson = objectMapper.createObjectNode().put("merged", "gameData");
        doReturn(mergedBoardJson).when(gameMessageHandler).getGameBoardInformation(lobbyId);

        ObjectNode placeRoadMessageNode = objectMapper.createObjectNode().put("roadId", roadId);
        MessageDTO placeRoadMessageDTO = new MessageDTO(MessageType.PLACE_ROAD, player2, lobbyId, placeRoadMessageNode);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3); // Expect CONNECTION_SUCCESSFUL, PLAYER_RESOURCES, PLACE_ROAD

        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(1);

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                                connectionLatch.countDown();
                                latch.countDown();
                            } else {
                                receivedMessages.add(message);
                                latch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message");
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        lobbyService.joinLobbyByCode(lobbyId, actualPlayerIds.get(0));

        webSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(placeRoadMessageDTO));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all expected messages were received");

        MessageDTO placeRoadResponse = receivedMessages.stream()
                .map(msg -> {
                    try {
                        return objectMapper.readValue(msg, MessageDTO.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(dto -> dto != null && dto.getType() == MessageType.PLACE_ROAD)
                .findFirst()
                .orElseThrow(() -> new AssertionError("PLACE_ROAD message not received"));

        assertEquals(player2, placeRoadResponse.getPlayer());
        assertEquals(lobbyId, placeRoadResponse.getLobbyId());
        assertNotNull(placeRoadResponse.getMessage());
        assertTrue(placeRoadResponse.getMessage().has("merged"), "Expected 'merged' field in message payload");

        var actualRoad = gameService.getGameboardByLobbyId(lobbyId).getRoadList().get(0);
        assertEquals(mockPlayer2, actualRoad.getOwner());

        verify(gameService).placeRoad(lobbyId, player2, roadId);
        verify(playerService, times(2)).getPlayerById(player2);
        verify(gameMessageHandler).getGameBoardInformation(lobbyId);
        verify(gameService, times(2)).getGameboardByLobbyId(lobbyId);
    }

    @Test
    void testPlacementOfRoadChecksForWin() throws Exception {
        String player1 = "Player1";
        String player2 = "Player2";

        Player mockPlayer2 = mock(Player.class);
        when(mockPlayer2.getUniqueId()).thenReturn(player2);
        when(mockPlayer2.getUsername()).thenReturn(player2);
        when(mockPlayer2.getResources()).thenReturn(new EnumMap<>(TileType.class));
        when(mockPlayer2.getResourceCount(any(TileType.class))).thenReturn(10);
        doReturn(mockPlayer2).when(playerService).getPlayerById(player2);

        doReturn(true).when(lobbyService).checkForWin(anyString(), eq(player2));

        String lobbyId = lobbyService.createLobby(player1);
        lobbyService.joinLobbyByCode(lobbyId, player2);
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setActivePlayer(player2);

        GameBoard gameBoard = gameService.createGameboard(lobbyId);
        int roadId = gameBoard.getRoadList().get(0).getId();
        doReturn(gameBoard).when(gameService).getGameboardByLobbyId(lobbyId);

        ObjectNode mergedBoardJson = objectMapper.createObjectNode().put("merged", "gameData");
        doReturn(mergedBoardJson).when(gameMessageHandler).getGameBoardInformation(lobbyId);

        ObjectNode placeRoadMessageNode = objectMapper.createObjectNode().put("roadId", roadId);
        MessageDTO placeRoadMessageDTO = new MessageDTO(MessageType.PLACE_ROAD, player2, lobbyId, placeRoadMessageNode);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3); // Expect CONNECTION_SUCCESSFUL, PLAYER_RESOURCES, GAME_WON

        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(1);

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                                connectionLatch.countDown();
                                latch.countDown();
                            } else {
                                receivedMessages.add(message);
                                latch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message");
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        lobbyService.joinLobbyByCode(lobbyId, actualPlayerIds.get(0));

        webSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(placeRoadMessageDTO));

        assertTrue(latch.await(5, TimeUnit.SECONDS), "Not all expected messages were received");

        MessageDTO gameWonResponse = receivedMessages.stream()
                .map(msg -> {
                    try {
                        return objectMapper.readValue(msg, MessageDTO.class);
                    } catch (JsonProcessingException e) {
                        return null;
                    }
                })
                .filter(dto -> dto != null && dto.getType() == MessageType.GAME_WON)
                .findFirst()
                .orElseThrow(() -> new AssertionError("GAME_WON message not received"));

        assertEquals(player2, gameWonResponse.getPlayer());
        assertEquals(lobbyId, gameWonResponse.getLobbyId());
        assertNotNull(gameWonResponse.getMessage());
        assertTrue(gameWonResponse.getMessage().has("leaderboard"), "Expected 'leaderboard' field in message payload");

        var actualRoad = gameService.getGameboardByLobbyId(lobbyId).getRoadList().get(0);
        assertEquals(mockPlayer2, actualRoad.getOwner());

        verify(gameService).placeRoad(lobbyId, player2, roadId);
        verify(playerService, times(3)).getPlayerById(player2);
        verify(lobbyService).checkForWin(lobbyId, player2);
        verify(gameMessageHandler, never()).getGameBoardInformation(lobbyId);
        verify(gameService, times(2)).getGameboardByLobbyId(lobbyId);
    }

    @ParameterizedTest
    @MethodSource("invalidPlaceRoadMessageNodes")
    void placementOfRoadShouldFailForInvalidMessageNode(ObjectNode placeRoadMessageNode) throws GameException, JsonProcessingException, InterruptedException {
        //Setup Players, Lobby and Gameboard
        String player1 = "Player1";
        String player2 = "Player2";
        String lobbyId = lobbyService.createLobby(player1);
        lobbyService.joinLobbyByCode(lobbyId, player2);
        gameService.createGameboard(lobbyId);
        //Create message DTO
        var placeRoadMessageDTO = new MessageDTO(MessageType.PLACE_ROAD, player2, lobbyId, placeRoadMessageNode);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch messageLatch = new CountDownLatch(2);

        var webSocketClientConnection = BasicWebSocketConnector.create().baseUri(serverUri).path("/game").onTextMessage((connection, message) -> {
            if (message.startsWith("{")) {
                receivedMessages.add(message);
                messageLatch.countDown();
            }
        }).connectAndAwait();

        String sentMessage = objectMapper.writeValueAsString(placeRoadMessageDTO);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        boolean allMessagesReceived = messageLatch.await(5, TimeUnit.SECONDS);

        assertTrue(allMessagesReceived, "Not all messages were received in time!");

        MessageDTO responseMessage = objectMapper.readValue(receivedMessages.get(receivedMessages.size() - 1), MessageDTO.class);
        assertEquals(MessageType.ERROR, responseMessage.getType());
        assertEquals("Invalid road id: id = %s".formatted(placeRoadMessageDTO.getMessageNode("roadId")), responseMessage.getMessageNode("error").textValue());

        verify(gameService, never()).placeSettlement(anyString(), anyString(), anyInt());
    }

    static Stream<Arguments> invalidPlaceSettlementMessageNodes() {
        return Stream.of(Arguments.of(JsonNodeFactory.instance.objectNode().put("settlementPositionId", "NoInteger")), Arguments.of(JsonNodeFactory.instance.objectNode().put("roadId", "1")));
    }

    static Stream<Arguments> invalidPlaceRoadMessageNodes() {
        return Stream.of(Arguments.of(JsonNodeFactory.instance.objectNode().put("roadId", "NoInteger")), Arguments.of(JsonNodeFactory.instance.objectNode().put("settlementPositionId", "1")));
    }

    @Test
    void testPlayerColorIsIncludedInGameBoardJson() throws Exception {
        String playerId = "TestPlayer";
        String lobbyId = lobbyService.createLobby(playerId);

        PlayerColor assignedColor = lobbyService.getPlayerColor(lobbyId, playerId);
        assertNotNull(assignedColor);

        Player mockPlayer = mock(Player.class);
        when(mockPlayer.getUniqueId()).thenReturn(playerId);
        ObjectNode playerJson = objectMapper.createObjectNode().put("username", playerId);
        when(playerService.getPlayerById(playerId)).thenReturn(mockPlayer);

        ObjectNode boardJson = objectMapper.createObjectNode().put("hexes", "fake");
        ObjectNode fullJson = objectMapper.createObjectNode();
        fullJson.set("gameboard", boardJson);
        ObjectNode playersNode = fullJson.putObject("players");
        ObjectNode playerData = playerJson.deepCopy();
        playerData.put("color", assignedColor.getHexCode());
        playersNode.set(playerId, playerData);

        doReturn(fullJson).when(gameMessageHandler).getGameBoardInformation(lobbyId);

        ObjectNode result = gameMessageHandler.getGameBoardInformation(lobbyId);

        assertTrue(result.has("gameboard"));
        assertTrue(result.has("players"));

        JsonNode playersJson = result.get("players");
        assertTrue(playersJson.has(playerId));

        JsonNode playerNode = playersJson.get(playerId);
        assertTrue(playerNode.has("color"));
        assertEquals(assignedColor.getHexCode(), playerNode.get("color").asText());
    }

    @Test
    void testHandleDiceRoll() throws GameException, JsonProcessingException, InterruptedException {
        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(1);

        List<MessageDTO> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch diceResultLatch = new CountDownLatch(1);

        var webSocketClientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((connection, message) -> {
                    if (message.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(message, MessageDTO.class);
                            if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                                actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                                connectionLatch.countDown();
                            } else if (dto.getType() == MessageType.DICE_RESULT) {
                                receivedMessages.add(dto);
                                diceResultLatch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message: " + e.getMessage());
                        }
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "WebSocket connection not established");
        assertEquals(1, actualPlayerIds.size());

        String actualPlayerId = actualPlayerIds.get(0);

        String lobbyId = lobbyService.createLobby(actualPlayerId);

        String player2 = "Player2";
        lobbyService.joinLobbyByCode(lobbyId, player2);

        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setActivePlayer(actualPlayerId);
        gameService.createGameboard(lobbyId);

        MessageDTO rollDiceMessageDTO = new MessageDTO(MessageType.ROLL_DICE, actualPlayerId, lobbyId);
        String sentMessage = objectMapper.writeValueAsString(rollDiceMessageDTO);
        webSocketClientConnection.sendTextAndAwait(sentMessage);

        assertTrue(diceResultLatch.await(5, TimeUnit.SECONDS), "Dice result message not received in time!");

        assertFalse(receivedMessages.isEmpty());

        MessageDTO diceResultMessage = receivedMessages.getFirst();

        assertNotNull(diceResultMessage, "Dice result message not found");
        assertEquals(MessageType.DICE_RESULT, diceResultMessage.getType());
        assertEquals(actualPlayerId, diceResultMessage.getPlayer());
        assertEquals(lobbyId, diceResultMessage.getLobbyId());

        JsonNode diceResult = diceResultMessage.getMessage();
        assertNotNull(diceResult);

        assertTrue(diceResult.has("dice1"), "Missing dice1 field");
        assertTrue(diceResult.has("dice2"), "Missing dice2 field");
        assertTrue(diceResult.has("total"), "Missing total field");

        int dice1 = diceResult.get("dice1").asInt();
        int dice2 = diceResult.get("dice2").asInt();
        int total = diceResult.get("total").asInt();

        assertTrue(dice1 >= 1 && dice1 <= 6, "Dice 1 value out of range: " + dice1);
        assertTrue(dice2 >= 1 && dice2 <= 6, "Dice 2 value out of range: " + dice2);
        assertEquals(dice1 + dice2, total, "Total doesn't match sum of dice");

        assertTrue(diceResult.has("rollingUsername"), "Missing rollingUsername field");
        assertTrue(diceResult.has("player"), "Missing player field");
        assertEquals(actualPlayerId, diceResult.get("player").asText());

        verify(gameService).rollDice(lobbyId, actualPlayerId);

        webSocketClientConnection.closeAndAwait();
    }

    @Test
    void testPlaceSettlement_success_noWin() throws Exception {
        Player player = new Player("Player1");
        String playerId = player.getUniqueId();
        doReturn(player).when(playerService).getPlayerById(playerId);

        int settlementPositionId = 5;

        String actualLobbyId = lobbyService.createLobby(playerId);
        assertNotNull(actualLobbyId, "Lobby ID should not be null after creation");

        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setActivePlayer(playerId);

        GameBoard mockGameBoard = mock(GameBoard.class);
        ObjectNode boardStateJson = objectMapper.createObjectNode().put("boardState", "updatedAfterSettlement");

        ObjectNode fullMessage = objectMapper.createObjectNode();
        fullMessage.set("gameboard", boardStateJson);
        fullMessage.set("players", objectMapper.createObjectNode()); // Simulates empty player map

        when(mockGameBoard.getJson()).thenReturn(boardStateJson);
        doReturn(mockGameBoard).when(gameService).getGameboardByLobbyId(actualLobbyId);
        doNothing().when(gameService).placeSettlement(actualLobbyId, playerId, settlementPositionId);
        when(lobbyService.checkForWin(lobby.getLobbyId(), playerId)).thenReturn(false);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch responseLatch = new CountDownLatch(2);

        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(1);

        var clientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.PLACE_SETTLEMENT) {
                            receivedMessages.add(msg);
                            responseLatch.countDown();
                        } else if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Failed to parse message: " + msg, e);
                    }
                }).connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        lobbyService.joinLobbyByCode(actualLobbyId, actualPlayerIds.get(0));

        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("settlementPositionId", settlementPositionId);
        MessageDTO placeSettlementMsg = new MessageDTO(MessageType.PLACE_SETTLEMENT, playerId, actualLobbyId, payload);
        clientConnection.sendTextAndAwait(objectMapper.writeValueAsString(placeSettlementMsg));

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS), "Did not receive PLACE_SETTLEMENT responses in time.");
        assertEquals(2, receivedMessages.size());

        for (String msg : receivedMessages) {
            MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
            assertEquals(MessageType.PLACE_SETTLEMENT, dto.getType());
            assertEquals(playerId, dto.getPlayer());
            assertEquals(actualLobbyId, dto.getLobbyId());

            assertTrue(dto.getMessage().has("gameboard"));
            assertEquals("updatedAfterSettlement", dto.getMessage().get("gameboard").get("boardState").asText());
        }

        verify(gameService).placeSettlement(actualLobbyId, playerId, settlementPositionId);
        verify(lobbyService, atLeastOnce()).checkForWin(actualLobbyId, playerId);
        verify(gameService, times(1)).getGameboardByLobbyId(actualLobbyId);
    }

    @Test
    void testHandleDiceRoll_ResourceDistribution_Success() throws Exception {
        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        List<MessageDTO> player2ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1DiceResultLatch = new CountDownLatch(1);
        CountDownLatch player2DiceResultLatch = new CountDownLatch(1);
        CountDownLatch connectionLatch = new CountDownLatch(2);

        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            player1ReceivedMessages.add(dto);
                            player1DiceResultLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1Connection = client1Connector.connectAndAwait();

        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            player2ReceivedMessages.add(dto);
                            player2DiceResultLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2Connection = client2Connector.connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Not all clients connected and sent CONNECTION_SUCCESSFUL");
        assertEquals(2, actualPlayerIds.size(), "Should have two player IDs");

        String player1ActualId = actualPlayerIds.get(0);
        String player2ActualId = actualPlayerIds.get(1);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        assertNotNull(actualLobbyId, "Lobby ID should not be null after creation");

        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertNotNull(lobby, "Lobby should not be null after retrieval");

        lobbyService.toggleReady(actualLobbyId, player1ActualId);
        lobbyService.toggleReady(actualLobbyId, player2ActualId);

        gameService.startGame(actualLobbyId, player1ActualId);
        lobby.setActivePlayer(player1ActualId);

        MessageDTO rollDiceMsg = new MessageDTO(MessageType.ROLL_DICE, player1ActualId, actualLobbyId);
        client1Connection.sendTextAndAwait(objectMapper.writeValueAsString(rollDiceMsg));

        assertTrue(player2DiceResultLatch.await(10, TimeUnit.SECONDS), "Player 2 did not received DICE_RESULT");
        assertTrue(player1DiceResultLatch.await(10, TimeUnit.SECONDS), "Player 1 did not received DICE_RESULT");

        MessageDTO p1ResMsg = player1ReceivedMessages.get(0);
        assertEquals(player1ActualId, p1ResMsg.getPlayer());
        assertEquals(actualLobbyId, p1ResMsg.getLobbyId());
        assertTrue(p1ResMsg.getPlayers().containsKey(player1ActualId));
        assertTrue(p1ResMsg.getPlayers().get(player1ActualId).resources().containsKey(TileType.CLAY), "Player 1 resource missing in payload");

        MessageDTO p2ResMsg = player2ReceivedMessages.get(0);
        assertEquals(player1ActualId, p2ResMsg.getPlayer());
        assertEquals(actualLobbyId, p2ResMsg.getLobbyId());
        assertTrue(p2ResMsg.getPlayers().containsKey(player2ActualId));
        assertTrue(p2ResMsg.getPlayers().get(player2ActualId).resources().containsKey(TileType.CLAY), "Player 2 resource missing in payload");

        verify(gameService).rollDice(actualLobbyId, player1ActualId);
        verify(playerService, atLeastOnce()).getPlayerById(player1ActualId);
        verify(playerService, atLeastOnce()).getPlayerById(player2ActualId);

        client1Connection.closeAndAwait();
        client2Connection.closeAndAwait();
    }

    @Test
    void testHandleDiceRoll_ResourceDistribution_PlayerConnectionClosed() throws Exception {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1DiceResultLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            System.out.println("Client1 got DICE_RESULT.");
                            player1ReceivedMessages.add(dto);
                            player1DiceResultLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            fail("Client2 got DICE_RESULT.");
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);

        lobbyService.toggleReady(actualLobbyId, player1ActualId);
        lobbyService.toggleReady(actualLobbyId, player2ActualId);

        gameService.startGame(actualLobbyId, player1ActualId);
        System.out.println("Test: Gameboard created for lobby " + actualLobbyId);

        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setActivePlayer(player1ActualId);
        System.out.println("Test: Active player set to " + player1ActualId);

        client2WebSocketClientConnection.closeAndAwait();

        MessageDTO rollDiceMsg = new MessageDTO(MessageType.ROLL_DICE, player1ActualId, actualLobbyId);
        System.out.println("Test: Sending ROLL_DICE from player " + player1ActualId + " for lobby " + actualLobbyId);
        client1WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(rollDiceMsg));

        System.out.println("Test: Awaiting DICE_RESULT for player 1...");
        assertTrue(player1DiceResultLatch.await(3, TimeUnit.SECONDS), "Player 1 did not receive DICE_RESULT. Latch: " + player1DiceResultLatch.getCount());

        verify(gameService).rollDice(actualLobbyId, player1ActualId);
        verify(playerService, atLeastOnce()).getPlayerById(player1ActualId);
        verify(playerService, atLeastOnce()).getPlayerById(player2ActualId);

        System.out.println("Test: Closing client connections...");
        client1WebSocketClientConnection.closeAndAwait();
        System.out.println("Test: Finished.");
    }

    @Test
    void testHandleDiceRoll_ResourceDistribution_OnePlayerSendFails() throws Exception {
        final List<String> actualPlayerIds = new CopyOnWriteArrayList<>();
        CountDownLatch connectionLatch = new CountDownLatch(2);
        CountDownLatch diceResultLatch = new CountDownLatch(2);

        List<MessageDTO> receivedMessages = new CopyOnWriteArrayList<>();

        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            receivedMessages.add(dto);
                            diceResultLatch.countDown();
                        }
                    } catch (Exception e) {
                        fail("Client1 error: " + e.getMessage());
                    }
                });

        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            actualPlayerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.DICE_RESULT) {
                            receivedMessages.add(dto);
                            diceResultLatch.countDown();
                        }
                    } catch (Exception e) {
                        fail("Client2 error: " + e.getMessage());
                    }
                });

        var client1 = client1Connector.connectAndAwait();
        var client2 = client2Connector.connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS), "Connections failed");
        assertEquals(2, actualPlayerIds.size());

        String lobbyId = lobbyService.createLobby(actualPlayerIds.get(0));
        lobbyService.joinLobbyByCode(lobbyId, actualPlayerIds.get(1));
        Lobby lobby = lobbyService.getLobbyById(lobbyId);
        lobby.setActivePlayer(actualPlayerIds.get(0));
        gameService.createGameboard(lobbyId);

        MessageDTO rollMsg = new MessageDTO(MessageType.ROLL_DICE, actualPlayerIds.get(0), lobbyId);
        client1.sendTextAndAwait(objectMapper.writeValueAsString(rollMsg));

        assertTrue(diceResultLatch.await(5, TimeUnit.SECONDS), "Result messages not received");

        assertTrue(receivedMessages.size() > 1);
        MessageDTO result = receivedMessages.get(0);

        assertEquals(MessageType.DICE_RESULT, result.getType());
        assertEquals(actualPlayerIds.get(0), result.getPlayer());
        assertEquals(lobbyId, result.getLobbyId());

        JsonNode diceResult = result.getMessage();
        assertNotNull(diceResult.get("dice1"));
        assertNotNull(diceResult.get("dice2"));
        assertNotNull(diceResult.get("total"));
        assertNotNull(diceResult.get("rollingUsername"));
        assertNotNull(diceResult.get("player"));

        assertNotNull(result.getPlayers());
        assertTrue(result.getPlayers().containsKey(actualPlayerIds.get(0)));
        assertTrue(result.getPlayers().containsKey(actualPlayerIds.get(1)));

        client1.closeAndAwait();
        client2.closeAndAwait();
    }

    @Test
    void testPlaceSettlement_success_playerWins() throws Exception {
        String winnerPlayerId = "playerPSW";
        String loserPlayerId = "playerLose";
        int settlementPositionId = 7;

        String actualLobbyId = lobbyService.createLobby(winnerPlayerId);
        assertNotNull(actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setActivePlayer(winnerPlayerId);

        doNothing().when(gameService).placeSettlement(actualLobbyId, winnerPlayerId, settlementPositionId);
        when(lobbyService.checkForWin(actualLobbyId, winnerPlayerId)).thenReturn(true);

        Player mockPlayer = mock(Player.class);
        when(mockPlayer.getUsername()).thenReturn(winnerPlayerId);
        when(mockPlayer.getUniqueId()).thenReturn(winnerPlayerId);
        when(playerService.getPlayerById(winnerPlayerId)).thenReturn(mockPlayer); // <-- Ensure this is how it's resolved
        lobby.addVictoryPoints(winnerPlayerId, 10);

        Player mockPlayer2 = mock(Player.class);
        when(mockPlayer2.getUsername()).thenReturn(loserPlayerId);
        when(mockPlayer2.getUniqueId()).thenReturn(loserPlayerId);
        when(playerService.getPlayerById(loserPlayerId)).thenReturn(mockPlayer2);
        lobby.addVictoryPoints(loserPlayerId, 8);

        lobbyService.joinLobbyByCode(lobby.getLobbyId(), loserPlayerId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch gameWonLatch = new CountDownLatch(1);

        var clientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.GAME_WON) {
                            receivedMessages.add(msg);
                            gameWonLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Failed to parse message: " + msg, e);
                    }
                })
                .connectAndAwait();

        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("settlementPositionId", settlementPositionId);
        MessageDTO placeSettlementMsg = new MessageDTO(MessageType.PLACE_SETTLEMENT, winnerPlayerId, actualLobbyId, payload);

        clientConnection.sendTextAndAwait(objectMapper.writeValueAsString(placeSettlementMsg));

        assertTrue(gameWonLatch.await(5, TimeUnit.SECONDS),
                "Did not receive GAME_WON response in time. Received: " + receivedMessages.size());
        assertEquals(1, receivedMessages.size());

        for (String msg : receivedMessages) {
            MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
            assertEquals(MessageType.GAME_WON, dto.getType());
            assertEquals(winnerPlayerId, dto.getPlayer());
            assertEquals(actualLobbyId, dto.getLobbyId());
            assertEquals(winnerPlayerId, dto.getMessageNode("winner").asText());
            assertEquals(2, dto.getMessage().withArray("leaderboard").size());

            JsonNode winner = dto.getMessage().withArray("leaderboard").get(0);
            assertEquals(winnerPlayerId, winner.get("id").asText());
            assertEquals(winnerPlayerId, winner.get("username").asText());
            assertEquals(10, winner.get("victoryPoints").asInt());

            JsonNode loser = dto.getMessage().withArray("leaderboard").get(1);
            assertEquals(loserPlayerId, loser.get("id").asText());
            assertEquals(loserPlayerId, loser.get("username").asText());
            assertEquals(8, loser.get("victoryPoints").asInt());
        }

        verify(gameService).placeSettlement(actualLobbyId, winnerPlayerId, settlementPositionId);
        verify(lobbyService, atLeastOnce()).checkForWin(actualLobbyId, winnerPlayerId);
        verify(gameMessageHandler, times(1)).broadcastWin(actualLobbyId, winnerPlayerId);
        verify(gameService, never()).getGameboardByLobbyId(anyString());
    }

    @Test
    void testPlaceSettlement_invalidPositionId_string() throws Exception {
        String playerId = "playerPSE1";

        String actualLobbyId = lobbyService.createLobby(playerId);
        assertNotNull(actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setActivePlayer(playerId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        var clientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.ERROR) {
                            receivedMessages.add(msg);
                            errorLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Failed to parse message: " + msg, e);
                    }
                })
                .connectAndAwait();

        clientConnection.sendTextAndAwait(objectMapper.writeValueAsString(
                new MessageDTO(MessageType.PLACE_SETTLEMENT, playerId, actualLobbyId,
                        JsonNodeFactory.instance.objectNode().put("settlementPositionId", "not-an-integer"))
        ));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Did not receive ERROR response in time.");
        assertEquals(1, receivedMessages.size());

        MessageDTO responseDto = objectMapper.readValue(receivedMessages.get(0), MessageDTO.class);
        assertEquals(MessageType.ERROR, responseDto.getType());
        assertTrue(responseDto.getMessageNode("error").asText().startsWith("Invalid settlement position id: id = "));

        verify(gameService, never()).placeSettlement(anyString(), anyString(), anyInt());
        verify(lobbyService, never()).checkForWin(anyString(), anyString());
        verify(gameService, never()).getGameboardByLobbyId(anyString());
    }

    @Test
    void testPlaceSettlement_gameServicePlaceSettlement_throwsGameException() throws Exception {
        String playerId = "playerPSE2";
        int settlementPositionId = 10;
        String gameServiceErrorMessage = "Cannot place settlement here (GameService error)";

        String actualLobbyId = lobbyService.createLobby(playerId);
        assertNotNull(actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setActivePlayer(playerId);

        doThrow(new GameException(gameServiceErrorMessage))
                .when(gameService).placeSettlement(actualLobbyId, playerId, settlementPositionId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        var clientConnection = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.ERROR) {
                            receivedMessages.add(msg);
                            errorLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Failed to parse message: " + msg, e);
                    }
                })
                .connectAndAwait();

        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("settlementPositionId", settlementPositionId);
        MessageDTO placeSettlementMsg = new MessageDTO(MessageType.PLACE_SETTLEMENT, playerId, actualLobbyId, payload);

        clientConnection.sendTextAndAwait(objectMapper.writeValueAsString(placeSettlementMsg));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Did not receive ERROR response in time.");
        assertEquals(1, receivedMessages.size());

        MessageDTO responseDto = objectMapper.readValue(receivedMessages.get(0), MessageDTO.class);
        assertEquals(MessageType.ERROR, responseDto.getType());
        assertEquals(gameServiceErrorMessage, responseDto.getMessageNode("error").asText());

        verify(gameService).placeSettlement(actualLobbyId, playerId, settlementPositionId);
        verify(lobbyService, never()).checkForWin(anyString(), anyString());
        verify(gameService, never()).getGameboardByLobbyId(anyString());
        verify(gameMessageHandler, never()).broadcastWin(anyString(), anyString());
    }

    @Test
    void testHandleStartGame_simple() throws Exception {
        CopyOnWriteArrayList<MessageDTO> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch connectionLatch = new CountDownLatch(1);

        List<String> playerIds = new ArrayList<>();

        var client = BasicWebSocketConnector
                .create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, text) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(text, MessageDTO.class);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            playerIds.add(dto.getMessageNode("playerId").asText());
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.GAME_STARTED) {
                            seen.add(dto);
                            latch.countDown();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        var connection = client.connectAndAwait();

        assertTrue(connectionLatch.await(5, TimeUnit.SECONDS));
        Player player1 = new Player("player1");
        Player player2 = playerService.getPlayerById(playerIds.get(0));

        when(playerService.getPlayerById(player1.getUniqueId())).thenReturn(player1);
        String lobbyId = lobbyService.createLobby(player1.getUniqueId());
        lobbyService.joinLobbyByCode(lobbyId, player2.getUniqueId());

        lobbyService.toggleReady(lobbyId, player1.getUniqueId());
        lobbyService.toggleReady(lobbyId, player2.getUniqueId());

        MessageDTO startedMessage = new MessageDTO(MessageType.START_GAME, player1.getUniqueId(), lobbyId);
        connection.sendTextAndAwait(startedMessage);

        assertTrue(latch.await(5, TimeUnit.SECONDS), "never saw GAME_STARTED");
        assertEquals(1, seen.size());
        MessageDTO response = seen.get(0);
        assertEquals(MessageType.GAME_STARTED, response.getType());
        assertNotNull(response.getMessageNode("gameboard"));
        assertNotNull(response.getMessageNode("players"));
        assertNotNull(response.getMessageNode("activePlayer"));
        assertTrue(lobbyService.getLobbyById(lobbyId).isGameStarted());

        verify(gameService).startGame(lobbyId, player1.getUniqueId());
        verify(lobbyService, atLeastOnce()).getLobbyById(lobbyId);
    }

    @Test
    void testEndTurn() throws InterruptedException, GameException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1NextTurnLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.NEXT_TURN) {
                            player1ReceivedMessages.add(dto);
                            player1NextTurnLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);

        lobbyService.toggleReady(actualLobbyId, player1ActualId);
        lobbyService.toggleReady(actualLobbyId, player2ActualId);

        gameService.startGame(actualLobbyId, player1ActualId);
        System.out.println("Test: Gameboard created for lobby " + actualLobbyId);

        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        lobby.setPlayerOrder(List.of(player1ActualId, player2ActualId));
        lobby.setActivePlayer(player1ActualId);

        gameService.placeRoad(actualLobbyId, player1ActualId, 1);
        gameService.placeSettlement(actualLobbyId, player1ActualId, 1);

        MessageDTO messageDTO = new MessageDTO(MessageType.END_TURN, player1ActualId, actualLobbyId);
        client1WebSocketClientConnection.sendTextAndAwait(messageDTO);

        assertTrue(player1NextTurnLatch.await(10, TimeUnit.SECONDS));
        assertFalse(player1ReceivedMessages.isEmpty());

        var receivedDTO = player1ReceivedMessages.getLast();
        assertEquals(MessageType.NEXT_TURN, receivedDTO.getType());
        assertTrue(receivedDTO.getPlayers().containsKey(player2ActualId));
        assertTrue(receivedDTO.getPlayers().get(player2ActualId).isActivePlayer());
        assertNotNull(receivedDTO.getMessageNode("gameboard"));
    }

    @Test
    void testJoinLobby_Failure_ThrowsGameException() throws Exception {
        String invalidLobbyId = "invalid123";
        String playerId = "testPlayer";

        doThrow(new GameException("Failed to join lobby: lobby session not found or full"))
                .when(lobbyService).getLobbyById(invalidLobbyId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        var client = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    if (msg.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                            if (dto.getType() == MessageType.ERROR) {
                                receivedMessages.add(msg);
                                errorLatch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message");
                        }
                    }
                })
                .connectAndAwait();

        MessageDTO joinMessage = new MessageDTO(MessageType.JOIN_LOBBY, playerId, invalidLobbyId);
        client.sendTextAndAwait(objectMapper.writeValueAsString(joinMessage));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS), "Did not receive ERROR message");
        assertEquals(1, receivedMessages.size());

        MessageDTO errorMessage = objectMapper.readValue(receivedMessages.get(0), MessageDTO.class);
        assertEquals(MessageType.ERROR, errorMessage.getType());
        assertEquals("Failed to join lobby: lobby session not found or full",
                errorMessage.getMessageNode("error").asText());

        verify(lobbyService).joinLobbyByCode(invalidLobbyId, playerId);
    }

    @Test
    void testJoinLobby_GameBoardUpdateFails_StillReturnsSuccess() throws Exception {

        Player hostPlayer = new Player("Host Player");
        when(playerService.getPlayerById(hostPlayer.getUniqueId())).thenReturn(hostPlayer);

        Player player = new Player("Player 1");
        String playerId = player.getUniqueId();
        when(playerService.getPlayerById(playerId)).thenReturn(player);

        String lobbyId = lobbyService.createLobby(hostPlayer.getUniqueId());

        doThrow(new GameException("Test exception")).when(gameMessageHandler).getGameBoardInformation(lobbyId);

        List<String> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch successLatch = new CountDownLatch(1);

        var client = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    if (msg.startsWith("{")) {
                        try {
                            MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                            if (dto.getType() == MessageType.PLAYER_JOINED) {
                                receivedMessages.add(msg);
                                successLatch.countDown();
                            }
                        } catch (JsonProcessingException e) {
                            fail("Failed to parse message");
                        }
                    }
                })
                .connectAndAwait();

        MessageDTO joinMessage = new MessageDTO(MessageType.JOIN_LOBBY, playerId, lobbyId);
        client.sendTextAndAwait(objectMapper.writeValueAsString(joinMessage));

        assertTrue(successLatch.await(5, TimeUnit.SECONDS), "Did not receive PLAYER_JOINED message");
        assertEquals(1, receivedMessages.size());

        MessageDTO successMessage = objectMapper.readValue(receivedMessages.get(0), MessageDTO.class);
        assertEquals(MessageType.PLAYER_JOINED, successMessage.getType());

        verify(lobbyService).joinLobbyByCode(lobbyId, playerId);
    }

    @Test
    void testJoinLobbyFailsWithJoinReturnsFalse() throws Exception {
        String playerId = "FailPlayer";
        String lobbyId = "failLobby";

        MessageDTO joinMessage = new MessageDTO(MessageType.JOIN_LOBBY, playerId, lobbyId);
        doReturn(false).when(lobbyService).joinLobbyByCode(lobbyId, playerId);

        List<MessageDTO> receivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        var client = BasicWebSocketConnector.create()
                .baseUri(serverUri)
                .path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        if (dto.getType() == MessageType.ERROR) {
                            receivedMessages.add(dto);
                            errorLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Failed to parse message");
                    }
                })
                .connectAndAwait();

        client.sendTextAndAwait(objectMapper.writeValueAsString(joinMessage));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, receivedMessages.size());

        MessageDTO error = receivedMessages.get(0);
        assertEquals(MessageType.ERROR, error.getType());
        assertEquals("Failed to join lobby: lobby session not found or full",
                error.getMessageNode("error").asText());

        verify(lobbyService).joinLobbyByCode(lobbyId, playerId);
    }

    @Test
    void testSetReady() throws InterruptedException, GameException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1SetReadyLatch = new CountDownLatch(3);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_UPDATED) {
                            player1ReceivedMessages.add(dto);
                            player1SetReadyLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertFalse(lobby.isReady(player1ActualId));

        client1WebSocketClientConnection.sendTextAndAwait(new MessageDTO(MessageType.SET_READY, player1ActualId, actualLobbyId));
        client1WebSocketClientConnection.sendTextAndAwait(new MessageDTO(MessageType.SET_READY, player1ActualId, actualLobbyId));
        client1WebSocketClientConnection.sendTextAndAwait(new MessageDTO(MessageType.SET_READY, player1ActualId, actualLobbyId));

        assertTrue(player1SetReadyLatch.await(10, TimeUnit.SECONDS));
        assertEquals(3, player1ReceivedMessages.size());

        var firstMessage = player1ReceivedMessages.get(0);
        assertEquals(MessageType.LOBBY_UPDATED, firstMessage.getType());
        assertNotNull(firstMessage.getPlayers());
        assertTrue(firstMessage.getPlayers().containsKey(player1ActualId));
        assertTrue(firstMessage.getPlayers().get(player1ActualId).isReady());

        var secondMessage = player1ReceivedMessages.get(1);
        assertEquals(MessageType.LOBBY_UPDATED, secondMessage.getType());
        assertNotNull(secondMessage.getPlayers());
        assertTrue(secondMessage.getPlayers().containsKey(player1ActualId));
        assertFalse(secondMessage.getPlayers().get(player1ActualId).isReady());

        var thirdMessage = player1ReceivedMessages.get(2);
        assertEquals(MessageType.LOBBY_UPDATED, thirdMessage.getType());
        assertNotNull(thirdMessage.getPlayers());
        assertTrue(thirdMessage.getPlayers().containsKey(player1ActualId));
        assertTrue(thirdMessage.getPlayers().get(player1ActualId).isReady());

        assertTrue(lobby.isReady(player1ActualId));

        verify(lobbyService, times(3)).toggleReady(actualLobbyId, player1ActualId);
    }

    @Test
    void testLeaveLobby() throws InterruptedException, GameException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1LobbyUpdatedLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_UPDATED) {
                            player1ReceivedMessages.add(dto);
                            player1LobbyUpdatedLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertTrue(lobby.getPlayers().contains(player2ActualId));

        client2WebSocketClientConnection.sendTextAndAwait(new MessageDTO(MessageType.LEAVE_LOBBY, player2ActualId, actualLobbyId));

        assertTrue(player1LobbyUpdatedLatch.await(10, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var firstMessage = player1ReceivedMessages.get(0);
        assertEquals(MessageType.LOBBY_UPDATED, firstMessage.getType());
        assertNotNull(firstMessage.getPlayers());
        assertFalse(firstMessage.getPlayers().containsKey(player2ActualId));

        assertFalse(lobby.getPlayers().contains(player2ActualId));

        Player player2 = playerService.getPlayerById(player2ActualId);
        assertNotNull(player2);
        assertEquals(0, lobby.getVictoryPoints(player2ActualId));

        verify(lobbyService).leaveLobby(actualLobbyId, player2ActualId);
    }

    @Test
    void testCreateLobby() throws InterruptedException {
        final String[] client1PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(1);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1LobbyCreatedLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_CREATED) {
                            player1ReceivedMessages.add(dto);
                            player1LobbyCreatedLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);

        client1WebSocketClientConnection.sendTextAndAwait(new MessageDTO(MessageType.CREATE_LOBBY, player1ActualId, null));

        assertTrue(player1LobbyCreatedLatch.await(10, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var response = player1ReceivedMessages.get(0);
        assertEquals(MessageType.LOBBY_CREATED, response.getType());
        assertNotNull(response.getPlayers());
        assertTrue(response.getPlayers().containsKey(player1ActualId));
        String lobbyId = response.getLobbyId();
        assertFalse(Util.isEmpty(lobbyId));

        Lobby lobby = assertDoesNotThrow(() -> lobbyService.getLobbyById(lobbyId));
        assertTrue(lobby.getPlayers().contains(player1ActualId));
        assertEquals(player1ActualId, lobby.getHostPlayer());
        assertFalse(lobby.isGameStarted());
        assertFalse(lobby.isReady(player1ActualId));
    }

    @Test
    void testCreatePlayerTradeRequest() throws InterruptedException, GameException, JsonProcessingException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1TradeOfferLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.TRADE_OFFER) {
                            player1ReceivedMessages.add(dto);
                            player1TradeOfferLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertTrue(lobby.getPlayers().contains(player2ActualId));

        lobby.setActivePlayer(player1ActualId);

        Player player1 = playerService.getPlayerById(player1ActualId);
        Player player2 = playerService.getPlayerById(player2ActualId);

        // Initial state for the player
        player1.receiveResource(TileType.WOOD, 1);
        player2.receiveResource(TileType.SHEEP, 1);

        // Prepare trade request from player 1 to player 2 (1 WOOD for 1 SHEEP)
        ObjectNode tradePayload = JsonNodeFactory.instance.objectNode();
        tradePayload.putObject("targetResources").put(TileType.WOOD.name(), 1);
        tradePayload.putObject("offeredResources").put(TileType.SHEEP.name(), 1);

        ObjectNode tradeRequestJson = JsonNodeFactory.instance.objectNode();
        tradeRequestJson.put("sourcePlayerId", player2ActualId);
        tradeRequestJson.put("targetPlayerId", player1ActualId);
        tradeRequestJson.set("trade", tradePayload);

        var messageDto = new MessageDTO(
                MessageType.CREATE_PLAYER_TRADE_REQUEST,
                player2ActualId,
                actualLobbyId,
                tradeRequestJson
        );

        client2WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(messageDto));

        assertTrue(player1TradeOfferLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var response = player1ReceivedMessages.getFirst();
        assertEquals(MessageType.TRADE_OFFER, response.getType());
        assertNotNull(response.getMessage());

        //Check tradeId
        String tradeId = response.getMessageNode("tradeId").asText();
        assertFalse(Util.isEmpty(tradeId));
        String[] tradeIdParts = tradeId.split("#");
        assertEquals(2, tradeIdParts.length);
        assertEquals(actualLobbyId, tradeIdParts[0]);
        assertDoesNotThrow(() -> UUID.fromString(tradeIdParts[1]));

        JsonNode tradeRequestJsonResponse = response.getMessageNode("tradeRequest");
        PlayerTradeRequest ptr = objectMapper.treeToValue(tradeRequestJsonResponse, PlayerTradeRequest.class);
        assertEquals(player2ActualId, ptr.sourcePlayerId());
        assertEquals(player1ActualId, ptr.targetPlayerId());

        assertEquals(1, ptr.trade().offeredResources().size());
        assertEquals(1, ptr.trade().targetResources().size());
        assertEquals(1, ptr.trade().offeredResources().get(TileType.SHEEP));
        assertEquals(1, ptr.trade().targetResources().get(TileType.WOOD));
    }

    @Test
    void testAcceptPlayerTradeRequest() throws InterruptedException, GameException, JsonProcessingException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        List<MessageDTO> player2ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1TradeOfferLatch = new CountDownLatch(1);
        CountDownLatch player2NotificationLatch = new CountDownLatch(2);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.TRADE_OFFER) {
                            player1ReceivedMessages.add(dto);
                            player1TradeOfferLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.ALERT) {
                            player2NotificationLatch.countDown();
                            player2ReceivedMessages.add(dto);
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertTrue(lobby.getPlayers().contains(player2ActualId));

        lobby.setActivePlayer(player1ActualId);

        Player player1 = playerService.getPlayerById(player1ActualId);
        Player player2 = playerService.getPlayerById(player2ActualId);

        // Initial state for the player
        player1.receiveResource(TileType.WOOD, 1);
        player2.receiveResource(TileType.SHEEP, 1);

        assertEquals(1, player1.getResources().size());
        assertEquals(1, player1.getResources().get(TileType.WOOD));

        assertEquals(1, player2.getResources().size());
        assertEquals(1, player2.getResources().get(TileType.SHEEP));

        // Prepare trade request from player 1 to player 2 (1 WOOD for 1 SHEEP)
        ObjectNode tradePayload = JsonNodeFactory.instance.objectNode();
        tradePayload.putObject("targetResources").put(TileType.WOOD.name(), 1);
        tradePayload.putObject("offeredResources").put(TileType.SHEEP.name(), 1);

        ObjectNode tradeRequestJson = JsonNodeFactory.instance.objectNode();
        tradeRequestJson.put("sourcePlayerId", player2ActualId);
        tradeRequestJson.put("targetPlayerId", player1ActualId);
        tradeRequestJson.set("trade", tradePayload);

        var messageDto = new MessageDTO(
                MessageType.CREATE_PLAYER_TRADE_REQUEST,
                player2ActualId,
                actualLobbyId,
                tradeRequestJson
        );

        client2WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(messageDto));

        assertTrue(player1TradeOfferLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var response = player1ReceivedMessages.getFirst();
        assertEquals(MessageType.TRADE_OFFER, response.getType());
        assertNotNull(response.getMessage());

        //Check tradeId
        String tradeId = response.getMessageNode("tradeId").asText();
        assertFalse(Util.isEmpty(tradeId));
        String[] tradeIdParts = tradeId.split("#");
        assertEquals(2, tradeIdParts.length);
        assertEquals(actualLobbyId, tradeIdParts[0]);
        assertDoesNotThrow(() -> UUID.fromString(tradeIdParts[1]));

        JsonNode tradeRequestJsonResponse = response.getMessageNode("tradeRequest");
        PlayerTradeRequest ptr = objectMapper.treeToValue(tradeRequestJsonResponse, PlayerTradeRequest.class);
        assertEquals(player2ActualId, ptr.sourcePlayerId());
        assertEquals(player1ActualId, ptr.targetPlayerId());

        assertEquals(1, ptr.trade().offeredResources().size());
        assertEquals(1, ptr.trade().targetResources().size());
        assertEquals(1, ptr.trade().offeredResources().get(TileType.SHEEP));
        assertEquals(1, ptr.trade().targetResources().get(TileType.WOOD));

        var acceptRequestDto = new MessageDTO(
                MessageType.ACCEPT_TRADE_REQUEST,
                player1ActualId,
                actualLobbyId,
                JsonNodeFactory.instance.objectNode().put("tradeId", tradeId)
        );
        client1WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(acceptRequestDto));

        assertTrue(player2NotificationLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, player2ReceivedMessages.size());

        var sendResponse = player2ReceivedMessages.getFirst();
        assertEquals(MessageType.ALERT, sendResponse.getType());
        assertNotNull(sendResponse.getMessage());
        assertEquals("Sent trade request to %s".formatted(player1.getUsername()), sendResponse.getMessageNode("message").asText());
        assertEquals("success", sendResponse.getMessageNode("severity").asText());

        var acceptResponse = player2ReceivedMessages.getLast();
        assertEquals(MessageType.ALERT, acceptResponse.getType());
        assertNotNull(acceptResponse.getMessage());
        assertEquals("Trade request was accepted by %s".formatted(player1.getUsername()), acceptResponse.getMessageNode("message").asText());
        assertEquals("success", acceptResponse.getMessageNode("severity").asText());

        assertEquals(2, player1.getResources().size());
        assertEquals(1, player1.getResources().get(TileType.SHEEP));
        assertEquals(0, player1.getResources().get(TileType.WOOD));

        assertEquals(2, player2.getResources().size());
        assertEquals(1, player2.getResources().get(TileType.WOOD));
        assertEquals(0, player2.getResources().get(TileType.SHEEP));

        //trade request must have been removed after trade
        assertThrows(GameException.class, () -> tradingService.getPlayerTradeRequest(tradeId));

        verify(tradingService).acceptPlayerTradeRequest(player1ActualId, tradeId);
        verify(tradingService).createPlayerTradeRequest(eq(actualLobbyId), any(PlayerTradeRequest.class));
    }

    @Test
    void testRejectPlayerTradeRequest() throws InterruptedException, GameException, JsonProcessingException {
        final String[] client1PlayerIdHolder = new String[1];
        final String[] client2PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(2);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        List<MessageDTO> player2ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1TradeOfferLatch = new CountDownLatch(1);
        CountDownLatch player2NotificationLatch = new CountDownLatch(2);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.TRADE_OFFER) {
                            player1ReceivedMessages.add(dto);
                            player1TradeOfferLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        System.out.println("Setting up Client 2...");
        BasicWebSocketConnector client2Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client2 RX: " + msg);
                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client2PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client2 Connected with ID: " + client2PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.ALERT) {
                            player2NotificationLatch.countDown();
                            player2ReceivedMessages.add(dto);
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client2: Failed to parse message: " + msg, e);
                    }
                });
        var client2WebSocketClientConnection = client2Connector.connectAndAwait();
        System.out.println("Client 2 connection object: " + client2WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");
        assertNotNull(client2PlayerIdHolder[0], "Client 2 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        String player2ActualId = client2PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);
        System.out.println("Test: player2ActualId = " + player2ActualId);

        String actualLobbyId = lobbyService.createLobby(player1ActualId);
        System.out.println("Test: Created lobby with ID: " + actualLobbyId + " for host " + player1ActualId);
        lobbyService.joinLobbyByCode(actualLobbyId, player2ActualId);
        System.out.println("Test: Player " + player2ActualId + " joined lobby " + actualLobbyId);
        Lobby lobby = lobbyService.getLobbyById(actualLobbyId);
        assertTrue(lobby.getPlayers().contains(player2ActualId));

        lobby.setActivePlayer(player2ActualId);

        Player player1 = playerService.getPlayerById(player1ActualId);
        Player player2 = playerService.getPlayerById(player2ActualId);

        // Initial state for the player
        player1.receiveResource(TileType.WOOD, 1);
        player2.receiveResource(TileType.SHEEP, 1);

        assertEquals(1, player1.getResources().size());
        assertEquals(1, player1.getResources().get(TileType.WOOD));

        assertEquals(1, player2.getResources().size());
        assertEquals(1, player2.getResources().get(TileType.SHEEP));

        // Prepare trade request from player 1 to player 2 (1 WOOD for 1 SHEEP)
        ObjectNode tradePayload = JsonNodeFactory.instance.objectNode();
        tradePayload.putObject("targetResources").put(TileType.WOOD.name(), 1);
        tradePayload.putObject("offeredResources").put(TileType.SHEEP.name(), 1);

        ObjectNode tradeRequestJson = JsonNodeFactory.instance.objectNode();
        tradeRequestJson.put("sourcePlayerId", player2ActualId);
        tradeRequestJson.put("targetPlayerId", player1ActualId);
        tradeRequestJson.set("trade", tradePayload);

        var messageDto = new MessageDTO(
                MessageType.CREATE_PLAYER_TRADE_REQUEST,
                player2ActualId,
                actualLobbyId,
                tradeRequestJson
        );

        client2WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(messageDto));

        assertTrue(player1TradeOfferLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var response = player1ReceivedMessages.getFirst();
        assertEquals(MessageType.TRADE_OFFER, response.getType());
        assertNotNull(response.getMessage());

        //Check tradeId
        String tradeId = response.getMessageNode("tradeId").asText();
        assertFalse(Util.isEmpty(tradeId));
        String[] tradeIdParts = tradeId.split("#");
        assertEquals(2, tradeIdParts.length);
        assertEquals(actualLobbyId, tradeIdParts[0]);
        assertDoesNotThrow(() -> UUID.fromString(tradeIdParts[1]));

        JsonNode tradeRequestJsonResponse = response.getMessageNode("tradeRequest");
        PlayerTradeRequest ptr = objectMapper.treeToValue(tradeRequestJsonResponse, PlayerTradeRequest.class);
        assertEquals(player2ActualId, ptr.sourcePlayerId());
        assertEquals(player1ActualId, ptr.targetPlayerId());

        assertEquals(1, ptr.trade().offeredResources().size());
        assertEquals(1, ptr.trade().targetResources().size());
        assertEquals(1, ptr.trade().offeredResources().get(TileType.SHEEP));
        assertEquals(1, ptr.trade().targetResources().get(TileType.WOOD));

        var acceptRequestDto = new MessageDTO(
                MessageType.REJECT_TRADE_REQUEST,
                player1ActualId,
                actualLobbyId,
                JsonNodeFactory.instance.objectNode().put("tradeId", tradeId)
        );
        client1WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(acceptRequestDto));

        assertTrue(player2NotificationLatch.await(5, TimeUnit.SECONDS));
        assertEquals(2, player2ReceivedMessages.size());

        var sendResponse = player2ReceivedMessages.getFirst();
        assertEquals(MessageType.ALERT, sendResponse.getType());
        assertNotNull(sendResponse.getMessage());
        assertEquals("Sent trade request to %s".formatted(player1.getUsername()), sendResponse.getMessageNode("message").asText());
        assertEquals("success", sendResponse.getMessageNode("severity").asText());

        var acceptResponse = player2ReceivedMessages.getLast();
        assertEquals(MessageType.ALERT, acceptResponse.getType());
        assertNotNull(acceptResponse.getMessage());
        assertEquals("Trade request was rejected by %s".formatted(player1.getUsername()), acceptResponse.getMessageNode("message").asText());
        assertEquals("error", acceptResponse.getMessageNode("severity").asText());

        assertEquals(1, player1.getResources().size());
        assertEquals(1, player1.getResources().get(TileType.WOOD));

        assertEquals(1, player2.getResources().size());
        assertEquals(1, player2.getResources().get(TileType.SHEEP));

        //trade request must have been removed after trade
        assertThrows(GameException.class, () -> tradingService.getPlayerTradeRequest(tradeId));

        verify(tradingService).rejectPlayerTradeRequest(player1ActualId, tradeId);
        verify(tradingService).createPlayerTradeRequest(eq(actualLobbyId), any(PlayerTradeRequest.class));
    }

    @Test
    void testGetLobbies() throws InterruptedException, GameException, JsonProcessingException {
        final String[] client1PlayerIdHolder = new String[1];

        CountDownLatch connectionLatch = new CountDownLatch(1);

        List<MessageDTO> player1ReceivedMessages = new CopyOnWriteArrayList<>();
        CountDownLatch player1LobbyListLatch = new CountDownLatch(1);

        System.out.println("Setting up Client 1...");
        BasicWebSocketConnector client1Connector = BasicWebSocketConnector.create()
                .baseUri(serverUri).path("/game")
                .onTextMessage((conn, msg) -> {
                    try {
                        MessageDTO dto = objectMapper.readValue(msg, MessageDTO.class);
                        System.out.println("Client1 RX: " + msg);

                        if (dto.getType() == MessageType.CONNECTION_SUCCESSFUL) {
                            client1PlayerIdHolder[0] = dto.getMessageNode("playerId").asText();
                            System.out.println("Client1 Connected with ID: " + client1PlayerIdHolder[0]);
                            connectionLatch.countDown();
                        } else if (dto.getType() == MessageType.LOBBY_LIST) {
                            player1ReceivedMessages.add(dto);
                            player1LobbyListLatch.countDown();
                        }
                    } catch (JsonProcessingException e) {
                        fail("Client1: Failed to parse message: " + msg, e);
                    }
                });
        var client1WebSocketClientConnection = client1Connector.connectAndAwait();
        System.out.println("Client 1 connection object: " + client1WebSocketClientConnection);

        assertTrue(connectionLatch.await(10, TimeUnit.SECONDS), "Not all clients connected and received their IDs. Latch: " + connectionLatch.getCount());
        assertNotNull(client1PlayerIdHolder[0], "Client 1 Player ID not set");

        String player1ActualId = client1PlayerIdHolder[0];
        System.out.println("Test: player1ActualId = " + player1ActualId);

        Player lobby1HostPlayer = new Player("lobby1HostPlayer");
        Player lobby2HostPlayer = new Player("lobby2HostPlayer");
        Player lobby3HostPlayer = new Player("lobby3HostPlayer");

        playerService.addPlayerWithoutConnection(lobby1HostPlayer);
        playerService.addPlayerWithoutConnection(lobby2HostPlayer);
        playerService.addPlayerWithoutConnection(lobby3HostPlayer);

        Lobby lobby1 = lobbyService.getLobbyById(lobbyService.createLobby(lobby1HostPlayer.getUniqueId()));
        Thread.sleep(100);
        Lobby lobby2 = lobbyService.getLobbyById(lobbyService.createLobby(lobby2HostPlayer.getUniqueId()));
        Thread.sleep(100);
        Lobby lobby3 = lobbyService.getLobbyById(lobbyService.createLobby(lobby3HostPlayer.getUniqueId()));

        lobbyService.joinLobbyByCode(lobby1.getLobbyId(), lobby2HostPlayer.getUniqueId());

        lobby2.setGameStarted(true);

        var getLobbiesDto = new MessageDTO(
                MessageType.GET_LOBBIES,
                null
        );
        client1WebSocketClientConnection.sendTextAndAwait(objectMapper.writeValueAsString(getLobbiesDto));

        assertTrue(player1LobbyListLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, player1ReceivedMessages.size());

        var sendResponse = player1ReceivedMessages.getFirst();
        assertEquals(MessageType.LOBBY_LIST, sendResponse.getType());
        assertNotNull(sendResponse.getMessage());
        List<LobbyInfo> lobbyInfoList = new ArrayList<>();

        sendResponse.getMessageNode("lobbies").forEach(node -> {
            try {
                lobbyInfoList.add(objectMapper.treeToValue(node, LobbyInfo.class));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(2, lobbyInfoList.size());
        //Info for lobby1 should be last, since it is the oldest lobby
        LobbyInfo lobbyInfo1 = lobbyInfoList.getLast();
        assertEquals(lobby1.getLobbyId(), lobbyInfo1.id());
        assertEquals(2, lobbyInfo1.playerCount());
        assertEquals(lobby1HostPlayer.getUsername(), lobbyInfo1.hostPlayer());

        LobbyInfo lobbyInfo2 = lobbyInfoList.getFirst();
        assertEquals(lobby3.getLobbyId(), lobbyInfo2.id());
        assertEquals(1, lobbyInfo2.playerCount());
        assertEquals(lobby3HostPlayer.getUsername(), lobbyInfo2.hostPlayer());
    }

}
