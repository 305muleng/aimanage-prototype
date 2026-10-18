(() => {
  const root = document.getElementById('backlog-workspace');
  if (!root) return;
  const rows = [...root.querySelectorAll('.bp-row')];
  const panels = [...root.querySelectorAll('.bp-detail')];
  const search = document.getElementById('bp-search');
  const priority = document.getElementById('bp-priority');
  const newPanel = document.getElementById('new-item');
  const detailPanel = root.querySelector('.bp-detail-panel');
  let view = 'REQUIREMENT';
  const filters = {REQUIREMENT: {q: '', priority: ''}, STORY: {q: '', priority: ''}};
  if (!search || !detailPanel) return;
  function closeDetail() {
    panels.forEach(panel => panel.hidden = true);
    rows.forEach(row => {row.classList.remove('selected'); row.removeAttribute('aria-current');});
    document.getElementById('bp-detail-empty').hidden = false;
    detailPanel.classList.remove('has-selection');
  }
  function applyFilters() {
    if (view === 'MAP') return;
    const q = search.value.trim().toLocaleLowerCase();
    let count = 0;
    rows.forEach(row => {
      const matches = row.dataset.type === view && (!priority.value || row.dataset.priority === priority.value)
        && row.dataset.search.toLocaleLowerCase().includes(q);
      row.hidden = !matches;
      if (matches) count++;
    });
    filters[view] = {q: search.value, priority: priority.value};
    document.getElementById('bp-result-count').textContent = count + (view === 'STORY' ? ' 条用户故事' : ' 条需求');
    document.getElementById('bp-list-empty').hidden = count > 0;
  }
  function setView(next) {
    view = next;
    root.dataset.view = view;
    root.querySelectorAll('[data-view]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.view === view)));
    document.getElementById('backlog-list').hidden = view === 'MAP';
    document.getElementById('story-map').hidden = view !== 'MAP';
    if (view !== 'MAP') { search.value = filters[view].q; priority.value = filters[view].priority; applyFilters(); }
  }
  function reveal(panel) {
    closeDetail();
    panel.hidden = false;
    document.getElementById('bp-detail-empty').hidden = true;
    detailPanel.classList.add('has-selection');
    detailPanel.scrollTop = 0;
  }
  function positionDetail() {
    if (window.matchMedia('(max-width: 800px)').matches) detailPanel.scrollIntoView({block: 'start'});
    else window.scrollTo(0, 0);
  }
  function openItem(id, edit = false) {
    const panel = document.getElementById('item-' + id);
    if (!panel) return;
    if (view !== 'MAP') setView(panel.dataset.type);
    reveal(panel);
    const row = rows.find(candidate => candidate.dataset.itemId === id);
    if (row?.hidden && view !== 'MAP') { search.value = ''; priority.value = ''; applyFilters(); }
    if (row) { row.classList.add('selected'); row.setAttribute('aria-current', 'true'); }
    if (edit) panel.querySelector('.bp-editor input[name="title"]')?.focus({preventScroll: true});
    requestAnimationFrame(positionDetail);
  }
  function syncNewForm() {
    if (!newPanel) return;
    const form = newPanel.querySelector('form');
    const story = form.elements.type.value === 'STORY';
    for (const name of ['acceptance', 'requirementId']) {
      const field = form.elements[name];
      field.closest('label').hidden = !story;
      field.disabled = !story;
    }
    form.elements.acceptance.required = story;
    newPanel.querySelector('h2').textContent = story ? '新增用户故事' : '新增需求';
  }
  function openNew(requirementId) {
    if (!newPanel) return;
    if (requirementId) { setView('STORY'); newPanel.querySelector('[name="type"]').value = 'STORY'; newPanel.querySelector('[name="requirementId"]').value = requirementId; }
    else newPanel.querySelector('[name="type"]').value = view === 'STORY' || view === 'MAP' ? 'STORY' : 'REQUIREMENT';
    syncNewForm(); reveal(newPanel);
    newPanel.querySelector('[name="title"]').focus({preventScroll: true});
    requestAnimationFrame(positionDetail);
  }
  function navigateHash() {
    const match = /^#(edit-)?item-(\d+)$/.exec(location.hash);
    if (match) { openItem(match[2], Boolean(match[1])); return; }
    if (location.hash === '#new-item') { openNew(); return; }
    closeDetail();
    setView(location.hash === '#story-map' ? 'MAP' : location.hash === '#stories' ? 'STORY' : 'REQUIREMENT');
  }
  root.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => {
    const next = button.dataset.view;
    history.pushState(null, '', next === 'MAP' ? '#story-map' : next === 'STORY' ? '#stories' : '#requirements');
    closeDetail(); setView(next);
  }));
  root.addEventListener('click', event => {
    const link = event.target.closest('a[href^="#"]');
    if (link) { event.preventDefault(); if (link.hash !== location.hash) history.pushState(null, '', link.hash); navigateHash(); }
    const newStory = event.target.closest('[data-new-story]');
    if (newStory) { openNew(newStory.dataset.newStory); history.replaceState(null, '', '#new-item'); }
    if (event.target.closest('.bp-close')) { closeDetail(); history.replaceState(null, '', view === 'MAP' ? '#story-map' : view === 'STORY' ? '#stories' : '#requirements'); }
  });
  search.addEventListener('input', applyFilters);
  priority.addEventListener('change', applyFilters);
  document.getElementById('bp-reset').addEventListener('click', () => {search.value = ''; priority.value = ''; applyFilters();});
  newPanel?.querySelector('[name="type"]').addEventListener('change', syncNewForm);
  window.addEventListener('hashchange', navigateHash);
  window.addEventListener('load', () => {if (/^#(?:(?:edit-)?item-\d+|new-item)$/.test(location.hash)) requestAnimationFrame(positionDetail);});
  let unsaved = false;
  root.querySelectorAll('form[method="post"]').forEach(form => {
    form.addEventListener('input', () => {unsaved = true;});
    form.addEventListener('change', () => {unsaved = true;});
    form.addEventListener('submit', () => {unsaved = false;});
  });
  window.addEventListener('beforeunload', event => {if (unsaved) {event.preventDefault(); event.returnValue = '';}});
  closeDetail(); setView('REQUIREMENT'); navigateHash();
})();
