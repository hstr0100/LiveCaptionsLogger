package net.brlns.livecaptions;

import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.event.ActionEvent;
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
import java.util.concurrent.locks.ReentrantLock;
import javax.imageio.ImageIO;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;
import org.apache.commons.text.similarity.JaroWinklerDistance;

public class LiveCaptionsLogger{

	/**
	 * Configs
	 *
	 * Only tested at 1080p
	 */
	private static final int boxStartX = 15;
	private static final int boxStartY = 15;

	private static final int boxEndX = 1795;
	private static final int boxEndY = 103;

	private static final String tessLanguage = "eng";
	private static final String outputFolder = "LiveCaptions";

	private boolean currentlyLogging = true;//Default ON?

	/**
	 * Constants and application states
	 */
	private static final String REGISTRY_APP_NAME = "LiveCaptionsLogger";//Don't change

	private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

	private final ReentrantLock tesseractLock = new ReentrantLock();

	private File currentFile;
	private String lastPrintedLine;
	private List<String> lastLines = new ArrayList<>();

	private final SystemTray tray;
	private TrayIcon trayIcon = null;
	private boolean contrastMode = false;
	private boolean debugMode = false;

	public LiveCaptionsLogger(){
		tray = SystemTray.getSystemTray();

		try{
			PopupMenu popup = new PopupMenu();

			{
				MenuItem menuItem = new MenuItem("Toggle LiveCaptions Logging");
				menuItem.addActionListener((ActionEvent e) -> {
					currentlyLogging = !currentlyLogging;

					if(!currentlyLogging){
						closeLogger();
					}

					if(trayIcon == null){
						throw new RuntimeException("This wasn't supposed to run yet");
					}

					trayIcon.displayMessage("LiveCaptions Logger", "Live caption logging is now " + (currentlyLogging ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
				});

				popup.add(menuItem);
			}

			{//In case the text is NOT black and white you might need to toggle this
				MenuItem menuItem = new MenuItem("Toggle Contrast Mode");
				menuItem.addActionListener((ActionEvent e) -> {
					contrastMode = !contrastMode;

					if(trayIcon == null){
						throw new RuntimeException("This wasn't supposed to run yet");
					}

					trayIcon.displayMessage("LiveCaptions Logger", "Contrast mode is now " + (contrastMode ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
				});

				popup.add(menuItem);
			}

			if(isWindows()){
				MenuItem menuItem = new MenuItem("Toggle Auto Start");
				menuItem.addActionListener((ActionEvent e) -> {
					try{
						boolean result = checkStartupStatusAndToggle();
						if(trayIcon == null){
							throw new RuntimeException("This wasn't supposed to run yet");
						}

						trayIcon.displayMessage("LiveCaptions Logger", "Application auto start is now " + (result ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
					}catch(Exception e1){
						handleException(e1);
					}
				});

				popup.add(menuItem);
			}

			{//In case the text is NOT black and white you might need to toggle this
				MenuItem menuItem = new MenuItem("Toggle Debug Mode");
				menuItem.addActionListener((ActionEvent e) -> {
					debugMode = !debugMode;

					if(trayIcon == null){
						throw new RuntimeException("This wasn't supposed to run yet");
					}

					trayIcon.displayMessage("LiveCaptions Logger", "Debug mode is now " + (debugMode ? "ON" : "OFF"), TrayIcon.MessageType.INFO);
				});

				popup.add(menuItem);
			}

			{
				MenuItem menuItem = new MenuItem("Exit");
				menuItem.addActionListener((ActionEvent e) -> {
					System.out.println("Exiting....");
					System.exit(0);
				});

				popup.add(menuItem);
			}

			//Register to the system tray
			Image image = Toolkit.getDefaultToolkit().createImage(this.getClass().getResource("/assets/tray_icon.png"));
			trayIcon = new TrayIcon(image, "CC", popup);
			trayIcon.setToolTip("LiveCaptions Logger");
			trayIcon.setImageAutoSize(true);

			trayIcon.addActionListener((ActionEvent e) -> {
				try{
					File file = new File(getDocumentsPath(), outputFolder);
					if(!file.exists()){
						file.mkdirs();
					}

					Desktop.getDesktop().open(file);
				}catch(IOException e1){
					handleException(e1);
				}
			});

			tray.add(trayIcon);

			GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();

			Rectangle screenBounds = screen.getDefaultConfiguration().getBounds();

			if(debugMode){
				System.out.println("Screen bounds: " + screenBounds);
			}

			Toolkit toolkit = Toolkit.getDefaultToolkit();

			Dimension screenSize = toolkit.getScreenSize();
			double screenWidth = screenSize.getWidth();
			double screenHeight = screenSize.getHeight();

			double startPercentageX = (double)boxStartX / screenWidth * 100.0;
			double startPercentageY = (double)boxStartY / screenHeight * 100.0;
			double endPercentageX = (double)boxEndX / screenWidth * 100.0;
			double endPercentageY = (double)boxEndY / screenHeight * 100.0;

			if(debugMode){
				System.out.println("Percentage Coordinates:");
				System.out.println("StartX: " + startPercentageX + "%");
				System.out.println("StartY: " + startPercentageY + "%");
				System.out.println("EndX: " + endPercentageX + "%");
				System.out.println("EndY: " + endPercentageY + "%");
			}

			int screenResolution = toolkit.getScreenResolution();
			double scalingFactor = 96.0 / screenResolution; // Invert the scaling factor

			if(debugMode){
				System.out.println("Scaling Factor: " + scalingFactor);
			}

			int scaledBoxStartX = (int)(boxStartX * scalingFactor);
			int scaledBoxStartY = (int)(boxStartY * scalingFactor);
			int scaledBoxEndX = (int)(boxEndX * scalingFactor);
			int scaledBoxEndY = (int)(boxEndY * scalingFactor);

			if(debugMode){
				System.out.println("Scaled Rectangle Coordinates:");
				System.out.println("StartX: " + scaledBoxStartX);
				System.out.println("StartY: " + scaledBoxStartY);
				System.out.println("EndX: " + scaledBoxEndX);
				System.out.println("EndY: " + scaledBoxEndY);
			}

			Robot robot = new Robot(screen);
			Rectangle screenRect = new Rectangle(scaledBoxStartX, scaledBoxStartY,
				scaledBoxEndX - scaledBoxStartX,
				scaledBoxEndY - scaledBoxStartY);

			File tessDataFolder = LoadLibs.extractTessResources("tessdata");
			System.out.println(tessDataFolder);

			Tesseract tesseract = new Tesseract();

			tesseract.setDatapath(tessDataFolder.getAbsolutePath());
			tesseract.setLanguage(tessLanguage);

			JaroWinklerDistance jaroWinklerDistance = new JaroWinklerDistance();
			Runnable captureAndProcess = () -> {
				if(debugMode){
					System.out.println("Tick Tock");
				}

				if(!currentlyLogging){
					return;
				}

				BufferedImage screenshot = robot.createScreenCapture(screenRect);

				if(debugMode){
					try{
						saveImageToPictures(screenshot, "cc_debug.png");
					}catch(IOException e){
						handleException(e);
					}
				}

				if(!inCaptionBox(screenshot)){
					if(debugMode){
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

								if(debugMode){
									System.out.println("OCR Saw: " + text);
								}

								String[] lines = text.split("\\n");

								for(String line : lines){
									boolean contains = false;
									for(String oldLine : lastLines){
										//This checks if more than 80% of a line matches the other
										double distance = jaroWinklerDistance.apply(oldLine, line);
										if(debugMode){
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
										if(debugMode){
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
					if(debugMode){
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

	private static void saveImageToPictures(BufferedImage image, String fileName) throws IOException{
		String picturesDirectoryPath = System.getProperty("user.home") + "/Pictures";

		File picturesDirectory = new File(picturesDirectoryPath);
		if(!picturesDirectory.exists()){
			picturesDirectory.mkdirs();
		}

		File destinationFile = new File(picturesDirectory, fileName);

		ImageIO.write(image, "png", destinationFile);
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

				if(debugMode){
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

				//String launchString = "javaw -Xmx256M -jar \"" + jarPath + "\"";//TODO: debug why windows dislikes this
				String launchString = jarPath;
				launchString = launchString.replace("target\\LiveCaptionsLogger.jar", "start.bat");
				launchString = launchString.replace("target/LiveCaptionsLogger.jar", "start.bat");
				launchString = launchString.replace("LiveCaptionsLogger.jar", "start.bat");
				launchString = launchString.replace("classes", "start.bat");
				if(debugMode){
					System.out.println(launchString);
				}

//				ProcessBuilder createBuilder = new ProcessBuilder(
//					"reg", "add",
//					"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
//					"/v", REGISTRY_APP_NAME,
//					"/t", "REG_SZ",
//					"/d", "\"" + launchString + "\"",
//					"/f"
//				);
				//Java seems to strip the double quotes from around the launch string,
				String command = "reg add \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v "
					+ REGISTRY_APP_NAME + " /t REG_SZ /d \"" + launchString + "\" /f";

				Process process = Runtime.getRuntime().exec(command);

				int exitCode = process.waitFor();

				if(debugMode){
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

	/**
	 * Filter out non-white pixels for better visibility
	 */
	private BufferedImage filterWhite(BufferedImage image){
		if(contrastMode){
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
	 */
	private boolean inCaptionBox(BufferedImage image){
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

			if(debugMode){
				System.out.println(red + ":" + green + ":" + blue);
			}

			return red <= 30 && green <= 30 && blue <= 30;//All mostly black! seems to vary a bit. This has to be tweeked if not black & white
		});
	}

	private void logToFile(String line){
		System.out.println("Line Finished: " + line);

		if(currentFile == null){
			Calendar now = Calendar.getInstance();

			File path = new File(getDocumentsPath(), outputFolder);
			if(!path.exists()){
				path.mkdirs();
			}

			currentFile = new File(path, "LiveCaptions_" + FORMATTER.format(now.getTime()) + ".txt");
		}

		//Open and close immediatelly so we don't keep a lock on the file
		try(FileWriter fw = new FileWriter(currentFile, true);
			PrintWriter pw = new PrintWriter(fw)){
			pw.println(line);
		}catch(IOException e){//Shenanigans happened
			handleException(e);
		}
	}

	private String getDocumentsPath(){
		if(isWindows()){
			return System.getProperty("user.home") + "\\Documents";
		}else{
			return System.getProperty("user.home") + "/Documents";
		}
	}

	private void handleException(Throwable e){
		e.printStackTrace();

		if(trayIcon == null){
			throw new RuntimeException(e);
		}

		trayIcon.displayMessage("LiveCaptions Logger",
			"An error occurred: " + e.getLocalizedMessage(), TrayIcon.MessageType.INFO);
	}

	private static boolean isWindows(){
		String osName = System.getProperty("os.name").toLowerCase();
		return osName.contains("windows");
	}

	public static void main(String[] args){
		if(SystemTray.isSupported()){
			System.out.println("Starting");
			LiveCaptionsLogger instance = new LiveCaptionsLogger();

			Thread.setDefaultUncaughtExceptionHandler((Thread t, Throwable e) -> {
				instance.handleException(e);
			});
		}else{
			System.err.println("System tray not supported???? did you run this on a calculator?");
		}
	}
}
