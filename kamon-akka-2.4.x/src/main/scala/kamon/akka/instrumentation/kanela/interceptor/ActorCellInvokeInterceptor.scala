package akka.kamon.instrumentation.kanela.interceptor


import java.util.concurrent.Callable

import akka.actor.Cell
import akka.dispatch.Envelope
import akka.kamon.instrumentation.InstrumentedEnvelope
import akka.kamon.instrumentation.kanela.advisor.ActorInstrumentationSupport
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.{Argument, RuntimeType, SuperCall, This}

object ActorCellInvokeInterceptor extends ActorInstrumentationSupport {
  @RuntimeType
  def onEnter(@This cell: Cell, @Argument(0) envelope: Object, @SuperCall callable: Callable[AnyRef]): AnyRef = {
    val a = envelope.asInstanceOf[InstrumentedEnvelope].timestampedContext()
    if(a == null) {
      println("ENVELOPE NULLLLLLL")
    }
    actorInstrumentation(cell).processMessage2(callable, a, envelope.asInstanceOf[Envelope])
  }
}