package com.example.cataniaunited.game.robber;

import com.example.cataniaunited.game.board.GameBoard;
import com.example.cataniaunited.game.board.Placable;
import com.example.cataniaunited.game.board.tile_list_builder.Tile;
import com.example.cataniaunited.game.dice.DiceRoller;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public class Robber implements Placable {
    public double[] coordinates;
    public int tile_ID;
    public Tile tile;

    public DiceRoller diceRoller;
    public Robber() {

    }

    @Override
    public double[] getCoordinates() {
        return coordinates;
    }

    @Override
    public ObjectNode toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode robberNode = mapper.createObjectNode();
        ArrayNode coordsNode = mapper.createArrayNode();

        if (this.coordinates != null && this.coordinates.length >= 2) {
            coordsNode.add(this.coordinates[0]); // Add x coordinate
            coordsNode.add(this.coordinates[1]); // Add y coordinate
        }

        robberNode.put("tile_ID", this.tile_ID);
        robberNode.set("coordinates", coordsNode);

        return robberNode; // Fixed: returned the constructed ObjectNode
    }

    public void rob_Tile(Tile tile){
        move_Away();
        tile_ID=tile.getValue();
        tile.setValue(0);
        diceRoller.removeSubscriber(tile);
        this.tile = tile;
    }
    public void rob_Player(PlayerService playerService){
    List< Player > players  = playerService.getAllPlayers();
        for (Player player:players) {
            if(Math.floor((player.getResources().size()/2))>7){
                player.halfResources();
            }

        }
    }

    public void move_Away(){
        if(tile!=null){
            tile.setValue(tile_ID);
            diceRoller.addSubscriber(tile);
        }
    }

    //TODO: Missing steal from other Player random card
}
