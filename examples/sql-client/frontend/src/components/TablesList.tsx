interface TablesListProps {
  tables: string[]
  loading: boolean
  onRefresh: () => void
  onSelectTable: (table: string) => void
}

export function TablesList({ tables, loading, onRefresh, onSelectTable }: TablesListProps) {
  return (
    <section className="panel">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h2>Tables</h2>
        <button type="button" onClick={onRefresh} disabled={loading}>
          {loading ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>
      {tables.length === 0 ? (
        <p className="hint">No tables yet - connect and run a query to create some.</p>
      ) : (
        <div className="chips">
          {tables.map((table) => (
            <button key={table} type="button" className="chip" onClick={() => onSelectTable(table)}>
              {table}
            </button>
          ))}
        </div>
      )}
    </section>
  )
}
