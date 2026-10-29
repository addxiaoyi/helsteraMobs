# SpawnerService 加固实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过测试先行和逐行审查，验证并加固 SpawnerService 的配置解析、成组上限、失败状态和 fail-closed 门控。

**Architecture:** 保持 SpawnerService 的现有单类边界和公开 API。先在现有 JUnit 测试类中补充可独立失败的边界测试，再只修改对应解析、生成和状态更新路径；不引入新模块、不修改 Paper 验证或菜单代码。

**Tech Stack:** Java、JUnit 5、Mockito、Bukkit/Paper API、Maven Wrapper。

---

### Task 1: 配置解析边界

**Files:**
- Modify: `helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java`
- Modify: `helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java`

- [ ] **Step 1: 写失败测试**

添加测试，直接覆盖包可见解析方法或通过加载配置断言结果：

```java
@Test
void parseRangeRejectsFractionalAndMalformedValues() {
    assertArrayEquals(new int[]{2, 5}, SpawnerService.parseRange("5-2"));
    assertNull(SpawnerService.parseRange("2.5"));
    assertNull(SpawnerService.parseRange("2-"));
}

@Test
void jitterPercentAndNonFiniteValuesAreSafe() {
    SpawnerService service = service("""
            spawners:
              s:
                mob: a
                interval: 100
                interval-jitter: 30%
            """);
    assertEquals(0.30, service.get("s").intervalJitter(), 1e-9);
}
```

- [ ] **Step 2: 运行测试确认失败**

运行：

```bash
./mvnw -pl helstera-ai -Dtest=SpawnerServiceTest test -Dfile.encoding=UTF-8
```

预期：新增断言在当前实现上至少有一项失败，或编译失败并明确指出缺少可测试入口。

- [ ] **Step 3: 实现最小修复**

仅修改解析路径，使整数区间拒绝小数和空端点；抖动百分比先去除 `%` 再转比例；所有非有限值不得进入 `Spawner` 字段；保留问题记录和既有默认值。

- [ ] **Step 4: 运行测试确认通过**

运行同一命令，预期 `SpawnerServiceTest` 全部通过。

- [ ] **Step 5: 提交**

```bash
git add helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java
git commit -m "test: cover SpawnerService parsing boundaries"
```

### Task 2: 成组生成与上限

**Files:**
- Modify: `helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java`
- Modify: `helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java`

- [ ] **Step 1: 写失败测试**

增加固定随机源或可控配置，验证 `group-size: 3-5` 在 `max-alive: 2` 时最多成功 2 只，并且不会增加超过上限的计数；同时验证 `max-per-player` 和 `max-per-world` 仍生效。

```java
@Test
void groupSpawnStopsAtAliveLimit() {
    AtomicInteger calls = new AtomicInteger();
    SpawnerService service = service("""
            spawners:
              s:
                mob: a
                group-size: 3-5
                max-alive: 2
                interval: 20
            """, (mob, location, ai) -> { calls.incrementAndGet(); return 1; });
    assertTrue(service.forceSpawn("s", new Location(world, 0, 64, 0)));
    assertTrue(calls.get() <= 2);
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./mvnw -pl helstera-ai -Dtest=SpawnerServiceTest#groupSpawnStopsAtAliveLimit test -Dfile.encoding=UTF-8
```

预期：当前实现若绕过上限，断言失败；若已满足，则补充能观察每只尝试和状态计数的更严格断言。

- [ ] **Step 3: 实现最小修复**

将成组生成复用普通单只生成前置检查；每成功一只再更新计数；任一上限达到时停止剩余组成员。

- [ ] **Step 4: 运行测试确认通过**

运行该测试及 `SpawnerServiceTest` 全类测试，预期全部通过。

- [ ] **Step 5: 提交**

```bash
git add helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java
git commit -m "fix: enforce SpawnerService group limits"
```

### Task 3: 失败状态与 fail-closed 门控

**Files:**
- Modify: `helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java`
- Modify: `helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java`

- [ ] **Step 1: 写失败测试**

覆盖回调返回 0、抛出运行时异常、未知世界和未加载区块；断言 `totalSpawned`、计数和下一次生成时间没有被成功路径错误推进，且异常不会逸出调度调用。

```java
@Test
void failedSpawnDoesNotAdvanceCounters() {
    SpawnerService service = service("""
            spawners:
              s:
                mob: a
                interval: 20
            """, (mob, location, ai) -> 0);
    assertFalse(service.forceSpawn("s", new Location(world, 0, 64, 0)));
    assertEquals(0, service.get("s").totalSpawned());
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
./mvnw -pl helstera-ai -Dtest=SpawnerServiceTest#failedSpawnDoesNotAdvanceCounters test -Dfile.encoding=UTF-8
```

预期：测试在当前错误状态更新行为下失败，或暴露需要修复的可观察状态。

- [ ] **Step 3: 实现最小修复**

把计数、冷却和状态写入放到生成回调成功之后；捕获单只生成回调异常并记录日志，返回失败；世界/区块/光照不能确认时直接拒绝。

- [ ] **Step 4: 运行测试确认通过**

运行 `SpawnerServiceTest` 全类测试。

- [ ] **Step 5: 提交**

```bash
git add helstera-ai/src/main/java/dev/helstera/ai/spawner/SpawnerService.java helstera-ai/src/test/java/dev/helstera/ai/SpawnerServiceTest.java
git commit -m "fix: preserve SpawnerService failure state"
```

### Task 4: 全量验证与交付检查

**Files:**
- No source changes expected.

- [ ] **Step 1: 检查差异与空白**

```bash
git diff --check
```

预期：无错误。

- [ ] **Step 2: 运行项目规定的离线全量测试**

```bash
mvn -o -Dmaven.repo.local=C:/Users/l/.m2/repository test -Dfile.encoding=UTF-8
```

预期：退出码 `0`，测试无 failures/errors。

- [ ] **Step 3: 检查提交范围**

```bash
git status --short --branch
git diff origin/main...HEAD --name-only
```

预期：只包含规格/计划文档和 SpawnerService 源码/测试；本地探针、日志、构建产物不在提交中。
