import { useState } from 'react'
import { Backend } from './generated/Backend.generated'

function App() {
  const [name, setName] = useState('world')
  const [greeting, setGreeting] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function handleSayHello() {
    setError(null)
    try {
      setGreeting(await Backend.hello(name))
    } catch (e) {
      setError(String(e))
    }
  }

  return (
    <section style={{ padding: '1.5rem', fontFamily: 'sans-serif' }}>
      <h1>__APP_NAME__</h1>
      <p>Edit <code>lib/src/main/java/.../Backend.java</code> and <code>src/App.tsx</code> to get started.</p>

      <input value={name} onChange={(e) => setName(e.target.value)} />
      <button type="button" onClick={handleSayHello}>Say hello (calls Java)</button>

      {greeting && <p>{greeting}</p>}
      {error && <p style={{ color: 'crimson' }}>Error: {error}</p>}
    </section>
  )
}

export default App
