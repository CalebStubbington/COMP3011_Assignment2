/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
//        System.out.println("Frame processors: " + frameProcessors.stream().map(p -> p.getClass().getSimpleName()).toList());
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }
    
    public List<FrameProcessor> getFrameProcessors() {
    	return new ArrayList<>(frameProcessors);
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        
        //Mapping short and long options to file options
        for (int i = 0; i < args.length; i++) {
        	String arg = args[i];
        	
        	switch (arg) {
        		case "-h":
        		case "--help":
        			helpRequested = true;
        			continue;
        			
        		case "-a":
        		case "--audio":
        			audioRequested = true;
        			continue;
        			
        		case "-x":
        		case "--maximise":
        			maximiseRequested = true;
        			continue;
        			
        		case "-1":
        		case "--monitor-1":
        			setDisplayId(1);
        			continue;
        			
        		case "-2":
        		case "--monitor-2":
        			setDisplayId(2);
        			continue;
        			
        		default:
        			break;
        	}
        	
        	// Long frame processor options or short options
        	FrameProcessor processor = createFrameProcessor(arg);
        	
        	if (processor != null) {
        		frameProcessors.add(processor);
        		continue;
        	}
        	
        	// Stacked short option
        	if (arg.startsWith("-") && !arg.startsWith("--") && arg.length() > 2) {
        		
        		boolean validStack = true;
        		List<FrameProcessor> stackedProcessors = new ArrayList<>();
        		
        		for (int j = 1; j < arg.length(); j++) {
        			String shortOption = "-" + arg.charAt(j);
        			FrameProcessor stackedProcessor = createFrameProcessor(shortOption);
        			
        			if (stackedProcessor == null) {
        				validStack = false;
        				break;
        			}
        			
        			stackedProcessors.add(stackedProcessor);
        		}
        		
        		if (validStack) {
        			frameProcessors.addAll(stackedProcessors);
        		}
        		else
        		{
        			errorMessage = "Unknown option: " + arg;
        			return;
        		}
        		
        		continue;
        	}
        	
        	if (arg.startsWith("-")) {
        		errorMessage = "Unknown option: " + arg;
        		return;
        	}
        	
        	videoFiles.add(arg);
        }

        if (videoFiles.size() > 1) {
            errorMessage = "(Only one video file may be specified) Usage: VideoPlayer [options] [video-file]";
            return;
        } 
        
        if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                return;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }
    
    // Mapping each short and long option to a new processor instance
    private FrameProcessor createFrameProcessor(String option) {
    	switch (option) {
	    	case "-n":
	    	case "--number-frames":
	    		return new FrameNumberer();
	    		
	    	case "-s":
	    	case "--scratch-frames":
	    		return new FrameScratcher();
	    	
	    	case "-f":
	    	case "--flicker-frames":
	    		return new FrameFlickerer();
    	
		    case "-w":
			case "--black-and-white":
				return new FrameBlackAndWhiter();
			
			case "-y":
			case "--yellow-frames":
				return new FrameYellower();
				
			case "-v":
			case "--vignette-frames":
				return new FrameVignetter();
				
			case "-d":
			case "--dust-frames":
				return new FrameDuster();
				
			case "-j":
			case "--jitter-frames":
				return new FrameJitterer();
				
			case "-m":
			case "--mottle-frames":
				return new FrameMottler();
				
			case "-b":
			case "--bleed-frames":
				return new FrameBleeder();
				
			case "-p":
			case "--pepper-frames":
				return new FramePepperer();
				
			default:
				return null;
    	}
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
    }
}
