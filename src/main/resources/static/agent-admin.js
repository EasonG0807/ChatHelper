document.addEventListener('DOMContentLoaded', () => {
    const guide = document.getElementById('credentialGuideDialog');
    const openGuide = document.getElementById('openCredentialGuide');
    const showGuide = () => {
        if (!guide) return;
        if (typeof guide.showModal === 'function') {
            if (!guide.open) guide.showModal();
        } else {
            guide.setAttribute('open', 'open');
        }
    };

    openGuide?.addEventListener('click', showGuide);
    if (guide?.dataset.autoOpen === 'true') {
        showGuide();
    }

    const authType = document.getElementById('mcpAuthType');
    const tokenField = document.getElementById('mcpBearerField');
    const tokenInput = document.getElementById('mcpBearerToken');
    const connectionForm = document.getElementById('mcpConnectionForm');

    function syncCredentialField() {
        const bearer = authType?.value === 'BEARER_TOKEN';
        if (tokenField) tokenField.hidden = !bearer;
        if (tokenInput) tokenInput.required = bearer;
    }

    authType?.addEventListener('change', syncCredentialField);
    syncCredentialField();

    connectionForm?.addEventListener('submit', event => {
        if (authType?.value === 'BEARER_TOKEN' && connectionForm.dataset.vaultConfigured !== 'true') {
            event.preventDefault();
            showGuide();
        }
    });

    document.querySelectorAll('.mcp-delete-form').forEach(form => {
        form.addEventListener('submit', event => {
            if (!window.confirm('确定删除这个 MCP 连接吗？发现的工具和加密凭据会一起删除。')) {
                event.preventDefault();
            }
        });
    });

    document.querySelectorAll('form[method="post"]').forEach(form => {
        form.addEventListener('submit', event => {
            if (event.defaultPrevented) return;
            const button = form.querySelector('button[type="submit"]');
            if (button) {
                button.disabled = true;
                button.setAttribute('aria-busy', 'true');
            }
        });
    });

    const cards = Array.from(document.querySelectorAll('[data-tool-card]'));
    const groups = Array.from(document.querySelectorAll('[data-tool-group]'));
    const searchInput = document.getElementById('toolSearch');
    const clearSearchButton = document.getElementById('clearToolSearch');
    const sourceButtons = Array.from(document.querySelectorAll('[data-source-filter]'));
    const statusSelect = document.getElementById('toolStatusFilter');
    const visibleCount = document.getElementById('visibleToolCount');
    const noResults = document.getElementById('agentToolNoResults');
    const resetButton = document.getElementById('resetToolFilters');
    if (cards.length === 0 || !searchInput || !statusSelect || !visibleCount || !noResults) return;

    const state = { source: 'ALL', status: 'ALL', query: '' };
    const normalize = value => (value || '').toLocaleLowerCase('zh-CN').trim();

    function applyFilters() {
        let matches = 0;
        cards.forEach(card => {
            const visible = (state.source === 'ALL' || card.dataset.source === state.source)
                && (state.status === 'ALL' || card.dataset.status === state.status)
                && (!state.query || normalize(card.dataset.searchText).includes(state.query));
            card.hidden = !visible;
            if (visible) matches += 1;
        });
        groups.forEach(group => {
            const groupCards = group.querySelectorAll('[data-tool-card]');
            group.hidden = groupCards.length > 0
                && group.querySelectorAll('[data-tool-card]:not([hidden])').length === 0;
            const count = group.querySelector('.agent-tool-group-count strong');
            if (count) count.textContent = String(group.querySelectorAll('[data-tool-card]:not([hidden])').length);
        });
        visibleCount.textContent = String(matches);
        noResults.hidden = matches !== 0;
        if (clearSearchButton) clearSearchButton.hidden = !state.query;
    }

    sourceButtons.forEach(button => button.addEventListener('click', () => {
        state.source = button.dataset.sourceFilter;
        sourceButtons.forEach(item => {
            const active = item === button;
            item.classList.toggle('is-active', active);
            item.setAttribute('aria-pressed', String(active));
        });
        applyFilters();
    }));

    searchInput.addEventListener('input', () => {
        state.query = normalize(searchInput.value);
        applyFilters();
    });
    clearSearchButton?.addEventListener('click', () => {
        searchInput.value = '';
        state.query = '';
        searchInput.focus();
        applyFilters();
    });
    statusSelect.addEventListener('change', () => {
        state.status = statusSelect.value;
        applyFilters();
    });
    resetButton?.addEventListener('click', () => {
        state.source = 'ALL';
        state.status = 'ALL';
        state.query = '';
        searchInput.value = '';
        statusSelect.value = 'ALL';
        sourceButtons.forEach(item => {
            const active = item.dataset.sourceFilter === 'ALL';
            item.classList.toggle('is-active', active);
            item.setAttribute('aria-pressed', String(active));
        });
        applyFilters();
    });
    applyFilters();
});
