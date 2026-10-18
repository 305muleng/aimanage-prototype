package com.aiguanli.ai;

import java.util.List;

/** 字段对应待人工审核的需求草稿，不代表已创建业务记录。 */
public record RequirementDecomposition(
        List<UserStory> userStories,
        List<DevelopmentTask> developmentTasks,
        List<String> openQuestions,
        List<String> optionalSuggestions) {

    public record UserStory(String id, String title, String role, String goal, String benefit,
            List<String> acceptanceCriteria) {}

    public record DevelopmentTask(String id, String title, String description, List<String> storyIds) {}
}
