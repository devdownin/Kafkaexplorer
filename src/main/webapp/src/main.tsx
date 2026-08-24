// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

import React from 'react'
import ReactDOM from 'react-dom/client'
import axios from 'axios'
import App from './App'
import { installStaleChunkRecovery } from './staleChunk'
import { installRequestTimeout } from './api/requestTimeout'
import './fonts.css'
import './index.css'

/* Avant le rendu : un onglet resté ouvert pendant un redéploiement demandera un chunk que
   le serveur n'a plus. Vite émet `vite:preloadError` avant que le rejet n'atteigne React,
   ce qui permet de recharger sans que l'utilisateur voie le moindre écran d'erreur. */
installStaleChunkRecovery()

/* `axios` n'a aucun délai par défaut : sans ça, une requête que le serveur n'honore jamais reste
   pendante pour toujours, et l'écran qui l'attend avec elle. Les appels qui ont besoin d'attendre
   plus longtemps portent déjà le leur et le surchargent. */
installRequestTimeout(axios)

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
