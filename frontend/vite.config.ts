import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The browser calls /api/...; the dev server forwards it to the backend without the /api prefix
// (nginx does the same in Docker), so the backend needs no CORS setup.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
