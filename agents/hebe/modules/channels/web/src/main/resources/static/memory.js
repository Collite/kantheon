class HebeMemory {
    constructor() {
        this.docs = [];
        this.currentPath = null;
        this.init();
    }

    init() {
        document.getElementById('refreshBtn').addEventListener('click', () => this.loadTree());
        document.getElementById('backBtn').addEventListener('click', () => this.goBack());
        document.getElementById('searchInput').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.search();
        });
        this.loadTree();
    }

    async loadTree(prefix = '') {
        try {
            const response = await fetch(`/api/memory/tree?prefix=${encodeURIComponent(prefix)}`);
            if (!response.ok) throw new Error('Failed to load tree');
            const data = await response.json();
            this.docs = JSON.parse(data.docs);
            this.renderTree(prefix);
        } catch (error) {
            this.showError('Failed to load memory tree: ' + error.message);
        }
    }

    renderTree(prefix) {
        const container = document.getElementById('docList');
        container.innerHTML = '';

        if (this.docs.length === 0) {
            container.innerHTML = '<div class="empty-state">No documents found</div>';
            return;
        }

        this.docs.forEach(doc => {
            const div = document.createElement('div');
            div.className = 'doc-item';
            div.innerHTML = `<span class="doc-name">${this.escapeHtml(doc.path || doc)}</span>`;
            div.addEventListener('click', () => this.loadDoc(doc.path || doc));
            container.appendChild(div);
        });
    }

    async loadDoc(path) {
        this.currentPath = path;
        try {
            const response = await fetch(`/api/memory/doc?path=${encodeURIComponent(path)}`);
            if (!response.ok) throw new Error('Failed to load document');
            const data = await response.json();
            this.renderDoc(path, data.content);
        } catch (error) {
            this.showError('Failed to load document: ' + error.message);
        }
    }

    renderDoc(path, content) {
        const container = document.getElementById('docList');
        const docContent = document.getElementById('docContent');
        const breadcrumbs = document.getElementById('breadcrumbs');

        breadcrumbs.innerHTML = `<span class="crumb">Memory</span> &gt; <span class="crumb">${this.escapeHtml(path)}</span>`;
        docContent.innerHTML = `<pre class="doc-text">${this.escapeHtml(content)}</pre>`;
        docContent.classList.remove('hidden');
        container.classList.add('hidden');
        document.getElementById('backBtn').classList.remove('hidden');
    }

    goBack() {
        const docContent = document.getElementById('docContent');
        const container = document.getElementById('docList');
        const breadcrumbs = document.getElementById('breadcrumbs');

        docContent.classList.add('hidden');
        container.classList.remove('hidden');
        breadcrumbs.innerHTML = '<span class="crumb">Memory</span>';
        document.getElementById('backBtn').classList.add('hidden');
        this.currentPath = null;
    }

    async search() {
        const query = document.getElementById('searchInput').value.trim();
        if (!query) return;

        try {
            const response = await fetch(`/api/memory/search?q=${encodeURIComponent(query)}`);
            if (!response.ok) throw new Error('Search failed');
            const data = await response.json();
            this.renderSearchResults(JSON.parse(data.results));
        } catch (error) {
            this.showError('Search failed: ' + error.message);
        }
    }

    renderSearchResults(results) {
        const container = document.getElementById('docList');
        container.innerHTML = '';

        if (results.length === 0) {
            container.innerHTML = '<div class="empty-state">No results found</div>';
            return;
        }

        results.forEach(result => {
            const div = document.createElement('div');
            div.className = 'doc-item search-result';
            div.innerHTML = `<span class="doc-name">${this.escapeHtml(result.path || 'Unknown')}</span>
                           <div class="doc-snippet">${this.escapeHtml(result.content?.substring(0, 100) || '')}...</div>`;
            div.addEventListener('click', () => this.loadDoc(result.path));
            container.appendChild(div);
        });
    }

    showError(message) {
        const container = document.getElementById('docList');
        container.innerHTML = `<div class="error-message">${this.escapeHtml(message)}</div>`;
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.hebeMemory = new HebeMemory();
});