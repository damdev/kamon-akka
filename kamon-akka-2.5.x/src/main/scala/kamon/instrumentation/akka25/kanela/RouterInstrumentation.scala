package kamon.instrumentation.akka25.kanela

import kanela.agent.scala.KanelaInstrumentation
import kamon.instrumentation.akka25.kanela.AkkaVersionedFilter._
import akka.kamon.instrumentation.akka25.kanela.advisor.{RoutedActorCellConstructorAdvisor, RoutedActorRefConstructorAdvisor, SendMessageMethodAdvisor, SendMessageMethodAdvisorForRouter}
import kamon.instrumentation.akka25.kanela.mixin.{RoutedActorCellInstrumentationMixin, RoutedActorRefInstrumentationMixin}

class RouterInstrumentation extends KanelaInstrumentation {

  /**
    * Instrument:
    *
    * akka.routing.RoutedActorCell::constructor
    * akka.routing.RoutedActorCell::sendMessage
    *
    * Mix:
    *
    * akka.routing.RoutedActorCell with kamon.akka.instrumentation.mixin.RouterInstrumentationAware
    *
    */
  forTargetType("akka.routing.RoutedActorCell") { builder ⇒
    filterAkkaVersion(builder)
      .withMixin(classOf[RoutedActorCellInstrumentationMixin])
      .withAdvisorFor(Constructor, classOf[RoutedActorCellConstructorAdvisor])
      .withAdvisorFor(method("sendMessage").and(takesArguments(1)), classOf[SendMessageMethodAdvisor])
      .withAdvisorFor(method("sendMessage").and(takesArguments(1)), classOf[SendMessageMethodAdvisorForRouter])
      .build()
  }

  /**
    * Instrument:
    *
    * akka.routing.RoutedActorRef::constructor
    * akka.routing.RoutedActorRef::sendMessage
    *
    * Mix:
    *
    * akka.routing.RoutedActorRef with kamon.akka.instrumentation.mixin.RoutedActorRefAccessor
    *
    */
  forTargetType("akka.routing.RoutedActorRef") { builder ⇒
    filterAkkaVersion(builder)
      .withMixin(classOf[RoutedActorRefInstrumentationMixin])
      .withAdvisorFor(Constructor, classOf[RoutedActorRefConstructorAdvisor])
      .build()
  }
}
