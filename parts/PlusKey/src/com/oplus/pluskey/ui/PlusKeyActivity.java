package com.oplus.pluskey.ui;

import android.animation.ArgbEvaluator;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.SmoothScroller;

import com.google.android.material.appbar.MaterialToolbar;
import com.oplus.pluskey.Constants;
import com.oplus.pluskey.R;
import com.oplus.pluskey.Settings;
import com.oplus.pluskey.actions.Haptics;
import com.oplus.pluskey.service.AssistantRoleClearer;

/**
 * Plus Key settings — the slick page from pluskey.webp.
 *
 * <p>Interaction model:
 * <ol>
 *   <li>The chip row at the bottom snaps the touched / scrolled chip into
 *       the screen-centre slot (LinearSnapHelper). Whichever chip lands in
 *       the centre is the "focused" action.</li>
 *   <li>The big pill above shows that focused action's icon + label +
 *       description, with a zoom-in entry whenever the focus changes.</li>
 *   <li>The bottom CTA pill says <b>"Set"</b> while the focused chip differs
 *       from what's persisted; tapping it writes the new selection. Once
 *       saved, the CTA flips to <b>"In use"</b> and goes inert.</li>
 * </ol>
 */
public class PlusKeyActivity extends Activity {

    private GlowingPillView mHalo;
    private ImageView mActionIcon;
    private TextView mActionLabel, mActionDesc;
    private View mPillContent;     // wrapper around icon+label+desc
    private View mOpenAppPicker;
    private ImageView mOpenAppPickerIcon;
    private TextView mOpenAppPickerLabel;
    private View mGestureSelector;
    private TextView mGestureSelectorLabel;
    private TextView mCtaButton;
    private RecyclerView mChipRow;
    private ActionChipAdapter mAdapter;

    private boolean mEditingLongPress = false;
    private int mSavedActionId;       // the user's persisted choice (-1 = unset)
    private int mFocusedActionId;     // whichever chip is currently centred
    private PopupWindow mOpenPopup;

    @Override
    protected void onResume() {
        super.onResume();
        // If the user just came back from AppPickerActivity having chosen
        // a different target package, re-render the description so it
        // reflects the new app's name without requiring a chip re-scroll.
        if (mFocusedActionId == Constants.ACTION_OPEN_APP
                || mFocusedActionId == Constants.ACTION_CAMERA) {
            renderFocus(mFocusedActionId, /*animate=*/false);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pluskey);

        AssistantRoleClearer.clearOnce(this);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        ImageButton settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setBackgroundResource(android.R.color.transparent);
        settingsButton.setColorFilter(getColor(android.R.color.transparent));
        settingsButton.clearColorFilter();
        settingsButton.setContentDescription(getString(R.string.pluskey_settings));
        settingsButton.setPadding(
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density),
                (int) (16 * getResources().getDisplayMetrics().density));
        tb.addView(settingsButton, new MaterialToolbar.LayoutParams(
                (int) (56 * getResources().getDisplayMetrics().density),
                (int) (56 * getResources().getDisplayMetrics().density),
                Gravity.END | Gravity.CENTER_VERTICAL));
        settingsButton.setOnClickListener(v -> showSettingsMenu(settingsButton));

        mHalo = findViewById(R.id.pill_halo);
        mGestureSelector = findViewById(R.id.gesture_selector);
        mGestureSelectorLabel = findViewById(R.id.gesture_selector_label);
        mGestureSelector.setOnClickListener(v -> showGestureMenu());
        mActionIcon = findViewById(R.id.action_icon);
        mActionLabel = findViewById(R.id.action_label);
        mActionDesc = findViewById(R.id.action_desc);
        mPillContent = findViewById(R.id.pill_content);
        mOpenAppPicker = findViewById(R.id.open_app_picker);
        mOpenAppPickerIcon = findViewById(R.id.open_app_icon);
        mOpenAppPickerLabel = findViewById(R.id.open_app_label);
        mOpenAppPicker.setOnClickListener(v -> {
            android.content.Intent intent =
                    new android.content.Intent(this, AppPickerActivity.class);
            if (mFocusedActionId == Constants.ACTION_CAMERA) {
                intent.putExtra(AppPickerActivity.EXTRA_MODE, AppPickerActivity.MODE_CAMERA_APP);
            }
            startActivity(intent);
        });
        mCtaButton = findViewById(R.id.cta_button);
        mChipRow = findViewById(R.id.action_chips);

        mSavedActionId = Settings.getAction(this, mEditingLongPress);
        // If the user has never picked anything, initialise the focused
        // chip to the default but DON'T persist it — they'll have to tap Set.
        int initialFocus = mSavedActionId == Constants.ACTION_UNSET
                ? Constants.DEFAULT_DISPLAY_ACTION : mSavedActionId;

        setupChipRow(initialFocus);
        updateGestureSelector();
        renderFocus(initialFocus, /*animate=*/false);
        animateEntry();
    }

    // ---------------------------------------------------------------- chips

    private void setupChipRow(int initialFocusId) {
        mAdapter = new ActionChipAdapter(this, initialFocusId, this::scrollToCenter);

        // Side padding equal to half the screen width minus half a chip, so
        // first/last chips can actually reach the centre snap slot.
        int chipPx = (int) (56 * getResources().getDisplayMetrics().density);
        int sidePad = (getResources().getDisplayMetrics().widthPixels - chipPx) / 2;
        mChipRow.setPadding(sidePad, mChipRow.getPaddingTop(),
                sidePad, mChipRow.getPaddingBottom());
        mChipRow.setClipToPadding(false);

        LinearLayoutManager lm = new LinearLayoutManager(
                this, RecyclerView.HORIZONTAL, false);
        mChipRow.setLayoutManager(lm);
        mChipRow.setAdapter(mAdapter);
        mChipRow.setItemAnimator(null);   // no flicker on rebind

        LinearSnapHelper snap = new LinearSnapHelper();
        snap.attachToRecyclerView(mChipRow);

        // Two-track scroll listener:
        //   • onScrolled — fires constantly during a fling. Track the chip
        //     currently nearest the centre and tick a tiny haptic each time
        //     that "nearest" position changes — gives the premium detent
        //     feel (like turning a notched dial).
        //   • onScrollStateChanged → IDLE — settled. Commit the focus
        //     change (zoom-in title/desc + halo accent crossfade), so the
        //     pill content only updates when the user has actually stopped
        //     on something instead of flickering through every chip.
        final int[] lastDetentPos = { mAdapter.getCenteredPosition() };
        mChipRow.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                View centerView = snap.findSnapView(lm);
                if (centerView == null) return;
                int pos = lm.getPosition(centerView);
                if (pos != lastDetentPos[0]) {
                    lastDetentPos[0] = pos;
                    mAdapter.setCenteredPosition(pos);
                    Haptics.scrollDetent(PlusKeyActivity.this);
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                View centerView = snap.findSnapView(lm);
                if (centerView == null) return;
                int pos = lm.getPosition(centerView);
                int focusId = ActionRegistry.get(pos).id;
                if (focusId != mFocusedActionId) {
                    renderFocus(focusId, /*animate=*/true);
                }
            }
        });

        // Centre the initial chip without animation. Two-stage post:
        //   1) wait for the RecyclerView to lay itself out (post #1) so its
        //      width and child views exist;
        //   2) wait one more frame (post #2) so findViewByPosition returns
        //      the laid-out chip view; then scrollBy the exact pixel delta
        //      that places that chip's centre at the RecyclerView's centre.
        // scrollToPositionWithOffset alone doesn't account for our horizontal
        // padding correctly when clipToPadding=false, hence the manual maths.
        final int pos = ActionRegistry.indexOf(initialFocusId);
        mChipRow.post(() -> {
            lm.scrollToPosition(pos);
            mChipRow.post(() -> {
                View v = lm.findViewByPosition(pos);
                if (v == null) return;
                int chipCentre = v.getLeft() + v.getWidth() / 2;
                int rvCentre = mChipRow.getWidth() / 2;
                mChipRow.scrollBy(chipCentre - rvCentre, 0);
                mAdapter.setCenteredPosition(pos);
            });
        });
    }

    private void scrollToCenter(int pos) {
        SmoothScroller s = new LinearSmoothScroller(this) {
            @Override protected int getHorizontalSnapPreference() { return SNAP_TO_ANY; }
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
                return 60f / dm.densityDpi;   // smoother / slower than default
            }
        };
        s.setTargetPosition(pos);
        if (mChipRow.getLayoutManager() != null) {
            mChipRow.getLayoutManager().startSmoothScroll(s);
        }
    }

    private void switchGesture(boolean longPress) {
        if (mEditingLongPress == longPress) return;
        mEditingLongPress = longPress;
        mSavedActionId = Settings.getAction(this, mEditingLongPress);
        int actionId = mSavedActionId == Constants.ACTION_UNSET
                ? Constants.DEFAULT_DISPLAY_ACTION : mSavedActionId;
        updateGestureSelector();
        renderFocus(actionId, /*animate=*/true);
        int pos = ActionRegistry.indexOf(actionId);
        mAdapter.setCenteredPosition(pos);
        scrollToCenter(pos);
        Haptics.scrollDetent(this);
    }

    private void updateGestureSelector() {
        mGestureSelectorLabel.setText(getString(mEditingLongPress
                ? R.string.long_press : R.string.short_press));
    }

    private void showGestureMenu() {
        MenuRow[] rows = {
                MenuRow.choice(getString(R.string.short_press), !mEditingLongPress,
                        () -> switchGesture(false)),
                MenuRow.choice(getString(R.string.long_press), mEditingLongPress,
                        () -> switchGesture(true))
        };
        showAnchoredMenu(mGestureSelector, rows, dp(220), AnchorMode.CENTER_UNDER);
    }

    private void showSettingsMenu(View anchor) {
        boolean shortScreenOnOnly = Settings.isShortPressScreenOnOnly(this);
        boolean cameraTrigger = Settings.isCameraTriggerEnabled(this);
        final MenuRow appsRow = MenuRow.navigation(getString(R.string.setting_camera_trigger_apps),
                cameraTrigger,
                () -> startActivity(new android.content.Intent(this, AppPickerActivity.class)
                        .putExtra(AppPickerActivity.EXTRA_MODE,
                                AppPickerActivity.MODE_CAMERA_TRIGGER)));
        MenuRow[] rows = {
                MenuRow.toggle(getString(R.string.setting_short_screen_on_only), shortScreenOnOnly,
                        checked -> Settings.setShortPressScreenOnOnly(this, checked)),
                MenuRow.toggle(getString(R.string.setting_camera_trigger), cameraTrigger,
                        checked -> {
                            Settings.setCameraTriggerEnabled(this, checked);
                            appsRow.setEnabled(checked);
                        }),
                appsRow
        };
        showAnchoredMenu(anchor, rows, dp(320), AnchorMode.TOP_CENTER);
    }

    private void showAnchoredMenu(View anchor, MenuRow[] rows, int width, AnchorMode mode) {
        if (mOpenPopup != null) {
            mOpenPopup.dismiss();
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        content.setBackground(menuBackground());

        for (MenuRow row : rows) {
            content.addView(menuRowView(row), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));
        }

        PopupWindow popup = new PopupWindow(content, width,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        popup.setOnDismissListener(() -> {
            if (mOpenPopup == popup) {
                mOpenPopup = null;
            }
        });
        mOpenPopup = popup;

        if (mode == AnchorMode.TOP_CENTER) {
            popup.showAtLocation(getWindow().getDecorView(),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(132));
        } else {
            int xoff = mode == AnchorMode.ALIGN_END
                    ? anchor.getWidth() - width
                    : (anchor.getWidth() - width) / 2;
            popup.showAsDropDown(anchor, xoff, dp(8), Gravity.NO_GRAVITY);
        }
    }

    private View menuRowView(MenuRow row) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), 0, dp(12), 0);
        item.setBackground(rowBackground(row.kind == MenuRow.Kind.CHOICE && row.checked));
        item.setClickable(true);
        item.setFocusable(true);

        TextView label = new TextView(this);
        label.setText(row.label);
        label.setTextColor(row.kind == MenuRow.Kind.CHOICE && row.checked
                ? themeColor(android.R.attr.colorAccent)
                : themeColor(com.google.android.material.R.attr.colorOnSurface));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(label, row.kind == MenuRow.Kind.NAVIGATION
                ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT)
                : new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        if (row.kind == MenuRow.Kind.TOGGLE) {
            Switch toggle = new Switch(this);
            toggle.setShowText(false);
            toggle.setChecked(row.checked);
            toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                row.checked = isChecked;
                item.setBackground(rowBackground(false));
                label.setTextColor(isChecked ? themeColor(android.R.attr.colorAccent)
                        : themeColor(com.google.android.material.R.attr.colorOnSurface));
                row.toggleAction.run(isChecked);
            });
            item.addView(toggle, new LinearLayout.LayoutParams(dp(52),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            item.setOnClickListener(v -> toggle.toggle());
            return item;
        }

        if (row.kind == MenuRow.Kind.NAVIGATION) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_dropdown_chevron);
            arrow.setImageTintList(ColorStateList.valueOf(
                    themeColor(com.google.android.material.R.attr.colorOnSurface)));
            arrow.setAlpha(0.7f);
            LinearLayout.LayoutParams arrowLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            arrowLp.setMarginStart(dp(6));
            item.addView(arrow, arrowLp);
            item.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            row.bind(item, label, arrow);
        } else {
            View indicator = radioIndicator(row.checked);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
            lp.setMarginStart(dp(6));
            item.addView(indicator, lp);
        }

        item.setOnClickListener(v -> {
            if (!row.enabled) {
                return;
            }
            if (mOpenPopup != null) {
                mOpenPopup.dismiss();
            }
            row.action.run();
        });
        if (row.kind == MenuRow.Kind.NAVIGATION) {
            row.setEnabled(row.enabled);
        }
        return item;
    }

    private GradientDrawable menuBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), withAlpha(themeColor(com.google.android.material.R.attr.colorOutline),
                0.35f));
        return bg;
    }

    private GradientDrawable rowBackground(boolean checked) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(checked ? withAlpha(themeColor(android.R.attr.colorAccent),
                0.14f) : Color.TRANSPARENT);
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private View radioIndicator(boolean checked) {
        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(2), checked ? themeColor(android.R.attr.colorAccent)
                : withAlpha(themeColor(com.google.android.material.R.attr.colorOnSurface), 0.55f));
        outer.setBackground(ring);

        if (checked) {
            View dot = new View(this);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(themeColor(android.R.attr.colorAccent));
            dot.setBackground(fill);
            android.widget.FrameLayout.LayoutParams lp =
                    new android.widget.FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER);
            outer.addView(dot, lp);
        }
        return outer;
    }

    private int themeColor(int attr) {
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(attr, out, true);
        return out.resourceId != 0 ? getColor(out.resourceId) : out.data;
    }

    private int withAlpha(int color, float alpha) {
        return Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum AnchorMode {
        CENTER_UNDER,
        ALIGN_END,
        TOP_CENTER
    }

    private interface ToggleAction {
        void run(boolean checked);
    }

    private static final class MenuRow {
        enum Kind {
            CHOICE,
            TOGGLE,
            NAVIGATION
        }

        final String label;
        boolean checked;
        boolean enabled;
        final Runnable action;
        final ToggleAction toggleAction;
        final Kind kind;
        View itemView;
        TextView labelView;
        ImageView arrowView;

        private MenuRow(String label, boolean checked, boolean enabled, Kind kind,
                Runnable action, ToggleAction toggleAction) {
            this.label = label;
            this.checked = checked;
            this.enabled = enabled;
            this.kind = kind;
            this.action = action;
            this.toggleAction = toggleAction;
        }

        static MenuRow choice(String label, boolean checked, Runnable action) {
            return new MenuRow(label, checked, true, Kind.CHOICE, action, null);
        }

        static MenuRow toggle(String label, boolean checked, ToggleAction action) {
            return new MenuRow(label, checked, true, Kind.TOGGLE, null, action);
        }

        static MenuRow navigation(String label, boolean enabled, Runnable action) {
            return new MenuRow(label, false, enabled, Kind.NAVIGATION, action, null);
        }

        void bind(View itemView, TextView labelView, ImageView arrowView) {
            this.itemView = itemView;
            this.labelView = labelView;
            this.arrowView = arrowView;
            setEnabled(enabled);
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
            if (itemView == null) {
                return;
            }
            itemView.setEnabled(enabled);
            itemView.setClickable(enabled);
            itemView.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    // -------------------------------------------------------------- focused

    /** Update the central pill, halo colour, auxiliary controls, and bottom CTA. */
    private void renderFocus(int actionId, boolean animate) {
        mFocusedActionId = actionId;
        ActionRegistry.Item item = ActionRegistry.get(ActionRegistry.indexOf(actionId));
        int accent = getColor(item.color);

        mHalo.setAccent(accent, animate);

        mActionIcon.setImageResource(item.icon);
        mActionIcon.setImageTintList(ColorStateList.valueOf(accent));

        mActionLabel.setText(item.label);
        mActionDesc.setText(descriptionFor(item));

        // Target picker pill — visible for actions with a configurable app target.
        boolean isOpenApp = actionId == Constants.ACTION_OPEN_APP;
        boolean isCamera = actionId == Constants.ACTION_CAMERA;
        mOpenAppPicker.setVisibility(isOpenApp || isCamera ? View.VISIBLE : View.GONE);
        if (isOpenApp || isCamera) {
            String pkg = isCamera ? Settings.getCameraAppPkg(this) : Settings.getOpenAppPkg(this);
            String label = pkg == null ? null : appLabelFor(pkg);
            android.graphics.drawable.Drawable icon = pkg == null ? null : appIconFor(pkg);
            mOpenAppPickerIcon.setVisibility(icon == null ? View.GONE : View.VISIBLE);
            if (icon != null) {
                mOpenAppPickerIcon.setImageDrawable(icon);
            }
            mOpenAppPickerLabel.setText(label != null ? label : getString(isCamera
                    ? R.string.action_camera_pick_summary
                    : R.string.action_open_app_pick_summary));
        }

        if (animate) {
            zoomIn(mActionIcon);
            zoomIn(mActionLabel);
            zoomIn(mActionDesc);
            if (mOpenAppPicker.getVisibility() == View.VISIBLE) zoomIn(mOpenAppPicker);
        }

        updateCta();
    }

    /** Description text for actions with optional app targets. */
    private CharSequence descriptionFor(ActionRegistry.Item item) {
        if (item.id == Constants.ACTION_CAMERA) {
            String pkg = Settings.getCameraAppPkg(this);
            if (pkg != null) {
                String label = appLabelFor(pkg);
                if (label != null) {
                    return getString(R.string.action_camera_desc_named, label);
                }
            }
            return getString(item.desc);
        }
        if (item.id == Constants.ACTION_OPEN_APP) {
            String pkg = Settings.getOpenAppPkg(this);
            if (pkg != null) {
                String label = appLabelFor(pkg);
                if (label != null) {
                    return getString(R.string.action_open_app_desc_named, label);
                }
            }
            return getString(R.string.action_open_app_desc);
        }
        return getString(item.desc);
    }

    private android.graphics.drawable.Drawable appIconFor(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) { return null; }
    }

    private String appLabelFor(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) { return null; }
    }

    // ----------------------------------------------------------------- CTA

    private void updateCta() {
        boolean isCurrent = mFocusedActionId == mSavedActionId;
        if (isCurrent) {
            mCtaButton.setText(R.string.in_use);
            mCtaButton.setAlpha(0.55f);
            mCtaButton.setClickable(false);
            mCtaButton.setOnClickListener(null);
        } else {
            mCtaButton.setText(R.string.set_action);
            mCtaButton.setAlpha(1f);
            mCtaButton.setClickable(true);
            mCtaButton.setOnClickListener(v -> commitFocusedAction());
        }
    }

    private void commitFocusedAction() {
        // For Open app, route through the picker if no app has been chosen
        // yet — committing without a target would be useless.
        if (mFocusedActionId == Constants.ACTION_OPEN_APP
                && Settings.getOpenAppPkg(this) == null) {
            startActivity(new android.content.Intent(this, AppPickerActivity.class));
            return;
        }
        Settings.setAction(this, mEditingLongPress, mFocusedActionId);
        mSavedActionId = mFocusedActionId;
        updateCta();
    }

    // ----------------------------------------------------------- animations

    private void zoomIn(View v) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setScaleX(0.85f);
        v.setScaleY(0.85f);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(280)
                // No overshoot — we want a smooth zoom-to-rest without the
                // bouncy spring that the previous OvershootInterpolator gave.
                .setInterpolator(new DecelerateInterpolator(2.2f))
                .start();
    }

    private void animateEntry() {
        mHalo.setScaleX(0.92f); mHalo.setScaleY(0.92f); mHalo.setAlpha(0f);
        mHalo.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(560).setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        mChipRow.setTranslationY(120f);
        mChipRow.setAlpha(0f);
        mChipRow.animate().translationY(0).alpha(1f)
                .setStartDelay(140).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .start();
    }

}
