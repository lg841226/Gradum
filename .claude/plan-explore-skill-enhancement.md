# ExploreProjectSkill 智能过滤搜索增强计划

## 目标
为 ExploreProjectSkill 添加智能过滤和搜索功能，允许用户更精确地定位项目文件。

## 当前功能
- 扫描项目目录树
- 分类返回：config_files, code_files(带行数), other_files
- 跳过构建/依赖目录
- depth 参数控制递归深度 (5-12)
- SIMPLE/FULL 输出模式

## 新增参数

### 1. filter_type - 文件类型过滤
```kotlin
"filter_type" to mapOf(
  "type" to "string",
  "description" to "Filter by file type: 'code', 'config', 'other', or 'all' (default='all')",
  "enum" to listOf("code", "config", "other", "all")
)
```

### 2. filter_extension - 扩展名过滤
```kotlin
"filter_extension" to mapOf(
  "type" to "string",
  "description" to "Filter by file extensions, comma-separated (e.g., 'kt,java,py')"
)
```

### 3. filter_directory - 目录过滤
```kotlin
"filter_directory" to mapOf(
  "type" to "string",
  "description" to "Only scan these directories, comma-separated (e.g., 'src,test')"
)
```

### 4. exclude_pattern - 排除模式
```kotlin
"exclude_pattern" to mapOf(
  "type" to "string",
  "description" to "Exclude files matching these glob patterns, comma-separated (e.g., '*.test.kt,**/test/**')"
)
```

### 5. min_lines / max_lines - 行数范围
```kotlin
"min_lines" to mapOf(
  "type" to "integer",
  "description" to "Minimum line count (default=0)"
)
"max_lines" to mapOf(
  "type" to "integer",
  "description" to "Maximum line count (default=unlimited)"
)
```

### 6. sort_by - 排序方式
```kotlin
"sort_by" to mapOf(
  "type" to "string",
  "description" to "Sort results by: 'name', 'lines', 'size' (default='name')",
  "enum" to listOf("name", "lines", "size")
)
```

### 7. limit - 数量限制
```kotlin
"limit" to mapOf(
  "type" to "integer",
  "description" to "Maximum number of files to return (default=500, max=2000)"
)
```

## 实现步骤

### Step 1: 更新 getSchema
在 `getSchema` 函数中添加新参数定义。

### Step 2: 更新 execute 方法
1. 解析新的过滤参数
2. 在 `scanDirectory` 过程中应用过滤
3. 在返回结果前应用排序和限制

### Step 3: 添加辅助函数
- `matchesExtension(fileName, extensions)`: 检查文件扩展名
- `matchesPattern(fileName, patterns)`: 检查 glob 模式匹配
- `matchesDirectory(path, allowedDirs)`: 检查目录是否在允许列表中

## 输出格式变化

### FULL 模式 (增加字段)
```kotlin
linkedMapOf(
  "project_root" to resolvedPath.toString(),
  "total_size" to formatSize(scanResult.totalSize),
  "config_files" to filteredConfigFiles,
  "code_files" to filteredCodeFiles,
  "other_files" to filteredOtherFiles,
  "filter_applied" to mapOf(
    "filter_type" to ...,
    "filter_extension" to ...,
    ...
  ),
  "result_count" to totalFilteredCount,
  "sort_by" to sortBy,
)
```

### SIMPLE 模式 (保持兼容)
保持现有输出，增加可选的过滤信息。

## 改动文件
- `src/main/kotlin/gradum/skill/ExploreProjectSkill.kt`

## 测试场景
1. 只返回 Kotlin 文件: `filter_extension="kt,kts"`
2. 排除测试目录: `exclude_pattern="**/test/**"`
3. 查找大型文件: `min_lines=500, sort_by="lines", limit=10`
4. 只扫描 src 目录: `filter_directory="src"`