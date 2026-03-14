const templates = {
    'COUNTER': "SELECT COUNT(*) as metric_value FROM {table}",
    'GAUGE': "SELECT AVG({column}) as metric_value FROM {table}",
    'HISTOGRAM': "SELECT COUNT(*) as metric_value, window_start, window_end FROM TABLE(TUMBLE(TABLE {table}, DESCRIPTOR({time_col}), INTERVAL '1' MINUTE)) GROUP BY window_start, window_end"
};

let metricEditor;

document.addEventListener('DOMContentLoaded', () => {
    const editorEl = document.getElementById('metricSqlEditor');
    if (editorEl) {
        metricEditor = CodeMirror(editorEl, {
            mode: 'text/x-sql',
            theme: 'material-darker',
            lineNumbers: true,
            indentWithTabs: true,
            smartIndent: true,
            matchBrackets: true,
            viewportMargin: Infinity,
            extraKeys: { "Ctrl-Space": "autocomplete" }
        });
        metricEditor.setSize("100%", "200px");

        // Sync CodeMirror with hidden textarea on change
        metricEditor.on('change', (instance) => {
            document.getElementById('metricSql').value = instance.getValue();
        });
    }

    fetchTablesForSelector();
});

async function fetchTablesForSelector() {
    try {
        const response = await fetch('/api/topics');
        const topics = await response.json();
        const selector = document.getElementById('tableSelector');
        if (selector) {
            topics.forEach(topic => {
                const opt = document.createElement('option');
                opt.value = topic;
                opt.textContent = topic;
                selector.appendChild(opt);
            });
        }
    } catch (e) {
        console.error("Failed to fetch tables", e);
    }
}

function prepareAddMetric() {
    document.getElementById('modalTitle').innerHTML = '<span class="material-symbols-outlined">add_chart</span> Create Metric';
    document.getElementById('metricForm').reset();
    document.getElementById('metricId').value = '';
    document.getElementById('testResultArea').classList.add('hidden');
    if (metricEditor) {
        metricEditor.setValue('');
    }
    onMetricTypeChange();
}

function editMetric(button) {
    document.getElementById('modalTitle').innerHTML = '<span class="material-symbols-outlined">edit_note</span> Edit Metric';
    const id = button.getAttribute('data-id');
    const name = button.getAttribute('data-name');
    const type = button.getAttribute('data-type');
    const sql = button.getAttribute('data-sql');
    const description = button.getAttribute('data-description');

    document.getElementById('metricId').value = id;
    document.getElementById('metricName').value = name;
    document.getElementById('metricType').value = type;
    document.getElementById('metricDescription').value = description;
    document.getElementById('testResultArea').classList.add('hidden');

    if (metricEditor) {
        metricEditor.setValue(sql);
    } else {
        document.getElementById('metricSql').value = sql;
    }

    const modal = new bootstrap.Modal(document.getElementById('metricModal'));
    modal.show();
}

function onMetricTypeChange() {
    const currentValue = metricEditor ? metricEditor.getValue() : document.getElementById('metricSql').value;
    const isTemplate = Object.values(templates).some(t => {
        const regex = new RegExp(t.replace(/{table}/g, '.*').replace(/{column}/g, '.*').replace(/{time_col}/g, '.*'), 'i');
        return regex.test(currentValue);
    }) || !currentValue;

    if (isTemplate) {
        applyTemplate();
    }
}

function applyTemplate() {
    const type = document.getElementById('metricType').value;
    const table = document.getElementById('tableSelector').value;
    let template = templates[type] || '';

    template = template.replace(/{table}/g, table);

    if (metricEditor) {
        metricEditor.setValue(template);
    } else {
        document.getElementById('metricSql').value = template;
    }
}

async function testMetric() {
    const sql = metricEditor ? metricEditor.getValue() : document.getElementById('metricSql').value;
    const resultArea = document.getElementById('testResultArea');
    const resultContent = document.getElementById('testResultContent');

    resultArea.classList.remove('hidden');
    resultContent.innerHTML = '<div class="flex items-center gap-2"><div class="animate-spin rounded-full h-3 w-3 border-2 border-primary border-t-transparent"></div> <span>Testing...</span></div>';
    resultContent.className = 'p-4 bg-background-dark/80 border border-primary/10 rounded-lg font-mono text-xs text-slate-300';

    try {
        const response = await fetch('/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ sql, maxRows: 1, timeout: 5000 })
        });

        const data = await response.json();

        if (data.error) {
            resultContent.textContent = 'Error: ' + data.error;
            resultContent.classList.add('text-danger');
        } else if (data.rows.length === 0) {
            resultContent.textContent = 'Success! Query executed but returned no rows yet (waiting for data?).';
            resultContent.classList.add('text-warning');
        } else {
            const val = data.rows[0]['metric_value'];
            if (val === undefined) {
                resultContent.textContent = 'Warning: Query succeeded but did not return a "metric_value" column.';
                resultContent.classList.add('text-warning');
            } else {
                resultContent.textContent = 'Success! metric_value = ' + val;
                resultContent.classList.add('text-success');
            }
        }
    } catch (err) {
        resultContent.textContent = 'Network Error: ' + err.message;
        resultContent.classList.add('text-danger');
    }
}
