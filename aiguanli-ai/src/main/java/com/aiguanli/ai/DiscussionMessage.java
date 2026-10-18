package com.aiguanli.ai;

/** 一条待解析的聊天消息，id 用于回溯原文。 */
public record DiscussionMessage(String id, String speaker, String content) {
}
