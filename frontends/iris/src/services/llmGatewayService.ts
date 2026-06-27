import axios from 'axios'
import { config } from '@/config'
import { useAuthStore } from '../stores/auth'

const apiClient = axios.create({
    baseURL: config.llmGateway.baseUrl,
    headers: {
        'Content-Type': 'application/json',
    },
})

apiClient.interceptors.request.use(async (config) => {
    const authStore = useAuthStore()

    if (authStore.isAuthenticated) {
        if (authStore.token) {
            config.headers.Authorization = `Bearer ${authStore.token}`
        }
        if (authStore.userId) {
            config.headers['X-User-ID'] = authStore.userId
        }
    }

    return config
})

export interface ChatMessage {
    role: string
    content: string
}

export interface ReasoningConfig {
    effort?: 'LOW' | 'MEDIUM' | 'HIGH' | 'REASONING_EFFORT_UNSPECIFIED'
}

export interface ResponseInput {
    text?: string
}

export interface CreateResponseRequest {
    conversation?: string
    model?: string
    input?: ResponseInput
    instructions?: string
    background?: string
    temperature?: number
    maxOutputTokens?: number
    maxToolsCalls?: number
    reasoning?: ReasoningConfig
    modelTags?: string[]
}

export interface ResponseOutput {
    type: string
    text?: string
}

export interface Usage {
    totalTokens: number
    inputTokens: number
    outputTokens: number
}

export interface ResponseReasoning {
    summary?: string
}

export interface ChatResponse {
    id?: string
    object?: string
    createdAt?: number
    conversationId?: string
    model?: string
    status?: string
    output?: ResponseOutput[]
    usage?: Usage
    reasoning?: ResponseReasoning
    content?: string
}

export interface ChatCompletionRequestApi {
    model?: string
    messages?: ChatMessage[]
    conversation?: string
    input?: ResponseInput
    instructions?: string
    background?: string
    temperature?: number
    maxOutputTokens?: number
    maxToolsCalls?: number
    reasoning?: ReasoningConfig
    modelTags?: string[]
}

export const llmGatewayService = {
    // baseURL points at the LLM gateway's chat base (…/api/v1/chat); each call
    // adds its own endpoint so the view isn't locked to a single path.
    createResponse(request: ChatCompletionRequestApi): Promise<ChatResponse> {
        return apiClient.post('/responses', request).then((res) => res.data)
    },
}