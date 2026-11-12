import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The dev server proxies /api to the gateway, so the browser sees a single origin:
// no CORS changes in the backend and no need to trust the gateway's self-signed certificate.
const gateway = process.env.GATEWAY_URL ?? 'https://localhost:8443'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: gateway, secure: false, changeOrigin: true },
    },
  },
})
