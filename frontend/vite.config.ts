import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `vite build` makes the UI for the WAR (deployed at /ach on Tomcat).
// `vite build --mode demo` makes the GitHub Pages demo: no server, the API runs in the browser (src/demo).
export default defineConfig(({ mode }) => {
  const demo = mode === 'demo'
  return {
    plugins: [react()],
    base: demo ? '/ACH-Payments/' : '/ach/',
    define: demo ? { 'import.meta.env.VITE_DEMO': JSON.stringify('1') } : {},
    build: {
      outDir: demo ? '../target/pages' : '../target/classes/static',
      emptyOutDir: true,
    },
    // `npm run dev` serves the UI on :5173 and forwards API calls to Tomcat
    server: {
      proxy: { '/ach/api': 'http://localhost:8080' },
    },
  }
})
