document.addEventListener('DOMContentLoaded', () => {
    const button = document.querySelector('.sidebar-toggle');
    const backButton = document.querySelector('.sidebar-back');
    if (!button || !backButton) return;
    const homeLink = document.querySelector('.side-nav a[href$="/manager"], .side-nav a[href$="/my-tasks"], .side-nav a[href$="/admin/roles"]');
    const fallback = homeLink ? new URL(homeLink.href).pathname : '/';
    const key = 'aimanage.page-history.' + backButton.dataset.role;
    const current = location.pathname + location.search;
    let pages = [];
    let index = -1;
    try {
        const saved = JSON.parse(sessionStorage.getItem(key));
        if (Array.isArray(saved.pages) && Number.isInteger(saved.index) && saved.index >= 0 && saved.index < saved.pages.length) {
            pages = saved.pages.filter(page => typeof page === 'string' && page.startsWith('/') && !page.startsWith('//'));
            index = Math.min(saved.index, pages.length - 1);
        }
    } catch (_) { /* Browser storage may be disabled. */ }
    if (pages[index] !== current) {
        const navigation = performance.getEntriesByType('navigation')[0];
        const existing = navigation && navigation.type === 'back_forward' ? pages.lastIndexOf(current) : -1;
        if (existing >= 0) index = existing;
        else {
            pages = pages.slice(0, index + 1);
            pages.push(current);
            index = pages.length - 1;
        }
    }
    const saveHistory = () => {
        try { sessionStorage.setItem(key, JSON.stringify({ pages, index })); } catch (_) { /* Current-page navigation still works. */ }
    };
    saveHistory();
    const hasPrevious = index > 0;
    const destination = hasPrevious ? pages[index - 1] : fallback;
    backButton.disabled = !hasPrevious && current === fallback;
    backButton.title = backButton.disabled ? '已是起始页面' : hasPrevious ? '返回上一页' : '返回工作台';
    backButton.setAttribute('aria-label', backButton.title);
    backButton.querySelector('span').textContent = hasPrevious ? '返回上一页' : '返回工作台';
    backButton.addEventListener('click', () => {
        if (backButton.disabled) return;
        if (hasPrevious) index -= 1;
        else { pages = [fallback]; index = 0; }
        saveHistory();
        location.assign(destination);
    });
    document.querySelector('.sidebar-footer form[action$="/logout"]')?.addEventListener('submit', () => {
        try { sessionStorage.removeItem(key); } catch (_) { /* Browser storage may be disabled. */ }
    });
    let collapsed = false;
    try { collapsed = localStorage.getItem('aimanage.sidebar.collapsed') === 'true'; } catch (_) { /* Storage may be disabled. */ }
    const apply = () => {
        document.body.classList.toggle('sidebar-collapsed', collapsed);
        button.setAttribute('aria-expanded', String(!collapsed));
        button.setAttribute('aria-label', collapsed ? '展开侧栏' : '收起侧栏');
        button.title = collapsed ? '展开侧栏' : '收起侧栏';
    };
    apply();
    button.addEventListener('click', () => {
        collapsed = !collapsed;
        try { localStorage.setItem('aimanage.sidebar.collapsed', String(collapsed)); } catch (_) { /* Keep current-page state. */ }
        apply();
    });
});
