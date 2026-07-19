package com.example.cataniaunited.mapper;

import com.example.cataniaunited.dto.PlayerInfo;
import com.example.cataniaunited.game.board.tile_list_builder.TileType;
import com.example.cataniaunited.lobby.Lobby;
import com.example.cataniaunited.player.Player;
import com.example.cataniaunited.player.PlayerColor;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@QuarkusTest
class PlayerMapperTest {

    @Inject
    PlayerMapper playerMapper;

    Player playerMock;

    @BeforeEach
    void setup() {
        playerMock = new Player("Player 1");
    }

    @Test
    void mapPlayerColorShouldReturnHexCode() {
        Lobby lobbyMock = mock(Lobby.class);
        when(lobbyMock.getPlayerColor(playerMock.getUniqueId())).thenReturn(PlayerColor.CYAN);

        String colorHex = playerMapper.mapPlayerColor(playerMock, lobbyMock);
        assertEquals(PlayerColor.CYAN.getHexCode(), colorHex);
    }

    @Test
    void mapPlayerNameShouldReturnNullIfPlayerHasNoColor() {
        Lobby lobbyMock = mock(Lobby.class);
        when(lobbyMock.getPlayerColor(playerMock.getUniqueId())).thenReturn(null);
        String colorHex = playerMapper.mapPlayerColor(playerMock, lobbyMock);
        assertNull(colorHex);
    }

    @Test
    void isHostShouldReturnTrueIfPlayerIsHost() {
        Lobby lobbyMock = new Lobby("1234", playerMock.getUniqueId());
        assertTrue(playerMapper.isHost(playerMock, lobbyMock));
    }

    @Test
    void isHostShouldReturnFalseIfPlayerIsHost() {
        Lobby lobbyMock = new Lobby("1234", "123");
        assertFalse(playerMapper.isHost(playerMock, lobbyMock));
    }

    @Test
    void isActivePlayerShouldReturnTrueIfItIsPlayersTurn() {
        Lobby lobbyMock = new Lobby("1234", playerMock.getUniqueId());
        lobbyMock.setActivePlayer(playerMock.getUniqueId());
        assertTrue(playerMapper.isActivePlayer(playerMock, lobbyMock));
    }

    @Test
    void isActivePlayerShouldReturnFalseIfItIsNotPlayersTurn() {
        Lobby lobbyMock = new Lobby("1234", playerMock.getUniqueId());
        lobbyMock.setActivePlayer("anotherPlayer");
        assertFalse(playerMapper.isActivePlayer(playerMock, lobbyMock));
    }

    @Test
    void canRollDiceShouldReturnTrueIfItPlayerCanRollDice() {
        Lobby lobbyMock = spy(new Lobby("1234", playerMock.getUniqueId()));
        doReturn(true).when(lobbyMock).canRollDice(playerMock.getUniqueId());
        assertTrue(playerMapper.canRollDice(playerMock, lobbyMock));
    }

    @Test
    void canRollDiceShouldReturnTrueIfItPlayerCannotRollDice() {
        Lobby lobbyMock = spy(new Lobby("1234", playerMock.getUniqueId()));
        doReturn(false).when(lobbyMock).canRollDice(playerMock.getUniqueId());
        assertFalse(playerMapper.canRollDice(playerMock, lobbyMock));
    }

    @Test
    void testToDto() {
        Lobby lobbyMock = new Lobby("1234", playerMock.getUniqueId());
        lobbyMock.setActivePlayer(playerMock.getUniqueId());
        lobbyMock.addVictoryPoints(playerMock.getUniqueId(), 7);
        PlayerColor expectedColor = lobbyMock.getPlayerColor(playerMock.getUniqueId());
        playerMock.receiveResource(TileType.WHEAT, 3);
        playerMock.receiveResource(TileType.SHEEP, 1);
        playerMock.receiveResource(TileType.ORE, 0);

        PlayerInfo playerInfo = playerMapper.toDto(playerMock, lobbyMock);

        assertEquals(playerMock.getUniqueId(), playerInfo.id());
        assertEquals(playerMock.getUsername(), playerInfo.username());
        assertEquals(expectedColor.getHexCode(), playerInfo.color());
        assertFalse(playerInfo.isReady());
        assertTrue(playerInfo.isHost());
        assertTrue(playerInfo.isActivePlayer());
        assertTrue(playerInfo.canRollDice());
        assertTrue(playerInfo.isSetupRound());
        assertEquals(7, playerInfo.victoryPoints());
        assertEquals(3, playerInfo.resources().get(TileType.WHEAT));
        assertEquals(1, playerInfo.resources().get(TileType.SHEEP));
        assertEquals(0, playerInfo.resources().get(TileType.ORE));

    }

    @Test
    void toDToShouldReturnNullIfPlayerIsNull() {
        Lobby lobbyMock = new Lobby("1234", playerMock.getUniqueId());
        lobbyMock.setActivePlayer(playerMock.getUniqueId());
        assertNull(playerMapper.toDto(null, lobbyMock));
    }

    @Test
    void toDtoShouldMapResourcesToNullIfPlayerResourcesAreNull() {
        Player playerSpy = spy(new Player("Player 2"));
        Lobby lobbyMock = new Lobby("1234", playerSpy.getUniqueId());
        lobbyMock.setActivePlayer(playerSpy.getUniqueId());
        when(playerSpy.getResources()).thenReturn(null);

        PlayerInfo playerInfo = playerMapper.toDto(playerSpy, lobbyMock);
        assertNull(playerInfo.resources());
    }

    @Test
    void isSetupRoundShouldBeFalseIfItIsNotSetupRound() {
        Lobby lobbyMock = spy(new Lobby("1234", playerMock.getUniqueId()));
        doReturn(3).when(lobbyMock).getRoundsPlayed();
        PlayerInfo playerInfo = playerMapper.toDto(playerMock, lobbyMock);
        assertFalse(playerInfo.isSetupRound());
    }

}
