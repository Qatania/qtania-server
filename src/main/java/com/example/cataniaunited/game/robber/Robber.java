package com.example.cataniaunited.game.robber;

import com.example.cataniaunited.game.board.Placable;
import com.example.cataniaunited.game.board.tile_list_builder.Tile;
import com.example.cataniaunited.game.dice.DiceRoller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Robber implements Placable {
    public double[] coordinates;
    public int tile_Value;
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

        coordsNode.add(this.coordinates[0]); // Add x
        coordsNode.add(this.coordinates[1]); // Add y
        robberNode.set("coordinates", coordsNode);
        return null;
    }

    public void rob_Tile(Tile tile){
        move_Away();
        tile_Value=tile.getValue();
        tile.setValue(0);
        diceRoller.removeSubscriber(tile);
        this.tile = tile;
    }

    public void move_Away(){
        if(tile!=null){
            tile.setValue(tile_Value);
            diceRoller.addSubscriber(tile);
        }
    }
}
