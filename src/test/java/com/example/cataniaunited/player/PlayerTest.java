package com.example.cataniaunited.player;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.InsufficientResourcesException;
import com.example.cataniaunited.game.board.ports.GeneralPort;
import com.example.cataniaunited.game.board.ports.Port;
import com.example.cataniaunited.game.board.ports.SpecificResourcePort;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.websockets.next.WebSocketConnection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class PlayerTest {

    Player player;

    @BeforeEach
    void setUp() {
        player = new Player();
    }

    @Test
    void testDefaultConstructor() {
        assertTrue(player.getUsername().startsWith("RandomPlayer_"));
        Assertions.assertNotNull(player.getUniqueId(), "uniqueId should not be null");
        Assertions.assertFalse(player.getUniqueId().isEmpty(), "uniqueId should not be empty");
    }

    @Test
    void testCustomConstructor() {
        String customUsername = "Alice1";
        Player customPlayer = new Player(customUsername);
        assertEquals(customUsername, customPlayer.getUsername());
        Assertions.assertNotNull(customPlayer.getUniqueId(), "uniqueId should not be null");
    }

    @Test
    void testSetUsername() {
        Player customPlayer = new Player();
        String newUsername = "Bob";
        customPlayer.setUsername(newUsername);
        assertEquals(newUsername, customPlayer.getUsername());
    }

    @Test
    void testUniqueIdIsDifferentForEachPlayer() {
        Player player1 = new Player();
        Player player2 = new Player();
        assertNotEquals(player1.getUniqueId(), player2.getUniqueId(),
                "Each Player should have a unique ID");
    }

    @Test
    void defaultConstructorInitializesConnectionToNull() {
        assertNull(player.getConnection(), "connection should be null for default constructor.");
    }

    @Test
    void constructorWithUsernameInitializesConnectionIdAsNull() {
        Player customPlayer = new Player("TestUser");
        assertNull(customPlayer.getConnection(), "connection should be null for username-only constructor.");
    }

    @Test
    void constructorWithWebSocketConnectionInitializesCorrectly() {
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        String expectedConnectionId = "connId_123";
        when(mockConnection.id()).thenReturn(expectedConnectionId);

        Player customPlayer = new Player(mockConnection);

        assertTrue(customPlayer.getUsername().startsWith("RandomPlayer_"), "Username should start with 'RandomPlayer_'.");
        assertNotNull(customPlayer.getUniqueId(), "uniqueId should not be null.");
        assertEquals(expectedConnectionId, customPlayer.getConnection().id(), "connectionId should match the mock connection's ID.");
    }

    @Test
    void constructorWithUsernameAndWebSocketConnectionInitializesCorrectly() {
        String customUsername = "BobWithConnection";
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        String expectedConnectionId = "connId_456";
        when(mockConnection.id()).thenReturn(expectedConnectionId);

        Player customPlayer = new Player(customUsername, mockConnection);

        assertEquals(customUsername, customPlayer.getUsername());
        assertNotNull(customPlayer.getUniqueId(), "uniqueId should not be null.");
        assertEquals(expectedConnectionId, customPlayer.getConnection().id(), "connectionId should match the mock connection's ID.");
    }

    @Test
    void toStringContainsAllRelevantFields() {
        String username = "TestUserToString";
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);
        String connectionId = "ws_conn_789";
        when(mockConnection.id()).thenReturn(connectionId);

        Player customPlayer = new Player(username, mockConnection);
        String uniqueId = customPlayer.getUniqueId();

        String expectedString = "Player{" +
                "username='" + username + '\'' +
                ", uniqueId='" + uniqueId + '\'' +
                ", connectionId='" + connectionId + '\'' +
                '}';
        assertEquals(expectedString, customPlayer.toString());
    }

    @Test
    void toStringHandlesNullConnectionId() {
        String username = "OfflineUserToString";
        Player customPlayer = new Player(username);
        String uniqueId = customPlayer.getUniqueId();

        String expectedString = "Player{" +
                "username='" + username + '\'' +
                ", uniqueId='" + uniqueId + '\'' +
                ", connectionId='null'" +
                '}';
        assertEquals(expectedString, customPlayer.toString());
    }

    @Test
    void getResourceAddsResourceCorrectlyForFirstTime() {
        TileType testResource = TileType.WHEAT;
        int amount = 5;

        player.receiveResource(testResource, amount);

        assertEquals(amount, (int) player.resources.get(testResource), "Resource count should be updated after getting resources.");
    }

    @Test
    void getResourceAddsToExistingResourceAmount() {
        TileType testResource = TileType.WOOD;
        int firstIncrease = 3;
        int secondIncrease = 7;
        int expectedTotal = firstIncrease + secondIncrease;

        player.receiveResource(testResource, firstIncrease);
        player.receiveResource(testResource, secondIncrease);

        assertEquals(expectedTotal, (int) player.resources.get(testResource), "Resource count should be the sum of initial and additional amounts.");
    }

    @Test
    void getResourceWorksForAllTileTypes() {
        int amount = 2;
        for (TileType type : TileType.values()) {
            if (type == TileType.WASTE) {
                assertNull(player.resources.get(type), "Waste should be null");
                continue;
            }
            player.receiveResource(type, amount);
            assertEquals(amount, (int) player.resources.get(type), "Resource count for " + type + " should be " + amount + " after first get.");
            player.receiveResource(type, amount);
            assertEquals(amount * 2, (int) player.resources.get(type), "Resource count for " + type + " should be " + (amount * 2) + " after second get.");
        }
    }

    @Test
    void getResourceWithWasteTypeDoesNotChangeResourceCounts() {
        int initialWoodCount = player.getResourceCount(TileType.WOOD);
        int initialWheatCount = player.getResourceCount(TileType.WHEAT);

        player.receiveResource(TileType.WASTE, 5);

        assertEquals(initialWoodCount, player.getResourceCount(TileType.WOOD), "Adding WASTE should not affect WOOD count.");
        assertEquals(initialWheatCount, player.getResourceCount(TileType.WHEAT), "Adding WASTE should not affect WHEAT count.");
        assertFalse(player.resources.containsKey(TileType.WASTE), "Player's internal resources map should not contain WASTE key.");
    }

    @Test
    void receiveResourceWithNullResourceTypeShouldDoNothing() {
        var previousResource = player.resources;
        player.receiveResource(null, 5);
        assertEquals(previousResource, player.resources);
    }

    @Test
    void removeResourceOfTypeWasteShouldDoNothing() throws GameException {
        var previousResource = player.resources;
        player.removeResource(TileType.WASTE, 5);
        assertEquals(previousResource, player.resources);
    }

    @Test
    void removeResourceOfNullShouldDoNothing() throws GameException {
        var previousResource = player.resources;
        player.removeResource(null, 5);
        assertEquals(previousResource, player.resources);
    }

    @Test
    void removeResourceShouldThrowExceptionIfResourceAmountIsTooSmall() {
        assertThrows(InsufficientResourcesException.class, () -> player.removeResource(TileType.WOOD, 1));
    }

    @Test
    void removeResourceShouldRemoveCorrectResourceAmount() throws GameException {
        int woodResource = 4;
        player.resources.put(TileType.WOOD, woodResource);
        player.removeResource(TileType.WOOD, 2);
        assertEquals(woodResource - 2, player.resources.get(TileType.WOOD));
    }

    @Test
    void getAccessiblePortsReturnsCorrectPortSet() {
        player.addPort(new GeneralPort());
        player.addPort(new SpecificResourcePort(TileType.WOOD));
        player.addPort(new SpecificResourcePort(TileType.WHEAT));
        player.addPort(new GeneralPort());

        assertEquals(player.accessiblePorts, player.getAccessiblePorts(), "Port Sets do Not Match");
    }

    @Test
    void testHashCode() {
        assertEquals(Objects.hashCode(player.getUniqueId()), player.hashCode());
    }

    @Test
    void addPortWithNullPortShouldThrowIllegalArgumentException() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> player.addPort(null));
        assertEquals("Port can't be null", exception.getMessage());
    }

    @Test
    void addPortShouldAddPortToAccessiblePortsAndIncreaseSetSize() {

        Port mockPort1 = mock(Port.class);
        Port mockPort2 = mock(Port.class);

        assertTrue(player.accessiblePorts.isEmpty(), "Accessible ports should be empty initially.");

        player.addPort(mockPort1);
        assertEquals(1, player.accessiblePorts.size(), "Size should be 1 after adding one port.");
        assertTrue(player.accessiblePorts.contains(mockPort1), "Accessible ports should contain the first added port.");

        player.addPort(mockPort2);
        assertEquals(2, player.accessiblePorts.size(), "Size should be 2 after adding a second distinct port.");
        assertTrue(player.accessiblePorts.contains(mockPort2), "Accessible ports should contain the second added port.");
        assertTrue(player.accessiblePorts.contains(mockPort1), "Accessible ports should still contain the first port.");
    }

    @Test
    void addPortAddingSamePortInstanceMultipleTimesShouldOnlyAddItOnceToSet() {

        Port mockPort = mock(Port.class);

        assertTrue(player.accessiblePorts.isEmpty(), "Accessible ports should be empty initially.");

        player.addPort(mockPort);
        assertEquals(1, player.accessiblePorts.size(), "Size should be 1 after adding the port once.");
        assertTrue(player.accessiblePorts.contains(mockPort), "Accessible ports should contain the port.");

        player.addPort(mockPort);
        assertEquals(1, player.accessiblePorts.size(), "Size should remain 1 after adding the same port instance again, due to Set behavior.");
        assertTrue(player.accessiblePorts.contains(mockPort), "Accessible ports should still contain the port.");

        player.addPort(mockPort);
        assertEquals(1, player.accessiblePorts.size(), "Size should still be 1.");
    }


    @Test
    void equalsSameObjectShouldReturnTrue() {
        assertEquals(player, player, "A player should be equal to itself.");
    }

    @Test
    void equalsNullObjectShouldReturnFalse() {
        assertNotEquals(null, player, "A player should not be equal to null.");
    }

    @Test
    void equalsDifferentClassObjectShouldReturnFalse() {
        Object otherObject = new Object();
        assertNotEquals(player, otherObject, "A player should not be equal to an object of a different class.");
    }

    @Test
    void equalsDifferentPlayerObjectWithDifferentIdShouldReturnFalse() {
        Player player1 = new Player("1");
        Player player2 = new Player("2");

        assertNotEquals(player1.getUniqueId(), player2.getUniqueId(), "Test setup: Player IDs should be different for this test.");

        assertNotEquals(player1, player2, "Two different players with different unique IDs should not be equal.");
        assertNotEquals(player2, player1, "Two different players with different unique IDs should not be equal.");
    }

    @Test
    void equalsPlayerWithSameFieldsButDifferentInstanceShouldReturnFalseDueToUniqueId() {

        String sharedUsername = "1";
        WebSocketConnection mockConnection = mock(WebSocketConnection.class);

        Player playerA = new Player(sharedUsername, mockConnection);
        Player playerB = new Player(sharedUsername, mockConnection);

        assertNotEquals(playerA.getUniqueId(), playerB.getUniqueId(), "Test setup: New instances should have different unique IDs.");

        assertNotEquals(playerA, playerB, "Players with same fields but different unique IDs should not be equal.");
        assertNotEquals(playerB, playerA, "Players with same fields but different unique IDs should not be equal.");
    }
}

