/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IStatusBar;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocalPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import static android.provider.Settings.System.END_BUTTON_BEHAVIOR;

import com.android.internal.policy.PolicyManager;
import com.android.internal.telephony.ITelephony;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationListener;
import android.view.RawInputEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD;
import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
import static android.view.WindowManager.LayoutParams.TYPE_SEARCH_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import android.view.WindowManagerImpl;
import android.view.WindowManagerPolicy;
import android.view.WindowManagerPolicy.WindowState;
import android.media.IAudioService;
import android.media.AudioManager;

import java.util.Observable;
import java.util.Observer;

/**
 * WindowManagerPolicy implementation for the Android MID UI.
 */
public class MidWindowManager implements WindowManagerPolicy {
    private static final String TAG = "MidWindowManager";
    private static final boolean DEBUG = false;
    private static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    private static final boolean SHOW_STARTING_ANIMATIONS = true;
    
    private static final int APPLICATION_LAYER = 1;
    private static final int PHONE_LAYER = 2;
    private static final int SEARCH_BAR_LAYER = 3;
    private static final int STATUS_BAR_PANEL_LAYER = 4;
    // toasts and the plugged-in battery thing
    private static final int TOAST_LAYER = 5;
    private static final int STATUS_BAR_LAYER = 6;
    // SIM errors and unlock.  Not sure if this really should be in a high layer.
    private static final int PRIORITY_PHONE_LAYER = 7;
    // like the ANR / app crashed dialogs
    private static final int SYSTEM_ALERT_LAYER = 8;
    // system-level error dialogs
    private static final int SYSTEM_ERROR_LAYER = 9;
    // the keyguard; nothing on top of these can take focus, since they are
    // responsible for power management when displayed.
    private static final int KEYGUARD_LAYER = 10;
    private static final int KEYGUARD_DIALOG_LAYER = 11;
    // things in here CAN NOT take focus, but are shown on top of everything else.
    private static final int SYSTEM_OVERLAY_LAYER = 12;

    static final int APPLICATION_MEDIA_SUBLAYER = -2;
    static final int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
    static final int APPLICATION_PANEL_SUBLAYER = 1;
    static final int APPLICATION_SUB_PANEL_SUBLAYER = 2;

    private static final boolean SINGLE_PRESS_OFF = false;
    
    private static final float SLIDE_TOUCH_EVENT_SIZE_LIMIT = 0.6f;
    
    static public final String SYSTEM_DIALOG_REASON_KEY = "reason";
    static public final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
    static public final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";

    private Context mContext;
    private IWindowManager mWindowManager;
    private LocalPowerManager mPowerManager;

    boolean mSafeMode;
    private WindowState mStatusBar = null;
    private WindowState mSearchBar = null;
    private WindowState mKeyguard = null;
    private boolean mFirstConnect = true;
    private GlobalActions mGlobalActions;
    private boolean mShouldTurnOffOnKeyUp;
    private RecentApplicationsDialog mRecentAppsDialog;
    private Handler mHandler;

    private boolean mLidOpen;
    private boolean mScreenOn = false;
    private int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    
    private int mW, mH;
    private int mCurLeft, mCurTop, mCurRight, mCurBottom;
    
    static final Rect mTmpParentFrame = new Rect();
    static final Rect mTmpDisplayFrame = new Rect();
    static final Rect mTmpVisibleFrame = new Rect();
    
    private WindowState mTopFullscreenOpaqueWindowState;
    private boolean mForceStatusBar;
    private boolean mHomePressed;
    private Intent mHomeIntent;
    private boolean mSearchKeyPressed;
    private boolean mConsumeSearchKeyUp;

    private static final int ENDCALL_HOME = 0x1;
    private static final int ENDCALL_SLEEPS = 0x2;
    private static final int DEFAULT_ENDCALL_BEHAVIOR = ENDCALL_SLEEPS;
    private int mEndcallBehavior;

    private ShortcutManager mShortcutManager;
    private PowerManager.WakeLock mBroadcastWakeLock;

    /*
     * Various use cases for invoking this function
     * screen turning off, should always disable listeners if already enabled
     * screen turned on and current app has sensor based orientation, enable listeners 
     * if not already enabled
     * screen turned on and current app does not have sensor orientation, disable listeners if
     * already enabled
     * screen turning on and current app has sensor based orientation, enable listeners if needed
     * screen turning on and current app has nosensor based orientation, do nothing
     */

    private Runnable mEndCallLongPress = new Runnable() {
        public void run() {
            mShouldTurnOffOnKeyUp = false;
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS);
            showGlobalActionsDialog();
        }
    };

    private void showGlobalActionsDialog() {
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActions(mContext, mPowerManager);
        }
        mGlobalActions.showDialog(false, isDeviceProvisioned());
    }

    private boolean isDeviceProvisioned() {
        return Settings.System.getInt(
                mContext.getContentResolver(), Settings.System.DEVICE_PROVISIONED, 0) != 0;
    }

    /**
     * When a home-key longpress expires, close other system windows and launch the recent apps
     */
    private Runnable mHomeLongPress = new Runnable() {
        public void run() {
            /*
             * Eat the longpress so it won't dismiss the recent apps dialog when
             * the user lets go of the home key
             */
            mHomePressed = false;
            sendCloseSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
            showRecentAppsDialog();
        }
    };

    /**
     * Create (if necessary) and launch the recent apps dialog
     */
    private void showRecentAppsDialog() {
        if (mRecentAppsDialog == null) {
            mRecentAppsDialog = new RecentApplicationsDialog(mContext);
        }
        mRecentAppsDialog.show();
    }
    
    /** {@inheritDoc} */
    public void init(Context context, IWindowManager windowManager,
            LocalPowerManager powerManager) {
        mContext = context;
        mWindowManager = windowManager;
        mPowerManager = powerManager;
        mHandler = new Handler();
        mShortcutManager = new ShortcutManager(context, mHandler);
        mShortcutManager.observe();
        mHomeIntent =  new Intent(Intent.ACTION_MAIN, null);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mBroadcastWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MidWindowManager.mBroadcastWakeLock");
    }

    /** {@inheritDoc} */
    public int checkAddPermission(WindowManager.LayoutParams attrs) {
        int type = attrs.type;
        if (type < WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                || type > WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
            return WindowManagerImpl.ADD_OKAY;
        }
        String permission = null;
        switch (type) {
            case TYPE_TOAST:
                // XXX right now the app process has complete control over
                // this...  should introduce a token to let the system
                // monitor/control what they are doing.
                break;
            case TYPE_PHONE:
            case TYPE_PRIORITY_PHONE:
            case TYPE_SYSTEM_ALERT:
            case TYPE_SYSTEM_ERROR:
            case TYPE_SYSTEM_OVERLAY:
                permission = android.Manifest.permission.SYSTEM_ALERT_WINDOW;
                break;
            default:
                permission = android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
        }
        if (permission != null) {
            if (mContext.checkCallingOrSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return WindowManagerImpl.ADD_PERMISSION_DENIED;
            }
        }
        return WindowManagerImpl.ADD_OKAY;
    }
    
    public void adjustWindowParamsLw(WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_SYSTEM_OVERLAY:
            case TYPE_TOAST:
                // These types of windows can't receive input events.
                attrs.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                break;
        }
    }
    
    /** {@inheritDoc} */
    public void adjustConfigurationLw(Configuration config) {
        mPowerManager.setKeyboardVisibility(true);
        config.keyboardHidden = Configuration.KEYBOARDHIDDEN_NO;
        mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                LocalPowerManager.OTHER_EVENT);
    }
    
    public boolean isCheekPressedAgainstScreen(MotionEvent ev) {
        return false;
    }
    
    /** {@inheritDoc} */
    public int windowTypeToLayerLw(int type) {
        if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return APPLICATION_LAYER;
        }
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_LAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_LAYER;
        case TYPE_STATUS_BAR:
            return STATUS_BAR_LAYER;
        case TYPE_STATUS_BAR_PANEL:
            return STATUS_BAR_PANEL_LAYER;
        case TYPE_SEARCH_BAR:
            return SEARCH_BAR_LAYER;
        case TYPE_PHONE:
            return PHONE_LAYER;
        case TYPE_KEYGUARD:
            return KEYGUARD_LAYER;
        case TYPE_KEYGUARD_DIALOG:
            return KEYGUARD_DIALOG_LAYER;
        case TYPE_SYSTEM_ALERT:
            return SYSTEM_ALERT_LAYER;
        case TYPE_SYSTEM_ERROR:
            return SYSTEM_ERROR_LAYER;
        case TYPE_SYSTEM_OVERLAY:
            return SYSTEM_OVERLAY_LAYER;
        case TYPE_PRIORITY_PHONE:
            return PRIORITY_PHONE_LAYER;
        case TYPE_TOAST:
            return TOAST_LAYER;
        }
        Log.e(TAG, "Unknown window type: " + type);
        return APPLICATION_LAYER;
    }

    /** {@inheritDoc} */
    public int subWindowTypeToLayerLw(int type) {
        switch (type) {
        case TYPE_APPLICATION_PANEL:
            return APPLICATION_PANEL_SUBLAYER;
        case TYPE_APPLICATION_MEDIA:
            return APPLICATION_MEDIA_SUBLAYER;
        case TYPE_APPLICATION_MEDIA_OVERLAY:
            return APPLICATION_MEDIA_OVERLAY_SUBLAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_SUB_PANEL_SUBLAYER;
        }
        Log.e(TAG, "Unknown sub-window type: " + type);
        return 0;
    }

    /** {@inheritDoc} */
    public View addStartingWindow(IBinder appToken, String packageName,
                                  int theme, CharSequence nonLocalizedLabel,
                                  int labelRes, int icon) {
        if (!SHOW_STARTING_ANIMATIONS) {
            return null;
        }
        if (packageName == null) {
            return null;
        }
        
    	Context context = mContext;
    	boolean setTheme = false;
    	//Log.i(TAG, "addStartingWindow " + packageName + ": nonLocalizedLabel="
    	//        + nonLocalizedLabel + " theme=" + Integer.toHexString(theme));
    	if (theme != 0 || labelRes != 0) {
    	    try {
    	        context = context.createPackageContext(packageName, 0);
    	        if (theme != 0) {
    	            context.setTheme(theme);
    	            setTheme = true;
    	        }
    	    } catch (PackageManager.NameNotFoundException e) {
    	    }
    	}
    	if (!setTheme) {
    	    context.setTheme(com.android.internal.R.style.Theme);
    	}
    	
        Window win = PolicyManager.makeNewWindow(context);
        Resources r = context.getResources();
        win.setTitle(r.getText(labelRes, nonLocalizedLabel));

        win.setType(
            WindowManager.LayoutParams.TYPE_APPLICATION_STARTING);
        win.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        win.setLayout(WindowManager.LayoutParams.FILL_PARENT,
                            WindowManager.LayoutParams.FILL_PARENT);

        final WindowManager.LayoutParams params = win.getAttributes();
        params.token = appToken;
        params.packageName = packageName;
        params.windowAnimations = win.getWindowStyle().getResourceId(
                com.android.internal.R.styleable.Window_windowAnimationStyle, 0);
        params.setTitle("Starting " + packageName);

        try {
            WindowManagerImpl wm = (WindowManagerImpl)
                    context.getSystemService(Context.WINDOW_SERVICE);
            View view = win.getDecorView();

            if (win.isFloating()) {
                // Whoops, there is no way to display an animation/preview
                // of such a thing!  After all that work...  let's skip it.
                // (Note that we must do this here because it is in
                // getDecorView() where the theme is evaluated...  maybe
                // we should peek the floating attribute from the theme
                // earlier.)
                return null;
            }
            
            if (localLOGV) Log.v(
                TAG, "Adding starting window for " + packageName
                + " / " + appToken + ": "
                + (view.getParent() != null ? view : null));

            wm.addView(view, params);

            // Only return the view if it was successfully added to the
            // window manager... which we can tell by it having a parent.
            return view.getParent() != null ? view : null;
        } catch (WindowManagerImpl.BadTokenException e) {
            // ignore
            Log.w(TAG, appToken + " already running, starting window not displayed");
        }

        return null;
    }

    /** {@inheritDoc} */
    public void removeStartingWindow(IBinder appToken, View window) {
        // RuntimeException e = new RuntimeException();
        // Log.i(TAG, "remove " + appToken + " " + window, e);

        if (localLOGV) Log.v(
            TAG, "Removing starting window for " + appToken + ": " + window);

        if (window != null) {
            WindowManagerImpl wm = (WindowManagerImpl) mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.removeView(window);
        }
    }

    /**
     * Preflight adding a window to the system.
     * 
     * Currently enforces that three window types are singletons:
     * <ul>
     * <li>STATUS_BAR_TYPE</li>
     * <li>SEARCH_BAR_TYPE</li>
     * <li>KEYGUARD_TYPE</li>
     * </ul>
     * 
     * @param win The window to be added
     * @param attrs Information about the window to be added
     * 
     * @return If ok, WindowManagerImpl.ADD_OKAY.  If too many singletons, WindowManagerImpl.ADD_MULTIPLE_SINGLETON
     */
    public int prepareAddWindowLw(WindowState win, WindowManager.LayoutParams attrs) {
        switch (attrs.type) {
            case TYPE_STATUS_BAR:
                if (mStatusBar != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mStatusBar = win;
                break;
            case TYPE_SEARCH_BAR:
                if (mSearchBar != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mSearchBar = win;
                break;
            case TYPE_KEYGUARD:
                if (mKeyguard != null) {
                    return WindowManagerImpl.ADD_MULTIPLE_SINGLETON;
                }
                mKeyguard = win;
                break;
        }
        return WindowManagerImpl.ADD_OKAY;
    }

    /** {@inheritDoc} */
    public void removeWindowLw(WindowState win) {
        if (mStatusBar == win) {
            mStatusBar = null;
        }
        else if (mSearchBar == win) {
            mSearchBar = null;
        }
        else if (mKeyguard == win) {
            mKeyguard = null;
        }
    }

    private static final boolean PRINT_ANIM = false;
    
    /** {@inheritDoc} */
    public int selectAnimationLw(WindowState win, int transit) {
        if (PRINT_ANIM) Log.i(TAG, "selectAnimation in " + win
              + ": transit=" + transit);
        if (transit == TRANSIT_PREVIEW_DONE) {
            if (win.hasAppShownWindows()) {
                if (PRINT_ANIM) Log.i(TAG, "**** STARTING EXIT");
                return com.android.internal.R.anim.app_starting_exit;
            }
        }

        return 0;
    }

    private static IAudioService getAudioInterface() {
        return IAudioService.Stub.asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
    }

    /** {@inheritDoc} */
    public boolean interceptKeyTi(WindowState win, int code, int metaKeys, boolean down, 
            int repeatCount) {
        if (false) {
            Log.d(TAG, "interceptKeyTi code=" + code + " down=" + down + " repeatCount="
                    + repeatCount);
        }

        // Clear a pending HOME longpress if the user releases Home
        // TODO: This could probably be inside the next bit of logic, but that code
        // turned out to be a bit fragile so I'm doing it here explicitly, for now.
        if ((code == KeyEvent.KEYCODE_HOME) && !down) {
            mHandler.removeCallbacks(mHomeLongPress);
        }

        // If the HOME button is currently being held, then we do special
        // chording with it.
        if (mHomePressed) {
            
            // If we have released the home key, and didn't do anything else
            // while it was pressed, then it is time to go home!
            if (code == KeyEvent.KEYCODE_HOME) {
                if (!down) {
                    mHomePressed = false;
                    launchHomeFromHotKey();
                }
            }
            
            return true;
        }
            
        // First we always handle the home key here, so applications
        // can never break it, although if keyguard is on, we do let
        // it handle it, because that gives us the correct 5 second
        // timeout.
        if (code == KeyEvent.KEYCODE_HOME) {

            // If a system window has focus, then it doesn't make sense
            // right now to interact with applications.
            WindowManager.LayoutParams attrs = win != null ? win.getAttrs() : null;
            if (attrs != null) {
                int type = attrs.type;
                if (type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW
                        && type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW) {
                    // Only do this once, so home-key-longpress doesn't close itself
                    if (repeatCount == 0 && down) {
                		sendCloseSystemWindows();
                    }
                    return false;
                }
            }
            
            if (down && repeatCount == 0) {
                mHandler.postDelayed(mHomeLongPress, ViewConfiguration.getGlobalActionKeyTimeout());
                mHomePressed = true;
            }
            return true;
        } else if (code == KeyEvent.KEYCODE_MENU) {
            // Hijack modified menu keys for debugging features
            final int chordBug = KeyEvent.META_SHIFT_ON;
            final int chordProcess = KeyEvent.META_ALT_ON;
           
            if (down && repeatCount == 0) {
                if ((metaKeys & chordBug) == chordBug) {
                    Intent intent = new Intent(Intent.ACTION_BUG_REPORT);
                    mContext.sendOrderedBroadcast(intent, null);
                    return true;
                } else if ((metaKeys & chordProcess) == chordProcess) {
                    Intent service = new Intent();
                    service.setClassName(mContext, "com.android.server.LoadAverageService");
                    ContentResolver res = mContext.getContentResolver();
                    boolean shown = Settings.System.getInt(
                            res, Settings.System.SHOW_PROCESSES, 0) != 0;
                    if (!shown) {
                        mContext.startService(service);
                    } else {
                        mContext.stopService(service);
                    }
                    Settings.System.putInt(
                            res, Settings.System.SHOW_PROCESSES, shown ? 0 : 1);
                    return true;
                }
            }
        } else if (code == KeyEvent.KEYCODE_NOTIFICATION) {
            if (down) {
                // this key doesn't exist on current hardware, but if a device
                // didn't have a touchscreen, it would want one of these to open
                // the status bar.
                IStatusBar sbs = IStatusBar.Stub.asInterface(ServiceManager.getService("statusbar"));
                if (sbs != null) {
                    try {
                        sbs.toggle();
                    } catch (RemoteException e) {
                        // we're screwed anyway, since it's in this process
                        throw new RuntimeException(e);
                    }
                }
            }
            return true;
        } else if (code == KeyEvent.KEYCODE_SEARCH) {
            if (down) {
                if (repeatCount == 0) {
                    mSearchKeyPressed = true;
                }
            } else {
                mSearchKeyPressed = false;
                
                if (mConsumeSearchKeyUp) {
                    // Consume the up-event
                    mConsumeSearchKeyUp = false;
                    return true;
                }
            }
        }
        
        // Shortcuts are invoked through Search+key, so intercept those here
        if (mSearchKeyPressed) {
            if (down && repeatCount == 0) {
                Intent shortcutIntent = mShortcutManager.getIntent(code, metaKeys);
                if (shortcutIntent != null) {
                    shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(shortcutIntent);
                    
                    /*
                     * We launched an app, so the up-event of the search key
                     * should be consumed
                     */
                    mConsumeSearchKeyUp = true;
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * A home key -> launch home action was detected.  Take the appropriate action
     * given the situation with the keyguard.
     */
    private void launchHomeFromHotKey() {
        // no keyguard stuff to worry about, just launch home!
        try {
            ActivityManagerNative.getDefault().stopAppSwitches();
        } catch (RemoteException e) {
        }
        mContext.startActivity(mHomeIntent);
        sendCloseSystemWindows();
    }

    public void getContentInsetHintLw(WindowManager.LayoutParams attrs, Rect coveredInset) {
        final int fl = attrs.flags;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            coveredInset.set(mCurLeft, mCurTop, mW - mCurRight, mH - mCurBottom);
        } else {
            coveredInset.setEmpty();
        }
    }
    
    /** {@inheritDoc} */
    public void beginLayoutLw(int displayWidth, int displayHeight) {
        mW = displayWidth;
        mH = displayHeight;
        mCurLeft = 0;
        mCurTop = 0;
        mCurRight = displayWidth;
        mCurBottom = displayHeight;

        // decide where the status bar goes ahead of time
        if (mStatusBar != null) {
            final Rect pf = mTmpParentFrame;
            final Rect df = mTmpDisplayFrame;
            final Rect vf = mTmpVisibleFrame;
            pf.left = df.left = vf.left = 0;
            pf.top = df.top = vf.top = 0;
            pf.right = df.right = vf.right = displayWidth;
            pf.bottom = df.bottom = vf.bottom = displayHeight;
            
            mStatusBar.computeFrameLw(pf, df, vf, vf);
            mCurTop = mStatusBar.getFrameLw().bottom;
        }
    }

    /** {@inheritDoc} */
    public void layoutWindowLw(WindowState win, WindowManager.LayoutParams attrs, WindowState attached) {
        // we've already done the status bar
        if (win == mStatusBar) {
            return;
        }

        final int fl = attrs.flags;
        
        final Rect pf = mTmpParentFrame;
        final Rect df = mTmpDisplayFrame;
        final Rect vf = mTmpVisibleFrame;
        
        if ((fl &
                (FLAG_LAYOUT_IN_SCREEN | FLAG_FULLSCREEN | FLAG_LAYOUT_INSET_DECOR))
                == (FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR)) {
            df.left = 0;
            df.top = 0;
            df.right = mW;
            df.bottom = mH;
            vf.left = mCurLeft;
            vf.top = mCurTop;
            vf.right = mCurRight;
            vf.bottom = mCurBottom;
        } else if ((fl & FLAG_LAYOUT_IN_SCREEN) == 0) {
            // Make sure this window doesn't intrude into the status bar.
            df.left = vf.left = mCurLeft;
            df.top = vf.top = mCurTop;
            df.right = vf.right = mCurRight;
            df.bottom = vf.bottom = mCurBottom;
        } else {
            df.left = vf.left = 0;
            df.top = vf.top = 0;
            df.right = vf.right = mW;
            df.bottom = vf.bottom = mH;
        }
        
        if (attached != null && (fl & (FLAG_LAYOUT_IN_SCREEN)) == 0) {
            pf.set(attached.getFrameLw());
        } else {
            pf.set(df);
        }
        
        if ((fl & FLAG_LAYOUT_NO_LIMITS) != 0) {
            df.left = df.top = vf.left = vf.top = -10000;
            df.right = df.bottom = vf.right = vf.bottom = 10000;
        }

        win.computeFrameLw(pf, df, vf, vf);
    }

    /** {@inheritDoc} */
    public boolean finishLayoutLw() {
        return false;
    }

    /** {@inheritDoc} */
    public void beginAnimationLw(int displayWidth, int displayHeight) {
        mTopFullscreenOpaqueWindowState = null;
        mForceStatusBar = false;
    }

    /** {@inheritDoc} */
    public void animatingWindowLw(WindowState win,
                                WindowManager.LayoutParams attrs) {
        if (mTopFullscreenOpaqueWindowState == null
            && attrs.type >= FIRST_APPLICATION_WINDOW
            && attrs.type <= LAST_APPLICATION_WINDOW
            && win.fillsScreenLw(mW, mH, true, false)
            && win.isDisplayedLw()) {
            mTopFullscreenOpaqueWindowState = win;
        } else if ((attrs.flags & FLAG_FORCE_NOT_FULLSCREEN) != 0) {
            mForceStatusBar = true;
        }
    }

    /** {@inheritDoc} */
    public boolean finishAnimationLw() {
        if (mStatusBar != null) {
            if (mForceStatusBar) {
                mStatusBar.showLw(true);
            } else if (mTopFullscreenOpaqueWindowState != null) {
               WindowManager.LayoutParams lp =
                   mTopFullscreenOpaqueWindowState.getAttrs();
               boolean hideStatusBar =
                   (lp.flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
               if (hideStatusBar) {
                   mStatusBar.hideLw(true);
               } else {
                   mStatusBar.showLw(true);
               }
           }
        }
       return false;
    }

    /** {@inheritDoc} */
    public boolean preprocessInputEventTq(RawInputEvent event) {
        return false;
    }

    
    /** {@inheritDoc} */
    public boolean isAppSwitchKeyTqTiLwLi(int keycode) {
        return keycode == KeyEvent.KEYCODE_HOME
                || keycode == KeyEvent.KEYCODE_ENDCALL;
    }
    
    /** {@inheritDoc} */
    public boolean isMovementKeyTi(int keycode) {
        switch (keycode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
        }
        return false;
    }

    /**
     * @return Whether music is being played right now.
     */
    private boolean isMusicActive() {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "isMusicActive: couldn't get IAudioService reference");
            return false;
        }
        try {
            return audio.isMusicActive();
        } catch (RemoteException e) {
            Log.w(TAG, "IAudioService.isMusicActive() threw RemoteException " + e);
            return false;
        }
    }

    /**
     * Tell the audio service to adjust the volume appropriate to the event.
     * @param keycode
     */
    private void sendVolToMusic(int keycode) {
        final IAudioService audio = getAudioInterface();
        if (audio == null) {
            Log.w(TAG, "sendVolToMusic: couldn't get IAudioService reference");
            return;
        }
        try {
            // since audio is playing, we shouldn't have to hold a wake lock
            // during the call, but we do it as a precaution for the rare possibility
            // that the music stops right before we call this
            mBroadcastWakeLock.acquire();
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                keycode == KeyEvent.KEYCODE_VOLUME_UP
                            ? AudioManager.ADJUST_RAISE
                            : AudioManager.ADJUST_LOWER,
                    0);
        } catch (RemoteException e) {
            Log.w(TAG, "IAudioService.adjustStreamVolume() threw RemoteException " + e);
        } finally {
            mBroadcastWakeLock.release();
        }
    }

    /** {@inheritDoc} */
    public int interceptKeyTq(RawInputEvent event, boolean screenIsOn) {
        int result = ACTION_PASS_TO_USER | ACTION_POKE_USER_ACTIVITY;
        return result;
    }

    class PassHeadsetKey implements Runnable {
        KeyEvent mKeyEvent;

        PassHeadsetKey(KeyEvent keyEvent) {
            mKeyEvent = keyEvent;
        }

        public void run() {
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
            intent.putExtra(Intent.EXTRA_KEY_EVENT, mKeyEvent);
            mContext.sendOrderedBroadcast(intent, null, mBroadcastDone,
                    mHandler, Activity.RESULT_OK, null, null);
        }
    }

    private BroadcastReceiver mBroadcastDone = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            mBroadcastWakeLock.release();
        }
    };
    
    /** {@inheritDoc} */
    public boolean isWakeRelMovementTq(int device, int classes,
            RawInputEvent event) {
        // if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /** {@inheritDoc} */
    public boolean isWakeAbsMovementTq(int device, int classes,
            RawInputEvent event) {
        // if it's tagged with one of the wake bits, it wakes up the device
        return ((event.flags & (FLAG_WAKE | FLAG_WAKE_DROPPED)) != 0);
    }

    /**
     * Given the current state of the world, should this key wake up the device?
     */
    protected boolean isWakeKeyTq(RawInputEvent event) {
        // There are not key maps for trackball devices, but we'd still
        // like to have pressing it wake the device up, so force it here.
        int keycode = event.keycode;
        int flags = event.flags;
        if (keycode == RawInputEvent.BTN_MOUSE) {
            flags |= WindowManagerPolicy.FLAG_WAKE;
        }
        return (flags
                & (WindowManagerPolicy.FLAG_WAKE | WindowManagerPolicy.FLAG_WAKE_DROPPED)) != 0;
    }

    /** {@inheritDoc} */
    public void screenTurnedOff(int why) {
        EventLog.writeEvent(70000, 0);
        mScreenOn = false;
    }

    /** {@inheritDoc} */
    public void screenTurnedOn() {
        EventLog.writeEvent(70000, 1);
        mScreenOn = true;
    }

    /** {@inheritDoc} */
    public void enableKeyguard(boolean enabled) {
    }

    /** {@inheritDoc} */
    public void exitKeyguardSecurely(OnKeyguardExitResult callback) {
    }

    /** {@inheritDoc} */
    public boolean keyguardIsShowingTq() {
        return false;
    }

    /** {@inheritDoc} */
    public boolean inKeyguardRestrictedKeyInputMode() {
        return false;
    }

    /**
     * Callback from {@link KeyguardViewMediator}
     */
    public void onKeyguardShow() {
        sendCloseSystemWindows();
    }

    private void sendCloseSystemWindows() {
        sendCloseSystemWindows(null);
    }

    private void sendCloseSystemWindows(String reason) {
        try {
            ActivityManagerNative.getDefault().closeSystemDialogs(reason);
        } catch (RemoteException e) {
        }
    }

    public int rotationForOrientationLw(int orientation, int lastRotation,
            boolean displayEnabled) {
        // get rid of rotation for now. Always rotation of 0
        return Surface.ROTATION_0;
    }
    
    public boolean detectSafeMode() {
        try {
            int menuState = mWindowManager.getKeycodeState(KeyEvent.KEYCODE_MENU);
            mSafeMode = menuState > 0;
            Log.i(TAG, "Menu key state: " + menuState + " safeMode=" + mSafeMode);
            return mSafeMode;
        } catch (RemoteException e) {
            // Doom! (it's also local)
            throw new RuntimeException("window manager dead");
        }
    }
    
    /** {@inheritDoc} */
    public void systemReady() {
        try {
            if (mSafeMode) {
                // If the user is holding the menu key code, then we are
                // going to boot into safe mode.
                ActivityManagerNative.getDefault().enterSafeMode();
            } else {
                // tell the keyguard
                android.os.SystemProperties.set("dev.bootcomplete", "1"); 
            }
        } catch (RemoteException e) {
        }
    }
    
    /** {@inheritDoc} */
    public void enableScreenAfterBoot() {
        updateRotation();
    }
    
    private void updateRotation() {
        mPowerManager.setKeyboardVisibility(true);
        int rotation = Surface.ROTATION_0;
        try {
            //set orientation on WindowManager
            mWindowManager.setRotation(rotation, true, 0);
        } catch (RemoteException e) {
        }

        mPowerManager.userActivity(SystemClock.uptimeMillis(), false,
                LocalPowerManager.OTHER_EVENT);
    }

    /**
     * goes to the home screen
     * @return whether it did anything
     */
    boolean goHome() {
        if (false) {
            // This code always brings home to the front.
            try {
                ActivityManagerNative.getDefault().stopAppSwitches();
            } catch (RemoteException e) {
            }
            mContext.startActivity(mHomeIntent);
        } else {
            // This code brings home to the front or, if it is already
            // at the front, puts the device to sleep.
            try {
                ActivityManagerNative.getDefault().stopAppSwitches();
                int result = ActivityManagerNative.getDefault()
                        .startActivity(null, mHomeIntent,
                                mHomeIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                                null, 0, null, null, 0, true /* onlyIfNeeded*/, false);
                if (result == IActivityManager.START_RETURN_INTENT_TO_CALLER) {
                    return false;
                }
            } catch (RemoteException ex) {
                // bummer, the activity manager, which is in this process, is dead
            }
        }
        sendCloseSystemWindows();
        return true;
    }
    
    public void setCurrentOrientationLw(int newOrientation) {
        if(newOrientation != mCurrentAppOrientation) {
            mCurrentAppOrientation = newOrientation;
        }
    }
    
    public boolean performHapticFeedbackLw(WindowState win, int effectId, boolean always) {
        return false;
    }
    
    public void screenOnStoppedLw() {
    }
}
