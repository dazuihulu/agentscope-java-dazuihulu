# ChatController.stream() 调用链分析

## 概述

`POST /api/agents/{agentId}/chat/stream` 是 DataAgent 的核心 SSE 流式聊天接口，负责接收用户消息，路由到目标 agent，并以 SSE 事件流返回实时回复。

---

## 完整调用链

```
POST /api/agents/{agentId}/chat/stream
         |
         v
+---------------------------------------------------------------------+
| 1. ChatController.stream()                                          |
|   - 校验用户权限 (guard.require)                                     |
|   - 解析 conversationId (会话标识)                                    |
|   - 处理斜杠命令 (/new, /reset, /dock_*) -> 短路返回                  |
|   - 订阅 ToolEventBus 获取工具调用事件                                 |
|   - 调用 executeChat() 执行 agent                                    |
|   - 合并 toolEvents + agentReply -> SSE 流返回客户端                  |
+----------------------------------+----------------------------------+
                                   |
                                   v
+---------------------------------------------------------------------+
| 2. ChatController.executeChat(userId, agentId, message, convId)      |
|   - shapeInboundMessages(): 注入用户语言偏好                          |
|   - 构建 InboundMessage (channelId, peer, messages, preferredAgentId) |
|   - 调用 chatUiChannel.dispatch(inbound)                             |
|   - doOnSuccess: 记录 usage 用量统计                                  |
+----------------------------------+----------------------------------+
                                   |
                                   v
+---------------------------------------------------------------------+
| 3. ChatUiChannel.dispatch(InboundMessage)                           |
|   - router.resolveRoute(config, message) -> RouteResult              |
|   - gateway.run(context, messages, outbound, runtimeContext, msg)    |
+----------------------------------+----------------------------------+
                                   |
          +------------------------+------------------------+
          |                                                 |
          v                                                 v
+--------------------------+               +--------------------------------+
| 4. ChannelRouter.        |               | 5. HarnessGateway.run()         |
|    resolveRoute()        |               |   - 从 extra["agentId"] 解析    |
|                          |               |     目标 agent                  |
|  绑定优先级 (高->低):     |               |   - resolveAgent(requestedId)   |
|  1. explicit             |               |   - resolveSessionId(gateKey)   |
|     (preferredAgentId)   |               |   - persistRoute(sessionId,     |
|  2. peer 精确匹配         |               |     outboundAddress)            |
|  3. guild + roles        |               |   - buildRuntimeContext()       |
|  4. channel default      |               |   - withGatedTurn() -> 串行化   |
|  5. global default       |               |     同 session 的请求            |
|                          |               |   - ha.call(msgs, runtimeCtx)   |
|  输出: RouteResult       |               |                                 |
|  (agentId, MsgContext,   |               |                                 |
|   matchedBy, outbound)   |               |                                 |
+--------------------------+               +---------------+-----------------+
                                                           |
                                                           v
+---------------------------------------------------------------------+
| 6. HarnessAgent.call(List<Msg>, RuntimeContext)                      |
|   - ensureSessionDefaults(): 注入 sessionId, userId 等               |
|   - wrappedCall():                                                  |
|       +-- sandboxLifecycleMw.acquireForCall()  沙箱生命周期          |
|       +-- delegate.call(msgs, effective)       实际 agent 执行       |
|       +-- sandboxLifecycleMw.releaseForCall()  沙箱释放              |
|       +-- onErrorResume: 上下文溢出时触发 compaction 压缩             |
+----------------------------------+----------------------------------+
                                   |
                                   v
+---------------------------------------------------------------------+
| 7. AgentDelegate.call() (ReActAgent / ToolAgent 等)                  |
|   - 运行 agent 主循环: think -> act -> observe -> repeat             |
|   - 调用 LLM 模型 (如 DashScope / OpenAI)                            |
|   - 执行工具调用 (tool_call -> tool_result)                          |
|   - 返回最终 Msg 回复                                                 |
+----------------------------------+----------------------------------+
                                   |
                                   v  返回 Mono<Msg>
                                   |
+---------------------------------------------------------------------+
| 8. 回到 ChatController.stream() - 组装 SSE 响应                       |
|                                                                     |
|   agentReply 流:                                                     |
|     - sse("token", { type:"token", data: replyText })               |
|     - sse("done",  { type:"done",  sessionKey: conversationId })    |
|                                                                     |
|   toolEvents 流 (并行):                                              |
|     - sse("tool_call",  { type:"tool_call",  toolName, toolInput }) |
|     - sse("tool_result",{ type:"tool_result", toolName, toolResult})|
|                                                                     |
|   Flux.merge(toolEvents, agentReply) -> SSE 流返回前端               |
+---------------------------------------------------------------------+
```

---

## 关键设计点

| 环节 | 职责 | 关键类 |
|------|------|--------|
| 权限 | 校验用户是否有 RUN 权限 | AgentAccessGuard |
| 路由 | 确定消息发给哪个 agent | ChannelRouter |
| 会话 | 通过 gateKey 管理 session 生命周期 | SessionAgentManager |
| 串行化 | 同一 session 的请求排队执行 | HarnessGateway.withGatedTurn() |
| 沙箱 | acquire/release 管理沙箱生命周期 | SandboxLifecycleMiddleware |
| 执行 | ReAct 循环 + LLM 调用 + 工具执行 | AgentDelegate |
| 事件 | 工具调用实时推送给前端 | ToolEventBus |
| 统计 | 记录每次调用的耗时 | UsageStore |

---

## 路由绑定优先级

ChannelRouter 按以下优先级确定目标 agent：

1. **explicit** - `InboundMessage.preferredAgentId()` 直接指定
2. **peer** - 精确匹配 `Peer.key()` (如 `"direct:u_42"`)
3. **guild + roles** - guild 匹配 + 用户角色匹配
4. **guild** - guild 匹配 (无角色限制)
5. **team** - team 匹配
6. **account** - account 匹配
7. **channel** - channel 匹配
8. **channel default** - `ChannelConfig.defaultAgentId()`
9. **global default** - 全局默认 agent

---

## SSE 事件类型

| 事件类型 | 说明 |
|---------|------|
| `token` | 文本回复片段 |
| `tool_call` | agent 调用工具 |
| `tool_result` | 工具执行结果 |
| `done` | 本轮对话结束，携带 sessionKey |
| `error` | 错误终止 |

---

## 斜杠命令

| 命令 | 说明 |
|------|------|
| `/new` | 创建新会话，返回新 sessionKey |
| `/reset` | 清除当前会话历史 |
| `/identity` | 查看已绑定的身份链接 |
| `/dock_<channel> <id>` | 绑定外部渠道身份 |
