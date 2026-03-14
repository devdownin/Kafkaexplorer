document.addEventListener('DOMContentLoaded', () => {
    // Multi-Tab Management
    const tabContainer = document.getElementById('tabContainer');
    const addTabBtn = document.getElementById('addTabBtn');
    const editorElement = document.getElementById('sqlEditor');
    let editor;

    let tabs = JSON.parse(localStorage.getItem('sqlTabs') || '[]');
    let activeTabId = localStorage.getItem('activeTabId');

    if (tabs.length === 0) {
        tabs = [{ id: 'tab-' + Date.now(), name: 'Query 1', sql: 'SELECT * FROM {table} LIMIT 10' }];
        activeTabId = tabs[0].id;
    }

    function initEditor() {
        if (!editorElement) return;
        editor = CodeMirror.fromTextArea(editorElement, {
            mode: 'text/x-sql',
            theme: 'material-darker',
            lineNumbers: true,
            indentWithTabs: true,
            smartIndent: true,
            autofocus: true,
            matchBrackets: true,
            viewportMargin: Infinity,
            extraKeys: {
                "Ctrl-Space": "autocomplete",
                "Ctrl-Enter": () => { document.getElementById('runQuery')?.click(); },
                "Cmd-Enter": () => { document.getElementById('runQuery')?.click(); },
                "Esc": () => { document.getElementById('stopQuery')?.click(); }
            },
            hintOptions: {
                completeSingle: false,
                tables: {} // Will be populated
            }
        });

        fetchTopicsForAutocomplete(editor);

        editor.on("change", (cm) => {
            const activeTab = tabs.find(t => t.id === activeTabId);
            if (activeTab) {
                activeTab.sql = cm.getValue();
                saveTabs();
            }
        });

        editor.on("inputRead", function(cm, change) {
            if (change.text[0] === " " || change.text[0] === "." || change.text[0] === "(") return;
            cm.showHint({ completeSingle: false });
        });

        renderTabs();
        switchTab(activeTabId);
    }

    function saveTabs() {
        localStorage.setItem('sqlTabs', JSON.stringify(tabs));
        localStorage.setItem('activeTabId', activeTabId);
    }

    function renderTabs() {
        if (!tabContainer) return;
        tabContainer.innerHTML = '';
        tabs.forEach(tab => {
            const tabEl = document.createElement('div');
            tabEl.className = `group flex items-center gap-2 px-4 py-2 text-[10px] font-bold uppercase tracking-widest border-t-2 transition-all cursor-pointer rounded-t-lg mb-0 ${tab.id === activeTabId ? 'bg-[#011627] text-primary border-primary' : 'bg-background-dark/40 text-slate-500 border-transparent hover:text-slate-300'}`;

            const nameSpan = document.createElement('span');
            nameSpan.textContent = tab.name;
            nameSpan.onclick = () => switchTab(tab.id);
            tabEl.appendChild(nameSpan);

            if (tabs.length > 1) {
                const closeBtn = document.createElement('span');
                closeBtn.className = 'material-symbols-outlined text-[12px] opacity-0 group-hover:opacity-100 hover:text-red-400 transition-all';
                closeBtn.textContent = 'close';
                closeBtn.onclick = (e) => {
                    e.stopPropagation();
                    closeTab(tab.id);
                };
                tabEl.appendChild(closeBtn);
            }

            tabContainer.appendChild(tabEl);
        });
    }

    function switchTab(id) {
        const tab = tabs.find(t => t.id === id);
        if (!tab) return;
        activeTabId = id;
        if (editor) {
            editor.setValue(tab.sql || '');
        }
        renderTabs();
        saveTabs();

        // Restore results and status for this tab
        const savedData = sessionStorage.getItem('results-' + id);
        const savedStatus = sessionStorage.getItem('status-' + id);
        const resultsCard = document.getElementById('resultsCard');
        const statusDiv = document.getElementById('queryStatus');

        if (savedData) {
            const data = JSON.parse(savedData);
            renderResults(data);
            resultsCard.style.display = 'block';
            document.getElementById('queryStats').textContent =
                `Found ${data.rows.length} rows in ${data.durationMs}ms`;
        } else {
            resultsCard.style.display = 'none';
        }

        if (savedStatus) {
            const status = JSON.parse(savedStatus);
            statusDiv.textContent = status.text;
            statusDiv.className = status.className;
            statusDiv.classList.remove('hidden');
        } else {
            statusDiv.classList.add('hidden');
        }
    }

    function closeTab(id) {
        const index = tabs.findIndex(t => t.id === id);
        if (index === -1) return;

        tabs.splice(index, 1);
        if (activeTabId === id) {
            activeTabId = tabs[Math.max(0, index - 1)].id;
        }
        renderTabs();
        switchTab(activeTabId);
    }

    if (addTabBtn) {
        addTabBtn.onclick = () => {
            const newId = 'tab-' + Date.now();
            tabs.push({ id: newId, name: `Query ${tabs.length + 1}`, sql: '' });
            switchTab(newId);
        };
    }

    initEditor();

    // DDL Viewer
    const ddlElement = document.getElementById('ddlEditor');
    if (ddlElement) {
        CodeMirror.fromTextArea(ddlElement, {
            mode: 'text/x-sql',
            theme: 'material-darker',
            lineNumbers: true,
            readOnly: true,
            viewportMargin: Infinity
        });
    }

    // Query Execution
    const runBtn = document.getElementById('runQuery');
    const stopBtn = document.getElementById('stopQuery');
    const clearBtn = document.getElementById('clearEditor');
    const pauseBtn = document.getElementById('pauseStream');
    const viewTableBtn = document.getElementById('viewTable');
    const viewChartBtn = document.getElementById('viewChart');

    let currentQueryId = null;
    let isPaused = false;
    let viewMode = 'TABLE'; // TABLE or CHART
    let resultsChart = null;
    let lastData = null;

    if (clearBtn) {
        clearBtn.addEventListener('click', () => {
            if (confirm('Clear editor?')) {
                editor.setValue('');
                showToast('Editor cleared', 'info');
            }
        });
    }

    if (runBtn) {
        runBtn.addEventListener('click', async () => {
            const sql = editor.getValue();
            if (sql.trim()) {
                saveToHistory(sql);
            }
            const statusDiv = document.getElementById('queryStatus');
            const resultsCard = document.getElementById('resultsCard');

            currentQueryId = Math.random().toString(36).substring(7);
            runBtn.classList.add('hidden');
            stopBtn.classList.remove('hidden');
            statusDiv.classList.add('hidden');
            resultsCard.style.display = 'none';
            isPaused = false;
            updatePauseButton();

            try {
                const readMode = document.querySelector('input[name="readMode"]:checked')?.value || 'earliest-offset';
                const response = await fetch('/query', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sql, maxRows: 50, timeout: 10000, readMode })
                });

                const data = await response.json();

                if (data.error) {
                    const statusText = 'Error: ' + data.error;
                    const statusClass = 'p-4 bg-red-500/10 text-red-500 text-sm font-medium';
                    statusDiv.textContent = statusText;
                    statusDiv.className = statusClass;
                    statusDiv.classList.remove('hidden');
                    sessionStorage.setItem('status-' + activeTabId, JSON.stringify({ text: statusText, className: statusClass }));
                    sessionStorage.removeItem('results-' + activeTabId);
                } else {
                    renderResults(data);
                    resultsCard.style.display = 'block';
                    const stats = `Found ${data.rows.length} rows in ${data.durationMs}ms`;
                    document.getElementById('queryStats').textContent = stats;
                    sessionStorage.setItem('results-' + activeTabId, JSON.stringify(data));
                    sessionStorage.removeItem('status-' + activeTabId);
                }
            } catch (err) {
                if (err.name === 'AbortError') {
                    statusDiv.textContent = 'Query cancelled by user.';
                } else {
                    statusDiv.textContent = 'Network Error: ' + err.message;
                }
                statusDiv.classList.remove('hidden', 'bg-emerald-500/10', 'text-emerald-500');
                statusDiv.classList.add('bg-red-500/10', 'text-red-500');
            } finally {
                runBtn.classList.remove('hidden');
                stopBtn.classList.add('hidden');
                currentQueryId = null;
            }
        });
    }

    if (stopBtn) {
        stopBtn.addEventListener('click', async () => {
            if (currentQueryId) {
                try {
                    await fetch(`/query/cancel/${currentQueryId}`, { method: 'POST' });
                } catch (e) {
                    console.error('Failed to cancel query', e);
                }
            }
        });
    }

    if (pauseBtn) {
        pauseBtn.onclick = () => {
            isPaused = !isPaused;
            updatePauseButton();
        };
    }

    function updatePauseButton() {
        if (!pauseBtn) return;
        if (isPaused) {
            pauseBtn.innerHTML = '<span class="material-symbols-outlined text-[14px]">play_arrow</span> <span>Resume</span>';
            pauseBtn.classList.replace('bg-primary/10', 'bg-amber-500/10');
            pauseBtn.classList.replace('text-primary', 'text-amber-500');
        } else {
            pauseBtn.innerHTML = '<span class="material-symbols-outlined text-[14px]">pause</span> <span>Pause</span>';
            pauseBtn.classList.replace('bg-amber-500/10', 'bg-primary/10');
            pauseBtn.classList.replace('text-amber-500', 'text-primary');
        }
    }

    if (viewTableBtn) {
        viewTableBtn.onclick = () => switchViewMode('TABLE');
    }
    if (viewChartBtn) {
        viewChartBtn.onclick = () => switchViewMode('CHART');
    }

    const colFilterInput = document.getElementById('columnFilter');
    if (colFilterInput) {
        colFilterInput.addEventListener('input', () => {
            if (lastData) renderResults(lastData);
        });
    }

    function switchViewMode(mode) {
        viewMode = mode;
        if (mode === 'TABLE') {
            document.getElementById('resultsTable').classList.remove('hidden');
            document.getElementById('chartContainer').classList.add('hidden');
            viewTableBtn.classList.add('bg-primary/20', 'text-primary');
            viewChartBtn.classList.remove('bg-primary/20', 'text-primary');
            viewChartBtn.classList.add('text-slate-500');
        } else {
            document.getElementById('resultsTable').classList.add('hidden');
            document.getElementById('chartContainer').classList.remove('hidden');
            viewChartBtn.classList.add('bg-primary/20', 'text-primary');
            viewTableBtn.classList.remove('bg-primary/20', 'text-primary');
            viewTableBtn.classList.add('text-slate-500');
            if (lastData) renderChart(lastData);
        }
    }

    function renderChart(data) {
        const ctx = document.getElementById('resultsChart').getContext('2d');

        // Identify numeric columns for Y axis
        const numericCols = data.columns.filter(col => {
            if (col.includes('window_')) return false;
            return data.rows.some(row => typeof row[col] === 'number');
        });

        // Identify X axis (prefer window_start, else first col)
        const xCol = data.columns.find(col => col === 'window_start') || data.columns[0];

        const labels = data.rows.map(row => row[xCol]);
        const datasets = numericCols.map((col, i) => ({
            label: col,
            data: data.rows.map(row => row[col]),
            borderColor: i === 0 ? '#25f4f4' : '#6ee7b7',
            backgroundColor: i === 0 ? 'rgba(37, 244, 244, 0.1)' : 'rgba(110, 231, 183, 0.1)',
            borderWidth: 2,
            fill: true,
            tension: 0.4
        }));

        if (resultsChart) {
            resultsChart.data.labels = labels;
            resultsChart.data.datasets = datasets;
            resultsChart.update('none');
        } else {
            resultsChart = new Chart(ctx, {
                type: 'line',
                data: { labels, datasets },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    scales: {
                        y: {
                            beginAtZero: true,
                            grid: { color: 'rgba(37, 244, 244, 0.05)' },
                            ticks: { color: '#94a3b8', font: { size: 10 } }
                        },
                        x: {
                            grid: { display: false },
                            ticks: { color: '#94a3b8', font: { size: 10 } }
                        }
                    },
                    plugins: {
                        legend: { labels: { color: '#f1f5f9', font: { size: 10, weight: 'bold' } } }
                    }
                }
            });
        }
    }

    function renderResults(data) {
        lastData = data;
        if (isPaused) return;

        const filterTerm = colFilterInput?.value.toLowerCase() || '';
        const filteredColumns = data.columns.filter(col => col.toLowerCase().includes(filterTerm));

        if (viewMode === 'CHART') {
            renderChart({ ...data, columns: filteredColumns });
        }
        const header = document.getElementById('resultsHeader');
        const body = document.getElementById('resultsBody');

        header.innerHTML = '';
        body.innerHTML = '';

        filteredColumns.forEach(col => {
            const th = document.createElement('th');
            th.textContent = col;
            th.className = 'px-4 py-3 border-b border-primary/10 text-[10px] font-bold text-slate-500 uppercase tracking-widest';
            header.appendChild(th);
        });

        data.rows.forEach(row => {
            const tr = document.createElement('tr');
            tr.className = 'hover:bg-primary/5 transition-colors group';
            filteredColumns.forEach(col => {
                const td = document.createElement('td');
                td.className = 'px-4 py-3 text-xs font-mono text-slate-300';
                let value = row[col] !== null ? row[col] : 'NULL';

                if (typeof value === 'string') {
                    if ((value.startsWith('{') && value.endsWith('}')) || (value.startsWith('[') && value.endsWith(']'))) {
                        try {
                            value = JSON.stringify(JSON.parse(value), null, 2);
                        } catch(e) {}
                    }
                }

                if (typeof value === 'string' && value.includes('\n')) {
                    const pre = document.createElement('pre');
                    pre.textContent = value;
                    pre.className = 'bg-background-dark border border-primary/10 rounded px-2 py-1 text-[10px] font-mono text-slate-400 group-hover:text-primary transition-colors';
                    td.appendChild(pre);
                } else {
                    td.textContent = value;
                }
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
    }

    // Load history
    renderHistory();

    // Global editor access for helper functions
    window.sqlEditor = editor;

    window.insertAtCursor = function(text) {
        if (!window.sqlEditor) return;
        const doc = window.sqlEditor.getDoc();
        const cursor = doc.getCursor();
        doc.replaceRange(text, cursor);
        window.sqlEditor.focus();
    };

    window.insertDdl = async function(topicName) {
        if (!window.sqlEditor) return;
        const readMode = document.querySelector('input[name="readMode"]:checked')?.value || 'earliest-offset';
        try {
            const response = await fetch(`/api/topic/${topicName}/ddl?readMode=${readMode}`);
            const ddl = await response.text();
            const doc = window.sqlEditor.getDoc();
            const cursor = doc.getCursor();
            doc.replaceRange(ddl + '\n\n', {line: 0, ch: 0});
            window.sqlEditor.focus();
        } catch (e) {
            console.error("Failed to fetch DDL", e);
        }
    };

    window.insertTemplate = function(template) {
        if (!window.sqlEditor) return;

        // Try to replace {table} with the first available table name
        const tableItem = document.querySelector('#collapseTables .list-group-item .truncate');
        const tableName = tableItem ? tableItem.textContent : 'my_table';
        template = template.replace(/{table}/g, tableName);

        // Try to replace {col1}, {col2} with columns if available
        const detailsDiv = document.querySelector('#collapseTables .schema-details:not(.hidden)');
        if (detailsDiv) {
            const cols = Array.from(detailsDiv.querySelectorAll('.cursor-pointer span:first-child')).map(s => s.textContent);
            if (cols.length > 0) {
                template = template.replace(/{col1}/g, cols[0]);
                if (cols.length > 1) template = template.replace(/{col2}/g, cols[1]);
            }
        }

        window.sqlEditor.setValue(template);
        window.sqlEditor.focus();
    };

    window.toggleHopFields = function() {
        const type = document.getElementById('winType').value;
        const hopFields = document.getElementById('hopFields');
        if (type === 'HOP') {
            hopFields.classList.remove('hidden');
        } else {
            hopFields.classList.add('hidden');
        }
    };

    window.applyWindowAssistant = function() {
        const table = document.getElementById('winTable').value;
        const type = document.getElementById('winType').value;
        const size = document.getElementById('winSize').value;
        const unit = document.getElementById('winUnit').value;
        const slide = document.getElementById('winSlide').value;

        let sql = '';
        if (type === 'TUMBLE') {
            sql = `SELECT window_start, window_end, COUNT(*)\nFROM TABLE(\n  TUMBLE(TABLE ${table}, DESCRIPTOR(proc_time), INTERVAL '${size}' ${unit})\n)\nGROUP BY window_start, window_end;`;
        } else if (type === 'HOP') {
            sql = `SELECT window_start, window_end, COUNT(*)\nFROM TABLE(\n  HOP(TABLE ${table}, DESCRIPTOR(proc_time), INTERVAL '${slide}' ${unit}, INTERVAL '${size}' ${unit})\n)\nGROUP BY window_start, window_end;`;
        }

        if (window.sqlEditor) {
            window.sqlEditor.setValue(sql);
            window.sqlEditor.focus();
        }
    };

    window.toggleSchema = async function(btn, tableName) {
        const detailsDiv = btn.closest('.list-group-item').querySelector('.schema-details');
        const icon = btn.querySelector('span');
        if (detailsDiv.classList.contains('hidden')) {
            icon.textContent = 'expand_more';
            detailsDiv.classList.remove('hidden');
            if (detailsDiv.innerHTML === '') {
                detailsDiv.innerHTML = '<div class="flex justify-center py-2"><div class="animate-spin rounded-full h-4 w-4 border-2 border-primary border-t-transparent"></div></div>';
                try {
                    const response = await fetch(`/api/schema/${tableName}`);
                    const schema = await response.json();
                    detailsDiv.innerHTML = '';
                    Object.entries(schema).forEach(([col, type]) => {
                        const div = document.createElement('div');
                        div.className = 'flex items-center justify-between py-1 px-2 text-xs text-slate-400 hover:text-primary cursor-pointer transition-colors';
                        div.innerHTML = `<span>${col}</span><span class="text-[10px] opacity-50 font-mono">${type}</span>`;
                        div.onclick = (e) => {
                            e.stopPropagation();
                            insertAtCursor(col);
                        };
                        detailsDiv.appendChild(div);
                    });
                } catch (e) {
                    detailsDiv.innerHTML = '<small class="text-red-400 text-[10px]">Error loading schema</small>';
                }
            }
        } else {
            icon.textContent = 'chevron_right';
            detailsDiv.classList.add('hidden');
        }
    };

    // Assistant Selection State
    let assistantColumns = new Set();
    let assistantFilters = new Map();
    let assistantAggregations = new Map(); // path -> agg
    let assistantFormat = 'JSON';

    // Query Assistant Toggle
    window.toggleAssistant = function(topicName) {
        const assistantMode = document.body.classList.toggle('assistant-active');
        const btn = document.getElementById('assistantToggleBtn');
        if (assistantMode) {
            btn.innerHTML = '<span class="material-symbols-outlined text-sm">close</span> Exit Assistant';
            btn.classList.add('bg-primary', 'text-background-dark');
            initializeAssistant(topicName);
        } else {
            btn.innerHTML = '<span class="material-symbols-outlined text-sm">magic_button</span> Query Assistant';
            btn.classList.remove('bg-primary', 'text-background-dark');
            exitAssistant();
        }
    };


    function initializeAssistant(topicName) {
        const samples = document.querySelectorAll('.sample-msg-pre');
        samples.forEach(pre => {
            const content = pre.textContent.trim();
            if (content.startsWith('{') || content.startsWith('[')) {
                try {
                    assistantFormat = 'JSON';
                    const json = JSON.parse(content);
                    const interactive = renderInteractiveJson(json, topicName);
                    pre.style.display = 'none';
                    pre.parentElement.appendChild(interactive);
                } catch (e) {}
            } else if (content.startsWith('<')) {
                try {
                    assistantFormat = 'XML';
                    const parser = new DOMParser();
                    const xmlDoc = parser.parseFromString(content, "text/xml");
                    if (!xmlDoc.getElementsByTagName("parsererror").length) {
                        const interactive = renderInteractiveXml(xmlDoc.documentElement, topicName);
                        pre.style.display = 'none';
                        pre.parentElement.appendChild(interactive);
                    }
                } catch (e) {}
            }
        });
        document.getElementById('assistantPreviewArea').classList.remove('d-none');
    }

    function exitAssistant() {
        document.querySelectorAll('.interactive-json').forEach(el => el.remove());
        document.querySelectorAll('.sample-msg-pre').forEach(pre => pre.style.display = 'block');
        document.getElementById('assistantPreviewArea').classList.add('d-none');
        assistantColumns.clear();
        assistantFilters.clear();
    }

    function renderInteractiveXml(node, topicName, path = '') {
        const container = document.createElement('div');
        container.className = 'interactive-json font-monospace small';

        const currentPath = path === '' ? node.nodeName : `${path}/${node.nodeName}`;
        const line = document.createElement('div');
        line.className = 'ms-3';

        const tagSpan = document.createElement('span');
        tagSpan.className = 'json-key text-teal cursor-pointer';
        tagSpan.textContent = `<${node.nodeName}>`;
        tagSpan.onclick = (e) => {
            e.stopPropagation();
            toggleAssistantColumn(currentPath, tagSpan, topicName);
        };
        line.appendChild(tagSpan);

        if (node.children.length > 0) {
            Array.from(node.children).forEach(child => {
                line.appendChild(renderInteractiveXml(child, topicName, currentPath));
            });
        } else if (node.textContent.trim()) {
            const val = node.textContent.trim();
            const valSpan = document.createElement('span');
            valSpan.className = 'json-value text-muted cursor-pointer ms-1';
            valSpan.textContent = val;
            valSpan.onclick = (e) => {
                e.stopPropagation();
                toggleAssistantFilter(currentPath, val, valSpan, topicName);
            };
            line.appendChild(valSpan);
        }

        const closeTagSpan = document.createElement('span');
        closeTagSpan.className = 'text-teal small';
        closeTagSpan.textContent = `</${node.nodeName}>`;
        line.appendChild(closeTagSpan);

        container.appendChild(line);
        return container;
    }

    function renderInteractiveJson(obj, topicName, path = '$') {
        const container = document.createElement('div');
        container.className = 'interactive-json font-monospace small';

        if (Array.isArray(obj)) {
            container.appendChild(document.createTextNode('['));
            obj.forEach((item, i) => {
                container.appendChild(renderInteractiveJson(item, topicName, `${path}[${i}]`));
                if (i < obj.length - 1) container.appendChild(document.createTextNode(', '));
            });
            container.appendChild(document.createTextNode(']'));
        } else if (obj !== null && typeof obj === 'object') {
            container.appendChild(document.createTextNode('{'));
            const keys = Object.keys(obj);
            keys.forEach((key, i) => {
                const line = document.createElement('div');
                line.className = 'ms-3';

                const currentPath = path === '$' ? key : `${path}.${key}`;

                const keySpan = document.createElement('span');
                keySpan.className = 'json-key text-teal cursor-pointer';
                keySpan.textContent = `"${key}"`;
                keySpan.onclick = (e) => {
                    e.stopPropagation();
                    toggleAssistantColumn(currentPath, keySpan, topicName);
                };

                line.appendChild(keySpan);
                line.appendChild(document.createTextNode(': '));

                const val = obj[key];
                if (typeof val === 'object' && val !== null) {
                    line.appendChild(renderInteractiveJson(val, topicName, currentPath));
                } else {
                    const valSpan = document.createElement('span');
                    valSpan.className = 'json-value text-muted cursor-pointer';
                    valSpan.textContent = typeof val === 'string' ? `"${val}"` : val;
                    valSpan.onclick = (e) => {
                        e.stopPropagation();
                        toggleAssistantFilter(currentPath, val, valSpan, topicName);
                        toggleAssistantFilter(key, val, valSpan, topicName);
                    };
                    line.appendChild(valSpan);
                }

                if (i < keys.length - 1) line.appendChild(document.createTextNode(','));
                container.appendChild(line);
            });
            container.appendChild(document.createTextNode('}'));
        }
        return container;
    }

    function toggleAssistantColumn(columnPath, el, topicName) {
        if (assistantColumns.has(columnPath)) {
            assistantColumns.delete(columnPath);
            el.classList.remove('fw-bold', 'border-bottom');
            const aggSelect = el.parentElement.querySelector('.agg-select');
            if (aggSelect) aggSelect.remove();
            assistantAggregations.delete(columnPath);
        } else {
            assistantColumns.add(columnPath);
            el.classList.add('fw-bold', 'border-bottom');

            // Add aggregation selector for beginner users
            const aggSelect = document.createElement('select');
            aggSelect.className = 'agg-select bg-dark text-teal border-0 small ms-1 rounded';
            ['NONE', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX'].forEach(agg => {
                const opt = document.createElement('option');
                opt.value = agg;
                opt.textContent = agg;
                aggSelect.appendChild(opt);
            });
            aggSelect.onclick = (e) => e.stopPropagation();
            aggSelect.onchange = (e) => {
                if (e.target.value === 'NONE') assistantAggregations.delete(columnPath);
                else assistantAggregations.set(columnPath, e.target.value);
                updateAssistantQuery(topicName);
            };
            el.parentElement.insertBefore(aggSelect, el.nextSibling);
        }
        updateAssistantQuery(topicName);
    }

    function toggleAssistantFilter(columnPath, value, el, topicName) {
        if (assistantFilters.has(columnPath) && assistantFilters.get(columnPath).value === value) {
            assistantFilters.delete(columnPath);
            el.classList.remove('bg-teal', 'text-dark', 'px-1', 'rounded');
            const opSelect = el.parentElement.querySelector('.op-select');
            if (opSelect) opSelect.remove();
        } else {
            assistantFilters.set(columnPath, { value: value, op: '=' });
            el.classList.add('bg-teal', 'text-dark', 'px-1', 'rounded');

            // Add operator selector
            const opSelect = document.createElement('select');
            opSelect.className = 'op-select bg-dark text-teal border-0 small ms-1 rounded';
            ['=', '!=', '>', '<', 'LIKE'].forEach(op => {
                const opt = document.createElement('option');
                opt.value = op;
                opt.textContent = op;
                opSelect.appendChild(opt);
            });
            opSelect.onclick = (e) => e.stopPropagation();
            opSelect.onchange = (e) => {
                assistantFilters.get(columnPath).op = e.target.value;
                updateAssistantQuery(topicName);
            };
            el.parentElement.appendChild(opSelect);

            // Also ensure it's in columns
            if (!assistantColumns.has(columnPath)) {
                const keyEl = el.parentElement.querySelector('.json-key');
                if (keyEl) toggleAssistantColumn(columnPath, keyEl, topicName);
            }
        }
        updateAssistantQuery(topicName);
    }

    function formatAssistantPath(path) {
        let formatted = path;
        if (assistantFormat === 'XML') {
            formatted = `XmlExtract(raw_value, '/${path}')`;
        } else if (path.includes('.')) {
            formatted = `JSON_VALUE(raw_value, '$.${path}')`;
        }

        if (assistantAggregations.has(path)) {
            return `${assistantAggregations.get(path)}(${formatted})`;
        }
        return formatted;
    }

    function updateAssistantQuery(topicName) {
        let select = assistantColumns.size > 0
            ? Array.from(assistantColumns).map(formatAssistantPath).join(', ')
            : '*';

        const hasAggs = assistantAggregations.size > 0;
        if (hasAggs && assistantColumns.size > assistantAggregations.size) {
            // Mixed agg and non-agg requires GROUP BY in SQL, which might be too complex for a simple assistant
            // but we can try to handle it by grouping by all non-agg columns
        }

        let sql = `SELECT ${select} FROM ${topicName}`;

        if (assistantFilters.size > 0) {
            let where = Array.from(assistantFilters.entries())
                .map(([path, filter]) => {
                    const col = formatAssistantPath(path);
                    const formattedVal = typeof filter.value === 'string' ? `'${filter.value}'` : filter.value;
                    return `${col} ${filter.op} ${formattedVal}`;
                })
                .join(' AND ');
            sql += ` WHERE ${where}`;
        }

        if (hasAggs) {
            const nonAggs = Array.from(assistantColumns).filter(path => !assistantAggregations.has(path));
            if (nonAggs.length > 0) {
                sql += ` GROUP BY ${nonAggs.map(formatAssistantPath).join(', ')}`;
            }
        }

        document.getElementById('generatedSqlPreview').textContent = sql + ';';
    }

    window.openAssistantQuery = function() {
        const sql = document.getElementById('generatedSqlPreview').textContent;
        sessionStorage.setItem('pendingAssistantQuery', sql);
        window.location.href = '/query';
    };

    window.registerTableFromAssistant = async function() {
        const ddl = document.getElementById('ddlEditor').value;
        try {
            const response = await fetch('/query', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sql: ddl, maxRows: 1, timeout: 5000 })
            });
            const data = await response.json();
            if (data.error) {
                showToast('Error registering table: ' + data.error, 'error');
            } else {
                showToast('Table registered successfully in Flink!');
            }
        } catch (e) {
            showToast('Failed to register table: ' + e.message, 'error');
        }
    };

    window.cancelJob = async function(jobId) {
        if (confirm('Are you sure you want to cancel this job?')) {
            try {
                const response = await fetch(`/query/cancel/${jobId}`, { method: 'POST' });
                if (response.ok) {
                    location.reload();
                } else {
                    alert('Failed to cancel job');
                }
            } catch (e) {
                alert('Error: ' + e.message);
            }
        }
    };

    // Populate editor from assistant if needed
    const pendingQuery = sessionStorage.getItem('pendingAssistantQuery');
    if (pendingQuery && editorElement && !editorElement.value) {
        setTimeout(() => {
            const cm = document.querySelector('.CodeMirror').CodeMirror;
            cm.setValue(pendingQuery);
            sessionStorage.removeItem('pendingAssistantQuery');
        }, 500);
    }

    // Topic Filtering
    const prefixInput = document.getElementById('prefixFilter');
    const fullNameInput = document.getElementById('fullNameFilter');

    if (prefixInput || fullNameInput) {
        const filterTopics = () => {
            const prefix = prefixInput?.value.toLowerCase();
            const fullName = fullNameInput.value.toLowerCase();
            const rows = document.querySelectorAll('.topic-row');

            rows.forEach(row => {
                const name = row.querySelector('td').textContent.toLowerCase();
                let matchesPrefix = true;
                let matchesFull = true;

                if (prefix) matchesPrefix = name.trim().startsWith(prefix);
                if (fullName) matchesFull = (name.trim() === fullName);

                if (matchesPrefix && matchesFull) {
                    row.classList.remove('hidden');
                } else {
                    row.classList.add('hidden');
                }
            });
        };

        if (prefixInput) prefixInput.addEventListener('input', filterTopics);
        if (fullNameInput) fullNameInput.addEventListener('input', filterTopics);
    }

    const hideEmptySwitch = document.getElementById('hideEmptyTopics');
    const hideDltSwitch = document.getElementById('hideDltTopics');

    const updateTopicFilters = () => {
        const hideEmpty = hideEmptySwitch?.checked || false;
        const hideDlt = hideDltSwitch?.checked || false;

        // Handle dashboard table rows
        document.querySelectorAll('.topic-row').forEach(row => {
            const size = parseInt(row.getAttribute('data-size') || '0');
            const isDlt = row.getAttribute('data-dlt') === 'true';

            let visible = true;
            if (hideEmpty && size === 0) visible = false;
            if (hideDlt && isDlt) visible = false;

            // Use tailwind display classes
            if (visible) {
                row.classList.remove('hidden');
            } else {
                row.classList.add('hidden');
            }
        });

        // Handle sidebar/accordion topic items
        document.querySelectorAll('.topic-item').forEach(item => {
            const isDlt = item.getAttribute('data-dlt') === 'true';

            let visible = true;
            if (hideDlt && isDlt) visible = false;

            if (visible) {
                item.classList.remove('hidden');
            } else {
                item.classList.add('hidden');
            }
        });
    };

    if (hideEmptySwitch) hideEmptySwitch.addEventListener('change', updateTopicFilters);
    if (hideDltSwitch) hideDltSwitch.addEventListener('change', updateTopicFilters);

    // Initial call to apply default filters (like Hide DLT which is checked by default)
    updateTopicFilters();
});

function saveToHistory(sql) {
    let history = JSON.parse(localStorage.getItem('sqlHistory') || '[]');
    // Remove if already exists to move it to the top
    history = history.filter(item => item !== sql);
    history.unshift(sql);
    if (history.length > 20) history.pop();
    localStorage.setItem('sqlHistory', JSON.stringify(history));
    renderHistory();
}

async function fetchTopicsForAutocomplete(editor) {
    try {
        const response = await fetch('/api/topics');
        const topics = await response.json();
        const tables = {};
        topics.forEach(topic => {
            tables[topic] = []; // We could also fetch columns for each topic if needed
        });
        editor.setOption("hintOptions", {
            tables: tables,
            completeSingle: false
        });
    } catch (e) {
        console.error("Failed to fetch topics for autocomplete", e);
    }
}

function renderHistory() {
    const historyList = document.getElementById('historyList');
    if (!historyList) return;

    const history = JSON.parse(localStorage.getItem('sqlHistory') || '[]');
    historyList.innerHTML = '';

    if (history.length === 0) {
        historyList.innerHTML = '<li class="list-group-item bg-dark text-muted small border-secondary">No history yet</li>';
        return;
    }

    history.forEach((sql, index) => {
        const li = document.createElement('li');
            li.className = 'text-[10px] text-slate-400 hover:text-primary cursor-pointer truncate font-mono bg-background-dark/50 px-2 py-1 rounded border border-primary/5 transition-colors';
        li.textContent = sql.substring(0, 50) + (sql.length > 50 ? '...' : '');
        li.title = sql;
        li.onclick = () => {
            const editor = document.querySelector('.CodeMirror').CodeMirror;
            editor.setValue(sql);
        };
        historyList.appendChild(li);
    });
}

function copyToClipboard(button) {
    const pre = button.previousElementSibling;
    const text = pre.textContent;
    navigator.clipboard.writeText(text).then(() => {
        showToast('Copied to clipboard');
    });
}
