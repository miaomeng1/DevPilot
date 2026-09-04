<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { maintenanceApi, type BackupOverview } from '@/api/maintenance'
import { apiErrorMessage } from '@/api/client'

const overview = ref<BackupOverview>()
const loading = ref(false)
const errorMessage = ref('')
const copied = ref('')
const drillBackupId = ref('')
const drillEnvironment = ref<'ISOLATED' | 'STAGING'>('ISOLATED')
const drillNotes = ref('')
const drillSaving = ref(false)
const drillMessage = ref('')
let pollTimer: number | undefined
let copyTimer: number | undefined

const stateCopy = computed(() => {
  const state = overview.value?.state
  if (state === 'HEALTHY') return { tone: 'healthy', eyebrow: 'BACKUP HEALTHY', title: '备份状态正常', detail: `最近备份距今 ${overview.value?.ageHours ?? 0} 小时，且已通过 SHA-256 校验。` }
  if (state === 'STALE') return { tone: 'stale', eyebrow: 'BACKUP OVERDUE', title: '备份已经过期', detail: `最近备份距今 ${overview.value?.ageHours} 小时，超过 ${overview.value?.freshnessHours} 小时新鲜度目标。` }
  if (state === 'NO_BACKUP') return { tone: 'empty', eyebrow: 'NO BACKUP EVIDENCE', title: '尚未收到备份证据', detail: '先在 DevPilot 所在主机执行一次 backup.sh，然后配置每日任务。' }
  return { tone: 'disabled', eyebrow: 'REPORTING DISABLED', title: '备份报告尚未配置', detail: '请配置独立的 MAINTENANCE_REPORT_SECRET，并重建 Server 容器。' }
})

const cronCommand = "(sudo crontab -l 2>/dev/null; echo '0 3 * * * /opt/devpilot/bin/backup.sh') | sudo crontab -"

async function load(silent = false) {
  if (!silent) loading.value = true
  errorMessage.value = ''
  try {
    overview.value = await maintenanceApi.backups()
    const latestReport = overview.value.reports[0]
    if (!drillBackupId.value && latestReport) drillBackupId.value = latestReport.id
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法加载维护状态 Maintenance')
  } finally {
    loading.value = false
  }
}

async function recordDrill(result: 'PASSED' | 'FAILED') {
  if (!drillBackupId.value) return
  const confirmation = result === 'PASSED'
    ? '确认你已经在隔离或预发布环境实际恢复了所选归档，并完成登录、密钥、Agent 与回滚检查？'
    : '确认记录本次恢复演练失败？失败记录会保留，便于后续修复。'
  if (!window.confirm(confirmation)) return
  drillSaving.value = true
  errorMessage.value = ''
  drillMessage.value = ''
  try {
    await maintenanceApi.recordRestoreDrill({
      backupReportId: drillBackupId.value,
      environment: drillEnvironment.value,
      result,
      notes: drillNotes.value.trim() || null,
    })
    drillMessage.value = result === 'PASSED' ? '恢复演练已记录为通过。' : '失败证据已记录，请根据说明修复。'
    drillNotes.value = ''
    await load(true)
  } catch (error) {
    errorMessage.value = apiErrorMessage(error, '无法记录恢复演练 Restore drill')
  } finally {
    drillSaving.value = false
  }
}

async function copy(label: string, value: string) {
  await navigator.clipboard.writeText(value)
  copied.value = label
  window.clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => { copied.value = '' }, 1800)
}

function restoreCommand(fileName: string) {
  return `sudo /opt/devpilot/bin/restore.sh --archive /var/backups/devpilot/${fileName} --yes`
}

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(`${value}Z`))
}

function bytes(value: number | string | null | undefined) {
  const numeric = Number(value)
  if (!Number.isFinite(numeric) || numeric <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(numeric) / Math.log(1024)), units.length - 1)
  return `${(numeric / 1024 ** index).toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}

onMounted(() => {
  void load()
  pollTimer = window.setInterval(() => void load(true), 60_000)
})
onBeforeUnmount(() => {
  window.clearInterval(pollTimer)
  window.clearTimeout(copyTimer)
})
</script>

<template>
  <section class="maintenance-view">
    <header class="page-heading">
      <div><p class="eyebrow">可靠性与恢复 · RELIABILITY</p><h1>备份与维护中心</h1><span>确认备份真的执行、真的完整，并且知道如何恢复。</span></div>
      <button class="secondary-compact" type="button" :disabled="loading" @click="load()">{{ loading ? '检查中…' : '重新检查 Check' }}</button>
    </header>
    <p v-if="errorMessage" class="inline-error">{{ errorMessage }}</p>

    <section class="backup-hero" :class="stateCopy.tone">
      <div class="backup-state-icon"><i /></div>
      <div><span>{{ stateCopy.eyebrow }}</span><h2>{{ stateCopy.title }}</h2><p>{{ stateCopy.detail }}</p></div>
      <dl><div><dt>最近备份 Latest</dt><dd>{{ formatTime(overview?.latest?.createdAt) }}</dd></div><div><dt>归档大小 Size</dt><dd>{{ bytes(overview?.latest?.sizeBytes) }}</dd></div><div><dt>新鲜度目标 SLO</dt><dd>{{ overview?.freshnessHours ?? 26 }} 小时</dd></div></dl>
    </section>

    <div class="maintenance-grid">
      <section class="maintenance-panel setup-panel">
        <header><div><strong>每日备份设置 Daily backup</strong><small>推荐每天 03:00 在控制面主机执行</small></div><span>HOST</span></header>
        <ol>
          <li><b>1</b><div><strong>安装维护脚本</strong><small>按部署文档将 backup.sh 与 restore.sh 放入 /opt/devpilot/bin。</small></div></li>
          <li><b>2</b><div><strong>先手动验证一次</strong><code>sudo /opt/devpilot/bin/backup.sh</code></div><button type="button" @click="copy('backup', 'sudo /opt/devpilot/bin/backup.sh')">{{ copied === 'backup' ? '已复制' : '复制' }}</button></li>
          <li><b>3</b><div><strong>添加每日任务</strong><code>{{ cronCommand }}</code></div><button type="button" @click="copy('cron', cronCommand)">{{ copied === 'cron' ? '已复制' : '复制' }}</button></li>
          <li><b>4</b><div><strong>配置 S3 异机副本</strong><small>在 .env 设置 BACKUP_S3_URI；远端上传并核对大小后，证据位置会显示 S3。</small></div></li>
        </ol>
      </section>

      <aside class="maintenance-panel safety-panel">
        <header><div><strong>恢复安全边界 Restore safety</strong><small>恢复必须在主机终端主动确认</small></div><span>SAFE</span></header>
        <div><span>01</span><p><strong>归档不会经过浏览器</strong>其中包含数据库和生产密钥，DevPilot 只接收校验后的元数据。</p></div>
        <div><span>02</span><p><strong>自动核对 Master Key</strong>密钥不一致时拒绝恢复，避免加密的 Provider 凭据永久不可读。</p></div>
        <div><span>03</span><p><strong>先做隔离恢复演练</strong>生产恢复会替换数据库；建议定期在隔离主机验证登录、Agent 和回滚。</p></div>
      </aside>
    </div>

    <section class="maintenance-panel restore-drill-panel">
      <header><div><strong>恢复演练 Restore drill</strong><small>人工验收记录：证明某份备份曾在非生产环境成功恢复</small></div><span :class="overview?.latestDrill?.result === 'PASSED' ? 'passed' : overview?.latestDrill?.result === 'FAILED' ? 'failed' : ''">{{ overview?.latestDrill?.result || 'NOT RUN' }}</span></header>
      <div class="restore-drill-grid">
        <article class="latest-drill-card" :class="overview?.latestDrill?.result?.toLowerCase() || 'empty'">
          <span>最近一次 Latest evidence</span>
          <strong>{{ overview?.latestDrill ? `${overview.latestDrill.result === 'PASSED' ? '恢复验证通过' : '恢复验证失败'}` : '尚未进行恢复演练' }}</strong>
          <p v-if="overview?.latestDrill">{{ overview.latestDrill.backupFileName || '备份记录已删除' }} · {{ overview.latestDrill.environment }}</p>
          <small v-if="overview?.latestDrill">{{ formatTime(overview.latestDrill.performedAt) }} · {{ overview.latestDrill.performedByName || '已删除用户' }}</small>
          <blockquote v-if="overview?.latestDrill?.notes">{{ overview.latestDrill.notes }}</blockquote>
          <p v-else-if="!overview?.latestDrill">建议每次重大升级后，以及至少每季度，在隔离主机执行一次。</p>
        </article>
        <form class="drill-attestation" @submit.prevent>
          <p><strong>记录已完成的演练</strong><small>这里不会执行恢复，只保存你的验收结果。生产数据请勿在控制台内恢复。</small></p>
          <div><label><span>验证的归档 Backup</span><select v-model="drillBackupId" :disabled="!overview?.reports.length"><option value="" disabled>选择备份证据</option><option v-for="report in overview?.reports || []" :key="report.id" :value="report.id">{{ report.fileName }} · {{ formatTime(report.createdAt) }}</option></select></label><label><span>演练环境 Environment</span><select v-model="drillEnvironment"><option value="ISOLATED">隔离环境 Isolated</option><option value="STAGING">预发布 Staging</option></select></label></div>
          <label><span>验证说明 Notes</span><textarea v-model="drillNotes" maxlength="1000" rows="3" placeholder="例如：登录、Agent 重连、Provider 密钥读取、健康检查与回滚均通过" /></label>
          <footer><small v-if="drillMessage">{{ drillMessage }}</small><span><button type="button" :disabled="drillSaving || !drillBackupId" @click="recordDrill('FAILED')">记录失败 Failed</button><button class="passed" type="button" :disabled="drillSaving || !drillBackupId" @click="recordDrill('PASSED')">{{ drillSaving ? '保存中…' : '确认通过 Passed' }}</button></span></footer>
        </form>
      </div>
    </section>

    <section class="maintenance-panel backup-history">
      <header><div><strong>备份证据 Backup evidence</strong><small>最近 30 条经过签名并通过 SHA-256 自检的报告</small></div><span>{{ overview?.reports.length || 0 }} RECORDS</span></header>
      <div v-if="overview?.reports.length" class="server-table-wrap"><table class="server-table"><thead><tr><th>归档 Archive</th><th>生成时间</th><th>大小</th><th>位置</th><th>SHA-256</th><th>恢复命令</th></tr></thead><tbody><tr v-for="report in overview.reports" :key="report.id"><td><strong>{{ report.fileName }}</strong><small>已验证 {{ formatTime(report.verifiedAt) }}</small></td><td>{{ formatTime(report.createdAt) }}</td><td>{{ bytes(report.sizeBytes) }}</td><td><span class="backup-destination">{{ report.destinationType }}</span></td><td><code class="backup-checksum" :title="report.sha256">{{ report.sha256.slice(0, 16) }}…</code></td><td><button class="table-copy" type="button" @click="copy(report.id, restoreCommand(report.fileName))">{{ copied === report.id ? '已复制' : '复制命令' }}</button></td></tr></tbody></table></div>
      <div v-else class="table-empty"><span class="server-empty-glyph">↥</span><strong>等待第一条备份证据</strong><small>成功运行 backup.sh 后，此处会自动出现经过校验的归档记录。</small></div>
    </section>
  </section>
</template>
