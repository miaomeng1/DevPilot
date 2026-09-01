<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = withDefaults(defineProps<{
  option: EChartsCoreOption
  height?: string
}>(), { height: '280px' })

const container = ref<HTMLDivElement>()
let chart: ECharts | undefined
let observer: ResizeObserver | undefined

function render() {
  if (!container.value) return
  if (!chart) chart = init(container.value, undefined, { renderer: 'canvas' })
  chart.setOption(props.option, { notMerge: true })
}

watch(() => props.option, render, { deep: true })
onMounted(() => {
  render()
  observer = new ResizeObserver(() => chart?.resize())
  if (container.value) observer.observe(container.value)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template><div ref="container" class="base-chart" :style="{ height }" /></template>
