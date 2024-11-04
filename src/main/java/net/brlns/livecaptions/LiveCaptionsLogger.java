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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileSystemView;
import lombok.extern.slf4j.Slf4j;
import net.brlns.livecaptions.util.LoggerUtils;
import net.brlns.livecaptions.util.Nullable;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.commons.text.similarity.JaroWinklerDistance;

/**
 * LiveCaptionsLogger
 *
 * @author Gabriel / hstr0100 / vertx010
 */
@Slf4j
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

    private File configFile;
    private Settings config;

    private ObjectMapper objectMapper = new ObjectMapper();

    private ScreenSnipper snipper = null;

    private final AtomicInteger currentTick = new AtomicInteger(0);
    private final AtomicBoolean liveCaptionsRunning = new AtomicBoolean(true);
    private int ticksSinceLastRunning = 0;

    @SuppressWarnings("this-escape") //TODO: properly deal with this, the issue lies in openSnipper()
    public LiveCaptionsLogger(){
        File logFile = new File("application_log.txt");
        File previousLogFile = new File("application_log_previous.txt");

        if(logFile.exists()){
            if(previousLogFile.exists()){
                previousLogFile.delete();
            }

            logFile.renameTo(previousLogFile);
        }

        LoggerUtils.setLogFile(logFile);

        //Initialize the config file
        configFile = new File("config.json");

        if(!configFile.exists()){
            config = new Settings();
            updateConfig();
        }

        try{
            config = objectMapper.readValue(configFile, Settings.class);
        }catch(IOException e){
            config = new Settings();
            updateConfig();

            handleException(e, true);
        }

        log.info("Loaded config file");

        tray = SystemTray.getSystemTray();

        try{
            //Register to the system tray
            Image image = Toolkit.getDefaultToolkit().createImage(this.getClass().getResource("/assets/tray_icon.png"));
            trayIcon = new TrayIcon(image, REGISTRY_APP_NAME, buildPopupMenu());
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

            //Initialize the capture area bounds
            updateScreenZone();

            //Initialize the capture tool for Tesseract
            Robot robot = new Robot();

            //Initialize Tesseract
            File tessDataFolder;
            if(config.getCustomTessDataPath().isEmpty()){
                tessDataFolder = LoadLibs.extractTessResources("tessdata");
            }else{
                tessDataFolder = new File(config.getCustomTessDataPath());
            }

            if(!tessDataFolder.exists()){
                throw new RuntimeException("tessdata folder not found!");
            }

            log.debug("Tesseract initialized");

            //Initialize the string comparison tool
            JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();

            Runnable captureAndProcess = () -> {
                int tick = currentTick.incrementAndGet();

                log.debug("Tick #" + tick);

                if(!config.isCurrentlyLogging()){
                    log.info("Logging is off");
                    return;
                }

                if(isWindows() && config.isLiveCaptionsSensing()){
                    //We would check if CaptureAnyText is off before running this. However, until we have mouse tooltips to explain why this setting conflicts with the other, this should do.
                    if(tick % 5 == 0){//Lets not call isLiveCaptionsRunning() every second, 5 should be fine.
                        long timeNow = System.currentTimeMillis();

                        //This method can be expensive depending on the system, so lets offload it to another thread
                        CompletableFuture<Boolean> isRunningTask = CompletableFuture.supplyAsync(this::isLiveCaptionsRunning);

                        isRunningTask.thenAcceptAsync((isRunning) -> {
                            log.debug("Took " + (System.currentTimeMillis() - timeNow) + "ms. running: " + isRunning);

                            if(isRunning){
                                liveCaptionsRunning.set(true);
                                ticksSinceLastRunning = 0;
                            }else{
                                if(++ticksSinceLastRunning == 4){ // ~20 seconds of inactivity, varies based on the set tick rate
                                    liveCaptionsRunning.set(false);
                                    closeLogger();
                                }
                            }
                        }).exceptionally((e) -> {
                            handleException(e);
                            return null;
                        });
                    }

                    //This is not exactly mission-critical, LiveCaptions takes time to start up or wind down.
                    //If the async future takes too long, it's acceptable for this to be updated around the next tick.
                    if(!liveCaptionsRunning.get()){
                        log.info("Sensed that LiveCaptions is not running, logging is off");
                        return;
                    }
                }

                BufferedImage screenshot = robot.createScreenCapture(screenZone);

                log.debug("Captured at " + screenZone);

                if(config.isDebugMode() && tick % 10 == 0){
                    try{
                        saveDebugImage(screenshot, "cc_debug.png");

                        log.debug("Saved debug image");
                    }catch(IOException e){
                        handleException(e);
                    }
                }

                if(!inCaptionBox(screenshot)){
                    log.debug("CC Window not detected");

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
                                Tesseract tesseract = new Tesseract();

                                tesseract.setDatapath(tessDataFolder.getAbsolutePath());
                                tesseract.setLanguage(config.getTessLanguage());

                                //Start the OCR process
                                String text = tesseract.doOCR(filteredImage);

                                text = text.replace("|", "I"); //This one is particularly common

                                if(text.contains("(") || text.contains(")")){//Not sure if these actually ever show up in closed captions?
                                    return;
                                }

                                log.debug("OCR Saw: " + text);

                                //Process the results
                                String[] lines = text.split("\\n");

                                synchronized(lastLines){
                                    for(String line : lines){
                                        boolean contains = false;
                                        for(String oldLine : lastLines){
                                            //This checks if more than 80% of a line matches the other
                                            double distance = jaroWinklerDistance.apply(oldLine, line);
                                            log.debug("Distance between previous line " + distance + " " + oldLine + ":" + line);

                                            if(distance <= 0.20 || oldLine.contains(line)){
                                                contains = true;
                                            }
                                        }

                                        if(!contains && !lastLines.isEmpty()){
                                            String oldestEntry = lastLines.remove(0);

                                            double distance = jaroWinklerDistance.apply(oldestEntry,
                                                lastPrintedLine != null ? lastPrintedLine : "");
                                            log.debug("Distance from the last line " + distance);

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
                                //Seems safe to just ignore this
                                handleException(e, false);
                            }
                        });

                        executorService.shutdown();

                        try{
                            //TODO: At least on linux, libtesseract.so can randomly crash the JVM when the thread is interrupted
                            //We can't cancel OCR when it decides to hang at a native level, and this workaround is not safe either
                            //This is something that should be addressed by Tesseract or tess4j's team
                            if(!executorService.awaitTermination(3000, TimeUnit.MILLISECONDS)){
                                log.error("Skipping a tick, OCR took too long");
                            }
                        }catch(InterruptedException e){
                            //Nothing
                        }
                    }finally{
                        tesseractLock.unlock();
                    }
                }else{
                    log.debug("Could not acquire lock. Skipping OCR.");
                }
            };

            //Schedule the main program loop, we do not rely on the main thread here.
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

            int clampedRateMs = clamp(config.getCaptureRateMs(), 50, 2500);

            scheduler.scheduleAtFixedRate(captureAndProcess, 0, clampedRateMs, TimeUnit.MILLISECONDS);//1 Second? seems ok
        }catch(Exception e){
            handleException(e);
        }
    }

    /**
     * Builds the system tray menu.
     *
     * TODO: move this to its own class, migrate to JPopupMenu to add mouse over tooltips
     */
    private PopupMenu buildPopupMenu(){
        PopupMenu popup = new PopupMenu();

        popup.add(buildMenuItem("Toggle LiveCaptions Logging", (ActionEvent e) -> {
            config.setCurrentlyLogging(!config.isCurrentlyLogging());

            if(!config.isCurrentlyLogging()){
                closeLogger();
            }

            updateConfig();

            trayIcon.displayMessage(REGISTRY_APP_NAME, "Live caption logging is now " + (config.isCurrentlyLogging() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
        }));

        if(isWindows()){
            popup.add(buildMenuItem("Toggle LiveCaptions Sensing", (ActionEvent e) -> {
                config.setLiveCaptionsSensing(!config.isLiveCaptionsSensing());

                updateConfig();

                trayIcon.displayMessage(REGISTRY_APP_NAME, "LiveCaptions sensing (stops and starts logging automatically when LiveCaptions is running) is now " + (config.isLiveCaptionsSensing() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
            }));
        }

        popup.add(buildMenuItem("Toggle Contrast Mode", (ActionEvent e) -> {
            config.setContrastMode(!config.isContrastMode());

            updateConfig();

            trayIcon.displayMessage(REGISTRY_APP_NAME, "Contrast mode is now " + (config.isContrastMode() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
        }));

        popup.add(buildMenuItem("Toggle Logging of Any Text", (ActionEvent e) -> {
            config.setCaptureAnyText(!config.isCaptureAnyText());

            updateConfig();

            trayIcon.displayMessage(REGISTRY_APP_NAME, "Logging of any text within the capture window is now " + (config.isCaptureAnyText() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
        }));

        if(isWindows()){
            popup.add(buildMenuItem("Toggle Auto Start", (ActionEvent e) -> {
                boolean result = checkStartupStatusAndToggle();

                trayIcon.displayMessage(REGISTRY_APP_NAME, "Application auto start is now " + (result ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
            }));
        }

        popup.add(buildMenuItem("Toggle Debug Mode", (ActionEvent e) -> {
            config.setDebugMode(!config.isDebugMode());

            updateConfig();

            trayIcon.displayMessage(REGISTRY_APP_NAME, "Debug mode is now " + (config.isDebugMode() ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
        }));

        popup.add(buildMenuItem("Configure Captions Area", (ActionEvent e) -> {
            openSnipper();
        }));

        popup.add(buildMenuItem("Configure Output Folder", (ActionEvent e) -> {
            openDirectoryPicker();
        }));

        popup.add(buildMenuItem("Restore Default Settings", (ActionEvent e) -> {
            int option = JOptionPane.showConfirmDialog(null,
                "Are you sure you want to restore default settings?",
                "Restore Default Settings",
                JOptionPane.YES_NO_OPTION);

            if(option == JOptionPane.YES_OPTION){
                config = new Settings();

                updateConfig();
                updateScreenZone();
                closeLogger();

                trayIcon.displayMessage(REGISTRY_APP_NAME, "Settings have been restored to default", TrayIcon.MessageType.INFO);
            }
        }));

        popup.add(buildMenuItem("Start Live Captions", (ActionEvent e) -> {
            startLiveCaptions();
        }));

        popup.add(buildMenuItem("Exit", (ActionEvent e) -> {
            log.info("Exiting....");

            try{
                closeLogger();
            }catch(Exception e1){
                handleException(e1);
            }finally{
                System.exit(0);
            }
        }));

        return popup;
    }

    /**
     * Helper for building system tray menu entries.
     */
    private MenuItem buildMenuItem(String name, ActionListener actionListener){
        MenuItem menuItem = new MenuItem(name);
        menuItem.addActionListener(actionListener);

        return menuItem;
    }

    /**
     * Opens a dialog window for selecting a custom output directory
     *
     * @see getOrCreateOutputDirectory()
     */
    private void openDirectoryPicker(){
        try{
            JFileChooser fileChooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

            int result = fileChooser.showOpenDialog(null);

            if(result == JFileChooser.APPROVE_OPTION){
                String selectedDirectory = fileChooser.getSelectedFile().getAbsolutePath();

                log.debug("Selected Directory: " + selectedDirectory);

                trayIcon.displayMessage(REGISTRY_APP_NAME, "Output path set to " + selectedDirectory, TrayIcon.MessageType.INFO);

                config.setOutputPath(selectedDirectory);

                updateConfig();
                closeLogger();
            }
        }catch(HeadlessException e){
            handleException(e);
        }
    }

    /**
     * Updates the capture area based on the current configuration, accounting
     * for the current display scale.
     */
    private void updateScreenZone(){
        AffineTransform transform = getGraphicsTransformAt(config.getBoxStartX(), config.getBoxStartY());
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();

        int scaledBoxStartX = (int)(config.getBoxStartX() / scaleX);
        int scaledBoxStartY = (int)(config.getBoxStartY() / scaleY);
        int scaledBoxEndX = (int)(config.getBoxEndX() / scaleX);
        int scaledBoxEndY = (int)(config.getBoxEndY() / scaleY);

        if(config.isDebugMode()){
            log.info("Scaling Factor 1: " + "X " + scaleX + " Y " + scaleY);

            log.info("Real Rectangle Coordinates:");
            log.info("StartX: " + config.getBoxStartX());
            log.info("StartY: " + config.getBoxStartY());
            log.info("EndX: " + config.getBoxEndX());
            log.info("EndY: " + config.getBoxEndY());

            log.info("Scaled Rectangle Coordinates:");
            log.info("StartX: " + scaledBoxStartX);
            log.info("StartY: " + scaledBoxStartY);
            log.info("EndX: " + scaledBoxEndX);
            log.info("EndY: " + scaledBoxEndY);
        }

        screenZone = new Rectangle(scaledBoxStartX, scaledBoxStartY,
            scaledBoxEndX - scaledBoxStartX,
            scaledBoxEndY - scaledBoxStartY);
    }

    /**
     * Updates the capture area based on the output generated by ScreenSnipper
     * and scales/downscales it based on the current screen scale.
     */
    public final void setBounds(int startX, int startY, int endX, int endY){
        AffineTransform transform = getGraphicsTransformAt(config.getBoxStartX(), config.getBoxStartY());
        double scaleX = transform.getScaleX();
        double scaleY = transform.getScaleY();

        int scaledStartX = (int)(startX * scaleX);
        int scaledStartY = (int)(startY * scaleY);
        int scaledEndX = (int)(endX * scaleX);
        int scaledEndY = (int)(endY * scaleY);

        if(config.isDebugMode()){
            log.info("Scaling Factor 2: " + "X " + scaleX + " Y " + scaleY);

            log.info("Provided Rectangle Coordinates:");
            log.info("StartX: " + startX);
            log.info("StartY: " + startY);
            log.info("EndX: " + endX);
            log.info("EndY: " + endY);

            log.info("Provided Scaled Rectangle Coordinates:");
            log.info("StartX: " + scaledStartX);
            log.info("StartY: " + scaledStartY);
            log.info("EndX: " + scaledEndX);
            log.info("EndY: " + scaledEndY);
        }

        if(Math.abs(scaledEndX - scaledStartX) > 5 && Math.abs(scaledEndY - scaledStartY) > 5){
            config.setBoxStartX(scaledStartX);
            config.setBoxStartY(scaledStartY);
            config.setBoxEndX(scaledEndX);
            config.setBoxEndY(scaledEndY);

            updateScreenZone();
            updateConfig();

            trayIcon.displayMessage(REGISTRY_APP_NAME, "The capture area has been updated", TrayIcon.MessageType.INFO);
        }else{
            trayIcon.displayMessage(REGISTRY_APP_NAME,
                "Please select a larger area", TrayIcon.MessageType.INFO);
        }
    }

    /**
     * Retrieves the graphics transform at the specified screen coordinates
     *
     * If the coordinates are not within any specific screen device,
     * the default screen device's graphics transform is returned.
     */
    private AffineTransform getGraphicsTransformAt(int x, int y){
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

    /**
     * Opens the tool to manually select the captions capture area.
     */
    public final void openSnipper(){
        if(snipper != null){
            closeSnipper();
            return;
        }

        snipper = new ScreenSnipper(this, new Window(null));

        try{
            snipper.init();
        }catch(Exception e){
            handleException(e);
        }
    }

    /**
     * Closes the tool to manually select the captions capture area
     * when called by ScreenSnipper itself.
     */
    public final void closeSnipper(){
        if(snipper != null){
            snipper.setVisible(false);
            snipper.dispose();
        }

        snipper = null;

        startLiveCaptions();
    }

    /**
     * Saves debug images (for troubleshooting) to the output
     * folder if debugMode = true.
     */
    private void saveDebugImage(BufferedImage image, String fileName) throws IOException{
        File picturesDirectory = getOrCreateOutputDirectory();

        File destinationFile = new File(picturesDirectory, fileName);

        ImageIO.write(image, "png", destinationFile);
    }

    /**
     * This shortcut is specific to Windows 11.
     */
    public final void startLiveCaptions(){
        if(isWindows11()){
            try{
                ProcessBuilder processBuilder = new ProcessBuilder(
                    "taskkill", "/f", "/im", "LiveCaptions.exe");

                processBuilder.start();
            }catch(IOException e){
                handleException(e);
            }
        }
    }

    /**
     * This shortcut is specific to Windows 11.
     */
    public final void stopLiveCaptions(){
        if(isWindows11()){
            try{
                //It gets in the way due to the always on top nature of live captions
                //this only runs on Windows 11
                ProcessBuilder processBuilder = new ProcessBuilder(
                    "taskkill", "/f", "/im", "LiveCaptions.exe");

                Process process = processBuilder.start();
                int exitCode = process.waitFor();

                if(exitCode == 0){
                    log.info("Process killed successfully.");
                }else{
                    log.error("Failed to kill the process. Exit code: {}", exitCode);
                }
            }catch(IOException e){
                handleException(e);
            }catch(InterruptedException e){
                //Nothing
            }
        }
    }

    private void updateConfig(){
        updateConfig(config);
    }

    /**
     * Writes changes made to the Settings class to disk.
     */
    private void updateConfig(Settings configIn){
        try{
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, configIn);

            JsonNode jsonNode = objectMapper.valueToTree(configIn);

            objectMapper.readerForUpdating(config).readValue(jsonNode);

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, configIn);

            LoggerUtils.setDebugLogLevel(configIn.isDebugMode());
        }catch(IOException e){
            handleException(e);
        }
    }

    /**
     * Checks whether LiveCaptions is currently running.
     * This is specific to Windows 11.
     */
    private boolean isLiveCaptionsRunning(){
        return ProcessHandle.allProcesses()
            .anyMatch((processHandle)
                -> processHandle.info().command().map((command)
                -> command.contains("LiveCaptions.exe")).orElse(false));
    }

    @Nullable
    private static String getJarLocation(){
        try{
            URI jarPath = LiveCaptionsLogger.class.getProtectionDomain().getCodeSource().getLocation().toURI();

            if(jarPath.toString().endsWith(".jar")){
                return new File(jarPath).getAbsolutePath();
            }
        }catch(URISyntaxException e){
            //Ignore
        }

        return null;
    }

    @Nullable
    private List<String> getLaunchCommand(){
        List<String> launchString = null;

        String jarLocation = getJarLocation();
        if(jarLocation != null){
            String javaHome = System.getProperty("java.home");

            if(javaHome == null || javaHome.isEmpty()){
                javaHome = System.getenv("JAVA_HOME");
            }

            if(javaHome != null && !javaHome.isEmpty()){
                if(!javaHome.endsWith(File.separator)){
                    javaHome = javaHome + File.separator;
                }

                String javaPath;
                String os = System.getProperty("os.name").toLowerCase();
                if(os.contains("win")){
                    javaPath = javaHome + "bin" + File.separator + "javaw.exe";
                }else{
                    javaPath = javaHome + "bin" + File.separator + "java";
                }

                String jarString = new File(jarLocation).getAbsolutePath();

                launchString = List.of(javaPath, "-jar", jarString);
            }else{
                log.error("Runtime type is .jar but JAVA_HOME is not set. Cannot restart program if necessary.");
            }
        }

        if(launchString == null || launchString.isEmpty()){
            return null;
        }

        log.debug("Launching from {}", launchString);

        return launchString;
    }

    /**
     * Toggles the status of automatic startup
     *
     * Currently only Windows is implemented, the Auto Start option
     * is not present in the tray menu if not running under Windows.
     */
    private boolean checkStartupStatusAndToggle(){
        try{
            List<String> launchString = getLaunchCommand();

            log.debug("Launch command is: {}", launchString);

            if(launchString == null || launchString.isEmpty()){
                log.error("Cannot locate runtime binary.");
                return false;
            }

            ProcessBuilder checkBuilder = new ProcessBuilder("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", REGISTRY_APP_NAME);

            Process checkProcess = checkBuilder.start();
            checkProcess.waitFor();

            int checkExitValue = checkProcess.exitValue();

            log.debug("Check startup status: {}", checkExitValue);

            if(checkExitValue == 0){
                ProcessBuilder deleteBuilder = new ProcessBuilder("reg", "delete",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v", REGISTRY_APP_NAME, "/f");

                Process updateProcess = deleteBuilder.start();
                updateProcess.waitFor();

                if(config.isDebugMode()){
                    int updateExitValue = updateProcess.exitValue();
                    if(updateExitValue == 0){
                        log.info("Startup entry updated successfully.");
                    }else{
                        log.error("Failed to update startup entry.");
                    }
                }

                return false;
            }else{
                List<String> regArgs = new ArrayList<>(List.of("reg", "add",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                    "/v", REGISTRY_APP_NAME,
                    "/t", "REG_SZ",
                    "/d"));

                List<String> programArgs = new ArrayList<>(launchString);
                programArgs.add("--no-gui");

                StringBuilder builder = new StringBuilder();
                builder.append("\"");

                for(String arg : programArgs){
                    if(arg.contains(File.separator)){
                        builder.append("\\\"").append(arg).append("\\\"").append(" ");
                    }else{
                        builder.append(arg).append(" ");
                    }
                }

                if(builder.charAt(builder.length() - 1) == ' '){
                    builder.deleteCharAt(builder.length() - 1);
                }

                builder.append("\"");

                regArgs.add(builder.toString());
                regArgs.add("/f");

                ProcessBuilder createBuilder = new ProcessBuilder(regArgs);

                Process process = createBuilder.start();
                int exitCode = process.waitFor();

                if(config.isDebugMode()){
                    log.info("Program args: {}", programArgs);
                    log.info("Startup command args: {}", regArgs);

                    if(exitCode == 0){
                        log.info("Registry entry added successfully.");
                    }else{
                        log.error("Failed to add registry entry. Exit code: {} Command list: {}",
                            exitCode, createBuilder.command());
                    }
                }

                return true;
            }
        }catch(Exception e){
            handleException(e);
            return false;
        }
    }

    /**
     * Flushes current lines to disk, even if they are not finished,
     * and closes the current log file.
     *
     * This method is called before exit or when the CC window goes away.
     */
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
     * Filters out non-white pixels for better visibility,
     * This is specifically tailored for white text.
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
     * screen area are darker than the set RGB threshold.
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

            log.debug("Corner RGB: " + red + ":" + green + ":" + blue);

            int threshold = config.getCaptionWindowColorThreshold();

            return red <= threshold && green <= threshold && blue <= threshold;//All mostly black! seems to vary a bit. This has to be tweaked if not black & white
        });
    }

    /**
     * Logs finished lines to disk.
     */
    private void logToFile(String line){
        log.info("Line Finished: " + line);

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

    /**
     * Retrieves, or if it does not exist, creates an output directory
     * for the logger and debug images.
     */
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

    /**
     * Returns the Documents path used as default save location.
     */
    private String getDocumentsPath(){
        return System.getProperty("user.home") + File.separator + "Documents";
    }

    public final void handleException(Throwable e){
        handleException(e, true);
    }

    /**
     * Just some rudimentary exception handling.
     */
    public final void handleException(Throwable e, boolean displayToUser){
        log.error("An exception has been caught", e);

        if(displayToUser && trayIcon != null){
            trayIcon.displayMessage(REGISTRY_APP_NAME,
                "An error occurred: " + e.getLocalizedMessage(), TrayIcon.MessageType.INFO);
        }
    }

    /**
     * In Java 21 we finally don't need to bring this method to every program we build.
     *
     * @see Math.clamp(int, int, int)
     */
    public static int clamp(int value, int min, int max){
        return Math.max(min, Math.min(value, max));
    }

    /**
     * Checks if the current platform is Windows.
     *
     * This is used to filter out Windows-specific features when
     * running the program on other platforms.
     *
     * This program can run in other OSs for capturing captions other than
     * Windows 11's LiveCaptions such as Youtube's.
     */
    public static boolean isWindows(){
        String osName = System.getProperty("os.name").toLowerCase();
        return osName.contains("windows");
    }

    /**
     * Checks if the current platform is Windows 11.
     *
     * This method verifies if the version is 10.0.22000 or higher,
     * which indicates Windows 11
     */
    public static boolean isWindows11(){
        String osName = System.getProperty("os.name").toLowerCase();
        String osVersion = System.getProperty("os.version").toLowerCase();

        if(osName.contains("win")){
            if(osVersion.startsWith("10.0")){//Yes, 10. ¯\_(ツ)_/¯
                String[] versionParts = osVersion.split("\\.");

                if(versionParts.length > 2 && Integer.parseInt(versionParts[2]) >= 22000){
                    log.info("Running on Windows 11");
                    return true;
                }else{
                    log.info("Running on Windows 10 or earlier");
                }
            }else{
                log.info("Running on an unknown version of Windows");
            }
        }else{
            log.info("Not running on Windows");
        }

        return false;
    }

    public static void main(String[] args){
        if(SystemTray.isSupported()){
            log.info("Starting...");

            try{
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }catch(Exception e){
                //Default to Java's look and feel
            }

            LiveCaptionsLogger instance = new LiveCaptionsLogger();
            log.info("Started");

            Thread.setDefaultUncaughtExceptionHandler((Thread t, Throwable e) -> {
                instance.handleException(e);
            });
        }else{
            log.error("System tray not supported???? did you run this on a calculator?");
        }
    }
}
