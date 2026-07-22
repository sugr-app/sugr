import type { Row } from '../services/sqlClient'

interface ResultsTableProps {
  rows: Row[]
  elapsedMs: number | null
  onCopy: () => void
}

export function ResultsTable({ rows, elapsedMs, onCopy }: ResultsTableProps) {
  const columns = rows.length > 0 ? Object.keys(rows[0]) : []

  return (
    <section className="panel">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>Results</h2>
        <div className="row">
          {elapsedMs !== null && (
            <span className="hint">
              {rows.length} row(s) in {elapsedMs}ms
            </span>
          )}
          <button type="button" onClick={onCopy} disabled={rows.length === 0}>
            Copy as TSV
          </button>
        </div>
      </div>

      {rows.length === 0 ? (
        <p className="hint">No results yet.</p>
      ) : (
        <div className="results-scroll">
          <table>
            <thead>
              <tr>
                {columns.map((col) => (
                  <th key={col}>{col}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  {columns.map((col) => (
                    <td key={col}>{row[col] ?? <span className="null-value">NULL</span>}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
