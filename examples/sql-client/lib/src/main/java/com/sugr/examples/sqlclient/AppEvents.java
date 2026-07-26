package com.sugr.examples.sqlclient;

import com.sugr.bridge.Emits;

/**
 * Events this app pushes to JS (Java -&gt; JS, the opposite direction from
 * SqlService's {@code @Bind} methods). The {@code processor} module generates
 * {@code AppEventsEmitter} (real {@code Window.emit} calls, one per method
 * below) and {@code AppEvents.generated.ts} (typed {@code on<Name>()} /
 * unsubscribe pairs) from this interface - see AppMenu, the only current
 * caller, for why: menu item actions don't have a JS-side handler to call
 * into directly.
 */
interface AppEvents {

    /** A path was picked via "File > Load DB file..." - see AppMenu#loadDbFile. */
    @Emits
    void menuLoadDb(String path);
}
