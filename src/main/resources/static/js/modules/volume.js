import { state } from './state.js';
import { api } from './api.js';

// Debounce timer so dragging the slider doesn't fire a request per pixel.
let saveTimer = null;

// Fetch the logged-in user's saved volume and reflect it in the UI.
export async function loadVolume() {
    if (!state.loggedIn) {
        applyVolumeToUi();
        return;
    }
    try {
        const data = await api('/users/me/preferences');
        if (data && typeof data.notificationVolume === 'number') {
            state.notificationVolume = data.notificationVolume;
        }
    } catch (e) {
        // Keep the current/default volume on failure.
    }
    applyVolumeToUi();
}

function applyVolumeToUi() {
    const slider = document.getElementById('volumeSlider');
    const label = document.getElementById('volumeValue');
    if (slider) slider.value = state.notificationVolume;
    if (label) label.textContent = state.notificationVolume + '%';
    updateVolumeControlVisibility();
}

// Show the volume control only when logged in (preference is per-user).
export function updateVolumeControlVisibility() {
    const control = document.getElementById('volumeControl');
    if (control) control.style.display = state.loggedIn ? '' : 'none';
}

// Called from the slider's oninput handler. Updates the label immediately and
// persists the value to the server (debounced).
export function setVolume(value) {
    const v = Math.max(0, Math.min(100, parseInt(value, 10) || 0));
    state.notificationVolume = v;
    const label = document.getElementById('volumeValue');
    if (label) label.textContent = v + '%';
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => persistVolume(v), 400);
}

async function persistVolume(v) {
    if (!state.loggedIn) return;
    try {
        await api('/users/me/preferences', {
            method: 'PUT',
            body: JSON.stringify({ notificationVolume: v })
        });
    } catch (e) {
        // Ignore transient persistence failures; the value stays in state.
    }
}

// Play a short notification beep at the user's saved volume.
export function playNotificationSound() {
    const vol = (state.notificationVolume ?? 75) / 100;
    if (vol <= 0) return;
    try {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        const ctx = new Ctx();
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.value = 880;
        // Scale by 0.2 so a max-volume beep stays gentle.
        gain.gain.value = vol * 0.2;
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.18);
        osc.onended = () => ctx.close();
    } catch (e) {
        // Audio unavailable (e.g. autoplay policy) — fail silently.
    }
}
