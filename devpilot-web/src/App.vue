<script setup lang="ts">
import { useThemeStore } from '@/stores/theme'
import { useSystemStore } from '@/stores/system'

const theme = useThemeStore()
theme.apply()
const system = useSystemStore()
void system.load().then((settings) => theme.applyDefault(settings.defaultTheme))
</script>

<template>
  <RouterView />
</template>
