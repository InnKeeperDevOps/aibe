import { state } from './state.js';
import { api } from './api.js';
import { timeAgo } from './utils.js';
import { refreshClaudeCliLogin } from './claudeCliLogin.js';

export async function loadSettings() {
    const settings = await api('/settings');
    state.settings = settings;
    document.getElementById('settingSiteName').value = settings.siteName || '';
    document.getElementById('settingRepoUrl').value = settings.targetRepoUrl || '';
    const managedFoldersEl = document.getElementById('settingManagedFolders');
    if (managedFoldersEl) managedFoldersEl.value = settings.managedFolders || '';
    document.getElementById('settingTimeout').value = settings.suggestionTimeoutMinutes || 1440;
    document.getElementById('settingGithubToken').value = settings.githubToken || '';
    document.getElementById('settingClaudeModel').value = settings.claudeModel || '';
    document.getElementById('settingClaudeModelExpert').value = settings.claudeModelExpert || '';
    document.getElementById('settingClaudeMaxTurnsExpert').value = settings.claudeMaxTurnsExpert || '';
    document.getElementById('settingMaxConcurrentSuggestions').value = settings.maxConcurrentSuggestions || 1;
    document.getElementById('settingAnonymous').checked = settings.allowAnonymousSuggestions;
    document.getElementById('settingVoting').checked = settings.allowVoting;
    document.getElementById('settingApproval').checked = settings.requireApproval;
    document.getElementById('autoMergePr').checked = settings.autoMergePr || false;
    document.getElementById('registrationsEnabled').checked = settings.registrationsEnabled ?? true;
    const slackInput = document.getElementById('settingSlackWebhookUrl');
    slackInput.value = settings.slackWebhookUrl || '';
    slackInput.placeholder = settings.slackWebhookUrl
        ? 'Currently configured — enter new URL to replace'
        : 'https://hooks.slack.com/services/...';
    const sshKeyInput = document.getElementById('settingGitSshKey');
    if (sshKeyInput) {
        sshKeyInput.value = '';
        const sshKeyStatus = document.getElementById('settingGitSshKeyStatus');
        if (sshKeyStatus) {
            sshKeyStatus.textContent = settings.hasGitSshKey
                ? 'A key is currently configured. Paste a new key to replace it, or leave blank to keep the existing key.'
                : 'Paste a private key to authenticate git operations (clone, pull, push). Leave blank to keep the existing key.';
        }
    }
    _renderGitSshPublicKey(settings.gitSshPublicKey);
    await loadPendingUsers();
    await loadGroups();
    await loadAllUsers();
    refreshClaudeCliLogin();
}

export async function loadGroups() {
    const section = document.getElementById('groupsSection');
    if (!section) return;

    const canManage = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    section.style.display = canManage ? '' : 'none';
    if (!canManage) return;

    const container = document.getElementById('groupsContainer');
    try {
        const groups = await api('/groups');
        if (groups.error) {
            container.textContent = 'Failed to load groups.';
            return;
        }
        state.groups = groups;
        _renderGroupsTable(groups);
    } catch (err) {
        container.textContent = 'Failed to load groups.';
    }
}

function _renderGroupsTable(groups) {
    const container = document.getElementById('groupsContainer');
    if (!groups || groups.length === 0) {
        container.textContent = 'No groups found.';
        return;
    }

    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';

    const thead = document.createElement('thead');
    thead.innerHTML = '<tr>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Name</th>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Permissions</th>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Actions</th>' +
        '</tr>';
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    groups.forEach(group => {
        const tr = document.createElement('tr');

        const tdName = document.createElement('td');
        tdName.style.padding = '0.5rem';
        tdName.textContent = group.name;

        const tdPerms = document.createElement('td');
        tdPerms.style.padding = '0.5rem';
        const permLabels = [];
        if (group.canCreateSuggestions) permLabels.push('Create');
        if (group.canVote) permLabels.push('Vote');
        if (group.canReply) permLabels.push('Reply');
        if (group.canApproveDenySuggestions) permLabels.push('Approve/Deny');
        if (group.canManageSettings) permLabels.push('Settings');
        if (group.canManageUsers) permLabels.push('Users');
        tdPerms.textContent = permLabels.length > 0 ? permLabels.join(', ') : 'None';

        const tdActions = document.createElement('td');
        tdActions.style.padding = '0.5rem';

        const editBtn = document.createElement('button');
        editBtn.className = 'btn btn-outline btn-sm';
        editBtn.textContent = 'Edit';
        editBtn.style.marginRight = '0.5rem';
        editBtn.onclick = () => app.editGroup(group.id);

        const deleteBtn = document.createElement('button');
        deleteBtn.className = 'btn btn-danger btn-sm';
        deleteBtn.textContent = 'Delete';
        deleteBtn.onclick = () => app.deleteGroup(group.id);

        tdActions.appendChild(editBtn);
        tdActions.appendChild(deleteBtn);

        tr.appendChild(tdName);
        tr.appendChild(tdPerms);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    container.innerHTML = '';
    container.appendChild(table);
}

export function editGroup(id) {
    const group = (state.groups || []).find(g => g.id === id);
    if (!group) return;

    document.getElementById('groupEditId').value = id;
    document.getElementById('groupName').value = group.name;
    document.getElementById('groupCanCreateSuggestions').checked = group.canCreateSuggestions;
    document.getElementById('groupCanVote').checked = group.canVote;
    document.getElementById('groupCanReply').checked = group.canReply;
    document.getElementById('groupCanApproveDenySuggestions').checked = group.canApproveDenySuggestions;
    document.getElementById('groupCanManageSettings').checked = group.canManageSettings;
    document.getElementById('groupCanManageUsers').checked = group.canManageUsers;

    document.getElementById('groupFormTitle').textContent = 'Edit Group';
    document.getElementById('groupSaveBtn').textContent = 'Save Changes';
    document.getElementById('groupCancelBtn').style.display = '';
}

export function cancelGroupEdit() {
    document.getElementById('groupForm').reset();
    document.getElementById('groupEditId').value = '';
    document.getElementById('groupFormTitle').textContent = 'Create New Group';
    document.getElementById('groupSaveBtn').textContent = 'Create Group';
    document.getElementById('groupCancelBtn').style.display = 'none';
}

export async function saveGroup(e) {
    e.preventDefault();
    const editId = document.getElementById('groupEditId').value;
    const payload = {
        name: document.getElementById('groupName').value,
        canCreateSuggestions: document.getElementById('groupCanCreateSuggestions').checked,
        canVote: document.getElementById('groupCanVote').checked,
        canReply: document.getElementById('groupCanReply').checked,
        canApproveDenySuggestions: document.getElementById('groupCanApproveDenySuggestions').checked,
        canManageSettings: document.getElementById('groupCanManageSettings').checked,
        canManageUsers: document.getElementById('groupCanManageUsers').checked
    };

    const path = editId ? '/groups/' + editId : '/groups';
    const method = editId ? 'PUT' : 'POST';
    const data = await api(path, { method, body: JSON.stringify(payload) });
    if (data.error) { alert(data.error); return; }

    cancelGroupEdit();
    await loadGroups();
}

export async function deleteGroup(id) {
    if (!window.confirm('Are you sure you want to delete this group?')) return;
    const data = await api('/groups/' + id, { method: 'DELETE' });
    if (data.error) { alert(data.error); return; }
    await loadGroups();
}

export function showUserTab(tab) {
    const pendingContainer = document.getElementById('pendingUsersContainer');
    const allContainer = document.getElementById('allUsersContainer');
    const pendingBtn = document.getElementById('tabPendingBtn');
    const allBtn = document.getElementById('tabAllBtn');
    if (!pendingContainer || !allContainer) return;
    if (tab === 'all') {
        pendingContainer.style.display = 'none';
        allContainer.style.display = '';
        if (pendingBtn) { pendingBtn.className = 'btn btn-outline btn-sm'; }
        if (allBtn) { allBtn.className = 'btn btn-primary btn-sm'; }
    } else {
        pendingContainer.style.display = '';
        allContainer.style.display = 'none';
        if (pendingBtn) { pendingBtn.className = 'btn btn-primary btn-sm'; }
        if (allBtn) { allBtn.className = 'btn btn-outline btn-sm'; }
    }
}

export async function loadPendingUsers() {
    const container = document.getElementById('pendingUsersContainer');
    if (!container) return;

    const section = document.getElementById('allUsersSection');
    const canManage = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    if (section) section.style.display = canManage ? '' : 'none';
    if (!canManage) return;

    try {
        const users = await api('/users/pending');
        if (!users || users.length === 0) {
            container.textContent = 'No pending registrations';
            return;
        }

        const table = document.createElement('table');
        table.style.cssText = 'width:100%;border-collapse:collapse';

        const thead = document.createElement('thead');
        thead.innerHTML = '<tr>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Username</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Registered</th>' +
            '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Actions</th>' +
            '</tr>';
        table.appendChild(thead);

        const tbody = document.createElement('tbody');
        users.forEach(user => {
            const tr = document.createElement('tr');

            const tdUser = document.createElement('td');
            tdUser.style.padding = '0.5rem';
            tdUser.textContent = user.username;

            const tdDate = document.createElement('td');
            tdDate.style.padding = '0.5rem';
            tdDate.textContent = timeAgo(user.createdAt);

            const tdActions = document.createElement('td');
            tdActions.style.padding = '0.5rem';

            const approveBtn = document.createElement('button');
            approveBtn.className = 'btn btn-success btn-sm';
            approveBtn.textContent = 'Approve';
            approveBtn.style.marginRight = '0.5rem';
            approveBtn.onclick = () => app.approveUser(user.id);

            const denyBtn = document.createElement('button');
            denyBtn.className = 'btn btn-danger btn-sm';
            denyBtn.textContent = 'Deny';
            denyBtn.onclick = () => app.denyUser(user.id);

            tdActions.appendChild(approveBtn);
            tdActions.appendChild(denyBtn);

            tr.appendChild(tdUser);
            tr.appendChild(tdDate);
            tr.appendChild(tdActions);
            tbody.appendChild(tr);
        });
        table.appendChild(tbody);

        container.innerHTML = '';
        container.appendChild(table);
    } catch (err) {
        container.textContent = 'Failed to load pending registrations.';
    }
}

export async function approveUser(userId) {
    const data = await api('/users/' + userId + '/approve', { method: 'POST' });
    if (data.error) { alert(data.error); return; }
    await loadPendingUsers();
}

export async function denyUser(userId) {
    const data = await api('/users/' + userId + '/deny', { method: 'POST' });
    if (data.error) { alert(data.error); return; }
    await loadPendingUsers();
}

export async function loadAllUsers() {
    const container = document.getElementById('allUsersContainer');
    if (!container) return;

    const canManage = state.role === 'ROOT_ADMIN' || state.role === 'ADMIN';
    if (!canManage) return;

    try {
        const users = await api('/users');
        if (users.error) {
            container.textContent = 'Failed to load users.';
            return;
        }
        _renderAllUsersTable(users);
    } catch (err) {
        container.textContent = 'Failed to load users.';
    }
}

function _renderAllUsersTable(users) {
    const container = document.getElementById('allUsersContainer');
    const groups = state.groups || [];

    if (!users || users.length === 0) {
        container.textContent = 'No users found.';
        return;
    }

    const table = document.createElement('table');
    table.style.cssText = 'width:100%;border-collapse:collapse';

    const thead = document.createElement('thead');
    thead.innerHTML = '<tr>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Username</th>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Role</th>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Group</th>' +
        '<th style="text-align:left;padding:0.5rem;border-bottom:1px solid var(--border)">Status</th>' +
        '</tr>';
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    users.forEach(user => {
        const tr = document.createElement('tr');

        const tdUser = document.createElement('td');
        tdUser.style.padding = '0.5rem';
        tdUser.textContent = user.username;

        const tdRole = document.createElement('td');
        tdRole.style.padding = '0.5rem';
        const roleBadge = document.createElement('span');
        const roleLabel = user.role === 'ROOT_ADMIN' ? 'Root Admin'
            : user.role === 'ADMIN' ? 'Admin'
            : 'User';
        roleBadge.textContent = roleLabel;
        roleBadge.style.cssText = 'padding:0.15rem 0.4rem;border-radius:3px;font-size:0.8em;background:var(--border);';
        tdRole.appendChild(roleBadge);

        const tdGroup = document.createElement('td');
        tdGroup.style.padding = '0.5rem';
        const select = document.createElement('select');
        select.className = 'form-control';
        select.style.cssText = 'padding:0.2rem 0.4rem;font-size:0.9em;';
        const noGroupOpt = document.createElement('option');
        noGroupOpt.value = '';
        noGroupOpt.textContent = '— No group —';
        select.appendChild(noGroupOpt);
        groups.forEach(g => {
            const opt = document.createElement('option');
            opt.value = g.id;
            opt.textContent = g.name;
            if (user.groupId && user.groupId === g.id) opt.selected = true;
            select.appendChild(opt);
        });
        select.onchange = () => {
            const gid = select.value ? parseInt(select.value, 10) : null;
            app.assignUserGroup(user.id, gid);
        };
        tdGroup.appendChild(select);

        const tdStatus = document.createElement('td');
        tdStatus.style.padding = '0.5rem';
        const statusText = user.denied ? 'Denied'
            : user.approved ? 'Active'
            : 'Pending';
        tdStatus.textContent = statusText;

        tr.appendChild(tdUser);
        tr.appendChild(tdRole);
        tr.appendChild(tdGroup);
        tr.appendChild(tdStatus);
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);

    container.innerHTML = '';
    container.appendChild(table);
}

export async function assignUserGroup(userId, groupId) {
    const data = await api('/users/' + userId + '/group', {
        method: 'PUT',
        body: JSON.stringify({ groupId: groupId })
    });
    if (data.error) { alert(data.error); }
}

function _renderGitSshPublicKey(publicKey) {
    const box = document.getElementById('settingGitSshPublicKeyBox');
    const textarea = document.getElementById('settingGitSshPublicKey');
    if (!box || !textarea) return;
    if (publicKey && publicKey.trim()) {
        textarea.value = publicKey;
        box.style.display = '';
    } else {
        textarea.value = '';
        box.style.display = 'none';
    }
    const copyStatus = document.getElementById('settingGitSshPublicKeyCopyStatus');
    if (copyStatus) copyStatus.textContent = '';
}

export async function generateGitSshKey() {
    if (!window.confirm('Generate a new SSH key? This replaces any existing private key configured here.')) return;
    const data = await api('/settings/git-ssh-key/generate', { method: 'POST' });
    if (data.error) { alert(data.error); return; }
    state.settings = data;
    const sshKeyInput = document.getElementById('settingGitSshKey');
    if (sshKeyInput) sshKeyInput.value = '';
    const sshKeyStatus = document.getElementById('settingGitSshKeyStatus');
    if (sshKeyStatus) {
        sshKeyStatus.textContent = 'A key is currently configured. Paste a new key to replace it, or leave blank to keep the existing key.';
    }
    _renderGitSshPublicKey(data.gitSshPublicKey);
    alert('SSH key generated. Copy the public key below into your git host (e.g. GitHub deploy keys).');
}

export async function copyGitSshPublicKey() {
    const textarea = document.getElementById('settingGitSshPublicKey');
    const status = document.getElementById('settingGitSshPublicKeyCopyStatus');
    if (!textarea || !textarea.value) return;
    try {
        await navigator.clipboard.writeText(textarea.value);
        if (status) status.textContent = 'Copied to clipboard.';
    } catch (e) {
        textarea.select();
        document.execCommand('copy');
        if (status) status.textContent = 'Copied to clipboard.';
    }
}

export async function saveSettings() {
    const sshKeyEl = document.getElementById('settingGitSshKey');
    // Send the SSH key only when the textarea has content. Sending null preserves the
    // existing key on the backend (which never echoes the stored key back to the UI).
    const sshKeyValue = sshKeyEl && sshKeyEl.value.trim() ? sshKeyEl.value : null;
    const data = await api('/settings', {
        method: 'PUT',
        body: JSON.stringify({
            siteName: document.getElementById('settingSiteName').value,
            targetRepoUrl: document.getElementById('settingRepoUrl').value,
            managedFolders: (document.getElementById('settingManagedFolders') || {}).value || null,
            suggestionTimeoutMinutes: parseInt(document.getElementById('settingTimeout').value) || 1440,
            githubToken: document.getElementById('settingGithubToken').value || null,
            claudeModel: document.getElementById('settingClaudeModel').value || null,
            claudeModelExpert: document.getElementById('settingClaudeModelExpert').value || null,
            claudeMaxTurnsExpert: parseInt(document.getElementById('settingClaudeMaxTurnsExpert').value) || null,
            maxConcurrentSuggestions: parseInt(document.getElementById('settingMaxConcurrentSuggestions').value) || 1,
            allowAnonymousSuggestions: document.getElementById('settingAnonymous').checked,
            allowVoting: document.getElementById('settingVoting').checked,
            requireApproval: document.getElementById('settingApproval').checked,
            autoMergePr: document.getElementById('autoMergePr').checked,
            slackWebhookUrl: document.getElementById('settingSlackWebhookUrl').value || null,
            registrationsEnabled: document.getElementById('registrationsEnabled').checked,
            gitSshKey: sshKeyValue
        })
    });
    if (data.error) { alert(data.error); return; }
    state.settings = data;
    const siteName = document.getElementById('settingSiteName').value;
    if (siteName) document.getElementById('siteName').textContent = siteName;
    if (sshKeyEl) sshKeyEl.value = '';
    const sshKeyStatus = document.getElementById('settingGitSshKeyStatus');
    if (sshKeyStatus) {
        sshKeyStatus.textContent = data.hasGitSshKey
            ? 'A key is currently configured. Paste a new key to replace it, or leave blank to keep the existing key.'
            : 'Paste a private key to authenticate git operations (clone, pull, push). Leave blank to keep the existing key.';
    }
    _renderGitSshPublicKey(data.gitSshPublicKey);
    alert('Settings saved!');
}

export async function createAdmin(e) {
    e.preventDefault();
    const data = await api('/auth/create-admin', {
        method: 'POST',
        body: JSON.stringify({
            username: document.getElementById('newAdminUsername').value,
            password: document.getElementById('newAdminPassword').value
        })
    });
    if (data.error) { alert(data.error); return; }
    alert('Admin user created: ' + data.username);
    document.getElementById('newAdminUsername').value = '';
    document.getElementById('newAdminPassword').value = '';
}
