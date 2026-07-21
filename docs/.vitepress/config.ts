import { defineConfig } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'

export default withMermaid(defineConfig({
  title: 'sugr',
  description: 'Build lightweight desktop apps with Java and web technologies.',
  cleanUrls: true,

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/quickstart' },
      { text: 'How it works', link: '/how-it-works' },
      { text: 'Reference', link: '/reference/api' },
    ],

    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Quickstart', link: '/guide/quickstart' },
          { text: 'Window & frontend', link: '/guide/window' },
          { text: 'Binding Java to JS (@Bind)', link: '/guide/bind' },
          { text: 'Events', link: '/guide/events' },
          { text: 'Building & packaging', link: '/guide/build' },
        ],
      },
      {
        text: 'Architecture',
        items: [{ text: 'How it works', link: '/how-it-works' }],
      },
      {
        text: 'Reference',
        items: [{ text: 'API reference', link: '/reference/api' }],
      },
    ],

    socialLinks: [],

    search: {
      provider: 'local',
    },
  },

  vite: {
    optimizeDeps: {
      // mermaid imports dayjs via a subpath ("dayjs/dayjs.min.js") that Vite's
      // dep scanner doesn't pick up on its own, so it gets served raw (CJS)
      // instead of pre-bundled to ESM - forcing it here fixes
      // "doesn't provide an export named: 'default'" in the browser console.
      include: ['mermaid', 'dayjs'],
    },
  },
}))
