package com.orbekk;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbekk.same.SameInterface;
import com.orbekk.same.StateChangedListener;
import com.orbekk.same.UpdateConflict;

import android.graphics.Paint;

public class GameController implements StateChangedListener {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private List<Player> remotePlayers = new ArrayList<Player>();
    private Player localPlayer;
    private ChangeListener changeListener = null;
    private SameInterface same;
    
    public static class Player {
        public Paint color;
        public float posX;
        public float posY;
    }
    
    public interface ChangeListener {
        void playerStatesChanged();
    }
    
    public static Player newPlayer() {
        Player player = new Player();
        player.color = new Paint();
        player.color.setARGB(255, 255, 0, 0);
        player.posX = 0.5f;
        player.posY = 0.5f;
        return player;
    }
    
    public static GameController create(Player localPlayer,
            SameInterface same) {
        GameController controller = new GameController(localPlayer, same);
        same.addStateChangedListener(controller);
        return controller;
    }
    
    GameController(Player localPlayer, SameInterface same) {
        this.localPlayer = localPlayer;
        this.same = same;
    }
    
    public void setMyPosition(float x, float y) {
        this.localPlayer.posX = x;
        this.localPlayer.posY = y;
        changeListener.playerStatesChanged();
        try {
            same.set("position", x + "," + y);
        } catch (UpdateConflict e) {
            logger.warn("Update failed.", e);
        }
    }
    
    public Player getLocalPlayer() {
        return localPlayer;
    }
    
    public List<Player> getRemotePlayers() {
        return remotePlayers;
    }
    
    public void setChangeListener(ChangeListener listener) {
        this.changeListener = listener;
    }

    @Override
    public void stateChanged(String id, String data) {
        logger.info("StateChanged({}, {})", id, data);        
    }
}
