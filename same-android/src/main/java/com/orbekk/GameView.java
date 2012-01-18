package com.orbekk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orbekk.same.State.Component;
import com.orbekk.same.ClientService;
import com.orbekk.same.ClientServiceImpl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class GameView extends SurfaceView implements SurfaceHolder.Callback {
    private Logger logger = LoggerFactory.getLogger(getClass());
    private GameThread thread;
    
    static class GameThread extends Thread {
        private Logger logger = LoggerFactory.getLogger(getClass());
        private int height = 0;
        private int width = 0;
        private float posX;
        private float posY;
        private SurfaceHolder holder;
        private Context context;
        private Paint background;
        private Paint paint;
        private ClientServiceImpl client;
        
        public GameThread(SurfaceHolder holder, Context context,
                ClientServiceImpl client) {
            this.holder = holder;
            this.context = context;
            this.client = client;
            posX = 100;
            posY = 100;
            paint = new Paint();
            paint.setAntiAlias(true);
            paint.setARGB(255, 255, 0, 0);
            background = new Paint();
            background.setARGB(255, 0, 0, 0);
        }
        
        public void setSize(int width, int height) {
            synchronized(holder) {
                this.width = width;
                this.height = height;
            }
        }
        
        private void doDraw(Canvas c) {
            c.drawRect(0.0f, 0.0f, width+1.0f, height+1.0f, background);
            c.drawCircle(posX, posY, 20.0f, paint);
        }
        
        @Override public void run() {
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                synchronized(holder) {
                    doDraw(c);
                }
            } finally {
                holder.unlockCanvasAndPost(c);
            }
        }
        
        private synchronized void setPosition(float x, float y) {
            posX = x;
            posY = y;
            run();
            long rev = 0;
            Component c = client.getState("position");
            if (c != null) {
                rev = c.getRevision();
            }
                
            if (client.sendStateUpdate("position", this.posX + "," + this.posY,
                    rev + 1)) {
                logger.warn("Unable to set state.");
            }
        }
    }
    
    public GameView(Context context, ClientServiceImpl client) {
        super(context);
        getHolder().addCallback(this);
        thread = new GameThread(getHolder(), context, client);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = new Paint();
        paint.setARGB(255, 255, 0, 0);
        canvas.drawCircle(50.0f, 50.0f, 50.0f, paint);
    }
    
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {
        logger.info("SurfaceChanged(w={}, h={})", width, height);
        thread.setSize(width, height);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        logger.info("SurfaceCreated()");
        thread.start();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        logger.info("SurfaceDestroyed()");
        // TODO: Stop thread.
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        thread.setPosition(e.getX(), e.getY());
        return true;
    }

}
