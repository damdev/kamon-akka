package akka.kamon.instrumentation.kanela.interceptor

import java.util.concurrent.Callable

import akka.kamon.instrumentation.RouterInstrumentationAware
import kanela.agent.libs.net.bytebuddy.implementation.bind.annotation.{RuntimeType, SuperCall, This}

object RoutedActorCellSendMethodInterceptor {
  @RuntimeType
  def onEnter(@This cell: Object, @SuperCall callable: Callable[AnyRef]): AnyRef = {
    cell.asInstanceOf[RouterInstrumentationAware].routerInstrumentation.processMessage(callable)
  }
}