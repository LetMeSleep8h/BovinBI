import { defineStore } from 'pinia'
import { login as apiLogin, me } from '@/api'
import type { UserInfo } from '@/api/types'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('bovin_token') || '',
    user: null as UserInfo | null
  }),
  actions: {
    async login(username: string, password: string) {
      const resp = await apiLogin(username, password)
      this.token = resp.token
      localStorage.setItem('bovin_token', resp.token)
      this.user = { id: resp.id, username: resp.username, nickname: resp.nickname, role: resp.role }
    },
    async fetchMe() {
      if (!this.user) this.user = await me()
      return this.user
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('bovin_token')
    }
  }
})
