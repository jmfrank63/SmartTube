package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a SECOND subtitle track (e.g. translation next to the original language)
 * in its own overlay view, positioned above the native subtitle area.
 *
 * Data source: YouTube timedtext URLs (CaptionTrack baseUrl, + "&tlang=xx" for translated
 * variants) from the video's MediaItemFormatInfo. Content is forced to WebVTT ("fmt=vtt")
 * and parsed with a small built-in parser.
 *
 * Fork feature. Not part of upstream SmartTube.
 */
public class DualSubtitleManager {
    private static final String TAG = DualSubtitleManager.class.getSimpleName();
    private static final String PREFS = "dual_subtitle";
    private static final String PREF_LANG = "lang";
    private static final String PREF_TYPE = "type";
    private static final String PREF_OFFSET = "offset";
    private static final String PREF_DUAL_POS = "dual_pos";
    private static final String PREF_DUAL_STYLE = "dual_style";
    private static final String PREF_DUAL_SCALE = "dual_scale";
    public static final float POSITION_FOLLOW = -1f;
    public static final int STYLE_FOLLOW = -1;
    public static final float SCALE_FOLLOW = -1f;
    private static final long TICK_MS = 250;
    private static final float POSITION_DELTA = 0.12f; // dual line sits this much above the native one
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?:(\\d+):)?(\\d+):(\\d+)[.,](\\d+)\\s*-->\\s*(?:(\\d+):)?(\\d+):(\\d+)[.,](\\d+)");

    private static DualSubtitleManager sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences mPrefs;

    private SubtitleView mDualView;
    private PositionProvider mPositionProvider;
    private List<MediaSubtitle> mTracks;
    private MediaSubtitle mSelected;
    private volatile List<CueEntry> mCueTimeline;
    private String mLastText;
    private boolean mTickerRunning;
    private long mPositionMs;
    private long mOffsetMs;
    private float mNativePosition = 0.08f; // ExoPlayer default
    private int mDebugTick;
    private String mNativeText;
    private float mDualPosition = POSITION_FOLLOW;
    private int mDualStyleIndex = STYLE_FOLLOW;
    private float mDualScale = SCALE_FOLLOW;
    private CaptionStyleCompat mNativeStyle;
    private float mNativeSizePx = -1;

    public interface PositionProvider {
        long getPositionMs();
    }

    private static class CueEntry {
        long startMs;
        long endMs;
        String text;

        CueEntry(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (!mTickerRunning) {
                return;
            }
            renderAtPosition();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    public static DualSubtitleManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new DualSubtitleManager(context.getApplicationContext() != null ? context.getApplicationContext() : context);
        }
        return sInstance;
    }

    private DualSubtitleManager(Context context) {
        mContext = context;
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        mOffsetMs = mPrefs.getLong(PREF_OFFSET, 0);
        mDualPosition = mPrefs.getFloat(PREF_DUAL_POS, POSITION_FOLLOW);
        mDualStyleIndex = mPrefs.getInt(PREF_DUAL_STYLE, STYLE_FOLLOW);
        mDualScale = mPrefs.getFloat(PREF_DUAL_SCALE, SCALE_FOLLOW);
    }

    /**
     * Call from the playback UI once the native SubtitleView exists.
     * Creates the second overlay view in the same container, shifted up.
     */
    public void attach(SubtitleView nativeSubtitleView) {
        if (nativeSubtitleView == null) {
            return;
        }
        boolean alreadyLive = mDualView != null && mDualView.getParent() != null
                && androidx.core.view.ViewCompat.isAttachedToWindow(mDualView);
        com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: attach, alreadyLive=" + alreadyLive);
        if (alreadyLive) {
            return;
        }
        ViewGroup parent = (ViewGroup) nativeSubtitleView.getParent();
        if (parent == null) {
            return;
        }
        mDualView = new SubtitleView(mContext, null);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mDualView.setLayoutParams(params);
        applyDualPosition();
        int index = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) == nativeSubtitleView) {
                index = i + 1;
                break;
            }
        }
        parent.addView(mDualView, index, params);
        mDualView.setVisibility(View.GONE);
        // Restore rendering into the fresh view if a track is already loaded
        if (mSelected != null && mCueTimeline != null && !mCueTimeline.isEmpty()) {
            mDualView.setVisibility(View.VISIBLE);
            startTicker();
        }
    }

    /** View teardown: drop the overlay reference, keep selection state. */
    public void detach() {
        stopTicker();
        mDualView = null;
    }

    /** Feed the current video's caption tracks. Called when format info arrives. */
    public void setTracks(@Nullable List<MediaSubtitle> subtitles) {
        mTracks = subtitles;
        synthesizeTranslations();
        String lang = mPrefs.getString(PREF_LANG, null);
        String type = mPrefs.getString(PREF_TYPE, null);
        com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "setTracks: " + (mTracks != null ? mTracks.size() : -1)
                + " tracks, pref lang=" + lang + " type=" + type);
        if (lang == null) {
            clearSelection();
            return;
        }
        MediaSubtitle match = findTrack(lang, type);
        if (match != null) {
            selectInternal(match);
        } else {
            com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "setTracks: no match for " + lang + ", clearing");
            clearSelection();
        }
    }

    /**
     * YouTube only returns caption tracks in their original languages. For tracks marked
     * translatable, synthesize variants for our target languages via "&tlang=xx" —
     * this makes e.g. a RU translation available on an EN-only-captioned video.
     */
    private void synthesizeTranslations() {
        if (mTracks == null) {
            return;
        }
        List<MediaSubtitle> result = new ArrayList<>(mTracks);
        List<String> existing = new ArrayList<>();
        for (MediaSubtitle t : mTracks) {
            if (t.getLanguageCode() != null) {
                existing.add(t.getLanguageCode());
            }
        }
        for (MediaSubtitle t : mTracks) {
            if (t.getBaseUrl() == null) {
                continue;
            }
            boolean synthesized = false;
            // Fork: synthesize regardless of the translatable flag — ASR/manual tracks
            // are translatable in practice, and some player responses omit the flag
            for (String target : TRANSLATE_TARGETS) {
                if (existing.contains(target)) {
                    continue;
                }
                result.add(new TranslatedSubtitle(t, target));
                existing.add(target);
                synthesized = true;
            }
            com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "setTracks: lang=" + t.getLanguageCode()
                    + " name=" + t.getName() + " translatable=" + t.isTranslatable()
                    + " synthesized=" + synthesized);
        }
        mTracks = result;
    }

    private static final String[] TRANSLATE_TARGETS = {"ru", "en"};

    private static class TranslatedSubtitle implements MediaSubtitle {
        private final MediaSubtitle mOrigin;
        private final String mTarget;
        private final String mOriginLang;

        TranslatedSubtitle(MediaSubtitle origin, String target) {
            mOrigin = origin;
            mTarget = target;
            mOriginLang = origin.getLanguageCode() != null ? origin.getLanguageCode() : "auto";
        }

        /** NOTE: returns the ORIGINAL baseUrl — translation happens client-side (tlang is dead). */
        @Override
        public String getBaseUrl() {
            return mOrigin.getBaseUrl();
        }

        @Override
        public boolean isTranslatable() {
            return false;
        }

        @Override
        public String getLanguageCode() {
            return mTarget;
        }

        public String getOriginLanguageCode() {
            return mOriginLang;
        }

        public String getTarget() {
            return mTarget;
        }

        @Override
        public String getVssId() {
            return mOrigin.getVssId();
        }

        @Override
        public String getName() {
            String originName = mOrigin.getName() != null ? mOrigin.getName() : mOriginLang;
            return originName + " → " + mTarget.toUpperCase();
        }

        @Override
        public String getMimeType() {
            return mOrigin.getMimeType();
        }

        @Override
        public String getCodecs() {
            return mOrigin.getCodecs();
        }

        @Override
        public String getType() {
            return mOrigin.getType();
        }
    }

    public List<MediaSubtitle> getTracks() {
        return mTracks;
    }

    public MediaSubtitle getSelected() {
        return mSelected;
    }

    /** User picked a track in the dialog (or Off/null to disable). */
    public void select(@Nullable MediaSubtitle track) {
        if (track == null) {
            mPrefs.edit().remove(PREF_LANG).remove(PREF_TYPE).apply();
            clearSelection();
            return;
        }
        mPrefs.edit().putString(PREF_LANG, track.getLanguageCode())
                .putString(PREF_TYPE, track.getType() != null ? track.getType() : "").apply();
        selectInternal(track);
    }

    private void selectInternal(MediaSubtitle track) {
        mSelected = track;
        mCueTimeline = null;
        mLastText = null;
        mLastText = null;
        if (mDualView != null) {
            mDualView.setVisibility(View.GONE);
        }
        String url = toVttUrl(track.getBaseUrl());

        // Fork: pre-baked tlang URLs are rejected by YouTube ("Sorry" page) — strip the
        // tlang param, fetch the origin track, then translate client-side
        String translateTarget = null;
        String translateSource = null;
        if (url != null && url.contains("tlang=")) {
            Matcher targetMatcher = Pattern.compile("tlang=([^&]*)").matcher(url);
            if (targetMatcher.find()) {
                translateTarget = targetMatcher.group(1);
            }
            Matcher langMatcher = Pattern.compile("[?&]lang=([^&]*)").matcher(url);
            if (langMatcher.find()) {
                translateSource = langMatcher.group(1);
            }
            url = url.replaceAll("&tlang=[^&]*", "");
            com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: tlang URL rejected by YT, fetching origin ("
                    + translateSource + ") and translating client-side to " + translateTarget);
        }
        if (url == null) {
            return;
        }
        final String fetchUrl = url;
        final boolean needsClientTranslate = translateTarget != null;
        final String clientSource = translateSource != null ? translateSource : "auto";
        final String clientTarget = translateTarget;
        new Thread(() -> {
            try {
                com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: fetching " + fetchUrl);
                okhttp3.Response response = OkHttpManager.instance().doGetRequest(fetchUrl);
                if (response.body() == null) {
                    throw new IOException("empty body");
                }
                String body = response.body().string();
                List<CueEntry> timeline = parseVtt(body);
                com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: fetched " + body.length()
                        + " chars, parsed " + timeline.size() + " cues"
                        + (timeline.size() > 0 ? ", first=" + timeline.get(0).startMs
                           + " last=" + timeline.get(timeline.size() - 1).endMs : ""));

                // Fork: client-side translation for synthesized tracks (YouTube rejects tlang)
                if (track instanceof TranslatedSubtitle && timeline.size() > 0) {
                    TranslatedSubtitle ts = (TranslatedSubtitle) track;
                    com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translating "
                            + timeline.size() + " cues " + ts.getOriginLanguageCode() + " -> " + ts.getTarget());
                    translateTimelineSync(timeline, ts.getOriginLanguageCode(), ts.getTarget());
                    com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translation done");
                } else if (needsClientTranslate && timeline.size() > 0) {
                    com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translating "
                            + timeline.size() + " cues " + clientSource + " -> " + clientTarget);
                    translateTimelineSync(timeline, clientSource, clientTarget);
                    com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translation done");
                }

                mHandler.post(() -> {
                    if (mSelected != track) {
                        com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: stale response dropped");
                        return; // stale response
                    }
                    mCueTimeline = timeline;
                    startTicker();
                });
            } catch (IOException e) {
                com.liskovsoft.sharedutils.mylogger.Log.e(TAG, "Dual subs: fetch failed", e);
                mHandler.post(() -> {
                    if (mSelected == track) {
                        mSelected = null;
                        clearSelection();
                        MessageHelpers.showMessage(mContext, "Dual subs: load failed");
                    }
                });
            }
        }).start();
    }

    private void clearSelection() {
        mSelected = null;
        mCueTimeline = null;
        mLastText = null;
        stopTicker();
        if (mDualView != null) {
            mDualView.setVisibility(View.GONE);
            mDualView.setCues(null);
        }
    }

    public void setPositionProvider(PositionProvider provider) {
        mPositionProvider = provider;
    }

    /** Sync offset for the second line: positive values shift it forward in time. */
    public void setOffset(long offsetMs) {
        mOffsetMs = offsetMs;
        mPrefs.edit().putLong(PREF_OFFSET, offsetMs).apply();
        if (mTickerRunning) {
            renderAtPosition();
        }
    }

    public long getOffset() {
        return mOffsetMs;
    }

    /**
     * Mirror the native subtitle's vertical position so the dual line
     * always sits POSITION_DELTA above it. Call when the user changes position.
     */
    public void setNativePosition(float bottomPaddingFraction) {
        mNativePosition = bottomPaddingFraction;
        applyDualPosition();
    }

    private void applyDualPosition() {
        if (mDualView != null) {
            float fraction;
            if (isComposing()) {
                // combined block renders both lines — sit exactly at the native position
                fraction = mDualPosition >= 0 ? mDualPosition : mNativePosition;
            } else {
                fraction = mDualPosition >= 0 ? mDualPosition
                        : Math.min(0.85f, mNativePosition + POSITION_DELTA);
            }
            mDualView.setBottomPaddingFraction(fraction);
        }
    }

    /** Called by SubtitleManager whenever the native style/size is (re)applied. */
    public void mirrorNativeStyle(CaptionStyleCompat nativeStyle, float nativeSizePx) {
        mNativeStyle = nativeStyle;
        mNativeSizePx = nativeSizePx;
        applyStyleToDualView();
    }

    private void applyStyleToDualView() {
        if (mDualView == null) {
            return;
        }
        CaptionStyleCompat style = mNativeStyle;
        float sizePx = mNativeSizePx;

        if (mDualStyleIndex >= 0) {
            List<SubtitleManager.SubtitleStyle> styles =
                    com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData.instance(mContext).getSubtitleStyles();
            if (styles != null && mDualStyleIndex < styles.size()) {
                SubtitleManager.SubtitleStyle s = styles.get(mDualStyleIndex);
                if (!s.isSystem()) {
                    style = new CaptionStyleCompat(
                            ContextCompat.getColor(mContext, s.subsColorResId),
                            ContextCompat.getColor(mContext, s.backgroundColorResId),
                            Color.TRANSPARENT,
                            s.captionStyle,
                            ContextCompat.getColor(mContext, com.liskovsoft.smartyoutubetv2.common.R.color.black),
                            Typeface.DEFAULT_BOLD);
                }
            }
        }

        if (mDualScale > 0 && sizePx > 0) {
            float base = mContext.getResources().getDimension(
                    com.liskovsoft.smartyoutubetv2.common.R.dimen.subtitle_text_size);
            sizePx = base * mDualScale;
        }

        if (style != null) {
            mDualView.setStyle(style);
        }
        if (sizePx > 0) {
            mDualView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx);
        }
    }

    public void setDualPosition(float fraction) {
        mDualPosition = fraction;
        mPrefs.edit().putFloat(PREF_DUAL_POS, fraction).apply();
        applyDualPosition();
    }

    public float getDualPosition() {
        return mDualPosition;
    }

    public void setDualStyleIndex(int index) {
        mDualStyleIndex = index;
        mPrefs.edit().putInt(PREF_DUAL_STYLE, index).apply();
        applyStyleToDualView();
    }

    public int getDualStyleIndex() {
        return mDualStyleIndex;
    }

    public void setDualScale(float scale) {
        mDualScale = scale;
        mPrefs.edit().putFloat(PREF_DUAL_SCALE, scale).apply();
        applyStyleToDualView();
    }

    public float getDualScale() {
        return mDualScale;
    }

    /** External position update (from the player progress loop). */
    public void updatePosition(long positionMs) {
        mPositionMs = positionMs;
        if (mTickerRunning) {
            renderAtPosition();
        }
    }

    private void startTicker() {
        if (mTickerRunning || mDualView == null) {
            return;
        }
        mTickerRunning = true;
        mDualView.setVisibility(View.VISIBLE);
        mHandler.post(mTicker);
    }

    private void stopTicker() {
        mTickerRunning = false;
        mHandler.removeCallbacks(mTicker);
    }

    private void renderAtPosition() {
        if (mDualView == null) {
            return;
        }
        if (mPositionProvider != null) {
            long pos = mPositionProvider.getPositionMs();
            if (pos >= 0) {
                mPositionMs = pos;
            }
        }
        long effectivePos = mPositionMs + mOffsetMs;
        mDebugTick++;
        String secondText = activeSecondText(effectivePos);
        if (mDebugTick % 16 == 0 && mCueTimeline != null && mCueTimeline.size() > 0) { // ~every 4s diagnostic
            com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "tick: pos=" + effectivePos
                    + " cues=" + mCueTimeline.size()
                    + " first=" + mCueTimeline.get(0).startMs
                    + " last=" + mCueTimeline.get(mCueTimeline.size() - 1).endMs);
        }
        composeAndRender(secondText);
    }

    @Nullable
    private String activeSecondText(long effectivePos) {
        if (mCueTimeline == null) {
            return null;
        }
        for (CueEntry e : mCueTimeline) {
            if (effectivePos >= e.startMs && effectivePos < e.endMs) {
                return e.text;
            }
        }
        return null;
    }

    /**
     * Fork: renders BOTH lines as one cue so their display windows are always identical —
     * the combined block stays up for the union of both tracks' timing.
     */
    private void composeAndRender(@Nullable String secondText) {
        if (mDualView == null) {
            return;
        }
        String combined;
        if (mNativeText != null && secondText != null) {
            combined = mNativeText + "\n" + secondText;
        } else if (mNativeText != null) {
            combined = mNativeText;
        } else if (secondText != null) {
            combined = secondText;
        } else {
            combined = null;
        }

        if (combined == null && mLastText == null) {
            return;
        }
        if (combined != null && combined.equals(mLastText)) {
            return;
        }
        mLastText = combined;
        if (combined == null) {
            mDualView.setCues(null);
            mDualView.setVisibility(View.GONE);
        } else {
            mDualView.setVisibility(View.VISIBLE);
            mDualView.setCues(java.util.Collections.singletonList(new Cue(combined)));
        }
    }

    /**
     * Fork: receives the native line's cues so both lines render together in one view.
     */
    public void onNativeCues(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        if (cues != null) {
            for (Cue c : cues) {
                CharSequence t = c.text;
                if (t != null && t.length() > 0) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(t);
                }
            }
        }
        mNativeText = sb.length() > 0 ? sb.toString() : null;
        composeAndRender(activeSecondText(mPositionMs + mOffsetMs));
    }

    /**
     * True while a second subtitle track is selected — the native view hands its cues
     * to the composer instead of rendering itself.
     */
    public boolean isComposing() {
        return mSelected != null;
    }

    @Nullable
    private MediaSubtitle findTrack(String lang, String type) {
        if (mTracks == null || lang == null) {
            return null;
        }
        MediaSubtitle fallback = null;
        for (MediaSubtitle track : mTracks) {
            if (lang.equals(track.getLanguageCode())) {
                boolean wantAsr = "asr".equals(type);
                if (wantAsr == "asr".equals(track.getType())) {
                    return track;
                }
                if (fallback == null) {
                    fallback = track;
                }
            }
        }
        return fallback;
    }

    /** Force the timedtext response into WebVTT form so the parser can read it. */
    @Nullable
    private static String toVttUrl(@Nullable String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        if (baseUrl.contains("fmt=")) {
            return baseUrl.replaceAll("fmt=[^&]*", "fmt=vtt");
        }
        return baseUrl + "&fmt=vtt";
    }

    /**
     * Fork: translate cue texts in-place via the public Google translate endpoint
     * (no API key). Blocking — call from a background thread.
     */
    private void translateTimelineSync(List<CueEntry> timeline, String sourceLang, String targetLang) {
        java.util.HashMap<String, String> cache = new java.util.HashMap<>();
        List<String> unique = new ArrayList<>();
        for (CueEntry e : timeline) {
            String t = e.text == null ? "" : e.text.trim();
            if (t.isEmpty() || (t.startsWith("[") && t.endsWith("]"))) {
                continue; // non-speech markers stay as-is
            }
            if (!cache.containsKey(t) && !unique.contains(t)) {
                unique.add(t);
            }
        }
        if (unique.isEmpty()) {
            return;
        }
        com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translating " + unique.size()
                + " unique lines " + sourceLang + " -> " + targetLang);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (final String line : unique) {
            futures.add(pool.submit(() -> {
                String translated = translateLine(line, sourceLang, targetLang);
                if (translated != null) {
                    synchronized (cache) {
                        cache.put(line, translated);
                    }
                }
                int n = done.incrementAndGet();
                if (n % 25 == 0) {
                    com.liskovsoft.sharedutils.mylogger.Log.i(TAG, "Dual subs: translated " + n + "/" + unique.size());
                }
            }));
        }
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();
        for (CueEntry e : timeline) {
            if (e.text == null) {
                continue;
            }
            String translated;
            synchronized (cache) {
                translated = cache.get(e.text.trim());
            }
            if (translated != null) {
                e.text = translated;
            }
        }
    }

    @Nullable
    private static String translateLine(String text, String sourceLang, String targetLang) {
        try {
            String q = java.net.URLEncoder.encode(text, "UTF-8");
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t"
                    + "&sl=" + java.net.URLEncoder.encode(sourceLang, "UTF-8")
                    + "&tl=" + java.net.URLEncoder.encode(targetLang, "UTF-8")
                    + "&q=" + q;
            okhttp3.Response response = OkHttpManager.instance().doGetRequest(url);
            if (response.body() == null) {
                return null;
            }
            return extractGtxTranslation(response.body().string());
        } catch (Exception e) {
            com.liskovsoft.sharedutils.mylogger.Log.e(TAG, "Dual subs: translate failed", e);
            return null;
        }
    }

    /**
     * Pulls translated sentences out of the gtx nested-array response
     * ([[["en text","ru text",...],...],...]) and joins them.
     */
    @Nullable
    private static String extractGtxTranslation(String body) {
        if (body == null || body.isEmpty() || body.startsWith("<")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        boolean any = false;
        while (m.find()) {
            if (any) {
                sb.append(" ");
            }
            sb.append(m.group(1));
            any = true;
        }
        return any ? sb.toString() : null;
    }

    /** Minimal WebVTT parser: timestamps + plain text, tags stripped. */
    private static List<CueEntry> parseVtt(String content) {
        List<CueEntry> result = new ArrayList<>();
        if (content == null) {
            return result;
        }
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        long startMs = -1;
        long endMs = -1;
        StringBuilder text = null;

        for (String line : lines) {
            Matcher m = TIMESTAMP.matcher(line);
            if (m.find()) {
                if (startMs >= 0 && text != null && text.length() > 0) {
                    result.add(new CueEntry(startMs, endMs, text.toString()));
                }
                startMs = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
                endMs = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
                text = new StringBuilder();
                continue;
            }
            if (startMs >= 0) { // inside a cue block
                if (line.trim().isEmpty()) {
                    if (text != null && text.length() > 0) {
                        result.add(new CueEntry(startMs, endMs, text.toString()));
                    }
                    startMs = -1;
                    endMs = -1;
                    text = null;
                } else if (!line.trim().matches("\\d+") && !line.startsWith("NOTE")
                        && !line.startsWith("STYLE") && !line.startsWith("REGION")) {
                    if (text.length() > 0) {
                        text.append("\n");
                    }
                    text.append(line.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").trim());
                }
            }
        }
        if (startMs >= 0 && text != null && text.length() > 0) {
            result.add(new CueEntry(startMs, endMs, text.toString()));
        }
        return result;
    }

    private static long toMs(String hours, String min, String sec, String millis) {
        long h = hours != null ? Long.parseLong(hours) : 0;
        return (h * 3600 + Long.parseLong(min) * 60 + Long.parseLong(sec)) * 1000 + Long.parseLong(millis);
    }
}
