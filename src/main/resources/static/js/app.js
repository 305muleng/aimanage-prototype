document.querySelectorAll('[data-user]').forEach(button => {
    button.addEventListener('click', () => {
        const username = document.querySelector('input[name="username"]');
        const password = document.querySelector('input[name="password"]');
        if (username && password) {
            username.value = button.dataset.user;
            password.value = '123456';
            password.focus();
        }
    });
});

document.querySelectorAll('[data-dialog-open]').forEach(button => {
    button.addEventListener('click', () => {
        const dialog = document.getElementById(button.dataset.dialogOpen);
        if (dialog) dialog.showModal();
    });
});

document.querySelectorAll('.modal-close, .modal-cancel').forEach(button => {
    button.addEventListener('click', () => button.closest('dialog')?.close());
});

document.querySelectorAll('dialog').forEach(dialog => {
    dialog.addEventListener('click', event => {
        const bounds = dialog.getBoundingClientRect();
        const inside = event.clientX >= bounds.left && event.clientX <= bounds.right
            && event.clientY >= bounds.top && event.clientY <= bounds.bottom;
        if (!inside) dialog.close();
    });
});

let toastTimer;
// Only dialogs already rendered for this authenticated user can be opened.
const linkedTaskId = new URLSearchParams(location.search).get('editTask');
if (linkedTaskId && /^\d+$/.test(linkedTaskId)) {
    const linkedTaskDialog = document.getElementById('edit-' + linkedTaskId);
    if (linkedTaskDialog instanceof HTMLDialogElement) linkedTaskDialog.showModal();
}
document.querySelectorAll('[data-placeholder]').forEach(control => {
    control.addEventListener('click', event => {
        event.preventDefault();
        const toast = document.querySelector('.toast');
        if (!toast) return;
        const featureName = control.dataset.placeholder || '此功能';
        toast.textContent = `${featureName}：当前为界面预览，将在后续 Sprint 中实现。`;
        toast.classList.add('show');
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(() => toast.classList.remove('show'), 2600);
    });
});

document.querySelectorAll('.permission-table input[type="checkbox"]').forEach(checkbox => {
    checkbox.addEventListener('change', () => {
        const toast = document.querySelector('.toast');
        if (!toast) return;
        toast.textContent = '权限矩阵已在界面中调整；当前原型不会保存此更改。';
        toast.classList.add('show');
        window.clearTimeout(toastTimer);
        toastTimer = window.setTimeout(() => toast.classList.remove('show'), 2600);
    });
});
