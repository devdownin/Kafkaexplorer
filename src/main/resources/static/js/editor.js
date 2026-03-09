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
            viewportMargin: Infinity
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
                td.textContent = row[col] !== null ? row[col] : 'NULL';
                td.className = 'text-muted small';
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
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
