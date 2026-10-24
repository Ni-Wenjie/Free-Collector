<template>
  <!-- 采集字段列表：展示可视化选取生成的字段，支持编辑/删除 -->
  <div class="field-list">
    <div v-if="fields.length === 0" class="empty-tip">
      <el-empty description="点击左侧网页元素添加采集字段" :image-size="80" />
    </div>
    <el-table v-else :data="fields" size="small" border>
      <el-table-column label="字段名" width="110">
        <template #default="{ row }">
          <el-input v-model="row.fieldName" size="small" placeholder="字段名" />
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-select v-model="row.fieldType" size="small">
            <el-option label="文本" value="TEXT" />
            <el-option label="链接" value="LINK" />
            <el-option label="图片" value="IMAGE" />
            <el-option label="列表" value="LIST" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="CSS 选择器" min-width="160">
        <template #default="{ row }">
          <el-input
            v-model="row.locatorExpression"
            size="small"
            placeholder="点击页面元素自动生成"
          />
        </template>
      </el-table-column>
      <el-table-column label="提取属性" width="90">
        <template #default="{ row }">
          <el-select v-model="row.extractAttr" size="small">
            <el-option label="文本" value="text" />
            <el-option label="href" value="href" />
            <el-option label="src" value="src" />
            <el-option label="outerHTML" value="outerHTML" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="60" align="center">
        <template #default="{ $index }">
          <el-button type="danger" link size="small" @click="onDelete($index)">删</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
const props = defineProps({
  fields: { type: Array, default: () => [] }
})
const emit = defineEmits(['delete'])

function onDelete(index) {
  emit('delete', index)
}
</script>

<style scoped>
.field-list { width: 100%; }
.empty-tip { padding: 20px 0; }
</style>
