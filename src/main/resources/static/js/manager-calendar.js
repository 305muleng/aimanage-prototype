const rescheduleForm = document.getElementById('reschedule-form');
if (rescheduleForm) {
    const taskSelect = document.getElementById('reschedule-task');
    const versionInput = document.getElementById('reschedule-version');
    const dateInput = document.getElementById('reschedule-date');
    const syncVersion = () => {
        versionInput.value = taskSelect.selectedOptions[0]?.dataset.version || '';
    };
    taskSelect.addEventListener('change', syncVersion);
    rescheduleForm.addEventListener('submit', syncVersion);
    document.querySelectorAll('.manager-calendar-task[draggable="true"]').forEach(card => {
        card.addEventListener('dragstart', event => {
            event.dataTransfer.setData('text/plain', card.dataset.taskId);
            event.dataTransfer.effectAllowed = 'move';
        });
    });
    document.querySelectorAll('[data-drop-date]').forEach(day => {
        day.addEventListener('dragover', event => { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; });
        day.addEventListener('drop', event => {
            event.preventDefault();
            const taskId = event.dataTransfer.getData('text/plain');
            if (![...taskSelect.options].some(option => option.value === taskId)) return;
            taskSelect.value = taskId;
            dateInput.value = day.dataset.dropDate;
            syncVersion();
            rescheduleForm.requestSubmit();
        });
    });
}
