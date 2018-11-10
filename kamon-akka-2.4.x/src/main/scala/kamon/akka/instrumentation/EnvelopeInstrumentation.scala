package akka.kamon.instrumentation

import kamon.context.Context
import org.aspectj.lang.annotation.{Aspect, DeclareMixin}

case class TimestampedContext(nanoTime: Long, @transient context: Context, i: Int)
object TimestampedContext {
  def apply(nanoTime: Long, @transient context: Context): TimestampedContext = {
    if (context == null) {
      try {
        println("ES NULLL")
        throw new NotImplementedError()
      } catch {
        case t: Throwable => t.printStackTrace()
      }
      new TimestampedContext(nanoTime, Context.Empty, 0)
    } else {
      new TimestampedContext(nanoTime, context, 0)
    }
  }
}


trait InstrumentedEnvelope extends Serializable {
  def timestampedContext(): TimestampedContext
  def setTimestampedContext(timestampedContext: TimestampedContext): Unit
}

object InstrumentedEnvelope {
  def apply(): InstrumentedEnvelope = new InstrumentedEnvelope {
    var timestampedContext: TimestampedContext = _

    def setTimestampedContext(timestampedContext: TimestampedContext): Unit =
      this.timestampedContext = timestampedContext
  }
}

@Aspect
class EnvelopeContextIntoEnvelopeMixin {

  @DeclareMixin("akka.dispatch.Envelope")
  def mixinInstrumentationToEnvelope: InstrumentedEnvelope = InstrumentedEnvelope()
}