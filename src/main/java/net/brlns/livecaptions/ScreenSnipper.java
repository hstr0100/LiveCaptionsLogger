/*
 * Copyright (C) 2024 hstr0100
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.brlns.livecaptions;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import lombok.extern.slf4j.Slf4j;

/**
 * Allows users to select a region of the screen to watch for live captions.
 *
 * @author Gabriel / hstr0100 / vertx010
 */
@Slf4j
public class ScreenSnipper extends Window {

    private static final int X_INDEX = 0;
    private static final int Y_INDEX = 1;

    private final LiveCaptionsLogger main;
    private final double shadowRatio;

    private int[] screenStateCache;
    private int[] screenShadowCache;
    private BufferedImage screenBuffer;
    private Rectangle screenRect;
    private int startX;
    private int startY;
    private int endX;
    private int endY;

    private static final Font TEXT_FONT = new Font("SansSerif", Font.BOLD, 25);
    private static final Color TEXT_COLOR = Color.WHITE;

    public ScreenSnipper(LiveCaptionsLogger mainIn, Window ownerIn) {
        super(ownerIn);

        main = mainIn;
        shadowRatio = 0.6;
    }

    /**
     * Initializes the screen snipper.
     */
    public void init() {
        screenRect = getScreenBounds();
        captureReferenceScreenState();

        main.stopLiveCaptions();

        SnippingMouseListener sml = new SnippingMouseListener();
        addMouseListener(sml);
        addMouseMotionListener(sml);

        setAlwaysOnTop(true);
        setBounds(screenRect);
        setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
        setVisible(true);
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);

        if (screenStateCache != null) {
            if (screenBuffer == null) {
                screenBuffer = selectRegion(screenStateCache, screenShadowCache, screenRect.width, screenRect.height, startX, startY, endX, endY);
                drawTextOnImage(screenBuffer, "Drag the cursor to select the live captions area");
            }

            g.drawImage(screenBuffer, 0, 0, null);
        }
    }

    @Override
    public void update(Graphics g) {
        screenBuffer = selectRegion(screenStateCache, screenShadowCache, screenRect.width, screenRect.height, startX, startY, endX, endY);

        paint(g);
    }

    private void captureReferenceScreenState() {
        try {
            Robot robot = new Robot();
            BufferedImage screenshot = robot.createScreenCapture(screenRect);
            int width = screenRect.width, height = screenRect.height;
            screenStateCache = screenshot.getRGB(0, 0, width, height, null, 0, width);
            screenShadowCache = getShadow(screenStateCache, width, height);
        } catch (AWTException e) {
            main.handleException(e);
        }
    }

    private void setupZone(int startX, int startY, int endX, int endY) {
        try {
            main.setBounds(startX, startY, endX, endY);
        } finally {
            main.closeSnipper();
        }
    }

    private void drawTextOnImage(BufferedImage image, String text) {
        Graphics2D g = image.createGraphics();
        g.setFont(TEXT_FONT);
        FontMetrics metrics = g.getFontMetrics(TEXT_FONT);
        int x = (image.getWidth() - metrics.stringWidth(text)) / 2;
        int y = image.getHeight() / 2;
        g.setColor(TEXT_COLOR);
        g.drawString(text, x, y);
        g.dispose();
    }

    private BufferedImage selectRegion(int[] img, int[] imgShadow, int width, int height, int startX, int startY, int endX, int endY) {
        int[] copyImg = new int[width * height];
        int[] llc = {Math.min(startX, endX), Math.min(startY, endY)};
        int[] urc = {Math.max(startX, endX), Math.max(startY, endY)};
        System.arraycopy(imgShadow, 0, copyImg, 0, width * height);

        for (int y = llc[Y_INDEX]; y < urc[Y_INDEX]; y++) {
            System.arraycopy(img, y * width + llc[X_INDEX], copyImg, y * width + llc[X_INDEX], urc[X_INDEX] - llc[X_INDEX]);
        }

        BufferedImage finalImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        finalImg.getRaster().setDataElements(0, 0, width, height, copyImg);

        return finalImg;
    }

    private int[] getShadow(int[] img, int width, int height) {
        int[] copyImg = new int[width * height];
        for (int i = 0; i < copyImg.length; i++) {
            copyImg[i] = createShadow(img[i], shadowRatio);
        }

        return copyImg;
    }

    private int createShadow(int rgb, double ratio) {
        int red = Math.min(255, (int)(((rgb >> 16) & 0xFF) * ratio));
        int blue = Math.min(255, (int)(((rgb >> 8) & 0xFF) * ratio));
        int green = Math.min(255, (int)((rgb & 0xFF) * ratio));

        return (red << 16) + (blue << 8) + green;
    }

    private void setStartingPoint(int x, int y) {
        startX = x;
        startY = y;
    }

    private void setEndPoint(int x, int y) {
        endX = x;
        endY = y;
    }

    /**
     * Retrieves the bounds of all screens combined.
     */
    private Rectangle getScreenBounds() {
        Rectangle bounds = new Rectangle(0, 0, 0, 0);
        for (GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }

        return bounds;
    }

    /**
     * Mouse listener for selecting the screen region.
     */
    private class SnippingMouseListener extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent e) {
            setStartingPoint(e.getX(), e.getY());
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            setEndPoint(e.getX(), e.getY());
            update(getGraphics());
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            setEndPoint(e.getX(), e.getY());
            setupZone(startX, startY, endX, endY);
        }
    }
}
