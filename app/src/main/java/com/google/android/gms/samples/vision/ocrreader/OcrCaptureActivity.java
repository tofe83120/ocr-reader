/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.gms.samples.vision.ocrreader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.samples.vision.ocrreader.ui.camera.CameraSourcePreview;
import com.google.android.gms.samples.vision.ocrreader.ui.camera.GraphicOverlay;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;


import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

/**
 * Activity for the Ocr Detecting app.  This app detects text and displays the value with the
 * rear facing camera. During detection overlay graphics are drawn to indicate the position,
 * size, and contents of each TextBlock.
 */
public final class OcrCaptureActivity extends AppCompatActivity implements RecognitionListener {
    private static final String TAG = "OcrCaptureActivity";

    // Intent request code to handle updating play services if needed.
    private static final int RC_HANDLE_GMS = 9001;

    // Permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;
    private static final int RC_HANDLE_AUDIORECORD_PERM = 3;

    // Constants used to pass extra data in the intent
    public static final String AutoFocus = "AutoFocus";
    public static final String UseFlash = "UseFlash";
    public static final String TextBlockObject = "String";

    private CameraSource mCameraSource;
    private CameraSourcePreview mPreview;
    private GraphicOverlay<OcrGraphic> mGraphicOverlay;

    // Helper objects for detecting taps and pinches.
    private GestureDetector gestureDetector;

    // A TextToSpeech engine for speaking a String value.
    private TextToSpeech tts;

    // A SpeechRecognizer to retrieve speech command.
    private static SpeechRecognizer speechRecognizer = null;
    private Intent speechRecognizerIntent;

    /**
     * Initializes the UI and creates the detector pipeline.
     */
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.ocr_capture);

        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay<OcrGraphic>) findViewById(R.id.graphicOverlay);

        // Set good defaults for capturing text.
        boolean autoFocus = true;
        boolean useFlash = false;

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(autoFocus, useFlash);
        } else {
            requestCameraPermission();
        }

        gestureDetector = new GestureDetector(this, new CaptureGestureListener());

        Snackbar.make(mGraphicOverlay, "Say PARLE or tap to Speak.",
                Snackbar.LENGTH_LONG)
                .show();

        // Set up the Text To Speech engine.
        TextToSpeech.OnInitListener listener =
                new TextToSpeech.OnInitListener() {
                    @Override
                    public void onInit(final int status) {
                        if (status == TextToSpeech.SUCCESS) {
                            Log.d("OnInitListener", "Text to speech engine started successfully.");
                            tts.setLanguage(Locale.FRANCE);
                        } else {
                            Log.d("OnInitListener", "Error starting the text to speech engine.");
                        }
                    }
                };
        tts = new TextToSpeech(this.getApplicationContext(), listener);

        // Check for the record audio permission before accessing the speechRecognizer.  If the
        // permission is not granted yet, request permission.
        rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (rc != PackageManager.PERMISSION_GRANTED) {
            createSpeechRecognizer();
            speechRecognizer.startListening(speechRecognizerIntent);
        } else {
            requestAudioRecordPermission();
        }
    }

    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Handles the requesting of the audio record permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestAudioRecordPermission() {
        Log.w(TAG, "Audio record permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.RECORD_AUDIO};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_AUDIORECORD_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_AUDIORECORD_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_audiorecord_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean c = gestureDetector.onTouchEvent(e);

        return c || super.onTouchEvent(e);
    }


    /**
     * Creates the speech recognizer
     */
    private void createSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "fr");
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this.getPackageName());
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the ocr detector to detect small text samples
     * at long distances.
     * <p>
     * Suppressing InlinedApi since there is a check that the minimum version is met before using
     * the constant.
     */
    @SuppressLint("InlinedApi")
    private void createCameraSource(boolean autoFocus, boolean useFlash) {
        Context context = getApplicationContext();

        // A text recognizer is created to find text.  An associated multi-processor instance
        // is set to receive the text recognition results, track the text, and maintain
        // graphics for each text block on screen.  The factory is used by the multi-processor to
        // create a separate tracker instance for each text block.
        TextRecognizer textRecognizer = new TextRecognizer.Builder(context).build();
        textRecognizer.setProcessor(new OcrDetectorProcessor(mGraphicOverlay));

        if (!textRecognizer.isOperational()) {
            // Note: The first time that an app using a Vision API is installed on a
            // device, GMS will download a native libraries to the device in order to do detection.
            // Usually this completes before the app is run for the first time.  But if that
            // download has not yet completed, then the above call will not detect any text,
            // barcodes, or faces.
            //
            // isOperational() can be used to check if the required native libraries are currently
            // available.  The detectors will automatically become operational once the library
            // downloads complete on device.
            Log.w(TAG, "Detector dependencies are not yet available.");
            Toast.makeText(this, "Detector dependencies are not yet available.", Toast.LENGTH_LONG).show();

            // Check for low storage.  If there is low storage, the native library will not be
            // downloaded, so detection will not become operational.
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }

        // Creates and starts the camera.  Note that this uses a higher resolution in comparison
        // to other detection examples to enable the text recognizer to detect small pieces of text.
        mCameraSource =
                new CameraSource.Builder(getApplicationContext(), textRecognizer)
                        .setFacing(CameraSource.CAMERA_FACING_BACK)
                        .setRequestedPreviewSize(1280, 1024)
                        .setRequestedFps(5.0f)
                        .setAutoFocusEnabled(true)
                        .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
        startCameraSource();

        createSpeechRecognizer();
        speechRecognizer.startListening(speechRecognizerIntent);
    }

    /**
     * Stops the camera and speech recognizer.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPreview != null) {
            mPreview.stop();
        }

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    /**
     * Releases the resources associated with the camera source, the associated detectors, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }

        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if ((requestCode != RC_HANDLE_CAMERA_PERM) && (requestCode != RC_HANDLE_AUDIORECORD_PERM)) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == RC_HANDLE_CAMERA_PERM) {
                Log.d(TAG, "Camera permission granted - initialize the camera source");
                // we have permission, so create the camerasource
                boolean autoFocus = getIntent().getBooleanExtra(AutoFocus, false);
                boolean useFlash = getIntent().getBooleanExtra(UseFlash, false);
                createCameraSource(autoFocus, useFlash);
            }

            if (requestCode == RC_HANDLE_AUDIORECORD_PERM) {
                Log.d(TAG, "Audio record permission granted - initialize the speech recognizer source");
                // we have permission, so create the speech recognizer
                createSpeechRecognizer();
                speechRecognizer.stopListening();
                speechRecognizer.startListening(speechRecognizerIntent);
            }
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("TOFE SAMPLE")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() throws SecurityException {
        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    /**
     * onTap is called to speak the tapped TextBlock, if any, out loud.
     *
     * @param rawX - the raw position of the tap
     * @param rawY - the raw position of the tap.
     * @return true if the tap was on a TextBlock
     */
    private boolean onTap(float rawX, float rawY) {
        OcrGraphic graphic = mGraphicOverlay.getGraphicAtLocation(rawX, rawY);
        TextBlock text = null;
        if (graphic != null) {
            text = graphic.getTextBlock();
            if (text != null && text.getValue() != null) {
                Log.d(TAG, "text data is being spoken! " + text.getValue());

                // Speak the string.
                tts.speak(text.getValue(), TextToSpeech.QUEUE_ADD, null, "DEFAULT");

            } else {
                Log.d(TAG, "text data is null");
            }
        } else {
            Log.d(TAG, "no text detected");
        }
        return text != null;
    }

    private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return onTap(e.getRawX(), e.getRawY()) || super.onSingleTapConfirmed(e);
        }
    }

    private boolean onSpeakCmd() {

        /*int orientation = this.getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            //code for portrait mode
        } else {
            //code for landscape mode
        }*/

        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        float rawX = size.x / 2.0f;
        float rawY = size.y / 2.0f;
        return onTap(rawX, rawY);
    }

    /* Speech recognizer listener
     */

    @Override
    public void onReadyForSpeech(Bundle params) {
        Log.d(TAG, "SpeechRecognizerListener - onReadyForSpeech");
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "SpeechRecognizerListener - onBeginningOfSpeech");
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        //Log.d(TAG, "SpeechRecognizerListener - onRmsChanged");
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
        //Log.d(TAG, "SpeechRecognizerListener - onBufferReceived");
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "SpeechRecognizerListener - onEndofSpeech");
    }

    @Override
    public void onError(int error) {
        Log.d(TAG, "SpeechRecognizerListener - error " + getErrorText(error));
        if((speechRecognizer != null) && ( (error == SpeechRecognizer.ERROR_NO_MATCH) || (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) ) {
            speechRecognizer.stopListening();
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            speechRecognizer.startListening(speechRecognizerIntent);
        }
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        //Log.d(TAG, "SpeechRecognizerListener - onPartialResults");
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
        Log.d(TAG, "SpeechRecognizerListener - onEvent " + eventType);
    }

    @Override
    public void onResults(Bundle results) {
        String str = new String();
        //Log.d(TAG, "onResults " + results);
        ArrayList data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        speechRecognizer.stopListening();

        for (int i = 0; i < data.size(); i++) {
            Log.d(TAG, "SpeechRecognizerListener - result " + data.get(i));
            str += data.get(i) + " ";
        }

        // Launch action on speech cmd
        if (str.toUpperCase().contains("PARLE")) {
            onSpeakCmd();
        } else if ( (str.toUpperCase().contains("LE PLUS BEAU")) || (str.toUpperCase().contains("LA PLUS BELLE")) ) {
            tts.speak("C'est toi!", TextToSpeech.QUEUE_ADD, null, "DEFAULT");
        }

        new CountDownTimer(60000, 500) {

            @Override
            public void onTick(long millisUntilFinished) {
                if(!tts.isSpeaking()) {
                    this.cancel();
                    this.onFinish();
                }
            }

            @Override
            public void onFinish() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                speechRecognizer.startListening(speechRecognizerIntent);
            }
        }.start();

        /*new Thread(
                new Runnable() {
                    @Override
                    public void run() {

                    }
                })
                .start();*/

        speechRecognizer.startListening(speechRecognizerIntent);

    }


    public static String getErrorText(int errorCode) {
        String message;
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "Audio recording error";
                break;
            case SpeechRecognizer.ERROR_CLIENT:
                message = "Client side error";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Insufficient permissions";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
                message = "Network error";
                break;
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "Network timeout";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "No match";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "RecognitionService busy";
                break;
            case SpeechRecognizer.ERROR_SERVER:
                message = "error from server";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "No speech input";
                break;
            default:
                message = "Didn't understand, please try again.";
                break;
        }
        return message;
    }
}
