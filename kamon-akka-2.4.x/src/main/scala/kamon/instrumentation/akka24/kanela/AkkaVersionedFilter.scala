package kamon.instrumentation.akka24.kanela

import java.util.function.Supplier

import kanela.agent.api.instrumentation.InstrumentationDescription
import kanela.agent.api.instrumentation.classloader.ClassLoaderRefiner

object AkkaVersionedFilter {

  implicit def toJavaSupplier[A](f: ⇒ A): Supplier[A] = new Supplier[A]() {
    def get: A = f
  }

  def filterAkkaVersion(builder: InstrumentationDescription.Builder): InstrumentationDescription.Builder = {
    builder.withClassLoaderRefiner(ClassLoaderRefiner.mustContains("akka.event.ActorClassification"))
  }

}
