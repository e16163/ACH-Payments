import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // The WAR is deployed at /ach on Tomcat
  base: '/ach/',
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  // `npm run dev` serves the UI on :5173 and forwards API calls to Tomcat
  server: {
    proxy: { '/ach/api': 'http://localhost:8080' },
  },
})
