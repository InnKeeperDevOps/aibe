import { showToast } from './utils.js';

// Session ID is module-level state (not in shared state.js)
let _pdCurrentSessionId = null;
let _pdFullContent = null;
let _importFileContent = null;

export async function openProjectDefinition() {
    const modal = document.getElementById('project-def-modal');
    if (!modal) return;
    try {
        const res = await fetch('/api/project-definition/state');
        const needsNewSession = res.status === 204;
        let existingState = null;

        if (res.ok && !needsNewSession) {
            existingState = await res.json();
        }

        const isComplete = existingState && ['COMPLETED', 'PR_OPEN'].includes(existingState.status);
        const shouldStartNew = needsNewSession || (existingState && existingState.status === 'FAILED');

        if (isComplete) {
            showProjectDefinitionModal(existingState);
        } else if (shouldStartNew) {
            const startRes = await fetch('/api/project-definition/start', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
            if (!startRes.ok) {
                const err = await startRes.json().catch(() => ({}));
                if (startRes.status === 409) {
                    showToast('A session is already in progress.');
                    return;
                }
                showToast(err.error || 'Could not start session.');
                return;
            }
            const state = await startRes.json();
            showProjectDefinitionModal(state);
        } else if (existingState) {
            showProjectDefinitionModal(existingState);
        }
    } catch (e) {
        showToast('Could not connect to the server.');
    }
}

export async function startNewProjectDefinition() {
    try {
        const startRes = await fetch('/api/project-definition/start', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
        if (!startRes.ok) {
            const err = await startRes.json().catch(() => ({}));
            if (startRes.status === 409) {
                showToast('A session is already in progress.');
                return;
            }
            showToast(err.error || 'Could not start session.');
            return;
        }
        const state = await startRes.json();
        showProjectDefinitionModal(state);
    } catch (e) {
        showToast('Could not connect to the server.');
    }
}

export function showProjectDefinitionModal(state) {
    const modal = document.getElementById('project-def-modal');
    if (!modal) return;
    modal.style.display = '';

    const titleEl = document.getElementById('pd-modal-title');
    if (titleEl) titleEl.textContent = state.isEdit ? 'Update Project Definition' : 'Project Definition';

    const bar = document.getElementById('pd-progress-bar');
    if (bar) bar.style.width = (state.progressPercent || 0) + '%';

    const statusEl = document.getElementById('pd-status-text');
    const spinner = document.getElementById('pd-spinner');
    const spinnerText = document.getElementById('pd-spinner-text');
    const questionArea = document.getElementById('pd-question-area');
    const completeView = document.getElementById('pd-complete-view');
    const noDefActions = document.getElementById('pd-no-def-actions');
    const actionsEl = document.getElementById('pd-actions');

    if (spinner) spinner.style.display = 'none';
    if (questionArea) questionArea.style.display = '';
    if (completeView) completeView.style.display = 'none';
    if (noDefActions) noDefActions.style.display = 'none';
    if (actionsEl) actionsEl.style.display = 'none';

    if (state.status === 'GENERATING') {
        if (statusEl) statusEl.textContent = 'Creating your project definition document...';
        if (spinner) spinner.style.display = '';
        if (spinnerText) spinnerText.textContent = 'Generating — this may take a moment';
        if (questionArea) questionArea.style.display = 'none';
    } else if (state.status === 'SAVING') {
        if (statusEl) statusEl.textContent = 'Saving and opening a pull request...';
        if (spinner) spinner.style.display = '';
        if (spinnerText) spinnerText.textContent = 'Saving changes and opening a pull request';
        if (questionArea) questionArea.style.display = 'none';
    } else if (state.status === 'COMPLETED') {
        if (statusEl) statusEl.textContent = 'Complete';
        if (questionArea) questionArea.style.display = 'none';
        renderProjectDefinitionComplete(state);
    } else if (state.status === 'PR_OPEN') {
        if (statusEl) statusEl.textContent = 'Pull request is open and waiting for review';
        if (questionArea) questionArea.style.display = 'none';
        renderProjectDefinitionComplete(state);
    } else if (state.status === 'FAILED') {
        if (statusEl) statusEl.textContent = 'Something went wrong.';
        if (questionArea) questionArea.style.display = 'none';
        if (completeView) completeView.style.display = '';
        const errSection = document.getElementById('pd-error-section');
        if (errSection) {
            errSection.style.display = '';
            errSection.innerHTML = (state.errorMessage || 'An error occurred.') +
                '<br><button class="btn btn-primary btn-sm" style="margin-top:0.75rem" ' +
                'onclick="app.retryProjectDefinition()">Try Again</button>';
        }
    } else {
        const questionEl = document.getElementById('pd-question');
        if (questionEl) questionEl.textContent = state.currentQuestion || '';

        const optionsEl = document.getElementById('pd-options');
        const textInput = document.getElementById('pd-text-input');
        const textSubmit = document.getElementById('pd-text-submit-area');

        if ((state.questionType === 'MULTIPLE_CHOICE' || state.questionType === 'multiple_choice') && state.options && state.options.length > 0) {
            if (textInput) textInput.style.display = 'none';
            if (textSubmit) textSubmit.style.display = 'none';
            if (optionsEl) {
                optionsEl.innerHTML = '';
                state.options.forEach(opt => {
                    const btn = document.createElement('button');
                    btn.className = 'btn btn-outline';
                    btn.textContent = opt;
                    btn.style.textAlign = 'left';
                    btn.onclick = () => submitProjectDefinitionAnswer(opt, state.sessionId);
                    optionsEl.appendChild(btn);
                });
            }
        } else {
            if (optionsEl) optionsEl.innerHTML = '';
            if (textInput) { textInput.style.display = ''; textInput.value = ''; }
            if (textSubmit) textSubmit.style.display = '';
            const submitBtn = document.getElementById('pd-submit-btn');
            if (submitBtn) {
                submitBtn.disabled = false;
                submitBtn.onclick = () => submitProjectDefinitionAnswer(document.getElementById('pd-text-input').value, state.sessionId);
            }
        }

        let label = '';
        if (state.status === 'ACTIVE') {
            label = state.isEdit
                ? 'Review and update your existing project definition.'
                : 'Answer the questions below to define your project.';
        }
        if (statusEl) statusEl.textContent = label;
    }

    _pdCurrentSessionId = state.sessionId;
}

export async function submitProjectDefinitionAnswer(answer, sessionId) {
    const id = sessionId || _pdCurrentSessionId;
    if (!id) return;
    if (!answer || !answer.trim()) {
        showToast('Please enter an answer before continuing.');
        return;
    }
    const submitBtn = document.getElementById('pd-submit-btn');
    if (submitBtn) submitBtn.disabled = true;
    try {
        const res = await fetch('/api/project-definition/' + id + '/answer', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ answer: answer.trim() })
        });
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            showToast(err.error || 'Could not submit answer.');
            if (submitBtn) submitBtn.disabled = false;
            return;
        }
        const state = await res.json();
        showProjectDefinitionModal(state);
    } catch (e) {
        showToast('Could not connect to the server.');
        if (submitBtn) submitBtn.disabled = false;
    }
}

export function renderProjectDefinitionComplete(state) {
    const completeView = document.getElementById('pd-complete-view');
    if (!completeView) return;
    completeView.style.display = '';

    const prSection = document.getElementById('pd-pr-section');
    const prOpenSection = document.getElementById('pd-pr-open-section');
    const prLink = document.getElementById('pd-pr-link');
    const prOpenLink = document.getElementById('pd-pr-open-link');
    const contentText = document.getElementById('pd-content-text');
    const contentExpander = document.getElementById('pd-content-expander');
    const errSection = document.getElementById('pd-error-section');
    const actionsEl = document.getElementById('pd-actions');

    if (errSection) errSection.style.display = 'none';

    if (state.status === 'PR_OPEN') {
        if (prSection) prSection.style.display = 'none';
        if (prOpenSection) prOpenSection.style.display = '';
        if (prOpenLink && state.prUrl) prOpenLink.href = state.prUrl;
    } else if (state.prUrl) {
        if (prOpenSection) prOpenSection.style.display = 'none';
        if (prSection) prSection.style.display = '';
        if (prLink) prLink.href = state.prUrl;
    } else {
        if (prSection) prSection.style.display = 'none';
        if (prOpenSection) prOpenSection.style.display = 'none';
    }

    if (state.generatedContent && contentText) {
        const preview = state.generatedContent.substring(0, 500);
        contentText.textContent = preview;
        if (state.generatedContent.length > 500) {
            _pdFullContent = state.generatedContent;
            if (contentExpander) contentExpander.style.display = '';
        } else {
            if (contentExpander) contentExpander.style.display = 'none';
        }
    }

    if (actionsEl) {
        const hasContent = !!(state.generatedContent);
        const downloadBtn = document.getElementById('pd-download-btn');
        if (downloadBtn) downloadBtn.style.display = hasContent ? '' : 'none';
        actionsEl.style.display = 'flex';
    }

    const btn = document.getElementById('project-def-btn');
    if (btn) btn.textContent = 'View Definition';
}

export function expandProjectDefinitionContent(e) {
    e.preventDefault();
    const contentText = document.getElementById('pd-content-text');
    if (contentText && _pdFullContent) {
        contentText.textContent = _pdFullContent;
        contentText.style.maxHeight = 'none';
    }
    const expander = document.getElementById('pd-content-expander');
    if (expander) expander.style.display = 'none';
}

export function closeProjectDefinitionModal() {
    const modal = document.getElementById('project-def-modal');
    if (modal) modal.style.display = 'none';
}

export async function retryProjectDefinition() {
    try {
        await fetch('/api/project-definition/reset', { method: 'POST' });
        const startRes = await fetch('/api/project-definition/start', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
        if (!startRes.ok) {
            const err = await startRes.json().catch(() => ({}));
            showToast(err.error || 'Could not start a new session.');
            return;
        }
        const state = await startRes.json();
        showProjectDefinitionModal(state);
    } catch (e) {
        showToast('Could not connect to the server.');
    }
}

export async function downloadProjectDefinition() {
    try {
        const res = await fetch('/api/project-definition/download');
        if (!res.ok) {
            const err = await res.json().catch(() => ({}));
            showToast(err.error || 'Could not download project definition.');
            return;
        }
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'PROJECT_DEFINITION.md';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    } catch (e) {
        showToast('Could not connect to the server.');
    }
}

export function openImportDefinitionModal() {
    const modal = document.getElementById('pd-import-modal');
    if (!modal) return;
    modal.style.display = '';
    const textArea = document.getElementById('pd-import-text');
    if (textArea) textArea.value = '';
    const fileInput = document.getElementById('pd-import-file');
    if (fileInput) fileInput.value = '';
    const fileInfo = document.getElementById('pd-import-file-info');
    if (fileInfo) fileInfo.style.display = 'none';
    const errEl = document.getElementById('pd-import-error');
    if (errEl) errEl.style.display = 'none';
    _importFileContent = null;

    const dropzone = document.getElementById('pd-import-dropzone');
    if (dropzone && !dropzone._listenersAttached) {
        dropzone._listenersAttached = true;
        dropzone.addEventListener('dragover', (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.style.borderColor = 'var(--primary)';
            dropzone.style.background = 'rgba(59,130,246,0.05)';
        });
        dropzone.addEventListener('dragleave', (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.style.borderColor = 'var(--border)';
            dropzone.style.background = '';
        });
        dropzone.addEventListener('drop', (e) => {
            e.preventDefault();
            e.stopPropagation();
            dropzone.style.borderColor = 'var(--border)';
            dropzone.style.background = '';
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                processImportFile(e.dataTransfer.files[0]);
            }
        });
    }
}

export function closeImportDefinitionModal() {
    const modal = document.getElementById('pd-import-modal');
    if (modal) modal.style.display = 'none';
    _importFileContent = null;
}

export function handleImportFileSelect(event) {
    const file = event.target.files && event.target.files[0];
    if (file) processImportFile(file);
}

export function processImportFile(file) {
    const reader = new FileReader();
    reader.onload = (e) => {
        _importFileContent = e.target.result;
        const fileInfo = document.getElementById('pd-import-file-info');
        const fileName = document.getElementById('pd-import-file-name');
        if (fileInfo) fileInfo.style.display = '';
        if (fileName) fileName.textContent = file.name + ' (' + formatFileSize(file.size) + ')';
        const textArea = document.getElementById('pd-import-text');
        if (textArea) textArea.value = '';
    };
    reader.readAsText(file);
}

export function clearImportFile(event) {
    if (event) event.preventDefault();
    _importFileContent = null;
    const fileInfo = document.getElementById('pd-import-file-info');
    if (fileInfo) fileInfo.style.display = 'none';
    const fileInput = document.getElementById('pd-import-file');
    if (fileInput) fileInput.value = '';
}

export function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
}

export async function submitImportDefinition() {
    const errEl = document.getElementById('pd-import-error');
    const submitBtn = document.getElementById('pd-import-submit');

    const pastedContent = (document.getElementById('pd-import-text') || {}).value || '';
    const content = _importFileContent || pastedContent;

    if (!content || !content.trim()) {
        if (errEl) {
            errEl.textContent = 'Please upload a file or paste the definition content.';
            errEl.style.display = '';
        }
        return;
    }

    if (errEl) errEl.style.display = 'none';
    if (submitBtn) submitBtn.disabled = true;

    try {
        const formData = new FormData();
        formData.append('content', content);

        const res = await fetch('/api/project-definition/import', {
            method: 'POST',
            body: formData
        });

        const data = await res.json().catch(() => ({}));

        if (!res.ok) {
            if (errEl) {
                errEl.textContent = data.error || 'Import failed. Please try again.';
                errEl.style.display = '';
            }
            if (submitBtn) submitBtn.disabled = false;
            return;
        }

        closeImportDefinitionModal();
        showToast('Project definition imported successfully.');
        const btn = document.getElementById('project-def-btn');
        if (btn) btn.textContent = 'Update Definition';
    } catch (e) {
        if (errEl) {
            errEl.textContent = 'Could not connect to the server.';
            errEl.style.display = '';
        }
        if (submitBtn) submitBtn.disabled = false;
    }
}

export function onProjectDefinitionUpdate(wsData) {
    showProjectDefinitionModal(wsData);
}
