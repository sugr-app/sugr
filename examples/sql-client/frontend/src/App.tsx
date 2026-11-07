import { useEffect, useState } from 'react'
import { app, type ThemeMode } from '@sugr/runtime'
import { AppEvents } from './generated/AppEvents.generated'
import { WindowControls } from './generated/WindowControls.generated'
import { TitleBar } from './components/TitleBar'
import { ConnectionPanel } from './components/ConnectionPanel'
import { QueryEditor } from './components/QueryEditor'
import { TablesList } from './components/TablesList'
import { ResultsTable } from './components/ResultsTable'
import {
  connect,
  copyToClipboard,
  errorMessage,
  exportResultsToFolder,
  importSqlScripts,
  isDialogCancelled,
  listTables,
  openDocs,
  openLogFolder,
  pickDatabaseFile,
  query,
  rowsToClipboardText,
  type Row,
} from './services/sqlClient'
import { env } from './env'
import './App.css'

const DEFAULT_SQL =
  "CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT, age INTEGER);\n" +
  "INSERT INTO users(name, age) VALUES ('Alice', 30), ('Bob', 25);\n" +
  'SELECT * FROM users;'

function App() {
  const [dbPath, setDbPath] = useState(':memory:')
  const [status, setStatus] = useState('not connected')
  const [connecting, setConnecting] = useState(false)

  const [sql, setSql] = useState(DEFAULT_SQL)
  const [running, setRunning] = useState(false)
  const [rows, setRows] = useState<Row[]>([])
  const [elapsedMs, setElapsedMs] = useState<number | null>(null)
  const [exportedPath, setExportedPath] = useState<string | null>(null)

  const [tables, setTables] = useState<string[]>([])
  const [tablesLoading, setTablesLoading] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [theme, setTheme] = useState<ThemeMode>('light')
  const [logDir, setLogDir] = useState<string | null>(null)

  // Off by default (Main.java doesn't call .customTitleBar() on the builder) - "Window >
  // Toggle custom title bar" flips it native-side and, since a menu action has no direct
  // JS-side handler to call into, tells us via customTitleBarChanged so this stays in sync.
  const [customTitleBar, setCustomTitleBar] = useState(false)

  useEffect(() => {
    // The native "File > Load DB file..." menu item (see AppMenu.java) shows the
    // dialog on the Java side (menus don't have a JS-side handler to call into),
    // then hands the picked path over as an event - we own connecting to it.
    return AppEvents.onMenuLoadDb((path) => {
      setDbPath(path)
      void connectTo(path)
    })
  }, [])

  useEffect(() => {
    void WindowControls.isCustomTitleBar().then(setCustomTitleBar)
    return AppEvents.onCustomTitleBarChanged(setCustomTitleBar)
  }, [])

  useEffect(() => {
    function applyTheme(next: ThemeMode) {
      setTheme(next)
      document.documentElement.dataset.theme = next
    }
    void app.theme().then(applyTheme).catch(() => {})
    void app
      .paths()
      .then((paths) => setLogDir(paths.logDir))
      .catch(() => {})
    return app.onThemeChanged(applyTheme)
  }, [])

  async function connectTo(path: string) {
    setError(null)
    setConnecting(true)
    try {
      const message = await connect(path)
      setStatus(message)
      await refreshTables()
    } catch (e) {
      setError(errorMessage(e))
      setStatus('not connected')
    } finally {
      setConnecting(false)
    }
  }

  async function handleBrowse() {
    setError(null)
    try {
      const path = await pickDatabaseFile()
      setDbPath(path)
    } catch (e) {
      if (!isDialogCancelled(e)) {
        setError(errorMessage(e))
      }
    }
  }

  async function handleConnect() {
    await connectTo(dbPath)
  }

  async function handleRunQuery() {
    setError(null)
    setExportedPath(null)
    setRunning(true)
    const start = performance.now()
    try {
      const result = await query(sql)
      setRows(result)
      setElapsedMs(Math.round(performance.now() - start))
      await refreshTables()
    } catch (e) {
      setError(errorMessage(e))
      setRows([])
      setElapsedMs(null)
    } finally {
      setRunning(false)
    }
  }

  async function handleImportScripts() {
    setError(null)
    try {
      const imported = await importSqlScripts()
      setSql(imported)
    } catch (e) {
      if (!isDialogCancelled(e)) {
        setError(errorMessage(e))
      }
    }
  }

  async function refreshTables() {
    setTablesLoading(true)
    try {
      const names = await listTables()
      setTables(names)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setTablesLoading(false)
    }
  }

  function handleSelectTable(table: string) {
    setSql(`SELECT * FROM ${table};`)
  }

  async function handleCopyResults() {
    setError(null)
    try {
      await copyToClipboard(rowsToClipboardText(rows))
    } catch (e) {
      setError(errorMessage(e))
    }
  }

  async function handleExportFolder() {
    setError(null)
    try {
      const saved = await exportResultsToFolder(rowsToClipboardText(rows))
      setExportedPath(saved)
    } catch (e) {
      if (!isDialogCancelled(e)) {
        setError(errorMessage(e))
      }
    }
  }

  return (
    <>
      {customTitleBar && <TitleBar />}
      {/* #sql-client's own CSS already gives it 32px of top padding by default - stack
          the 32px title bar height on top of that (64px), instead of under it, when shown. */}
      <main id="sql-client" style={customTitleBar ? { paddingTop: 64 } : undefined}>
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <div>
            <h1>{env.appTitle ?? 'sugr - SQL client'}</h1>
            <p className="env-badge">
              env: {env.mode} · theme: {theme}
              {logDir ? ` · logs: ${logDir}` : ''}
            </p>
          </div>
          <div className="row">
            <button type="button" onClick={() => void openLogFolder()}>
              Open log folder
            </button>
            <button type="button" onClick={() => void openDocs()}>
              Docs
            </button>
          </div>
        </div>

        <ConnectionPanel
          dbPath={dbPath}
          status={status}
          connecting={connecting}
          onDbPathChange={setDbPath}
          onBrowse={handleBrowse}
          onConnect={handleConnect}
        />

        <QueryEditor
          sql={sql}
          running={running}
          onSqlChange={setSql}
          onRun={handleRunQuery}
          onImportScripts={handleImportScripts}
        />

        {error && <p className="error-banner">{error}</p>}

        <TablesList
          tables={tables}
          loading={tablesLoading}
          onRefresh={refreshTables}
          onSelectTable={handleSelectTable}
        />

        <ResultsTable
          rows={rows}
          elapsedMs={elapsedMs}
          exportedPath={exportedPath}
          onCopy={handleCopyResults}
          onExportFolder={handleExportFolder}
        />
      </main>
    </>
  )
}

export default App
