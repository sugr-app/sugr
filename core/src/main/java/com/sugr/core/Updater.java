package com.sugr.core;

import com.sugr.bridge.Experimental;
import com.sugr.bridge.Json;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Built-in application updates: checks a release endpoint, downloads the new
 * version, verifies it, replaces the installed app and restarts it.
 *
 * <p>Configured entirely from {@code sugr.config.json} - an app that adds
 * {@code "updater": { "endpoints": [...], "pubkey": "..." }} gets working
 * updates with no Java or TypeScript at all, including a native "an update is
 * available" prompt. Apps that want their own UI turn the prompt off and drive
 * {@link #check()} / {@link #downloadAndInstall()} from the frontend through
 * the {@code sugr.updater.*} bridge methods.
 *
 * <p>Nothing here ever runs on the UI thread and nothing here can stop the app
 * from starting: every failure path ends in an {@code sugr://update-error}
 * event and a log line.
 *
 * <p>Experimental: the API shape (and the manifest format behind it) may
 * change in the next minor version.
 *
 * @see <a href="file:../../../../../../docs/guide/updates.md">docs/guide/updates.md</a>
 */
@Experimental
public final class Updater {

    /** Set to {@code 1} to switch updates off entirely - for managed/enterprise deployments. */
    public static final String DISABLE_ENV = "SUGR_DISABLE_UPDATER";

    /**
     * On Windows, a {@code REG_DWORD} of {@code 1} with this name under
     * {@code HKLM\Software\Policies\<identifier>} or the same path in
     * {@code HKCU} also switches updates off - what Group Policy / Intune write.
     */
    public static final String DISABLE_POLICY_VALUE = "DisableUpdates";

    /** Emitted when a check finds a newer release. Payload: {@link UpdateInfo}. */
    public static final String EVENT_AVAILABLE = "sugr://update-available";
    /** Emitted repeatedly during a download. Payload: {@link Progress}. */
    public static final String EVENT_PROGRESS = "sugr://update-progress";
    /** Emitted once an update is verified and staged, ready for {@link #install()}. */
    public static final String EVENT_DOWNLOADED = "sugr://update-downloaded";
    /** Emitted when anything goes wrong. Payload: {@code {code, message}}. */
    public static final String EVENT_ERROR = "sugr://update-error";
    /**
     * Emitted when an update exists but can't be applied automatically.
     * Payload: {@code {version, reason, url, managed}} - {@code managed} is true
     * for an administrator-deployed install, where {@code url} is null because
     * the user isn't expected to update it themselves.
     */
    public static final String EVENT_MANUAL_REQUIRED = "sugr://update-manual-required";

    /** The {@code reason} reported for a per-machine install, which this updater never touches. */
    static final String MANAGED_REASON = "Managed install - updates are delivered by your administrator";

    /** A release newer than what's running. */
    @Experimental
    public record UpdateInfo(String version, String currentVersion, String notes, String pubDate, long size) {

        Json toJson() {
            Map<String, Json> out = new LinkedHashMap<>();
            out.put("version", Json.of(version));
            out.put("currentVersion", Json.of(currentVersion));
            out.put("notes", notes == null ? Json.NULL : Json.of(notes));
            out.put("pubDate", pubDate == null ? Json.NULL : Json.of(pubDate));
            out.put("size", Json.of(size));
            return Json.object(out);
        }
    }

    /** Download progress. {@code total} is -1 and {@code percent} 0 when the size isn't known. */
    @Experimental
    public record Progress(long downloaded, long total, double percent) {

        static Progress of(long downloaded, long total) {
            double percent = total > 0 ? Math.min(100.0, (downloaded * 100.0) / total) : 0.0;
            return new Progress(downloaded, total, Math.round(percent * 10.0) / 10.0);
        }

        Json toJson() {
            Map<String, Json> out = new LinkedHashMap<>();
            out.put("downloaded", Json.of(downloaded));
            out.put("total", Json.of(total));
            out.put("percent", Json.of(percent));
            return Json.object(out);
        }
    }

    /** How a {@link Updater} pushes events at whatever is listening - {@link Window} bridges these to JS. */
    interface EventSink {
        void emit(String event, String payloadJson);
    }

    /** A release a check found and verified the manifest of - available, not yet downloaded. */
    private record Available(UpdateManifest manifest, UpdateManifest.Artifact artifact, UpdateInfo info,
                             UpdateInstaller.Strategy strategy) {
    }

    /** A release whose artifact has been downloaded, verified and unpacked beside the install. */
    private record Staged(Available release, Path stagingDir) {
    }

    private static volatile Updater shared;
    private static volatile boolean sharedResolved;

    private final AppMetadata metadata;
    private final AppMetadata.UpdaterSettings settings;
    private final UpdateDownloader http;
    private final List<EventSink> sinks = new CopyOnWriteArrayList<>();
    private final Executor executor;

    /**
     * Two separate pieces of state, because "a newer version exists" and "a
     * newer version is ready to install" are different promises: only the
     * second may lead to {@link #install()}.
     */
    private volatile Available available;
    private volatile Staged staged;
    private CompletableFuture<UpdateInfo> checkInFlight;
    private CompletableFuture<Void> downloadInFlight;

    private Updater(AppMetadata metadata, AppMetadata.UpdaterSettings settings) {
        this(metadata, settings, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sugr-updater");
            thread.setDaemon(true);
            return thread;
        }));
    }

    /** Visible for testing - lets a test hold work in a queue to observe in-flight state. */
    Updater(AppMetadata metadata, AppMetadata.UpdaterSettings settings, Executor executor) {
        this.metadata = metadata;
        this.settings = settings;
        this.executor = executor;
        this.http = new UpdateDownloader(
                effectiveAllowInsecure(settings.allowInsecure(), AppInstall.current().layout()));
    }

    /**
     * {@code allowInsecure} only ever takes effect outside a packaged build.
     * A shipped app accepting plain HTTP would let anyone on the network pin
     * its users to an old release, and a config flag left on from local
     * testing is exactly how that would ship - so packaged builds ignore it.
     */
    static boolean effectiveAllowInsecure(boolean configured, AppInstall.Layout layout) {
        if (!configured) {
            return false;
        }
        if (layout != AppInstall.Layout.UNPACKAGED) {
            System.err.println("[sugr] ignoring \"allowInsecure\" - it only applies to unpackaged/dev runs.");
            return false;
        }
        return true;
    }

    /**
     * The updater for this app, or {@code null} when updates are unavailable -
     * not configured, running under {@code sugr dev}, not a packaged binary, or
     * disabled by {@link #DISABLE_ENV} or the {@link #DISABLE_POLICY_VALUE}
     * policy. Callers treat null as "the feature is off", which is why every
     * reason lands in the same place.
     */
    static Updater shared() {
        if (!sharedResolved) {
            synchronized (Updater.class) {
                if (!sharedResolved) {
                    shared = create();
                    sharedResolved = true;
                }
            }
        }
        return shared;
    }

    private static Updater create() {
        if ("1".equals(System.getenv(DISABLE_ENV))) {
            return null;
        }
        AppMetadata metadata = AppMetadata.get();
        AppMetadata.UpdaterSettings settings = metadata.updater();
        if (settings == null) {
            return null;
        }
        if (!settings.isUsable()) {
            System.err.println("[sugr] updates are disabled: " + settings.unusableReason());
            return null;
        }
        if (AppInstall.isDevMode()) {
            System.out.println("[sugr] updates are disabled under `sugr dev`.");
            return null;
        }
        if (AppInstall.current().layout() == AppInstall.Layout.UNPACKAGED) {
            System.out.println("[sugr] updates are disabled - this isn't a packaged app.");
            return null;
        }
        // Last, because it costs reg.exe round trips and every check above is free.
        if (Os.isWindows() && disabledByPolicy(metadata.identifier())) {
            System.out.println("[sugr] updates are disabled by policy (" + DISABLE_POLICY_VALUE + ").");
            return null;
        }
        return new Updater(metadata, settings);
    }

    /**
     * Whether an administrator switched updates off for {@code identifier}.
     * Machine policy (HKLM) and user policy (HKCU) are both honoured; either
     * one set is enough. The two keys are read in parallel so startup pays one
     * round trip, not two.
     */
    static boolean disabledByPolicy(String identifier) {
        String key = "\\Software\\Policies\\" + identifier;
        CompletableFuture<Map<String, WindowsRegistry.Value>> machine =
                CompletableFuture.supplyAsync(() -> WindowsRegistry.readKey("HKLM" + key));
        Map<String, WindowsRegistry.Value> user = WindowsRegistry.readKey("HKCU" + key);
        return policyDisables(machine.join()) || policyDisables(user);
    }

    /** Visible for testing - the decision for one policy key's values. */
    static boolean policyDisables(Map<String, WindowsRegistry.Value> values) {
        WindowsRegistry.Value value = values.get(DISABLE_POLICY_VALUE.toLowerCase(java.util.Locale.ROOT));
        return value != null && value.isEnabledDword();
    }

    /** The version this app is running, as embedded at package time. */
    public String currentVersion() {
        return metadata.version().toString();
    }

    /** The release channel this app follows ({@code "stable"} unless configured otherwise). */
    public String channel() {
        return settings.channel();
    }

    /** Whether {@code sugr.config.json} asked for an automatic check shortly after startup. */
    boolean shouldAutoCheck() {
        return settings.autoCheck();
    }

    /** Whether the framework should show its own "update available" dialog rather than leaving it to the app. */
    boolean shouldPrompt() {
        return settings.prompt();
    }

    void addEventSink(EventSink sink) {
        sinks.add(sink);
    }

    /**
     * Asks the configured endpoints whether a newer release exists.
     *
     * <p>Concurrent calls share one request: a frontend that checks on mount
     * while the automatic startup check is already running should produce one
     * network round trip, not two. A check while a download is running answers
     * with the release being downloaded rather than fetching a manifest that
     * could swap the target out from under it.
     *
     * @return the release, or {@code null} when the app is already current
     */
    public synchronized CompletableFuture<UpdateInfo> check() {
        if (checkInFlight != null && !checkInFlight.isDone()) {
            return checkInFlight;
        }
        if (downloadInFlight != null && !downloadInFlight.isDone()) {
            Available current = available;
            return CompletableFuture.completedFuture(current == null ? null : current.info());
        }
        checkInFlight = CompletableFuture.supplyAsync(this::checkNow, executor);
        return checkInFlight;
    }

    /**
     * Downloads and verifies the release found by the last {@link #check()},
     * emitting {@link #EVENT_PROGRESS} as it goes. Completes when the update is
     * staged and ready to install.
     */
    public synchronized CompletableFuture<Void> download() {
        if (downloadInFlight != null && !downloadInFlight.isDone()) {
            return downloadInFlight;
        }
        downloadInFlight = CompletableFuture.runAsync(this::downloadNow, executor);
        return downloadInFlight;
    }

    /** {@link #check()}, then {@link #download()}, then {@link #install()} - the whole thing. */
    public CompletableFuture<Void> downloadAndInstall() {
        return check().thenCompose(info -> {
            if (info == null) {
                return CompletableFuture.completedFuture(null);
            }
            return download().thenRun(this::install);
        });
    }

    /**
     * Applies the staged update and restarts the app. Does not return on
     * success - the process is replaced.
     *
     * @throws IllegalStateException if nothing has been downloaded yet
     */
    public void install() {
        applyStagedUpdate();
        Relauncher.relaunch();
    }

    /**
     * The half of {@link #install()} that can fail: swaps the staged version
     * into place without restarting. Split out so the bridge can report the
     * outcome to JS <em>before</em> the process exits.
     *
     * @throws IllegalStateException if nothing has been downloaded yet, or the swap failed
     */
    void applyStagedUpdate() {
        Staged current = staged;
        if (current == null) {
            throw new IllegalStateException("No update has been downloaded - call check() and download() first");
        }
        try {
            UpdateInstaller.applyStaged(AppInstall.current().target());
        } catch (IOException e) {
            fail("INSTALL_FAILED", "Couldn't apply the update: " + e.getMessage());
            throw new IllegalStateException("Couldn't apply the update", e);
        }
        staged = null;
        UpdateInstaller.deleteRecursively(current.stagingDir());
    }

    /**
     * {@link #check()} then {@link #download()} then {@link #applyStagedUpdate()},
     * without the restart. Completes {@code true} when an update was applied
     * and the caller should relaunch, {@code false} when there was nothing new.
     */
    CompletableFuture<Boolean> downloadAndApply() {
        return check().thenCompose(info -> {
            if (info == null) {
                return CompletableFuture.completedFuture(false);
            }
            return download().thenApply(ignored -> {
                applyStagedUpdate();
                return true;
            });
        });
    }

    /** True once an update has been downloaded, verified and staged - not merely found by a check. */
    public boolean isReadyToInstall() {
        return staged != null;
    }

    // ---- the work, all on the updater thread ----

    private UpdateInfo checkNow() {
        SemVer current = metadata.version();
        String target = UpdateManifest.currentTarget();
        List<String> failures = new ArrayList<>();

        for (String rawEndpoint : settings.endpoints()) {
            String url = expandPlaceholders(rawEndpoint, current, target, settings.channel());
            try {
                String body = http.fetchManifest(URI.create(url));
                if (body == null) {
                    return null; // 204 - the server says we're current.
                }
                UpdateManifest manifest = UpdateManifest.parse(body, target);
                return evaluate(manifest, current, target);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (RuntimeException | IOException e) {
                failures.add(url + ": " + e.getMessage());
            }
        }

        // Every endpoint failed. This is routine (the user is offline) so it's
        // an event, not an exception - the app carries on regardless.
        fail("CHECK_FAILED", "Couldn't check for updates - " + String.join("; ", failures));
        return null;
    }

    /** Visible for testing - decides what a fetched manifest means for this app, and records it. */
    UpdateInfo evaluate(UpdateManifest manifest, SemVer current, String target) {
        return evaluate(manifest, current, target, UpdateInstaller.strategyFor(AppInstall.current()));
    }

    UpdateInfo evaluate(UpdateManifest manifest, SemVer current, String target, UpdateInstaller.Strategy strategy) {
        if (manifest.artifactFor(target) == null) {
            available = null;
            return null;
        }
        // Before anything in the manifest is believed - its version included,
        // since an old release relabelled as new is exactly what this stops -
        // check that the developer's key signed it, for this app and target.
        try {
            UpdateVerifier.verifyManifest(manifest, target, metadata.identifier(), settings.pubkey());
        } catch (UpdateVerifier.VerificationException e) {
            available = null;
            fail(e.code(), e.getMessage());
            return null;
        }

        // Only touch the persisted install id when a staged rollout actually
        // needs a bucket - a 100% release is the same answer for everyone.
        int bucket = manifest.rolloutPercentage() >= 100 ? 0 : rolloutBucket();
        UpdateManifest.Verdict verdict = manifest.appliesTo(current, target, settings.allowDowngrade(), bucket);
        if (verdict != UpdateManifest.Verdict.APPLIES) {
            if (verdict == UpdateManifest.Verdict.BELOW_MIN_VERSION) {
                emitManualRequired(manifest.version().toString(),
                        "This version is too old to update automatically",
                        downloadUrlFor(manifest.artifactFor(target)), false);
            }
            available = null;
            return null;
        }

        UpdateManifest.Artifact artifact = manifest.artifactFor(target);
        UpdateInfo info = new UpdateInfo(manifest.version().toString(), current.toString(),
                manifest.notes(), manifest.pubDate(), artifact.size());
        Available found = new Available(manifest, artifact, info, strategy);
        available = found;

        // A staged download of some other release is stale now; one of this
        // same release is still good and stays ready to install.
        Staged ready = staged;
        if (ready != null && !ready.release().info().version().equals(info.version())) {
            staged = null;
            UpdateInstaller.deleteRecursively(ready.stagingDir());
        }

        if (strategy != UpdateInstaller.Strategy.BINARY_SWAP) {
            emitManualRequired(found);
            return info;
        }
        emit(EVENT_AVAILABLE, info.toJson().encode());
        return info;
    }

    private void downloadNow() {
        // Captured once: everything below works on this release even if a
        // later check records a different one.
        Available current = available;
        if (current == null) {
            throw new IllegalStateException("No update to download - call check() first");
        }
        if (current.strategy() != UpdateInstaller.Strategy.BINARY_SWAP) {
            emitManualRequired(current);
            throw new IllegalStateException(current.strategy() == UpdateInstaller.Strategy.MANAGED
                    ? MANAGED_REASON
                    : "This installation can't be updated in place");
        }
        Staged ready = staged;
        if (ready != null && ready.release() == current) {
            emit(EVENT_DOWNLOADED, current.info().toJson().encode());
            return;
        }

        UpdateManifest.Artifact artifact = current.artifact();
        Path stagingDir = AppPaths.cacheDir(metadata.identifier())
                .resolve("updates")
                .resolve(current.info().version());
        Path file = stagingDir.resolve("artifact.bin");
        try {
            http.download(URI.create(artifact.url()), file, artifact.size(),
                    (downloaded, total) -> emit(EVENT_PROGRESS, Progress.of(downloaded, total).toJson().encode()));

            UpdateVerifier.verify(file, artifact.sha256(), artifact.size(), artifact.signature(), settings.pubkey());

            UpdateInstaller.stage(AppInstall.current().target(), file, artifact.format());
            staged = new Staged(current, stagingDir);
            emit(EVENT_DOWNLOADED, current.info().toJson().encode());
        } catch (UpdateVerifier.VerificationException e) {
            // A file that fails verification is never kept: leaving it around
            // risks a later run treating it as a resumable partial download.
            UpdateInstaller.deleteRecursively(stagingDir);
            available = null;
            fail(e.code(), e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Update download interrupted", e);
        } catch (IOException e) {
            fail("DOWNLOAD_FAILED", "Couldn't download the update: " + e.getMessage());
            throw new IllegalStateException("Couldn't download the update", e);
        }
    }

    // ---- staged rollout ----

    /**
     * A stable 0-99 bucket for this installation, derived from a random id
     * persisted in the app's data directory.
     *
     * <p>Stability is the whole point. A bucket recomputed per check (from the
     * clock, or a fresh random) would move an installation in and out of a
     * staged rollout between checks, so a user could be offered an update, then
     * not, then offered it again. Persisting the id means a 10% rollout reaches
     * the same 10% of users every time.
     */
    int rolloutBucket() {
        try {
            Path idFile = AppPaths.dataDir(metadata.identifier()).resolve("install-id");
            String id;
            if (Files.isRegularFile(idFile)) {
                id = Files.readString(idFile).trim();
            } else {
                id = UUID.randomUUID().toString();
                Files.writeString(idFile, id);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(((digest[0] & 0xff) << 8) | (digest[1] & 0xff), 100);
        } catch (Exception e) {
            // Without a stable id the safest default is "always included" -
            // failing closed would silently strand this user on an old version.
            return 0;
        }
    }

    /**
     * Substitutes the values an endpoint may want to route on. Both
     * {@code currentVersion} and Tauri's {@code current_version} spelling are
     * accepted so an existing update server works unchanged; {@code channel}
     * is how a configured {@code "channel"} reaches the server at all (e.g.
     * {@code https://host/latest-{{channel}}.json}).
     */
    static String expandPlaceholders(String endpoint, SemVer current, String target, String channel) {
        return endpoint
                .replace("{{channel}}", channel)
                .replace("{{target}}", target)
                .replace("{{os}}", UpdateManifest.currentOs())
                .replace("{{arch}}", UpdateManifest.currentArch())
                .replace("{{currentVersion}}", current.toString())
                .replace("{{current_version}}", current.toString());
    }

    // ---- events ----

    private void emitManualRequired(Available current) {
        boolean managed = current.strategy() == UpdateInstaller.Strategy.MANAGED;
        emitManualRequired(current.info().version(),
                managed ? MANAGED_REASON : "This installation can't be updated in place",
                managed ? null : downloadUrlFor(current.artifact()),
                managed);
    }

    /**
     * Only ever reached with a manifest whose signature has been checked, so a
     * URL handed to the frontend (and from there, likely, to the user's
     * browser) is one the developer actually published.
     */
    private void emitManualRequired(String version, String reason, String url, boolean managed) {
        Map<String, Json> payload = new LinkedHashMap<>();
        payload.put("version", Json.of(version));
        payload.put("reason", Json.of(reason));
        payload.put("url", url == null ? Json.NULL : Json.of(url));
        payload.put("managed", Json.of(managed));
        emit(EVENT_MANUAL_REQUIRED, Json.object(payload).encode());
    }

    /** The full installer when one was published, else the update artifact itself. */
    private static String downloadUrlFor(UpdateManifest.Artifact artifact) {
        return artifact.installerUrl() != null ? artifact.installerUrl() : artifact.url();
    }

    private void fail(String code, String message) {
        System.err.println("[sugr] " + code + ": " + message);
        Map<String, Json> payload = new LinkedHashMap<>();
        payload.put("code", Json.of(code));
        payload.put("message", Json.of(message));
        emit(EVENT_ERROR, Json.object(payload).encode());
    }

    private void emit(String event, String payloadJson) {
        for (EventSink sink : sinks) {
            try {
                sink.emit(event, payloadJson);
            } catch (RuntimeException e) {
                System.err.println("[sugr] an updater event listener threw: " + e);
            }
        }
    }

    /** Whether the release found by the last check can be downloaded and installed by this process. */
    boolean canSelfUpdate() {
        Available current = available;
        return current != null && current.strategy() == UpdateInstaller.Strategy.BINARY_SWAP;
    }

    /** The release found by the last check - available, not necessarily downloaded. */
    UpdateInfo availableInfo() {
        Available current = available;
        return current == null ? null : current.info();
    }
}
