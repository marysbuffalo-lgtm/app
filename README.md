# Kiwix RAG

An offline Android app that answers questions from your own Kiwix `.zim` files using a multimodal model running on the phone. No network permission, no accounts, no cloud, no embedding model and no vector database.

Target device: Android 14 or newer, arm64-v8a (built for a Samsung S24 Ultra with 12 GB of RAM).

## How it works

```
question
  -> keyword extraction (stop words removed)
  -> libzim search over every loaded ZIM: title suggestions + Xapian full-text, merged by rank fusion
  -> top 3 to 5 articles read straight from the ZIM
  -> Jsoup: clean text (keyword-relevant passages first) + <img> tags resolved to ZIM entry paths
  -> up to 2 photos decoded from Entry.getItem().getData(), downscaled to 512 px, thumbnails kept for the UI
  -> prompt trimmed to fit a 4096 token context (text, photos and a reserved answer budget)
  -> MediaPipe (.task) or LiteRT-LM (.litertlm) generates the answer from that context only
  -> bitmaps recycled, answer shown with expandable "Sources & Retrieved Photos"
```

The system prompt tells the model to answer only from the retrieved context and to cite `[S1]` style tags plus a final `Sources:` list with the exact ZIM archive file name, article title, image file name and caption. The Sources card under every answer is built from the real retrieval results, so it is correct even if the model cites badly.

## Dependencies (Maven)

| Library | Coordinates | Repository |
|---|---|---|
| MediaPipe LLM Inference | `com.google.mediapipe:tasks-genai:0.10.35` and `tasks-core:0.10.35` | Google Maven (`google()`) |
| LiteRT-LM | `com.google.ai.edge.litertlm:litertlm-android:0.19.1` | Google Maven (`google()`) |
| libzim bindings | `org.kiwix:libkiwix:2.7.0` | Maven Central |
| HTML parsing | `org.jsoup:jsoup:1.21.2` | Maven Central |

There is no artifact called `org.kiwix:libzim`. The libzim Java classes (`org.kiwix.libzim.Archive`, `Searcher`, `Query`, `SuggestionSearcher`) and the native `libzim.so` ship inside `org.kiwix:libkiwix`. That library is GPLv3, so if you ever distribute this app, the app must be GPLv3 too. Personal use is fine.

## Build and install

Needs JDK 17 or newer and the Android SDK with platform 36.

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk (about 180 MB, three engines)
./gradlew testDebugUnitTest      # 46 tests
```

Install: copy the APK to the phone, open it, allow installs from that source. Or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## First run

1. Open the app, tap **Setup**.
2. **Grant all files access**. This opens Android's settings page for the app. It is what lets libzim open 70 GB of ZIM files in place.
3. **Choose ZIM folder**. Every `.zim` in that folder (two levels deep) is opened. The badge shows the count and total size.
4. **Choose model file**. Pick a `.task` or `.litertlm` file already on the phone. It is opened by path and never copied.
5. Ask a question.

The app remembers both choices and reloads them on the next start. Without a model, questions still return the matching Kiwix pages, which is handy for checking the library on its own.

### Models

- `.task` files go to MediaPipe (Gemma 3n multimodal bundles).
- `.litertlm` files go to LiteRT-LM (Gemma 3n and Gemma 4 style bundles).
- Accelerator: GPU (default), CPU, or NPU. NPU is only available through LiteRT-LM and needs a supported vendor runtime on the phone. If the chosen accelerator fails, the app falls back automatically and the status line says so.
- If a model has no vision encoder, the app loads it as text only and the status shows "text only".

## Memory safety

- Context is capped at 4096 tokens for prompt, photos and answer together. Article text is trimmed first, photos are dropped last. 700 tokens are reserved for the answer and each photo is budgeted at 256 tokens.
- Photos are decoded with subsampling (the full-size bitmap never exists), scaled to at most 512 px, and recycled in a `finally` block after every question, including on error and on Stop.
- libzim caches are kept small (8 clusters) and shrunk further when Android reports memory pressure.
- Only one question runs at a time. The screen stays on while generating so Android does not suspend inference.
- The app opts into a large heap, but the model and libzim live in native memory, which the Java heap limit does not cover.

## What was actually verified

Verified here, in this repository's tests (46 tests, all passing):

- Keyword extraction, query variants, rank fusion, HTML text selection, noise stripping, image selection, and image path resolution (including `../` above the archive root, spaces and percent-encoding).
- The whole retrieval pipeline running on real entries exported from a ZIM file built with libzim 9.8: title search, full-text search, redirects, caption extraction, photo selection across articles.
- Real image decoding and scaling (Robolectric native graphics): large images land at 512 px, tiny, extreme or corrupt images are rejected.
- The 4096 token budget under several tokenizer ratios, photo dropping, and prompt contents (ZIM name, article, image file, caption).
- Bitmaps are recycled on success, model failure and cancellation. The inference lock serializes questions.
- Real libzim search behaviour, measured with the Python binding of the same C++ library: terms are ANDed, there is no `OR`, stop words are not ignored, stemming and case folding work, title suggestions only match single terms or prefixes. The search strategy is built around those measurements.
- The JNI `SearchIterator` ordering, read from the java-libkiwix source (getters describe the element `next()` returns).
- APK contents: arm64-v8a only, one shared `libc++`, the only permission requested is `MANAGE_EXTERNAL_STORAGE`.

## Not verified (needs your phone)

I could not run the app on a device, so these are untested:

- Loading a real Gemma model through MediaPipe or LiteRT-LM, GPU and NPU delegates, and answer quality.
- The Java side of libzim (`Archive`, `Searcher`, `SuggestionSearcher`) against a real ZIM on Android. The classes compile against the real library and the semantics were checked with the Python binding, but the JNI path itself has not been run.
- Opening a model through `/proc/self/fd/N` when the picked file cannot be mapped to a path. The normal path-based route does not use this.
- NPU on the S24 Ultra, and photo input with `.litertlm` models (the LiteRT-LM call shape compiles against the real API but has not been run).
- Search latency on 70 GB. Title suggestions are fast, full-text queries on very large indexes can take a second or more each. The app runs the strict query first and only runs the single-keyword queries when it needs more results.

If something fails on the device, the status line or the red error text in the chat shows the underlying message.

## Known limits

- Retrieval is keyword based. Questions phrased with words the articles never use can miss. Irregular forms such as leaf and leaves do not stem together.
- ZIMs without a full-text index only match by title.
- Split ZIM files (`.zimaa` and so on) are not opened.
- Photos are decoded from raster formats only. SVG, tiny icons and math images are skipped on purpose.
- LiteRT-LM exposes no tokenizer call, so its token counts are a conservative estimate and prompts are a little shorter than they could be.
- A 4096 token window is small. Long articles are trimmed to the passages that match your keywords, plus the lead.
- If all files access is not granted, the app falls back to Storage Access Framework file descriptors for ZIMs. That path is slower to open and not covered by tests.
- Google Play restricts `MANAGE_EXTERNAL_STORAGE`. Fine for sideloading, not for a Play Store release.

## Layout

```
app/src/main/java/com/kiwixrag/app/
  llm/      LlmEngine, MediaPipeEngine, LiteRtLmEngine, EngineFactory, PromptBuilder
  zim/      ZimLibrary, LibzimLibrary (libzim JNI), HitFusion
  rag/      KeywordExtractor, HtmlExtractor, ImageDecoder, RagPipeline, AnswerRunner
  storage/  PathResolver (picker result to real path), Prefs
  ui/       ChatScreen, ArticleViewer (WebView served from the ZIM), ChatViewModel
app/src/test/   46 tests, fixtures exported from a real ZIM in src/test/resources/zimfixture
```
