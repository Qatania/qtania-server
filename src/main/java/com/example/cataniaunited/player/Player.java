package com.example.cataniaunited.player;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.InsufficientResourcesException;
import com.example.cataniaunited.game.board.ports.Port;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import io.quarkus.websockets.next.WebSocketConnection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Represents a player in the Catan game.
 * Manages player-specific information such as username, unique ID, WebSocket connection,
 * victory points, and resources.
 */
public class Player {

    private String username;
    private final String uniqueId;
    private final WebSocketConnection connection;
    HashMap<TileType, Integer> resources = new HashMap<>();

    final Set<Port> accessiblePorts = new HashSet<>();

    /**
     * Default constructor. Creates a player with a random username and a unique ID.
     * The WebSocket connection will be null.
     */
    public Player() {
        this.uniqueId = UUID.randomUUID().toString();
        this.username = "RandomPlayer_" + new Random().nextInt(10000);
        this.connection = null;
    }

    /**
     * Constructs a player associated with a WebSocket connection.
     * Generates a random username and a unique ID.
     *
     * @param connection The {@link WebSocketConnection} for this player.
     */
    public Player(WebSocketConnection connection) {
        this.uniqueId = UUID.randomUUID().toString();
        this.username = "RandomPlayer_" + new Random().nextInt(10000);
        this.connection = connection;
    }

    /**
     * Constructs a player with a specified username.
     * Generates a unique ID. The WebSocket connection will be null.
     * Initializes resources.
     *
     * @param username The desired username for the player.
     */
    public Player(String username) {
        this.username = username;
        this.uniqueId = UUID.randomUUID().toString();
        this.connection = null;
    }

    /**
     * Constructs a player with a specified username and WebSocket connection.
     * Generates a unique ID.
     * Initializes resources.
     *
     * @param username   The desired username for the player.
     * @param connection The {@link WebSocketConnection} for this player.
     */
    public Player(String username, WebSocketConnection connection) {
        this.username = username;
        this.uniqueId = UUID.randomUUID().toString();
        this.connection = connection;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUniqueId() {
        return uniqueId;
    }

    public void addPort(Port port) {
        if (port == null) {
            throw new IllegalArgumentException("Port can't be null");
        }

        accessiblePorts.add(port);
    }

    public WebSocketConnection getConnection() {
        return connection;
    }

    /**
     * Gets the count of a specific resource type held by the player.
     *
     * @param type The {@link TileType} of the resource.
     * @return The amount of the specified resource the player has. (0 if not tracked)
     */
    public int getResourceCount(TileType type) {
        return resources.getOrDefault(type, 0);
    }

    @Override
    public String toString() {
        return "Player{" +
                "username='" + username + '\'' +
                ", uniqueId='" + uniqueId + '\'' +
                ", connectionId='" + (connection != null ? connection.id() : "null") + '\'' +
                '}';
    }

    /**
     * Adds a specified amount of a resource to the player's
     * Does nothing if the resource type is null or WASTE.
     *
     * @param resource The {@link TileType} of the resource to receive.
     * @param amount   The amount of the resource to add.
     */
    public void receiveResource(TileType resource, int amount) {
        if (resource == null || resource == TileType.WASTE)
            return;

        int resourceCount = getResourceCount(resource);
        resources.put(resource, resourceCount + amount);
    }

    /**
     * Removes a specified amount of a resource from the player's inventory.
     * Does nothing if the resource type is null or WASTE.
     *
     * @param resource The {@link TileType} of the resource to remove.
     * @param amount   The amount of the resource to remove.
     * @throws GameException                  if the resource type is invalid.
     * @throws InsufficientResourcesException if the player does not have enough of the resource.
     */
    public void removeResource(TileType resource, int amount) throws GameException {
        if (resource == null || resource == TileType.WASTE)
            return;

        int resourceCount = getResourceCount(resource) - amount;
        if (resourceCount < 0) {
            throw new InsufficientResourcesException();
        }
        resources.put(resource, resourceCount);
    }

    public Map<TileType, Integer> getResources() {
        return resources;
    }

    /**
     * Compares this Player object to another object for equality.
     * Two players are considered equal if their unique IDs are the same.
     *
     * @param o The object to compare with.
     * @return true if the objects are equal, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // Optimization
        if (o == null || getClass() != o.getClass()) return false;
        Player player = (Player) o;
        return Objects.equals(uniqueId, player.uniqueId);
    }

    /**
     * Generates a hash code for this Player object.
     * Based on the player's unique ID.
     *
     * @return The hash code.
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(uniqueId);
    }

    public Set<Port> getAccessiblePorts() {
        return accessiblePorts;
    }
}
