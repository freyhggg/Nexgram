package org.telegram.nexgram;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import org.telegram.messenger.AndroidUtilities;

/**
 * NEXGRAM animation kit — motion effects that the original Telegram app
 * does not have. All effects are opt-out (KEY_ANIM_EXPERIMENTAL) and are
 * disabled together with "reduce motion". Nothing here touches Telegram's
 * own animations.
 */
public final class NexgramAnim {

    private static final DecelerateInterpolator DECELERATE = new DecelerateInterpolator(1.4f);
    private static final OvershootInterpolator OVERSHOOT = new OvershootInterpolator(1.6f);

    private NexgramAnim() {
    }

    /**
     * Staggered cascade entrance for the children of a container:
     * cards rise 26dp and fade in one after another.
     */
    public static void cascade(ViewGroup container) {
        if (container == null) {
            return;
        }
        Context context = container.getContext();
        if (!NexgramPrefs.animExperiments(context)) {
            return;
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(AndroidUtilities.dp(26));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setStartDelay(Math.min(i * 36L, 900L))
                    .setInterpolator(DECELERATE)
                    .start();
        }
    }

    /**
     * Slow "breathing" background: the view's color oscillates between
     * base and a slightly lighter shade. Returns the animator so the
     * fragment can cancel it in onFragmentDestroy().
     */
    public static ValueAnimator breathingBackground(View view, int baseColor, int liftedColor) {
        if (view == null || view.getContext() == null) {
            return null;
        }
        if (!NexgramPrefs.animExperiments(view.getContext())) {
            return null;
        }
        view.setBackgroundColor(baseColor);
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), baseColor, liftedColor);
        animator.setDuration(5200);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.addUpdateListener(a -> {
            if (view.getParent() != null) {
                view.setBackgroundColor((Integer) a.getAnimatedValue());
            }
        });
        animator.start();
        return animator;
    }

    /**
     * Press feedback with springy release (scale 0.96 → 1.0 with overshoot).
     * Applied on top of an existing click listener; the touch is passed on
     * (returns false), so normal click handling stays intact.
     */
    public static void pressScale(View view) {
        if (view == null || view.getContext() == null) {
            return;
        }
        if (!NexgramPrefs.animExperiments(view.getContext())) {
            return;
        }
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).setInterpolator(DECELERATE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(230).setInterpolator(OVERSHOOT)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    v.setScaleX(1f);
                                    v.setScaleY(1f);
                                }
                            }).start();
                    break;
            }
            return false;
        });
    }

    /**
     * One-shot "pop" for elements that change state (chips, badges).
     */
    public static void pop(View view) {
        if (view == null || view.getContext() == null || !NexgramPrefs.animExperiments(view.getContext())) {
            return;
        }
        view.animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).setInterpolator(DECELERATE)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(180)
                        .setInterpolator(OVERSHOOT).start()).start();
    }
}
