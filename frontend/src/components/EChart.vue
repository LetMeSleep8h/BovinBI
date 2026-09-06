<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ChartSpec } from '@/api/types'

const props = defineProps<{ spec: ChartSpec }>()
const el = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null

function fmt(n: number): string {
  return n == null ? '-' : Math.abs(n) >= 10000 ? n.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) : n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function buildOption(s: ChartSpec): echarts.EChartsOption {
  const tip = { trigger: 'axis' as const, valueFormatter: (v: any) => fmt(Number(v)) }
  if (s.type === 'pie') {
    const data = (s.xValues || []).map((x, i) => ({ name: x, value: s.series?.[0]?.values[i] ?? 0 }))
    return {
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { orient: 'vertical', right: 6, top: 'middle', type: 'scroll' },
      series: [{ type: 'pie', radius: ['38%', '66%'], center: ['40%', '50%'], data,
        label: { formatter: '{d}%' } }]
    }
  }
  if (s.type === 'barH') {
    const xs = s.xValues || []
    return {
      tooltip: tip,
      grid: { left: 10, right: 24, top: 10, bottom: 10, containLabel: true },
      xAxis: { type: 'value' as const, axisLabel: { formatter: (v: number) => fmt(v) } },
      yAxis: { type: 'category' as const, data: [...xs].reverse() },
      series: (s.series || []).map((sr) => ({ name: sr.name, type: 'bar' as const,
        data: [...sr.values].reverse(), itemStyle: { borderRadius: [0, 4, 4, 0] } }))
    }
  }
  // line / bar
  return {
    tooltip: tip,
    legend: { top: 0 },
    grid: { left: 10, right: 16, top: 30, bottom: 10, containLabel: true },
    xAxis: { type: 'category' as const, data: s.xValues || [],
      axisLabel: { rotate: (s.xValues || []).length > 12 ? 35 : 0 } },
    yAxis: { type: 'value' as const, axisLabel: { formatter: (v: number) => fmt(v) },
      splitLine: { lineStyle: { color: '#f0f0f0' } } },
    series: (s.series || []).map((sr) => s.type === 'line'
      ? { name: sr.name, type: 'line' as const, data: sr.values, smooth: true,
          areaStyle: { opacity: 0.08 }, symbolSize: 6 }
      : { name: sr.name, type: 'bar' as const, data: sr.values, itemStyle: { borderRadius: [4, 4, 0, 0] } })
  }
}

function render() {
  if (!el.value) return
  if (!chart) chart = echarts.init(el.value)
  if (props.spec && props.spec.type !== 'none') {
    chart.setOption(buildOption(props.spec), true)
    chart.resize()
  }
}

function onResize() {
  chart?.resize()
}

onMounted(() => {
  render()
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
watch(() => props.spec, render, { deep: true })
</script>

<template>
  <div ref="el" style="width: 100%; height: 320px" />
</template>
