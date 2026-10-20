package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 与业务修改加入同一个事务，失败时记录和修改一起回滚。 */
@Service
public class TaskJournal {
    private final TaskEntryRepository entries;
    private final UserRepository users;
    public TaskJournal(TaskEntryRepository entries, UserRepository users) {
        this.entries = entries;
        this.users = users;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Task task, Long actorId, String message) {
        User actor = users.findById(actorId).orElseThrow(() -> new BusinessException("账号不存在"));
        entries.save(new TaskEntry(task, actor, "CHANGE", message));
    }
}
