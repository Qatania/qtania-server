package com.example.cataniaunited.exception.ui;

import com.example.cataniaunited.exception.GameException;

public class RobberPlacementException extends GameException {

    private static final String DEFAULT_MESSAGE = "Player is not allowed to move robber!";

    public RobberPlacementException() {
        super(DEFAULT_MESSAGE);
    }
}
