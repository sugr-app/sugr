package com.sugr.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogsTest {

    @Test
    void formatsWindowsExtensionAndNamedFilters() {
        assertEquals("All files (*.*)|*.*", Dialogs.windowsFilter((String[]) null));
        assertEquals("All files (*.*)|*.*", Dialogs.windowsFilter(new String[0]));
        assertEquals("*.db;*.sqlite files (*.db;*.sqlite)|*.db;*.sqlite",
                Dialogs.windowsFilter(new String[] {"db", ".sqlite"}));

        List<Dialogs.FileFilter> filters = List.of(
                new Dialogs.FileFilter("SQL scripts", "sql", "*.ddl"),
                new Dialogs.FileFilter("All files", "*")
        );
        assertEquals("SQL scripts (*.sql;*.ddl)|*.sql;*.ddl|All files (*.*)|*.*",
                Dialogs.windowsFilter(filters));
    }

    @Test
    void validatesFileFilterInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Dialogs.FileFilter(""));
        assertThrows(IllegalArgumentException.class, () -> new Dialogs.FileFilter("SQL"));
        assertThrows(IllegalArgumentException.class, () -> new Dialogs.FileFilter("SQL", "a;b"));
        assertThrows(IllegalArgumentException.class, () -> new Dialogs.FileFilter("SQL", "a|b"));
    }

    @Test
    void parsesMultiFileOutputAndHandlesCancellation() {
        assertEquals(List.of(), Dialogs.parseMultiFileOutput(null));
        assertEquals(List.of(), Dialogs.parseMultiFileOutput("   \r\n  "));
        assertEquals(
                List.of("C:\\db\\schema.sql", "C:\\db\\seed.sql"),
                Dialogs.parseMultiFileOutput("C:\\db\\schema.sql\r\nC:\\db\\seed.sql\r\n"));
    }

    @Test
    void generatesMultiSelectAndFolderDialogScripts() {
        String multiScript = Dialogs.fileDialogScript(
                "OpenFileDialog", 12345L, "Pick SQL", "SQL (*.sql)|*.sql", true);
        assertTrue(multiScript.contains("$dlg.Multiselect = $true"));
        assertTrue(multiScript.contains("$dlg.FileNames"));
        assertTrue(multiScript.contains("SugrOwnerWindow([IntPtr]12345)"));

        String folderScript = Dialogs.folderDialogScript(0L, "Choose export folder");
        assertTrue(folderScript.contains("System.Windows.Forms.FolderBrowserDialog"));
        assertTrue(folderScript.contains("'Choose export folder'"));
        assertTrue(folderScript.contains("$dlg.SelectedPath"));
    }
}
