package com.sugr.core;

/**
 * The batteries-included update experience: check shortly after startup, ask
 * the user with a native dialog, then download, install and restart.
 *
 * <p>This is what makes {@code "updater": { "endpoints": [...], "pubkey": "..." }}
 * in {@code sugr.config.json} sufficient on its own - an app author writes no
 * Java and no TypeScript and their users still get updates. Apps that want
 * their own in-app UI set {@code "prompt": false} and drive
 * {@code sugr.updater.*} from the frontend; the events fire either way, so a
 * custom UI can also just listen and leave the prompt on.
 */
final class UpdateFlow {

    /**
     * How long after the window is ready to run the first check. Long enough
     * that a cold start isn't competing with the network for a slow disk, short
     * enough that a user who opens the app briefly still gets offered updates.
     */
    private static final long INITIAL_DELAY_MS = 5_000;

    private UpdateFlow() {
    }

    /**
     * Runs the automatic check on a daemon thread. Daemon matters: a check that
     * is still waiting on an unreachable endpoint must never be the reason the
     * process refuses to exit when the user closes the window.
     */
    static void startAutoCheck(Window window, Updater updater) {
        if (!updater.shouldAutoCheck()) {
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(INITIAL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            runCheck(window, updater);
        }, "sugr-updater-autocheck");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runCheck(Window window, Updater updater) {
        updater.check().whenComplete((info, error) -> {
            if (error != null || info == null) {
                // check() already emitted sugr://update-error for real failures;
                // a null result just means there's nothing new.
                return;
            }
            // A managed or manual install has already been reported through
            // sugr://update-manual-required; offering "install now" there would
            // be a promise the updater can't keep.
            if (updater.shouldPrompt() && updater.canSelfUpdate()) {
                promptAndInstall(window, updater, info);
            }
        });
    }

    /**
     * Asks, then applies. The dialog runs on the updater's own thread rather
     * than the UI thread: {@code MessageBoxW} pumps its own modal message loop,
     * so putting it on the UI thread would block the webview for as long as the
     * user takes to answer - and the user's first instinct on a frozen window
     * is to kill it.
     */
    private static void promptAndInstall(Window window, Updater updater, Updater.UpdateInfo info) {
        AppMetadata metadata = AppMetadata.get();
        StringBuilder message = new StringBuilder()
                .append(metadata.name())
                .append(' ')
                .append(info.version())
                .append(" is available (you have ")
                .append(info.currentVersion())
                .append(").");
        if (info.notes() != null && !info.notes().isBlank()) {
            message.append("\n\n").append(trimNotes(info.notes()));
        }
        message.append("\n\nDownload and install it now? The app will restart.");

        boolean confirmed = Dialogs.confirm(window.nativeHandle(), "Update available", message.toString());
        if (!confirmed) {
            return;
        }

        updater.download().whenComplete((ignored, error) -> {
            if (error == null) {
                try {
                    updater.install();
                    return;
                } catch (RuntimeException e) {
                    // Falls through to the same dialog as a failed download.
                }
            }
            // The failure was already reported through sugr://update-error;
            // the dialog is for the user who explicitly opted in and would
            // otherwise be left watching nothing happen.
            Dialogs.showMessage(window.nativeHandle(), "Update failed",
                    "The update couldn't be installed. You can try again later.");
        });
    }

    /** Keeps a long changelog from producing a message box taller than the screen. */
    private static String trimNotes(String notes) {
        String trimmed = notes.strip();
        int limit = 600;
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit) + "…";
    }
}
