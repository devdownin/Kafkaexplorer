// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
// The public Pages companion to the in-app MCP call-graph guide.
// Pure read-only documentation: no server calls, no credentials, no fake telemetry.
    (() => {
      "use strict";
      // This is a read-only architectural illustration. No fetch, token, Kafka request or fake trace.
      const kex = "https://github.com/devdownin/Kex-anHarness/blob/main/docs/ARCHITECTURE.md";
      const policy = "https://github.com/devdownin/Kex-anHarness/blob/main/docs/TOOL-CONTROLS.md";
      const reviewed = "https://github.com/devdownin/Kex-anHarness/blob/main/docs/GOVERNED-GENERAL-AGENT.md";
      const explorer = "https://github.com/devdownin/Kafkaexplorer/blob/main/docs/notes/mcp-server.md";
      const skillDoc = "https://github.com/devdownin/Kafkaexplorer/blob/main/docs/mcp-skills.md";
      const ids = ["request","entry","agent","policy","client","server","backend","evidence","answer"];
      // title, subtitle, explanation, invariant, documented source
      const shared = [
        ["Demande","Utilisateur","Une requête précise lance la conversation de Kex.","La question ne donne pas de nouveau droit.",kex],
        ["AgentController","API Kex","Kex authentifie l’appel avant de conduire la conversation.","Le bearer ne doit pas entrer dans le prompt.",kex],
        ["AgentService","ChatClient","Kex choisit les outils disponibles et organise les étapes.","Un modèle ne peut pas inventer un outil autorisé.",kex],
        ["Tool controls","Callbacks Kex","Sélection, arguments et policy filtrent l’invocation.","Une permission côté agent ne remplace pas celle du serveur.",policy],
        ["McpSyncClient","Transport MCP","Kex transporte les appels via le client MCP Java.","La connexion et son bearer sont limités à leur cible.",kex],
        ["kex_trace_key","KafkaExplorer MCP","Le serveur applique scope, budgets et lecture seule.","Aucun contournement d’un refus serveur.",explorer],
        ["Services Kafka","StreamFlowService","Les adaptateurs MCP relisent les services métier de KafkaExplorer.","Les scans restent bornés.",explorer],
        ["Preuves","Coverage · Measured","Le résultat nomme ce qui est mesuré, partiel ou inconnu.","Une absence partielle ne prouve pas le néant.",explorer],
        ["Synthèse","Réponse Kex","La réponse distingue faits, hypothèses et inconnues.","Un incident n’est pas résolu sans nouvelle mesure comparable.",kex],
      ];
      const variant = {
        incident:{},
        forecast:{
          server:["kex_forecast_*","Outils forecast","KafkaExplorer expose les séries approuvées, l’historique et la qualité calculée.","Pas d’inférence implicite.",explorer],
          backend:["ForecastPilot","Historique & prédictions","Le service retourne des valeurs mesurées et prévues.","Le modèle et l’horizon doivent être identifiés.",explorer],
          evidence:["Qualité & bandes","Observation ≠ projection","Les points réels, les quantiles et la qualité sont séparés.","Une bande nominale n’est pas une garantie.",explorer],
          answer:["Verdict de risque","Synthèse Kex","Kex explique risques prévus et limites du modèle.","Une prévision ne masque pas un incident observé.",kex],
        },
        skill:{
          agent:["Skills Kex","Index · read_skill","Kex possède ses propres compétences revues et indexées.","Un skill PENDING n’est pas activé.",reviewed],
          policy:["Revue & droits","Gouvernance Kex","Le client ne confond pas contenu lu et permission d’exécuter.","Une procédure n’accorde pas un nouveau privilège.",reviewed],
          client:["McpSyncClient","Lecture Resources","Le client peut consulter kex://skills et une URI skill://.","Pas d’import automatique dans Kex.",kex],
          server:["kex://skills","Manifest KafkaExplorer","Le serveur publie des URI, descriptions et SHA-256.","skills/list/get natifs non annoncés.",skillDoc],
          backend:["SKILL.md","Ressource versionnée","Le serveur lit les fichiers approuvés depuis le classpath.","Aucune lecture arbitraire de disque.",skillDoc],
          evidence:["Manifeste","SHA-256","Le client peut comparer la somme des octets au digest publié.","L’intégrité ne garantit pas la sûreté.",skillDoc],
          answer:["Choix explicite","Validation côté hôte","L’utilisateur ou l’hôte décide si la compétence doit être utilisée.","Lu ne veut pas dire approuvé ni activé.",reviewed],
        },
      };
      const scenarios = ["incident", "forecast", "skill"];
      const kinds = {
        request:"human",entry:"agent",agent:"agent",policy:"guard",client:"transport",
        server:"service",backend:"service",evidence:"evidence",answer:"agent",
      };
      const validLenses = ["all","human","agent","guard","transport","service","evidence"];
      const search = new URLSearchParams(window.location.search);
      let selectedScenario = scenarios.includes(search.get("scenario")) ? search.get("scenario") : "incident";
      let selectedId = ids.includes(search.get("step")) ? search.get("step") : "server";
      let selectedLens = validLenses.includes(search.get("focus")) ? search.get("focus") : "all";
      let zoom = 1;
      const buttons=[...document.querySelectorAll("[data-node]")];
      const mobileList=document.getElementById("mobile-steps");
      const wrapper=document.getElementById("graph-wrap");
      const graph=document.getElementById("graph-canvas");
      const graphScroll=document.querySelector(".graph-scroll");
      const rail=document.getElementById("step-rail");
      function revealStep(id) {
        if (!graphScroll || getComputedStyle(graphScroll).display === "none") return;
        const target=buttons.find(btn=>btn.dataset.node===id);
        if (!target || !graphScroll.clientWidth) return;
        const center=(target.offsetLeft+target.offsetWidth/2)*zoom;
        const max=Math.max(0,wrapper.offsetWidth-graphScroll.clientWidth);
        graphScroll.scrollTo?.({left:Math.max(0,Math.min(max,center-graphScroll.clientWidth/2)),behavior:"smooth"});
      }
      function permalink() {
        const url = new URL(window.location.href);
        url.searchParams.set("scenario", selectedScenario);
        url.searchParams.set("step", selectedId);
        if (selectedLens === "all") url.searchParams.delete("focus");
        else url.searchParams.set("focus", selectedLens);
        return url.href;
      }
      function updateUrl() { window.history.replaceState(null, "", permalink()); }
      function focusStep(id, focusButton=false) {
        selectedId=id;
        updateUrl();
        render();
        revealStep(id);
        if(focusButton) {
          const target=buttons.find((btn)=>btn.dataset.node===id);
          target?.focus({preventScroll:true});
        }
      }
      function zoomTo(value) {
        zoom=Math.min(1.25,Math.max(.75,value));
        wrapper.style.width=(1380*zoom)+"px";
        wrapper.style.height=(478*zoom)+"px";
        graph.style.transform="scale("+zoom+")";
        document.getElementById("zoom-label").textContent=Math.round(zoom*100)+"%";
        document.getElementById("zoom-out").disabled=zoom<=.75;
        document.getElementById("zoom-in").disabled=zoom>=1.25;
        revealStep(selectedId);
      }
      async function copyValue(value) {
        const output=document.getElementById("copy-status");
        try {
          await navigator.clipboard.writeText(value);
          output.textContent="Copié";
        } catch {
          output.textContent="Copie indisponible : utilisez la barre d’adresse";
        }
      }
      function getMermaid() {
        const entries=ids.map((id,i)=>variant[selectedScenario][id]||shared[i]);
        const lines=["flowchart LR","%% Architecture documentée, pas trace réelle"];
        ids.forEach((id,i)=>lines.push('  '+id+'["'+entries[i][0].replaceAll('"',"'")+'"]'));
        for(let i=0;i<ids.length-1;i++) lines.push('  '+ids[i]+' '+(i<6?'-->':'-.->')+' '+ids[i+1]);
        lines.push('  answer -.-> request');
        return lines.join("\n");
      }
      function render() {
        const entries=ids.map((id,i)=>variant[selectedScenario][id]||shared[i]);
        mobileList.replaceChildren();
        rail.replaceChildren();
        document.getElementById("rail-position").textContent=
          "Position "+String(ids.indexOf(selectedId)+1).padStart(2,"0")+" / 09";
        for (let i=0;i<ids.length;i++) {
          const id=ids[i], row=entries[i];
          if(i===7){
            const phase=document.createElement("span");
            phase.className="step-rail-phase";
            phase.textContent="↩ Retour";
            rail.append(phase);
          }
          const jump=document.createElement("button");
          jump.type="button";
          jump.textContent=String(i+1).padStart(2,"0")+" · "+row[0];
          jump.title=row[1];
          if(id===selectedId)jump.setAttribute("aria-current","step");
          jump.addEventListener("click",()=>focusStep(id));
          rail.append(jump);
        }
        for (let i=0;i<ids.length;i++) {
          const id=ids[i], row=entries[i];
          const li=document.createElement("li");
          const button=document.createElement("button"); button.type="button";
          button.setAttribute("aria-label","Choisir le point "+(i+1)+" : "+row[0]);
          button.setAttribute("aria-pressed",String(id===selectedId));
          if(selectedLens!=="all"&&selectedLens!==kinds[id]&&id!==selectedId)button.classList.add("role-muted");
          const number=document.createElement("span");number.className="mobile-number";number.textContent=String(i+1);
          const name=document.createElement("span");
          const title=document.createElement("strong");title.textContent=row[0];
          const subtitle=document.createElement("small");subtitle.textContent=row[1];
          name.append(title,subtitle);button.append(number,name);
          button.addEventListener("click",()=>focusStep(id));
          li.append(button);mobileList.append(li);
        }
        for(const btn of buttons) {
          const id=btn.dataset.node, i=ids.indexOf(id), row=entries[i];
          btn.replaceChildren();
          const num=document.createElement("span");num.className="num";num.textContent=String(i+1).padStart(2,"0");
          const title=document.createElement("strong");title.textContent=row[0];
          const subtitle=document.createElement("small");subtitle.textContent=row[1];
          btn.append(num,title,subtitle);
          btn.setAttribute("aria-label",`Étape ${i+1} : ${row[0]} — ${row[1]}`);
          btn.setAttribute("aria-pressed",String(id===selectedId));
          btn.classList.toggle("role-muted",selectedLens!=="all"&&kinds[id]!==selectedLens&&id!==selectedId);
        }
        const i=ids.indexOf(selectedId), row=entries[i];
        document.getElementById("selected-step").textContent=`Étape ${i+1} / ${ids.length}`;
        document.getElementById("selected-phase").textContent=i<7?"Demande et invocation":"Retour et restitution";
        document.getElementById("selected-title").textContent=row[0];
        document.getElementById("selected-desc").textContent=row[2];
        document.getElementById("selected-guard").textContent=row[3];
        document.getElementById("selected-source").href=row[4];
        for (const btn of document.querySelectorAll("[data-scenario]")) {
          btn.setAttribute("aria-pressed",String(btn.dataset.scenario===selectedScenario));
        }
        for (const btn of document.querySelectorAll("[data-lens]")) {
          btn.setAttribute("aria-pressed",String(btn.dataset.lens===selectedLens));
        }
        document.getElementById("guide-note").hidden=selectedLens==="all";
      }
      for(const btn of buttons) {
        btn.addEventListener("click",()=>focusStep(btn.dataset.node));
        btn.addEventListener("keydown",(event)=>{
          const index=ids.indexOf(btn.dataset.node);
          let target;
          if(event.key==="ArrowRight"||event.key==="ArrowDown")target=ids[(index+1)%ids.length];
          if(event.key==="ArrowLeft"||event.key==="ArrowUp")target=ids[(index+ids.length-1)%ids.length];
          if(event.key==="Home")target=ids[0];
          if(event.key==="End")target=ids[ids.length-1];
          if(target){event.preventDefault();focusStep(target,true);}
        });
      }
      for(const btn of document.querySelectorAll("[data-scenario]"))
        btn.addEventListener("click",()=>{selectedScenario=btn.dataset.scenario;focusStep("server")});
      for(const btn of document.querySelectorAll("[data-lens]"))
        btn.addEventListener("click",()=>{
          selectedLens=btn.dataset.lens;
          const target=ids.find((id)=>kinds[id]===selectedLens);
          if(target)selectedId=target;
          updateUrl();render();revealStep(selectedId);
        });
      document.getElementById("prev-node").addEventListener("click",()=>focusStep(ids[(ids.indexOf(selectedId)+ids.length-1)%ids.length]));
      document.getElementById("next-node").addEventListener("click",()=>focusStep(ids[(ids.indexOf(selectedId)+1)%ids.length]));
      document.getElementById("zoom-out").addEventListener("click",()=>zoomTo(zoom-.25));
      document.getElementById("zoom-in").addEventListener("click",()=>zoomTo(zoom+.25));
      document.getElementById("zoom-reset").addEventListener("click",()=>zoomTo(1));
      document.getElementById("copy-link").addEventListener("click",()=>copyValue(permalink()));
      document.getElementById("copy-mermaid").addEventListener("click",()=>copyValue(getMermaid()));
      window.addEventListener("popstate",()=>{
        const query=new URLSearchParams(window.location.search);
        selectedScenario=scenarios.includes(query.get("scenario"))?query.get("scenario"):"incident";
        selectedId=ids.includes(query.get("step"))?query.get("step"):"server";
        selectedLens=validLenses.includes(query.get("focus"))?query.get("focus"):"all";
        render();
        revealStep(selectedId);
      });
      zoomTo(1);
      render();
      revealStep(selectedId);
    })();
