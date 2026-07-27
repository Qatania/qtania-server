package com.example.cataniaunited.game.robber;

import com.example.cataniaunited.game.board.Placable;
import com.example.cataniaunited.game.board.tile_list_builder.Tile;
import com.example.cataniaunited.game.dice.DiceRoller;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;

import java.util.List;

public class Robber implements Placable {
    public double[] coordinates;
    public int tileID;
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

        robberNode.put("tile_ID", this.tileID);
        robberNode.set("coordinates", coordsNode);

        return robberNode; // Fixed: returned the constructed ObjectNode
    }

    public void robTile(Tile tile){
        if(tile!=null) {
            moveAway();
            tileID = tile.getId();
            coordinates = tile.getCoordinates();
            diceRoller.removeSubscriber(tile);
            this.tile = tile;
        }else {
            Log.warnf("Tile has not been found. ");
        }
    }

    public void moveAway(){
        if(tile!=null){
            diceRoller.addSubscriber(tile);
        }else {
            Log.warnf("Tile has not been found. ");
        }
    }

    //TODO: Missing steal from other Player random card
}
