package net.brlns.livecaptions;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Configs
 *
 * Ships with default settings for 1080p
 * If you have a different resolution make sure
 * to configure the capture area
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Config{

    @JsonProperty("PixelStartX")
    public int boxStartX = 15;
    @JsonProperty("PixelStartY")
    public int boxStartY = 15;

    @JsonProperty("PixelEndX")
    public int boxEndX = 1795;
    @JsonProperty("PixelEndY")
    public int boxEndY = 103;

    @JsonProperty("TesseractLanguage")
    public String tessLanguage = "eng";
    @JsonProperty("OutputPath")
    public String outputPath = "";

    @JsonProperty("ContrastMode")
    public boolean contrastMode = false;
    @JsonProperty("CaptureAnyText")
    public boolean captureAnyText = false;
    @JsonProperty("DebugMode")
    public boolean debugMode = false;
    @JsonProperty("LogAtStartup")
    public boolean currentlyLogging = true;

    /**
     * should be a value between 0-255
     * 255 is the same as CaptureAnyText = true
     * 30 makes sure only gray~black caption boxes will work with this program
     * this value is checked against the RGB components of the pixels around the four corners
     */
    @JsonProperty("CaptionWindowDetectColorThreshold")
    public int captionWindowColorThreshold = 30;

    /**
     * If you need to use languages other than English,
     * download tesseract from https://github.com/UB-Mannheim/tesseract/wiki and point this to your own tessdata folder,
     * Typically that would be C:\Program Files\Tesseract-OCR\tessdata
     */
    @JsonProperty("CustomTessDataPath")
    public String customTessDataPath = "";

}
