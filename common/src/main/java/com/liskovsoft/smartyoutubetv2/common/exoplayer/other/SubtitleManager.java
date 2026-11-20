package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build.VERSION;
import android.util.TypedValue;
import android.view.View;
import android.view.accessibility.CaptioningManager;
import android.view.accessibility.CaptioningManager.CaptionStyle;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.DualSubtitleManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;

import java.util.ArrayList;
import java.util.List;

public class SubtitleManager implements TextOutput, OnDataChange {
    private static final String TAG = SubtitleManager.class.getSimpleName();
    private final SubtitleView mSubtitleView;
    private final Context mContext;
    private final List<SubtitleStyle> mSubtitleStyles = new ArrayList<>();
    private final AppPrefs mPrefs;
    private final PlayerData mPlayerData;
    private final android.content.SharedPreferences mDualPrefs;
    private final android.os.Handler mCueHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private List<Cue> mPendingCues;
    private long mShowAtElapsed;
    private boolean mReleasePending;
    private CharSequence subsBuffer;

    public static class SubtitleStyle {
        public final int nameResId;
        public final int subsColorResId;
        public final int backgroundColorResId;
        public final int captionStyle;

        public SubtitleStyle(int nameResId) {
            this(nameResId, -1, -1, -1);
        }

        public SubtitleStyle(int nameResId, int subsColorResId, int backgroundColorResId, int captionStyle) {
            this.nameResId = nameResId;
            this.subsColorResId = subsColorResId;
            this.backgroundColorResId = backgroundColorResId;
            this.captionStyle = captionStyle;
        }

        public boolean isSystem() {
            return subsColorResId == -1 && backgroundColorResId == -1 && captionStyle == -1;
        }
    }

    public SubtitleManager(SubtitleView subtitleView) {
        mContext = subtitleView.getContext();
        mSubtitleView = subtitleView;
        mPrefs = AppPrefs.instance(mContext);
        mPlayerData = PlayerData.instance(mContext);
        mDualPrefs = mContext.getSharedPreferences("dual_subtitle", Context.MODE_PRIVATE);
        mPlayerData.setOnChange(this);
        configureSubtitleView();
    }

    @Override
    public void onDataChange() {
        configureSubtitleView();
    }

    @Override
    public void onCues(List<Cue> cues) {
        if (mSubtitleView == null) {
            return;
        }
        List<Cue> aligned = forceCenterAlignment(cues);

        // Fork: while a second subtitle track is selected, the native line is composed
        // together with it into one synchronized block
        DualSubtitleManager dual = DualSubtitleManager.instance(mContext);
        boolean composing = dual.isComposing();

        // Fork: optional delay for the native line (voiceover/dub timing)
        long offsetMs = mDualPrefs.getLong("native_offset", 0);
        if (offsetMs <= 0) {
            mCueHandler.removeCallbacks(mReleaseRunnable);
            mReleasePending = false;
            mPendingCues = null;
            if (composing) {
                mSubtitleView.setVisibility(View.GONE);
                dual.onNativeCues(aligned);
            } else {
                mSubtitleView.setVisibility(View.VISIBLE);
                mSubtitleView.setCues(aligned);
            }
            return;
        }
        mPendingCues = aligned;
        mShowAtElapsed = android.os.SystemClock.elapsedRealtime() + offsetMs;
        if (!mReleasePending) {
            mReleasePending = true;
            mCueHandler.post(mReleaseRunnable);
        }
    }

    private final Runnable mReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mReleasePending) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now < mShowAtElapsed) {
                mCueHandler.postDelayed(this, Math.min(200, mShowAtElapsed - now));
                return;
            }
            mReleasePending = false;
            if (mPendingCues == null) {
                return;
            }
            DualSubtitleManager dual = DualSubtitleManager.instance(mContext);
            if (dual.isComposing()) {
                if (mSubtitleView != null) {
                    mSubtitleView.setVisibility(View.GONE);
                }
                dual.onNativeCues(mPendingCues);
            } else if (mSubtitleView != null) {
                mSubtitleView.setCues(mPendingCues);
            }
            mPendingCues = null;
        }
    };

    /** Fork: current delay of the native subtitle line, ms (>= 0). */
    public long getNativeOffset() {
        return mDualPrefs.getLong("native_offset", 0);
    }

    public void show(boolean show) {
        if (mSubtitleView != null) {
            mSubtitleView.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    private List<SubtitleStyle> getSubtitleStyles() {
        return mSubtitleStyles;
    }

    private SubtitleStyle getSubtitleStyle() {
        return mPlayerData.getSubtitleStyle();
    }

    private void setSubtitleStyle(SubtitleStyle subtitleStyle) {
        mPlayerData.setSubtitleStyle(subtitleStyle);
        configureSubtitleView();
    }

    private List<Cue> forceCenterAlignment(List<Cue> cues) {
        List<Cue> result = new ArrayList<>();

        for (Cue cue : cues) {
            // Autogenerated subs repeated lines fix
            final String textStr = cue.text.toString();
            if (Helpers.endsWithAny(textStr, "\n", " ")) { // vtt subs format
                subsBuffer = textStr;
            } else if (textStr.contains("\n")) { // ttml subs format
                //CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "").replace("\n", "") : textStr;

                CharSequence text;

                if (subsBuffer != null && textStr.contains(subsBuffer)) {
                    text = textStr.replace(subsBuffer, "").replace("\n", "");
                } else {
                    text = textStr;
                }

                result.add(new Cue(text)); // sub centered by default

                String[] split = textStr.split("\n");
                subsBuffer = split.length == 2 ? split[1] : textStr;
            } else {
                CharSequence text = subsBuffer != null ? textStr.replace(subsBuffer, "") : textStr;
                result.add(new Cue(text)); // sub centered by default
                subsBuffer = text;
            }
        }

        return result;
    }

    private void configureSubtitleView() {
        if (mSubtitleView != null) {
            // disable default style
            mSubtitleView.setApplyEmbeddedStyles(false);

            SubtitleStyle subtitleStyle = getSubtitleStyle();

            if (subtitleStyle.isSystem()) {
                if (VERSION.SDK_INT >= 19) {
                    applySystemStyle();
                }
            } else {
                applyStyle(subtitleStyle);
            }

            mSubtitleView.setBottomPaddingFraction(mPlayerData.getSubtitlePosition());
            DualSubtitleManager.instance(mContext).setNativePosition(mPlayerData.getSubtitlePosition()); // Fork: keep dual line above native
        }
    }

    private void applyStyle(SubtitleStyle subtitleStyle) {
        int textColor = ContextCompat.getColor(mContext, subtitleStyle.subsColorResId);
        int outlineColor = ContextCompat.getColor(mContext, R.color.black);
        int backgroundColor = ContextCompat.getColor(mContext, subtitleStyle.backgroundColorResId);

        CaptionStyleCompat style =
                new CaptionStyleCompat(textColor,
                        backgroundColor, Color.TRANSPARENT,
                        subtitleStyle.captionStyle,
                        outlineColor, Typeface.DEFAULT_BOLD);
        mSubtitleView.setStyle(style);

        float textSize = getTextSizePx();
        mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
        DualSubtitleManager.instance(mContext).mirrorNativeStyle(style, textSize); // Fork: mirror to dual line
    }

    @RequiresApi(19)
    private void applySystemStyle() {
        CaptioningManager captioningManager =
                (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        if (captioningManager != null) {
            CaptionStyle userStyle = captioningManager.getUserStyle();

            CaptionStyleCompat style =
                    new CaptionStyleCompat(userStyle.foregroundColor,
                            userStyle.backgroundColor, VERSION.SDK_INT >= 21 ? userStyle.windowColor : Color.TRANSPARENT,
                            userStyle.edgeType,
                            userStyle.edgeColor, userStyle.getTypeface());
            mSubtitleView.setStyle(style);

            float textSizePx = getTextSizePx();
            mSubtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx * captioningManager.getFontScale());
            DualSubtitleManager.instance(mContext).mirrorNativeStyle(style, textSizePx * captioningManager.getFontScale()); // Fork: mirror to dual line
        }
    }

    private float getTextSizePx() {
        float textSizePx = mSubtitleView.getContext().getResources().getDimension(R.dimen.subtitle_text_size);
        return textSizePx * mPlayerData.getSubtitleScale();
    }
}
