package com.example.voicerecognizersp;

/**
 * VoiceRecognizer
 * A speech processor for the Android plattform.
 * 
 * @author Florian Schulze, schulze@in.tum.de, 2013
 * 
 *
 * contains code taken from:
 * 
 * Funf: Open Sensing Framework
 * Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
 * Acknowledgments: Alan Gardner
 * Contact: nadav@media.mit.edu
 */

/**
 * Copy of MainActivity which was already running and tested for MFCC extraction and storing in CSV file.
 * Reason to create a separate activity and copying is to apply optimizing technique. 
 * 
 * This also contains Integration of FFT module from Superpowered SDK and systrace code to evaluate
 * performance of FFT function from Superpowered and Cooley-Tukey
 * 
 * @author Wahib-Ul-Haq
 *
 */


import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;

import org.apache.commons.collections.buffer.CircularFifoBuffer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Trace;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.example.javagmm.FFT;
import com.example.javagmm.FrequencyProperties;
import com.example.javagmm.MFCC;
import com.example.javagmm.Window;

public class RecordingMfccService extends Service {
	
	private static String APP_NAME = "VoiceRecognizerSP";
	
	///////////Service parameters/////////////
	
	// Binder given to clients
    private final IBinder mBinder = new LocalBinder();
    

	LocalBroadcastManager broadcaster;
    static final public String COPA_RESULT = "com.example.voicerecognizersp.RecordingMfccService.REQUEST_PROCESSED";
    static final public String COPA_MESSAGE = "UINotification";

    Handler handler;
	String uiMessage = "";
	
	//////////Voice Recognizer specific ///////////

	private static int RECORDER_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
	private static int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_MONO;
	private static int RECORDER_AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
	private static int RECORDER_SAMPLERATE = 16000; //16Hz frequency 

	//private static int WINDOWS_TO_RECORD = 4;

	private static final int FFT_SIZE = 512; //512 = default;
	//8000samples/s divided by 256samples/frame -> 32ms/frame (31.25ms)
	//now its 16000 samples/s divided by 512 samples.frame but still -> 32ms/frame (31.25ms)
	private static final int FRAME_SIZE_IN_SAMPLES = 512; //512 = default value;

	//32ms/frame times 64frames/window = 2s/window
	private static final int WINDOW_SIZE_IN_FRAMES = 64;

	private static final int MFCCS_VALUE = 19; //MFCCs count 
	private static final int NUMBER_OF_FINAL_FEATURES = MFCCS_VALUE - 1; //discard energy
	private static final int MEL_BANDS = 20; //use FB-20
	private static final double[] FREQ_BANDEDGES = {50,250,500,1000,2000};

	private int bufferSize = 0;

	private static int[] freqBandIdx = null;
	public double[] featureBuffer = null;

	private Thread recordingThread = null;
	private FFT featureFFT = null; //not using now and instead using FFT from Superpowered Sdk
	private MFCC featureMFCC = null;
	private Window featureWin = null;
	private AudioRecord audioRecorder = null;

	private boolean isRecording = false;
	
	private static int DROP_FIRST_X_WINDOWS = 1;
	
	//////////////////variabled added later by Wahib//////////////
	private static final double windowsToRead = 2.5; //10;//10 = 20 seconds //2.5 = 5 seconds; //audio file duration in seconds
	private static final int OVERLAP_SIZE_IN_SAMPLES = 160;
	private static final int BUFFER_ITERATIONS_COUNT = 4;
	
	static Handler repeatRecordHandler = null;
	static Runnable repeatRecordRunnable = null;
	Thread repeatRecordThread = null;
		
	//private final int RECORDING_REPEAT_CYCLE = 10000; //1000 = 10 seconds
	static int cycleCount = 0;
	//final int maxCycleCount = 100; //use it to set total time to run; total time (sec) = maxCycleCount * RECORDING_REPEAT_CYCLE
	final static String TAG = "VoiceRecognizerSP"; //Voice Recognizer with Superpowered functionality

	ArrayList<LinkedList<ArrayList<double[]>>> mfccFinalList;
	private static final String appProcessName = "com.example.voicerecognizersp";
	
	MonitoringData monitorOprObj;
	static volatile MonitoringData monitorInstance = null;
	Thread dumpCpuValueThread = null;
	
	/**
	 * singleton method to ensure MonitoringData instance remains unique so that constructor is not called more than once
	 * 
	 * @param mainBindingActivity
	 * @return
	 */
	//http://howtodoinjava.com/2012/10/22/singleton-design-pattern-in-java/
	public static MonitoringData getMonitoringInstance(Context context) {
        if (monitorInstance == null) {
            synchronized (MonitoringData.class) {
                monitorInstance = new MonitoringData(context);
            }
        }
        return monitorInstance;
    }

		
	/////////////////Superpowered specifc/////////
		
	public static final int logSize = 8; //9; //2^9 = 512 = FFTSize
	public static final boolean ifReal = true;  //We are using FFT-in-Place 
	public static final boolean ifForward = false;
		
	public static String fftType = "FFT_CT"; //FFT_CT : Cooley-Tukey and other option is FFT_SP : Superpowered

	/*
	 * These two functions are taken from Superpowered SDK
	 */
	//http://superpowered.com/docs/_superpowered_f_f_t_8h.html 
    private native void onFFTPrepare(int logSize, boolean ifReal);
    private native void onFFTReal(float[] real, float[] imag, int logSize, boolean ifForward);

    static {
		Log.i(TAG, "Superpowered lib loaded successfully");
        System.loadLibrary("SuperpoweredExample");
    }
	    
    /////////////////////Notification specific//////////////////////
    
    
    private NotificationManager mNM;
    
    // Unique Identification Number for the Notification.
    // We use it on Notification start, and to cancel it.
    private int NOTIFICATION = 123;
    
    //////////////////////

    public RecordingMfccService() {
        Log.d(TAG, "constructor done");

    }
    
    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate called");
    
		broadcaster = LocalBroadcastManager.getInstance(this);
        monitorOprObj = getMonitoringInstance(this);
        
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        // Display a notification about us starting.  We put an icon in the status bar.
        runAsForeground();

        
    }
    

        
    /**
     * Show a notification while this service is running and run as foreground so
     * that OS knows that Activity depends on service and service is not a candidate
     * to be killed
     */
    //http://stackoverflow.com/a/28144499/1016544
    private void runAsForeground() {
       
    	
    	Intent notificationIntent = new Intent(this, MainBindingActivity.class);
        PendingIntent pendingIntent=PendingIntent.getActivity(this, 0,
                notificationIntent, Intent.FLAG_ACTIVITY_NEW_TASK);

        Notification notification=new NotificationCompat.Builder(this)
                                    .setSmallIcon(R.drawable.ic_launcher)
                                    .setContentTitle("VoiceRecognizerSP")
                                    .setContentText("Service is running ...")
                                    .setContentIntent(pendingIntent).build();

        startForeground(NOTIFICATION, notification);

    }

    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i("LocalService", "Received start id " + startId + ": " + intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
    	
    	super.onDestroy();
    	
        // Cancel the persistent notification.
        mNM.cancel(NOTIFICATION);

        // Tell the user we stopped.
        Toast.makeText(this, "RecordingMfccService stopped !!", Toast.LENGTH_LONG).show();
    }

    
    

    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class LocalBinder extends Binder {
        RecordingMfccService getService() {
            Log.d(TAG, "getService done");

            // Return this instance of LocalService so clients can call public methods
            return RecordingMfccService.this;
        }
    }

    
    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind done");
        
        return mBinder;
    }
    

    /**
     * initializing handler which manages recording 
     */
    
    public void initDispatcher()
    {
    	repeatRecordHandler = new Handler();

    	mfccFinalList = new ArrayList<LinkedList<ArrayList<double[]>>>();
        Log.d(TAG, "initDispatcher done");

    }
    
    public boolean isDispatcherNull()
    {
    	if(recordingThread == null)
    		return true;
    	else
    		return false;
    }
    
    public void stopDispatcher()
    {
    	stopRecording();

    }
    
    /**
     * FFT types are two : Cooley Turkey (CT) and Super Powered (SP)
     * @param type
     */
    public void setFFTType(String type)
    {
    	fftType = type;
    }
    
    
    public void startMfccExtraction()
    {
    	handleRecordingAudio();
    }
    
    
    public boolean isRecording()
    {
    	return isRecording;
    }
	/**
	 * Start recording audio in a separate thread.
	 * If the switch in our main view is checked, the audio will be saved in
	 * a file {@link #storeAudioStream()}. Otherwise we process the stream
	 * immediately {@link #processAudioStream()}.
	 */
	protected void recordAudioFromMic() {
		
		synchronized (this) {
			if (isRecording())
				return;
			else
				isRecording = true;
		}
		
		bufferSize = AudioRecord.getMinBufferSize(
				RECORDER_SAMPLERATE,
				RECORDER_CHANNELS,
				RECORDER_AUDIO_ENCODING);

		bufferSize = Math.max(bufferSize, RECORDER_SAMPLERATE*2);
		
		audioRecorder = new AudioRecord(
				RECORDER_SOURCE,
				RECORDER_SAMPLERATE,
				RECORDER_CHANNELS,
				RECORDER_AUDIO_ENCODING,
				bufferSize);
		
		if(fftType.equals("FFT_SP"))
			onFFTPrepare(logSize, ifReal);
		
		audioRecorder.startRecording();
		recordingThread = new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				processAudioStream();
				
				
			}
		}, APP_NAME + "_Thread");
		
		recordingThread.start();
	}


	/**
	 * Stop recording. Kills off any ongoing recording.
	 */
	protected void stopRecording() {
		
		synchronized (this) {
			if (!isRecording())
				return;
			else
			{
				
				
				if(audioRecorder != null)
				{
					isRecording = false;

					sendResult("Stopped recording. Resetting...");


					audioRecorder.stop();
					audioRecorder.release();
					audioRecorder = null;
					
					cycleCount=0;
					
					
					recordingThread.interrupt();
					recordingThread = null;
					
					
					
					repeatRecordHandler.removeCallbacks(repeatRecordRunnable);
					
					
			    	Log.i(TAG, "RecordingMfccService stopRecording() released !");
			    	
				}
				else
				{
					isRecording = false;
					sendResult("Recording already in stopped state !");
			    	Log.i(TAG, "RecordingMfccService Recording already in stopped state !");


				}

			}
		}

		
		
	}
	
	private void handleRecordingAudio()
	{


		repeatRecordRunnable = new Runnable() {
			
			@Override
			public void run() {
				// TODO Auto-generated method stub
				
				++cycleCount;
	        	Log.i(TAG, "MFCC cycle #" + cycleCount);
	        	
	        	if(cycleCount == 1)
	        	{
	        		
	        		
	        		//includes first time initialization
	        		recordAudioFromMic();
	        	}
	        	else
	        	{
		        	recordingThread = new Thread(new Runnable()
		    		{
		    			@Override
		    			public void run()
		    			{

		    				isRecording = true;
		    				processAudioStream();
		    			}
		    		}, APP_NAME + "_Thread");
		    		
		    		recordingThread.start();
		        	
	        	}
			
	        	
			}
		};
		
		repeatRecordHandler.post(repeatRecordRunnable);
		//repeatRecordHandler.postDelayed(repeatRecordRunnable, RECORDING_REPEAT_CYCLE); //this is to add delay in the beginning which is not required
	}
    
	
	//TODO: drop frames without speech -> speakersense
	//TODO: cepstral mean normalization -> overview paper
	//TODO: augment features with derivatives -> overview paper
	//TODO: buffer in-between frames vs drop; drop whole window vs frames

	/**
	 * Reads from {@link #audioRecorder} and processes the stream.
	 * For that, it cuts the stream into chunks (frames) which are
	 * processed individually in
	 * {@link #processAudioFrame(int, short[], double[], double[])}
	 * to obtain feature vectors.
	 * Features of multiple frames are then bundled to feature windows
	 * which represent speech utterances.
	 * Before we add a frame to a window we run admission checks.
	 * Frames that are not admitted simply get dropped. Therefore,
	 * a window contains sequential but not necessarily consecutive
	 * frames.
	 * 
	 * Updated by Wahib on 03/03/2015
	 * Decoupling of audioRecord.read() and sending to processAudioFrame().
	 * CircularFifoBuffer is introduced which maintins the buffer and when filled then
	 * only sent for processing. Reason for doing this was to introduce overlapping of
	 * samples for better efficiency.
	 * 
	 * v1.1
	 */
	private void processAudioStream()
	{
		
		///newly added ///
		
		short dataFrame16bit_one[] = new short[OVERLAP_SIZE_IN_SAMPLES]; //160
		short dataFrame16bit_two[] = new short[OVERLAP_SIZE_IN_SAMPLES]; //160
		short dataFrame16bit_three[] = new short[OVERLAP_SIZE_IN_SAMPLES]; //160
		short dataFrame16bit_four[] = new short[OVERLAP_SIZE_IN_SAMPLES]; //160

		
		
		int dataFrameCount = 0;
		
		//https://commons.apache.org/proper/commons-collections/javadocs/api-3.2.1/org/apache/commons/collections/buffer/CircularFifoBuffer.html
		CircularFifoBuffer buffer = new CircularFifoBuffer(BUFFER_ITERATIONS_COUNT);
		///////////////
		
		
		
		//initialize general processing data
		featureWin = new Window(FRAME_SIZE_IN_SAMPLES); //smoothing window, nothing to do with frame window
		
		//choising FFT technology
		if(fftType.equals("FFT_CT"))
			featureFFT = new FFT(FFT_SIZE);
		
		featureMFCC = new MFCC(FFT_SIZE, MFCCS_VALUE, MEL_BANDS, RECORDER_SAMPLERATE);

		freqBandIdx = new int[FREQ_BANDEDGES.length];
		for (int i = 0; i < FREQ_BANDEDGES.length; i ++)
		{
			freqBandIdx[i] = Math.round((float)FREQ_BANDEDGES[i]*((float)FFT_SIZE/(float)RECORDER_SAMPLERATE));
		}

		//lists of windows
		LinkedList<ArrayList<double[]>> featureCepstrums = new LinkedList<ArrayList<double[]>>();

		//windows: list of frames
		ArrayList<double[]> cepstrumWindow = new ArrayList<double[]>(WINDOW_SIZE_IN_FRAMES);

		int readAudioSamples = 0;
		int currentIteration = 0;
		
		
		FrequencyProperties freq = null;
		
    	Log.i(TAG, "MFCC processAudioStream() Staring to Record !");
    	
    	
    	//analysing and extracting MFCC features in a 20 sec frame. WINDOW_SIZE_IN_FRAMES here refers to 2 sec
		while (currentIteration < windowsToRead * WINDOW_SIZE_IN_FRAMES) //640 = 10*64 -> 10*2s=20s
		{
			
			// read() kann entweder mind. buffer_size/2 zeichen zur�ckliefern
			// (wir brauchen viel weniger) oder blockiert:
			// http://stackoverflow.com/questions/15804903/android-dev-audiorecord-without-blocking-or-threads
			
			try 
			{
				synchronized (this) {
					if (isRecording())
					{
												
						//to keep a check when buffer is full and needs to be send to process
						++dataFrameCount;
						
						//the only reason to have 4 different dataFrame arrays is that buffer uses pass by reference so
						//data was not actually changed in buffer even if dataFrame16bit content was changed 
						if(dataFrameCount == 1)
						{
							readAudioSamples = audioRecorder.read(dataFrame16bit_one, 0, OVERLAP_SIZE_IN_SAMPLES);
							buffer.add(dataFrame16bit_one);
						}
						else if(dataFrameCount == 2)
						{
							readAudioSamples = audioRecorder.read(dataFrame16bit_two, 0, OVERLAP_SIZE_IN_SAMPLES);
							buffer.add(dataFrame16bit_two);
						}
						else if(dataFrameCount== 3)
						{
							readAudioSamples = audioRecorder.read(dataFrame16bit_three, 0, OVERLAP_SIZE_IN_SAMPLES);
							buffer.add(dataFrame16bit_three);
						}
						else if(dataFrameCount == 4)
						{
							readAudioSamples = audioRecorder.read(dataFrame16bit_four, 0, OVERLAP_SIZE_IN_SAMPLES);
							buffer.add(dataFrame16bit_four);
						}
						
						
						
						//Log.i(TAG, "MFCC circular buffer : " + dataFrameCount);
						
					}
					else
					{
						Log.i(TAG, "ProcessAudioStream is killed early");

						//we only get here in case the user kills us off early
						//remove last window as it's incomplete
						if (cepstrumWindow.size() > 0 && cepstrumWindow.size() < WINDOW_SIZE_IN_FRAMES)
						{
							if(!featureCepstrums.isEmpty())
								featureCepstrums.removeLast();
						}
						
				    	mfccFinalList.add(featureCepstrums);
				    	
						//sendResult("Recording in progress .. " + String.valueOf(currentIteration));

				    	
						return;
					}
				}
			}
			catch (Exception e) {
				
				e.printStackTrace();
			}

			if (readAudioSamples == 0)
				return;
			
			Object bufferArray[] = buffer.toArray();
			if(!isFrameAdmittedByRMS((short[])bufferArray[dataFrameCount-1]))
				continue; //skips rest of the code in loop

			//means 512 samples count is achieved and now its ready for processing
			if(dataFrameCount == BUFFER_ITERATIONS_COUNT )
			{
				freq = processAudioFrame(FRAME_SIZE_IN_SAMPLES, buffer, true);
				
				dataFrameCount = 0;//resetting count
				
			}
			
			if (freq == null)
				continue; //skips rest of the code in loop
			
			//combine WINDOW_SIZE_IN_FRAMES frames to a window
			//2s with a window size of 64
			if (cepstrumWindow.size() == WINDOW_SIZE_IN_FRAMES){
				
				cepstrumWindow = new ArrayList<double[]>(WINDOW_SIZE_IN_FRAMES);
				featureCepstrums.add(cepstrumWindow);
		    	
				//Log.i(TAG, "MFCC recording cycle : " + currentIteration);

			}
			
			currentIteration++;

		
			// Add MFCCs of this frame to our window
			cepstrumWindow.add(freq.getFeatureCepstrum());
			
			//if(currentIteration == WINDOW_SIZE_IN_FRAMES * 5)
			//	monitorOprObj.dumpRealtimeCpuValues();//dumping at half for better evaluation


	    	//Log.i(TAG, "MFCC recording cycle : " + currentIteration);
	    	

		}

    	Log.i(TAG, "MFCC processAudioStream() Done Recording !");

    	mfccFinalList.add(featureCepstrums);
    	
    	repeatCycle();

		return;
	}



	/**
	 * Takes an audio frame and processes it in the frequency domain:
	 * 1. Applies a Hamming smoothing window
	 * 2. Computes the FFT
	 * 3. Computes the Power Spectral Density for each frequency band
	 * 4. Computes the MFCC coefficients
	 * 
	 * updated by Wahib on 03/03/2015
	 * Accessing CircularBufferFifo and filling the main data array with contents
	 * of buffer so that it can be used like before
	 * 
	 * @param samples Number of samples in this frame (should be static)
	 * @param dataFrame16bit Array of samples
	 * @param dropIfBad Whether to drop the frame if the spectral entropy is below a threshold
	 * @return FrequencyProperties object containing FFT & MFCC coefs, PSDs;  null if dropped
	 * 
	 * based on code of Funf's AudioFeaturesProbe class
	 * 
	 * Funf: Open Sensing Framework
	 * Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
	 * Acknowledgments: Alan Gardner
	 * Contact: nadav@media.mit.edu
	 * 
	 * v1.1
	 */
	public FrequencyProperties processAudioFrame(int samples, CircularFifoBuffer buffer, boolean dropIfBad) {
		
		double fftBufferR[] = new double[FFT_SIZE];
		double fftBufferI[] = new double[FFT_SIZE];
	
		double[] featureCepstrum = new double[MFCCS_VALUE-1];
		
		short dataFrame16bit[] = new short[samples];
		
		////////newly added///////
		Object bufferArray[] = buffer.toArray();
		int count = 0;
		short[] temp;
		
		for(int i=0; i<buffer.size(); i++)
		{
			if(i != buffer.size() - 1)
			{
				//except last one
				temp = (short[]) bufferArray[i];
			}
			else
			{
				//last one
				int maxForLast = FRAME_SIZE_IN_SAMPLES - (3 * OVERLAP_SIZE_IN_SAMPLES) ; 
				temp = Arrays.copyOfRange((short[]) bufferArray[i], 0, maxForLast);

			}
				
			
			
			System.arraycopy(temp, 0, dataFrame16bit, count, temp.length);
			
			count += OVERLAP_SIZE_IN_SAMPLES;
			
		}
		///////////////////
		
		// Frequency analysis
		Arrays.fill(fftBufferR, 0);
		Arrays.fill(fftBufferI, 0);

		// Convert audio buffer to doubles
		for (int i = 0; i < samples; i++)
		{
			
			fftBufferR[i] = dataFrame16bit[i];
		}

		// In-place windowing
		featureWin.applyWindow(fftBufferR);

		
		if(getAndroidVersion() >= 18)
		{
			//Android 4.3 (API level 18) or higher
			
			Trace.beginSection("ProcessFFT");
			try {
			        Trace.beginSection("Processing onFFTReal");
			        try {
			            // code for superpowered onFFTReal task...
			        	
			        	if(fftType.equals("FFT_SP"))
			        	{
			        		// In-place FFT - this is what we have to use as instructed by Florian
			        		onFFTReal(convertDoublesToFloats(fftBufferR), convertDoublesToFloats(fftBufferI), logSize, ifForward);
			        	}
			        	else if(fftType.equals("FFT_CT"))
			        		featureFFT.fft(fftBufferR, fftBufferI);
	
	
			        } finally {
			            Trace.endSection(); // ends "Processing Jane"
			        }
			 } 
			finally {
			        Trace.endSection(); // ends "ProcessPeople"
			 }
		}
		else
		{
			//Android 4.2 (API level 17) or lower
			if(fftType.equals("FFT_SP"))
        	{
        		// In-place FFT - this is what we have to use as instructed by Florian
        		onFFTReal(convertDoublesToFloats(fftBufferR), convertDoublesToFloats(fftBufferI), logSize, ifForward);
        	}
        	else if(fftType.equals("FFT_CT"))
        		featureFFT.fft(fftBufferR, fftBufferI);

		}

		//featureFFT.fft(fftBufferR, fftBufferI);
		//onFFTReal(convertDoublesToFloats(fftBufferR), convertDoublesToFloats(fftBufferI), logSize, ifForward);
		
		
		
		FrequencyProperties freq = new FrequencyProperties();
		
		freq.setFftImag(fftBufferI);
		freq.setFftReal(fftBufferR);
		freq.setFeatureCepstrum(featureCepstrum);

		// Get MFCCs
		double[] featureCepstrumTemp = featureMFCC.cepstrum(fftBufferR, fftBufferI);

		// copy MFCCs
		for(int i = 1; i < featureCepstrumTemp.length; i++) {
			//only keep energy-independent features, drop first coefficient
			featureCepstrum[i-1] = featureCepstrumTemp[i];
		}
		
		return freq;
	}

	
	/**
	 * Fetches OS version of the phone and returns the sdk version
	 * @return
	 */
	public int getAndroidVersion() {
	    //String release = Build.VERSION.RELEASE;
	    int sdkVersion = Build.VERSION.SDK_INT;
	    //return "Android SDK: " + sdkVersion + " (" + release +")";
	    return sdkVersion;
	}

	public static float[] convertDoublesToFloats(double[] input)
	{
	    if (input == null)
	    {
	        return null; // Or throw an exception - your choice
	    }
	    
	    float[] output = new float[input.length];
	    
	    for (int i = 0; i < input.length; i++)
	    {
	        output[i] = (float) input[i];
	    }
	    return output;
	}
	
	public double getRMS(short[] buffer) {
		double rms = 0;
		for (int i = 0; i < buffer.length; i++) {
			rms += buffer[i] * buffer[i];
		}
		return Math.sqrt(rms / buffer.length);
	}


	/**
	 * 
	 * @param frame Array of features
	 * @return returns whether the frame is to be admitted
	 */
	public boolean isFrameAdmittedByRMS(short[] buffer) {
		//if (getRMS(buffer) < 0.5)
		//	return false;
		return true;
	}

	

	
	private void repeatCycle()
	{
    		//isRecording = false;//to skip synchronized if condition
    		
    		repeatRecordHandler.post(repeatRecordRunnable);
    		//repeatRecordHandler.postDelayed(repeatRecordRunnable, RECORDING_REPEAT_CYCLE);
    		
	    	Log.i(TAG, "MFCC ------------------------");

	    	Log.i(TAG, "MFCC repeatCycle() new repeat initiated ..");

	    	//Log.i(TAG, "MFCC repeatCycle() cpu usage value : " + monitorOprObj.getCpuUsage());
	    	
	    	//monitorOprObj.dumpRealtimeCpuValues();

	}
	
	
	
  	
  	public ArrayList<LinkedList<ArrayList<double[]>>> getMfccList()
  	{
  		return mfccFinalList;
  	}
  	

	public void sendResult(String message) {
	    Intent intent = new Intent(COPA_RESULT);
	    
	    if(message != null)
	        intent.putExtra(COPA_MESSAGE, message);
	    
	    broadcaster.sendBroadcast(intent);
	}
	
	
    
}