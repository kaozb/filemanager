package com.lumen.files;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

/**
 * 首页双面板翻页容器：滑动全程跟手，松手按位移与速度吸附，
 * 减速曲线回弹到目标页，替代原先生硬的 ViewFlipper 切换。
 * 双指（捏合缩放行高）出现时立即取消翻页手势。
 */
public final class PanePager extends ViewGroup {

    public interface Callback {
        /** 页面开始吸附到目标页时回调。 */
        void onPageSettled(int page);

        /** 拖动进度（0=第一页，1=第二页），用于指示点联动。 */
        void onDragProgress(float fraction);
    }

    private static final int PAGE_COUNT = 2;

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;

    private VelocityTracker tracker;
    private ValueAnimator settle;
    private int activePointer = -1;
    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private boolean locked;
    private int page = 0;
    private Callback callback;

    public PanePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        minFlingVelocity = config.getScaledMinimumFlingVelocity() * 16;
        maxFlingVelocity = config.getScaledMaximumFlingVelocity();
    }

    public void setCallback(Callback value) {
        callback = value;
    }

    public int currentPage() {
        return page;
    }

    /** 切换页面；animate=false 用于初始化时静默定位。 */
    public void setPage(int target, boolean animate) {
        target = Math.max(0, Math.min(PAGE_COUNT - 1, target));
        if (!animate) {
            cancelSettle();
            page = target;
            scrollTo(target * getWidth(), 0);
            notifyProgress();
            return;
        }
        if (target == page && !isSettling()) {
            return;
        }
        settleTo(target);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(i * width, 0, (i + 1) * width, bottom - top);
        }
        if (changed) {
            scrollTo(page * width, 0);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = event.getPointerId(0);
                downX = lastX = event.getX();
                downY = event.getY();
                dragging = false;
                locked = false;
                obtainTracker(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指手势（捏合缩放）不触发翻页，若已在拖动则回弹到最近页面
                locked = true;
                dragging = false;
                releaseTracker();
                settleTo(nearestPage());
                break;
            case MotionEvent.ACTION_MOVE: {
                if (locked) {
                    break;
                }
                int index = event.findPointerIndex(activePointer);
                if (index < 0) {
                    break;
                }
                float x = event.getX(index);
                float y = event.getY(index);
                float dx = x - downX;
                float dy = y - downY;
                if (!dragging && Math.abs(dx) > touchSlop
                        && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    dragging = true;
                    lastX = x;
                    cancelSettle();
                    obtainTracker(event);
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                releaseTracker();
                activePointer = -1;
                dragging = false;
                locked = false;
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tracker != null) {
            tracker.addMovement(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                locked = true;
                dragging = false;
                releaseTracker();
                settleTo(nearestPage());
                break;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging || locked) {
                    break;
                }
                int index = event.findPointerIndex(activePointer);
                if (index < 0) {
                    break;
                }
                float x = event.getX(index);
                float dx = x - lastX;
                lastX = x;
                int width = getWidth();
                if (width <= 0) {
                    break;
                }
                float next = Math.max(0f, Math.min(width, getScrollX() - dx));
                scrollTo(Math.round(next), 0);
                notifyProgress();
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (!dragging) {
                    break;
                }
                dragging = false;
                int width = getWidth();
                int target;
                if (tracker != null && width > 0) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float vx = tracker.getXVelocity(activePointer);
                    float position = getScrollX() / (float) width;
                    if (vx < -minFlingVelocity) {
                        target = Math.min(PAGE_COUNT - 1, (int) Math.floor(position) + 1);
                    } else if (vx > minFlingVelocity) {
                        target = Math.max(0, (int) Math.ceil(position) - 1);
                    } else {
                        target = Math.round(position);
                    }
                } else {
                    target = nearestPage();
                }
                releaseTracker();
                settleTo(target);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                releaseTracker();
                settleTo(nearestPage());
                break;
            default:
                break;
        }
        return true;
    }

    private void settleTo(int target) {
        target = Math.max(0, Math.min(PAGE_COUNT - 1, target));
        int width = getWidth();
        int destination = target * width;
        boolean changed = target != page;
        page = target;
        if (width <= 0 || getScrollX() == destination) {
            cancelSettle();
            notifyProgress();
            if (changed && callback != null) {
                callback.onPageSettled(target);
            }
            return;
        }
        if (settle != null) {
            settle.cancel();
        }
        settle = ValueAnimator.ofInt(getScrollX(), destination);
        float distance = Math.abs(destination - getScrollX()) / (float) width;
        settle.setDuration(Math.max(140, Math.min(300, (long) (160 + 170 * distance))));
        settle.setInterpolator(new DecelerateInterpolator(1.8f));
        settle.addUpdateListener(animation -> {
            scrollTo((Integer) animation.getAnimatedValue(), 0);
            notifyProgress();
        });
        settle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                notifyProgress();
            }
        });
        settle.start();
        if (changed && callback != null) {
            callback.onPageSettled(target);
        }
    }

    private int nearestPage() {
        int width = getWidth();
        if (width <= 0) {
            return page;
        }
        return Math.round(getScrollX() / (float) width);
    }

    private boolean isSettling() {
        return settle != null && settle.isRunning();
    }

    private void cancelSettle() {
        if (settle != null) {
            settle.cancel();
            settle = null;
        }
    }

    private void obtainTracker(MotionEvent event) {
        if (tracker == null) {
            tracker = VelocityTracker.obtain();
        }
        tracker.addMovement(event);
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private void notifyProgress() {
        int width = getWidth();
        if (callback != null && width > 0) {
            callback.onDragProgress(getScrollX() / (float) width);
        }
    }
}
