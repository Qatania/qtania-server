package com.example.cataniaunited.mapper;

import com.example.cataniaunited.dto.PlayerInfo;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.Objects;

@Mapper(componentModel = "cdi", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PlayerMapper {

    @Mapping(source = "player.uniqueId", target = "id")
    @Mapping(target = "victoryPoints", expression = "java( mapVictoryPoints(player, lobby) )")
    @Mapping(target = "color", expression = "java( mapPlayerColor(player, lobby) )")
    @Mapping(target = "isHost", expression = "java( isHost(player, lobby) )")
    @Mapping(target = "isReady", expression = "java( isReady(player, lobby) )")
    @Mapping(target = "isActivePlayer", expression = "java( isActivePlayer(player, lobby) )")
    @Mapping(target = "canRollDice", expression = "java( canRollDice(player, lobby) )")
    @Mapping(target = "isSetupRound", expression = "java( lobby.getRoundsPlayed() <= 1 )")
    PlayerInfo toDto(Player player, @Context Lobby lobby);

    default String mapPlayerColor(Player player, @Context Lobby lobby) {
        PlayerColor color = lobby.getPlayerColor(player.getUniqueId());
        if (color == null) {
            return null;
        }
        return color.getHexCode();
    }

    default boolean isHost(Player player, @Context Lobby lobby) {
        return Objects.equals(lobby.getHostPlayer(), player.getUniqueId());
    }

    default boolean isReady(Player player, @Context Lobby lobby) {
        return lobby.isReady(player.getUniqueId());
    }

    default boolean isActivePlayer(Player player, @Context Lobby lobby) {
        return player.getUniqueId().equals(lobby.getActivePlayer());
    }

    default boolean canRollDice(Player player, @Context Lobby lobby) {
        return lobby.canRollDice(player.getUniqueId());
    }

    default int mapVictoryPoints(Player player, @Context Lobby lobby) {
        return lobby.getVictoryPoints(player.getUniqueId());
    }
}
