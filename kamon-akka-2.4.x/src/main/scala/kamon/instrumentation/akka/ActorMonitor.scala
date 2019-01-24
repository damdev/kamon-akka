/*
 * =========================================================================================
 * Copyright © 2013-2018 the kamon project <http://kamon.io/>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */


package akka.kamon.instrumentation

import java.io.Closeable

import akka.actor.{ActorCell, ActorRef, ActorSystem, Cell}
import akka.dispatch.Envelope
import akka.kamon.instrumentation.ActorMonitors.{TracedMonitor, TrackedActor, TrackedRoutee}
import kamon.Kamon
import kamon.akka.Metrics
import kamon.akka.Metrics.{ActorGroupMetrics, ActorMetrics, RouterMetrics}
import kamon.context.Storage.Scope
import kamon.trace.Span
import org.aspectj.lang.ProceedingJoinPoint

trait ActorMonitor {
  def captureEnvelopeContext(): TimestampedContext
  def processMessage(pjp: ProceedingJoinPoint, envelopeContext: TimestampedContext, envelope: Envelope): AnyRef
  def processFailure(failure: Throwable): Unit
  def processDroppedMessage(count: Long): Unit
  def cleanup(): Unit

  //Kanela
  def processMessageStartTimestamp: Long
  def processMessageStart(envelopeContext: TimestampedContext, envelope: Envelope): AnyRef
  def processMessageEnd(envelopeContext: TimestampedContext, toClose: AnyRef, timestampBeforeProcessing: Long): Unit
}

object ActorMonitor {

  def createActorMonitor(cell: Cell, system: ActorSystem, ref: ActorRef, parent: ActorRef, actorCellCreation: Boolean): ActorMonitor = {
    val cellInfo = CellInfo.cellInfoFor(cell, system, ref, parent, actorCellCreation)

    if(cell.isInstanceOf[ActorCell]) {
      // Avoid increasing when in UnstartedCell
      Metrics.forSystem(system.name).activeActors.increment()
    }

    val monitor = if (cellInfo.isRouter)
      ActorMonitors.ContextPropagationOnly(cellInfo)
    else {
      if (cellInfo.isRoutee && cellInfo.isTracked)
        createRouteeMonitor(cellInfo)
      else
        createRegularActorMonitor(cellInfo)
    }

    if(cellInfo.isTraced) new TracedMonitor(cellInfo, monitor) else monitor
  }

  def createRegularActorMonitor(cellInfo: CellInfo): ActorMonitor = {
    if (cellInfo.isTracked || !cellInfo.trackingGroups.isEmpty) {
      val actorMetrics = if (cellInfo.isTracked) Some(Metrics.forActor(cellInfo.path, cellInfo.systemName, cellInfo.dispatcherName, cellInfo.actorOrRouterClass.getName)) else None
      new TrackedActor(actorMetrics, trackingGroupMetrics(cellInfo), cellInfo.actorCellCreation, cellInfo)
    } else {
      ActorMonitors.ContextPropagationOnly(cellInfo)
    }
  }

  def createRouteeMonitor(cellInfo: CellInfo): ActorMonitor = {
    val routerMetrics = Metrics.forRouter(cellInfo.path, cellInfo.systemName, cellInfo.dispatcherName, cellInfo.actorOrRouterClass.getName,
      cellInfo.routeeClass.map(_.getName).getOrElse("Unknown"))

    new TrackedRoutee(routerMetrics, trackingGroupMetrics(cellInfo), cellInfo.actorCellCreation, cellInfo)
  }

  private def trackingGroupMetrics(cellInfo: CellInfo): Seq[ActorGroupMetrics] = {
    cellInfo.trackingGroups.map { groupName =>
      Metrics.forGroup(groupName, cellInfo.systemName)
    }
  }
}

object ActorMonitors {



  class TracedMonitor(cellInfo: CellInfo, monitor: ActorMonitor) extends ActorMonitor {
    private val actorClassName = cellInfo.actorOrRouterClass.getName
    private val actorSimpleClassName = simpleClassName(cellInfo.actorOrRouterClass)

    override def captureEnvelopeContext(): TimestampedContext = {
      monitor.captureEnvelopeContext()
    }

    override def processMessage(pjp: ProceedingJoinPoint, envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {
      val messageSpan: Span = buildSpan(cellInfo, envelopeContext, envelope)
      val contextWithMessageSpan = envelopeContext.context.withKey(Span.ContextKey, messageSpan)

      try {
        monitor.processMessage(pjp, envelopeContext.copy(context = contextWithMessageSpan), envelope)
      } finally {
        messageSpan.finish()
      }
    }

    override val processMessageStartTimestamp: Long = 0L

    override def processMessageStart(envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {

      val messageSpan = buildSpan(cellInfo, envelopeContext, envelope)
      val contextWithMessageSpan = envelopeContext.context.withKey(Span.ContextKey, messageSpan)
      monitor.processMessageStart(envelopeContext.copy(context = contextWithMessageSpan), envelope)
      messageSpan
    }

    override def processMessageEnd(envelopeContext: TimestampedContext, span: AnyRef, timestampBeforeProcessing: Long): Unit =
      span.asInstanceOf[Span].finish()


    override def processFailure(failure: Throwable): Unit = monitor.processFailure(failure)
    override def processDroppedMessage(count: Long): Unit = monitor.processDroppedMessage(count)

    override def cleanup(): Unit = monitor.cleanup()

    private def buildSpan(cellInfo: CellInfo, envelopeContext: TimestampedContext, envelope: Envelope): Span = {
      val messageClass = simpleClassName(envelope.message.getClass)
      val parentSpan = envelopeContext.context.get(Span.ContextKey)
      val operationName = actorSimpleClassName + ": " + messageClass

      Kamon.buildSpan(operationName)
        .withFrom(Kamon.clock().toInstant(envelopeContext.nanoTime))
        .asChildOf(parentSpan)
        .disableMetrics()
        .start()
        .mark("akka.actor.dequeued")
        .tag("component", "akka.actor")
        .tag("akka.system", cellInfo.systemName)
        .tag("akka.actor.path", cellInfo.path)
        .tag("akka.actor.class", actorClassName)
        .tag("akka.actor.message-class", messageClass)
    }
  }


  def ContextPropagationOnly(cellInfo: CellInfo) = new ActorMonitor {
    private val processedMessagesCounter = Metrics.forSystem(cellInfo.systemName).processedMessagesByNonTracked

    def captureEnvelopeContext(): TimestampedContext = {
      val envelopeTimestamp = if(cellInfo.isTraced) Kamon.clock().nanos() else 0L
      TimestampedContext(envelopeTimestamp, Kamon.currentContext())
    }

    def processMessage(pjp: ProceedingJoinPoint, envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {
      processedMessagesCounter.increment()

      Kamon.withContext(envelopeContext.context) {
        pjp.proceed()
      }
    }

    def processFailure(failure: Throwable): Unit = {}
    def processDroppedMessage(count: Long): Unit = {}
    def cleanup(): Unit = {
      Metrics.forSystem(cellInfo.systemName).activeActors.decrement()
    }

    override val processMessageStartTimestamp: Long = 0L

    def processMessageStart(envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {

      processedMessagesCounter.increment()
      val scope = Kamon.storeContext(envelopeContext.context)
      scope
    }

    override def processMessageEnd(envelopeContext: TimestampedContext, scope: AnyRef, timestampBeforeProcessing: Long): Unit =
      scope.asInstanceOf[Scope].close()
  }

  def simpleClassName(cls: Class[_]): String = {
    // could fail, check SI-2034
    try { cls.getSimpleName } catch { case _: Throwable => cls.getName }
  }

  class TrackedActor(actorMetrics: Option[ActorMetrics], groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean, cellInfo: CellInfo)
    extends GroupMetricsTrackingActor(groupMetrics, actorCellCreation, cellInfo) {

    private val processedMessagesCounter = Metrics.forSystem(cellInfo.systemName).processedMessagesByTracked

    override def captureEnvelopeContext(): TimestampedContext = {
      actorMetrics.foreach { am =>
        am.mailboxSize.increment()
      }
      super.captureEnvelopeContext()
    }

    def processMessage(pjp: ProceedingJoinPoint, envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {
      val timestampBeforeProcessing = Kamon.clock().nanos()
      processedMessagesCounter.increment()

      try {
        Kamon.withContext(envelopeContext.context) {
          pjp.proceed()
        }
      } finally {
        val timestampAfterProcessing = Kamon.clock().nanos()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        actorMetrics.foreach { am =>
          am.processingTime.record(processingTime)
          am.timeInMailbox.record(timeInMailbox)
          am.mailboxSize.decrement()
        }
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    def processMessageStartTimestamp: Long = Kamon.clock().nanos()

    override def processMessageStart(envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {
      processedMessagesCounter.increment()
      val scope = Kamon.storeContext(envelopeContext.context)
      scope
    }

    override def processMessageEnd(envelopeContext: TimestampedContext, scope: AnyRef, timestampBeforeProcessing: Long): Unit = {
      try scope.asInstanceOf[Scope].close() finally {
        val timestampAfterProcessing = Kamon.clock().nanos()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        actorMetrics.foreach { am =>
          am.processingTime.record(processingTime)
          am.timeInMailbox.record(timeInMailbox)
          am.mailboxSize.decrement()
        }
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    override def processFailure(failure: Throwable): Unit = {
      actorMetrics.foreach { am =>
        am.errors.increment()
      }
      super.processFailure(failure: Throwable)
    }

    override def processDroppedMessage(count: Long): Unit = {
      // Dropped messages are only measured for routees
    }

    override def cleanup(): Unit = {
      super.cleanup()
      actorMetrics.foreach(_.cleanup())
    }
  }

  class TrackedRoutee(routerMetrics: RouterMetrics, groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean, cellInfo: CellInfo)
    extends GroupMetricsTrackingActor(groupMetrics, actorCellCreation, cellInfo) {

    routerMetrics.members.increment()
    private val processedMessagesCounter = Metrics.forSystem(cellInfo.systemName).processedMessagesByTracked



    override def captureEnvelopeContext(): TimestampedContext = {
      routerMetrics.pendingMessages.increment()
      super.captureEnvelopeContext()
    }

    def processMessage(pjp: ProceedingJoinPoint, envelopeContext: TimestampedContext, envelope: Envelope): AnyRef = {
      val timestampBeforeProcessing = Kamon.clock().nanos()
      processedMessagesCounter.increment()

      try {
        Kamon.withContext(envelopeContext.context) {
          pjp.proceed()
        }
      } finally {
        val timestampAfterProcessing = Kamon.clock().nanos()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        routerMetrics.processingTime.record(processingTime)
        routerMetrics.timeInMailbox.record(timeInMailbox)
        routerMetrics.pendingMessages.decrement()
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    def processMessageStartTimestamp(): Long = Kamon.clock().nanos()

    override def processMessageStart(envelopeContext: TimestampedContext, envelope: Envelope): AnyRef  = {
      processedMessagesCounter.increment()
      val scope = Kamon.storeContext(envelopeContext.context)
      scope
    }

    override def processMessageEnd(envelopeContext: TimestampedContext, scope: AnyRef, timestampBeforeProcessing: Long): Unit = {
      try scope.asInstanceOf[Scope].close() finally {
        val timestampAfterProcessing = Kamon.clock().nanos()
        val timeInMailbox = timestampBeforeProcessing - envelopeContext.nanoTime
        val processingTime = timestampAfterProcessing - timestampBeforeProcessing

        routerMetrics.processingTime.record(processingTime)
        routerMetrics.timeInMailbox.record(timeInMailbox)
        routerMetrics.pendingMessages.decrement()
        recordProcessMetrics(processingTime, timeInMailbox)
      }
    }

    override def processFailure(failure: Throwable): Unit = {
      routerMetrics.errors.increment()
      super.processFailure(failure)
    }


    override def processDroppedMessage(count: Long): Unit = {
      routerMetrics.pendingMessages.decrement(count)
    }

    override def cleanup(): Unit = {
      super.cleanup()
      routerMetrics.members.decrement()
    }
  }

  abstract class GroupMetricsTrackingActor(groupMetrics: Seq[ActorGroupMetrics], actorCellCreation: Boolean, cellInfo: CellInfo) extends ActorMonitor {
    if (actorCellCreation) {
      groupMetrics.foreach { gm =>
        gm.members.increment()
      }
    }

    def captureEnvelopeContext(): TimestampedContext = {
      groupMetrics.foreach { gm =>
        gm.pendingMessages.increment()
      }

      TimestampedContext(Kamon.clock().nanos(), Kamon.currentContext())
    }

    def processFailure(failure: Throwable): Unit = {
      groupMetrics.foreach { gm =>
        gm.errors.increment()
      }
    }

    protected def recordProcessMetrics(processingTime: Long, timeInMailbox: Long): Unit = {
      groupMetrics.foreach { gm =>
        gm.processingTime.record(processingTime)
        gm.timeInMailbox.record(timeInMailbox)
        gm.pendingMessages.decrement()
      }
    }

    def cleanup(): Unit = {
      Metrics.forSystem(cellInfo.systemName).activeActors.decrement()
      if (actorCellCreation) {
        groupMetrics.foreach { gm =>
          gm.members.decrement()
        }
      }
    }
  }
}