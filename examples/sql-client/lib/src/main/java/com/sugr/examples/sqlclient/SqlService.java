package com.sugr.examples.sqlclient;

import com.sugr.bridge.Bind;
import com.sugr.bridge.BridgeException;
import com.sugr.core.Clipboard;
import com.sugr.core.Dialogs;
import com.sugr.core.NativeLibrary;
import com.sugr.core.Notification;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable FFM binding for libsqlite3, kept open across many query() calls.
 * App-specific (not part of the sugr core/bridge library - a real app would
 * bring its own data layer the same way).
 */
final class SqlService implements AutoCloseable {

    private final Arena arena = Arena.ofShared();
    private final MethodHandle sqlite3_open;
    private final MethodHandle sqlite3_exec;
    private final MethodHandle sqlite3_close;
    private final MethodHandle sqlite3_errmsg;
    private final MemorySegment execCallbackStub;

    private MemorySegment db = MemorySegment.NULL;
    private List<Map<String, String>> lastRows;
    private final String iconPath;

    SqlService() throws Throwable {
        Path dllPath = NativeLibrary.extractToTempFile(SqlService.class, "/native/sqlite3.dll", "sqlite3", ".dll");
        NativeLibrary sqlite = new NativeLibrary(dllPath, arena);
        // Used as the connect() notification's icon (see Notification.show's iconPath param) -
        // a placeholder for now, swap for the app's real branding whenever it has one.
        iconPath = NativeLibrary.extractToTempFile(SqlService.class, "/native/icon.png", "sugr-sql-client-icon", ".png")
                .toString();

        sqlite3_open = sqlite.downcall("sqlite3_open",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        sqlite3_exec = sqlite.downcall("sqlite3_exec",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        sqlite3_close = sqlite.downcall("sqlite3_close",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

        sqlite3_errmsg = sqlite.downcall("sqlite3_errmsg",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        execCallbackStub = sqlite.upcall(MethodHandles.lookup(), this, "onExecRow",
                MethodType.methodType(int.class, MemorySegment.class, int.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    /** The extracted temp-file path of the bundled placeholder icon - see {@code Main}'s {@code Notification.registerApp} call. */
    String iconPath() {
        return iconPath;
    }

    /**
     * Native "open file" dialog (see {@link Dialogs}) - throws a structured
     * {@code "DIALOG_CANCELLED"} error the frontend can distinguish from a
     * real failure (see {@code services/sqlClient.ts}'s handling of this call).
     */
    @Bind
    String pickDatabaseFile() {
        String path = Dialogs.openFile("Choose a SQLite database", "db", "sqlite");
        if (path == null) {
            throw new BridgeException("DIALOG_CANCELLED", "No file was selected");
        }
        return path;
    }

    /** Copies text (e.g. rendered query results) to the OS clipboard - see {@link Clipboard}. */
    @Bind
    void copyToClipboard(String text) {
        Clipboard.write(text);
    }

    /** Notifies via a toast (see {@link Notification}) on top of the returned/thrown result -
     * a passive supplement to the frontend's own status text/error banner, not a replacement. */
    @Bind
    String connect(String path) throws Throwable {
        if (!db.equals(MemorySegment.NULL)) {
            sqlite3_close.invoke(db);
        }
        MemorySegment dbHandlePtr = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment pathNative = arena.allocateFrom(path);
        int result = (int) sqlite3_open.invoke(pathNative, dbHandlePtr);
        if (result != 0) {
            Notification.show("Connection failed", "Could not open " + path, iconPath);
            throw new RuntimeException("sqlite3_open failed with code " + result);
        }
        db = dbHandlePtr.get(ValueLayout.ADDRESS, 0);
        Notification.show("Connected", "Connected to " + path, iconPath);
        return "connected: " + path;
    }

    /** Runs arbitrary SQL and returns every row seen by {@link #onExecRow}. */
    @Bind
    List<Map<String, String>> query(String sql) throws Throwable {
        if (db.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("Not connected - call connect() first");
        }
        lastRows = new ArrayList<>();
        MemorySegment sqlNative = arena.allocateFrom(sql);
        MemorySegment errMsgPtr = arena.allocate(ValueLayout.ADDRESS);
        int result = (int) sqlite3_exec.invoke(db, sqlNative, execCallbackStub, MemorySegment.NULL, errMsgPtr);
        if (result != 0) {
            MemorySegment errMsg = (MemorySegment) sqlite3_errmsg.invoke(db);
            String message = errMsg.equals(MemorySegment.NULL)
                    ? "unknown error"
                    : errMsg.reinterpret(4096).getString(0);
            throw new RuntimeException("sqlite3_exec failed (" + result + "): " + message);
        }
        return lastRows;
    }

    @Bind
    List<String> listTables() throws Throwable {
        List<Map<String, String>> rows = query(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;");
        List<String> names = new ArrayList<>();
        for (Map<String, String> row : rows) {
            names.add(row.get("name"));
        }
        return names;
    }

    /** Upcall target: sqlite3_exec calls this once per result row. */
    int onExecRow(MemorySegment notUsed, int argc, MemorySegment argv, MemorySegment azColName) {
        MemorySegment argvArr = argv.reinterpret((long) argc * ValueLayout.ADDRESS.byteSize());
        MemorySegment colArr = azColName.reinterpret((long) argc * ValueLayout.ADDRESS.byteSize());

        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < argc; i++) {
            MemorySegment colNamePtr = colArr.getAtIndex(ValueLayout.ADDRESS, i);
            MemorySegment valuePtr = argvArr.getAtIndex(ValueLayout.ADDRESS, i);
            String colName = colNamePtr.reinterpret(4096).getString(0);
            String value = valuePtr.equals(MemorySegment.NULL) ? null : valuePtr.reinterpret(4096).getString(0);
            row.put(colName, value);
        }
        lastRows.add(row);
        return 0;
    }

    @Override
    public void close() {
        try {
            if (!db.equals(MemorySegment.NULL)) {
                sqlite3_close.invoke(db);
            }
        } catch (Throwable ignored) {
            // best-effort cleanup
        } finally {
            arena.close();
        }
    }

}
