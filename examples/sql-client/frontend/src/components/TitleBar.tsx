import { useEffect, useRef, useState } from 'react'
import { WindowControls } from '../generated/WindowControls.generated'

/**
 * Custom HTML title bar - real native drag/resize/frame, but the caption row itself is
 * ours to draw. Dragging isn't automatic (WebView2's child window swallows WM_NCHITTEST
 * before it ever reaches the native frame), so mousedown forwards it via startDrag().
 *
 * The maximize button additionally gets a real Windows 11 Snap Layouts hover flyout - same
 * technique as Tauri's tauri-plugin-frame: an invisible native overlay window is stacked
 * exactly over this button (see WindowNative#createSnapOverlay's javadoc), which is why its
 * on-screen rect is reported to native on every resize below. Once that overlay is
 * positioned, it - not this HTML button - is what actually receives the click, so
 * toggleMaximize()'s own click handler only fires as a fallback (overlay not created yet,
 * older Windows, non-Windows).
 *
 * Not currently combined with the app's native menu bar (AppMenu) - see
 * Window.Builder#customTitleBar's javadoc for why.
 */
export function TitleBar() {
  const [maximized, setMaximized] = useState(false)
  const maxButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    void WindowControls.isMaximized().then(setMaximized)
  }, [])

  useEffect(() => {
    function reportMaxButtonBounds() {
      const el = maxButtonRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const dpr = window.devicePixelRatio || 1
      void WindowControls.setMaxButtonBounds(
        Math.round(rect.left * dpr),
        Math.round(rect.top * dpr),
        Math.round(rect.width * dpr),
        Math.round(rect.height * dpr),
      )
    }
    reportMaxButtonBounds()
    window.addEventListener('resize', reportMaxButtonBounds)
    return () => window.removeEventListener('resize', reportMaxButtonBounds)
  }, [])

  function handleMouseDown(e: React.MouseEvent) {
    if (e.button !== 0) return
    void WindowControls.startDrag()
  }

  async function toggleMaximize() {
    if (await WindowControls.isMaximized()) {
      await WindowControls.restore()
      setMaximized(false)
    } else {
      await WindowControls.maximize()
      setMaximized(true)
    }
  }

  function stopDrag(e: React.MouseEvent) {
    e.stopPropagation()
  }

  return (
    <div className="title-bar" onMouseDown={handleMouseDown} onDoubleClick={toggleMaximize}>
      <span className="title-bar-text">sugr - SQL client</span>
      <div className="title-bar-controls">
        <button
          className="title-bar-button"
          onMouseDown={stopDrag}
          onClick={() => WindowControls.minimize()}
          aria-label="Minimize"
        >
          &#8211;
        </button>
        <button
          ref={maxButtonRef}
          className="title-bar-button"
          onMouseDown={stopDrag}
          onClick={toggleMaximize}
          aria-label={maximized ? 'Restore' : 'Maximize'}
        >
          {maximized ? '❐' : '☐'}
        </button>
        <button
          className="title-bar-button title-bar-close"
          onMouseDown={stopDrag}
          onClick={() => WindowControls.close()}
          aria-label="Close"
        >
          &#10005;
        </button>
      </div>
    </div>
  )
}
