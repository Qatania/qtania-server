package com.example.cataniaunited.dto;

import com.example.cataniaunited.game.board.tile_list_builder.TileType;

import java.util.Map;

public record PlayerInfo(
        String id,
        String username,
        String color,
        boolean isHost,
        boolean isReady,
        boolean isActivePlayer,
        boolean canRollDice,
        boolean isSetupRound,
        boolean needsToMoveRobber,
        int victoryPoints,
        Map<TileType, Integer> resources) {
}
