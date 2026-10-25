package com.lumen.files;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * 下拉刷新容器：第一个子视图为可滚动内容（ListView）。
 * 列表滚动到顶部继续下拉时内容跟手下移并露出指示器，松手超过阈值直接触发刷新。
 */
public final class PullRefreshLayout extends FrameLayout {

    /** 刷新回调，完成后由调用方执行 setRefreshing(false)。 */
    public interface OnRefreshListener {
        void onRefresh();
    }

    private static final float DRAG_RATE = 0.55f;

    private final int touchSlop;
    private final int triggerDistance;
    private final int maxDragDistance;
    private final int settleDistance;
    private final ProgressBar indicator;

    private View target;
    private OnRefreshListener listener;
    private boolean pulling;
    private boolean refreshing;
    private float downX;
    private float prevY;
    private float pull;
    private ValueAnimator settle;

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        triggerDistance = dp(64);
        maxDragDistance = dp(130);
        settleDistance = dp(56);
        indicator = new ProgressBar(context);
        indicator.setIndeterminate(true);
        indicator.getIndeterminateDrawable().mutate();
        addView(indicator, new LayoutParams(dp(26), dp(26),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 0) {
            target = getChildAt(0);
        }
        // 指示器置顶绘制，避免被列表遮挡
        indicator.bringToFront();
        indicator.setAlpha(0f);
        indicator.setVisibility(INVISIBLE);
    }

    public void setOnRefreshListener(OnRefreshListener onRefreshListener) {
        listener = onRefreshListener;
    }

    /** 刷新结束：内容回弹归位。 */
    public void setRefreshing(boolean value) {
        if (value && refreshing) {
            return;
        }
        if (value) {
            refreshing = true;
            indicator.setVisibility(VISIBLE);
            animateTo(settleDistance);
            return;
        }
        if (!refreshing && pull == 0f) {
            return;
        }
        animateTo(0f);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                prevY = event.getY();
                pulling = false;
                if (settle != null && settle.isRunning()) {
                    settle.cancel();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!refreshing && !pulling && target != null
                        && !target.canScrollVertically(-1)) {
                    float dy = event.getY() - prevY;
                    float dx = event.getX() - downX;
                    if (dy > touchSlop && dy > Math.abs(dx) * 1.2f) {
                        pulling = true;
                        prevY = event.getY();
                        return true;
                    }
                }
                prevY = event.getY();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                prevY = event.getY();
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!pulling) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float delta = (event.getY() - prevY) * DRAG_RATE;
                prevY = event.getY();
                float next = Math.max(0f, Math.min(maxDragDistance, pull + delta));
                applyPull(next);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                if (refreshing) {
                    return true;
                }
                if (pull >= triggerDistance) {
                    refreshing = true;
                    indicator.setVisibility(VISIBLE);
                    animateTo(settleDistance);
                    if (listener != null) {
                        listener.onRefresh();
                    }
                } else {
                    animateTo(0f);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void applyPull(float value) {
        pull = value;
        if (target != null) {
            target.setTranslationY(value);
        }
        float progress = Math.min(1f, value / triggerDistance);
        indicator.setAlpha(progress);
        indicator.setTranslationY((value - indicator.getLayoutParams().height) / 2f);
        indicator.setVisibility(value > 0f ? VISIBLE : INVISIBLE);
    }

    private void animateTo(float destination) {
        if (settle != null) {
            settle.cancel();
        }
        settle = ValueAnimator.ofFloat(pull, destination);
        settle.setDuration(220);
        settle.setInterpolator(new DecelerateInterpolator(1.6f));
        settle.addUpdateListener(animation -> applyPull((Float) animation.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (destination == 0f) {
                    refreshing = false;
                    indicator.setVisibility(INVISIBLE);
                    indicator.setAlpha(0f);
                }
            }
        });
        settle.start();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
