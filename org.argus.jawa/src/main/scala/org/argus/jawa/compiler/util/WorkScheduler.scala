/*
 * Copyright (c) 2017. Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Detailed contributors are listed in the CONTRIBUTOR.md
 */

package org.argus.jawa.compiler.util

import scala.collection.mutable

class WorkScheduler {

  type Action = () => Unit

  private val todo = new mutable.Queue[Action]
  private val throwables = new mutable.Queue[Throwable]
  private val interruptReqs = new mutable.Queue[InterruptReq]

  /** Called from server: block until one of todo list, throwables or interruptReqs is nonempty */
  def waitForMoreWork(): Unit = synchronized {
    while (todo.isEmpty && throwables.isEmpty && interruptReqs.isEmpty) { wait() }
  }

  /** called from Server: test whether one of todo list, throwables, or InterruptReqs is nonempty */
  def moreWork: Boolean = synchronized {
    todo.nonEmpty || throwables.nonEmpty || interruptReqs.nonEmpty
  }

  /** Called from server: get first action in todo list, and pop it off */
  def nextWorkItem(): Option[Action] = synchronized {
    if (todo.isEmpty) None else Some(todo.dequeue())
  }

  def dequeueAll[T](f: Action => Option[T]): Seq[T] = synchronized {
    todo.dequeueAll(a => f(a).isDefined).map(a => f(a).get)
  }

  def dequeueAllInterrupts(f: InterruptReq => Unit): Unit = synchronized {
    interruptReqs.dequeueAll { iq => f(iq); true }
  }

  /** Called from server: return optional exception posted by client
   *  Reset to no exception.
   */
  def pollThrowable(): Option[Throwable] = synchronized {
    if (throwables.isEmpty)
      None
    else {
      val result = Some(throwables.dequeue())
      if (throwables.nonEmpty)
        postWorkItem { () => }
      result
    }
  }

  def pollInterrupt(): Option[InterruptReq] = synchronized {
    if (interruptReqs.isEmpty) None else Some(interruptReqs.dequeue())
  }

  /** Called from client: have interrupt executed by server and return result */
  def doQuickly[A](op: () => A): A = {
    val ir = askDoQuickly(op)
    ir.getResult
  }

  def askDoQuickly[A](op: () => A): InterruptReq { type R = A } = {
    val ir = new InterruptReq {
      type R = A
      val todo: () => A = op
    }
    synchronized {
      interruptReqs enqueue ir
      notify()
    }
    ir
  }

  /** Called from client: have action executed by server */
  def postWorkItem(action: Action): Unit = synchronized {
    todo enqueue action
    notify()
  }

  /** Called from client: cancel all queued actions */
  def cancelQueued(): Unit = synchronized {
    todo.clear()
  }

  /** Called from client:
   *  Require an exception to be thrown on next poll.
   */
  def raise(exc: Throwable): Unit = synchronized {
    throwables enqueue exc
    postWorkItem { new EmptyAction }
  }
}

class EmptyAction extends (() => Unit) {
  def apply() {}
}
