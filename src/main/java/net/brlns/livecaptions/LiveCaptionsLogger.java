package net.brlns.livecaptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.event.ActionEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileSystemView;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.commons.text.similarity.JaroWinklerDistance;

public class LiveCaptionsLogger{

    /**
     * Constants and application states
     */
    private static final String REGISTRY_APP_NAME = "LiveCaptionsLogger";//Don't change

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    private final ReentrantLock tesseractLock = new ReentrantLock();

    private File currentFile;
    private String lastPrintedLine;
    private final List<String> lastLines = new ArrayList<>();

    private final SystemTray tray;
    private TrayIcon trayIcon = null;

    private Rectangle screenZone;

    private File configFile = new File("config.json");
    private Config config;

    private ObjectMapper objectMapper = new ObjectMapper();

    private ScreenSnipper snipper = null;

    private final AtomicInteger currentTick = new AtomicInteger(0);

    public LiveCaptionsLogger(){
        if(!configFile.exists()){
            try{
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, new Config());
            }catch(IOException e){
                handleException(e);
            }
        }

        try{
            config = objectMapper.readValue(configFile, Config.class);
        }catch(IOException e){
            config = new Config();
            updateConfig();

            handleException(e);
        }

        if(config.isDebugMode()){
            System.out.println("Loaded config file");
        }

        tray = SystemTray.getSystemTray();

        try{
            //TODO: move this to its own class, migrate to JPopupMenu to add mouse over tooltips
            PopupMenu popup = new PopupMenu();

            {
                MenuItem menuItem = new MenuItem("Toggle LiveCaptions Logging");
                menuItem.addActionListener((ActionEvent e) -> {
                    config.setCurrentlyLogging(!config.isCurrentlyLogging());

                    if(!config.isCurrentlyLogging()){
                        closeLogger();
                    }

                    updateConfig();

                    trayIcon.displayMessage(REGISTRY_APP_NAME, "Live caption logging is now " + (config.isCurrentlyLogging() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
                });

                popup.add(menuItem);
            }

            {//In case the text is NOT black and white you might need to toggle this
                MenuItem menuItem = new MenuItem("Toggle Contrast Mode");
                menuItem.addActionListener((ActionEvent e) -> {
                    config.setContrastMode(!config.isContrastMode());

                    updateConfig();

                    trayIcon.displayMessage(REGISTRY_APP_NAME, "Contrast mode is now " + (config.isContrastMode() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
                });

                popup.add(menuItem);
            }

            {
                MenuItem menuItem = new MenuItem("Toggle Logging Any Text");
                menuItem.addActionListener((ActionEvent e) -> {
                    config.setCaptureAnyText(!config.isCaptureAnyText());

                    updateConfig();

                    trayIcon.displayMessage(REGISTRY_APP_NAME, "Logging of any text within the capture window is now " + (config.isCaptureAnyText() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
                });

                popup.add(menuItem);
            }

            if(isWindows()){
                MenuItem menuItem = new MenuItem("Toggle Auto Start");
                menuItem.addActionListener((ActionEvent e) -> {
                    try{
                        boolean result = checkStartupStatusAndToggle();

                        trayIcon.displayMessage(REGISTRY_APP_NAME, "Application auto start is now " + (result ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
                    }catch(Exception e1){
                        handleException(e1);
                    }
                });

                popup.add(menuItem);
            }

            {//Debug output is displayed in the console when the program is started via cmd
                MenuItem menuItem = new MenuItem("Toggle Debug Mode");
                menuItem.addActionListener((ActionEvent e) -> {
                    config.setDebugMode(!config.isDebugMode());

                    updateConfig();

                    trayIcon.displayMessage(REGISTRY_APP_NAME, "Debug mode is now " + (config.isDebugMode() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
                });

                popup.add(menuItem);
            }

            {
                MenuItem menuItem = new MenuItem("Configure Captions Area");
                menuItem.addActionListener((ActionEvent e) -> {
                    try{
                        this.openSnipper();
                    }catch(Exception e1){
                        handleException(e1);
                    }
                });

                popup.add(menuItem);
            }

            {
                MenuItem menuItem = new MenuItem("Configure Output Folder");
                menuItem.addActionListener((ActionEvent e) -> {
                    try{
                        this.openDirectoryPicker();
                    }catch(Exception e1){
                        handleException(e1);
                    }
                });

                popup.add(menuItem);
            }

            {
                MenuItem menuItem = new MenuItem("Start Live Captions");
                menuItem.addActionListener((ActionEvent e) -> {
                    startLiveCaptions();
                });

                popup.add(menuItem);
            }

            {
                MenuItem menuItem = new MenuItem("Exit");
                menuItem.addActionListener((ActionEvent e) -> {
                    System.out.println("Exiting....");

                    try{
                        closeLogger();
                    }catch(Exception e1){
                        handleException(e1);
                    }finally{
                        System.exit(0);
                    }
                });

                popup.add(menuItem);
            }

            //Register to the system tray
            Image image = Toolkit.getDefaultToolkit().createImage(this.getClass().getResource("/assets/tray_icon.png"));
            trayIcon = new TrayIcon(image, "CC", popup);
            trayIcon.setToolTip(REGISTRY_APP_NAME);
            trayIcon.setImageAutoSize(true);

            trayIcon.addActionListener((ActionEvent e) -> {
                try{
                    File file = getOrCreateOutputDirectory();

                    Desktop.getDesktop().open(file);
                }catch(IOException e1){
                    handleException(e1);
                }
            });

            tray.add(trayIcon);

            Robot robot = new Robot();

            updateScreenZone();

            File tessDataFolder;
            if(config.getCustomTessDataPath().isEmpty()){
                tessDataFolder = LoadLibs.extractTessResources("tessdata");
            }else{
                tessDataFolder = new File(config.getCustomTessDataPath());
            }

            if(!tessDataFolder.exists()){
                throw new RuntimeException("tessdata folder not found!");
            }

            Tesseract tesseract = new Tesseract();

            tesseract.setDatapath(tessDataFolder.getAbsolutePath());
            tesseract.setLanguage(config.getTessLanguage());

            if(config.isDebugMode()){
                System.out.println("Tesseract initialized");
            }

            JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();

            Runnable captureAndProcess = () -> {
                int tick = currentTick.incrementAndGet();

                if(config.isDebugMode()){
                    System.out.println("Tick #" + tick);
                }

                if(!config.isCurrentlyLogging()){
                    System.out.println("Logging is off");
                    return;
                }

                if(config.isDebugMode()){
                    System.out.println("Start capture at " + screenZone);
                }

                BufferedImage screenshot = robot.createScreenCapture(screenZone);

                if(config.isDebugMode()){
                    System.out.println("Captured");
                }

                if(config.isDebugMode() && tick % 10 == 0){
                    try{
                        saveDebugImage(screenshot, "cc_debug.png");

                        System.out.println("Saved debug image");
                    }catch(IOException e){
                        handleException(e);
                    }
                }

                if(!inCaptionBox(screenshot)){
                    if(config.isDebugMode()){
                        System.out.println("CC Window not detected");
                    }

                    closeLogger();
                    return;
                }

                BufferedImage filteredImage = filterWhite(screenshot);

                if(tesseractLock.tryLock()){
                    try{
                        //This needs a more elegant solution, Tesseract keeps hanging with random images
                        ExecutorService executorService = Executors.newSingleThreadExecutor();

                        executorService.execute(() -> {
                            try{
                                String text = tesseract.doOCR(filteredImage);

                                text = text.replace("|", "I"); //This one is particularly common

                                if(text.contains("(") || text.contains(")")){//Not sure if those ever actually show up?
                                    return;
                                }

                                if(config.isDebugMode()){
                                    System.out.println("OCR Saw: " + text);
                                }

                                String[] lines = text.split("\\n");

                                synchronized(lastLines){
                                    for(String line : lines){
                                        boolean contains = false;
                                        for(String oldLine : lastLines){
                                            //This checks if more than 80% of a line matches the other
                                            double distance = jaroWinklerDistance.apply(oldLine, line);
                                            if(config.isDebugMode()){
                                                System.out.println("Distance between previous line " + distance + " " + oldLine + ":" + line);
                                            }

                                            if(distance <= 0.20 || oldLine.contains(line)){
                                                contains = true;
                                            }
                                        }

                                        if(!contains && !lastLines.isEmpty()){
                                            String oldestEntry = lastLines.remove(0);

                                            double distance = jaroWinklerDistance.apply(oldestEntry,
                                                lastPrintedLine != null ? lastPrintedLine : "");
                                            if(config.isDebugMode()){
                                                System.out.println("Distance from the last line " + distance);
                                            }

                                            if(distance > 0.20){
                                                lastPrintedLine = oldestEntry;
                                                logToFile(oldestEntry);
                                            }
                                        }
                                    }

                                    lastLines.clear();
                                    lastLines.addAll(Arrays.asList(lines));
                                }
                            }catch(TesseractException e){
                                e.printStackTrace();
                            }
                        });

                        executorService.shutdown();

                        try{
                            if(!executorService.awaitTermination(3000, TimeUnit.MILLISECONDS)){
                                System.out.println("Skipping a tick, OCR took too long");
                            }
                        }catch(InterruptedException e){
                            e.printStackTrace();
                        }
                    }finally{
                        tesseractLock.unlock();
                    }
                }else{
                    if(config.isDebugMode()){
                        System.out.println("Could not acquire lock. Skipping OCR.");
                    }
                }
            };

            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(captureAndProcess, 0, 1000, TimeUnit.MILLISECONDS);//1 Second? seems ok
        }catch(Exception e){
            handleException(e);
        }
    }

    private void openDirectoryPicker(){
        JFileChooser fileChooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        int result = fileChooser.showOpenDialog(null);

        if(result == JFileChooser.APPROVE_OPTION){
            String selectedDirectory = fileChooser.getSelectedFile().getAbsolutePath();

            if(config.isDebugMode()){
                System.out.println("Selected Directory: " + selectedDirectory);
            }

            trayIcon.displayMessage(REGISTRY_APP_NAME, "Output path set to " + selectedDirectory, TrayIcon.MessageType.INFO);

            config.setOutputPath(selectedDirectory);

            updateConfig();
            closeLogger();
        }
    }

    private void updateScreenZone(){
        AffineTransform transform = getGraphicsTransformAt(config.getBoxStartX(), config.getBoxStartY());
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();

        if(config.isDebugMode()){
            System.out.println("Scaling Factor 1: " + "X " + scaleX + " Y " + scaleY);
        }

        int scaledBoxStartX = (int)(config.getBoxStartX() / scaleX);
        int scaledBoxStartY = (int)(config.getBoxStartY() / scaleY);
        int scaledBoxEndX = (int)(config.getBoxEndX() / scaleX);
        int scaledBoxEndY = (int)(config.getBoxEndY() / scaleY);

        if(config.isDebugMode()){
            System.out.println("Real Rectangle Coordinates:");
            System.out.println("StartX: " + config.getBoxStartX());
            System.out.println("StartY: " + config.getBoxStartY());
            System.out.println("EndX: " + config.getBoxEndX());
            System.out.println("EndY: " + config.getBoxEndY());

            System.out.println("Scaled Rectangle Coordinates:");
            System.out.println("StartX: " + scaledBoxStartX);
            System.out.println("StartY: " + scaledBoxStartY);
            System.out.println("EndX: " + scaledBoxEndX);
            System.out.println("EndY: " + scaledBoxEndY);
        }

        screenZone = new Rectangle(scaledBoxStartX, scaledBoxStartY,
            scaledBoxEndX - scaledBoxStartX,
            scaledBoxEndY - scaledBoxStartY);
    }

    public void setBounds(int startX, int startY, int endX, int endY){
        AffineTransform transform = getGraphicsTransformAt(config.getBoxStartX(), config.getBoxStartY());
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();

        int scaledStartX = (int)(startX * scaleX);
        int scaledStartY = (int)(startY * scaleY);
        int scaledEndX = (int)(endX * scaleX);
        int scaledEndY = (int)(endY * scaleY);

        if(config.isDebugMode()){
            System.out.println("Scaling Factor 2: " + "X " + scaleX + " Y " + scaleY);

            System.out.println("Provided Rectangle Coordinates:");
            System.out.println("StartX: " + startX);
            System.out.println("StartY: " + startY);
            System.out.println("EndX: " + endX);
            System.out.println("EndY: " + endY);

            System.out.println("Provided Scaled Rectangle Coordinates:");
            System.out.println("StartX: " + scaledStartX);
            System.out.println("StartY: " + scaledStartY);
            System.out.println("EndX: " + scaledEndX);
            System.out.println("EndY: " + scaledEndY);
        }

        if(Math.abs(scaledEndX - scaledStartX) > 5 && Math.abs(scaledEndY - scaledStartY) > 5){
            config.setBoxStartX(scaledStartX);
            config.setBoxStartY(scaledStartY);
            config.setBoxEndX(scaledEndX);
            config.setBoxEndY(scaledEndY);

            updateScreenZone();
            updateConfig();
        }else{
            trayIcon.displayMessage(REGISTRY_APP_NAME,
                "Please select a larger area", TrayIcon.MessageType.INFO);
        }
    }

    public AffineTransform getGraphicsTransformAt(int x, int y){
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();

        GraphicsDevice device = null;
        for(GraphicsDevice gd : environment.getScreenDevices()){
            if(gd.getDefaultConfiguration().getBounds().contains(x, y)){
                device = gd;
            }
        }

        if(device == null){
            device = environment.getDefaultScreenDevice();
        }

        GraphicsConfiguration graphicsConfig = device.getDefaultConfiguration();
        AffineTransform tx = graphicsConfig.getDefaultTransform();

        return tx;
    }

    public void openSnipper() throws Exception{
        if(this.snipper != null){
            closeSnipper();
            return;
        }

        this.snipper = new ScreenSnipper(this, new Window(null));
    }

    public void closeSnipper(){
        if(this.snipper != null){
            this.snipper.setVisible(false);
            this.snipper.dispose();
        }

        this.snipper = null;

        startLiveCaptions();
    }

    /**
     * Saves debug images (for troubleshooting) to the output
     * folder if debugMode = true
     */
    private void saveDebugImage(BufferedImage image, String fileName) throws IOException{
        File picturesDirectory = this.getOrCreateOutputDirectory();

        File destinationFile = new File(picturesDirectory, fileName);

        ImageIO.write(image, "png", destinationFile);
    }

    private void startLiveCaptions(){
        if(isWindows()){
            try{
                Runtime.getRuntime().exec("LiveCaptions.exe");
            }catch(IOException e){
                handleException(e);
            }
        }
    }

    private void updateConfig(){
        try{
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);
        }catch(IOException e){
            handleException(e);
        }
    }

    private boolean checkStartupStatusAndToggle(){
        try{
            ProcessBuilder checkBuilder = new ProcessBuilder("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", REGISTRY_APP_NAME);

            Process checkProcess = checkBuilder.start();
            checkProcess.waitFor();

            int checkExitValue = checkProcess.exitValue();

            if(checkExitValue == 0){
                ProcessBuilder deleteBuilder = new ProcessBuilder("reg", "delete",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v", REGISTRY_APP_NAME, "/f");

                Process updateProcess = deleteBuilder.start();
                updateProcess.waitFor();

                if(config.isDebugMode()){
                    int updateExitValue = updateProcess.exitValue();
                    if(updateExitValue == 0){
                        System.out.println("Startup entry updated successfully.");
                    }else{
                        System.out.println("Failed to update startup entry.");
                    }
                }

                return false;
            }else{
                //Shenanigans to figure out the location of our JAR file
                String jarPath = new File(LiveCaptionsLogger.class.getProtectionDomain().getCodeSource().getLocation()
                    .toURI()).getPath();

                //The PATH does not seem to be evaluated at this stage
                //String launchString = "start /b javaw -Xmx256M -jar \\\"" + jarPath + "\\\"";
                String launchString = jarPath;
                launchString = launchString.replace("target\\LiveCaptionsLogger.jar", "start.bat");
                launchString = launchString.replace("target/LiveCaptionsLogger.jar", "start.bat");
                launchString = launchString.replace("LiveCaptionsLogger.jar", "start.bat");
                launchString = launchString.replace("classes", "start.bat");
                if(config.isDebugMode()){
                    System.out.println(launchString);
                }

                ProcessBuilder createBuilder = new ProcessBuilder(
                    "reg", "add",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v", REGISTRY_APP_NAME,
                    "/t", "REG_SZ",
                    "/d", "\\\"" + launchString + "\\\"",
                    "/f"
                );

                Process process = createBuilder.start();
                int exitCode = process.waitFor();

                if(config.isDebugMode()){
                    if(exitCode == 0){
                        System.out.println("Registry entry added successfully.");
                    }else{
                        System.out.println("Failed to add registry entry. Exit code: " + exitCode);
                    }
                }

                return true;
            }
        }catch(Exception e){
            handleException(e);
            return false;
        }
    }

    private void closeLogger(){
        synchronized(lastLines){
            while(!lastLines.isEmpty()){
                String oldestEntry = lastLines.remove(0);

                if(!oldestEntry.equals(lastPrintedLine)){
                    lastPrintedLine = oldestEntry;
                    logToFile(oldestEntry);
                }
            }

            currentFile = null;
            lastPrintedLine = "";
        }
    }

    /**
     * Filter out non-white pixels for better visibility
     */
    private BufferedImage filterWhite(BufferedImage image){
        if(config.isContrastMode()){
            BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics g = result.getGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();

            ColorConvertOp colorConvert = new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null);
            colorConvert.filter(result, result);

            int threshold = 180;
            int[] pixels = result.getRGB(0, 0, result.getWidth(), result.getHeight(), null, 0, result.getWidth());
            for(int i = 0; i < pixels.length; i++){
                int alpha = (pixels[i] >> 24) & 0xFF;
                int red = (pixels[i] >> 16) & 0xFF;
                int green = (pixels[i] >> 8) & 0xFF;
                int blue = pixels[i] & 0xFF;

                int gray = (red + green + blue) / 3;
                int color = (gray < threshold) ? 0x000000 : 0xFFFFFF;

                pixels[i] = (alpha << 24) | color;
            }

            result.setRGB(0, 0, result.getWidth(), result.getHeight(), pixels, 0, result.getWidth());
            return result;
        }else{
            return image;
        }
    }

    /**
     * Ignore capturing if we aren't seeing the caption box
     *
     * This method checks if all four corners of the selected
     * screen area are black
     */
    private boolean inCaptionBox(BufferedImage image){
        if(config.isCaptureAnyText()){//Results might not be the best, toggle contrast mode on and do NOT select the whole screen unless you have a really good CPU
            return true;
        }

        List<Integer> corners = Arrays.asList(
            image.getRGB(0, 0),
            image.getRGB(image.getWidth() - 1, 0),
            image.getRGB(0, image.getHeight() - 1),
            image.getRGB(image.getWidth() - 1, image.getHeight() - 1)
        );

        return corners.stream().allMatch((colour) -> {
            int red = (colour >> 16) & 0xFF;
            int green = (colour >> 8) & 0xFF;
            int blue = colour & 0xFF;

            if(config.isDebugMode()){
                System.out.println(red + ":" + green + ":" + blue);
            }

            int threshold = config.getCaptionWindowColorThreshold();

            return red <= threshold && green <= threshold && blue <= threshold;//All mostly black! seems to vary a bit. This has to be tweaked if not black & white
        });
    }

    private void logToFile(String line){
        System.out.println("Line Finished: " + line);

        if(currentFile == null){
            Calendar now = Calendar.getInstance();

            File file = getOrCreateOutputDirectory();

            currentFile = new File(file, "LiveCaptions_" + FORMATTER.format(now.getTime()) + ".txt");
        }

        //Open and close immediatelly so we don't keep a lock on the file
        try(FileWriter fw = new FileWriter(currentFile, true);
            PrintWriter pw = new PrintWriter(fw)){
            pw.println(line);
        }catch(IOException e){//Shenanigans happened
            handleException(e);
        }
    }

    private File getOrCreateOutputDirectory(){
        File file;
        if(!config.getOutputPath().isEmpty()){
            file = new File(config.getOutputPath());
        }else{
            file = new File(getDocumentsPath(), "LiveCaptions");
        }

        if(!file.exists()){
            file.mkdirs();
        }

        return file;
    }

    private String getDocumentsPath(){
        if(isWindows()){
            return System.getProperty("user.home") + "\\Documents";
        }else{
            return System.getProperty("user.home") + "/Documents";
        }
    }

    public void handleException(Throwable e){
        e.printStackTrace();

        if(trayIcon == null){
            throw new RuntimeException(e);
        }

        trayIcon.displayMessage(REGISTRY_APP_NAME,
            "An error occurred: " + e.getLocalizedMessage(), TrayIcon.MessageType.INFO);
    }

    public static boolean isWindows(){
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("windows");
    }

    public static void main(String[] args){
        if(SystemTray.isSupported()){
            System.out.println("Starting...");
            LiveCaptionsLogger instance = new LiveCaptionsLogger();
            System.out.println("Started");

            Thread.setDefaultUncaughtExceptionHandler((Thread t, Throwable e) -> {
                try{
                    instance.handleException(e);
                }catch(Exception e1){//Just in case the irony pays off
                    e1.printStackTrace();
                }
            });
        }else{
            System.err.println("System tray not supported???? did you run this on a calculator?");
        }
    }
}
