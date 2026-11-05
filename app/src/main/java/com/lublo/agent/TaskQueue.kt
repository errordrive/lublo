package com.lublo.agent

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous task queue: background tasks can be queued (from a plugin, a scheduled trigger,
 * or the model deciding to continue). The queue runs one task at a time so the agent doesn't
 * collide with an active user session.
 */
class TaskQueue(private val svc: AgentService) {
    private val queue = ArrayBlockingQueue<Runnable>(32)
    private val worker = Thread {
        while (true) {
            val task = queue.take()
            try {
                task.run()
            } catch (e: Exception) {
                svc.say("A background task failed.", "Task error: ${e.message}")
            }
        }
    }.apply { isDaemon = true; start() }

    /** Enqueue a task. The agent's voice is free to keep talking while this runs. */
    fun enqueue(task: () -> Unit) {
        queue.offer(task)
        svc.status("Task queued: working in the background")
    }

    fun isEmpty(): Boolean = queue.isEmpty()
    fun size(): Int = queue.size()

    fun shutdown() {
        worker.interrupt()
    }
}
