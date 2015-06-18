/*
 * libjingle
 * Copyright 2015 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.jitsi.androidwebrtc;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.opengl.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.view.WindowManager.*;
import android.widget.*;
import org.jitsi.androidwebrtc.AppRTCClient.*;
import org.jitsi.androidwebrtc.PeerConnectionClient.*;
import org.jitsi.androidwebrtc.meet.*;
import org.jitsi.androidwebrtc.util.*;
import org.webrtc.*;
import org.webrtc.VideoRendererGui.*;

/**
 * Activity for peer connection call setup, call waiting
 * and call view.
 */
public class CallActivity extends Activity
    implements AppRTCClient.SignalingEvents,
      PeerConnectionClient.PeerConnectionEvents,
      CallFragment.OnCallEvents {

  public static final String EXTRA_ROOMID =
      "org.jitsi.androidwebrtc.ROOMID";
  public static final String EXTRA_LOOPBACK =
      "org.jitsi.androidwebrtc.LOOPBACK";
  public static final String EXTRA_VIDEO_CALL =
      "org.jitsi.androidwebrtc.VIDEO_CALL";
  public static final String EXTRA_VIDEO_WIDTH =
      "org.jitsi.androidwebrtc.VIDEO_WIDTH";
  public static final String EXTRA_VIDEO_HEIGHT =
      "org.jitsi.androidwebrtc.VIDEO_HEIGHT";
  public static final String EXTRA_VIDEO_FPS =
      "org.jitsi.androidwebrtc.VIDEO_FPS";
  public static final String EXTRA_VIDEO_BITRATE =
      "org.jitsi.androidwebrtc.VIDEO_BITRATE";
  public static final String EXTRA_VIDEOCODEC =
      "org.jitsi.androidwebrtc.VIDEOCODEC";
  public static final String EXTRA_HWCODEC_ENABLED =
      "org.jitsi.androidwebrtc.HWCODEC";
  public static final String EXTRA_AUDIO_BITRATE =
      "org.jitsi.androidwebrtc.AUDIO_BITRATE";
  public static final String EXTRA_AUDIOCODEC =
      "org.jitsi.androidwebrtc.AUDIOCODEC";
  public static final String EXTRA_NOAUDIOPROCESSING_ENABLED =
      "org.appspot.apprtc.NOAUDIOPROCESSING";
  public static final String EXTRA_CPUOVERUSE_DETECTION =
      "org.jitsi.androidwebrtc.CPUOVERUSE_DETECTION";
  public static final String EXTRA_DISPLAY_HUD =
      "org.jitsi.androidwebrtc.DISPLAY_HUD";
  public static final String EXTRA_CMDLINE =
      "org.jitsi.androidwebrtc.CMDLINE";
  public static final String EXTRA_RUNTIME =
      "org.jitsi.androidwebrtc.RUNTIME";
  private static final String TAG = "CallRTCClient";

  // List of mandatory application permissions.
  private static final String[] MANDATORY_PERMISSIONS = {
      "android.permission.MODIFY_AUDIO_SETTINGS",
      "android.permission.RECORD_AUDIO",
      "android.permission.INTERNET"
  };

  // Peer connection statistics callback period in ms.
  private static final int STAT_CALLBACK_PERIOD = 1000;
  // Local preview screen position before call is connected.
  private static final int LOCAL_X_CONNECTING = 0;
  private static final int LOCAL_Y_CONNECTING = 0;
  private static final int LOCAL_WIDTH_CONNECTING = 100;
  private static final int LOCAL_HEIGHT_CONNECTING = 100;
  // Local preview screen position after call is connected.
  private static final int LOCAL_X_CONNECTED = 0;
  private static final int LOCAL_Y_CONNECTED = 0;
  private static final int LOCAL_WIDTH_CONNECTED = 25;
  private static final int LOCAL_HEIGHT_CONNECTED = 25;

  private PeerConnectionClient peerConnectionClient = null;
  private AppRTCClient appRtcClient;
  private AppRTCClient.SignalingParameters signalingParameters;
  private AppRTCAudioManager audioManager = null;
  private VideoRenderer.Callbacks localRender;
  private final static int MAX_REMOTE_COUNT = 15;
  private VideoStreamHandler[] remoteRenders
      = new VideoStreamHandler[MAX_REMOTE_COUNT];
  private ScalingType scalingType;
  private Toast logToast;
  private boolean commandLineRun;
  private int runTimeMs;
  private boolean activityRunning;
  private AppRTCClient.RoomConnectionParameters roomConnectionParameters;
  private PeerConnectionClient.PeerConnectionParameters peerConnectionParameters;
  private boolean iceConnected;
  private boolean isError;
  private boolean callControlFragmentVisible = true;
  private long callStartedTimeMs = 0;

  // Controls
  private GLSurfaceView videoView;
  CallFragment callFragment;
  HudFragment hudFragment;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Thread.setDefaultUncaughtExceptionHandler(
        new UnhandledExceptionHandler(this));

    // Set window styles for fullscreen-window size. Needs to be done before
    // adding content.
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().addFlags(
        LayoutParams.FLAG_FULLSCREEN
            | LayoutParams.FLAG_KEEP_SCREEN_ON
            | LayoutParams.FLAG_DISMISS_KEYGUARD
            | LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | LayoutParams.FLAG_TURN_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    setContentView(R.layout.activity_call);

    iceConnected = false;
    signalingParameters = null;
    scalingType = ScalingType.SCALE_ASPECT_FILL;

    // Create UI controls.
    videoView = (GLSurfaceView) findViewById(R.id.glview_call);
    callFragment = new CallFragment();
    hudFragment = new HudFragment();

    // Create video renderers.
    VideoRendererGui.setView(videoView, new Runnable()
    {
      @Override
      public void run()
      {
        createPeerConnectionFactory();
      }
    });

    remoteRenders[0] = new VideoStreamHandler(25, 0, 25, 25, scalingType, false);
    remoteRenders[1] = new VideoStreamHandler(50, 0, 25, 25, scalingType, false);
    remoteRenders[2] = new VideoStreamHandler(75, 0, 25, 25, scalingType, false);

    remoteRenders[3] = new VideoStreamHandler(0, 25, 25, 25, scalingType, false);
    remoteRenders[4] = new VideoStreamHandler(25, 25, 25, 25, scalingType, false);
    remoteRenders[5] = new VideoStreamHandler(50, 25, 25, 25, scalingType, false);
    remoteRenders[6] = new VideoStreamHandler(75, 25, 25, 25, scalingType, false);

    remoteRenders[7] = new VideoStreamHandler(0, 50, 25, 25, scalingType, false);
    remoteRenders[8] = new VideoStreamHandler(25, 50, 25, 25, scalingType, false);
    remoteRenders[9] = new VideoStreamHandler(50, 50, 25, 25, scalingType, false);
    remoteRenders[10] = new VideoStreamHandler(75, 50, 25, 25, scalingType, false);

    remoteRenders[11] = new VideoStreamHandler(0, 75, 25, 25, scalingType, false);
    remoteRenders[12] = new VideoStreamHandler(25, 75, 25, 25, scalingType, false);
    remoteRenders[13] = new VideoStreamHandler(50, 75, 25, 25, scalingType, false);
    remoteRenders[14] = new VideoStreamHandler(75, 75, 25, 25, scalingType, false);

    localRender = VideoRendererGui.create(
        LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
        LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING, scalingType, true);

    // Show/hide call control fragment on view click.
    videoView.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        toggleCallControlFragmentVisibility();
      }
    });

    // Check for mandatory permissions.
    for (String permission : MANDATORY_PERMISSIONS) {
      if (checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        logAndToast("Permission " + permission + " is not granted");
        setResult(RESULT_CANCELED);
        finish();
        return;
      }
    }

    // Get Intent parameters.
    final Intent intent = getIntent();
    Uri roomUri = intent.getData();
    if (roomUri == null) {
      logAndToast(getString(R.string.missing_url));
      Log.e(TAG, "Didn't get any URL in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }
    String roomId = intent.getStringExtra(EXTRA_ROOMID);
    if (roomId == null || roomId.length() == 0) {
      logAndToast(getString(R.string.missing_url));
      Log.e(TAG, "Incorrect room ID in intent!");
      setResult(RESULT_CANCELED);
      finish();
      return;
    }
    boolean loopback = intent.getBooleanExtra(EXTRA_LOOPBACK, false);
    peerConnectionParameters = new PeerConnectionClient.PeerConnectionParameters(
        intent.getBooleanExtra(EXTRA_VIDEO_CALL, true),
        loopback,
        intent.getIntExtra(EXTRA_VIDEO_WIDTH, 0),
        intent.getIntExtra(EXTRA_VIDEO_HEIGHT, 0),
        intent.getIntExtra(EXTRA_VIDEO_FPS, 0),
        intent.getIntExtra(EXTRA_VIDEO_BITRATE, 0),
        intent.getStringExtra(EXTRA_VIDEOCODEC),
        intent.getBooleanExtra(EXTRA_HWCODEC_ENABLED, true),
        intent.getIntExtra(EXTRA_AUDIO_BITRATE, 0),
        intent.getStringExtra(EXTRA_AUDIOCODEC),
        intent.getBooleanExtra(EXTRA_NOAUDIOPROCESSING_ENABLED, false),
        intent.getBooleanExtra(EXTRA_CPUOVERUSE_DETECTION, true));
    commandLineRun = intent.getBooleanExtra(EXTRA_CMDLINE, false);
    runTimeMs = intent.getIntExtra(EXTRA_RUNTIME, 0);

    // Create connection client and connection parameters.
    appRtcClient = new MeetRTCClient(this);
    roomConnectionParameters = new AppRTCClient.RoomConnectionParameters(
        roomUri.toString(), roomId, loopback);

    // Send intent arguments to fragments.
    callFragment.setArguments(intent.getExtras());
    hudFragment.setArguments(intent.getExtras());
    // Activate call and HUD fragments and start the call.
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    ft.add(R.id.call_fragment_container, callFragment);
    ft.add(R.id.hud_fragment_container, hudFragment);
    ft.commit();
    startCall();

    // For command line execution run connection for <runTimeMs> and exit.
    if (commandLineRun && runTimeMs > 0) {
      videoView.postDelayed(new Runnable() {
        public void run() {
          disconnect();
        }
      }, runTimeMs);
    }
  }

  // Activity interfaces
  @Override
  public void onPause() {
    super.onPause();

    Log.d(TAG, "ON PAUSE");

    videoView.onPause();
    activityRunning = false;
    if (peerConnectionClient != null) {
      peerConnectionClient.stopVideoSource();
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    Log.d(TAG, "ON RESUME");

    videoView.onResume();
    activityRunning = true;
    if (peerConnectionClient != null) {
      peerConnectionClient.startVideoSource();
    }
  }

  @Override
  protected void onDestroy() {
    disconnect();
    super.onDestroy();
    if (logToast != null) {
      logToast.cancel();
    }
    activityRunning = false;
  }

  // CallFragment.OnCallEvents interface implementation.
  @Override
  public void onCallHangUp() {
    disconnect();
  }

  @Override
  public void onCameraSwitch() {
    if (peerConnectionClient != null) {
      peerConnectionClient.switchCamera();
    }
  }

  @Override
  public void onVideoScalingSwitch(ScalingType scalingType) {
    this.scalingType = scalingType;
    updateVideoView();
  }

  // Helper functions.
  private void toggleCallControlFragmentVisibility() {
    if (!iceConnected || !callFragment.isAdded()) {
      return;
    }
    // Show/hide call control fragment
    callControlFragmentVisible = !callControlFragmentVisible;
    FragmentTransaction ft = getFragmentManager().beginTransaction();
    if (callControlFragmentVisible) {
      ft.show(callFragment);
      ft.show(hudFragment);
    } else {
      ft.hide(callFragment);
      ft.hide(hudFragment);
    }
    ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
    ft.commit();
  }

  private void updateVideoView() {
    for (VideoStreamHandler handler : remoteRenders)
    {
      handler.update();
    }
    if (iceConnected) {
      VideoRendererGui.update(localRender,
          LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED,
          LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED,
          ScalingType.SCALE_ASPECT_FIT, true);
    } else {
      VideoRendererGui.update(localRender,
          LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
          LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING, scalingType, true);
    }
  }

  private void startCall() {
    if (appRtcClient == null) {
      Log.e(TAG, "AppRTC client is not allocated for a call.");
      return;
    }
    callStartedTimeMs = System.currentTimeMillis();

    // Start room connection.
    logAndToast(getString(R.string.connecting_to,
        roomConnectionParameters.roomUrl));
    appRtcClient.connectToRoom(roomConnectionParameters);

    // Create and audio manager that will take care of audio routing,
    // audio modes, audio device enumeration etc.
    audioManager = AppRTCAudioManager.create(this, new Runnable() {
        // This method will be called each time the audio state (number and
        // type of devices) has been changed.
        @Override
        public void run() {
          onAudioManagerChangedState();
        }
      }
    );
    // Store existing audio settings and change audio mode to
    // MODE_IN_COMMUNICATION for best possible VoIP performance.
    Log.d(TAG, "Initializing the audio manager...");
    audioManager.init();
  }

  // Should be called from UI thread
  private void callConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    Log.i(TAG, "Call connected: delay=" + delta + "ms");

    // Update video view.
    updateVideoView();
    // Enable statistics callback.
    peerConnectionClient.enableStatsEvents(true, STAT_CALLBACK_PERIOD);
  }

  private void onAudioManagerChangedState() {
    // TODO(henrika): disable video if AppRTCAudioManager.AudioDevice.EARPIECE
    // is active.
  }

  // Create peer connection factory when EGL context is ready.
  private void createPeerConnectionFactory() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          final long delta = System.currentTimeMillis() - callStartedTimeMs;
          Log.d(TAG, "Creating peer connection factory, delay=" + delta + "ms");
          peerConnectionClient = PeerConnectionClient.getInstance();
          peerConnectionClient.createPeerConnectionFactory(CallActivity.this,
              VideoRendererGui.getEGLContext(), peerConnectionParameters,
              CallActivity.this);
        }
        if (signalingParameters != null) {
          Log.w(TAG, "EGL context is ready after room connection.");
          onConnectedToRoomInternal(signalingParameters);
        }
      }
    });
  }

  // Disconnect from remote resources, dispose of local resources, and exit.
  private void disconnect() {
    activityRunning = false;
    if (appRtcClient != null) {
      appRtcClient.disconnectFromRoom();
      appRtcClient = null;
    }
    if (peerConnectionClient != null) {
      peerConnectionClient.close();
      peerConnectionClient = null;
    }
    if (audioManager != null) {
      audioManager.close();
      audioManager = null;
    }
    if (iceConnected && !isError) {
      setResult(RESULT_OK);
    } else {
      setResult(RESULT_CANCELED);
    }
    finish();
  }

  private void disconnectWithErrorMessage(final String errorMessage) {
    if (commandLineRun || !activityRunning) {
      Log.e(TAG, "Critical error: " + errorMessage);
      disconnect();
    } else {
      new AlertDialog.Builder(this)
          .setTitle(getText(R.string.channel_error_title))
          .setMessage(errorMessage)
          .setCancelable(false)
          .setNeutralButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
              dialog.cancel();
              disconnect();
            }
          }).create().show();
    }
  }

  // Log |msg| and Toast about it.
  private void logAndToast(String msg) {
    Log.d(TAG, msg);
    if (logToast != null) {
      logToast.cancel();
    }
    logToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
    logToast.show();
  }

  private void reportError(final String description) {
    runOnUiThread(new Runnable()
    {
      @Override
      public void run()
      {
        if (!isError)
        {
          isError = true;
          disconnectWithErrorMessage(description);
        }
      }
    });
  }

  // -----Implementation of AppRTCClient.AppRTCSignalingEvents ---------------
  // All callbacks are invoked from websocket signaling looper thread and
  // are routed to UI thread.
  private void onConnectedToRoomInternal(final SignalingParameters params) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;

    signalingParameters = params;
    if (peerConnectionClient == null) {
      Log.w(TAG, "Room is connected, but EGL context is not ready yet.");
      return;
    }
    logAndToast("Creating peer connection, delay=" + delta + "ms");
    peerConnectionClient.createPeerConnection(
        localRender, remoteRenders, signalingParameters);

    if (signalingParameters.initiator) {
      logAndToast("Creating OFFER...");
      // Create offer. Offer SDP will be sent to answering client in
      // PeerConnectionEvents.onLocalDescription event.
      peerConnectionClient.createOffer();
    } else {
      if (params.offerSdp != null) {
        peerConnectionClient.setRemoteDescription(params.offerSdp);
        logAndToast("Creating ANSWER...");
        // Create answer. Answer SDP will be sent to offering client in
        // PeerConnectionEvents.onLocalDescription event.
        peerConnectionClient.createAnswer();
      }
      if (params.iceCandidates != null) {
        // Add remote ICE candidates from room.
        for (IceCandidate iceCandidate : params.iceCandidates) {
          peerConnectionClient.addRemoteIceCandidate(iceCandidate);
        }
      }
    }
  }

  @Override
  public void onConnectedToRoom(final SignalingParameters params) {
    runOnUiThread(new Runnable()
    {
      @Override
      public void run()
      {
        onConnectedToRoomInternal(params);
      }
    });
  }

  @Override
  public void onRemoteDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          Log.e(TAG, "Received remote SDP for non-initilized peer connection.");
          return;
        }
        logAndToast("Received remote " + sdp.type + ", delay=" + delta + "ms");
        peerConnectionClient.setRemoteDescription(sdp);
        if (!signalingParameters.initiator) {
          logAndToast("Creating ANSWER...");
          // Create answer. Answer SDP will be sent to offering client in
          // PeerConnectionEvents.onLocalDescription event.
          peerConnectionClient.createAnswer();
        }
      }
    });
  }

  @Override
  public void onRemoteIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (peerConnectionClient == null) {
          Log.e(TAG,
              "Received ICE candidate for non-initilized peer connection.");
          return;
        }
        peerConnectionClient.addRemoteIceCandidate(candidate);
      }
    });
  }

  @Override
  public void onChannelClose() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("Remote end hung up; dropping PeerConnection");
        disconnect();
      }
    });
  }

  @Override
  public void onChannelError(final String description) {
    reportError(description);
  }

  // -----Implementation of PeerConnectionClient.PeerConnectionEvents.---------
  // Send local peer connection SDP and ICE candidates to remote party.
  // All callbacks are invoked from peer connection client looper thread and
  // are routed to UI thread.
  @Override
  public void onLocalDescription(final SessionDescription sdp) {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable()
    {
      @Override
      public void run()
      {
        if (appRtcClient != null)
        {
          logAndToast("Sending " + sdp.type + ", delay=" + delta + "ms");
          if (signalingParameters.initiator)
          {
            appRtcClient.sendOfferSdp(sdp);
          }
          else
          {
            appRtcClient.sendAnswerSdp(sdp);
          }
        }
      }
    });
  }

  @Override
  public void onIceCandidate(final IceCandidate candidate) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (appRtcClient != null) {
          appRtcClient.sendLocalIceCandidate(candidate);
        }
      }
    });
  }

  @Override
  public void onIceConnected() {
    final long delta = System.currentTimeMillis() - callStartedTimeMs;
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE connected, delay=" + delta + "ms");
        iceConnected = true;
        callConnected();
      }
    });
  }

  @Override
  public void onIceDisconnected() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        logAndToast("ICE disconnected");
        iceConnected = false;
        disconnect();
      }
    });
  }

  @Override
  public void onPeerConnectionClosed() {
  }

  @Override
  public void onPeerConnectionStatsReady(final StatsReport[] reports) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (!isError && iceConnected) {
          hudFragment.updateEncoderStatistics(reports);
        }
      }
    });
  }

  @Override
  public void onPeerConnectionError(final String description) {
    reportError(description);
  }

  public SessionDescription getRemoteDescription()
  {
    return peerConnectionClient.getRemoteDescription();
  }

  public void setRemoteDescription(SessionDescription remoteDescription)
  {
    peerConnectionClient.setRemoteDescription(remoteDescription);
  }
}
