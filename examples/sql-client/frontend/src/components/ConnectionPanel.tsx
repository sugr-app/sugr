interface ConnectionPanelProps {
  dbPath: string
  status: string
  connecting: boolean
  onDbPathChange: (path: string) => void
  onBrowse: () => void
  onConnect: () => void
}

export function ConnectionPanel({
  dbPath,
  status,
  connecting,
  onDbPathChange,
  onBrowse,
  onConnect,
}: ConnectionPanelProps) {
  return (
    <section className="panel">
      <h2>Connection</h2>
      <div className="row">
        <input
          className="path-input"
          value={dbPath}
          onChange={(e) => onDbPathChange(e.target.value)}
          placeholder=":memory: or a path to a .sqlite file"
        />
        <button type="button" onClick={onBrowse}>
          Browse...
        </button>
        <button type="button" onClick={onConnect} disabled={connecting}>
          {connecting ? 'Connecting...' : 'Connect'}
        </button>
      </div>
      <p className="status-line">{status}</p>
    </section>
  )
}
