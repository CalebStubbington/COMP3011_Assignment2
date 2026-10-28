/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Caleb Stubbington, a2991661
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;


/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private List<FrameProcessor> frameProcessors = null;
    private final Queue <PendingAudio> pendingAudio = new ArrayDeque<>();
    // Tracks frame processing tasks in order
    // playback retrieves results in order
    private final Queue<Future<PreparedFrame>> pendingFrames = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private boolean endVideoReached; // tracks if the decoder has reach the end of the video
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;
    // this is the current playback session
    private long playbackGeneration = 0;

    public VideoPlayerModel(
            boolean audioEnabled,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler, 
            List<FrameProcessor> frameProcessors) {
    	logWorkerConfiguration();
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        this.frameProcessors = new ArrayList<>(frameProcessors);
//        frameProcessors.add(new FrameBleeder());
//        frameProcessors.add(new FrameScratcher());
//        frameProcessors.add(new FrameDuster());
//        frameProcessors.add(new FramePepperer());
//        frameProcessors.add(new FrameBlackAndWhiter());
//        frameProcessors.add(new FrameYellower());
//        frameProcessors.add(new FrameVignetter());
//        frameProcessors.add(new FrameFlickerer());
//        frameProcessors.add(new FrameJitterer());
//        frameProcessors.add(new FrameNumberer());
    }
    
    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
        // shutdown the executor
        executor.shutdownNow();
    }
    
    // gets the count of the workers from the number of available processors the videoplayer can use,
    // one is kept in reserve to leave some CPU capacity for other features.
    private final int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    
    // this is the maxmium number of frames that can be processed/waiting at a time
    private final int maxPendingFrames = workerCount * 3;
    
    // manages a fixed number of threads instead of creating new ones for every frame
    // this pool allows us to concurrently process individual frames
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
    		
    		// limits queued tasks to prevent too many frames from overloading memory
    		new ArrayBlockingQueue<>(workerCount * 2), 
    		
    		// creates custom threads with names and sets them as daemons so they dont
    		// keep the JVM running
    		new ThreadFactory() {
    			private int threadNum = 1;
    			
    			@Override
		    	public Thread newThread(Runnable task) {
		    		// each thread is named to help with debugging
		    		Thread thread = new Thread(task, "worker-" + threadNum++);
		    		thread.setDaemon(true);
		    		return thread;
		    	}
		    },
    		// aborts new tasks if the pool and its queue is full
    		new ThreadPoolExecutor.AbortPolicy());
    
    private void logWorkerConfiguration() {
    	System.out.println("Available processors: " + Runtime.getRuntime().availableProcessors());
    	System.out.println("worker count: " + workerCount);
    	System.out.println("Max pending frames: " + maxPendingFrames);
    }
    
    // creates an independent copy of the frame which includes the image buffer.
    private Frame copyFrame(Frame source) {
    	if (source == null || source.image == null) {
    		throw new IllegalArgumentException("Cannot Copy Frame");
    	}
    	
    	return source.clone();
    }


    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            // submit the first frame after seeking to the worker pool
            decodeAndSubmitNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            
            // submits the frames to our worker pool
            try {
            	decodeAndSubmitNextFrame();
            } catch (Exception e) {
            	handlePlaybackError(e);
            }
            
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
    	
    	for (Future<PreparedFrame> future : pendingFrames) {
    		future.cancel(true);
    	}
    	
    	prepareNextFrameQueued = false;
    	playbackGeneration++;
        pendingAudio.clear();
        
        // clears frames from queue
        pendingFrames.clear();
        preparedFrame = null;
        endVideoReached = false; 
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }
    
    // applies effects to a copied frame and converts the result into an image for the JFX UI
    private PreparedFrame processDecodedFrame(
    		Frame processingFrame,
    		InfoFrame info,
    		long timestampUs,
    		int frameNumber,
    		long relativeTimestampUs,
    		long logicalTimestampUs,
    		long targetTimeNs,
    		long generation) throws Exception {
    	
    	try {
    		if (Thread.currentThread().isInterrupted()) {
    		    throw new InterruptedException("Frame processing cancelled");
    		}
    		// Apply the configured effects in order
    		processFrame(processingFrame, info);
    		
    		if (Thread.currentThread().isInterrupted()) {
    		    throw new InterruptedException("Frame processing cancelled");
    		}
    		// creates a converter locally for this processing task
    		JavaFXFrameConverter frameConverter = new JavaFXFrameConverter();
    		
    		// converts the frame into an image
    		Image image = frameConverter.convert(processingFrame);
    		
    		// repackages the image and its timing information 
    		return new PreparedFrame(
    				image,
    				frameNumber,
    				timestampUs,
    				logicalTimestampUs,
    				targetTimeNs,
    				System.nanoTime(),
    				generation);
    	} finally {
    		// releases the copied frame
    		processingFrame.close();
    	}
    }
    
    // submits the copied frame for processing by a worker
    // future allows the frame to be retrieved when processing has been completed.
    private Future<PreparedFrame> submitFrame(
    		Frame processingFrame,
    		InfoFrame info,
    		long timestampUs,
    		int frameNumber,
    		long relativeTimestampUs,
    		long logicalTimestampUs,
    		long targetTimeNs,
    		long generation) {
    	
    	// schedules the work and applies the effects/converts the frame
    	FutureTask<PreparedFrame> task = new FutureTask<>(
    			() -> processDecodedFrame(processingFrame, 
    					info, 
    					timestampUs, 
    					frameNumber,
    			relativeTimestampUs, 
    			logicalTimestampUs, 
    			targetTimeNs, 
    			generation));
    		try {
    			executor.execute(task);
    			return task;
    		} catch (RejectedExecutionException e) {
    			// the executor cannot take any more work
    			// cancel the task so it cannot be used
    			task.cancel(false);
    			
    			// releases the copied frame so it will not process
    			processingFrame.close();
    			return null;
    		}
    			
    }
    
    // retrieves the next processed frame when the worker is finsihed
    // retrieved in order
    private void retrieveProcessedFrame() throws Exception {
    	if (preparedFrame != null || pendingFrames.isEmpty()) {
    		return;
    	}
    	
    	// check what the latest frame is
    	Future<PreparedFrame> future = pendingFrames.peek();
    	
    	// prevents the playback from being blocked while waiting for a worker
    	if (!future.isDone()) {
    		return;
    	}
    	
    	pendingFrames.remove();
    	
    	// retrieves the finished frame or throws an exception
    	try {
    		PreparedFrame result = future.get();
    		
    		// the worker can finish afte a seek, restart, or stop.
    		// it will discard any result the belongs to a previous generation
    		if (result.generation() != playbackGeneration) {
    			preparedFrame = null;
    			return;
    		}
    		preparedFrame = result;
    		
    	} 
    	catch (CancellationException e) {
    		// task gets cancelled
    		preparedFrame = null;
    	}
    	
    	catch (ExecutionException e) {
    		Throwable cause = e.getCause();
    		
    		if (cause instanceof Exception exception) {
    			throw exception;
    		}
    		
    		throw new RuntimeException(cause);
    	}
    }
    
    // decodes and submits frames until a video frame is available
    // processing is given to the worker pool
    private void decodeAndSubmitNextFrame() throws Exception {
    	if (!playbackOpen || endVideoReached || executor.isShutdown()) {
    		return;
    	}
    	
    	Frame frame = grabFrame();
    	
    	// stops decoding
    	if (frame == null) {
    		endVideoReached = true;
    		return;
    	}
    	
    	long timestampUs = grabber.getTimestamp();
    	
    	// lets us keep the audio sample before moving on to the next frame
    	if (audioAvailable && frame.samples != null) {
    		queueAudio(timestampUs, frame);
    	}
    	
    	// audio only frames dont need to be processed
    	if (frame.image == null) {
    		return;
    	}
    	
    	// same process as the ReadNextVideoFrame method
    	if (!frameProcessorsInitialised) {
    		initialiseFrameProcessors(new InfoVideo(
                    mediaName(videoFile),
                    totalVideoFrames,
                    frame.imageWidth,
                    frame.imageHeight,
                    frame.imageDepth,
                    frame.imageChannels,
                    frame.imageStride,
                    frameRate,
                    intFrameRate,
                    videoFrameDurationUs,
                    grabber.getPixelFormat()));
    		frameProcessorsInitialised = true;
    	}
    	
    	if (firstTimestampUs == NO_SEEK_REQUEST) {
            firstTimestampUs = timestampUs;
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }

        int frameNumber = grabber.getFrameNumber();
        InfoFrame info = new InfoFrame(frameNumber, timestampUs);
        
        Frame processingFrame = copyFrame(frame);
        
        long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
        long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
        long generation = playbackGeneration;
        
        // submit the frame for process and track its order
        Future<PreparedFrame> future = submitFrame(
        		processingFrame, info, timestampUs, frameNumber, 
        		relativeTimestampUs, logicalTimestampUs, targetTimeNs, generation);
        	
        if (future != null) {
        	pendingFrames.add(future);
        }
        	
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        // Task (1)
        writeDueAudio(now);
        
        // checks if the latest frame has been finished
        try {
        	retrieveProcessedFrame();
        } catch (Exception e) {
        	handlePlaybackError(e);
        }

        // Task (2)
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            // We have a prepared frame ready to go and it is due (or just past due!) so get it up on screen ASAP!
            displayPreparedFrame(now);
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        // Caleb:
        // I've updated the statement to also compare if the number of pendingframes isn't at its max and if the video has finished
        if (!pauseRequested && !prepareNextFrameQueued && !endVideoReached && pendingFrames.size() < maxPendingFrames) {
            prepareNextFrameQueued = true;
            
            // remembers which playback session scheduled this
            long generation = playbackGeneration;
            
            Platform.runLater(() -> {
            	prepareNextFrameQueued = false;
            	
            	if (generation != playbackGeneration) {
            		return;
            	}
                

                // this ensure decoding can only happen when the playback is open and unpaused and hasnt ended
                if (!playbackOpen || pauseRequested || endVideoReached) {
                    return;
                }
                // decodes the frame or throws error
                try {
                	decodeAndSubmitNextFrame();
                } catch (Exception e) {
                	handlePlaybackError(e);
                	return;
                }
            });
          
            
          }
	     // finish only if all queues are empty
	        if (playbackOpen && endVideoReached && pendingFrames.isEmpty() && preparedFrame == null && pendingAudio.isEmpty()) {
	    	  finishPlayback();
            
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // Dump some logging to the console once per second so that real time performance can be monitored.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
    	// attempts to cancel each task and interrupts it
    	for (Future<PreparedFrame> future : pendingFrames) {
    		future.cancel(true);
    	}
    	
    	playbackTimer.stop();
        playbackOpen = false;
        
        playbackGeneration++;
        
        preparedFrame = null;
        endVideoReached = false;
        prepareNextFrameQueued = false;
        pendingAudio.clear();
        // clears queued frame when the playback is closed
        pendingFrames.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        
    	// for each frame we lock each processor individually
    	// a single global lock would prevent different frames from processing concurrently
    	for (FrameProcessor processor : frameProcessors) {
    		synchronized (processor) {
    			processor.process(frame, info);
    		}
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs,
            long generation) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
