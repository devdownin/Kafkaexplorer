import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { installStaleChunkRecovery } from './staleChunk'
import './fonts.css'
import './index.css'

/* Avant le rendu : un onglet resté ouvert pendant un redéploiement demandera un chunk que
   le serveur n'a plus. Vite émet `vite:preloadError` avant que le rejet n'atteigne React,
   ce qui permet de recharger sans que l'utilisateur voie le moindre écran d'erreur. */
installStaleChunkRecovery()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
