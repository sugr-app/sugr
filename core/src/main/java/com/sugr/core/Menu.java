package com.sugr.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A native menu tree - either a window's menu bar (via {@code Window.Builder.menu})
 * or a system tray's context menu (via {@code Tray}). Built declaratively, then
 * realized into native menu handles by {@link WindowNative}/{@link Tray} when
 * actually attached. Windows only for now - a no-op elsewhere, same as this
 * framework's other per-OS native chrome (see docs/guide/window.md).
 */
public final class Menu {

    sealed interface Entry permits Item, Submenu, Separator {
    }

    record Item(String label, Runnable action) implements Entry {
    }

    record Submenu(String label, Menu menu) implements Entry {
    }

    record Separator() implements Entry {
    }

    private final List<Entry> entries = new ArrayList<>();

    public Menu item(String label, Runnable action) {
        entries.add(new Item(label, action));
        return this;
    }

    public Menu submenu(String label, Menu menu) {
        entries.add(new Submenu(label, menu));
        return this;
    }

    public Menu separator() {
        entries.add(new Separator());
        return this;
    }

    List<Entry> entries() {
        return entries;
    }
}
