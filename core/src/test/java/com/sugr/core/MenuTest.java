package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The declarative {@link Menu} builder shared by the window menu bar and the tray menu. */
final class MenuTest {

    @Test
    void recordsEntriesInInsertionOrder() {
        Menu menu = new Menu()
                .item("Open", () -> {})
                .separator()
                .submenu("More", new Menu().item("Nested", () -> {}));

        List<Menu.Entry> entries = menu.entries();
        assertEquals(3, entries.size());
        assertInstanceOf(Menu.Item.class, entries.get(0));
        assertInstanceOf(Menu.Separator.class, entries.get(1));
        assertInstanceOf(Menu.Submenu.class, entries.get(2));
    }

    @Test
    void builderMethodsChainOnTheSameInstance() {
        Menu menu = new Menu();
        assertSame(menu, menu.item("A", () -> {}));
        assertSame(menu, menu.separator());
        assertSame(menu, menu.submenu("B", new Menu()));
    }

    @Test
    void anItemKeepsItsLabelAndAction() {
        AtomicInteger runs = new AtomicInteger();
        Menu menu = new Menu().item("Reload", runs::incrementAndGet);

        Menu.Item item = (Menu.Item) menu.entries().get(0);
        assertEquals("Reload", item.label());
        item.action().run();
        assertEquals(1, runs.get());
    }

    @Test
    void aSubmenuHoldsItsOwnMenuTree() {
        Menu child = new Menu().item("Quit", () -> {});
        Menu parent = new Menu().submenu("File", child);

        Menu.Submenu sub = (Menu.Submenu) parent.entries().get(0);
        assertEquals("File", sub.label());
        assertSame(child, sub.menu());
        assertEquals(1, sub.menu().entries().size());
    }

    @Test
    void aFreshMenuHasNoEntries() {
        assertTrue(new Menu().entries().isEmpty());
    }
}
