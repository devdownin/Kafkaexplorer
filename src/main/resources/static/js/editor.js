document.addEventListener('DOMContentLoaded', () => {
    // SQL Editor
    const editorElement = document.getElementById('sqlEditor');
    let editor;
    if (editorElement) {
        editor = CodeMirror.fromTextArea(editorElement, {
            mode: 'text/x-sql',
            theme: 'material-darker',
            lineNumbers: true,
            indentWithTabs: true,
            smartIndent: true,
            autofocus: true,
            matchBrackets: true,
            viewportMargin: Infinity,
            extraKeys: { "Ctrl-Space": "autocomplete" },
            hintOptions: {
                completeSingle: false,
                tables: {} // Will be populated
            }
        });

        // Populate tables for auto-completion
        fetchTopicsForAutocomplete(editor);

        editor.on("inputRead", function(cm, change) {
            if (change.text[0] === " " || change.text[0] === "." || change.text[0] === "(") return;
            cm.showHint({ completeSingle: false });
        });
    }

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
    if (runBtn) {
        runBtn.addEventListener('click', async () => {
            const sql = editor.getValue();
            if (sql.trim()) {
                saveToHistory(sql);
            }
            const statusDiv = document.getElementById('queryStatus');
            const resultsCard = document.getElementById('resultsCard');

            runBtn.disabled = true;
            runBtn.textContent = 'Running...';
            statusDiv.classList.add('d-none');
            resultsCard.style.display = 'none';

            try {
                const response = await fetch('/query', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sql, maxRows: 50, timeout: 10000 })
                });

                const data = await response.json();

                if (data.error) {
                    statusDiv.textContent = 'Error: ' + data.error;
                    statusDiv.classList.remove('d-none', 'alert-success');
                    statusDiv.classList.add('alert-danger');
                } else {
                    renderResults(data);
                    resultsCard.style.display = 'block';
                    document.getElementById('queryStats').textContent =
                        `Found ${data.rows.length} rows in ${data.durationMs}ms`;
                }
            } catch (err) {
                statusDiv.textContent = 'Network Error: ' + err.message;
                statusDiv.classList.remove('d-none');
                statusDiv.classList.add('alert-danger');
            } finally {
                runBtn.disabled = false;
                runBtn.textContent = 'Execute';
            }
        });
    }

    function renderResults(data) {
        const header = document.getElementById('resultsHeader');
        const body = document.getElementById('resultsBody');

        header.innerHTML = '';
        body.innerHTML = '';

        data.columns.forEach(col => {
            const th = document.createElement('th');
            th.textContent = col;
            th.className = 'text-teal small';
            header.appendChild(th);
        });

        data.rows.forEach(row => {
            const tr = document.createElement('tr');
            data.columns.forEach(col => {
                const td = document.createElement('td');
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
                    pre.className = 'mb-0 small text-muted';
                    td.appendChild(pre);
                } else {
                    td.textContent = value;
                }

                td.className = 'text-muted small';
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
    }

    // Load history
    renderHistory();

    // Assistant Selection State
    let assistantColumns = new Set();
    let assistantFilters = new Map();

    // Query Assistant Toggle
    window.toggleAssistant = function(topicName) {
        const assistantMode = document.body.classList.toggle('assistant-active');
        const btn = document.getElementById('assistantToggleBtn');
        if (assistantMode) {
            btn.textContent = 'Exit Assistant';
            btn.classList.add('btn-teal');
            btn.classList.remove('btn-outline-teal');
            initializeAssistant(topicName);
        } else {
            btn.textContent = 'Query Assistant';
            btn.classList.remove('btn-teal');
            btn.classList.add('btn-outline-teal');
            exitAssistant();
        }
    };

    function initializeAssistant(topicName) {
        const samples = document.querySelectorAll('.sample-msg-pre');
        samples.forEach(pre => {
            const content = pre.textContent.trim();
            if (content.startsWith('{') || content.startsWith('[')) {
                try {
                    const json = JSON.parse(content);
                    const interactive = renderInteractiveJson(json, topicName);
                    pre.style.display = 'none';
                    pre.parentElement.appendChild(interactive);
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

    function renderInteractiveJson(obj, topicName, path = '') {
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

                const keySpan = document.createElement('span');
                keySpan.className = 'json-key text-teal cursor-pointer';
                keySpan.textContent = `"${key}"`;
                keySpan.onclick = (e) => {
                    e.stopPropagation();
                    toggleAssistantColumn(key, keySpan, topicName);
                };

                line.appendChild(keySpan);
                line.appendChild(document.createTextNode(': '));

                const val = obj[key];
                if (typeof val === 'object' && val !== null) {
                    line.appendChild(renderInteractiveJson(val, topicName, key));
                } else {
                    const valSpan = document.createElement('span');
                    valSpan.className = 'json-value text-muted cursor-pointer';
                    valSpan.textContent = typeof val === 'string' ? `"${val}"` : val;
                    valSpan.onclick = (e) => {
                        e.stopPropagation();
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

    function toggleAssistantColumn(column, el, topicName) {
        if (assistantColumns.has(column)) {
            assistantColumns.delete(column);
            el.classList.remove('fw-bold', 'border-bottom');
        } else {
            assistantColumns.add(column);
            el.classList.add('fw-bold', 'border-bottom');
        }
        updateAssistantQuery(topicName);
    }

    function toggleAssistantFilter(column, value, el, topicName) {
        if (assistantFilters.has(column) && assistantFilters.get(column) === value) {
            assistantFilters.delete(column);
            el.classList.remove('bg-teal', 'text-dark', 'px-1', 'rounded');
        } else {
            assistantFilters.set(column, value);
            el.classList.add('bg-teal', 'text-dark', 'px-1', 'rounded');
            // Also ensure it's in columns
            if (!assistantColumns.has(column)) {
                const keyEl = el.parentElement.querySelector('.json-key');
                if (keyEl) toggleAssistantColumn(column, keyEl, topicName);
            }
        }
        updateAssistantQuery(topicName);
    }

    function updateAssistantQuery(topicName) {
        let select = assistantColumns.size > 0 ? Array.from(assistantColumns).join(', ') : '*';
        let sql = `SELECT ${select} FROM ${topicName}`;

        if (assistantFilters.size > 0) {
            let where = Array.from(assistantFilters.entries())
                .map(([k, v]) => {
                    const formattedVal = typeof v === 'string' ? `'${v}'` : v;
                    return `${k} = ${formattedVal}`;
                })
                .join(' AND ');
            sql += ` WHERE ${where}`;
        }

        document.getElementById('generatedSqlPreview').textContent = sql + ';';
    }

    window.openAssistantQuery = function() {
        const sql = document.getElementById('generatedSqlPreview').textContent;
        sessionStorage.setItem('pendingAssistantQuery', sql);
        window.location.href = '/query';
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

    // Topic Search
    const searchInput = document.getElementById('topicSearch');
    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            const term = e.target.value.toLowerCase();
            const rows = document.querySelectorAll('.topic-row');
            rows.forEach(row => {
                const name = row.querySelector('td').textContent.toLowerCase();
                row.style.display = name.includes(term) ? '' : 'none';
            });
        });
    }
});

function saveToHistory(sql) {
    let history = JSON.parse(sessionStorage.getItem('sqlHistory') || '[]');
    // Remove if already exists to move it to the top
    history = history.filter(item => item !== sql);
    history.unshift(sql);
    if (history.length > 20) history.pop();
    sessionStorage.setItem('sqlHistory', JSON.stringify(history));
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

    const history = JSON.parse(sessionStorage.getItem('sqlHistory') || '[]');
    historyList.innerHTML = '';

    if (history.length === 0) {
        historyList.innerHTML = '<li class="list-group-item bg-dark text-muted small border-secondary">No history yet</li>';
        return;
    }

    history.forEach((sql, index) => {
        const li = document.createElement('li');
        li.className = 'list-group-item bg-dark text-light small border-secondary history-item';
        li.style.cursor = 'pointer';
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
        const originalText = button.textContent;
        button.textContent = 'Copied!';
        button.classList.add('btn-teal');
        button.classList.remove('btn-outline-teal');
        setTimeout(() => {
            button.textContent = originalText;
            button.classList.remove('btn-teal');
            button.classList.add('btn-outline-teal');
        }, 2000);
    });
}
