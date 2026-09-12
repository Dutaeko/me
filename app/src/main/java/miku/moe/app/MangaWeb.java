package miku.moe.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MangaWeb extends KomikcastClient {
    private static final String SOURCE_ID = MangaSettingsManager.MANGA_SOURCE_MANGAWEB;
    private static final String SOURCE_LABEL = "Manga Web";
    private static final int PAGE_SIZE = 24;
    private static final long CACHE_TTL = 12L * 60L * 1000L;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36";
    private static final String IMAGE_PROXY = "https://iusndfindf-dpeyalw6nmle.edgeone.dev";
    private static final OkHttpClient CLIENT = MangaHttpClient.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(true).build();
    private static final MangaMemoryCache<String, ArrayList<MangaPost>> LIST_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, MangaPost> DETAIL_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<MangaChapter>> CHAPTER_CACHE = new MangaMemoryCache<>(64, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<String>> PAGE_CACHE = new MangaMemoryCache<>(48, CACHE_TTL);
    private static final MangaMemoryCache<String, ArrayList<GenreItem>> GENRE_CACHE = new MangaMemoryCache<>(4, CACHE_TTL);
    private static final ConcurrentHashMap<String, String> CHAPTER_SLUGS = new ConcurrentHashMap<>();
    private static final Pattern CHAPTER_NUMBER = Pattern.compile("(?i)(?:chapter\\s*)?(\\d+(?:[.,]\\d+)?)");

    private interface RscCallback {
        void onSuccess(RscDocument document);
        void onError(String message);
    }

    private interface RscObjectCallback {
        void onSuccess(RscDocument document, JsonObject object);
        void onError(String message);
    }

    private static final class RscDocument {
        final LinkedHashMap<String, JsonElement> frames = new LinkedHashMap<>();
        final ArrayList<JsonElement> roots = new ArrayList<>();

        static RscDocument parse(String body) {
            RscDocument document = new RscDocument();
            if (body == null || body.isEmpty()) return document;
            String[] lines = body.split("\\n");
            for (String rawLine : lines) {
                if (rawLine == null) continue;
                String line = rawLine.endsWith("\\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
                int separator = line.indexOf(':');
                if (separator <= 0 || separator >= line.length() - 1) continue;
                String id = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                if (id.isEmpty() || value.isEmpty()) continue;
                try {
                    JsonElement element = JsonParser.parseString(value);
                    document.frames.put(id, element);
                    document.roots.add(element);
                } catch (Exception ignored) {
                }
            }
            return document;
        }

        JsonObject findObject(String... keys) {
            for (JsonElement root : roots) {
                JsonObject found = findObject(root, keys);
                if (found != null) return found;
            }
            return null;
        }

        private JsonObject findObject(JsonElement element, String... keys) {
            JsonElement resolved = resolve(element);
            if (resolved == null || resolved.isJsonNull()) return null;
            if (resolved.isJsonObject()) {
                JsonObject object = resolved.getAsJsonObject();
                boolean matches = true;
                for (String key : keys) {
                    if (!object.has(key)) {
                        matches = false;
                        break;
                    }
                }
                if (matches) return object;
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    JsonObject found = findObject(entry.getValue(), keys);
                    if (found != null) return found;
                }
            } else if (resolved.isJsonArray()) {
                for (JsonElement child : resolved.getAsJsonArray()) {
                    JsonObject found = findObject(child, keys);
                    if (found != null) return found;
                }
            }
            return null;
        }

        JsonElement resolve(JsonElement element) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return element;
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (!primitive.isString()) return element;
            String value = primitive.getAsString();
            if (value.length() < 2 || value.charAt(0) != '$') return element;
            JsonElement frame = frames.get(value.substring(1));
            return frame == null ? element : frame;
        }

        JsonArray array(JsonObject object, String key) {
            if (object == null || key == null || !object.has(key)) return new JsonArray();
            JsonElement value = resolve(object.get(key));
            return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
        }
    }

    private static final class DetailBundle {
        final MangaPost detail;
        final ArrayList<MangaChapter> chapters;

        DetailBundle(MangaPost detail, ArrayList<MangaChapter> chapters) {
            this.detail = detail;
            this.chapters = chapters;
        }
    }

    protected static String base() {
        return MangaSettingsManager.getSourceDomain(SOURCE_ID);
    }

    @Override protected String sourceLabel() {
        return SOURCE_LABEL;
    }

    @Override public void list(int page, String sort, String query, String genre, Result<ArrayList<MangaPost>> cb) {
        int safePage = Math.max(1, page);
        String businessUrl = buildListUrl(safePage, sort, query, genre);
        ArrayList<MangaPost> cached = LIST_CACHE.get(businessUrl);
        if (cached != null) {
            cb.onSuccess(new ArrayList<>(cached), cached.size() >= PAGE_SIZE);
            return;
        }
        requestRscObject(businessUrl, listStateTree(), businessUrl, new String[]{"mangas", "totalItems", "currentFilters"}, "Daftar Manga Web gagal dibaca", new RscObjectCallback() {
            @Override public void onSuccess(RscDocument document, JsonObject payload) {
                try {
                    ArrayList<MangaPost> out = new ArrayList<>();
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    JsonArray mangas = document.array(payload, "mangas");
                    for (JsonElement element : mangas) {
                        if (element == null || !element.isJsonObject()) continue;
                        MangaPost post = parseListPost(element.getAsJsonObject(), document);
                        String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
                        if (key != null && !key.trim().isEmpty() && seen.add(key)) out.add(post);
                    }
                    LIST_CACHE.put(businessUrl, new ArrayList<>(out));
                    boolean hasNext = out.size() >= PAGE_SIZE;
                    MangaCoroutines.main(() -> cb.onSuccess(out, hasNext));
                } catch (Exception e) {
                    MangaCoroutines.main(() -> cb.onError("Daftar Manga Web gagal dibaca"));
                }
            }

            @Override public void onError(String message) {
                MangaCoroutines.main(() -> cb.onError(message));
            }
        });
    }

    @Override public void genres(Result<ArrayList<GenreItem>> cb) {
        ArrayList<GenreItem> cached = GENRE_CACHE.get("genres");
        if (cached != null) {
            cb.onSuccess(new ArrayList<>(cached), false);
            return;
        }
        String businessUrl = buildListUrl(1, "latest", "", "");
        requestRscObject(businessUrl, listStateTree(), businessUrl, new String[]{"mangas", "totalItems", "currentFilters"}, "Genre Manga Web gagal dibaca", new RscObjectCallback() {
            @Override public void onSuccess(RscDocument document, JsonObject payload) {
                try {
                    ArrayList<GenreItem> out = new ArrayList<>();
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    JsonArray genres = document.array(payload, "genres");
                    for (JsonElement element : genres) {
                        if (element == null || !element.isJsonObject()) continue;
                        String name = getString(element.getAsJsonObject(), "name");
                        if (!name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) out.add(new GenreItem(name, name));
                    }
                    GENRE_CACHE.put("genres", new ArrayList<>(out));
                    MangaCoroutines.main(() -> cb.onSuccess(out, false));
                } catch (Exception e) {
                    MangaCoroutines.main(() -> cb.onError("Genre Manga Web gagal dibaca"));
                }
            }

            @Override public void onError(String message) {
                MangaCoroutines.main(() -> cb.onError(message));
            }
        });
    }

    @Override public void detail(String slug, Result<MangaPost> cb) {
        String mangaSlug = cleanSlug(slug);
        MangaPost cached = DETAIL_CACHE.get(mangaSlug);
        if (cached != null) {
            cb.onSuccess(cached, false);
            return;
        }
        loadDetailBundle(mangaSlug, new Result<DetailBundle>() {
            @Override public void onSuccess(DetailBundle data, boolean hasNext) {
                cb.onSuccess(data.detail, false);
            }

            @Override public void onError(String message) {
                cb.onError(message);
            }
        });
    }

    @Override public void chapters(String slug, Result<ArrayList<MangaChapter>> cb) {
        String mangaSlug = cleanSlug(slug);
        ArrayList<MangaChapter> cached = CHAPTER_CACHE.get(mangaSlug);
        if (cached != null) {
            cb.onSuccess(new ArrayList<>(cached), false);
            return;
        }
        loadDetailBundle(mangaSlug, new Result<DetailBundle>() {
            @Override public void onSuccess(DetailBundle data, boolean hasNext) {
                cb.onSuccess(new ArrayList<>(data.chapters), false);
            }

            @Override public void onError(String message) {
                cb.onError(message);
            }
        });
    }

    @Override public void pages(String slug, float index, Result<ArrayList<String>> cb) {
        String inputSlug = cleanSlug(slug);
        String mangaSlug = inputSlug;
        String chapterSlug = CHAPTER_SLUGS.get(chapterKey(mangaSlug, index));
        if (chapterSlug == null || chapterSlug.trim().isEmpty()) {
            int marker = inputSlug.lastIndexOf("-chapter-");
            if (marker > 0) {
                chapterSlug = inputSlug;
                mangaSlug = inputSlug.substring(0, marker);
            }
        }
        if (chapterSlug == null || chapterSlug.trim().isEmpty()) chapterSlug = mangaSlug + "-chapter-" + MangaChapter.formatIndex(index).replace('.', '-');
        String cacheKey = chapterKey(mangaSlug, index);
        ArrayList<String> cached = PAGE_CACHE.get(cacheKey);
        if (cached != null) {
            cb.onSuccess(new ArrayList<>(cached), false);
            return;
        }
        String readerUrl = base() + "/read/" + pathSegment(mangaSlug) + "/" + pathSegment(chapterSlug);
        String finalMangaSlug = mangaSlug;
        String finalChapterSlug = chapterSlug;
        requestRscObject(readerUrl, readerStateTree(finalMangaSlug, finalChapterSlug), base() + "/manga/" + pathSegment(finalMangaSlug), new String[]{"initialData"}, "Reader Manga Web gagal dibaca", new RscObjectCallback() {
            @Override public void onSuccess(RscDocument document, JsonObject wrapper) {
                try {
                    JsonElement initialElement = wrapper.has("initialData") ? document.resolve(wrapper.get("initialData")) : null;
                    if (initialElement == null || !initialElement.isJsonObject()) {
                        MangaCoroutines.main(() -> cb.onError("Reader Manga Web gagal dibaca"));
                        return;
                    }
                    JsonObject initialData = initialElement.getAsJsonObject();
                    JsonElement chapterElement = initialData.has("chapter") ? document.resolve(initialData.get("chapter")) : null;
                    JsonObject chapter = chapterElement != null && chapterElement.isJsonObject() ? chapterElement.getAsJsonObject() : null;
                    if (chapter == null) {
                        MangaCoroutines.main(() -> cb.onError("Chapter Manga Web kosong"));
                        return;
                    }
                    ArrayList<String> out = new ArrayList<>();
                    LinkedHashSet<String> seen = new LinkedHashSet<>();
                    JsonArray images = document.array(chapter, "images");
                    for (JsonElement image : images) {
                        if (image == null || image.isJsonNull()) continue;
                        String imageUrl;
                        try {
                            imageUrl = image.getAsString().trim();
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (!imageUrl.startsWith("http") || !seen.add(imageUrl)) continue;
                        String proxyUrl = proxyImageUrl(imageUrl);
                        out.add(proxyUrl);
                        MangaImageLoader.registerImageReferer(proxyUrl, base() + "/");
                    }
                    if (out.isEmpty()) {
                        MangaCoroutines.main(() -> cb.onError("Gambar chapter Manga Web kosong"));
                        return;
                    }
                    PAGE_CACHE.put(cacheKey, new ArrayList<>(out));
                    MangaCoroutines.main(() -> cb.onSuccess(out, false));
                } catch (Exception e) {
                    MangaCoroutines.main(() -> cb.onError("Reader Manga Web gagal dibaca"));
                }
            }

            @Override public void onError(String message) {
                MangaCoroutines.main(() -> cb.onError(message));
            }
        });
    }

    private void loadDetailBundle(String slug, Result<DetailBundle> cb) {
        if (slug.isEmpty()) {
            cb.onError("Slug Manga Web kosong");
            return;
        }
        MangaPost detailCached = DETAIL_CACHE.get(slug);
        ArrayList<MangaChapter> chaptersCached = CHAPTER_CACHE.get(slug);
        if (detailCached != null && chaptersCached != null) {
            cb.onSuccess(new DetailBundle(detailCached, new ArrayList<>(chaptersCached)), false);
            return;
        }
        String detailUrl = base() + "/manga/" + pathSegment(slug);
        requestRscObject(detailUrl, detailStateTree(slug), base() + "/manga", new String[]{"_id", "slug", "chapters"}, "Detail Manga Web gagal dibaca", new RscObjectCallback() {
            @Override public void onSuccess(RscDocument document, JsonObject payload) {
                try {
                    MangaPost detail = parseDetailPost(payload, document);
                    ArrayList<MangaChapter> chapters = parseChapters(slug, payload, document);
                    DETAIL_CACHE.put(slug, detail);
                    CHAPTER_CACHE.put(slug, new ArrayList<>(chapters));
                    MangaCoroutines.main(() -> cb.onSuccess(new DetailBundle(detail, chapters), false));
                } catch (Exception e) {
                    MangaCoroutines.main(() -> cb.onError("Detail Manga Web gagal dibaca"));
                }
            }

            @Override public void onError(String message) {
                MangaCoroutines.main(() -> cb.onError(message));
            }
        });
    }

    private MangaPost parseListPost(JsonObject item, RscDocument document) {
        String genre = joinStrings(document.array(item, "genres"));
        MangaPost post = new MangaPost(
                getString(item, "slug"),
                getString(item, "title"),
                getString(item, "coverImage"),
                getString(item, "author"),
                getString(item, "status"),
                getString(item, "synopsis"),
                genre,
                getString(item, "type"),
                getString(item, "last_chapter"),
                firstNonEmpty(getString(item, "last_update"), getString(item, "lastUpdated"))
        ).withSource(SOURCE_ID, SOURCE_LABEL);
        post.totalChapters = getInt(item, "chapter_count", 0);
        return post;
    }

    private MangaPost parseDetailPost(JsonObject item, RscDocument document) {
        String genres = joinStrings(document.array(item, "genres"));
        if (genres.isEmpty()) genres = joinStrings(document.array(item, "tags"));
        String themes = joinStrings(document.array(item, "themes"));
        String demographics = joinStrings(document.array(item, "demographics"));
        String author = getString(item, "author");
        String illustrator = getString(item, "illustrator");
        String type = getString(item, "type");
        String status = getString(item, "status");
        MangaPost post = new MangaPost(
                getString(item, "slug"),
                getString(item, "title"),
                getString(item, "coverImage"),
                author,
                status,
                normalizeWhitespace(getString(item, "synopsis")),
                genres,
                type,
                getString(item, "last_chapter"),
                firstNonEmpty(getString(item, "last_update"), getString(item, "lastUpdated"))
        ).withSource(SOURCE_ID, SOURCE_LABEL);
        post.totalChapters = Math.max(getInt(item, "chapter_count", 0), document.array(item, "chapters").size());
        post.info = buildDetailInfo(item, author, illustrator, type, status, genres, themes, demographics);
        return post;
    }


    private static String buildDetailInfo(JsonObject item, String author, String illustrator, String type, String status, String genres, String themes, String demographics) {
        String nativeTitle = normalizeWhitespace(getString(item, "nativeTitle"));
        String creator = formatPair("Author", author, "Illustrator", illustrator);
        String themeLine = formatPair("Tema", themes, "Demografi", demographics);
        String rating = getNumberString(item, "rating");
        String views = getNumberString(item, "views");
        String ratingLine = formatPair("Rating", rating, "Views", views);
        ArrayList<String> rows = new ArrayList<>();
        rows.add(nativeTitle.isEmpty() ? "Judul Asli: " + getString(item, "title") : "Judul Asli: " + nativeTitle);
        rows.add(creator);
        rows.add(type.isEmpty() ? "" : "Tipe: " + type);
        rows.add(status.isEmpty() ? "" : "Status: " + status);
        rows.add(genres.isEmpty() ? "" : "Genre: " + genres);
        rows.add(themeLine);
        rows.add(ratingLine);
        rows.add("Sumber: " + SOURCE_LABEL);
        return String.join("||", rows);
    }

    private static String formatPair(String firstLabel, String firstValue, String secondLabel, String secondValue) {
        String first = normalizeWhitespace(firstValue);
        String second = normalizeWhitespace(secondValue);
        if (first.isEmpty() && second.isEmpty()) return "";
        if (first.isEmpty()) return secondLabel + ": " + second;
        if (second.isEmpty() || first.equalsIgnoreCase(second)) return firstLabel + ": " + first;
        return firstLabel + ": " + first + " | " + secondLabel + ": " + second;
    }

    private static String getNumberString(JsonObject object, String key) {
        try {
            if (object == null || !object.has(key) || object.get(key).isJsonNull()) return "";
            JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return "";
            double number = value.getAsDouble();
            if (Math.rint(number) == number) return Long.toString((long) number);
            return Double.toString(number);
        } catch (Exception e) {
            return "";
        }
    }

    private ArrayList<MangaChapter> parseChapters(String mangaSlug, JsonObject item, RscDocument document) {
        ArrayList<MangaChapter> out = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        JsonArray chapters = document.array(item, "chapters");
        for (JsonElement element : chapters) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject chapterObject = element.getAsJsonObject();
            String title = getString(chapterObject, "title");
            String chapterSlug = cleanSlug(getString(chapterObject, "slug"));
            float index = parseChapterIndex(title, chapterSlug);
            if (chapterSlug.isEmpty() || index < 0f) continue;
            if (!seen.add(chapterSlug)) continue;
            MangaChapter chapter = new MangaChapter(chapterSlug, index, chapterSuffix(title, index), "");
            chapter.chapterId = chapterSlug;
            out.add(chapter);
            CHAPTER_SLUGS.put(chapterKey(mangaSlug, index), chapterSlug);
        }
        return out;
    }

    private void requestRscObject(String businessUrl, String stateTree, String referer, String[] keys, String errorMessage, RscObjectCallback cb) {
        requestRscObject(businessUrl, stateTree, referer, keys, errorMessage, 0, cb);
    }

    private void requestRscObject(String businessUrl, String stateTree, String referer, String[] keys, String errorMessage, int attempt, RscObjectCallback cb) {
        requestRsc(businessUrl, stateTree, referer, new RscCallback() {
            @Override public void onSuccess(RscDocument document) {
                JsonObject object = document.findObject(keys);
                if (object != null) {
                    cb.onSuccess(document, object);
                    return;
                }
                if (attempt < 2) {
                    requestRscObject(businessUrl, stateTree, referer, keys, errorMessage, attempt + 1, cb);
                    return;
                }
                cb.onError(errorMessage);
            }

            @Override public void onError(String message) {
                String safeMessage = message == null ? "" : message.trim();
                if (attempt < 2 && !safeMessage.contains("tidak ditemukan") && !CloudflareHelper.isCloudflareRequiredMessage(safeMessage)) {
                    requestRscObject(businessUrl, stateTree, referer, keys, errorMessage, attempt + 1, cb);
                    return;
                }
                cb.onError(safeMessage.isEmpty() ? errorMessage : safeMessage);
            }
        });
    }

    private void requestRsc(String businessUrl, String stateTree, String referer, RscCallback cb) {
        String requestUrl = businessUrl + (businessUrl.contains("?") ? "&" : "?") + "_rsc=" + rscToken();
        Request request = new Request.Builder()
                .url(requestUrl)
                .header("RSC", "1")
                .header("Next-Router-State-Tree", encode(stateTree))
                .header("Accept", "*/*")
                .header("Accept-Language", "id-ID,id;q=0.9")
                .header("User-Agent", USER_AGENT)
                .header("Sec-CH-UA-Platform", "\"Android\"")
                .header("Sec-CH-UA", "\"Not=A?Brand\";v=\"99\", \"Brave\";v=\"151\", \"Chromium\";v=\"151\"")
                .header("Sec-CH-UA-Mobile", "?1")
                .header("Sec-GPC", "1")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .header("Priority", "u=1, i")
                .header("Cache-Control", "no-cache")
                .header("Referer", referer == null || referer.trim().isEmpty() ? base() + "/manga" : referer)
                .build();
        CloudflareHelper.enqueue(CLIENT, request, SOURCE_LABEL, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                cb.onError(CloudflareHelper.errorMessage(e));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) {
                    cb.onError("HTTP " + response.code());
                    return;
                }
                if (body.contains("NEXT_NOT_FOUND")) {
                    cb.onError("Manga atau chapter tidak ditemukan");
                    return;
                }
                RscDocument document = RscDocument.parse(body);
                if (document.roots.isEmpty()) {
                    cb.onError("Response Next.js Manga Web kosong");
                    return;
                }
                cb.onSuccess(document);
            }
        });
    }

    private static String buildListUrl(int page, String sort, String query, String genre) {
        ArrayList<String> params = new ArrayList<>();
        String cleanQuery = query == null ? "" : query.trim();
        String cleanGenre = extractGenre(genre);
        if (!cleanQuery.isEmpty()) params.add("q=" + encodeQuery(cleanQuery));
        if (!cleanGenre.isEmpty()) params.add("genre=" + encodeQuery(cleanGenre));
        if (cleanQuery.isEmpty()) params.add("order=" + encodeQuery(mapSort(sort)));
        if (page > 1) params.add("page=" + page);
        StringBuilder url = new StringBuilder(base()).append("/manga");
        if (!params.isEmpty()) url.append('?').append(String.join("&", params));
        return url.toString();
    }

    private static String mapSort(String sort) {
        String value = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        if (value.equals("popular") || value.equals("popularity") || value.equals("views")) return "popular";
        if (value.equals("az") || value.equals("a-z") || value.equals("title")) return "az";
        if (value.equals("za") || value.equals("z-a") || value.equals("titlereverse")) return "za";
        return "latest";
    }

    private static String extractGenre(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        for (String part : raw.split("\\|")) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("type:") || lower.startsWith("status:")) continue;
            return value;
        }
        return "";
    }

    private static String listStateTree() {
        return "[\"\",{\"children\":[\"manga\",{\"children\":[\"__PAGE__\",{},null,\"refetch\"]},null,null]},null,null]";
    }

    private static String detailStateTree(String slug) {
        return "[\"\",{\"children\":[\"manga\",{\"children\":[[\"slug\"," + quote(slug) + ",\"d\"],{\"children\":[\"__PAGE__\",{},null,null]},null,\"refetch\"]},null,null]},null,null]";
    }

    private static String readerStateTree(String mangaSlug, String chapterSlug) {
        return "[\"\",{\"children\":[\"read\",{\"children\":[[\"slug\"," + quote(mangaSlug) + ",\"d\"],{\"children\":[[\"chapterSlug\"," + quote(chapterSlug) + ",\"d\"],{\"children\":[\"__PAGE__\",{},null,null]},null,null]},null,null]},null,\"refetch\"]},null,null]";
    }

    private static String quote(String value) {
        return new JsonPrimitive(value == null ? "" : value).toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static String encodeQuery(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }


    private static String proxyImageUrl(String imageUrl) {
        String value = imageUrl == null ? "" : imageUrl.trim();
        if (value.isEmpty()) return value;
        if (value.startsWith(IMAGE_PROXY + "?url=")) return value;
        return IMAGE_PROXY + "?url=" + encodeQuery(value);
    }

    private static String pathSegment(String value) {
        String clean = cleanSlug(value);
        return clean.replace("%", "%25").replace("/", "%2F").replace("?", "%3F").replace("#", "%23").replace(" ", "%20");
    }

    private static String rscToken() {
        String value = Long.toString(System.nanoTime(), 36);
        return value.length() <= 5 ? value : value.substring(value.length() - 5);
    }

    private static String cleanSlug(String value) {
        if (value == null) return "";
        String clean = value.trim();
        while (clean.startsWith("/")) clean = clean.substring(1);
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        int mangaMarker = clean.indexOf("/manga/");
        if (mangaMarker >= 0) clean = clean.substring(mangaMarker + 7);
        int readMarker = clean.indexOf("/read/");
        if (readMarker >= 0) clean = clean.substring(readMarker + 6);
        int query = clean.indexOf('?');
        if (query >= 0) clean = clean.substring(0, query);
        if (clean.startsWith("manga/")) clean = clean.substring(6);
        return clean;
    }

    private static float parseChapterIndex(String title, String slug) {
        String text = title == null ? "" : title.trim();
        Matcher matcher = CHAPTER_NUMBER.matcher(text);
        if (matcher.find()) {
            try {
                return Float.parseFloat(matcher.group(1).replace(',', '.'));
            } catch (Exception ignored) {
            }
        }
        String source = slug == null ? "" : slug;
        int marker = source.lastIndexOf("-chapter-");
        if (marker >= 0) {
            String tail = source.substring(marker + 9);
            Matcher tailMatcher = Pattern.compile("^(\\d+)(?:-(\\d+))?").matcher(tail);
            if (tailMatcher.find()) {
                try {
                    String whole = tailMatcher.group(1);
                    String decimal = tailMatcher.group(2);
                    return Float.parseFloat(decimal == null || decimal.isEmpty() ? whole : whole + "." + decimal);
                } catch (Exception ignored) {
                }
            }
        }
        return -1f;
    }

    private static String chapterSuffix(String title, float index) {
        String value = normalizeWhitespace(title);
        if (value.isEmpty()) return "";
        value = value.replaceFirst("(?i)^chapter\\s*\\d+(?:[\\.,]\\d+)?\\s*", "");
        value = value.replaceFirst("^[:\\-–—]+\\s*", "").trim();
        return value;
    }

    private static String chapterKey(String mangaSlug, float index) {
        return cleanSlug(mangaSlug) + ":" + MangaChapter.formatIndex(index);
    }

    private static String joinStrings(JsonArray array) {
        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (array != null) {
            for (JsonElement element : array) {
                String value = "";
                if (element == null || element.isJsonNull()) continue;
                try {
                    if (element.isJsonPrimitive()) value = element.getAsString();
                    else if (element.isJsonObject()) value = getString(element.getAsJsonObject(), "name");
                } catch (Exception ignored) {
                }
                value = normalizeWhitespace(value);
                if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) values.add(value);
            }
        }
        return String.join(", ", values);
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String getString(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() && object.get(key).isJsonPrimitive() ? object.get(key).getAsString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }
}
