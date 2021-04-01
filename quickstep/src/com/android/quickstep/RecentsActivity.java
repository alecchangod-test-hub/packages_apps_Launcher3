/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.quickstep;

import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;

import static com.android.launcher3.QuickstepTransitionManager.RECENTS_LAUNCH_DURATION;
import static com.android.launcher3.QuickstepTransitionManager.STATUS_BAR_TRANSITION_DURATION;
import static com.android.launcher3.QuickstepTransitionManager.STATUS_BAR_TRANSITION_PRE_DELAY;
import static com.android.launcher3.Utilities.createHomeIntent;
import static com.android.launcher3.testing.TestProtocol.OVERVIEW_STATE_ORDINAL;
import static com.android.quickstep.TaskUtils.taskIsATargetWithMode;
import static com.android.quickstep.TaskViewUtils.createRecentsWindowAnimator;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.InvariantDeviceProfile;
import com.android.launcher3.LauncherAnimationRunner;
import com.android.launcher3.LauncherAnimationRunner.AnimationResult;
import com.android.launcher3.R;
import com.android.launcher3.WrappedAnimationRunnerImpl;
import com.android.launcher3.WrappedLauncherAnimationRunner;
import com.android.launcher3.anim.Interpolators;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.compat.AccessibilityManagerCompat;
import com.android.launcher3.statemanager.StateManager;
import com.android.launcher3.statemanager.StateManager.AtomicAnimationFactory;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.statemanager.StatefulActivity;
import com.android.launcher3.util.ActivityOptionsWrapper;
import com.android.launcher3.util.ActivityTracker;
import com.android.launcher3.util.RunnableList;
import com.android.launcher3.util.SystemUiController;
import com.android.launcher3.views.BaseDragLayer;
import com.android.quickstep.fallback.FallbackRecentsStateController;
import com.android.quickstep.fallback.FallbackRecentsView;
import com.android.quickstep.fallback.RecentsDragLayer;
import com.android.quickstep.fallback.RecentsState;
import com.android.quickstep.util.RecentsAtomicAnimationFactory;
import com.android.quickstep.util.SplitSelectStateController;
import com.android.quickstep.views.OverviewActionsView;
import com.android.quickstep.views.SplitPlaceholderView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * A recents activity that shows the recently launched tasks as swipable task cards.
 * See {@link com.android.quickstep.views.RecentsView}.
 */
public final class RecentsActivity extends StatefulActivity<RecentsState> {

    public static final ActivityTracker<RecentsActivity> ACTIVITY_TRACKER =
            new ActivityTracker<>();

    private Handler mUiHandler = new Handler(Looper.getMainLooper());

    private RecentsDragLayer mDragLayer;
    private FallbackRecentsView mFallbackRecentsView;
    private OverviewActionsView mActionsView;

    private Configuration mOldConfig;

    private StateManager<RecentsState> mStateManager;

    // Strong refs to runners which are cleared when the activity is destroyed
    private WrappedAnimationRunnerImpl mActivityLaunchAnimationRunner;

    /**
     * Init drag layer and overview panel views.
     */
    protected void setupViews() {
        inflateRootView(R.layout.fallback_recents_activity);
        setContentView(getRootView());
        mDragLayer = findViewById(R.id.drag_layer);
        mFallbackRecentsView = findViewById(R.id.overview_panel);
        mActionsView = findViewById(R.id.overview_actions_view);

        SplitPlaceholderView splitPlaceholderView = findViewById(R.id.split_placeholder);
        splitPlaceholderView.init(
                new SplitSelectStateController(
                        SystemUiProxy.INSTANCE.get(this))
        );

        mDragLayer.recreateControllers();
        mFallbackRecentsView.init(mActionsView, splitPlaceholderView);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        onHandleConfigChanged();
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ACTIVITY_TRACKER.handleNewIntent(this, intent);
    }

    /**
         * Logic for when device configuration changes (rotation, screen size change, multi-window,
         * etc.)
         */
    protected void onHandleConfigChanged() {
        initDeviceProfile();

        AbstractFloatingView.closeOpenViews(this, true,
                AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
        dispatchDeviceProfileChanged();

        reapplyUi();
        mDragLayer.recreateControllers();
    }

    /**
     * Generate the device profile to use in this activity.
     * @return device profile
     */
    protected DeviceProfile createDeviceProfile() {
        DeviceProfile dp = InvariantDeviceProfile.INSTANCE.get(this).getDeviceProfile(this);

        // In case we are reusing IDP, create a copy so that we don't conflict with Launcher
        // activity.
        return (mDragLayer != null) && isInMultiWindowMode()
                ? dp.getMultiWindowProfile(this, getMultiWindowDisplaySize())
                : dp.copy(this);
    }

    @Override
    public BaseDragLayer getDragLayer() {
        return mDragLayer;
    }

    @Override
    public <T extends View> T getOverviewPanel() {
        return (T) mFallbackRecentsView;
    }

    public OverviewActionsView getActionsView() {
        return mActionsView;
    }

    @Override
    public void returnToHomescreen() {
        super.returnToHomescreen();
        // TODO(b/137318995) This should go home, but doing so removes freeform windows
    }

    @Override
    public ActivityOptionsWrapper getActivityLaunchOptions(final View v) {
        if (!(v instanceof TaskView)) {
            return super.getActivityLaunchOptions(v);
        }

        final TaskView taskView = (TaskView) v;
        RunnableList onEndCallback = new RunnableList();

        mActivityLaunchAnimationRunner = (int transit,
                    RemoteAnimationTargetCompat[] appTargets,
                    RemoteAnimationTargetCompat[] wallpaperTargets,
                    RemoteAnimationTargetCompat[] nonAppTargets,
                    AnimationResult result) -> {
            AnimatorSet anim = composeRecentsLaunchAnimator(taskView, appTargets,
                    wallpaperTargets);
            anim.addListener(resetStateListener());
            result.setAnimation(anim, RecentsActivity.this, onEndCallback::executeAllAndDestroy);
        };

        final LauncherAnimationRunner wrapper = new WrappedLauncherAnimationRunner<>(
                mUiHandler, mActivityLaunchAnimationRunner, true /* startAtFrontOfQueue */);
        RemoteAnimationAdapterCompat adapterCompat = new RemoteAnimationAdapterCompat(
                wrapper, RECENTS_LAUNCH_DURATION,
                RECENTS_LAUNCH_DURATION - STATUS_BAR_TRANSITION_DURATION
                        - STATUS_BAR_TRANSITION_PRE_DELAY);
        return new ActivityOptionsWrapper(
                ActivityOptionsCompat.makeRemoteAnimation(adapterCompat),
                onEndCallback);
    }

    /**
     * Composes the animations for a launch from the recents list if possible.
     */
    private AnimatorSet  composeRecentsLaunchAnimator(TaskView taskView,
            RemoteAnimationTargetCompat[] appTargets,
            RemoteAnimationTargetCompat[] wallpaperTargets) {
        AnimatorSet target = new AnimatorSet();
        boolean activityClosing = taskIsATargetWithMode(appTargets, getTaskId(), MODE_CLOSING);
        PendingAnimation pa = new PendingAnimation(RECENTS_LAUNCH_DURATION);
        createRecentsWindowAnimator(taskView, !activityClosing, appTargets,
                wallpaperTargets, null /* depthController */, pa);
        target.play(pa.buildAnim());

        // Found a visible recents task that matches the opening app, lets launch the app from there
        if (activityClosing) {
            Animator adjacentAnimation = mFallbackRecentsView
                    .createAdjacentPageAnimForTaskLaunch(taskView);
            adjacentAnimation.setInterpolator(Interpolators.TOUCH_RESPONSE_INTERPOLATOR);
            adjacentAnimation.setDuration(RECENTS_LAUNCH_DURATION);
            adjacentAnimation.addListener(resetStateListener());
            target.play(adjacentAnimation);
        }
        return target;
    }

    @Override
    protected void onStart() {
        // Set the alpha to 1 before calling super, as it may get set back to 0 due to
        // onActivityStart callback.
        mFallbackRecentsView.setContentAlpha(1);
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Workaround for b/78520668, explicitly trim memory once UI is hidden
        onTrimMemory(TRIM_MEMORY_UI_HIDDEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AccessibilityManagerCompat.sendStateEventToTest(getBaseContext(), OVERVIEW_STATE_ORDINAL);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mStateManager = new StateManager<>(this, RecentsState.DEFAULT);

        mOldConfig = new Configuration(getResources().getConfiguration());
        initDeviceProfile();
        setupViews();

        getSystemUiController().updateUiState(SystemUiController.UI_STATE_BASE_WINDOW,
                mFallbackRecentsView.hasLightBackground());
        ACTIVITY_TRACKER.handleCreate(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int diff = newConfig.diff(mOldConfig);
        if ((diff & (CONFIG_ORIENTATION | CONFIG_SCREEN_SIZE)) != 0) {
            onHandleConfigChanged();
        }
        mOldConfig.setTo(newConfig);
        super.onConfigurationChanged(newConfig);
    }

    /**
     * Initialize/update the device profile.
     */
    private void initDeviceProfile() {
        mDeviceProfile = createDeviceProfile();
        onDeviceProfileInitiated();
    }

    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        // After the transition to home, enable the high-res thumbnail loader if it wasn't enabled
        // as a part of quickstep, so that high-res thumbnails can load the next time we enter
        // overview
        RecentsModel.INSTANCE.get(this).getThumbnailCache()
                .getHighResLoadingState().setVisible(true);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        RecentsModel.INSTANCE.get(this).onTrimMemory(level);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ACTIVITY_TRACKER.onActivityDestroyed(this);
        mActivityLaunchAnimationRunner = null;
    }

    @Override
    public void onBackPressed() {
        // TODO: Launch the task we came from
        startHome();
    }

    public void startHome() {
        startActivity(createHomeIntent());
    }

    @Override
    protected void collectStateHandlers(List<StateHandler> out) {
        out.add(new FallbackRecentsStateController(this));
    }

    @Override
    public StateManager<RecentsState> getStateManager() {
        return mStateManager;
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        writer.println(prefix + "Misc:");
        dumpMisc(prefix + "\t", writer);
    }

    @Override
    public AtomicAnimationFactory<RecentsState> createAtomicAnimationFactory() {
        return new RecentsAtomicAnimationFactory<>(this);
    }

    private AnimatorListenerAdapter resetStateListener() {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mFallbackRecentsView.resetTaskVisuals();
                mStateManager.reapplyState();
            }
        };
    }
}
