import type { KeyboardEvent } from 'react'

interface QueryEditorProps {
  sql: string
  running: boolean
  onSqlChange: (sql: string) => void
  onRun: () => void
  onImportScripts: () => void
}

export function QueryEditor({
  sql,
  running,
  onSqlChange,
  onRun,
  onImportScripts,
}: QueryEditorProps) {
  function handleKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault()
      onRun()
    }
  }

  return (
    <section className="panel">
      <h2>Query</h2>
      <textarea
        className="sql-editor"
        value={sql}
        onChange={(e) => onSqlChange(e.target.value)}
        onKeyDown={handleKeyDown}
        rows={8}
        spellCheck={false}
      />
      <div className="row">
        <button type="button" onClick={onRun} disabled={running}>
          {running ? 'Running...' : 'Run query'}
        </button>
        <button type="button" onClick={onImportScripts} disabled={running}>
          Import .sql files...
        </button>
        <span className="hint">Ctrl/Cmd+Enter to run</span>
      </div>
    </section>
  )
}
