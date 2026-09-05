package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class MangaHomeV1HeroAdapter extends RecyclerView.Adapter<MangaHomeV1HeroAdapter.Holder> {
    public interface Listener {
        void onClick(MangaPost post);
        void onNeedChapter(MangaPost post);
        void onChapterClick(MangaPost post);
    }

    private final Context context;
    private final ArrayList<MangaPost> data;
    private final Listener listener;

    public MangaHomeV1HeroAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override public long getItemId(int position) {
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v1_hero_item, parent, false);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(420), Math.max(dp(280), screenWidth - dp(48)));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, dp(218));
        params.setMargins(0, 0, dp(12), 0);
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        MangaPost post = data.get(position);
        holder.title.setText(post.title == null ? "" : post.title);
        holder.genre.setText(formatGenre(post.genre));
        bindChapter(holder, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindChapter(Holder holder, MangaPost post) {
        String chapter = actualChapter(post.latestChapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, true);
            if (MangaSettingsManager.shouldLoadLatestChapterLabel(context) && listener != null) listener.onNeedChapter(post);
            return;
        }
        MangaLabelUtils.bindChapter(holder.chapter, chapter, context, true);
        if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> listener.onChapterClick(post));
        }
    }

    private String formatGenre(String value) {
        if (value == null || value.trim().isEmpty()) return "Manga terbaru";
        String[] parts = value.split(",");
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String clean = part.trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(clean);
            count++;
            if (count >= 3) break;
        }
        return out.length() == 0 ? "Manga terbaru" : out.toString();
    }

    private String actualChapter(String value) {
        return MangaLatestChapterResolver.normalize(value);
    }

    private void bindCover(Holder holder, MangaPost post) {
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        String key = post.getSourceId() + "|" + url;
        holder.boundCoverKey = key;
        holder.image.setBackgroundColor(0x1F888888);
        if (url.isEmpty()) {
            MangaImageLoader.clear(holder.image);
            holder.progress.setVisibility(View.GONE);
            return;
        }
        holder.progress.setVisibility(View.VISIBLE);
        MangaImageLoader.loadForSource(holder.image, url, post.getSourceId(), true, new MangaImageLoader.Callback() {
            @Override public void onSuccess() { updateCoverProgress(holder, key, url, post.getSourceId()); }
            @Override public void onError() { updateCoverProgress(holder, key, url, post.getSourceId()); }
        });
    }

    private void updateCoverProgress(Holder holder, String key, String url, String sourceId) {
        holder.image.post(() -> {
            if (!key.equals(holder.boundCoverKey)) return;
            holder.progress.setVisibility(MangaImageLoader.isLoaded(holder.image, url, sourceId) ? View.GONE : View.VISIBLE);
        });
    }

    @Override public int getItemCount() { return Math.min(data.size(), 8); }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.boundCoverKey = "";
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.progress.setVisibility(View.GONE);
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView genre;
        final TextView chapter;
        String boundCoverKey = "";

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.heroImageView);
            progress = itemView.findViewById(R.id.heroImageProgress);
            title = itemView.findViewById(R.id.heroTitleTextView);
            genre = itemView.findViewById(R.id.heroGenreTextView);
            chapter = itemView.findViewById(R.id.heroChapterTextView);
        }
    }
}
