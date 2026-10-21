document.querySelectorAll('.share-progress-dialog').forEach(dialog => {
    const canvas = dialog.querySelector('canvas');
    const context = canvas.getContext('2d');
    const tasks = [...dialog.querySelectorAll('[data-task-title]')].map(node => ({
        title: node.dataset.taskTitle,
        status: node.dataset.taskStatus,
        due: node.dataset.taskDue,
        blocked: node.dataset.taskBlocked === 'true'
    }));
    const selected = field => dialog.querySelector(`[data-share-field="${field}"]`).checked;
    const toast = message => {
        const node = document.querySelector('.toast');
        if (!node) return;
        node.textContent = message;
        node.classList.add('show');
        setTimeout(() => node.classList.remove('show'), 3500);
    };
    const line = (text, x, y, width, font = '28px sans-serif') => {
        context.font = font;
        let value = text || '';
        while (value.length && context.measureText(value).width > width) value = value.slice(0, -1);
        context.fillText(value.length < (text || '').length ? `${value.slice(0, -1)}…` : value, x, y);
    };
    const render = () => {
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        const done = tasks.filter(task => task.status === 'DONE').length;
        const doing = tasks.filter(task => task.status === 'IN_PROGRESS').length;
        const risks = tasks.filter(task => task.status !== 'DONE' && (task.blocked || (task.due && task.due < today)));
        const riskRows = selected('risks') ? Math.min(risks.length, 5) : 0;
        canvas.height = 390 + (selected('description') ? 66 : 0) + (selected('status') ? 55 : 0)
            + (selected('progress') ? 122 : 0) + (selected('breakdown') ? 70 : 0)
            + (selected('risks') ? 70 + riskRows * 42 : 0);
        const w = canvas.width, h = canvas.height;
        context.fillStyle = '#f4f6f8'; context.fillRect(0, 0, w, h);
        context.fillStyle = '#ffffff'; context.fillRect(32, 32, w - 64, h - 64);
        context.fillStyle = '#2563eb'; context.fillRect(32, 32, 12, h - 64);
        context.fillStyle = '#1e293b';
        line(dialog.dataset.projectName, 78, 106, w - 160, 'bold 46px sans-serif');
        context.fillStyle = '#64748b';
        line(`项目进展快照  ·  ${new Date().toLocaleDateString('zh-CN')}`, 80, 154, w - 170, '22px sans-serif');
        let y = 220;
        if (selected('description')) {
            context.fillStyle = '#475569';
            line(dialog.dataset.projectDescription, 80, y, w - 170, '24px sans-serif');
            y += 66;
        }
        if (selected('status')) {
            context.fillStyle = '#334155';
            line(`项目状态：${dialog.dataset.projectStatus}`, 80, y, w - 170, 'bold 27px sans-serif');
            y += 55;
        }
        if (selected('progress')) {
            const percent = tasks.length ? Math.round(done * 100 / tasks.length) : 0;
            context.fillStyle = '#0f172a';
            line(`已完成 ${done} / ${tasks.length} 项任务     ${percent}%`, 80, y, w - 170, 'bold 32px sans-serif');
            y += 34;
            context.fillStyle = '#e2e8f0'; context.fillRect(80, y, w - 160, 20);
            context.fillStyle = '#2563eb'; context.fillRect(80, y, (w - 160) * percent / 100, 20);
            y += 88;
        }
        if (selected('breakdown')) {
            context.fillStyle = '#475569';
            line(`待处理 ${tasks.length - done - doing}   ·   进行中 ${doing}   ·   已完成 ${done}`, 80, y, w - 170, '26px sans-serif');
            y += 70;
        }
        if (selected('risks')) {
            context.fillStyle = '#b45309';
            line(`逾期或阻塞：${risks.length} 项`, 80, y, w - 170, 'bold 27px sans-serif');
            y += 44;
            if (!risks.length) { line('当前没有逾期或阻塞任务', 80, y, w - 170, '22px sans-serif'); y += 42; }
            risks.slice(0, 5).forEach(task => {
                line(`• ${task.title}${task.blocked ? '（阻塞）' : '（逾期）'}`, 80, y, w - 170, '22px sans-serif');
                y += 42;
            });
            if (risks.length > 5) line(`另有 ${risks.length - 5} 项未列出`, 80, y, w - 170, '20px sans-serif');
        }
        context.fillStyle = '#94a3b8';
        line('由爱管理生成 · 数据以导出时为准', 80, h - 78, w - 170, '20px sans-serif');
    };
    dialog.querySelectorAll('[data-share-field]').forEach(box => box.addEventListener('change', render));
    render();
    const imageBlob = () => new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
    dialog.querySelector('[data-share-download]').addEventListener('click', async () => {
        render();
        const blob = await imageBlob();
        if (!blob) return toast('图片生成失败，请重试。');
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `${dialog.dataset.projectName.replace(/[\\/:*?"<>|]/g, '_')}-项目进展.png`;
        anchor.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
        toast('项目进展图片已下载。');
    });
    dialog.querySelector('[data-share-copy]').addEventListener('click', async () => {
        render();
        const blob = await imageBlob();
        if (!blob) return toast('图片生成失败，请重试。');
        try {
            await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
            toast('项目进展图片已复制。');
        } catch (_) {
            toast('当前浏览器无法复制图片，请使用“下载 PNG”。');
        }
    });
});
