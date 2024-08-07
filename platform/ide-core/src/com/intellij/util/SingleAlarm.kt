// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.intellij.util

import com.intellij.codeWithMe.ClientId
import com.intellij.codeWithMe.asContextElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.ComponentManagerEx
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.Alarm.ThreadToUse
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private val LOG: Logger = logger<SingleAlarm>()

/**
 * Use a [kotlinx.coroutines.flow.Flow] with [kotlinx.coroutines.flow.debounce] and [kotlinx.coroutines.flow.sample] instead.
 * Alarm is deprecated.
 * Allows scheduling a single `Runnable` instance ([task]) to be executed after a specific time interval on a specific thread.
 * [request] adds a request if it's not scheduled yet, i.e., it does not delay execution of the request
 * [cancelAndRequest] cancels the current request and schedules a new one instead, i.e., it delays execution of the request
 *
 */
class SingleAlarm @JvmOverloads constructor(
  private val task: Runnable,
  private val delay: Int,
  parentDisposable: Disposable?,
  threadToUse: ThreadToUse = ThreadToUse.SWING_THREAD,
  modalityState: ModalityState? = if (threadToUse == ThreadToUse.SWING_THREAD) ModalityState.nonModal() else null,
  coroutineScope: CoroutineScope? = null,
) : Disposable {
  // it is a supervisor coroutine scope
  private val taskCoroutineScope: CoroutineScope

  private val LOCK = Any()

  // guarded by LOCK
  private var currentJob: Job? = null

  private val inEdt: Boolean = threadToUse == ThreadToUse.SWING_THREAD

  constructor(task: Runnable, delay: Int, threadToUse: ThreadToUse, parentDisposable: Disposable)
    : this(
    task = task,
    delay = delay,
    parentDisposable = parentDisposable,
    threadToUse = threadToUse,
    modalityState = if (threadToUse == ThreadToUse.SWING_THREAD) ModalityState.nonModal() else null,
  )

  constructor(task: Runnable, delay: Int) : this(
    task = task,
    delay = delay,
    parentDisposable = null,
    threadToUse = ThreadToUse.SWING_THREAD,
    modalityState = ModalityState.nonModal(),
  )

  init {
    if (inEdt && modalityState == null) {
      throw IllegalArgumentException("modalityState must be not null if threadToUse == ThreadToUse.SWING_THREAD")
    }

    var context = modalityState?.asContextElement() ?: EmptyCoroutineContext
    ClientId.currentOrNull?.let {
      context += it.asContextElement()
    }

    val coroutineContext = Dispatchers.Default.limitedParallelism(1) + context
    if (coroutineScope == null) {
      val app = ApplicationManager.getApplication()
      if (app == null) {
        LOG.error("Do not use an alarm in an early executing code")
        @Suppress("SSBasedInspection")
        taskCoroutineScope = CoroutineScope(SupervisorJob() + coroutineContext)
      }
      else {
        @Suppress("UsagesOfObsoleteApi")
        taskCoroutineScope = (app as ComponentManagerEx).getCoroutineScope().childScope("SingleAlarm (task=$task)", coroutineContext)
      }

      parentDisposable?.let {
        Disposer.register(it, this)
      }
    }
    else {
      taskCoroutineScope = coroutineScope.childScope("SingleAlarm (task=$task)", coroutineContext)
    }
  }

  companion object {
    fun pooledThreadSingleAlarm(delay: Int, parentDisposable: Disposable, task: () -> Unit): SingleAlarm {
      return SingleAlarm(
        task = task,
        delay = delay,
        threadToUse = ThreadToUse.POOLED_THREAD,
        parentDisposable = parentDisposable,
      )
    }

    @JvmStatic
    fun singleAlarm(delay: Int, coroutineScope: CoroutineScope, task: Runnable): SingleAlarm {
      return SingleAlarm(
        task = task,
        delay = delay,
        parentDisposable = null,
        threadToUse = ThreadToUse.POOLED_THREAD,
        coroutineScope = coroutineScope,
      )
    }
  }

  override fun dispose() {
    cancel()
    taskCoroutineScope.cancel()
  }

  val isDisposed: Boolean
    get() = !taskCoroutineScope.isActive

  val isEmpty: Boolean
    get() = synchronized(LOCK) { currentJob == null }

  @TestOnly
  fun waitForAllExecuted(timeout: Long, timeUnit: TimeUnit) {
    assert(ApplicationManager.getApplication().isUnitTestMode)

    val currentJob = currentJob ?: return
    @Suppress("RAW_RUN_BLOCKING")
    runBlocking {
      try {
        withTimeout(timeUnit.toMillis(timeout)) {
          currentJob.join()
        }
      }
      catch (e: TimeoutCancellationException) {
        // compatibility - throw TimeoutException as before
        throw TimeoutException(e.message)
      }
    }
  }

  @JvmOverloads
  fun request(forceRun: Boolean = false, delay: Int = this@SingleAlarm.delay) {
    val effectiveDelay = if (forceRun) 0 else delay.toLong()
    synchronized(LOCK) {
      if (currentJob != null) {
        return
      }

      currentJob = taskCoroutineScope.launch {
        delay(effectiveDelay)
        var taskContext: CoroutineContext = NonCancellable
        if (inEdt) {
          taskContext += Dispatchers.EDT
        }
        withContext(taskContext) {
          try {
            task.run()
          }
          catch (e: CancellationException) {
            throw e
          }
          catch (e: Throwable) {
            LOG.error(e)
          }
        }
      }
    }
  }

  /**
   * Cancel doesn't interrupt already running task.
   */
  fun cancel() {
    synchronized(LOCK) {
      currentJob?.also {
        currentJob = null
      }
    }?.cancel()
  }

  /**
   * Cancel doesn't interrupt already running task.
   */
  @JvmOverloads
  fun cancelAndRequest(forceRun: Boolean = false) {
    cancel()
    request(forceRun = forceRun)
  }

  @Deprecated("Use cancel")
  fun cancelAllRequests(): Int {
    val currentJob = synchronized(LOCK) {
      currentJob?.also {
        currentJob = null
      }
    } ?: return 0
    currentJob.cancel()
    return 1
  }
}
