import { useEffect, useState } from 'react'
import { AppEvents } from './generated/AppEvents.generated'
import { ConnectionPanel } from './components/ConnectionPanel'
import { QueryEditor } from './components/QueryEditor'
import { TablesList } from './components/TablesList'
import { ResultsTable } from './components/ResultsTable'
import {
  connect,
  copyToClipboard,
  errorMessage,
  isDialogCancelled,
  listTables,
  pickDatabaseFile,
  query,
  rowsToClipboardText,
  type Row,
} from './services/sqlClient'
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

  const [tables, setTables] = useState<string[]>([])
  const [tablesLoading, setTablesLoading] = useState(false)

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // The native "File > Load DB file..." menu item (see AppMenu.java) shows the
    // dialog on the Java side (menus don't have a JS-side handler to call into),
    // then hands the picked path over as an event - we own connecting to it.
    return AppEvents.onMenuLoadDb((path) => {
      setDbPath(path)
      void connectTo(path)
    })
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

  return (
    <main id="sql-client">
      <h1>sugr - SQL client</h1>

      <ConnectionPanel
        dbPath={dbPath}
        status={status}
        connecting={connecting}
        onDbPathChange={setDbPath}
        onBrowse={handleBrowse}
        onConnect={handleConnect}
      />

      <QueryEditor sql={sql} running={running} onSqlChange={setSql} onRun={handleRunQuery} />

      {error && <p className="error-banner">{error}</p>}

      <TablesList
        tables={tables}
        loading={tablesLoading}
        onRefresh={refreshTables}
        onSelectTable={handleSelectTable}
      />

      <ResultsTable rows={rows} elapsedMs={elapsedMs} onCopy={handleCopyResults} />
    </main>
  )
}

export default App
