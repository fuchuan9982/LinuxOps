package com.linuxops.task;

import java.time.Instant;

public record TaskResult(String taskId, String hostId, TaskState state,
                         String message, Integer exitCode,
                         Instant startedAt, Instant finishedAt) { }
