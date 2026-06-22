package com.example.cataniaunited.game.board;

import com.example.cataniaunited.exception.GameException;
import com.example.cataniaunited.exception.ui.BuildableLimitReachedException;
import com.example.cataniaunited.exception.ui.InsufficientResourcesException;
import com.example.cataniaunited.exception.ui.NoAdjacentRoadException;
import com.example.cataniaunited.game.BuildRequest;
import com.example.cataniaunited.game.Buildable;
import com.example.cataniaunited.game.board.ports.Port;
import com.example.cataniaunited.game.board.tile_list_builder.StandardTileListBuilder;
import com.example.cataniaunited.game.board.tile_list_builder.Tile;
import com.example.cataniaunited.game.board.tile_list_builder.TileListBuilder;
import com.example.cataniaunited.game.board.tile_list_builder.TileListDirector;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.game.buildings.Building;
import com.example.cataniaunited.game.buildings.City;
import com.example.cataniaunited.game.buildings.Settlement;
import com.example.cataniaunited.game.dice.DiceRoller;
import com.example.cataniaunited.game.robber.Robber;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the Catan game board, including its tiles, building sites, and roads.
 * Manages the generation of the board layout and handles actions like placing buildings and roads.
 */
public class GameBoard {
    private static final Logger logger = Logger.getLogger(GameBoard.class);
    static final int DEFAULT_TILES_PER_PLAYER_GOAL = 6;
    static final int SIZE_OF_HEX = 10; // Size parameter for graphical representation of hexes
    private String longestRoadPlayerId = null;
    private int longestRoadLength = 0;

    private Robber robber;
    final int sizeOfBoard; // Number of rings/layers of tiles from the center
    private final DiceRoller diceRoller;
    private final Map<String, Map<Placable, Class<? extends Buildable>>> playerStructures = new HashMap<>();

    List<BuildingSite> buildingSiteGraph;
    List<Tile> tileList;
    List<Road> roadList;
    List<Port> portList;

    /**
     * Constructs a new GameBoard based on the number of players.
     * Initializes tiles, building sites, roads, and the dice roller.
     *
     * @param playerCount The number of players in the game.
     * @throws IllegalArgumentException if playerCount is less than or equal to 1.
     * @throws IllegalStateException    if board generation fails.
     */
    public GameBoard(int playerCount) {
        if (playerCount <= 1) {
            throw new IllegalArgumentException("Player count must be greater than 1.");
        }

        sizeOfBoard = calculateSizeOfBoard(playerCount);
        logger.infof("Generating Board for %d players, with %d Levels...%n", playerCount, sizeOfBoard);
        long starttime = System.nanoTime();

        generateTileList();
        generateBoard();

        this.diceRoller = new DiceRoller();
        subscribeTilesToDice();

        long endtime = System.nanoTime();

        // Something went wrong
        if (this.tileList == null || this.buildingSiteGraph == null || this.roadList == null || this.portList == null) {
            logger.errorf("Board generation failed for %d players.", playerCount);
            throw new IllegalStateException("Board generation resulted in null lists.");
        }

        logger.infof("Generated Board for %d players, with %d Levels in %fs%n".formatted(playerCount, sizeOfBoard, (endtime - starttime) * 10e-10));
    }

    /**
     * Calculates the appropriate size (number of rings) of the game board based on the player count.
     *
     * @param playerCount The number of players.
     * @return The calculated size of the board (number of rings).
     */
    static int calculateSizeOfBoard(int playerCount) {
        return switch (playerCount) {
            case 2, 3, 4 -> 3;
            case 5, 6 -> 4;
            case 7, 8 -> 5;
            default -> (int) Math.floor(Math.sqrt((double) (DEFAULT_TILES_PER_PLAYER_GOAL * playerCount - 1) / 3)) + 1;
        };
    }

    public Robber getRobber() {
        return robber;
    }

    /**
     * Generates the list of tiles for the game board using a {@link TileListDirector} and a {@link StandardTileListBuilder}.
     */
    void generateTileList() {
        TileListBuilder tileBuilder = new StandardTileListBuilder();
        TileListDirector director = new TileListDirector(tileBuilder);
        tileList = director.constructStandardTileList(sizeOfBoard, SIZE_OF_HEX, true);
        robber = new Robber();
        robber.diceRoller=diceRoller;
        robber.rob_Tile(findWaste());
    }
    Tile findWaste(){
        for(Tile tile : tileList){
            if(tile.getType().equals(TileType.WASTE)){
                return tile;
            }
        }
        return null;
    }
    /**
     * Generates the graph structure of building sites and roads for the game board.
     * This method relies on the tile list having been generated first.
     *
     * @throws IllegalStateException if the tile list has not been generated.
     */
    void generateBoard() {
        if (this.tileList == null) {
            throw new IllegalStateException("Cannot generate board graph before tile list is generated.");
        }

        GraphBuilder graphBuilder = new GraphBuilder(tileList, sizeOfBoard);
        buildingSiteGraph = graphBuilder.generateGraph();
        roadList = graphBuilder.getRoadList();
        portList = graphBuilder.getPortList();
    }

    /**
     * Places a building site for a player at the specified position on the board.
     *
     * @throws GameException if the placement is invalid (e.g., position occupied, rules violated).
     */
    public void placeSettlement(BuildRequest buildRequest) throws GameException {
        placeBuilding(buildRequest, new Settlement(buildRequest.player(), buildRequest.color()));
    }

    /**
     * Places a city for a player at the specified position on the board, upgrading an existing settlement.
     *
     * @throws GameException if the placement is invalid (e.g., no settlement to upgrade, rules violated).
     */
    public void placeCity(BuildRequest buildRequest) throws GameException {
        placeBuilding(buildRequest, new City(buildRequest.player(), buildRequest.color()));
    }

    /**
     * Internal helper method to place a generic building (settlement or city) on the board.
     * Checks for required resources and updates the building site.
     *
     * @param building The {@link Building} to be placed.
     * @throws GameException if resources are insufficient, position is invalid, or other rules are violated.
     */
    private void placeBuilding(BuildRequest buildRequest, Building building) throws GameException {
        try {
            Player player = building.getPlayer();
            if (!buildRequest.isSetupRound()) {
                checkRequiredResources(player, building);
            }
            checkBuildableCount(player.getUniqueId(), building);
            int positionId = buildRequest.positionId();
            logger.debugf("Placing building: playerId = %s, positionId = %s, type = %s", player.getUniqueId(), positionId, building.getClass().getSimpleName());
            BuildingSite buildingSite = buildingSiteGraph.get(positionId - 1);
            buildingSite.setBuilding(building);
            if (!buildRequest.isSetupRound()) {
                removeRequiredResources(player, building);
            }

            updatePlayerStructures(player.getUniqueId(), buildingSite, building);
        } catch (IndexOutOfBoundsException e) {
            throw new GameException("Settlement position not found: id = %s", buildRequest.positionId());
        }
    }

    /**
     * Places a road for a player at the specified road ID on the board.
     *
     * @throws GameException if the placement is invalid (e.g., road ID not found, rules violated).
     */
    public void placeRoad(BuildRequest buildRequest) throws GameException {
        try {
            Player player = buildRequest.player();
            PlayerColor color = buildRequest.color();
            int roadId = buildRequest.positionId();
            Road road = roadList.get(roadId - 1);
            if (!buildRequest.isSetupRound()) {
                checkRequiredResources(player, road);
                if (!hasAdjacentRoads(road, player)) {
                    throw new NoAdjacentRoadException();
                }
            }
            checkBuildableCount(player.getUniqueId(), road);
            logger.debugf("Placing road: playerId = %s, roadId = %s", player.getUniqueId(), roadId);
            road.setOwner(player);
            road.setColor(color);
            if (!buildRequest.isSetupRound()) {
                removeRequiredResources(player, road);
            }
            updatePlayerStructures(player.getUniqueId(), road, road);
        } catch (IndexOutOfBoundsException e) {
            throw new GameException("Road not found: id = %s", buildRequest.positionId());
        }
    }

    private boolean hasAdjacentRoads(Road road, Player player) {
        List<Road> currentRoadList = road.getAdjacentRoads().stream().filter(r -> r.getOwner() == player).toList();
        List<BuildingSite> buildingSites = road.getBuildingSites();
        for(Road adjacentRoad : currentRoadList){
            BuildingSite interMediateBuildingSite = adjacentRoad.getBuildingSites().stream().filter(buildingSites::contains).toList().get(0);
            if(interMediateBuildingSite.getBuildingOwner() == null || interMediateBuildingSite.getBuildingOwner() == player){
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the required resources from a player's inventory for a buildable item.
     *
     * @param player    The {@link Player} whose resources are to be removed.
     * @param buildable The {@link Buildable} item for which resources are required.
     * @throws GameException                  if the player is null or an error occurs during resource removal.
     * @throws InsufficientResourcesException if the player does not have enough resources.
     */
    private void removeRequiredResources(Player player, Buildable buildable) throws GameException {
        for (Map.Entry<TileType, Integer> entry : buildable.getRequiredResources().entrySet()) {
            TileType tileType = entry.getKey();
            Integer amount = entry.getValue();
            logger.debugf("Removing resource of player: playerId = %s, tileType = %s, amount = %s", player.getUniqueId(), tileType, amount);
            player.removeResource(tileType, amount);
        }
    }

    /**
     * Checks if a player has the required resources to build a specific item.
     *
     * @param player    The {@link Player} to check.
     * @param buildable The {@link Buildable} item.
     * @throws GameException                  if the player is null.
     * @throws InsufficientResourcesException if the player does not have enough of any required resource.
     */
    private void checkRequiredResources(Player player, Buildable buildable) throws GameException {
        if (player == null) {
            throw new GameException("Player must not be null");
        }
        logger.debugf("Checking if player has required amount of resources: playerId = %s, requiredResources = %s",
                player.getUniqueId(), buildable.getRequiredResources());
        for (Map.Entry<TileType, Integer> entry : buildable.getRequiredResources().entrySet()) {
            TileType tileType = entry.getKey();
            Integer amount = entry.getValue();
            if (player.getResourceCount(tileType) < amount) {
                throw new InsufficientResourcesException();
            }
        }
    }

    public long getPlayerStructureCount(String playerId, Class<? extends Buildable> buildableClass) {
        Map<Placable, Class<? extends Buildable>> structures = playerStructures.getOrDefault(playerId, new HashMap<>());
        return structures.values().stream().filter(b -> b == buildableClass).count();
    }

    private void updatePlayerStructures(String playerId, Placable placable, Buildable buildable) {
        Map<Placable, Class<? extends Buildable>> structures = playerStructures.getOrDefault(playerId, new HashMap<>());
        structures.put(placable, buildable.getClass());
        playerStructures.put(playerId, structures);
    }

    private void checkBuildableCount(String playerId, Buildable buildable) throws GameException {
        long buildableCount = getPlayerStructureCount(playerId, buildable.getClass());
        if (buildableCount >= buildable.getBuildLimit()) {
            throw new BuildableLimitReachedException(buildable);
        }
    }

    /**
     * Gets the list of all building sites on the game board.
     *
     * @return A list of {@link BuildingSite} objects.
     */
    public List<BuildingSite> getBuildingSitePositionGraph() {
        return buildingSiteGraph;
    }

    /**
     * Gets the list of all tiles on the game board.
     *
     * @return A list of {@link Tile} objects.
     */
    public List<Tile> getTileList() {
        return tileList;
    }

    /**
     * Gets the list of all roads on the game board.
     *
     * @return A list of {@link Road} objects.
     */
    public List<Road> getRoadList() {
        return roadList;
    }

    public Port getPortOfBuildingSite(int buildingSitePositionId) {
        return buildingSiteGraph.get(buildingSitePositionId - 1).getPort();
    }

    /**
     * Generates a JSON representation of the current game board state.
     * Includes information about tiles, building sites, roads, ports,
     * board size, and hex size.
     *
     * @return An {@link ObjectNode} containing the game board's JSON structure.
     */
    public ObjectNode getJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode boardNode = mapper.createObjectNode();

        // Components of Json
        ArrayNode tilesNode = mapper.createArrayNode();
        ArrayNode positionsNode = mapper.createArrayNode();
        ArrayNode roadsNode = mapper.createArrayNode();
        ArrayNode portsNode = mapper.createArrayNode();

        // Add tiles
        for (Tile tile : this.tileList) {
            tilesNode.add(tile.toJson());
        }

        // Add building sites
        for (BuildingSite position : this.buildingSiteGraph) {
            positionsNode.add(position.toJson());
        }

        // Add roads
        for (Road road : this.roadList) {
            roadsNode.add(road.toJson());
        }

        // Add Ports
        for (Port port : this.portList) {
            portsNode.add(port.toJson());
        }

        // Add the arrays to the main board node
        boardNode.set("tiles", tilesNode);
        boardNode.set("settlementPositions", positionsNode);
        boardNode.set("roads", roadsNode);
        boardNode.set("ports", portsNode);

        boardNode.put("ringsOfBoard", this.sizeOfBoard);
        boardNode.put("sizeOfHex", DEFAULT_TILES_PER_PLAYER_GOAL);

        return boardNode;
    }

    public String getLongestRoadPlayerId() {
        return longestRoadPlayerId;
    }

    public int getLongestRoadLength() {
        return longestRoadLength;
    }

    public void setLongestRoad(String playerId, int length) {
        this.longestRoadPlayerId = playerId;
        this.longestRoadLength = length;
        logger.infof("New Longest Road updated: Player %s with length %d", playerId, length);
    }

    /**
     * Subscribes all tiles on the board to the dice roller.
     * This allows tiles to be notified when dice are rolled to distribute resources.
     */
    private void subscribeTilesToDice() {
        tileList.forEach(tile -> tile.subscribeToDice(diceRoller));
    }

    /**
     * Rolls the dice using the board's {@link DiceRoller}.
     * This will trigger resource distribution based on the roll.
     *
     * @return An {@link ObjectNode} containing the dice roll result (dice1, dice2, total).
     */
    public ObjectNode rollDice() {
        return diceRoller.rollDice();
    }
}

