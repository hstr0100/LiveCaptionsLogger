package net.brlns.livecaptions;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;

public class ScreenSnipper extends Window{

    private static final int X_INDEX = 0;
    private static final int Y_INDEX = 1;

    private int[] screenStateCache;
    private int[] screenShadowCache;
    private BufferedImage screenBuffer;
    private Rectangle screenRect;
    private int startX;
    private int startY;
    private int endX;
    private int endY;
    private double shadowRatio;
    private LiveCaptionsLogger main;

    private static final Font TEXT_FONT = new Font("SansSerif", Font.BOLD, 25);
    private static final Color TEXT_COLOR = Color.WHITE;

    public ScreenSnipper(LiveCaptionsLogger main, Window owner){
        super(owner);
        this.main = main;
        this.shadowRatio = 0.6;
        screenRect = getScreenBounds();
        takeScreenShot();

        if(LiveCaptionsLogger.isWindows()){
            try{
                //It gets in the way
                Process process = Runtime.getRuntime().exec("taskkill /f /im LiveCaptions.exe");
                process.waitFor();
            }catch(IOException e){
                main.handleException(e);
            }catch(InterruptedException e){
                //Nothing
            }
        }

        setupUI();
    }

    private void setupUI(){
        SnippingMouseListener sml = new SnippingMouseListener();
        addMouseListener(sml);
        addMouseMotionListener(sml);
        setAlwaysOnTop(true);
        setBounds(screenRect);
        setCursor(new Cursor(Cursor.CROSSHAIR_CURSOR));
        setVisible(true);
    }

    @Override
    public void paint(Graphics g){
        super.paint(g);
        if(screenStateCache != null){
            if(screenBuffer == null){
                screenBuffer = selectRegion(screenStateCache, screenShadowCache, screenRect.width, screenRect.height, startX, startY, endX, endY);
                drawTextOnImage(screenBuffer, "Drag the cursor to select the live captions area");
            }

            g.drawImage(screenBuffer, 0, 0, null);
        }
    }

    @Override
    public void update(Graphics g){
        screenBuffer = selectRegion(screenStateCache, screenShadowCache, screenRect.width, screenRect.height, startX, startY, endX, endY);
        paint(g);
    }

    private void takeScreenShot(){
        try{
            Robot robot = new Robot();
            BufferedImage screenShot = robot.createScreenCapture(screenRect);
            int width = screenRect.width, height = screenRect.height;
            screenStateCache = screenShot.getRGB(0, 0, width, height, null, 0, width);
            screenShadowCache = getShadow(screenStateCache, width, height);
        }catch(AWTException e){
            main.handleException(e);
        }
    }

    private void setupZone(int startX, int startY, int endX, int endY){
        Toolkit toolkit = Toolkit.getDefaultToolkit();

        int screenResolution = toolkit.getScreenResolution();
        double scalingFactor = 96.0 / screenResolution;

        try{
            main.setBounds(
                (int)(startX / scalingFactor),
                (int)(startY / scalingFactor),
                (int)(endX / scalingFactor),
                (int)(endY / scalingFactor));
        }catch(Exception e){
            main.handleException(e);
        }

        main.closeSnipper();
    }

    private void drawTextOnImage(BufferedImage image, String text){
        Graphics2D g = image.createGraphics();
        g.setFont(TEXT_FONT);
        FontMetrics metrics = g.getFontMetrics(TEXT_FONT);
        int x = (image.getWidth() - metrics.stringWidth(text)) / 2;
        int y = image.getHeight() / 2;
        g.setColor(TEXT_COLOR);
        g.drawString(text, x, y);
        g.dispose();
    }

    private BufferedImage selectRegion(int[] img, int[] imgShadow, int width, int height, int startX, int startY, int endX, int endY){
        int[] copyImg = new int[width * height];
        int[] llc = {Math.min(startX, endX), Math.min(startY, endY)};
        int[] urc = {Math.max(startX, endX), Math.max(startY, endY)};
        System.arraycopy(imgShadow, 0, copyImg, 0, width * height);

        for(int y = llc[Y_INDEX]; y < urc[Y_INDEX]; y++){
            System.arraycopy(img, y * width + llc[X_INDEX], copyImg, y * width + llc[X_INDEX], urc[X_INDEX] - llc[X_INDEX]);
        }

        BufferedImage finalImg = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        finalImg.getRaster().setDataElements(0, 0, width, height, copyImg);
        return finalImg;
    }

    private int[] getShadow(int[] img, int width, int height){
        int[] copyImg = new int[width * height];
        for(int i = 0; i < copyImg.length; i++){
            copyImg[i] = makeShadow(img[i], shadowRatio);
        }

        return copyImg;
    }

    private int makeShadow(int rgb, double ratio){
        int red = Math.min(255, (int)(((rgb >> 16) & 0xFF) * ratio));
        int blue = Math.min(255, (int)(((rgb >> 8) & 0xFF) * ratio));
        int green = Math.min(255, (int)((rgb & 0xFF) * ratio));
        return (red << 16) + (blue << 8) + green;
    }

    private void setStartingPoint(int x, int y){
        startX = x;
        startY = y;
    }

    private void setEndPoint(int x, int y){
        endX = x;
        endY = y;
    }

    private Rectangle getScreenBounds(){
        Rectangle bounds = new Rectangle(0, 0, 0, 0);
        for(GraphicsDevice gd : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
            bounds = bounds.union(gd.getDefaultConfiguration().getBounds());
        }

        return bounds;
    }

    private class SnippingMouseListener extends MouseAdapter{

        @Override
        public void mousePressed(MouseEvent e){
            setStartingPoint(e.getX(), e.getY());
        }

        @Override
        public void mouseDragged(MouseEvent e){
            setEndPoint(e.getX(), e.getY());
            update(getGraphics());
        }

        @Override
        public void mouseReleased(MouseEvent e){
            setEndPoint(e.getX(), e.getY());
            setupZone(startX, startY, endX, endY);
        }
    }
}
