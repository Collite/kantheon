<script setup lang="ts">
import { ref } from 'vue'
import Dialog from 'primevue/dialog'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Message from 'primevue/message'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

const visible = ref(true)
const userId = ref('analytik')
const userName = ref('Analytik DFP')
const error = ref('')

const submit = () => {
    if (!userId.value.trim()) {
        error.value = 'User ID is required'
        return
    }
    if (!userName.value.trim()) {
        error.value = 'User Name is required'
        return
    }
    error.value = ''
    authStore.setFallbackUser(userId.value.trim(), userName.value.trim())
    visible.value = false
}
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    header="Authentication Required"
    :closable="false"
    :draggable="false"
    :dismissable-mask="false"
    :style="{ width: '28rem' }"
  >
    <p class="text-sm mb-6" style="color: var(--p-surface-600);">
      Keycloak is not available. Please enter your user credentials to continue.
    </p>

    <form @submit.prevent="submit" class="flex flex-col gap-4">
      <div class="flex flex-col gap-1">
        <label for="userId" class="text-sm font-medium" style="color: var(--p-surface-700);">
          User ID
        </label>
        <InputText
          id="userId"
          v-model="userId"
          autocomplete="username"
          placeholder="Enter your user ID"
        />
      </div>

      <div class="flex flex-col gap-1">
        <label for="userName" class="text-sm font-medium" style="color: var(--p-surface-700);">
          User Name
        </label>
        <InputText
          id="userName"
          v-model="userName"
          autocomplete="name"
          placeholder="Enter your name"
        />
      </div>

      <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

      <Button type="submit" label="Continue" class="mt-2" />
    </form>
  </Dialog>
</template>
