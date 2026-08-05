package io.kaizensolutions.natchez

import cats.{~>, Applicative}
import cats.effect.syntax.all.*
import cats.effect.Resource
import io.opentelemetry.api.OpenTelemetry
import zio.{Trace as ZioTrace, *}
import zio.interop.catz.*

import java.net.URI

import _root_.natchez.*
import _root_.natchez.opentelemetry.OpenTelemetry as NatchezOpenTelemetry

object NatchezTrace {

  /** An implementation of a natchez.Trace based on `FiberRef` given a root span. Adapted from the
    * core natchez library
    * https://github.com/typelevel/natchez/blob/main/modules/core/shared/src/main/scala/Trace.scala
    *
    * @param rootSpan
    * @param zt
    * @return
    */
  def rioTrace[R](
      rootSpan: Span[RIO[R, *]]
  )(implicit zt: ZioTrace): RIO[Scope, Trace[RIO[R, *]]] =
    FiberRef.make(rootSpan).map { ref =>
      new Trace[RIO[R, *]] {

        override def put(fields: (String, TraceValue)*): RIO[R, Unit] =
          ref.get.flatMap(_.put(fields*))

        override def log(fields: (String, TraceValue)*): RIO[R, Unit] =
          ref.get.flatMap(_.log(fields*))

        override def log(event: String): RIO[R, Unit] =
          ref.get.flatMap(_.log(event))

        override def attachError(err: Throwable, fields: (String, TraceValue)*): RIO[R, Unit] =
          ref.get.flatMap(_.attachError(err, fields*))

        override def kernel: RIO[R, Kernel] = ref.get.flatMap(_.kernel)

        override def spanR(
            name: String,
            options: Span.Options
        ): Resource[RIO[R, *], RIO[R, *] ~> RIO[R, *]] = for {
          parent <- Resource.eval[RIO[R, *], Span[RIO[R, *]]](ref.get)
          child  <- parent.span(name, options)
        } yield new (RIO[R, *] ~> RIO[R, *]) {
          def apply[A](fa: RIO[R, A]): RIO[R, A] =
            (ref.get: RIO[R, Span[RIO[R, *]]])
              .flatMap[R, Throwable, A] { old =>
                (ref.set(child): RIO[R, Unit])
                  .bracket(_ => fa.tapError[R, Throwable](e => child.attachError(e)))(_ =>
                    ref.set(old)
                  )
              }
        }

        override def span[A](name: String, options: Span.Options)(k: RIO[R, A]): RIO[R, A] =
          spanR(name, options).use(_(k))

        override def traceId: RIO[R, Option[String]] = ref.get.flatMap(_.traceId)

        override def spanId(implicit F: Applicative[RIO[R, *]]): RIO[R, Option[String]] =
          ref.get.flatMap(_.spanId)

        override def traceUri: RIO[R, Option[URI]] = ref.get.flatMap(_.traceUri)

      }
    }

  def rioTraceForEntryPoint[R](ep: EntryPoint[RIO[R, *]]): RIO[Scope, Trace[RIO[R, *]]] =
    rioTrace(Span.makeRoots(ep))

  def layer[R: Tag]: ZLayer[OpenTelemetry & R, Throwable, Trace[RIO[R, *]]] =
    ZLayer.scoped[OpenTelemetry & R](
      for {
        otel       <- ZIO.service[OpenTelemetry]
        entryPoint <- NatchezOpenTelemetry.entryPointFor[RIO[R, *]](otel)
        trace      <- rioTraceForEntryPoint(entryPoint)
      } yield trace
    )

  val layer: ZLayer[OpenTelemetry, Throwable, Trace[Task]] =
    ZLayer.scoped[OpenTelemetry](
      for {
        otel       <- ZIO.service[OpenTelemetry]
        entryPoint <- NatchezOpenTelemetry.entryPointFor[Task](otel)
        trace      <- rioTraceForEntryPoint(entryPoint)
      } yield trace
    )
}
