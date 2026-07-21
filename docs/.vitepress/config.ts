import { defineConfig } from 'vitepress'

export default defineConfig({
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
})
