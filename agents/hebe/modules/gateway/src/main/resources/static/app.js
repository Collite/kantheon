class HebeChat {
    constructor() {
        this.sessionId = this.getOrCreateSessionId();
        this.lastEventId = 0;
        this.eventSource = null;
        this.pendingApproval = null;
        this.currentAssistantBubble = null;

        this.messagesContainer = document.getElementById('messages');
        this.messageInput = document.getElementById('messageInput');
        this.messageForm = document.getElementById('messageForm');
        this.sendButton = document.getElementById('sendButton');
        this.typingIndicator = document.getElementById('typingIndicator');
        this.approvalModal = document.getElementById('approvalModal');
        this.sessionIdDisplay = document.getElementById('sessionId');

        this.sessionIdDisplay.textContent = `Session: ${this.sessionId}`;

        this.init();
    }

    getOrCreateSessionId() {
        let sessionId = localStorage.getItem('hebe-session-id');
        if (!sessionId) {
            sessionId = crypto.randomUUID();
            localStorage.setItem('hebe-session-id', sessionId);
        }
        return sessionId;
    }

    init() {
        this.messageForm.addEventListener('submit', (e) => this.handleSubmit(e));
        this.messageInput.addEventListener('input', () => this.autoResize());
        document.getElementById('approveBtn').addEventListener('click', () => this.handleApproval(true));
        document.getElementById('denyBtn').addEventListener('click', () => this.handleApproval(false));

        this.connectSSE();
    }

    connectSSE() {
        if (this.eventSource) {
            this.eventSource.close();
        }

        const url = `/api/sessions/${this.sessionId}/events?lastEventId=${this.lastEventId}`;
        this.eventSource = new EventSource(url);

        this.eventSource.onopen = () => {
            console.log('SSE connected');
        };

        this.eventSource.onerror = (error) => {
            console.error('SSE error:', error);
            setTimeout(() => this.connectSSE(), 3000);
        };

        this.eventSource.addEventListener('text_delta', (e) => {
            this.handleTextDelta(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('done', (e) => {
            this.handleDone(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('approval_requested', (e) => {
            this.handleApprovalRequested(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('token_usage', (e) => {
            this.handleTokenUsage(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('error', (e) => {
            this.handleError(JSON.parse(e.data));
        });

        this.eventSource.onmessage = (e) => {
            const data = JSON.parse(e.data);
            this.lastEventId = data.id || this.lastEventId;
        };
    }

    async handleSubmit(e) {
        e.preventDefault();
        const content = this.messageInput.value.trim();
        if (!content) return;

        this.messageInput.value = '';
        this.autoResize();
        this.addMessage('user', content);
        this.currentAssistantBubble = null;
        this.setTyping(true);

        try {
            const response = await fetch('/api/messages', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content, sessionId: this.sessionId })
            });

            if (!response.ok) {
                const error = await response.json();
                this.addError(error.error || 'Failed to send message');
                this.setTyping(false);
            }
        } catch (error) {
            this.addError('Network error. Please try again.');
            console.error('Send error:', error);
            this.setTyping(false);
        }
    }

    getOrCreateAssistantBubble() {
        if (!this.currentAssistantBubble) {
            this.currentAssistantBubble = this.addMessage('assistant', '');
        }
        return this.currentAssistantBubble;
    }

    handleTextDelta(data) {
        this.setTyping(false);
        if (data.text) {
            const bubble = this.getOrCreateAssistantBubble();
            const contentDiv = bubble.querySelector('.content');
            if (contentDiv) {
                contentDiv.textContent += data.text;
                this.scrollToBottom();
            }
        }
    }

    handleDone(data) {
        this.setTyping(false);
        if (data.text) {
            const bubble = this.getOrCreateAssistantBubble();
            const contentDiv = bubble.querySelector('.content');
            if (contentDiv && !contentDiv.textContent) {
                contentDiv.textContent = data.text;
                this.scrollToBottom();
            }
        }
        this.currentAssistantBubble = null;
    }

    handleApprovalRequested(data) {
        this.pendingApproval = data;
        document.getElementById('approvalMessage').textContent = data.text || 'A tool execution is requested.';
        document.getElementById('approvalTool').textContent = data.approvalTool || data.tool || 'Unknown';
        this.approvalModal.classList.remove('hidden');
    }

    handleTokenUsage(data) {
        console.log('Token usage:', data);
    }

    handleError(data) {
        this.setTyping(false);
        this.currentAssistantBubble = null;
        this.addError(data.message || 'An error occurred');
    }

    async handleApproval(approved) {
        this.approvalModal.classList.add('hidden');
        if (!this.pendingApproval) return;

        const approvalId = this.pendingApproval.approvalId;

        try {
            await fetch(`/api/approval/${approvalId}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approved })
            });
        } catch (error) {
            console.error('Approval error:', error);
            this.addError('Failed to send approval response');
        }

        this.pendingApproval = null;
    }

    addMessage(type, content) {
        const message = document.createElement('div');
        message.className = `message ${type}`;

        const senderDiv = document.createElement('div');
        senderDiv.className = 'sender';
        senderDiv.textContent = type === 'user' ? 'You' : 'Assistant';
        message.appendChild(senderDiv);

        const contentDiv = document.createElement('div');
        contentDiv.className = 'content';
        contentDiv.textContent = content;
        message.appendChild(contentDiv);

        this.messagesContainer.appendChild(message);
        this.scrollToBottom();
        return message;
    }

    addError(message) {
        const errorDiv = document.createElement('div');
        errorDiv.className = 'error-message';
        errorDiv.textContent = message;
        this.messagesContainer.appendChild(errorDiv);
        this.scrollToBottom();
    }

    setTyping(show) {
        if (show) {
            this.typingIndicator.classList.remove('hidden');
        } else {
            this.typingIndicator.classList.add('hidden');
        }
    }

    autoResize() {
        this.messageInput.style.height = 'auto';
        this.messageInput.style.height = Math.min(this.messageInput.scrollHeight, 120) + 'px';
    }

    scrollToBottom() {
        this.messagesContainer.scrollTop = this.messagesContainer.scrollHeight;
    }

}

document.addEventListener('DOMContentLoaded', () => {
    window.hebeChat = new HebeChat();
});
