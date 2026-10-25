package com.lumen.files;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

/**
 * 首页双面板横滑容器：手指拖动实时跟手，松手按速度与位移平滑吸附到目标面板，
 * 动画进行中可随时按住打断，边缘带橡皮筋阻尼。
 * 双指按下立即让位给列表的捏合缩放手势。
 */
public final class PanePager extends ViewGroup {

    /** 页面吸附完成后回调（宿主据此刷新指示点与顶栏）。 */
    public interface Callback {
        void onPageSelected(int page);
    }

    private static final int SNAP_DURATION_MS = 300;
    private static final int FLING_VELOCITY_DP = 1200;

    private final OverScroller scroller;
    private final int touchSlop;
    private final int flingVelocity;
    private VelocityTracker tracker;

    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging;
    private int page;
    private Callback callback;

    public PanePager(Context context) {
        this(context, null);
    }

    public PanePager(Context context, AttributeSet attrs) {
        super(context);
        ViewConfiguration config = ViewConfiguration.get(context);
        touchSlop = config.getScaledTouchSlop();
        flingVelocity = (int) (FLING_VELOCITY_DP
                * context.getResources().getDisplayMetrics().density);
        scroller = new OverScroller(context);
    }

    public void setCallback(Callback value) {
        callback = value;
    }

    int page() {
        return page;
    }

    /** 切换页面：smooth=true 平滑滚动，false 直接定位（恢复状态用）。 */
    void setPage(int target, boolean smooth) {
        page = Math.max(0, Math.min(Math.max(0, getChildCount() - 1), target));
        if (getWidth() > 0) {
            if (smooth) {
                scroller.startScroll(getScrollX(), 0,
                        page * getWidth() - getScrollX(), 0, SNAP_DURATION_MS);
                postInvalidateOnAnimation();
            } else {
                scrollTo(page * getWidth(), 0);
            }
        }
        if (callback != null) {
            callback.onPageSelected(page);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(i * width, 0, (i + 1) * width, bottom - top);
        }
        scrollTo(page * width, 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 双指（捏合缩放行高）不触发翻页
                dragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    lastX = event.getX();
                    if (tracker == null) {
                        tracker = VelocityTracker.obtain();
                    }
                    tracker.addMovement(event);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tracker == null) {
            tracker = VelocityTracker.obtain();
        }
        tracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                lastX = event.getX();
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) {
                    // 拖动中落下第二根手指：取消翻页，回弹到当前页
                    dragging = false;
                    setPage(page, true);
                    break;
                }
                if (!dragging) {
                    float startX = event.getX() - downX;
                    float startY = event.getY() - downY;
                    if (Math.abs(startX) > touchSlop && Math.abs(startX) > Math.abs(startY)) {
                        dragging = true;
                    }
                }
                if (dragging) {
                    float delta = lastX - event.getX();
                    lastX = event.getX();
                    int maxX = Math.max(0, (getChildCount() - 1) * getWidth());
                    int next = (int) (getScrollX() + delta);
                    if (next < 0) {
                        next = next / 3;
                    } else if (next > maxX) {
                        next = maxX + (next - maxX) / 3;
                    }
                    scrollTo(next, 0);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    tracker.computeCurrentVelocity(1000);
                    float velocity = tracker.getXVelocity();
                    int target;
                    if (velocity < -flingVelocity) {
                        target = page + 1;
                    } else if (velocity > flingVelocity) {
                        target = page - 1;
                    } else {
                        target = (getScrollX() + getWidth() / 2) / getWidth();
                    }
                    setPage(target, true);
                }
                dragging = false;
                recycleTracker();
                break;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                setPage(page, true);
                recycleTracker();
                break;
            default:
                break;
        }
        return true;
    }

    private void recycleTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidateOnAnimation();
        }
    }
}
