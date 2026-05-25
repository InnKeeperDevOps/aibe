import { api } from './api.js';
import { showToast } from './utils.js';

// Self-service change-password modal. Available to any logged-in user via the
// hamburger menu. The session identifies which user — the backend doesn't
// accept a username from the request, so this can only change the caller's
// own password.

function el(id) { return document.getElementById(id); }

function setError(message) {
    const errEl = el('changePasswordError');
    if (!errEl) return;
    if (message) {
        errEl.textContent = message;
        errEl.style.display = '';
    } else {
        errEl.style.display = 'none';
        errEl.textContent = '';
    }
}

export function openChangePasswordModal() {
    const modal = el('changePasswordModal');
    if (!modal) return;
    // Reset every time it opens.
    const form = el('changePasswordForm');
    if (form) form.reset();
    setError(null);
    const submit = el('changePasswordSubmit');
    if (submit) {
        submit.disabled = false;
        submit.textContent = 'Change Password';
    }
    modal.style.display = '';
    const cur = el('changePasswordCurrent');
    if (cur) cur.focus();
}

export function closeChangePasswordModal() {
    const modal = el('changePasswordModal');
    if (modal) modal.style.display = 'none';
}

export async function submitChangePassword(event) {
    if (event) event.preventDefault();

    const current = (el('changePasswordCurrent') || {}).value || '';
    const next = (el('changePasswordNew') || {}).value || '';
    const confirm = (el('changePasswordConfirm') || {}).value || '';

    if (!current || !next || !confirm) {
        setError('Please fill in every field.');
        return;
    }
    if (next.length < 6) {
        setError('New password must be at least 6 characters long.');
        return;
    }
    if (next !== confirm) {
        setError('The new password and its confirmation do not match.');
        return;
    }
    if (next === current) {
        setError('The new password must be different from your current password.');
        return;
    }

    const submit = el('changePasswordSubmit');
    if (submit) {
        submit.disabled = true;
        submit.textContent = 'Changing…';
    }
    try {
        const res = await api('/auth/change-password', {
            method: 'POST',
            body: JSON.stringify({ currentPassword: current, newPassword: next })
        });
        if (res && res.error) {
            setError(res.error);
            return;
        }
        closeChangePasswordModal();
        showToast('Password changed.');
    } catch (e) {
        setError('Could not change the password: ' + e.message);
    } finally {
        if (submit) {
            submit.disabled = false;
            submit.textContent = 'Change Password';
        }
    }
}
