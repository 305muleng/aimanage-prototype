package com.aiguanli.ai;

import java.util.List;

/** 独立验证用输入；外层及任务快照都必须显式标记为模拟数据。 */
public record CollaborationInput(Boolean simulation, List<DiscussionMessage> messages, ProgressSnapshot progress) {}
