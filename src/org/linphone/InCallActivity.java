package org.linphone;
/*
InCallActivity.java
Copyright (C) 2012  Belledonne Communications, Grenoble, France
Modified 2015  Daniel Getz

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/
import java.util.Arrays;
import java.util.List;

import org.linphone.core.LinphoneAddress;
import org.linphone.core.LinphoneCall;
import org.linphone.core.LinphoneCall.State;
import org.linphone.core.LinphoneCallParams;
import org.linphone.core.LinphoneCore;
import org.linphone.core.LinphoneCoreException;
import org.linphone.core.LinphoneCoreFactory;
import org.linphone.core.LinphoneCoreListenerBase;
import org.linphone.mediastream.Log;
import org.linphone.mediastream.Version;
import org.linphone.ui.AvatarWithShadow;
import org.linphone.ui.Numpad;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.support.v4.app.FragmentActivity;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Chronometer;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * @author Sylvain Berfini
 */
public class InCallActivity extends FragmentActivity implements OnClickListener {
	private static InCallActivity instance;

	private Handler mControlsHandler = new Handler(); 
	private Runnable mControls;
	private TextView pause, hangUp, dialer, micro, speaker, options, addCall, transfer, conference;
	private TextView audioRoute, routeSpeaker, routeReceiver, routeBluetooth;
	private LinearLayout routeLayout;
	private StatusFragment status;
	private AudioCallFragment audioCallFragment;
	private boolean isSpeakerEnabled = false, isMicMuted = false, isTransferAllowed, isAnimationDisabled;
	private ViewGroup mControlsLayout;
	private Numpad numpad;
	private Animation slideOutLeftToRight, slideInRightToLeft, slideInBottomToTop, slideInTopToBottom, slideOutBottomToTop, slideOutTopToBottom;

	private TableLayout callsList;
	private LayoutInflater inflater;
	private ViewGroup container;
	private boolean isConferenceRunning = false;
	private LinphoneCoreListenerBase mListener;
	
	public static InCallActivity instance() {
		return instance;
	}
	
	public static boolean isInstanciated() {
		return instance != null;
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		instance = this;
		
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        setContentView(R.layout.incall);
        
        isTransferAllowed = getApplicationContext().getResources().getBoolean(R.bool.allow_transfers);
        isSpeakerEnabled = LinphoneManager.getLcIfManagerNotDestroyedOrNull().isSpeakerEnabled();

		if (Version.sdkAboveOrEqual(Version.API11_HONEYCOMB_30)) {
			if(!BluetoothManager.getInstance().isBluetoothHeadsetAvailable()) {
				BluetoothManager.getInstance().initBluetooth();
			} else {
				isSpeakerEnabled = false;
			}
		}

        isAnimationDisabled = getApplicationContext().getResources().getBoolean(R.bool.disable_animations) || !LinphonePreferences.instance().areAnimationsEnabled();

        mListener = new LinphoneCoreListenerBase(){
        	@Override
        	public void callState(LinphoneCore lc, final LinphoneCall call, LinphoneCall.State state, String message) {
        		if (LinphoneManager.getLc().getCallsNb() == 0) {
        			finish();
        			return;
        		}
        		
        		if (state == State.IncomingReceived) {
        			startIncomingCallActivity();
        			return;
        		}

        		if (state == State.StreamsRunning) {
        			LinphoneManager.getLc().enableSpeaker(isSpeakerEnabled);

        			isMicMuted = LinphoneManager.getLc().isMicMuted();
        			enableAndRefreshInCallActions();
        			
        			if (status != null) {
        				status.refreshStatusItems(call);
        			}
        		}
        		
        		refreshInCallActions();
        		
        		refreshCallList(getResources());
        		
        		if (state == State.CallUpdatedByRemote) {
					acceptCallUpdate();
					return;
        		}
        		
        		transfer.setEnabled(LinphoneManager.getLc().getCurrentCall() != null);
        	}
        };
        
        if (findViewById(R.id.fragmentContainer) != null) {

            initUI();
            
            if (LinphoneManager.getLc().getCallsNb() > 0) {
            	LinphoneCall call = LinphoneManager.getLc().getCalls()[0];

            	if (LinphoneUtils.isCallEstablished(call)) {
	    			enableAndRefreshInCallActions();
            	}
            }
            
            if (savedInstanceState != null) { 
            	// Fragment already created, no need to create it again (else it will generate a memory leak with duplicated fragments)
            	isSpeakerEnabled = savedInstanceState.getBoolean("Speaker");
            	isMicMuted = savedInstanceState.getBoolean("Mic");
            	refreshInCallActions();
            	return;
            }
            
			audioCallFragment = new AudioCallFragment();

			if(BluetoothManager.getInstance().isBluetoothHeadsetAvailable()){
				BluetoothManager.getInstance().routeAudioToBluetooth();
			}

			audioCallFragment.setArguments(getIntent().getExtras());
            getSupportFragmentManager().beginTransaction().add(R.id.fragmentContainer, audioCallFragment).commitAllowingStateLoss();

        }
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		outState.putBoolean("Speaker", LinphoneManager.getLc().isSpeakerEnabled());
		outState.putBoolean("Mic", LinphoneManager.getLc().isMicMuted());

		super.onSaveInstanceState(outState);
	}
	
	private boolean isTablet() {
		return getResources().getBoolean(R.bool.isTablet);
	}
	
	private void initUI() {
		inflater = LayoutInflater.from(this);
		container = (ViewGroup) findViewById(R.id.topLayout);
        callsList = (TableLayout) findViewById(R.id.calls);

		micro = (TextView) findViewById(R.id.micro);
		micro.setOnClickListener(this);
//		micro.setEnabled(false);
		speaker = (TextView) findViewById(R.id.speaker);
		speaker.setOnClickListener(this);
		if(isTablet()){
			speaker.setEnabled(false);
		}
//		speaker.setEnabled(false);
		addCall = (TextView) findViewById(R.id.addCall);
		addCall.setOnClickListener(this);
		addCall.setEnabled(false);
		transfer = (TextView) findViewById(R.id.transfer);
		transfer.setOnClickListener(this);
		transfer.setEnabled(false);
		options = (TextView) findViewById(R.id.options);
		options.setOnClickListener(this);
		options.setEnabled(false);
		pause = (TextView) findViewById(R.id.pause);
		pause.setOnClickListener(this);
		pause.setEnabled(false);
		hangUp = (TextView) findViewById(R.id.hangUp);
		hangUp.setOnClickListener(this);
		conference = (TextView) findViewById(R.id.conference);
		conference.setOnClickListener(this);
		dialer = (TextView) findViewById(R.id.dialer);
		dialer.setOnClickListener(this);
		dialer.setEnabled(false);
		numpad = (Numpad) findViewById(R.id.numpad);

		
		try {
			routeLayout = (LinearLayout) findViewById(R.id.routesLayout);
			audioRoute = (TextView) findViewById(R.id.audioRoute);
			audioRoute.setOnClickListener(this);
			routeSpeaker = (TextView) findViewById(R.id.routeSpeaker);
			routeSpeaker.setOnClickListener(this);
			routeReceiver = (TextView) findViewById(R.id.routeReceiver);
			routeReceiver.setOnClickListener(this);
			routeBluetooth = (TextView) findViewById(R.id.routeBluetooth);
			routeBluetooth.setOnClickListener(this);
		} catch (NullPointerException npe) {
			Log.e("Bluetooth: Audio routes menu disabled on tablets for now (1)");
		}

		mControlsLayout = (ViewGroup) findViewById(R.id.menu);
		
        if (!isTransferAllowed) {
        	addCall.setBackgroundResource(R.drawable.options_add_call);
        }

        if (!isAnimationDisabled) {
	        slideInRightToLeft = AnimationUtils.loadAnimation(this, R.anim.slide_in_right_to_left);
	        slideOutLeftToRight = AnimationUtils.loadAnimation(this, R.anim.slide_out_left_to_right);
	        slideInBottomToTop = AnimationUtils.loadAnimation(this, R.anim.slide_in_bottom_to_top);
	        slideInTopToBottom = AnimationUtils.loadAnimation(this, R.anim.slide_in_top_to_bottom);
	        slideOutBottomToTop = AnimationUtils.loadAnimation(this, R.anim.slide_out_bottom_to_top);
	        slideOutTopToBottom = AnimationUtils.loadAnimation(this, R.anim.slide_out_top_to_bottom);
        }

		if (BluetoothManager.getInstance().isBluetoothHeadsetAvailable()) {
			try {
				if (routeLayout != null)
					routeLayout.setVisibility(View.VISIBLE);
				audioRoute.setVisibility(View.VISIBLE);
				speaker.setVisibility(View.GONE);
			} catch (NullPointerException npe) { Log.e("Bluetooth: Audio routes menu disabled on tablets for now (2)"); }
		} else {
			try {
				if (routeLayout != null)
					routeLayout.setVisibility(View.GONE);
				audioRoute.setVisibility(View.GONE);
				speaker.setVisibility(View.VISIBLE);
			} catch (NullPointerException npe) { Log.e("Bluetooth: Audio routes menu disabled on tablets for now (3)"); }
		}
		
		LinphoneManager.getInstance().changeStatusToOnThePhone();
	}
	
	private void refreshInCallActions() {
		try {
			if (isSpeakerEnabled) {
				speaker.setBackgroundResource(R.drawable.speaker_on);
				routeSpeaker.setBackgroundResource(R.drawable.route_speaker_on);
				routeReceiver.setBackgroundResource(R.drawable.route_receiver_off);
				routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_off);
			} else {
				speaker.setBackgroundResource(R.drawable.speaker_off);
				routeSpeaker.setBackgroundResource(R.drawable.route_speaker_off);
				if (BluetoothManager.getInstance().isUsingBluetoothAudioRoute()) {
					routeReceiver.setBackgroundResource(R.drawable.route_receiver_off);
					routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_on);
				} else {
					routeReceiver.setBackgroundResource(R.drawable.route_receiver_on);
					routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_off);
				}
			}
		} catch (NullPointerException npe) {
			Log.e("Bluetooth: Audio routes menu disabled on tablets for now (4)");
		}
		
		if (isMicMuted) {
			micro.setBackgroundResource(R.drawable.micro_off);
		} else {
			micro.setBackgroundResource(R.drawable.micro_on);
		}

		if (LinphoneManager.getLc().getCallsNb() > 1) {
			conference.setVisibility(View.VISIBLE);
			pause.setVisibility(View.GONE);
		} else {
			conference.setVisibility(View.GONE);
			pause.setVisibility(View.VISIBLE);
			
			List<LinphoneCall> pausedCalls = LinphoneUtils.getCallsInState(LinphoneManager.getLc(), Arrays.asList(State.Paused));
			if (pausedCalls.size() == 1) {
				pause.setBackgroundResource(R.drawable.pause_on);
			} else {
				pause.setBackgroundResource(R.drawable.pause_off);
			}
		}
	}
	
	private void enableAndRefreshInCallActions() {
		addCall.setEnabled(LinphoneManager.getLc().getCallsNb() < LinphoneManager.getLc().getMaxCalls());
		transfer.setEnabled(getResources().getBoolean(R.bool.allow_transfers));
		options.setEnabled(!getResources().getBoolean(R.bool.disable_options_in_call) && (addCall.isEnabled() || transfer.isEnabled()));

		micro.setEnabled(true);
		if(!isTablet()){
			speaker.setEnabled(true);
    	}
		transfer.setEnabled(true);
		pause.setEnabled(true);
		dialer.setEnabled(true);
		conference.setEnabled(true);

		refreshInCallActions();
	}

	public void updateStatusFragment(StatusFragment statusFragment) {
		status = statusFragment;
	}

	@Override
	public void onClick(View v) {
		int id = v.getId();

		if (id == R.id.micro) {
			toggleMicro();
		} 
		else if (id == R.id.speaker) {
			toggleSpeaker();
		} 
		else if (id == R.id.addCall) {
			goBackToDialer();
		} 
		else if (id == R.id.pause) {
			pauseOrResumeCall();
		} 
		else if (id == R.id.hangUp) {
			hangUp();
		} 
		else if (id == R.id.dialer) {
			hideOrDisplayNumpad();
		}
		else if (id == R.id.conference) {
			enterConference();
		}
		else if (id == R.id.transfer) {
			goBackToDialerAndDisplayTransferButton();
		}
		else if (id == R.id.options) {
			hideOrDisplayCallOptions();
		}
		else if (id == R.id.audioRoute) {
			hideOrDisplayAudioRoutes();
		}
		else if (id == R.id.routeBluetooth) {
			if (BluetoothManager.getInstance().routeAudioToBluetooth()) {
				isSpeakerEnabled = false;
				routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_on);
				routeReceiver.setBackgroundResource(R.drawable.route_receiver_off);
				routeSpeaker.setBackgroundResource(R.drawable.route_speaker_off);
			}
			hideOrDisplayAudioRoutes();
		}
		else if (id == R.id.routeReceiver) {
			LinphoneManager.getInstance().routeAudioToReceiver();
			isSpeakerEnabled = false;
			routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_off);
			routeReceiver.setBackgroundResource(R.drawable.route_receiver_on);
			routeSpeaker.setBackgroundResource(R.drawable.route_speaker_off);
			hideOrDisplayAudioRoutes();
		}
		else if (id == R.id.routeSpeaker) {
			LinphoneManager.getInstance().routeAudioToSpeaker();
			isSpeakerEnabled = true;
			routeBluetooth.setBackgroundResource(R.drawable.route_bluetooth_off);
			routeReceiver.setBackgroundResource(R.drawable.route_receiver_off);
			routeSpeaker.setBackgroundResource(R.drawable.route_speaker_on);
			hideOrDisplayAudioRoutes();
		}
		
		else if (id == R.id.callStatus) {
			LinphoneCall call = (LinphoneCall) v.getTag();
			pauseOrResumeCall(call);
		}
		else if (id == R.id.conferenceStatus) {
			pauseOrResumeConference();
		}
	}

	public void displayCustomToast(final String message, final int duration) {
		LayoutInflater inflater = getLayoutInflater();
		View layout = inflater.inflate(R.layout.toast, (ViewGroup) findViewById(R.id.toastRoot));

		TextView toastText = (TextView) layout.findViewById(R.id.toastMessage);
		toastText.setText(message);

		final Toast toast = new Toast(getApplicationContext());
		toast.setGravity(Gravity.CENTER, 0, 0);
		toast.setDuration(duration);
		toast.setView(layout);
		toast.show();
	}
	
	private void showAudioView() {
		LinphoneManager.startProximitySensorForActivity(InCallActivity.this);
		setCallControlsVisibleAndRemoveCallbacks();
	}
	
	private void toggleMicro() {
		LinphoneCore lc = LinphoneManager.getLc();
		isMicMuted = !isMicMuted;
		lc.muteMic(isMicMuted);
		if (isMicMuted) {
			micro.setBackgroundResource(R.drawable.micro_off);
		} else {
			micro.setBackgroundResource(R.drawable.micro_on);
		}
	}
	
	private void toggleSpeaker() {
		isSpeakerEnabled = !isSpeakerEnabled;
		if (isSpeakerEnabled) {
			LinphoneManager.getInstance().routeAudioToSpeaker();
			speaker.setBackgroundResource(R.drawable.speaker_on);
			LinphoneManager.getLc().enableSpeaker(isSpeakerEnabled);
		} else {
			Log.d("Toggle speaker off, routing back to earpiece");
			LinphoneManager.getInstance().routeAudioToReceiver();
			speaker.setBackgroundResource(R.drawable.speaker_off);
		}
	}
	
	private void pauseOrResumeCall() {
		LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
		if (lc != null && lc.getCallsNb() >= 1) {
			LinphoneCall call = lc.getCalls()[0];
			pauseOrResumeCall(call);
		}
	}
	
	public void pauseOrResumeCall(LinphoneCall call) {
		LinphoneCore lc = LinphoneManager.getLc();
		if (call != null && LinphoneUtils.isCallRunning(call)) {
			if (call.isInConference()) {
				lc.removeFromConference(call);
				if (lc.getConferenceSize() <= 1) {
					lc.leaveConference();
				}
			} else {
				lc.pauseCall(call);
				pause.setBackgroundResource(R.drawable.pause_on);
			}
		} else if (call != null) {
			if (call.getState() == State.Paused) {
				lc.resumeCall(call);
				pause.setBackgroundResource(R.drawable.pause_off);
			}
		}
	}
	
	private void hangUp() {
		LinphoneCore lc = LinphoneManager.getLc();
		LinphoneCall currentCall = lc.getCurrentCall();
		
		if (currentCall != null) {
			lc.terminateCall(currentCall);
		} else if (lc.isInConference()) {
			lc.terminateConference();
		} else {
			lc.terminateAllCalls();
		}
	}
	
	private void enterConference() {
		LinphoneManager.getLc().addAllToConference();
	}
	
	public void pauseOrResumeConference() {
		LinphoneCore lc = LinphoneManager.getLc();
		if (lc.isInConference()) {
			lc.leaveConference();
		} else {
			lc.enterConference();
		}
	}

	public void resetControlsHidingCallBack() {
		if (mControlsHandler != null && mControls != null) {
			mControlsHandler.removeCallbacks(mControls);
		}
		mControls = null;
	}

	public void setCallControlsVisibleAndRemoveCallbacks() {
		if (mControlsHandler != null && mControls != null) {
			mControlsHandler.removeCallbacks(mControls);
		}
		mControls = null;
		
		mControlsLayout.setVisibility(View.VISIBLE);
		callsList.setVisibility(View.VISIBLE);
	}
	
	private void hideNumpad() {
		if (numpad == null || numpad.getVisibility() != View.VISIBLE) {
			return;
		}
			
		dialer.setBackgroundResource(R.drawable.dialer_alt);
		if (isAnimationDisabled) {
			numpad.setVisibility(View.GONE);
		} else {
			Animation animation = slideOutTopToBottom;
			animation.setAnimationListener(new AnimationListener() {
				@Override
				public void onAnimationStart(Animation animation) {
					
				}
				
				@Override
				public void onAnimationRepeat(Animation animation) {
					
				}
				
				@Override
				public void onAnimationEnd(Animation animation) {
					numpad.setVisibility(View.GONE);
					animation.setAnimationListener(null);
				}
			});
			numpad.startAnimation(animation);
		}
	}
	
	private void hideOrDisplayNumpad() {
		if (numpad == null) {
			return;
		}
		
		if (numpad.getVisibility() == View.VISIBLE) {
			hideNumpad();
		} else {	
			dialer.setBackgroundResource(R.drawable.dialer_alt_back);	
			if (isAnimationDisabled) {
				numpad.setVisibility(View.VISIBLE);
			} else {
				Animation animation = slideInBottomToTop;
				animation.setAnimationListener(new AnimationListener() {
					@Override
					public void onAnimationStart(Animation animation) {
						
					}
					
					@Override
					public void onAnimationRepeat(Animation animation) {
						
					}
					
					@Override
					public void onAnimationEnd(Animation animation) {
						numpad.setVisibility(View.VISIBLE);
						animation.setAnimationListener(null);
					}
				});
				numpad.startAnimation(animation);
			}
		}
	}
	
	private void hideAnimatedPortraitCallOptions() {
		Animation animation = slideOutLeftToRight;
		animation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
			}
			
			@Override
			public void onAnimationRepeat(Animation animation) {
			}
			
			@Override
			public void onAnimationEnd(Animation animation) {
				if (isTransferAllowed) {
					transfer.setVisibility(View.INVISIBLE);
				}
				addCall.setVisibility(View.INVISIBLE);
				animation.setAnimationListener(null);
			}
		});
		if (isTransferAllowed) {
			transfer.startAnimation(animation);
		}
		addCall.startAnimation(animation);
	}
	
	private void hideAnimatedLandscapeCallOptions() {
		Animation animation = slideOutTopToBottom;
		if (isTransferAllowed) {
			animation.setAnimationListener(new AnimationListener() {
				@Override
				public void onAnimationStart(Animation animation) {
				}
				
				@Override
				public void onAnimationRepeat(Animation animation) {
				}
				
				@Override
				public void onAnimationEnd(Animation animation) {
					transfer.setAnimation(null);
					transfer.setVisibility(View.INVISIBLE);
					animation = AnimationUtils.loadAnimation(InCallActivity.this, R.anim.slide_out_top_to_bottom); // Reload animation to prevent transfer button to blink
					animation.setAnimationListener(new AnimationListener() {
						@Override
						public void onAnimationStart(Animation animation) {
						}
						
						@Override
						public void onAnimationRepeat(Animation animation) {
						}
						
						@Override
						public void onAnimationEnd(Animation animation) {
							addCall.setVisibility(View.INVISIBLE);
						}
					});
					addCall.startAnimation(animation);
				}
			});
			transfer.startAnimation(animation);
		} else {
			animation.setAnimationListener(new AnimationListener() {
				@Override
				public void onAnimationStart(Animation animation) {
				}
				
				@Override
				public void onAnimationRepeat(Animation animation) {
				}
				
				@Override
				public void onAnimationEnd(Animation animation) {
					addCall.setVisibility(View.INVISIBLE);
				}
			});
			addCall.startAnimation(animation);
		}
	}
	
	private void showAnimatedPortraitCallOptions() {
		Animation animation = slideInRightToLeft;
		animation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
			}
			
			@Override
			public void onAnimationRepeat(Animation animation) {
			}
			
			@Override
			public void onAnimationEnd(Animation animation) {
				options.setBackgroundResource(R.drawable.options_alt);
				if (isTransferAllowed) {
					transfer.setVisibility(View.VISIBLE);
				}
				addCall.setVisibility(View.VISIBLE);
				animation.setAnimationListener(null);
			}
		});
		if (isTransferAllowed) {
			transfer.startAnimation(animation);
		}
		addCall.startAnimation(animation);
	}
	
	private void showAnimatedLandscapeCallOptions() {
		Animation animation = slideInBottomToTop;
		animation.setAnimationListener(new AnimationListener() {
			@Override
			public void onAnimationStart(Animation animation) {
			}
			
			@Override
			public void onAnimationRepeat(Animation animation) {
			}
			
			@Override
			public void onAnimationEnd(Animation animation) {
				addCall.setAnimation(null);
				options.setBackgroundResource(R.drawable.options_alt);
				addCall.setVisibility(View.VISIBLE);
				if (isTransferAllowed) {
					animation.setAnimationListener(new AnimationListener() {
						@Override
						public void onAnimationStart(Animation animation) {
						}
						
						@Override
						public void onAnimationRepeat(Animation animation) {
						}
						
						@Override
						public void onAnimationEnd(Animation animation) {
							transfer.setVisibility(View.VISIBLE);
						}
					});
					transfer.startAnimation(animation);
				}
			}
		});
		addCall.startAnimation(animation);
	}
	
	private void hideOrDisplayAudioRoutes()
	{		
		if (routeSpeaker.getVisibility() == View.VISIBLE) {
			routeSpeaker.setVisibility(View.GONE);
			routeBluetooth.setVisibility(View.GONE);
			routeReceiver.setVisibility(View.GONE);
			audioRoute.setSelected(false);
		} else {
			routeSpeaker.setVisibility(View.VISIBLE);
			routeBluetooth.setVisibility(View.VISIBLE);
			routeReceiver.setVisibility(View.VISIBLE);
			audioRoute.setSelected(true);
		}
	}
	
	private void hideOrDisplayCallOptions() {
		boolean isOrientationLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
		
		if (addCall.getVisibility() == View.VISIBLE) {
			options.setBackgroundResource(R.drawable.options);
			if (isAnimationDisabled) {
				if (isTransferAllowed) {
					transfer.setVisibility(View.INVISIBLE);
				}
				addCall.setVisibility(View.INVISIBLE);
			} else {
				if (isOrientationLandscape) {
					hideAnimatedLandscapeCallOptions();
				} else {
					hideAnimatedPortraitCallOptions();
				}
			}
			options.setSelected(false);
		} else {		
			if (isAnimationDisabled) {
				if (isTransferAllowed) {
					transfer.setVisibility(View.VISIBLE);
				}
				addCall.setVisibility(View.VISIBLE);
				options.setBackgroundResource(R.drawable.options_alt);
			} else {
				if (isOrientationLandscape) {
					showAnimatedLandscapeCallOptions();
				} else {
					showAnimatedPortraitCallOptions();
				}
			}
			options.setSelected(true);
			transfer.setEnabled(LinphoneManager.getLc().getCurrentCall() != null);
		}
	}
	
	public void goBackToDialer() {
		Intent intent = new Intent();
		intent.putExtra("Transfer", false);
		setResult(Activity.RESULT_FIRST_USER, intent);
		finish();
	}
	
	private void goBackToDialerAndDisplayTransferButton() {
		Intent intent = new Intent();
		intent.putExtra("Transfer", true);
		setResult(Activity.RESULT_FIRST_USER, intent);
		finish();
	}
	
	public void acceptCallUpdate() {
		LinphoneCall call = LinphoneManager.getLc().getCurrentCall();
		if (call == null) {
			return;
		}
		 
		LinphoneCallParams params = call.getCurrentParamsCopy();
		try {
			LinphoneManager.getLc().acceptCallUpdate(call, params);
		} catch (LinphoneCoreException e) {
			e.printStackTrace();
		}
	}
	
	public void startIncomingCallActivity() {
		startActivity(new Intent(this, IncomingCallActivity.class));
	}

	@Override
	protected void onResume() {
		instance = this;
		
		LinphoneManager.startProximitySensorForActivity(this);
		setCallControlsVisibleAndRemoveCallbacks();

		super.onResume();
		
		LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
		if (lc != null) {
			lc.addListener(mListener);
		}

		refreshCallList(getResources());
	}

	@Override
	protected void onPause() {
		LinphoneCore lc = LinphoneManager.getLcIfManagerNotDestroyedOrNull();
		if (lc != null) {
			lc.removeListener(mListener);
		}
		
		super.onPause();

		if (mControlsHandler != null && mControls != null) {
			mControlsHandler.removeCallbacks(mControls);
		}
		mControls = null;
	}
	
	@Override
	protected void onDestroy() {
		LinphoneManager.getInstance().changeStatusToOnline();
		
		if (mControlsHandler != null && mControls != null) {
			mControlsHandler.removeCallbacks(mControls);
		}
		mControls = null;
		mControlsHandler = null;
		
		unbindDrawables(findViewById(R.id.topLayout));
		instance = null;
		super.onDestroy();
	    System.gc();
	}
	
	private void unbindDrawables(View view) {
        if (view.getBackground() != null) {
        	view.getBackground().setCallback(null);
        }
        if (view instanceof ImageView) {
        	view.setOnClickListener(null);
        }
        if (view instanceof ViewGroup && !(view instanceof AdapterView)) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            	unbindDrawables(((ViewGroup) view).getChildAt(i));
            }
            ((ViewGroup) view).removeAllViews();
        }
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (LinphoneUtils.onKeyVolumeAdjust(keyCode)) return true;
 		if (LinphoneUtils.onKeyBackGoHome(this, keyCode, event)) return true;
 		return super.onKeyDown(keyCode, event);
 	}

	public void bindAudioFragment(AudioCallFragment fragment) {
		audioCallFragment = fragment;
	}

	private void displayConferenceHeader() {
		LinearLayout conferenceHeader = (LinearLayout) inflater.inflate(R.layout.conference_header, container, false);
		
		ImageView conferenceState = (ImageView) conferenceHeader.findViewById(R.id.conferenceStatus);
		conferenceState.setOnClickListener(this);
		if (LinphoneManager.getLc().isInConference()) {
			conferenceState.setImageResource(R.drawable.play);
		} else {
			conferenceState.setImageResource(R.drawable.pause);
		}
		
		callsList.addView(conferenceHeader);
	}
	
	private void displayCall(Resources resources, LinphoneCall call, int index) {
		String sipUri = call.getRemoteAddress().asStringUriOnly();
        LinphoneAddress lAddress;
		try {
			lAddress = LinphoneCoreFactory.instance().createLinphoneAddress(sipUri);
		} catch (LinphoneCoreException e) {
			Log.e("Incall activity cannot parse remote address",e);
			lAddress= LinphoneCoreFactory.instance().createLinphoneAddress("uknown","unknown","unkonown");
		}

        // Control Row
    	LinearLayout callView = (LinearLayout) inflater.inflate(R.layout.active_call_control_row, container, false);
    	callView.setId(index+1);
		setContactName(callView, lAddress, sipUri, resources);
		displayCallStatusIconAndReturnCallPaused(callView, call);
		setRowBackground(callView, index);
		registerCallDurationTimer(callView, call);
    	callsList.addView(callView);
    	
		// Image Row
    	LinearLayout imageView = (LinearLayout) inflater.inflate(R.layout.active_call_image_row, container, false);
        Contact contact  = ContactsManager.getInstance().findContactWithAddress(imageView.getContext().getContentResolver(), lAddress);
		if(contact != null) {
			displayOrHideContactPicture(imageView, contact.getPhotoUri(), contact.getThumbnailUri(), false);
		} else {
			displayOrHideContactPicture(imageView, null, null, false);
		}
    	callsList.addView(imageView);
    	
    	callView.setTag(imageView);
    	callView.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (v.getTag() != null) {
					View imageView = (View) v.getTag();
					if (imageView.getVisibility() == View.VISIBLE)
						imageView.setVisibility(View.GONE);
					else
						imageView.setVisibility(View.VISIBLE);
					callsList.invalidate();
				}
			}
		});
	}
	
	private void setContactName(LinearLayout callView, LinphoneAddress lAddress, String sipUri, Resources resources) {
		TextView contact = (TextView) callView.findViewById(R.id.contactNameOrNumber);

		Contact lContact  = ContactsManager.getInstance().findContactWithAddress(callView.getContext().getContentResolver(), lAddress);
		if (lContact == null) {
	        if (resources.getBoolean(R.bool.only_display_username_if_unknown) && LinphoneUtils.isSipAddress(sipUri)) {
	        	contact.setText(lAddress.getUserName());
			} else {
				contact.setText(sipUri);
			}
		} else {
			contact.setText(lContact.getName());
		}
	}
	
	private boolean displayCallStatusIconAndReturnCallPaused(LinearLayout callView, LinphoneCall call) {
		boolean isCallPaused, isInConference;
		ImageView callState = (ImageView) callView.findViewById(R.id.callStatus);
		callState.setTag(call);
		callState.setOnClickListener(this);
		
		if (call.getState() == State.Paused || call.getState() == State.PausedByRemote || call.getState() == State.Pausing) {
			callState.setImageResource(R.drawable.pause);
			isCallPaused = true;
			isInConference = false;
		} else if (call.getState() == State.OutgoingInit || call.getState() == State.OutgoingProgress || call.getState() == State.OutgoingRinging) {
			callState.setImageResource(R.drawable.call_state_ringing_default);
			isCallPaused = false;
			isInConference = false;
		} else {
			if (isConferenceRunning && call.isInConference()) {
				callState.setImageResource(R.drawable.remove);
				isInConference = true;
			} else {
				callState.setImageResource(R.drawable.play);
				isInConference = false;
			}
			isCallPaused = false;
		}
		
		return isCallPaused || isInConference;
	}
	
	private void displayOrHideContactPicture(LinearLayout callView, Uri pictureUri, Uri thumbnailUri, boolean hide) {
		AvatarWithShadow contactPicture = (AvatarWithShadow) callView.findViewById(R.id.contactPicture);
		if (pictureUri != null) {
        	LinphoneUtils.setImagePictureFromUri(callView.getContext(), contactPicture.getView(), Uri.parse(pictureUri.toString()), thumbnailUri, R.drawable.unknown_small);
        }
		callView.setVisibility(hide ? View.GONE : View.VISIBLE);
	}
	
	private void setRowBackground(LinearLayout callView, int index) {
		int backgroundResource;
		if (index == 0) {
//			backgroundResource = active ? R.drawable.cell_call_first_highlight : R.drawable.cell_call_first;
			backgroundResource = R.drawable.cell_call_first;
		} else {
//			backgroundResource = active ? R.drawable.cell_call_highlight : R.drawable.cell_call;
			backgroundResource = R.drawable.cell_call;
		}
		callView.setBackgroundResource(backgroundResource);
	}
	
	private void registerCallDurationTimer(View v, LinphoneCall call) {
		int callDuration = call.getDuration();
		if (callDuration == 0 && call.getState() != State.StreamsRunning) {
			return;
		}
		
		Chronometer timer = (Chronometer) v.findViewById(R.id.callTimer);
		if (timer == null) {
			throw new IllegalArgumentException("no callee_duration view found");
		}
		
		timer.setBase(SystemClock.elapsedRealtime() - 1000 * callDuration);
		timer.start();
	}
	
	public void refreshCallList(Resources resources) {
		if (callsList == null) {
			return;
		}

		callsList.removeAllViews();
		int index = 0;
        
        if (LinphoneManager.getLc().getCallsNb() == 0) {
        	goBackToDialer();
        	return;
        }
		
        isConferenceRunning = LinphoneManager.getLc().getConferenceSize() > 1;
        if (isConferenceRunning) {
        	displayConferenceHeader();
        	index++;
        }
        for (LinphoneCall call : LinphoneManager.getLc().getCalls()) {
        	displayCall(resources, call, index);	
        	index++;
        }
        
        if(LinphoneManager.getLc().getCurrentCall() == null){
        	showAudioView();
        }
        
        callsList.invalidate();
	}
}
