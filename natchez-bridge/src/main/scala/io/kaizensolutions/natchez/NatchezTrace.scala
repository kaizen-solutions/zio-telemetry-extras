package io.kaizensolutions.natchez

import cats.{~>, Applicative}
import cats.arrow.FunctionK
import cats.effect.Resource
import io.opentelemetry.api.common.*
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.Context
import natchez.Span.SpanKind as NSpanKind
import org.typelevel.ci.CIString
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.*
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.net.URI
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import _root_.natchez.{Kernel as NKernel, Span as NSpan, Trace as NTrace, TraceValue}

object NatchezTrace {

  def layer[R: Tag]: ZLayer[Tracing & TraceContextPropagator, Nothing, NTrace[RIO[R, *]]] =
    ZLayer.fromFunction((t: Tracing, tp: TraceContextPropagator) => make[R](t, tp))

  val layer: ZLayer[Tracing & TraceContextPropagator, Nothing, NTrace[Task]] =
    ZLayer.fromFunction((t: Tracing, tp: TraceContextPropagator) => make[Any](t, tp))

  def make[R](
      t: Tracing,
      tp: TraceContextPropagator = TraceContextPropagator.default
  ): NTrace[RIO[R, *]] = new NTrace[RIO[R, *]] {
    // The span is installed by `spanScoped` for the duration of the scope, so the effects handed
    // back to natchez need no rewrapping.
    private val identityK: RIO[R, *] ~> RIO[R, *] = FunctionK.id[RIO[R, *]]

    override def put(fields: (String, TraceValue)*): ZIO[R, Throwable, Unit] = {
      val attrs = attributes(fields)
      if (attrs.isEmpty) ZIO.unit
      else t.getCurrentSpanUnsafe.map(_.setAllAttributes(attrs)).unit
    }

    override def log(fields: (String, TraceValue)*): ZIO[R, Throwable, Unit] =
      t.addEventWithAttributes("event", attributes(fields))

    override def log(event: String): ZIO[R, Throwable, Unit] =
      t.addEvent(event)

    override def attachError(
        err: Throwable,
        fields: (String, TraceValue)*
    ): ZIO[R, Throwable, Unit] =
      t.getCurrentSpanUnsafe
        .map(_.recordException(err, attributes(fields)))
        .unit

    override def kernel: ZIO[R, Throwable, NKernel] = {
      val carrier = kernelCarrier()
      t.injectSpan(tp, carrier).as(NKernel(carrier.kernel.result()))
    }

    override def spanR(
        name: String,
        options: NSpan.Options
    ): Resource[RIO[R, *], RIO[R, *] ~> RIO[R, *]] =
      Resource.scopedZIO(
        t.spanScoped(
          spanName = name,
          spanKind = spanKind(options.spanKind),
          links = links(tp, options)
        ).as(identityK)
      )

    override def span[A](name: String, options: NSpan.Options)(
        k: ZIO[R, Throwable, A]
    ): ZIO[R, Throwable, A] =
      t.span(
        spanName = name,
        spanKind = spanKind(options.spanKind),
        links = links(tp, options)
      )(k)

    override def traceId: ZIO[R, Throwable, Option[String]] =
      t.getCurrentSpanContextUnsafe.map(sc => Option.when(sc.isValid)(sc.getTraceId))

    override def spanId(implicit F: Applicative[RIO[R, *]]): ZIO[R, Throwable, Option[String]] =
      t.getCurrentSpanContextUnsafe.map(sc => Option.when(sc.isValid)(sc.getSpanId))

    override def traceUri: ZIO[R, Throwable, Option[URI]] = ZIO.none
  }

  private type KernelBuilder = mutable.Builder[(CIString, String), Map[CIString, String]]

  /** An [[OutgoingContextCarrier]] that accumulates a natchez [[NKernel]] directly, sparing the
    * intermediate mutable `Map[String, String]` and the conversion pass over it.
    */
  private def kernelCarrier(): OutgoingContextCarrier[KernelBuilder] =
    new OutgoingContextCarrier[KernelBuilder] {
      override val kernel: KernelBuilder = Map.newBuilder

      override def set(carrier: KernelBuilder, key: String, value: String): Unit = {
        carrier += (CIString(key) -> value)
        ()
      }
    }

  private val kernelGetter: TextMapGetter[NKernel] = new TextMapGetter[NKernel] {
    override def keys(carrier: NKernel): java.lang.Iterable[String] =
      carrier.toHeaders.keys.view.map(_.toString).asJava

    override def get(carrier: NKernel, key: String): String =
      carrier.toHeaders.getOrElse(CIString(key), null)
  }

  private def links(tp: TraceContextPropagator, options: NSpan.Options): List[SpanContext] =
    if (options.links.isEmpty) Nil
    else options.links.iterator.flatMap(kernelToSpanContext(tp, _)).toList

  /** Extraction starts from [[Context.root]] rather than the ambient context: a link describes the
    * span the kernel points at and nothing else, so a kernel that carries no usable trace context
    * yields no link instead of one pointing back at the current span.
    */
  private def kernelToSpanContext(
      tp: TraceContextPropagator,
      kernel: NKernel
  ): Option[SpanContext] = {
    val extracted =
      Span.fromContext(tp.instance.extract(Context.root(), kernel, kernelGetter)).getSpanContext

    if (!extracted.isValid) None
    else if (extracted.isRemote) Some(extracted)
    else
      Some(
        SpanContext.createFromRemoteParent(
          extracted.getTraceId,
          extracted.getSpanId,
          extracted.getTraceFlags,
          extracted.getTraceState
        )
      )
  }

  private def putNumber(b: AttributesBuilder, k: String, n: Number): AttributesBuilder =
    n match {
      case _: java.lang.Byte | _: java.lang.Short | _: java.lang.Integer | _: java.lang.Long |
          _: java.math.BigInteger =>
        b.put(k, n.longValue)
      case _ => // Float, Double, BigDecimal, and anything else
        b.put(k, n.doubleValue)
    }

  private def attributes(fields: Seq[(String, TraceValue)]): Attributes =
    if (fields.isEmpty) Attributes.empty()
    else {
      val b = Attributes.builder()
      fields.foreach { case (k, v) =>
        v match {
          case TraceValue.BooleanValue(value) => b.put(k, value)
          case TraceValue.NumberValue(value)  => putNumber(b, k, value)
          case TraceValue.StringValue(value)  => b.put(k, value)
        }
      }
      b.build()
    }

  private def spanKind(in: NSpanKind): SpanKind = in match {
    case NSpanKind.Internal => SpanKind.INTERNAL
    case NSpanKind.Client   => SpanKind.CLIENT
    case NSpanKind.Server   => SpanKind.SERVER
    case NSpanKind.Producer => SpanKind.PRODUCER
    case NSpanKind.Consumer => SpanKind.CONSUMER
  }

}
